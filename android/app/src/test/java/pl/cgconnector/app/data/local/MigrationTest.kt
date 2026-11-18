package pl.cgconnector.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Migracja 1→2 na prawdziwym pliku bazy: tworzymy bazę w wersji 1 dokładnie wg `schemas/.../1.json`,
 * a Room po otwarciu uruchamia migrację i sam sprawdza, czy wynik zgadza się ze schematem wersji 2
 * (przy niezgodności rzuca IllegalStateException).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_keepsActivitiesAndAddsNewTables() =
        runTest {
            createVersion1Database()

            val db =
                Room
                    .databaseBuilder(context, CgDatabase::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            try {
                val activity = db.activityDao().getById(7)
                assertThat(activity?.name).isEqualTo("Bieg")
                assertThat(activity?.startTime).isEqualTo(Instant.ofEpochMilli(1_763_361_000_000))
                assertThat(activity?.paceMinPerKm).isNull()
                assertThat(db.statsDao().getSyncStatus()).isNull()
                assertThat(db.statsDao().latestVo2MaxDate()).isNull()
            } finally {
                db.close()
            }
        }

    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `activities` (`id` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                    "`name` TEXT, `start_time` INTEGER NOT NULL, `duration_sec` INTEGER NOT NULL, " +
                    "`distance_m` REAL, `avg_hr` INTEGER, `max_hr` INTEGER, `elevation_gain_m` REAL, " +
                    "`avg_power_w` INTEGER, `avg_cadence_spm` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_start_time` ON `activities` (`start_time`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_type` ON `activities` (`type`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '2ba53902759e57748aee13d75189d8e0')",
            )
            db.execSQL(
                "INSERT INTO activities (id, type, name, start_time, duration_sec) " +
                    "VALUES (7, 'running', 'Bieg', 1763361000000, 1800)",
            )
            db.version = 1
        }
    }
}
