package com.brewprint.data

import android.content.ContextWrapper
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class BatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: BrewRepository
    private val kii = Beans(name = "Kii", roaster = "Tim Wendelboe")
    private val grinder = Grinder(name = "Comandante C40", lastSetting = 22)
    private val recipe = Recipe(name = "Filter", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 30)))

    @Before fun setUp() {
        val directory = temporary.newFolder("data")
        repository = BrewRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = directory })
        repository.upsertGrinder(grinder).getOrThrow()
        repository.upsertRecipe(recipe).getOrThrow()
    }

    private fun beans() = repository.state.value.beans.single { it.id == kii.id }

    private fun brew(batchId: String? = null): BrewRecord {
        val draft = repository.startDraftWith(beansId = kii.id, batchId = batchId).getOrThrow().copy(recipe = recipe, stage = DraftStage.REVIEW)
        repository.updateDraft(draft).getOrThrow()
        return repository.saveBrew(draft.id).getOrThrow()
    }

    @Test fun `beans saved with a roast date get one batch, and without one none`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        assertEquals(listOf("2026-11-10"), beans().batches.map { it.roastDate })
        val other = Beans(name = "Gesha", roaster = "Sey")
        repository.saveBeans(other, null).getOrThrow()
        assertEquals(emptyList<BeanBatch>(), repository.state.value.beans.single { it.id == other.id }.batches)
        assertTrue(repository.saveBeans(other, LocalDate.now().plusDays(1).toString()).isFailure)
    }

    @Test fun `editing beans keeps batches synced meanwhile, and redates a single batch`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        val editedForm = beans().copy(name = "Kii AA")
        repository.saveBeans(editedForm, "2026-11-11").getOrThrow()
        assertEquals("Kii AA", beans().name)
        assertEquals(listOf("2026-11-11"), beans().batches.map { it.roastDate })
        // A form opened before another bag arrived does not drop it.
        repository.addBeanBatch(kii.id, "2026-11-20").getOrThrow()
        repository.saveBeans(editedForm.copy(notes = "Lovely"), "2026-11-11").getOrThrow()
        assertEquals(2, beans().batches.size)
        assertEquals("Lovely", beans().notes)
    }

    @Test fun `buying again keeps the first bag apart, brings archived beans back, and is chosen next`() {
        repository.saveBeans(kii, null).getOrThrow()
        repository.archiveBeans(kii.id).getOrThrow()
        val added = repository.addBeanBatch(kii.id, "2026-11-20").getOrThrow()
        assertEquals(listOf(added.id, beans().batches.first().id), beans().batchesNewestFirst.map { it.id })
        assertEquals("Original bag", beans().batches.first().notes)
        assertEquals(false, beans().archived)
        val draft = repository.startDraftWith(beansId = kii.id).getOrThrow()
        assertEquals(added.id, draft.batchId)
        assertEquals("2026-11-20", draft.roastDate)
        // The brew keeps the beans without their batches, and the batch it used.
        repository.updateDraft(draft.copy(recipe = recipe, stage = DraftStage.REVIEW)).getOrThrow()
        val record = repository.saveBrew(draft.id).getOrThrow()
        assertEquals(emptyList<BeanBatch>(), record.beans.batches)
        assertEquals(added.id to "2026-11-20", record.batchId to record.roastDate)
        assertEquals(added.id, repository.state.value.lastBatchId)
    }

    @Test fun `the batch used last is chosen again, until it is deleted`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        val first = beans().batches.single()
        repository.addBeanBatch(kii.id, "2026-11-20").getOrThrow()
        val draft = repository.startDraftWith(beansId = kii.id, batchId = first.id).getOrThrow()
        repository.updateDraft(draft.copy(recipe = recipe, stage = DraftStage.REVIEW)).getOrThrow()
        repository.saveBrew(draft.id).getOrThrow()
        assertEquals(first.id, repository.startDraft().getOrThrow().batchId)
        repository.discardDraft().getOrThrow()
        repository.deleteBeanBatch(kii.id, first.id).getOrThrow()
        assertNull(repository.state.value.lastBatchId)
        assertEquals(1, beans().batches.size)
        // The last batch stays.
        assertTrue(repository.deleteBeanBatch(kii.id, beans().batches.single().id).isFailure)
    }

    @Test fun `deleting the batch a setup uses moves it to the newest left, but a brew underway keeps its date`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        val first = beans().batches.single()
        val second = repository.addBeanBatch(kii.id, "2026-11-20").getOrThrow()
        repository.startDraftWith(beansId = kii.id, batchId = second.id).getOrThrow()
        repository.deleteBeanBatch(kii.id, second.id).getOrThrow()
        assertEquals(first.id to "2026-11-10", repository.state.value.draft!!.let { it.batchId to it.roastDate })
        repository.discardDraft().getOrThrow()
        val third = repository.addBeanBatch(kii.id, "2026-11-21").getOrThrow()
        val brewing = repository.startDraftWith(beansId = kii.id).getOrThrow()
        repository.updateDraft(brewing.copy(stage = DraftStage.SHEET)).getOrThrow()
        repository.deleteBeanBatch(kii.id, third.id).getOrThrow()
        assertEquals("2026-11-21", repository.state.value.draft!!.roastDate)
    }

    @Test fun `a repeat uses the newest bag and says which one the original used`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        val original = brew()
        repository.addBeanBatch(kii.id, "2026-11-20").getOrThrow()
        val repeat = repository.repeatBrew(original.id).getOrThrow()
        assertEquals("2026-11-20", repeat.roastDate)
        assertEquals("2026-11-10", repeat.repeatedFromRoastDate)
        repository.discardDraft().getOrThrow()
        // A brew of the newest bag repeats with it, saying nothing.
        val same = repository.repeatBrew(brew(beans().newestBatch!!.id).id).getOrThrow()
        assertEquals("2026-11-20", same.roastDate)
        assertNull(same.repeatedFromRoastDate)
    }

    @Test fun `days off roast count whole days and never go negative`() {
        val today = LocalDate.of(2026, 11, 22)
        assertEquals(12, daysOffRoast("2026-11-10", today))
        assertEquals(0, daysOffRoast("2026-11-22", today))
        assertNull(daysOffRoast("2026-11-23", today))
        assertNull(daysOffRoast(null, today))
        assertNull(daysOffRoast("not a date", today))
    }

    @Test fun `deleted beans forget their remembered batch, and Undo remembers it again`() {
        repository.saveBeans(kii, "2026-11-10").getOrThrow()
        brew()
        val remembered = repository.state.value.lastBatchId!!
        repository.deleteBeans(kii.id).getOrThrow()
        assertNull(repository.state.value.lastBatchId)
        repository.restoreBeans(beans = Beans(id = kii.id, name = kii.name, roaster = kii.roaster), remembered = true, account = null, rememberedBatchId = remembered).getOrThrow()
        assertEquals(remembered, repository.state.value.lastBatchId)
    }
}
