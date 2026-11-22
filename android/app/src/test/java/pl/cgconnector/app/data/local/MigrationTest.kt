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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.workout.QueueStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Migracje na prawdziwym pliku bazy: tworzymy bazę w starej wersji dokładnie wg `schemas/.../<n>.json`,
 * a Room po otwarciu uruchamia migracje i sam sprawdza, czy wynik zgadza się z bieżącym schematem
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

    @Test
    fun migrate2To3_keepsDataAndAddsWorkoutQueue() =
        runTest {
            createDatabaseFromSchema(version = 2)
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL(
                    "INSERT INTO activities (id, type, name, start_time, duration_sec) " +
                        "VALUES (7, 'running', 'Bieg', 1763361000000, 1800)",
                )
            }

            val db =
                Room
                    .databaseBuilder(context, CgDatabase::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertThat(db.activityDao().getById(7)?.name).isEqualTo("Bieg")
                val dao = db.workoutQueueDao()
                assertThat(dao.getAll()).isEmpty()
                val id =
                    dao.insert(
                        QueuedWorkoutEntity(
                            clientId = "00000000-0000-4000-8000-000000000001",
                            name = "Bieg testowy",
                            sport = "running",
                            definitionJson = "{}",
                            date = LocalDate.of(2030, 1, 1),
                            status = QueueStatus.PENDING.apiValue,
                            createdAt = Instant.EPOCH,
                        ),
                    )
                assertThat(dao.getById(id)?.name).isEqualTo("Bieg testowy")
            } finally {
                db.close()
            }
        }

    @Test
    fun migrate3To4_keepsActivitiesAndAddsEmptyTag() =
        runTest {
            createDatabaseFromSchema(version = 3)
            context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL(
                    "INSERT INTO activities (id, type, name, start_time, duration_sec) " +
                        "VALUES (7, 'running', 'Bieg', 1763361000000, 1800)",
                )
            }

            val db =
                Room
                    .databaseBuilder(context, CgDatabase::class.java, dbName)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertThat(db.activityDao().getById(7)?.tag).isNull()
                db.activityDao().updateTag(7, "intervals")
                assertThat(db.activityDao().getById(7)?.toDomain()?.tag).isEqualTo(ActivityTag.INTERVALS)
            } finally {
                db.close()
            }
        }

    /**
     * Pusta baza dokładnie wg wyeksportowanego schematu (`app/schemas/.../<version>.json`, katalog roboczy
     * testów to moduł `app`), razem z identity hash w `room_master_table`.
     */
    private fun createDatabaseFromSchema(version: Int) {
        val schema = File("schemas/${CgDatabase::class.java.name}/$version.json").readText()
        val database = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            database.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val statements =
                    listOf(entity.getValue("createSql")) +
                        (entity["indices"]?.jsonArray?.map { it.jsonObject.getValue("createSql") } ?: emptyList())
                statements.forEach { db.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
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
