package io.astrolabe.java

import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import java.util.concurrent.CompletableFuture

/**
 * Java-implementable form of [io.astrolabe.event.Authority] (D-07, I-14). Futures may complete on any thread; a
 * future completed with `null` from [ask] or [review] means "no answer" (the cell ends `blocked`). Completing a
 * future exceptionally is treated as no answer and logged. Cancelling the campaign cancels the wait but never
 * the host's own future. No coroutine types appear in this package.
 */
public interface JavaAuthority {
    public fun ask(question: Question): CompletableFuture<Answer?>

    public fun approve(request: DClassRequest): CompletableFuture<Decision>

    public fun resolve(proposal: AmendmentProposal): CompletableFuture<Resolution>

    public fun review(request: ReviewRequest): CompletableFuture<Verdict?>
}
