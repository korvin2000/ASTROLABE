package io.astrolabe.cell

import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationState
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Terminal
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.telemetry.Accounting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalAccountingTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `cancellation and transport errors reconcile terminal usage exactly once without executing late calls`() = runTest {
        for (cancelled in listOf(true, false)) {
            CellFixture(stateRoot.resolve("cancelled-$cancelled")).use { f ->
                val base = f.context(ScriptedModel.of())
                val started = CompletableDeferred<Unit>()
                val usage = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 321L, BillingDimension.OUTPUT to 79L),
                    UsageProvenance("fake", "main", "terminal"))
                val error = ProviderError.Transport("connection lost after billing")
                var reconciled = 0
                val adapter = object : ProviderAdapter by base.model.adapter {
                    override fun start(request: Request, id: InvocationId): Invocation = object : Invocation {
                        override val id = id
                        override val state = InvocationState.Requested
                        override suspend fun await(): Response {
                            started.complete(Unit)
                            if (cancelled) awaitCancellation() else throw error
                        }
                        override fun cancel() = Unit
                        override suspend fun terminal(): Terminal {
                            reconciled++
                            return Terminal(id, null, error, listOf(
                                CellFixture.say("late terminal evidence"),
                                CellFixture.call("late", "edit", """{"ops":[{"create":"src/late.py","content":"forbidden"}],"why":"late"}"""),
                            ), usage, cancelled)
                        }
                    }
                }
                val accounting = Accounting(f.store, f.clock)
                val context = CellContext(base.ids, base.role, base.contracts,
                    CellModel(adapter, base.model.profile, base.model.estimator), base.tools, base.workspace,
                    base.evidence, base.prime, accounting = accounting)
                val budget = f.budget()
                if (cancelled) {
                    val job = launch { f.cell().run(context, f.increment, budget) }
                    started.await()
                    job.cancelAndJoin()
                } else {
                    f.cell().run(context, f.increment, budget)
                }
                assertEquals(1, reconciled)
                assertEquals(usage, accounting.calls(f.ids.work).single().usage)
                assertEquals(400L, budget.working.spent.value)
                assertEquals(0L, budget.working.heldTokens.value)
                assertFalse(Files.exists(f.repo.resolve("src/late.py")))
                assertTrue(f.journal.events(JournalScope(f.ids.work)).any { "late terminal evidence" in it.text })
            }
        }
    }
}
