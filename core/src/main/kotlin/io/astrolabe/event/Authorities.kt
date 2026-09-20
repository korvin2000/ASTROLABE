package io.astrolabe.event

import io.astrolabe.java.JavaAuthority
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bridges between the Kotlin [Authority] and the Java SPI [JavaAuthority] (D-07). */
public object Authorities {
    @JvmStatic
    public fun fromJava(authority: JavaAuthority): Authority = object : Authority {
        override suspend fun ask(question: Question): Answer? = authority.ask(question).awaitNullable()
        override suspend fun approve(request: DClassRequest): Decision = authority.approve(request).awaitRequired()
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = authority.resolve(proposal).awaitRequired()
        override suspend fun review(request: ReviewRequest): Verdict? = authority.review(request).awaitNullable()
    }

    /** Waits without cancelling the host's future; an exceptional completion counts as "no answer". */
    private suspend fun <T> CompletableFuture<T?>.awaitNullable(): T? = try {
        awaitRequired()
    } catch (e: CompletionException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private suspend fun <T> CompletableFuture<T>.awaitRequired(): T = suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value) else cont.resumeWithException(error.unwrap())
        }
    }

    private fun Throwable.unwrap(): Throwable = if (this is CompletionException) cause ?: this else this
}
