package cy.agorise.bitsybitshareswallet.processors

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import cy.agorise.bitsybitshareswallet.database.entities.Transfer
import cy.agorise.bitsybitshareswallet.models.HistoricalOperationEntry
import cy.agorise.bitsybitshareswallet.repositories.AuthorityRepository
import cy.agorise.bitsybitshareswallet.repositories.TransferRepository
import cy.agorise.bitsybitshareswallet.utils.Constants
import cy.agorise.bitsybitshareswallet.utils.CryptoUtils
import cy.agorise.graphenej.*
import cy.agorise.graphenej.api.ConnectionStatusUpdate
import cy.agorise.graphenej.api.android.NetworkService
import cy.agorise.graphenej.api.android.RxBus
import cy.agorise.graphenej.api.calls.GetRelativeAccountHistory
import cy.agorise.graphenej.errors.ChecksumException
import cy.agorise.graphenej.models.JsonRpcResponse
import cy.agorise.graphenej.models.OperationHistory
import cy.agorise.graphenej.operations.TransferOperation
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Consumer
import io.reactivex.schedulers.Schedulers
import org.bitcoinj.core.DumpedPrivateKey
import org.bitcoinj.core.ECKey
import java.util.*
import javax.crypto.AEADBadTagException

/**
 * This class is responsible for loading the local database with all past transfer operations of the
 * currently selected account.
 *
 * The procedure used to load the database in 3 steps:
 *
 * 1- Load all transfer operations
 * 2- Load all missing times
 * 3- Load all missing equivalent times
 *
 * Since the 'get_relative_account_history' will not provide either timestamps nor equivalent values
 * for every transfer, we must first load all historical transfer operations, and then proceed to
 * handle those missing columns.
 */
class TransfersLoader(private var mContext: Context?): ServiceConnection {

    private val TAG = this.javaClass.simpleName

    /** Constant that specifies if we are on debug mode */
    private val DEBUG = false

    /* Constant used to fix the number of historical transfers to fetch from the network in one batch */
    private val HISTORICAL_TRANSFER_BATCH_SIZE = 100

    private val RESPONSE_GET_RELATIVE_ACCOUNT_HISTORY = 0

    private var mDisposables = CompositeDisposable()

    /* Current user account */
    private var mCurrentAccount: UserAccount? = null

    /** Variable holding the current user's private key in the WIF format */
    private var wifKey: String? = null

    /** Repository to access and update Transfers */
    private var transferRepository: TransferRepository? = null

    /** Repository to access and update Authorities */
    private var authorityRepository: AuthorityRepository? = null

    /* Network service connection */
    private var mNetworkService: NetworkService? = null

    /* Counter used to keep track of the transfer history batch count */
    private var historicalTransferCount = 0

    // Used to keep track of the current state TODO this may not be needed
    private var mState = State.IDLE

    /**
     * Flag used to keep track of the NetworkService binding state
     */
    private var mShouldUnbindNetwork: Boolean = false

    private var lastId: Long = 0

    // Map used to keep track of request and response id pairs
    private val responseMap = HashMap<Long, Int>()

    /**
     * Enum class used to keep track of the current state of the loader
     */
    private enum class State {
        IDLE,
        LOADING_MISSING_TIMES,
        LOADING_EQ_VALUES,
        CANCELLED,
        FINISHED
    }

    init {
        transferRepository = TransferRepository(mContext!!)
        authorityRepository = AuthorityRepository(mContext!!)

        val pref = PreferenceManager.getDefaultSharedPreferences(mContext)
        val userId = pref.getString(Constants.KEY_CURRENT_ACCOUNT_ID, "")
        if (userId != "") {
            mCurrentAccount = UserAccount(userId)
            mDisposables.add(authorityRepository!!.getWIF(userId!!, AuthorityType.MEMO.ordinal)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { encryptedWIF ->
                    try {
                        wifKey = CryptoUtils.decrypt(mContext!!, encryptedWIF)
                    } catch (e: AEADBadTagException) {
                        Log.e(TAG, "AEADBadTagException. Class: " + e.javaClass + ", Msg: " + e.message)
                    }

                }
            )
            mDisposables.add(RxBus.getBusInstance()
                .asFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(Consumer { message ->
                    if (mState == State.FINISHED) return@Consumer
                    if (message is JsonRpcResponse<*>) {
                        if (message.result is List<*>) {
                            if (responseMap.containsKey(message.id)) {
                                val responseType = responseMap[message.id]
                                when (responseType) {
                                    RESPONSE_GET_RELATIVE_ACCOUNT_HISTORY -> handleOperationList(message.result as List<OperationHistory>)
                                }
                                responseMap.remove(message.id)
                            }
                        }
                    } else if (message is ConnectionStatusUpdate) {
                        if (message.updateCode == ConnectionStatusUpdate.DISCONNECTED) {
                            // If we got a disconnection notification, we should clear our response map, since
                            // all its stored request ids will now be reset
                            responseMap.clear()
                        }
                    }
                })
            )
        } else {
            // If there is no current user, we should not do anything
            mState = State.CANCELLED
        }

        onStart()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        mShouldUnbindNetwork = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        // We've bound to LocalService, cast the IBinder and get LocalService instance
        val binder = service as NetworkService.LocalBinder
        mNetworkService = binder.service

        // Start the transfers update
        startTransfersUpdateProcedure()
    }

