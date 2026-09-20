package io.astrolabe.event

import io.astrolabe.id.Identities
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Store
import io.astrolabe.store.TEST_CONTEXT
import io.astrolabe.store.TEST_IDS
import io.astrolabe.store.TEST_INSTANT
import io.astrolabe.store.TEST_WORK
import io.astrolabe.store.insertRow
import java.nio.charset.StandardCharsets

/** Rows for the projection tests: one small campaign's worth of every table a view reads. */
internal fun Store.seedCampaign() {
    val cellIds: Identities = TEST_IDS.withContext(TEST_CONTEXT)

    insertRow("contracts", "version" to 1, body = """{"title":"first"}""")
    insertRow("contracts", "version" to 2, body = """{"title":"amended"}""")
    insertRow("requests", "id" to "request-2", "seq" to 2, body = """{"text":"and also"}""")
    insertRow("requests", "id" to "request-1", "seq" to 1, body = """{"text":"do the thing"}""")
    insertRow("requirements", "id" to "req-b", "contract_version" to 2)
    insertRow("requirements", "id" to "req-a", "contract_version" to 1)
    insertRow("acceptance", "id" to "acc-1", "contract_version" to 1, "kind" to "test")
    insertRow("constraints", "id" to "con-1", "contract_version" to 1)
    insertRow("amendments", "id" to "amend-1", "status" to "proposed")

    insertRow("increments", "id" to "inc-1", "status" to "open")
    insertRow("ledger", "requirement_id" to "req-a", "status" to "open")
    insertRow("sizing", "increment_id" to "inc-1", "cell_id" to "cell-1")

    for (version in 1..3) {
        insertRow("register_versions", "version" to version, ids = cellIds, body = """{"v":$version}""")
    }
    insertRow("workset_exports", "id" to "ws-2", ids = cellIds)
    insertRow("workset_exports", "id" to "ws-1", ids = cellIds)

    val raw = blobs.put("check output".toByteArray(StandardCharsets.UTF_8), BlobKind.LOG, TEST_IDS)
    insertRow(
        "receipts",
        "receipt_id" to "receipt-2",
        "check_id" to "check-unit",
        "outcome" to "fail",
        "raw_blob" to raw.hex,
    )
    insertRow("receipts", "receipt_id" to "receipt-1", "check_id" to "check-unit", "outcome" to "pass")
    insertRow("receipts", "receipt_id" to "receipt-3", "check_id" to "check-build", "outcome" to "pass")

    insertRow(
        "usage",
        "invocation_id" to "inv-2",
        "profile_id" to "main",
        "native" to """{"input_tokens":10}""",
        "normalized" to """{"inputTokens":10}""",
    )
    insertRow(
        "usage",
        "invocation_id" to "inv-1",
        "profile_id" to "main",
        "native" to """{"input_tokens":5}""",
        "normalized" to """{"inputTokens":5}""",
    )

    insertRow(
        "packets",
        "id" to "packet-finish",
        "kind" to Views.FINISH_RECEIPT_KIND,
        body = """{"outcome":"delivered"}""",
    )
    insertRow("packets", "id" to "packet-other", "kind" to "result")
}

internal val SEEDED_WORK = TEST_WORK

internal val SEEDED_CONTEXT = TEST_CONTEXT

internal val SEEDED_AT = TEST_INSTANT
