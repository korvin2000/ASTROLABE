package io.astrolabe.auth

import io.astrolabe.Config
import io.astrolabe.evidence.Observation
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.Envelope
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.Gauge
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.10.3 — redaction before exposure and persistence (§14.3, D-14, D-49, IX-10). */
class RedactionTest {

    private val redaction = Redaction()

    /** One sample per default pattern, with the raw secret kept for a substring assertion. */
    private val secrets = mapOf(
        "aws-access-key-id" to "AKIAIOSFODNN7EXAMPLE",
        "github-token" to "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
        "openai-key" to "sk-proj_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123",
        "slack-token" to "xoxb-123456789012-abcdefghijkl",
        "jwt" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        "bearer-token" to "Bearer abcdefghijklmnopqrstuvwxyz",
        "url-credentials" to "https://deploy:hunter2@internal.example.com/repo.git",
        "secret-assignment" to "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY",
    )

    private val privateKey = """
        -----BEGIN RSA PRIVATE KEY-----
        MIIEowIBAAKCAQEAx3Fl6f3oQmVhP1nqTzR0nJ0Qo1bWq9
        kQq3Q0m1vQ2n5aB7cD8eF9gH0iJ1kL2mN3oP4qR5sT6uV7w
        -----END RSA PRIVATE KEY-----
    """.trimIndent()

    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))

    @Test
    fun `every default pattern is caught and no raw secret survives in the output`() {
        // Each secret on a bare line: a `<label>:` prefix would itself match `secret-assignment` and hide
        // which pattern actually fired.
        val log = buildString {
            appendLine("starting deploy")
            secrets.values.forEach { appendLine("  $it") }
            appendLine(privateKey)
            appendLine("done")
        }
        val result = redaction.apply(log)

        for ((kind, secret) in secrets) {
            assertFalse(result.text.contains(secret), "$kind secret survived redaction:\n${result.text}")
        }
        assertFalse(result.text.contains("MIIEowIBAAKCAQEAx3Fl"), "private key body survived redaction")
        val kinds = result.hits.map { it.kind }.toSet()
        assertEquals(RedactionConfig.DEFAULT_PATTERNS.map { it.kind }.toSet(), kinds, "not every pattern fired")
        assertTrue(result.applied)

        // Structure survives: only the secret spans are replaced.
        assertContains(result.text, "starting deploy")
        assertContains(result.text, "done")
        assertContains(result.text, "[REDACTED:aws-access-key-id]")
        assertEquals(log.lines().size, result.text.lines().size, "redaction is line-preserving")
    }

    @Test
    fun `apply is idempotent and the replacement marker is never re-matched`() {
        val once = redaction.apply(secrets.values.joinToString("\n"))
        val twice = redaction.apply(once.text)
        assertEquals(once.text, twice.text)
        assertFalse(twice.applied, "the marker itself must not match a pattern: ${twice.hits}")
    }

    @Test
    fun `the mask hides exactly the source lines that were replaced`() {
        val text = listOf(
            "line 1 clean",
            "line 2 clean",
            "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "line 4 clean",
        ).joinToString("\n")
        val result = redaction.apply(text)
        assertEquals(Ranges.single(3, 3), result.mask.hiddenLines)
        assertEquals(listOf(RedactionHit("secret-assignment", 3)), result.hits)

        // A multi-line secret hides every line it spans.
        val block = "header\n$privateKey\ntrailer"
        val blockResult = redaction.apply(block)
        assertEquals(Ranges.single(2, 5), blockResult.mask.hiddenLines)
        assertContains(blockResult.text, "header")
        assertContains(blockResult.text, "trailer")
        assertEquals(block.lines().size, blockResult.text.lines().size, "the rendered view keeps the source numbering")
        assertEquals(6, blockResult.text.lines().indexOf("trailer") + 1)
    }

    @Test
    fun `IX-10 a redacted line inside a multi-line anchor grants no coverage while exact spans stay usable`() {
        val view = (1..10).joinToString("\n") { line ->
            if (line == 5) "  api_key = \"sk-proj_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123\"" else "  line $line"
        }
        val result = redaction.apply(view)
        assertEquals(Ranges.single(5, 5), result.mask.hiddenLines)

        val observation = Observation(
            id = "obs-1", ids = ids, actionId = "act-1", candidate = CandidateId(Digest.ofUtf8("s1")),
            contentRef = Digest.ofUtf8(result.text), paths = listOf("src/config.py"),
            ranges = mapOf("src/config.py" to Ranges.single(1, 10)), complete = true,
            sourceVersions = mapOf("src/config.py" to FileVersion.of(view.toByteArray())),
            captureComplete = true, redaction = result.mask,
        )

        val coverage = observation.coverage("src/config.py")
        assertEquals(Ranges.of(LineRange(1, 4), LineRange(6, 10)), coverage)
        assertFalse(5 in coverage, "a redacted line may not anchor an edit (D-49)")
        assertFalse(coverage.covers(LineRange(3, 7)), "a multi-line anchor across the redacted line is not covered")
        assertTrue(coverage.covers(LineRange(1, 4)), "exact unredacted spans remain usable")
        assertTrue(coverage.covers(LineRange(6, 10)))

        // The raw source version identity stays separate from the rendered observation (D-49).
        assertEquals(FileVersion.of(view.toByteArray()), observation.sourceVersions.getValue("src/config.py"))
        assertTrue(observation.sourceVersions.getValue("src/config.py").digest != Digest.ofUtf8(result.text))
    }

    @Test
    fun `the byte cap records a limitation and never returns the tail`() {
        val tail = "AKIAIOSFODNN7EXAMPLE"
        val text = (1..200).joinToString("\n") { "line $it padding padding padding" } + "\n$tail\n"
        val capped = Redaction(RedactionConfig(maxBytes = 512)).apply(text)

        assertFalse(capped.text.contains(tail), "the unscanned tail must not be returned")
        assertTrue(capped.text.length < text.length)
        assertEquals(1, capped.limitations.size, capped.limitations.toString())
        assertContains(capped.limitations.first(), "not scanned beyond 512 bytes")
        assertTrue(capped.text.endsWith("\n"), "the cap cuts at a line boundary so no value is split")

        // A cap that lands inside an unterminated private key hides the rest of the capture.
        val keyAtEnd = "prefix line\n" + privateKey.substringBefore("-----END")
        val cut = Redaction(RedactionConfig(maxBytes = keyAtEnd.toByteArray().size + 40)).apply(keyAtEnd + "\nMIIEowIBAAKCAQEAx3Fl6f3oQmVhP1nqTzR0nJ0\nrest of the key\n")
        assertFalse(cut.text.contains("MIIEowIBAAKCAQEAx3Fl"), "a cut private-key block must not leak its body")
        assertTrue(cut.limitations.any { it.contains("private-key block") }, cut.limitations.toString())
    }

    @Test
    fun `binary captures are hidden as a whole and empty input is untouched`() {
        val binary = ByteArray(64) { (it % 7).toByte() }
        val result = redaction.applyBytes(binary)
        assertEquals("[REDACTED:binary]", result.text)
        assertEquals(Ranges.single(1, 1), result.mask.hiddenLines)
        assertContains(result.limitations.first(), "binary capture")

        val textBytes = "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789\n".toByteArray()
        assertFalse(redaction.applyBytes(textBytes).text.contains("ghp_"))
        assertEquals("", redaction.applyBytes(ByteArray(0)).text)
        assertFalse(redaction.apply("").applied)
    }

    @Test
    fun `D-25 and D-14 protected stores are refused, so a preimage revert stays exact`() {
        assertTrue(Redaction.neverRewrite(ContentClass.NativeReplay))
        assertTrue(Redaction.neverRewrite(ContentClass.RecoveryPreimage))
        assertFalse(Redaction.neverRewrite(ContentClass.ModelFacing))
        assertFalse(Redaction.neverRewrite(ContentClass.ReusableEvidence))

        val preimage = "api_key = \"sk-proj_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123\"\nvalue = 1\n"
        val refusal = redaction.refuse(ContentClass.RecoveryPreimage)
        assertEquals(RefusalReason.ProtectedContent, refusal?.reason)
        assertFailsWith<IllegalArgumentException> { redaction.apply(preimage, ContentClass.RecoveryPreimage) }
        assertFailsWith<IllegalArgumentException> { redaction.applyBytes(preimage.toByteArray(), ContentClass.NativeReplay) }

        // A store that honours the guard keeps the preimage byte-exact, so `revert:#id` is still exact.
        fun persist(text: String, content: ContentClass): String =
            if (Redaction.neverRewrite(content)) text else redaction.apply(text, content).text
        assertEquals(preimage, persist(preimage, ContentClass.RecoveryPreimage))
        assertEquals(preimage, persist(preimage, ContentClass.NativeReplay))
        assertNotEquals(preimage, persist(preimage, ContentClass.ModelFacing))

        // Reusable evidence is redacted before persistence, so recall and export can never return the secret.
        val persisted = redaction.apply(preimage, ContentClass.ReusableEvidence)
        assertFalse(persisted.text.contains("sk-proj_"))
        assertTrue(persisted.applied)
    }

    @Test
    fun `a redacted view reaches the envelope with capture-redacted set and no secret in it`() {
        val secret = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val result = redaction.apply("cloning with token $secret\n")
        val header = EnvelopeHeader(
            resultAlias = "#12", tool = "run", effectClass = EffectClass.W, versions = emptyMap(), stamp = null,
            truncated = false, effects = Effects.Observed, flags = Boundary.present(result.text).flags,
            runtime = RuntimeFields(
                actionId = "act-9", status = "ok", candidateBefore = null, candidateAfter = null, scope = null,
                completeness = "complete", redactionApplied = result.applied,
            ),
        )
        val rendered = Envelope.render(header, Boundary.present(result.text).body, Gauge(10, true, "none", 0, 0, 1, 1, 40))
        assertFalse(rendered.contains(secret))
        assertContains(rendered, "[REDACTED:github-token]")
        assertTrue(header.runtime.redactionApplied)
    }

    @Test
    fun `the config validates its patterns and is wired into Config`() {
        val broken = RedactionConfig(patterns = listOf(RedactionPattern("bad", "([unclosed")))
        assertEquals(1, broken.violations().size)
        assertContains(broken.violations().first().message, "does not compile")
        assertFailsWith<IllegalArgumentException> { Redaction(broken) }
        assertTrue(RedactionConfig(maxBytes = 0).violations().any { it.field.endsWith("maxBytes") })
        assertTrue(
            RedactionConfig(patterns = RedactionConfig.DEFAULT_PATTERNS + RedactionPattern("jwt", "x"))
                .violations().any { it.message.contains("duplicate kind") },
        )
        assertFailsWith<IllegalArgumentException> { RedactionPattern("kind:with:colon", "x") }

        val config = Config()
        assertEquals(RedactionConfig.DEFAULT_PATTERNS, config.redaction.patterns)
        assertTrue(config.violations().none { it.field.startsWith("redaction") }, config.violations().toString())
        assertContains(config.redaction.envAllowlist, "PATH")
        assertFalse(config.redaction.envAllowlist.any { it.contains("TOKEN") || it.contains("KEY") })
        assertTrue(
            Config(redaction = broken).violations().any { it.field.startsWith("redaction") },
            "a broken pattern set is a config violation",
        )
        assertNull(config.rulesFile)
    }
}
