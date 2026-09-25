package io.astrolabe.atlas

import io.astrolabe.java.JavaLanguageService
import io.astrolabe.tool.run.Diagnostic
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bridges between the Kotlin [LanguageService] and the Java SPI [JavaLanguageService] (D-07, TODO P5.4.2). */
public object LanguageServices {
    @JvmStatic
    public fun fromJava(adapter: JavaLanguageService): LanguageService = object : LanguageService {
        override suspend fun defs(name: String): LanguageDefs = adapter.defs(name).await()
        override suspend fun refs(name: String): LanguageRefs = adapter.refs(name).await()
        override suspend fun diagnostics(paths: List<String>): List<Diagnostic> = adapter.diagnostics(paths).await()
        override suspend fun incrementalTypeCheck(changed: List<String>): IncrementalCheck = adapter.incrementalTypeCheck(changed).await()
    }

    private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value) else cont.resumeWithException(error.unwrap())
        }
    }

    private fun Throwable.unwrap(): Throwable = if (this is CompletionException) cause ?: this else this
}
