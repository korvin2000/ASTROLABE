package io.astrolabe.evidence

import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import kotlinx.serialization.Serializable

/**
 * A campaign-global display alias `#n` → canonical journal/artifact id (D-46): allocated monotonically,
 * never recycled on rebuild, cancellation or resume, with the producing context and workspace as provenance.
 */
@Serializable
public data class Alias(
    val work: WorkId,
    val number: Int,
    val canonicalId: String,
    val kind: String,
    val context: ContextId?,
    val workspace: WorkspaceId?,
) {
    init {
        require(number >= 1 && canonicalId.isNotBlank()) { "alias needs a positive number and a canonical id" }
    }

    val text: String get() = "#$number"
}

/** Persistence seam for aliases; allocation is transactional in the store implementation. */
public interface Aliases {
    public fun allocate(work: WorkId, canonicalId: String, kind: String, context: ContextId?, workspace: WorkspaceId?): Alias

    public fun resolve(work: WorkId, number: Int): Alias?

    public fun byCanonical(work: WorkId, canonicalId: String): Alias?

    public companion object {
        /** Parses `#17` → 17. */
        @JvmStatic
        public fun parse(text: String): Int? = text.trim().takeIf { it.startsWith("#") }?.substring(1)?.toIntOrNull()?.takeIf { it >= 1 }
    }
}

public class InMemoryAliases : Aliases {
    private val byWork = HashMap<WorkId, MutableList<Alias>>()

    @Synchronized
    override fun allocate(work: WorkId, canonicalId: String, kind: String, context: ContextId?, workspace: WorkspaceId?): Alias {
        val list = byWork.getOrPut(work) { ArrayList() }
        return Alias(work, list.size + 1, canonicalId, kind, context, workspace).also { list += it }
    }

    @Synchronized
    override fun resolve(work: WorkId, number: Int): Alias? = byWork[work]?.getOrNull(number - 1)

    @Synchronized
    override fun byCanonical(work: WorkId, canonicalId: String): Alias? = byWork[work]?.firstOrNull { it.canonicalId == canonicalId }
}
