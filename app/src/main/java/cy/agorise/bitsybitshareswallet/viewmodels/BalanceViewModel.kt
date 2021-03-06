package cy.agorise.bitsybitshareswallet.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import cy.agorise.bitsybitshareswallet.database.entities.Balance
import cy.agorise.bitsybitshareswallet.repositories.BalanceRepository

class BalanceViewModel(application: Application) : AndroidViewModel(application) {
    private var mRepository = BalanceRepository(application)

    internal fun getMissingAssetIds(): LiveData<List<String>> {
        return mRepository.getMissingAssetIds()
    }

    fun insertAll(balances: List<Balance>) {
        mRepository.insertAll(balances)
    }
}