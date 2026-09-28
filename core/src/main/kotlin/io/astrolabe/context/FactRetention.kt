package io.astrolabe.context

import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.register.Register
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/** One durable retention decision per ended cell; a reopened boundary never ages facts twice. */
internal object FactRetention {
    fun capture(
        store: Store, ids: Identities, register: Register, current: (String) -> FileVersion?,
        evidence: (String) -> Boolean, estimator: TokenEstimator, cap: Int, clock: Clock,
    ): Retention = store.db.tx { tx ->
        val key = "retention-" + Digest.ofUtf8("${ids.work.value}\n${register.cell.value}").hex
        val saved = tx.query("SELECT body FROM packets WHERE id = ?", key) { it.string("body") }.firstOrNull()
        if (saved != null) return@tx Json.decodeFromString(Retention.serializer(), saved)
        val refs = register.plan.mapNotNull { it.evidence } + register.deadEnds.mapNotNull { it.evidence } +
            register.decisions.mapNotNull { it.probe } + register.open.filter { !it.closed }.mapNotNull { it.needs }
        val text = listOfNotNull(register.cursor?.text, register.next, register.focus).joinToString("\n")
        val referenced = register.facts.filter { fact ->
            val path = fact.anchor?.path
            val focused = path != null && register.focus?.let { path == it || path.startsWith(it.trimEnd('/') + "/") } == true
            fact.evidenceId in refs || fact.text in text || (path != null && path in text) || focused
        }.map { it.n }.toSet()
        val result = FactCoherence.retain(register, emptyMap(), referenced, current, evidence, estimator, cap)
        tx.execute("INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            key, ids.work, ids.attempt, ids.candidate, register.cell, "fact-retention", Migrations.SCHEMA_VERSION, clock.instant(), Json.encodeToString(Retention.serializer(), result))
        result
    }
}
