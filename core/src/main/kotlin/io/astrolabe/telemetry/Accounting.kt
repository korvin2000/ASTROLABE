package io.astrolabe.telemetry

import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * The four quantities of §11.5, kept apart. `null` is unknown, never zero: bytes on the wire are the transport's
 * (P7), so [bytesTransmitted] here is the serialized request handed to the adapter; [modelVisibleInput] and
 * [billedUsage] come from the provider's usage; [durableState] is measured over the store when exported.
 */
@Serializable
public data class Quantities(
    val bytesTransmitted: Long?,
    val modelVisibleInput: Long?,
    val billedUsage: Long?,
    val durableState: Long?,
)

/**
 * One priced model call (§15.2): the native usage retained, the normalized categories, and money from the
 * profile's dated price table. [money] is [Money.unknown] when usage is missing, incomplete or unpriced — a
 * missing usage is recorded as missing, never as zero spend (FX-59). [warm] says whether the call read the
 * provider cache; cold and warm calls are reported separately.
 */
@Serializable
public data class CallAccount(
    val invocationId: String,
    val ids: Identities,
    val profileId: String,
    val usage: BillableUsage?,
    val money: Money,
    val priceTableDate: String,
    val quantities: Quantities,
    val warm: Boolean?,
    @Serializable(with = InstantSerializer::class) val at: Instant,
)

/** Totals over calls, cold and warm apart; each money sum is unknown as soon as one call's is. */
@Serializable
public data class AccountTotals(
    val calls: Int,
    val callsWithoutUsage: Int,
    val money: Money,
    val coldMoney: Money,
    val warmMoney: Money,
    val quantities: Map<BillingDimension, Long?>,
    /** Undefined (`null`) at zero accepted tasks and when [money] is unknown. */
    val costPerAcceptedTask: Money?,
)

/**
 * Per-call accounting (§15.2, §11.5): the cell reports each response here, and the call is priced and stored in
 * the `usage` table (this component is its one writer). Reasoning is priced only as the provider's own
 * dimensions say — no field is added to another with a similar name.
 */
public class Accounting(private val store: Store, private val clock: Clock) {
    /** Records one call; a `null` [usage] is a call whose usage never arrived. */
    public fun record(ids: Identities, invocationId: String, profile: Profile, request: Request?, usage: BillableUsage?): CallAccount {
        val table = profile.priceTable
        val money = usage?.price(table) ?: Money.unknown(table.currency)
        val account = CallAccount(
            invocationId = invocationId,
            ids = ids,
            profileId = profile.id,
            usage = usage,
            money = money,
            priceTableDate = table.date.toString(),
            quantities = Quantities(
                bytesTransmitted = request?.let { JSON.encodeToString(Request.serializer(), it).toByteArray(Charsets.UTF_8).size.toLong() },
                modelVisibleInput = usage?.takeIf { it.unknown.none(BillingDimension::isInput) }?.totalInput,
                billedUsage = usage?.takeIf { it.isComplete }?.quantities?.values?.sum(),
                durableState = null,
            ),
            warm = usage?.let { u -> u.quantities[BillingDimension.CACHE_READ]?.let { it > 0 } ?: if (BillingDimension.CACHE_READ in u.unknown) null else false },
            at = clock.instant(),
        )
        store.db.tx { tx ->
            tx.execute(
                "INSERT INTO usage (invocation_id, work_id, attempt_id, candidate_id, context_id, profile_id, native, normalized, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                invocationId, ids.work, ids.attempt, ids.candidate, ids.context, profile.id,
                (usage?.native ?: JsonNull).toString(),
                usage?.let { JSON.encodeToString(BillableUsage.serializer(), it) } ?: "null",
                Migrations.SCHEMA_VERSION, account.at, JSON.encodeToString(CallAccount.serializer(), account),
            )
        }
        return account
    }

    /** Every call of [work], in recording order. */
    public fun calls(work: WorkId): List<CallAccount> = store.db.query(
        "SELECT body FROM usage WHERE work_id = ? ORDER BY created_at, rowid", work,
    ) { JSON.decodeFromString(CallAccount.serializer(), it.string("body")) }

    /** Totals of [work] with `cost_per_accepted_task` over [accepted] verified increments. */
    public fun totals(work: WorkId, accepted: Int, currency: String): AccountTotals = totals(calls(work), accepted, currency)

    public companion object {
        internal val JSON: Json = Json { encodeDefaults = true }

        @JvmStatic
        public fun totals(calls: List<CallAccount>, accepted: Int, currency: String): AccountTotals {
            fun sum(selected: List<CallAccount>) = selected.fold(Money.zero(currency)) { total, call -> total + call.money }
            val money = sum(calls)
            val dimensions = calls.mapNotNull { it.usage }.flatMap { it.quantities.keys + it.unknown }.distinct()
            return AccountTotals(
                calls = calls.size,
                callsWithoutUsage = calls.count { it.usage == null },
                money = money,
                coldMoney = sum(calls.filter { it.warm == false }),
                warmMoney = sum(calls.filter { it.warm == true }),
                quantities = dimensions.associateWith { d -> if (calls.any { it.usage == null || d !in it.usage.quantities }) null else calls.sumOf { it.usage!!.quantities.getValue(d) } },
                costPerAcceptedTask = if (accepted == 0 || money.unknown) null else Money(currency, money.amount.divide(BigDecimal.valueOf(accepted.toLong()), 10, RoundingMode.HALF_EVEN)),
            )
        }

        /** Bytes the store holds on disk: the database and every blob (§11.5 "durable state"). */
        @JvmStatic
        public fun durableState(root: Path): Long = if (!Files.exists(root)) 0 else Files.walk(root).use { paths ->
            paths.asSequence().filter { it.isRegularFile() }.sumOf { it.fileSize() }
        }
    }
}
