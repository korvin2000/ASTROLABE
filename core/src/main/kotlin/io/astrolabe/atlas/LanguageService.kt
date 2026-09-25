package io.astrolabe.atlas

import io.astrolabe.tool.run.Diagnostic

/**
 * The boundary a tier-2 answer is valid within (§7.2): the adapter's own declared reach — a project, a set of
 * packages, a workspace — beyond which it makes no claim. Never inferred by the harness (L8).
 */
public data class AdapterScope(public val description: String) {
    init {
        require(description.isNotBlank()) { "an adapter scope names what it covers" }
    }
}

/** A call site the adapter saw but could not resolve to a definite target (§7.2): dynamic dispatch, reflection, a plugin hook. */
public data class UnresolvedCase(public val path: String, public val line: Int, public val reason: String) {
    init {
        require(line >= 1) { "line numbers are 1-based; got $line" }
    }
}

/** Tier-2 definitions of [name], honest about [scope] and [complete] the way tier 0's [Refs] already is (§7.2). */
public data class LanguageDefs(
    public val name: String,
    public val locations: List<Location>,
    public val scope: AdapterScope,
    public val complete: Boolean,
)

/**
 * Tier-2 references to [name]: [references] the adapter resolved with project semantics, [unresolved] the
 * dynamic or reflective sites it saw but could not resolve to this name for certain (§7.2 "adapter reports
 * scope and unresolved dynamic cases") — never silently dropped, so an incomplete answer is never mistaken
 * for absence (L8).
 */
public data class LanguageRefs(
    public val name: String,
    public val references: List<Reference>,
    public val unresolved: List<UnresolvedCase>,
    public val scope: AdapterScope,
    public val complete: Boolean,
)

/**
 * The tier-2 adapter's incremental type check (§7.2 "incremental type checking that makes fast checkers viable
 * in large repos", D12): only [changed] was re-checked; [diagnostics] covers just those paths, so a clean
 * result is never read as "the whole project is clean". [complete] is false when the adapter fell back to a
 * wider or partial check than the requested [changed] set.
 */
public data class IncrementalCheck(
    public val changed: List<String>,
    public val diagnostics: List<Diagnostic>,
    public val complete: Boolean,
)

/**
 * Tier 2 of the symbol index (§7.2, D-07, D12, TODO P5.4.2): an optional, first-paid language-service adapter
 * feeding `look(def/refs/impact)` at [IndexTier.LanguageService] and the fast end-of-turn checker with project
 * semantics tier 0/1 cannot claim — definitions, references with their scope and unresolved dynamic cases,
 * diagnostics, and the incremental type check that makes async watchers viable ([§8.1](../verification/scheduler.md#sec-8-1)
 * "why synchronous in the baseline", [D12](../reference/decisions.md#sec-20-2)). This module fixes the contract
 * only, behind `Config.Flags.languageService` (off by default) and validated against a fake in test fixtures; a
 * live LSP-backed implementation is P7. `suspend` here, never in `io.astrolabe.java` (D-07) — see
 * [io.astrolabe.java.JavaLanguageService] for the Java-implementable form.
 */
public interface LanguageService {
    /** The tier every answer from this adapter carries; always [IndexTier.LanguageService]. */
    public val tier: IndexTier get() = IndexTier.LanguageService

    public suspend fun defs(name: String): LanguageDefs

    public suspend fun refs(name: String): LanguageRefs

    public suspend fun diagnostics(paths: List<String>): List<Diagnostic>

    public suspend fun incrementalTypeCheck(changed: List<String>): IncrementalCheck
}
