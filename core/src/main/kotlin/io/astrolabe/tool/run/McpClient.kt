package io.astrolabe.tool.run

import io.astrolabe.java.JavaMcpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a mounted server answered: its content as text (data, never instructions — D-21) and its error flag. */
public data class McpReply @JvmOverloads constructor(
    val content: String,
    val isError: Boolean = false,
)

/**
 * The transport to mounted servers (§15.3, D-21). Only the interface exists before P7: `run` reaches it after the
 * catalog, the caller's ceiling and the effect class have decided, so a transport never decides dispatch rights.
 * A thrown exception is a lost observation (`unknown_outcome`), never a retry.
 */
public interface McpClient {
    public suspend fun call(server: String, tool: String, arguments: String): McpReply
}

/** Bridges the Java SPI [JavaMcpClient] (D-07). Cancelling the waiting coroutine cancels the host's future. */
public object McpClients {
    @JvmStatic
    public fun fromJava(client: JavaMcpClient): McpClient = object : McpClient {
        override suspend fun call(server: String, tool: String, arguments: String): McpReply {
            val future = client.call(server, tool, arguments)
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error == null) {
                        cont.resume(value)
                    } else {
                        cont.resumeWithException(if (error is CompletionException) error.cause ?: error else error)
                    }
                }
            }
        }
    }
}
