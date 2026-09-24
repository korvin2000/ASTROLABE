package io.astrolabe.campaign

import io.astrolabe.atlas.Atlas
import io.astrolabe.id.WorkspaceId
import io.astrolabe.fixtures.TempRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3.2.6: the §3.7 impact pre-scan over the D-40 candidates, recorded as incomplete discovery. */
class ImpactPrescanTest {
    private lateinit var repo: TempRepo
    private val ws = WorkspaceId("ws-1")

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("pay/pyproject.toml", "[project]\nname = 'pay'\n")
        repo.write("pay/pay/router.py", "def route_payment(req):\n    return req\n")
        repo.write("pay/pay/api.py", "from pay.router import route_payment\n\n\ndef handle(r):\n    return route_payment(r)\n")
        repo.write("ledger/pyproject.toml", "[project]\nname = 'ledger'\n")
        repo.write("ledger/ledger/book.py", "def postEntry(x):\n    return x\n")
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() = repo.close()

    @Test
    fun `candidates are named paths, code-shaped identifiers and focus hubs, and plain words find nothing`() {
        val atlas = Atlas.build(repo.root)
        val inputs = ImpactPrescan.inputs(atlas, ws, "Fix route_payment in router.py so the Router returns `postEntry` results")
        assertEquals(listOf("pay/pay/router.py"), inputs.named)
        assertEquals(mapOf("route_payment" to listOf("pay/pay/router.py"), "postEntry" to listOf("ledger/ledger/book.py")), inputs.identifiers)
        assertEquals(listOf("pay/pay/router.py"), inputs.hubs, "the imported router is the focus subsystems' hub")

        val nothing = ImpactPrescan.of(atlas, ws, ImpactPrescan.inputs(atlas, ws, "make a return 10"))
        assertTrue(nothing.prescan.unknown, "zero hits never mean no impact")
        assertEquals(listOf("no candidate paths: discovery found nothing, which proves nothing"), nothing.unresolved)
    }

    @Test
    fun `the pre-scan estimates files and packages, never claims completeness, and a contract touch is true or unknown`() {
        val atlas = Atlas.build(repo.root)
        val inputs = ImpactPrescan.inputs(atlas, ws, "rename route_payment and postEntry")
        val scan = ImpactPrescan.of(atlas, ws, inputs)
        assertEquals(listOf("ledger/ledger/book.py", "pay/pay/api.py", "pay/pay/router.py"), scan.blast)
        assertEquals(listOf("ledger", "pay"), scan.packages)
        assertEquals(Prescan(filesEstimated = 3, crossPackage = true, contractTouch = null, fanIn = 2), scan.prescan)
        assertEquals(false, scan.complete)
        assertTrue(scan.unresolved.contains("lexical graph is incomplete"), scan.unresolved.toString())
        assertTrue(scan.log.startsWith("prescan named=[] identifiers=[route_payment:1,postEntry:1]"), scan.log)

        val touched = ImpactPrescan.of(atlas, ws, inputs, mapOf("CON-pay-api@1" to setOf("pay/pay/router.py")))
        assertEquals(true, touched.prescan.contractTouch)
        assertEquals(listOf("CON-pay-api@1"), touched.contractsTouched)
        assertNull(ImpactPrescan.of(atlas, ws, inputs, mapOf("CON-x@1" to setOf("docs/x.md"))).prescan.contractTouch, "no touch found is still unknown")
    }
}
