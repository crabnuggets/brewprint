package com.brewprint.data

import android.content.ContextWrapper
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PairedScalesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private val home = PairedScale("AA:AA:AA:AA:AA:AA", "TIMEMORE Dot")
    private val office = PairedScale("BB:BB:BB:BB:BB:BB", "TIMEMORE Basic3 Link")
    private val state get() = repository.state.value

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        })
    }

    @Test fun `the first scale paired is in use, and a second joins it`() {
        repository.pairScale(home).getOrThrow()
        repository.pairScale(office).getOrThrow()
        assertEquals(listOf(home, office), state.pairedScales)
        assertEquals(home, state.scale)
        repository.useScale(office.address).getOrThrow()
        assertEquals(office, state.scale)
        assertEquals(listOf(home, office), state.pairedScales)
    }

    @Test fun `pairing a scale again keeps the name it was given`() {
        repository.pairScale(home).getOrThrow()
        repository.renameScale(home.address, " Home ").getOrThrow()
        repository.pairScale(home).getOrThrow()
        assertEquals(listOf(PairedScale(home.address, "Home", model = "TIMEMORE Dot")), state.pairedScales)
        assertEquals("Home", state.scale?.name)
        // A second rename keeps the scale's own name as its model.
        repository.renameScale(home.address, "Kitchen").getOrThrow()
        assertEquals(PairedScale(home.address, "Kitchen", model = "TIMEMORE Dot"), state.scale)
        assertTrue(repository.renameScale(home.address, "  ").isFailure)
    }

    @Test fun `forgetting the scale in use hands over to the next, and the last leaves none`() {
        repository.pairScale(home).getOrThrow()
        repository.pairScale(office).getOrThrow()
        repository.forgetScale(home.address).getOrThrow()
        assertEquals(office, state.scale)
        assertEquals(listOf(office), state.pairedScales)
        repository.forgetScale(office.address).getOrThrow()
        assertNull(state.scale)
        assertTrue(state.pairedScales.isEmpty())
        assertTrue(repository.useScale(home.address).isFailure)
    }

    @Test fun `a brew that holds the scale keeps it`() {
        repository.pairScale(home).getOrThrow()
        repository.pairScale(office).getOrThrow()
        repository.startDraft().getOrThrow()
        repository.changeDraft { it.copy(scale = ScaleData(), stage = DraftStage.SHEET) }
        assertTrue(repository.useScale(office.address).isFailure)
        assertEquals(home, state.scale)
        // Choosing the scale already in use is no change, so it is allowed.
        repository.useScale(home.address).getOrThrow()
        // Forgetting it mid-brew leaves the brew to the clock: the other scale's readings are not taken for its own.
        repository.forgetScale(home.address).getOrThrow()
        assertNull(state.scale)
        assertEquals(listOf(office), state.pairedScales)
        repository.discardDraft().getOrThrow()
        assertEquals(office, state.scaleToSwitchTo(setOf(office.address)))
    }

    @Test fun `data saved when one scale could be paired still has its scale`() {
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString(AppState.serializer(), """{"scale":{"address":"AA:AA:AA:AA:AA:AA","name":"TIMEMORE Dot"}}""")
        assertEquals(listOf(home), saved.pairedScales)
        assertEquals(home, saved.scale)
    }

    @Test fun `with the scale in use off, the first paired scale that is on takes over`() {
        repository.pairScale(home).getOrThrow()
        repository.pairScale(office).getOrThrow()
        assertNull(state.scaleToSwitchTo(emptySet()))
        assertNull(state.scaleToSwitchTo(setOf(home.address, office.address)))
        assertEquals(office, state.scaleToSwitchTo(setOf(office.address)))
        // A brew whose scale steps have begun keeps its scale: the tare belongs to it.
        repository.startDraft().getOrThrow()
        repository.changeDraft { it.copy(scale = ScaleData()) }
        assertNull(state.scaleToSwitchTo(setOf(office.address)))
    }
}
