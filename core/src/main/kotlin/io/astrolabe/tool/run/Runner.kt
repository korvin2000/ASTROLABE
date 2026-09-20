package io.astrolabe.tool.run

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.os.Os
import io.astrolabe.os.Proc
import io.astrolabe.os.SpawnSpec
import java.io.IOException

/**
 * Where commands execute (§4.6 execution modes). A runner launches a process under the harness's
 * ownership and returns the durable [Proc] handle (D-43); polling, cancellation and reattachment stay
 * with the [Os] adapter, because they are about the process, not about the mode it was launched in.
 */
public interface Runner {
    public val mode: ExecutionMode

    @Throws(IOException::class)
    public fun start(spec: SpawnSpec): Proc
}

/** No confinement: effect classes are labels, and every report says so (§4.6 `trusted-local`). */
public class TrustedLocalRunner(private val os: Os) : Runner {
    override val mode: ExecutionMode get() = ExecutionMode.TrustedLocal

    override fun start(spec: SpawnSpec): Proc = os.spawn(spec)
}

/**
 * The contract of a confined backend (container, bwrap, sandbox-exec, firejail): writable roots = workspace +
 * tmp, env allowlist, network off by default, resource limits (§4.6). Interface only until P7 (D-11): a host
 * that requires confinement is refused at dispatch, never served by the trusted-local runner in disguise.
 */
public interface ConfinedRunner : Runner {
    public val backend: String
}
