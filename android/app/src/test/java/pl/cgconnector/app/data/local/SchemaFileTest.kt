package pl.cgconnector.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Zacommitowany schemat bieżącej wersji (`app/schemas/.../<version>.json`) zgadza się z kodem: ten sam
 * identity hash i te same polecenia CREATE TABLE. Na nim opiera się następna AutoMigration.
 */
@RunWith(AndroidJUnit4::class)
class SchemaFileTest {
    @Test
    fun committedSchemaMatchesDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CgDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val version = sqlite.version
            val schema =
                Json
                    .parseToJsonElement(File("schemas/${CgDatabase::class.java.name}/$version.json").readText())
                    .jsonObject
                    .getValue("database")
                    .jsonObject

            val hash = sqlite.query("SELECT identity_hash FROM room_master_table").use { it.moveToFirst(); it.getString(0) }
            assertThat(schema.getValue("identityHash").jsonPrimitive.content).isEqualTo(hash)

            schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val actual =
                    sqlite.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                        it.moveToFirst()
                        it.getString(0)
                    }
                val expected =
                    entity
                        .getValue("createSql")
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table)
                        .replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE")
                assertThat(actual).isEqualTo(expected)
            }
        } finally {
            db.close()
        }
    }
}
