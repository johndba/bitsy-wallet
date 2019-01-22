package cy.agorise.bitsybitshareswallet.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import cy.agorise.bitsybitshareswallet.database.entities.Teller

@Dao
interface TellerDao {
    @Insert
    fun insert(teller: Teller)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tellers: List<Teller>)
}