package cy.agorise.bitsybitshareswallet.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.navigation.Navigation
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.jakewharton.rxbinding3.widget.textChanges
import cy.agorise.bitsybitshareswallet.R
import cy.agorise.bitsybitshareswallet.utils.Constants
import cy.agorise.bitsybitshareswallet.utils.toast
import cy.agorise.graphenej.*
import cy.agorise.graphenej.api.ConnectionStatusUpdate
import cy.agorise.graphenej.api.calls.GetAccounts
import cy.agorise.graphenej.api.calls.GetKeyReferences
import cy.agorise.graphenej.models.AccountProperties
import cy.agorise.graphenej.models.JsonRpcResponse
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.android.synthetic.main.fragment_import_brainkey.*
import org.bitcoinj.core.ECKey
import java.util.ArrayList
import java.util.concurrent.TimeUnit

class ImportBrainkeyFragment : BaseAccountFragment() {
    companion object {
        private const val TAG = "ImportBrainkeyActivity"
    }

    /** User account associated with the key derived from the brainkey that the user just typed in */
    private var mUserAccount: UserAccount? = null

    /**
     * List of user account candidates, this is required in order to allow the user to select a single
     * user account in case one key (derived from the brainkey) controls more than one account.
     */
    private var mUserAccountCandidates: List<UserAccount>? = null

    private var mKeyReferencesAttempts = 0

    private var keyReferencesRequestId: Long? = null
    private var getAccountsRequestId: Long? = null

    private var isPINValid = false
    private var isPINConfirmationValid = false
    private var isBrainKeyValid = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Remove up navigation icon from the toolbar
        val toolbar: Toolbar? = activity?.findViewById(R.id.toolbar)
        toolbar?.navigationIcon = null

