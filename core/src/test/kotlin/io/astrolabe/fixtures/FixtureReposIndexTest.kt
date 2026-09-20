package io.astrolabe.fixtures

import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The hand-written `fixtures/index/<name>.txt` is what [FixtureRepos.materialize] copies, because
 * a jar cannot be enumerated by path. These tests are the only thing keeping it honest.
 */
class FixtureReposIndexTest {

    @ParameterizedTest
    @EnumSource(Fixture::class)
    fun `index and resource directory list exactly the same files`(fixture: Fixture) {
        val directory = FixtureRepos.resourceDirectory(fixture)
        assumeTrue(directory != null, "only a packaged copy of the fixtures is reachable")
        val root = directory!!
        val onDisk = Files.walk(root).use { paths ->
            paths.asSequence()
                .filter(Files::isRegularFile)
                .map { root.relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
        val indexed = FixtureRepos.files(fixture).map(FixtureRepos::resourceName).sorted()
        assertEquals(onDisk, indexed)
    }

    @ParameterizedTest
    @EnumSource(Fixture::class)
    fun `every indexed file is readable from the classpath and tiny`(fixture: Fixture) {
        val files = FixtureRepos.files(fixture)
        assertTrue(files.size in 1 until MAX_FILES, "${fixture.dir} has ${files.size} files")
        assertTrue(".gitignore" in files, "${fixture.dir} has no .gitignore")
        assertTrue("README.md" in files, "${fixture.dir} has no README.md")
        for (relative in files) {
            val bytes = FixtureRepos.bytes(fixture, relative)
            assertTrue(bytes.isNotEmpty(), "${fixture.dir}/$relative is empty")
        }
    }

    @ParameterizedTest
    @EnumSource(Fixture::class)
    fun `fixture files carry no carriage return`(fixture: Fixture) {
        // The repository normalizes to LF (`.gitattributes`), so the committed bytes of a
        // materialized fixture are the same on both platforms — identities hash raw bytes (I-05).
        for (relative in FixtureRepos.files(fixture)) {
            val bytes = FixtureRepos.bytes(fixture, relative)
            assertTrue(bytes.none { it == CR }, "${fixture.dir}/$relative contains a CR")
        }
    }

    private companion object {
        const val MAX_FILES = 15
        const val CR: Byte = 0x0D
    }
}
