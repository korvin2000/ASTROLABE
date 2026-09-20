package io.astrolabe.evidence

import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.CasCheck
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.VersionChange
import io.astrolabe.workspace.VersionRegistry
import java.util.concurrent.CopyOnWriteArrayList

/** `serve(item)` (§4.4): current iff every anchor matches the version now; never silently current. */
public sealed interface Served {
    public object Current : Served {
        override fun toString(): String = "current"
    }

    /** At least one anchor moved: [moved] maps each such path to its version now, `null` when the file is gone. */
    public data class Stale(val moved: Map<String, FileVersion?>) : Served

    /** The path contract refused [paths], so no version could be established; not current, not provably stale. */
    public data class Unknown(val paths: Set<String>) : Served
}

/**
 * The coherence protocol (§4.4, TODO P1.4.4): one version registry, one rule — *mark, never serve as
 * current, never delete the evidence*.
 *
 * `Coherence` is the registry's single subscriber. Every `path: v → v'` transition reaches the
 * horizons in registration order; the P1 wiring is turn (`Workset`), cell (register facts through the
 * cell's holder), verification (`Checks`), and later phases register the project horizon (knowledge
 * notes, P2.6.3) and the integration horizon (delegated results, P5.1.3) here rather than on the
 * registry, so the order and the rule stay in one place. Horizons mark their own items; nothing is
 * deleted (`delete(item) := never` — eviction is the window's business, the store keeps everything).
 *
 * Two boundary duties of the protocol are batched instead of applied per change: the changed paths
 * accumulate in [scheduled] until the end-of-turn checker takes them, and the same set is what the
 * atlas refreshes its rows from (`Atlas.refresh(touched)`), because a run touches many files at once.
 *
 * Horizons run on the announcing thread and must not throw: the registry has already recorded the
 * transition, so a failed horizon would not hear it again (workspace mutation is serialized, D-26).
 */
public class Coherence(private val registry: VersionRegistry) : ChangeListener, AutoCloseable {
    private val horizons = CopyOnWriteArrayList<ChangeListener>()
    private val pending = LinkedHashSet<String>()
    private val subscription: AutoCloseable = registry.addListener(this)

    /** Registers a horizon; later changes reach it after every horizon registered before it. */
    public fun register(horizon: ChangeListener): AutoCloseable {
        horizons.add(horizon)
        return AutoCloseable { horizons.remove(horizon) }
    }

    /** The registry calls this once per transition; tests and adapters may announce a change directly. */
    override fun onChange(change: VersionChange) {
        synchronized(pending) { pending.add(change.path) }
        for (horizon in horizons) horizon.onChange(change)
    }

    /** Paths changed since the checker last took them (§4.4 "schedule the end-of-turn checker on path"). */
    public val scheduled: Set<String>
        get() = synchronized(pending) { LinkedHashSet(pending) }

    /** Drains [scheduled]; the caller runs the checker over the result and refreshes the atlas rows with it. */
    public fun takeScheduled(): Set<String> = synchronized(pending) {
        val taken = LinkedHashSet(pending)
        pending.clear()
        taken
    }

    /**
     * `serve(item)`: [anchors] (`path → version the item is about`) are current only when every path
     * hashes to that version **now** — the bytes are read, not a metadata hint, because this is the
     * decision that lets an item be presented as current. An external rewrite the registry was never
     * told about is therefore still caught here. An item recalled from the store at its recorded
     * version is labelled `historical` by the recall path (`Workset.recall`), not by this function.
     */
    public fun serve(anchors: Map<String, FileVersion>): Served {
        val moved = LinkedHashMap<String, FileVersion?>()
        val unknown = LinkedHashSet<String>()
        for ((path, version) in anchors) {
            when (val now = registry.versionOf(path)) {
                is CasCheck.Current -> if (now.version != version) moved[path] = now.version
                is CasCheck.Stale -> moved[path] = now.actual
                is CasCheck.Refused -> unknown += path
            }
        }
        // A moved anchor settles it: the item is stale even if another anchor could not be resolved.
        return when {
            moved.isNotEmpty() -> Served.Stale(moved)
            unknown.isNotEmpty() -> Served.Unknown(unknown)
            else -> Served.Current
        }
    }

    public fun serve(anchor: Anchor): Served = serve(mapOf(anchor.path to anchor.version))

    /** Unsubscribes from the registry; the horizons keep their marks. */
    override fun close() {
        subscription.close()
    }
}
