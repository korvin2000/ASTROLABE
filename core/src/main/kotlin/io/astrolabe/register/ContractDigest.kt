package io.astrolabe.register

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Ledger
import io.astrolabe.provider.TokenEstimator

/** Status text of one acceptance item as the scheduler renders it (`green @s41 STALE (closure moved)`, `red #42`, `needs #id`). */
public data class ObligationStatus(val acceptanceId: String, val text: String)

/**
 * The ≤ 150-token contract digest at the tail of `[A]` (§5.1, D-17): the current authorized objective
 * (every verbatim request, newest last), requirement and obligation ids with status and currency, and the
 * critical exclusions. Ids-only rendering is allowed here and nowhere else (D-52); the full acceptance
 * definitions stay in `[K]`. Truncation is deterministic and labelled: the oldest request text shrinks first,
 * then acceptance statuses collapse to `+N more`; requirement statuses and exclusions are never dropped.
 */
public object ContractDigest {
    public const val TRUNCATED: String = "…[truncated]"

    @JvmStatic
    public fun render(
        contract: Contract,
        ledger: Ledger,
        obligations: List<ObligationStatus>,
        estimator: TokenEstimator,
        capTokens: Int = 150,
    ): String {
        require(capTokens > 0)
        var requests = contract.requests.map { it.text }
        var statuses = obligations
        var text = compose(contract, ledger, requests, statuses)
        var attempts = 0
        while (estimator.estimate(text).tokens > capTokens && attempts < 64) {
            attempts++
            when {
                requests.size > 1 -> requests = requests.drop(1)
                requests.single().length > 40 -> requests = listOf(shrink(requests.single()))
                statuses.isNotEmpty() -> statuses = statuses.dropLast(1)
                else -> break
            }
            val hidden = obligations.size - statuses.size
            text = compose(contract, ledger, requests, statuses, hiddenStatuses = hidden, requestsHidden = contract.requests.size - requests.size)
        }
        return text
    }

    private fun shrink(text: String): String {
        val keep = maxOf(24, text.length * 2 / 3)
        val cut = text.take(keep).trimEnd()
        return if (cut.endsWith(TRUNCATED)) cut else cut.removeSuffix(TRUNCATED) + TRUNCATED
    }

    private fun compose(
        contract: Contract,
        ledger: Ledger,
        requests: List<String>,
        statuses: List<ObligationStatus>,
        hiddenStatuses: Int = 0,
        requestsHidden: Int = 0,
    ): String {
        val sb = StringBuilder()
        sb.append("── CONTRACT v").append(contract.version).append(" (").append(contract.shape.name).append(") ── ")
        if (requestsHidden > 0) sb.append("(+").append(requestsHidden).append(" earlier) ")
        sb.append(requests.joinToString(" + ") { "\"$it\"" }).append('\n')
        val reqs = contract.requirements.joinToString(" · ") { r -> "${r.id} ${(ledger[r.id]?.status ?: r.status).wire}" }
        sb.append(reqs)
        if (statuses.isNotEmpty()) sb.append(" → ").append(statuses.joinToString(" · ") { "${it.acceptanceId} ${it.text}" })
        if (hiddenStatuses > 0) sb.append(" · +").append(hiddenStatuses).append(" more")
        if (contract.exclusions.isNotEmpty()) sb.append(" · exclusions: ").append(contract.exclusions.joinToString(", "))
        if (contract.amendmentsPending.isNotEmpty()) sb.append(" · pending amendments: ").append(contract.amendmentsPending.size)
        return sb.toString()
    }
}
