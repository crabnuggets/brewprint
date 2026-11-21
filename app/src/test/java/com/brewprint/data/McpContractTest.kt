package com.brewprint.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Drafts are written by the server's drafts.ts, which builds them as these fixtures; its tests check that side. */
    @Test fun `the MCP server's drafts are beans and a recipe the app can add as they are`() {
        val beansFixture = File("../supabase/functions/brewprint-mcp/fixtures/draft-beans.json").readText()
        val recipeFixture = File("../supabase/functions/brewprint-mcp/fixtures/draft-recipe.json").readText()
        // Strict decoding fails on fields the app doesn't have; matching every field path catches ones the drafts lack.
        val beans = Json.decodeFromString(Beans.serializer(), beansFixture)
        val recipe = Json.decodeFromString(Recipe.serializer(), recipeFixture)
        assertEquals(paths(syncJson.parseToJsonElement(beansFixture)), paths(syncJson.encodeToJsonElement(Beans.serializer(), beans)))
        assertEquals(paths(syncJson.parseToJsonElement(recipeFixture)), paths(syncJson.encodeToJsonElement(Recipe.serializer(), recipe)))
        assertTrue(recipe.validationErrors().isEmpty())
        assertEquals(beans, libraryDraft("beans", beans.id, syncJson.parseToJsonElement(beansFixture))?.beans)
        assertEquals(recipe, libraryDraft("recipes", recipe.id, syncJson.parseToJsonElement(recipeFixture))?.recipe)
    }

    private fun paths(element: JsonElement, at: String = ""): Set<String> = when (element) {
        is JsonObject -> element.flatMap { (name, value) -> paths(value, "$at/$name") + "$at/$name" }.toSet()
        is JsonArray -> element.flatMapIndexed { index, value -> paths(value, "$at/$index") }.toSet()
        else -> emptySet()
    }
}
