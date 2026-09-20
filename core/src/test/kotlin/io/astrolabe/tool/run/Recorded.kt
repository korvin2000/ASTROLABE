package io.astrolabe.tool.run

/**
 * Recorded runner outputs under `core/src/test/resources/shaper/`. `junit-stale-green.xml` is a real Gradle
 * JUnit report from this build; the remaining files are written by hand from real runner output, because
 * pytest, jest, cargo, go, dotnet and mocha are not installed here and the suite must stay offline (§2.3).
 */
internal object Recorded {
    fun bytes(name: String): ByteArray =
        requireNotNull(Recorded::class.java.getResourceAsStream("/shaper/$name")) { "missing resource shaper/$name" }
            .use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun capture(
        resource: String? = null,
        argv: List<String>,
        exitCode: Int?,
        shell: Boolean = false,
        reports: List<ReportArtifact> = emptyList(),
        captureComplete: Boolean = true,
        timedOut: Boolean = false,
        checkId: String? = null,
        output: ByteArray? = null,
        runnerVersion: String? = null,
    ): RunCapture = RunCapture(
        actionId = "act-7",
        argv = argv,
        shell = shell,
        cwd = "/home/dev/shop",
        exitCode = exitCode,
        timedOut = timedOut,
        output = output ?: resource?.let { bytes(it) } ?: ByteArray(0),
        captureComplete = captureComplete,
        reports = reports,
        runnerVersion = runnerVersion,
        checkId = checkId,
    )

    fun report(
        resource: String,
        kind: ReportKind = ReportKind.JUnitXml,
        path: String = "build/test-results/astrolabe-act-7/$resource",
        fresh: Boolean = true,
        provenance: String = "written by this invocation to a fresh destination",
    ): ReportArtifact = ReportArtifact(
        path = path,
        kind = kind,
        freshForThisInvocation = fresh,
        provenance = provenance,
        content = bytes(resource),
    )
}
