package com.brewprint.data

import android.content.Context
import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reads each file in an export, by name. */
internal fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun repository(): Pair<BrewRepository, Context> {
        val directory = temporary.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        return BrewRepository(context) to context
    }

    @Test fun `an export holds the app's details, its data and its log`() {
        val output = ByteArrayOutputStream()
        writeExport(output, "Brewprint 0.1.0 (1)\n", """{"beans":[]}""".toByteArray(), "11-24 08:30:00.000 W Brewprint: Sync failed\n")
        val files = unzip(output.toByteArray())
        assertEquals(listOf("about.txt", "data.json", "log.txt"), files.keys.toList())
        assertEquals("Brewprint 0.1.0 (1)\n", files["about.txt"])
        assertEquals("""{"beans":[]}""", files["data.json"])
        assertEquals("11-24 08:30:00.000 W Brewprint: Sync failed\n", files["log.txt"])
        assertEquals("brewprint-export-2026-11-24.zip", exportFileName(LocalDate.of(2026, 11, 24)))
    }

    @Test fun `sign-in tokens are removed from the exported log`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl"
        val log = """
            Headers: [Authorization=[Bearer $jwt], apikey=[sb_publishable_abc]]
            Authorization: bearer opaque-token-123
            {"access_token":"abc.def","refresh_token":"r3fr3sh","expires_in":3600}
            refresh_token=r3fr3sh&grant=1
            {\"refresh_token\":\"esc4ped\",\"provider_refresh_token\":\"pr0vider\"}
            refresh-token: hyph3n
            {"access_token":"$jwt","token_type":"bearer"}
            W Brewprint: Sync failed
        """.trimIndent()
        val redacted = redactSecrets(log)
        listOf(jwt, "opaque-token-123", "abc.def", "r3fr3sh", "esc4ped", "pr0vider", "hyph3n").forEach { assertFalse(it, it in redacted) }
        assertTrue(redacted, "Authorization=[Bearer [redacted]], apikey" in redacted)
        assertTrue("bearer [redacted]" in redacted)
        assertTrue(""""refresh_token":"[redacted]"""" in redacted)
        assertTrue(redacted, """{"access_token":"[redacted]","token_type":"bearer"}""" in redacted)
        assertTrue(redacted, """{\"refresh_token\":\"[redacted]\",""" in redacted)
        assertTrue("W Brewprint: Sync failed" in redacted)
        assertTrue("sb_publishable_abc" in redacted)

        val output = ByteArrayOutputStream()
        writeExport(output, "", ByteArray(0), log)
        assertFalse(jwt in unzip(output.toByteArray()).getValue("log.txt"))
    }

    @Test fun `the exported data is everything the app keeps, and reads back the same`() {
        val (repository, _) = repository()
        repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow()
        repository.upsertRecipe(Recipe(name = "Filter", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))).getOrThrow()
        repository.startDraft().getOrThrow()
        val exported = repository.exportData().toString(Charsets.UTF_8)
        assertEquals(repository.state.value, Json.decodeFromString<AppState>(exported))
        assertTrue("pretty printed", exported.lines().size > 10)
    }

    @Test fun `saved data the app couldn't read is exported as it is`() {
        val (repository, context) = repository()
        repository.upsertBeans(Beans(name = "Ethiopia", roaster = "Sample")).getOrThrow()
        repository.awaitWrites()
        File(context.filesDir, "brewprint.json").writeText("broken saved data")
        assertEquals("broken saved data", BrewRepository(context).exportData().toString(Charsets.UTF_8))
    }
}
