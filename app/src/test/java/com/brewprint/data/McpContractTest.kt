package com.brewprint.data

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MCP server (supabase/functions/brewprint-mcp) reads brew records as this app syncs them, using the fixture below.
 * When this fails, a model change reached synced JSON: update the fixture, then brews.ts and its tests to match.
 */
class McpContractTest {
    @Test fun `the MCP server's brew fixture matches the synced brew record exactly`() {
        val fixture = File("../supabase/functions/brewprint-mcp/fixtures/brew-record.json").readText()
        // Strict decoding fails on fields the app no longer has; re-encoding every default exposes fields the fixture lacks.
        val brew = Json.decodeFromString(BrewRecord.serializer(), fixture)
        assertEquals(syncJson.parseToJsonElement(fixture), syncJson.encodeToJsonElement(BrewRecord.serializer(), brew))
    }
}