        return inflater.inflate(R.layout.fragment_import_brainkey, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Use RxJava Debounce to update the PIN error only after the user stops writing for > 500 ms
        mDisposables.add(
            tietPin.textChanges()
                .skipInitialValue()
                .debounce(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { validatePIN() }
        )

        // Use RxJava Debounce to update the PIN Confirmation error only after the user stops writing for > 500 ms
        mDisposables.add(
            tietPinConfirmation.textChanges()
                .skipInitialValue()
                .debounce(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { validatePINConfirmation() }
        )

        // Use RxJava Debounce to update the BrainKey error only after the user stops writing for > 500 ms
        mDisposables.add(
            tietBrainKey.textChanges()
                .skipInitialValue()
                .debounce(500, TimeUnit.MILLISECONDS)
                .map { it.toString().trim() }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { validateBrainKey(it) }
        )

        btnImport.isEnabled = false
        btnImport.setOnClickListener { verifyBrainKey(false) }

        btnCreate.setOnClickListener (
            Navigation.createNavigateOnClickListener(R.id.create_account_action)
        )
    }

    private fun validatePIN() {
        val pin = tietPin.text.toString()

        if (pin.length < Constants.MIN_PIN_LENGTH) {
            tilPin.error = getString(R.string.error__pin_too_short)
            isPINValid = false
        } else {
            tilPin.isErrorEnabled = false
            isPINValid = true
        }

        validatePINConfirmation()
    }

    private fun validatePINConfirmation() {
        val pinConfirmation = tietPinConfirmation.text.toString()

        if (pinConfirmation != tietPin.text.toString()) {
            tilPinConfirmation.error = getString(R.string.error__pin_mismatch)
            isPINConfirmationValid = false
        } else {
            tilPinConfirmation.isErrorEnabled = false
            isPINConfirmationValid = true
        }

        enableDisableImportButton()
    }

    private fun validateBrainKey(brainKey: String) {
        if (brainKey.isEmpty() || !brainKey.contains(" ") || brainKey.split(" ").size !in 12..16) {
            tilBrainKey.error = getString(R.string.error__enter_correct_brainkey)
            isBrainKeyValid = false
        } else {
            tilBrainKey.isErrorEnabled = false
            isBrainKeyValid = true
        }

        enableDisableImportButton()
    }

    private fun enableDisableImportButton() {
        btnImport.isEnabled =  (isPINValid && isPINConfirmationValid && isBrainKeyValid)
    }

    /**
     * Method that will verify the provided brain key, and if valid will retrieve the account information
     * associated to the user id.
     *
     * This method will perform a network lookup to look which accounts use the public key associated
     * with the user provided brainkey.
     *
     * Some sources use brainkeys in capital letters, while others use lowercase. The activity should
     * initially call this method with the 'switchCase' parameter as false, in order to try the
     * brainkey as it was provided by the user.
     *
     * But in case this lookup fails, it is expected that the activity makes another attempt. This time
     * with the 'switchCase' argument set to true.
     *
     * If both attempts fail, then we can be certain that the provided brainkey is not currently
     * associated with any account.
     *
     * @param switchCase Whether to switch the case used in the brainkey or not.
     */
    private fun verifyBrainKey(switchCase: Boolean) {
        //showDialog("", getString(R.string.importing_your_wallet))
        val brainKey = tietBrainKey.text.toString()
        // Should we switch the brainkey case?
        if (switchCase) {
            if (Character.isUpperCase(brainKey.toCharArray()[brainKey.length - 1])) {
                // If the last character is an uppercase, we assume the whole brainkey
                // was given in capital letters and turn it to lowercase
                getAccountFromBrainkey(brainKey.toLowerCase())
            } else {
                // Otherwise we turn the whole brainkey to capital letters
                getAccountFromBrainkey(brainKey.toUpperCase())
            }
        } else {
            // If no case switching should take place, we perform the network call with
            // the brainkey as it was provided to us.
            getAccountFromBrainkey(brainKey)
        }
    }

    /**
     * Method that will send a network request asking for all the accounts that make use of the
     * key derived from a give brain key.
     *
     * @param brainKey The brain key the user has just typed
     */
    private fun getAccountFromBrainkey(brainKey: String) {
        mBrainKey = BrainKey(brainKey, 0)
        val address = Address(ECKey.fromPublicOnly(mBrainKey!!.privateKey.pubKey))
        Log.d(TAG, String.format("Brainkey would generate address: %s", address.toString()))
        keyReferencesRequestId = mNetworkService?.sendMessage(GetKeyReferences(address), GetKeyReferences.REQUIRED_API)
    }

    override fun handleJsonRpcResponse(response: JsonRpcResponse<*>) {
        if (response.id == keyReferencesRequestId) {
            handleBrainKeyAccountReferences(response.result)
        } else if (response.id == getAccountsRequestId) {
            handleAccountProperties(response.result)
        }
    }

    override fun handleConnectionStatusUpdate(connectionStatusUpdate: ConnectionStatusUpdate) {
        Log.d(TAG, "handleConnectionStatusUpdate. code: " + connectionStatusUpdate.updateCode)
    }

    /**
     * Handles the response from the NetworkService when the app asks for the accounts that are controlled by a
     * specified BrainKey
     */
    private fun handleBrainKeyAccountReferences(result: Any?) {
        if (result !is List<*>)
            return

        val list = result[0] as? List<*> ?: return

        if (list[0] !is UserAccount)
            return

        val resp = result as List<List<UserAccount>>
        val accountList: List<UserAccount> = resp[0].distinct()

        if (accountList.isEmpty()) {
            if (mKeyReferencesAttempts == 0) {
                mKeyReferencesAttempts++
                verifyBrainKey(true)
            } else {
                context?.toast(getString(R.string.error__invalid_brainkey))
            }
        } else if (accountList.size == 1) {
            // If we only found one account linked to this key, then we just proceed
            // trying to find out the account name
            mUserAccount = accountList[0]
            getAccountsRequestId =
                    mNetworkService?.sendMessage(GetAccounts(mUserAccount), GetAccounts.REQUIRED_API)
        } else {
            // If we found more than one account linked to this key, we must also
            // find out the account names, but the procedure is a bit different in
            // that after having those, we must still ask the user to decide which
            // account should be imported.
            mUserAccountCandidates = accountList
            getAccountsRequestId = mNetworkService?.sendMessage(
                GetAccounts(mUserAccountCandidates),
                GetAccounts.REQUIRED_API
            )
        }
    }

    /**
     * Handles the response from the NetworkService when the app asks for the AccountProperties of a list of
     * Accounts controlled by the given BrainKey
     */
    private fun handleAccountProperties(result: Any?) {
        if (result is List<*> && result[0] is AccountProperties) {
            val accountPropertiesList = result as List<AccountProperties>
            if (accountPropertiesList.size > 1) {
                val candidates = ArrayList<String>()
                for (accountProperties in accountPropertiesList) {
                    candidates.add(accountProperties.name)
                }
                MaterialDialog(context!!)
                    .title(R.string.dialog__account_candidates_title)
                    .message(R.string.dialog__account_candidates_content)
                    .listItemsSingleChoice (items = candidates, initialSelection = -1) { _, index, _ ->
                        if (index >= 0) {
                            // If one account was selected, we keep a reference to it and
                            // store the account properties
                            mUserAccount = mUserAccountCandidates!![index]
                            onAccountSelected(accountPropertiesList[index], tietPin.text.toString())
                        }
                    }
                    .positiveButton(android.R.string.ok)
                    .negativeButton(android.R.string.cancel) {
                        mKeyReferencesAttempts = 0
                    }
                    .cancelable(false)
                    .show()
            } else if (accountPropertiesList.size == 1) {
                onAccountSelected(accountPropertiesList[0], tietPin.text.toString())
            } else {
                context?.toast(getString(R.string.error__try_again))
            }
        }
    }
}