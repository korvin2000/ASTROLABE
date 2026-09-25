package io.astrolabe.java

import io.astrolabe.tool.run.McpReply
import java.util.concurrent.CompletableFuture

/**
 * Java-implementable form of [io.astrolabe.tool.run.McpClient] (D-07), bridged by `McpClients.fromJava`. The
 * future may complete on any thread; completing it exceptionally is a lost observation (the invocation's intent
 * stays open as `unknown_outcome`); cancelling the campaign cancels the future. No coroutine types appear here.
 */
public fun interface JavaMcpClient {
    public fun call(server: String, tool: String, arguments: String): CompletableFuture<McpReply>
}
