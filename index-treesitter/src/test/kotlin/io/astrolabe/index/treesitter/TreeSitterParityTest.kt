package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.Outline
import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P5.4.1 Done: tier-1 outlines match the tier-0 fixture outlines or better (§7.2). */
class TreeSitterParityTest {

    @Test
    fun `every fixture source keeps each tier-0 declaration with the same span, namespace and imports`() {
        val report = StringBuilder()
        var compared = 0
        for (fixture in Fixture.entries) {
            FixtureRepos.materialize(fixture).use { repo ->
                val atlas = Atlas.build(repo.root)
                TreeSitterIndex(atlas).use { index ->
                    for (row in atlas.rows) {
                        if (Grammar.of(row.path) == null) continue
                        compared++
                        val lexical = Outline.of(row.path, FixtureRepos.bytes(fixture, row.path))
                        val syntax = index.outline(row.path)
                        val where = "${fixture.dir}/${row.path}"
                        assertEquals(IndexTier.Syntax, syntax.tier, where)
                        assertEquals(lexical.namespace, syntax.namespace, where)
                        assertEquals(lexical.importTargets, syntax.importTargets, where)
                        assertEquals(lexical.exports, syntax.exports, where)
                        for (entry in lexical.entries) {
                            val match = syntax.entries.firstOrNull { it.kind == entry.kind && it.name == entry.name && it.from == entry.from }
                            when {
                                match == null -> report.append("$where: tier 0 $entry missing at tier 1\n")
                                match.to != entry.to -> report.append("$where: ${entry.name} tier 0 ${entry.from}..${entry.to}, tier 1 ${match.from}..${match.to}\n")
                                match.exported != entry.exported -> report.append("$where: ${entry.name} export differs\n")
                            }
                        }
                    }
                    assertNull(index.degradation)
                }
            }
        }
        assertTrue(compared >= 20, "compared only $compared fixture sources")
        assertTrue(report.isEmpty(), report.toString())
    }
}