    private fun onStart() {
        if (mState != State.CANCELLED) {
            val intent = Intent(mContext, NetworkService::class.java)
            if (mContext!!.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                mShouldUnbindNetwork = true
            } else {
                Log.e(TAG, "Binding to the network service failed.")
            }
        }
    }

    /**
     * Starts the procedure that will try to update the 'transfers' table
     */
    private fun startTransfersUpdateProcedure() {
        if (DEBUG) {
            // If we are in debug mode, we first erase all entries in the 'transfer' table
            transferRepository!!.deleteAll()
        }
        mDisposables.add(
            transferRepository!!.getCount()
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { transferCount ->
                     if (transferCount > 0) {
                        // If we already have some transfers in the database, we might want to skip the request
                        // straight to the last batch
                        historicalTransferCount = Math.floor((transferCount /
                                HISTORICAL_TRANSFER_BATCH_SIZE).toDouble()).toInt()
                    }
                    // Retrieving account transactions
                    loadNextOperationsBatch()
                }
        )
    }

    /**
     * Handles a freshly obtained list of OperationHistory instances. This is how the full node
     * answers our 'get_relative_account_history' API call.
     *
     * This response however, has to be processed before being stored in the local database.
     *
     * @param operationHistoryList List of OperationHistory instances
     */
    private fun handleOperationList(operationHistoryList: List<OperationHistory>) {
        historicalTransferCount++

        val insertedCount = transferRepository!!.insertAll(processOperationList(operationHistoryList))
        // TODO return number of inserted rows
//        Log.d(TAG, String.format("Inserted count: %d, list size: %d", insertedCount, operationHistoryList.size))
        if (/* insertedCount == 0 && */ operationHistoryList.isEmpty()) {
            onDestroy()
        } else {

            // If we inserted more than one operation, we cannot yet be sure we've reached the
            // end of the operation list, so we issue another call to the 'get_relative_account_history'
            // API call
            loadNextOperationsBatch()
        }
    }

    /**
     * Method used to issue a new 'get_relative_account_history' API call. This is expected to retrieve
     * at most HISTORICAL_TRANSFER_BATCH_SIZE operations.
     */
    private fun loadNextOperationsBatch() {
        val stop = historicalTransferCount * HISTORICAL_TRANSFER_BATCH_SIZE
        val start = stop + HISTORICAL_TRANSFER_BATCH_SIZE
        lastId = mNetworkService!!.sendMessage(
            GetRelativeAccountHistory(
                mCurrentAccount,
                stop,
                HISTORICAL_TRANSFER_BATCH_SIZE,
                start
            ), GetRelativeAccountHistory.REQUIRED_API
        )
        responseMap[lastId] = RESPONSE_GET_RELATIVE_ACCOUNT_HISTORY
    }

    /**
     * Method that will transform a list of OperationHistory instances to a list of
     * HistoricalOperationEntry.
     *
     * The HistoricalOperationEntry class is basically a wrapper around the OperationHistory class
     * provided by the Graphenej library. It is used to better reflect what we store in the internal
     * database for every transfer and expands the OperationHistory class basically adding
     * two things:
     *
     * 1- A timestamp
     * 2- An AssetAmount instance to represent the equivalent value in a fiat value
     *
     * @param operations    List of OperationHistory instances
     * @return              List of HistoricalOperationEntry instances
     */
    private fun processOperationList(operations: List<OperationHistory>): List<Transfer> {
        val transfers = ArrayList<Transfer>()

        if (wifKey == null) {
            // In case of key storage corruption, we give up on processing this list of operations
            return transfers
        }
        val memoKey = DumpedPrivateKey.fromBase58(null, wifKey!!).key
        val publicKey = PublicKey(ECKey.fromPublicOnly(memoKey.pubKey))
        val myAddress = Address(publicKey.key)


        for (historicalOp in operations) {
            if (historicalOp.operation == null || historicalOp.operation !is TransferOperation) {
                // Some historical operations might not be transfer operations.
                // As of right now non-transfer operations get deserialized as null
                continue
            }

            val entry = HistoricalOperationEntry()
            val op = historicalOp.operation as TransferOperation

            val memo = op.memo
            if (memo.byteMessage != null) {
                val destinationAddress = memo.destination
                try {
                    if (destinationAddress.toString() == myAddress.toString()) {
                        val decryptedMessage = Memo.decryptMessage(memoKey, memo.source, memo.nonce, memo.byteMessage)
                        memo.plaintextMessage = decryptedMessage
                    }
                } catch (e: ChecksumException) {
                    Log.e(TAG, "ChecksumException. Msg: " + e.message)
                } catch (e: NullPointerException) {
                    // This is expected in case the decryption fails, so no need to log this event.
                    Log.e(TAG, "NullPointerException. Msg: " + e.message)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception while decoding memo. Msg: " + e.message)
                }
            }

            val transfer = Transfer(
                historicalOp.objectId,
                historicalOp.blockNum,
                entry.timestamp,
                op.fee.amount.toLong(),
                op.fee.asset.objectId,
                op.from.objectId,
                op.to.objectId,
                op.assetAmount.amount.toLong(),
                op.assetAmount.asset.objectId,
                memo.plaintextMessage
            )

            transfers.add(transfer)
        }
        return transfers
    }

    private fun onDestroy() {
        Log.d(TAG, "Destroying TransfersLoader")
        if (!mDisposables.isDisposed) mDisposables.dispose()
        if (mShouldUnbindNetwork) {
            mContext!!.unbindService(this)
            mShouldUnbindNetwork = false
        }
        mContext = null
    }
}
