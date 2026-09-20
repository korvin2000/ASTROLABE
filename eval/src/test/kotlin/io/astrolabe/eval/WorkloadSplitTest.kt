package io.astrolabe.eval

import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkloadSplitTest {
    @Test fun `repeated task is indivisible and carries weight once`() {
        val policy = WorkloadPolicy("v1", mapOf("hard" to true), emptySet(), emptyList(), emptyMap(),
            emptyMap(), WorkloadPartition.entries.map { WorkloadQuota(it, null, bd(0), bd(9), bd(3), bd(1)) })
        val design = WorkloadDesign(policy, listOf(
            WorkloadTask("a", setOf(0, 1, 2), null, null, "hard", bd(3), null),
            WorkloadTask("b", setOf(0), null, null, "hard", bd(3), null),
            WorkloadTask("c", setOf(0), null, null, "hard", bd(3), null),
        ))
        val result = WorkloadSplit.solve(design, 100)
        assertEquals(WorkloadSplitStatus.Optimal, result.status)
        assertEquals(0, result.objective!!.compareTo(bd(0)))
        assertEquals(1, result.assignment.filterKeys { it.task == "a" }.values.toSet().size)
        assertEquals(9, result.totals.filter { it.stratum == null }.sumOf { it.weight }.toInt())
        assertEquals(5L, result.totals.filter { it.stratum == null }.sumOf { it.trials })
        assertEquals(WorkloadSplitStatus.Feasible,
            WorkloadSplit.validate(design, design.fingerprint, result.assignment).status)
    }

    @Test fun `hybrid links are transitive and time conflict cannot cut a component`() {
        val tasks = listOf(task("a", repo = "r1", family = "f1", time = 1),
            task("b", repo = "r1", family = "f2", time = 11),
            task("c", repo = "r2", family = "f2", time = 21))
        val p = policy(grouping = WorkloadGrouping.entries.toSet(), windows = windows())
        val result = WorkloadSplit.solve(WorkloadDesign(p, tasks), 100)
        assertEquals(WorkloadSplitStatus.Infeasible, result.status)
        assertEquals(listOf("a", "b", "c"), result.groups.single().tasks)
        assertTrue(result.groups.single().allowed.isEmpty())
        assertTrue(result.issues.any { "no allowed partition" in it })
        assertEquals(0L, result.visitedNodes)
    }

    @Test fun `unknown required metadata is reported and unused metadata stays optional`() {
        val partial = WorkloadTask("a", setOf(0), null, null, null, null, null)
        val result = WorkloadSplit.solve(WorkloadDesign(policy(grouping = WorkloadGrouping.entries.toSet(),
            windows = windows()), listOf(partial)), 100)
        assertEquals(WorkloadSplitStatus.UnknownMetadata, result.status)
        assertEquals(5, result.issues.size)
        assertFalse(result.searchComplete)
        assertTrue(result.assignment.isEmpty())
        assertEquals(WorkloadSplitStatus.Optimal,
            WorkloadSplit.solve(WorkloadDesign(policy(), listOf(task("a"))), 100).status)
    }

    @Test fun `complex requirement uses task weights not row or repetition counts`() {
        val light = (0..20).map { WorkloadTask("h$it", setOf(0, 1, 2), null, null, "hard", bd(1), null) }
        val heavy = task("easy", stratum = "easy", weight = 22)
        val result = WorkloadSplit.solve(WorkloadDesign(policy(), light + heavy), 100)
        assertEquals(WorkloadSplitStatus.Infeasible, result.status)
        assertTrue(result.issues.single().contains("below half"))
        val exact = WorkloadSplit.solve(WorkloadDesign(policy(), listOf(task("a"),
            task("b", stratum = "easy"))), 100)
        assertEquals(WorkloadSplitStatus.Optimal, exact.status)
        assertEquals(WorkloadSplitStatus.Infeasible, WorkloadSplit.solve(
            WorkloadDesign(policy(), listOf(task("zero", weight = 0))), 100).status)
    }

    @Test fun `rare stratum quota and giant component give different impossibility proofs`() {
        val rare = WorkloadQuota(WorkloadPartition.Final, "rare", bd(1), bd(5), bd(1), bd(1))
        val p = policy(extra = listOf(rare))
        val missing = WorkloadSplit.solve(WorkloadDesign(p, listOf(task("a"))), 100)
        assertEquals(WorkloadSplitStatus.Infeasible, missing.status)
        assertTrue(missing.issues.single().contains("attainable"))
        val giant = (0..19999).map { task("t$it", repo = "same", weight = 1) }
        val result = WorkloadSplit.solve(WorkloadDesign(policy(grouping = setOf(WorkloadGrouping.Repository)),
            giant), 100)
        assertEquals(1, result.groups.size)
        assertEquals(WorkloadSplitStatus.Infeasible, result.status)
        assertEquals(3L, result.visitedNodes)
        assertTrue(result.searchComplete)
    }

    @Test fun `limit before and after incumbent never claims optimal or infeasible`() {
        val d = WorkloadDesign(policy(), listOf(task("a"), task("b")))
        val none = WorkloadSplit.solve(d, 0)
        assertEquals(WorkloadSplitStatus.SearchLimit, none.status)
        assertTrue(none.assignment.isEmpty())
        assertNull(none.objective)
        val some = WorkloadSplit.solve(d, 2)
        assertEquals(WorkloadSplitStatus.SearchLimit, some.status)
        assertEquals(2, some.assignment.size)
        assertFalse(some.searchComplete)
        assertEquals(WorkloadSplitStatus.Feasible, WorkloadSplit.validate(d, d.fingerprint, some.assignment).status)
        val all = WorkloadSplit.solve(d, 100)
        assertEquals(WorkloadSplitStatus.Optimal, all.status)
        assertEquals(WorkloadSplitStatus.Optimal, WorkloadSplit.solve(d, all.visitedNodes).status)
        assertFailsWith<IllegalArgumentException> { WorkloadSplit.solve(d, -1) }
    }

    @Test fun `validator rejects split repeats missing extra stale and disallowed assignments`() {
        val d = WorkloadDesign(policy(), listOf(WorkloadTask("a", setOf(0, 1), null, null, "hard", bd(1), null)))
        val good = WorkloadSplit.solve(d, 100).assignment
        fun invalid(map: Map<WorkloadTrialKey, WorkloadPartition>) =
            WorkloadSplit.validate(d, d.fingerprint, map).also { assertEquals(WorkloadSplitStatus.InvalidInput, it.status) }
        invalid(good - WorkloadTrialKey("a", 1))
        invalid(good + (WorkloadTrialKey("absent", 0) to WorkloadPartition.Final))
        val split = invalid(good + (WorkloadTrialKey("a", 1) to WorkloadPartition.Final))
        assertTrue(split.totals.isEmpty())
        assertNull(split.objective)
        val changed = WorkloadDesign(policy(version = "v2"), d.tasks)
        assertEquals(WorkloadSplitStatus.InvalidInput, WorkloadSplit.validate(changed, d.fingerprint, good).status)
        val limited = WorkloadDesign(policy(allowed = mapOf("a" to setOf(WorkloadPartition.Selection))), d.tasks)
        assertEquals(WorkloadSplitStatus.InvalidInput,
            WorkloadSplit.validate(limited, limited.fingerprint, good).status)
    }

    @Test fun `cutoff is half open and exact decimal quotas do not round excess into fit`() {
        val d = WorkloadDesign(policy(windows = windows()), listOf(task("a", time = 10)))
        assertEquals(setOf(WorkloadPartition.Selection), WorkloadSplit.solve(d, 100).assignment.values.toSet())
        val quotas = WorkloadPartition.entries.map {
            WorkloadQuota(it, null, bd(0), BigDecimal("0.3"), BigDecimal("0.3"), bd(1))
        }
        val p = WorkloadPolicy("exact", mapOf("hard" to true), emptySet(), emptyList(), emptyMap(), emptyMap(), quotas)
        fun run(weight: String) = WorkloadSplit.solve(WorkloadDesign(p, listOf(
            WorkloadTask("a", setOf(0), null, null, "hard", BigDecimal(weight), null))), 100)
        assertEquals(WorkloadSplitStatus.Optimal, run("0.30000000000000000000000000000").status)
        assertEquals(WorkloadSplitStatus.Infeasible, run("0.30000000000000000000000000001").status)
    }

    @Test fun `invalid supplied split is diagnosed before known design infeasibility`() {
        val d = WorkloadDesign(policy(), listOf(WorkloadTask("zero", setOf(0, 1), null, null, "hard", bd(0), null)))
        val split = mapOf(WorkloadTrialKey("zero", 0) to WorkloadPartition.Development,
            WorkloadTrialKey("zero", 1) to WorkloadPartition.Final)
        val invalid = WorkloadSplit.validate(d, d.fingerprint, split)
        assertEquals(WorkloadSplitStatus.InvalidInput, invalid.status)
        assertTrue(invalid.issues.single().contains("split component"))
        assertEquals(WorkloadSplitStatus.Infeasible, WorkloadSplit.validate(d, d.fingerprint,
            split.mapValues { WorkloadPartition.Development }).status)
        val noPartition = WorkloadDesign(policy(allowed = mapOf("a" to emptySet())), listOf(task("a")))
        assertEquals(WorkloadSplitStatus.InvalidInput, WorkloadSplit.validate(noPartition,
            noPartition.fingerprint, mapOf(WorkloadTrialKey("a", 0) to WorkloadPartition.Final)).status)
    }

    @Test fun `snapshots resist mutation and fingerprints bind every policy field`() {
        val repeats = mutableSetOf(0)
        val rows = mutableListOf(WorkloadTask("a", repeats, null, null, "hard", bd(1), null))
        val parts = mutableSetOf(WorkloadPartition.Development)
        val allowed = mutableMapOf<String, Set<WorkloadPartition>>("a" to parts)
        val p = policy(allowed = allowed)
        val d = WorkloadDesign(p, rows)
        val fp = d.fingerprint
        repeats += 99; rows.clear(); parts.clear(); allowed.clear()
        val result = WorkloadSplit.solve(d, 100)
        assertEquals(fp, d.fingerprint)
        assertEquals(1, result.assignment.size)
        assertFailsWith<UnsupportedOperationException> { (d.tasks as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (p.allowed as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (p.allowed.getValue("a") as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.assignment as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.groups.single().tasks as MutableList).clear() }
        val base = policy()
        listOf(policy(version = "v2"), policy(grouping = setOf(WorkloadGrouping.Repository)),
            policy(links = listOf(WorkloadLink("a", "b"))), policy(windows = windows()), p,
            policy(extra = listOf(WorkloadQuota(WorkloadPartition.Final, "hard", bd(0), bd(99), bd(2), bd(1)))))
            .forEach { assertNotEquals(base.fingerprint, it.fingerprint) }
        val nullMetadata = WorkloadDesign(base, listOf(task("a")))
        val literal = WorkloadDesign(base, listOf(task("a", repo = "<null>")))
        assertNotEquals(nullMetadata.fingerprint, literal.fingerprint)
        val rescaled = WorkloadDesign(base, listOf(WorkloadTask("a", setOf(0), null, null, "hard", BigDecimal("1.00"), null)))
        assertEquals(nullMetadata.fingerprint, rescaled.fingerprint)
    }

    @Test fun `malformed metadata tables and policies fail at construction`() {
        assertFailsWith<IllegalArgumentException> { WorkloadDesign(policy(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { WorkloadDesign(policy(), listOf(task("a"), task("a"))) }
        assertFailsWith<IllegalArgumentException> { WorkloadDesign(policy(links = listOf(WorkloadLink("a", "x"))), listOf(task("a"))) }
        assertFailsWith<IllegalArgumentException> { WorkloadDesign(policy(), listOf(task("a", stratum = "undeclared"))) }
        assertFailsWith<IllegalArgumentException> { WorkloadTask("a", emptySet(), null, null, "hard", bd(1), null) }
        assertFailsWith<IllegalArgumentException> { policy(windows = mapOf(WorkloadPartition.Final to WorkloadWindow(null, null))) }
    }

    @Test fun `exact search matches independent all task assignment oracle and permutations`() {
        val random = Random(615)
        var feasible = 0
        repeat(300) { sample ->
            val guaranteed = sample % 3 == 0
            val n = random.nextInt(1, 8)
            val rows = (0 until n).map { i -> task("t$i", repo = "r${random.nextInt(3)}",
                family = "f${random.nextInt(3)}", weight = random.nextInt(if (guaranteed) 1 else 0, 5),
                stratum = if (!guaranteed && random.nextInt(4) == 0) "easy" else "hard", time = random.nextLong(30)) }
            val links = if (n > 1 && random.nextBoolean()) listOf(WorkloadLink("t0", "t${n - 1}")) else emptyList()
            val allowed = rows.filter { !guaranteed && random.nextInt(3) == 0 }.associate {
                it.id to WorkloadPartition.entries.filter { random.nextBoolean() }.toSet()
            }
            val quotas = WorkloadPartition.entries.flatMap { p -> listOf(null, "hard").map { s ->
                val min = if (guaranteed) 0 else random.nextInt(3)
                val max = if (guaranteed) 99 else random.nextInt(min, 18)
                WorkloadQuota(p, s, bd(min), bd(max), bd(random.nextInt(min, max + 1)), bd(random.nextInt(3)))
            } }
            val p = WorkloadPolicy("oracle", mapOf("easy" to false, "hard" to true),
                WorkloadGrouping.entries.filter { random.nextBoolean() }.toSet(), links, allowed,
                if (!guaranteed && sample % 4 == 0) windows() else emptyMap(), quotas)
            val d = WorkloadDesign(p, rows)
            val expected = bruteForce(d)
            val actual = WorkloadSplit.solve(d, 10000)
            assertEquals(if (expected == null) WorkloadSplitStatus.Infeasible else WorkloadSplitStatus.Optimal,
                actual.status, "sample $sample")
            if (expected != null) {
                feasible++
                assertEquals(0, expected.compareTo(actual.objective), "sample $sample")
                assertEquals(WorkloadSplitStatus.Feasible, WorkloadSplit.validate(d, d.fingerprint, actual.assignment).status)
            }
            val reversed = WorkloadDesign(WorkloadPolicy(p.version, p.strata.entries.reversed().associate { it.toPair() },
                p.grouping.reversed().toSet(), p.links.reversed().map { WorkloadLink(it.second, it.first) },
                p.allowed, p.windows, p.quotas.reversed()), rows.reversed())
            val again = WorkloadSplit.solve(reversed, 10000)
            assertEquals(d.fingerprint, reversed.fingerprint)
            assertEquals(actual.assignment, again.assignment)
            assertEquals(actual.visitedNodes, again.visitedNodes)
        }
        assertTrue(feasible >= 100, "oracle must exercise feasible optimization as well as contradictions")
    }

    // Independent enumeration assigns individual tasks, checking every pair/link directly (no DSU or pruning).
    private fun bruteForce(d: WorkloadDesign): BigDecimal? {
        val rows = d.tasks
        val p = d.policy
        val total = rows.sumOf { it.weight!! }
        if (total.signum() == 0 || rows.filter { p.strata[it.stratum] == true }.sumOf { it.weight!! } * bd(2) < total)
            return null
        var best: BigDecimal? = null
        repeat((0 until rows.size).fold(1) { acc, _ -> acc * 3 }) { encoding ->
            var remaining = encoding
            val parts = rows.map { val part = WorkloadPartition.entries[remaining % 3]; remaining /= 3; part }
            if (rows.indices.any { i -> p.allowed[rows[i].id]?.contains(parts[i]) == false ||
                    p.windows[parts[i]]?.contains(rows[i].time!!) == false }) return@repeat
            for (i in rows.indices) for (j in 0 until i) {
                val linked = (WorkloadGrouping.Repository in p.grouping && rows[i].repository == rows[j].repository) ||
                    (WorkloadGrouping.TaskFamily in p.grouping && rows[i].family == rows[j].family) ||
                    p.links.any { setOf(it.first, it.second) == setOf(rows[i].id, rows[j].id) }
                if (linked && parts[i] != parts[j]) return@repeat
            }
            var score = bd(0)
            for (q in p.quotas) {
                val mass = rows.indices.filter { parts[it] == q.partition &&
                    (q.stratum == null || rows[it].stratum == q.stratum) }.sumOf { rows[it].weight!! }
                if (mass < q.minimum || mass > q.maximum) return@repeat
                score += q.penalty * (mass - q.target).abs()
            }
            if (best == null || score < best) best = score
        }
        return best
    }

    private fun task(id: String, repo: String? = null, family: String? = null, stratum: String = "hard",
        weight: Int = 1, time: Long? = null) =
        WorkloadTask(id, setOf(0), repo, family, stratum, bd(weight), time?.let(Instant::ofEpochSecond))

    private fun windows() = mapOf(
        WorkloadPartition.Development to WorkloadWindow(null, Instant.ofEpochSecond(10)),
        WorkloadPartition.Selection to WorkloadWindow(Instant.ofEpochSecond(10), Instant.ofEpochSecond(20)),
        WorkloadPartition.Final to WorkloadWindow(Instant.ofEpochSecond(20), null),
    )

    private fun policy(version: String = "v1", grouping: Set<WorkloadGrouping> = emptySet(),
        links: List<WorkloadLink> = emptyList(), allowed: Map<String, Set<WorkloadPartition>> = emptyMap(),
        windows: Map<WorkloadPartition, WorkloadWindow> = emptyMap(), extra: List<WorkloadQuota> = emptyList()) =
        WorkloadPolicy(version, mapOf("hard" to true, "easy" to false, "rare" to true), grouping, links, allowed,
            windows, WorkloadPartition.entries.map { WorkloadQuota(it, null, bd(0), bd(99), bd(1), bd(1)) } + extra)

    private fun bd(n: Int) = BigDecimal(n)
}
