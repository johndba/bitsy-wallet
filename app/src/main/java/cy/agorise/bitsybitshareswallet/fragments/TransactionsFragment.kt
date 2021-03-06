package cy.agorise.bitsybitshareswallet.fragments

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsMultiChoice
import com.crashlytics.android.Crashlytics
import com.jakewharton.rxbinding3.appcompat.queryTextChangeEvents
import cy.agorise.bitsybitshareswallet.R
import cy.agorise.bitsybitshareswallet.adapters.TransfersDetailsAdapter
import cy.agorise.bitsybitshareswallet.database.joins.TransferDetail
import cy.agorise.bitsybitshareswallet.models.FilterOptions
import cy.agorise.bitsybitshareswallet.utils.*
import cy.agorise.bitsybitshareswallet.viewmodels.TransactionsViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.fragment_transactions.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shows the list of transactions as well as options to filter and export those transactions
 * to PDF and CSV files
 */
class TransactionsFragment : Fragment(), FilterOptionsDialog.OnFilterOptionsSelectedListener {

    companion object {
        private const val TAG = "TransactionsFragment"

        private const val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION = 100
    }

    private lateinit var mViewModel: TransactionsViewModel

    private var mDisposables = CompositeDisposable()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)

        return inflater.inflate(R.layout.fragment_transactions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Crashlytics.setString(Constants.CRASHLYTICS_KEY_LAST_SCREEN, TAG)

        val userId = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.KEY_CURRENT_ACCOUNT_ID, "") ?: ""

        val transfersDetailsAdapter = TransfersDetailsAdapter(context!!)
        rvTransactions.adapter = transfersDetailsAdapter
        rvTransactions.layoutManager = LinearLayoutManager(context)

        // Configure TransactionsViewModel to fetch the transaction history
        mViewModel = ViewModelProviders.of(this).get(TransactionsViewModel::class.java)

        mViewModel.getFilteredTransactions(userId).observe(this,
            Observer<List<TransferDetail>> { transactions ->
                if (transactions.isEmpty()) {
                    rvTransactions.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvTransactions.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE

                    val shouldScrollUp = transactions.size - transfersDetailsAdapter.itemCount == 1
                    transfersDetailsAdapter.replaceAll(transactions)

                    // Scroll to the top only if the difference between old and new items is 1
                    // which most likely means a new transaction was received/sent.
                    if (shouldScrollUp)
                        rvTransactions.scrollToPosition(0)
                }
        })

        // Set custom touch listener to handle bounce/stretch effect
        val bounceTouchListener = BounceTouchListener(rvTransactions)
        rvTransactions.setOnTouchListener(bounceTouchListener)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_transactions, menu)

        // Adds listener for the SearchView
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView
        mDisposables.add(
            searchView.queryTextChangeEvents()
                .skipInitialValue()
                .debounce(500, TimeUnit.MILLISECONDS)
                .map { it.queryText.toString().toLowerCase() }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    mViewModel.setFilterQuery(it)
                }
        )

        // Adjust SearchView width to avoid pushing other menu items out of the screen
        searchView.maxWidth = getScreenWidth(activity) * 3 / 5
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_filter -> {
                val filterOptionsDialog = FilterOptionsDialog()
                val args = Bundle()
                args.putParcelable(FilterOptionsDialog.KEY_FILTER_OPTIONS, mViewModel.getFilterOptions())
                filterOptionsDialog.arguments = args
                filterOptionsDialog.show(childFragmentManager, "filter-options-tag")
                true
            }
            R.id.menu_export -> {
                verifyStoragePermission()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Returns the screen width in pixels for the given [FragmentActivity]
     */
    private fun getScreenWidth(activity: FragmentActivity?): Int {
        if (activity == null)
            return 200

        val size = Point()
        activity.windowManager.defaultDisplay.getSize(size)
        return size.x
    }

    /**
     * Gets called when the user selects some filter options in the [FilterOptionsDialog] and wants to apply them.
     */
    override fun onFilterOptionsSelected(filterOptions: FilterOptions) {
        mViewModel.applyFilterOptions(filterOptions)
    }

    /** Verifies that the storage permission has been granted before attempting to generate the export options */
    private fun verifyStoragePermission() {
        if (ContextCompat.checkSelfPermission(activity!!, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            // Permission is not already granted
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION)
        } else {
            // Permission is already granted
            showExportOptionsDialog()
        }
    }

    /** Received the result of the storage permission request and if it was accepted then shows the export options
     * dialog, but if it was not accepted then shows a toast explaining that the permission is necessary to generate
     * the export options */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                showExportOptionsDialog()
            } else {
                context?.toast(getString(R.string.msg__storage_permission_necessary_export))
            }
            return
        }
    }

    private fun showExportOptionsDialog() {
        MaterialDialog(context!!).show {
            title(R.string.title_export_transactions)
            listItemsMultiChoice(R.array.export_options, initialSelection = intArrayOf(0,1)) { _, indices, _ ->
                val exportPDF = indices.contains(0)
                val exportCSV = indices.contains(1)
                exportFilteredTransactions(exportPDF, exportCSV)
            }
            positiveButton(R.string.title_export)
        }
    }

    /** Creates the export procedures for PDF and CSV, depending on the user selection. */
    private fun exportFilteredTransactions(exportPDF: Boolean, exportCSV: Boolean) {
        // Verifies the BiTSy folder exists in the external storage and if it doesn't then it tries to create it
        val dir = File(Environment.getExternalStorageDirectory(), Constants.EXTERNAL_STORAGE_FOLDER)
        if (!dir.exists()) {
            if (!dir.mkdirs())
                return
        }

        mViewModel.getFilteredTransactionsOnce()?.let { filteredTransactions ->
            if (exportPDF)
                activity?.let { PDFGeneratorTask(it).execute(filteredTransactions) }

            if (exportCSV)
                activity?.let { CSVGenerationTask(it).execute(filteredTransactions) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!mDisposables.isDisposed) mDisposables.dispose()
    }
}
