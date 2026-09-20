package io.astrolabe.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaBridgeTest {
    /** A JavaInvocation written the way a Java host would: futures completed by hand. */
    private class ScriptedJavaInvocation(private val id: InvocationId) : JavaInvocation {
        val response = CompletableFuture<Response>()
        val terminal = CompletableFuture<Terminal>()
        var cancelled = false
        private var state = InvocationState.Requested

        override fun id() = id
        override fun state() = state
        override fun response() = response
        override fun terminal() = terminal
        override fun cancel() {
            cancelled = true
            state = InvocationState.CancelRequested
        }
    }

    private fun adapter(invocation: ScriptedJavaInvocation) = ProviderAdapters.fromJava(object : JavaProviderAdapter {
        override fun id() = "java-fake"
        override fun capabilities(profile: Profile) = Fixtures.capabilities
        override fun validate(request: Request, estimate: Estimate): Validation = Validation.Ok
        override fun start(request: Request, id: InvocationId): JavaInvocation = invocation
        override fun normalizer() = UsageNormalizer { _, _ -> BillableUsage.missing(Fixtures.provenance, Fixtures.dims) }
    })

    private val request = Fixtures.request(Segment(SegmentKind.T, listOf(Message.text(Role.User, "hi"))))

    @Test
    fun `await returns the response and maps foreign exceptions to transport errors`() = runTest {
        val inv = ScriptedJavaInvocation(InvocationId("inv-1"))
        val handle = adapter(inv).start(request, InvocationId("inv-1"))
        inv.response.complete(Response(listOf(Message.text(Role.Assistant, "ok")), StopReason.EndTurn))
        assertEquals("ok", handle.await().text)

        val failing = ScriptedJavaInvocation(InvocationId("inv-2"))
        val h2 = adapter(failing).start(request, InvocationId("inv-2"))
        failing.response.completeExceptionally(IllegalStateException("socket closed"))
        val error = assertFailsWith<ProviderError.Transport> { h2.await() }
        assertEquals("socket closed", error.message)

        val refusing = ScriptedJavaInvocation(InvocationId("inv-3"))
        val h3 = adapter(refusing).start(request, InvocationId("inv-3"))
        refusing.response.completeExceptionally(ProviderError.Refusal("policy"))
        assertFailsWith<ProviderError.Refusal> { h3.await() }
    }

    @Test
    fun `cancelling the awaiting coroutine requests provider cancellation but never cancels the futures`() = runTest {
        val inv = ScriptedJavaInvocation(InvocationId("inv-4"))
        val handle = adapter(inv).start(request, InvocationId("inv-4"))
        val waiter = async { handle.await() }
        yield()
        waiter.cancel()
        assertFailsWith<CancellationException> { waiter.await() }
        assertTrue(inv.cancelled)
        assertEquals(InvocationState.CancelRequested, handle.state)
        assertFalse(inv.response.isCancelled)
        assertFalse(inv.terminal.isDone)

        // Late usage after cancellation is still delivered through terminal(), exactly once, with no tool calls.
        val lateUsage = BillableUsage(mapOf(BillingDimension.OUTPUT to 7L), Fixtures.provenance)
        inv.response.complete(Response(emptyList(), StopReason.Cancelled, lateUsage))
        inv.terminal.complete(Terminal(InvocationId("inv-4"), null, null, listOf(Message.text(Role.Assistant, "late")), lateUsage, cancelled = true))
        val terminal = handle.terminal()
        assertTrue(terminal.cancelled)
        assertEquals(lateUsage, terminal.usage)
        assertIs<Message>(terminal.lateItems.single())
    }
}
