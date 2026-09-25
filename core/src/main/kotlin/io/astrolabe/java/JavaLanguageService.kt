package io.astrolabe.java

import io.astrolabe.atlas.IncrementalCheck
import io.astrolabe.atlas.LanguageDefs
import io.astrolabe.atlas.LanguageRefs
import io.astrolabe.tool.run.Diagnostic
import java.util.concurrent.CompletableFuture

/**
 * Java-implementable form of [io.astrolabe.atlas.LanguageService] (D-07, I-14, TODO P5.4.2). Futures may
 * complete on any thread; an exceptional completion is treated as the adapter being unavailable for that call
 * (the caller falls back to a lower tier, FX-46) rather than failing the whole request. Cancelling the campaign
 * cancels the wait but never the host's own future. No coroutine types appear in this package.
 */
public interface JavaLanguageService {
    public fun defs(name: String): CompletableFuture<LanguageDefs>

    public fun refs(name: String): CompletableFuture<LanguageRefs>

    public fun diagnostics(paths: List<String>): CompletableFuture<List<Diagnostic>>

    public fun incrementalTypeCheck(changed: List<String>): CompletableFuture<IncrementalCheck>
}
