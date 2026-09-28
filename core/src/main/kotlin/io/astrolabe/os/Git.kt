package io.astrolabe.os

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * A thin, typed wrapper over the `git` command line (D-04).
 *
 * Every operation here is either a read or a write the harness owns. The wrapper never runs
 * `reset`, `clean`, `stash`, `checkout` or `add`, never writes `.git/info/exclude`, and never lets
 * a tree-building operation touch the user's index: `updateIndex`/`writeTree` run with
 * `GIT_INDEX_FILE` pointing at a caller-supplied temporary index that this wrapper initialises
 * deliberately (D-53).
 *
 * Instances are cheap and stateless apart from the lazily resolved git directory; all methods are
 * blocking and may be called from any thread. Operations that a submodule or a sparse checkout
 * would silently corrupt re-check [unsupportedForms] each time rather than caching a shape the
 * caller may change between calls.
 */
public class Git @JvmOverloads constructor(
    repo: Path,
    public val executable: String = "git",
    private val timeoutMillis: Long = 120_000,
    private val maxOutputBytes: Int = 64 * 1024 * 1024,
) {
    init { require(timeoutMillis in 1..3_600_000 && maxOutputBytes in 1 until Int.MAX_VALUE) }

    /** The working-tree root every command runs in. */
    public val repo: Path = repo.toAbsolutePath().normalize()

    private val gitDir: Path by lazy {
        val printed = decode(run(listOf("rev-parse", "--absolute-git-dir"))).trim()
        Path.of(printed).toAbsolutePath().normalize()
    }

    // ---------------------------------------------------------------- version

    /** `git version`, parsed. */
    public fun version(): GitVersion = GitVersion.parse(decode(run(listOf("version"))))

    /**
     * Returns the local git version, or throws when it is older than the given floor. D-04: 2.20
     * is a version this wrapper is *tested* against, never a stand-in for testing a feature.
     */
    public fun requireMinimum(major: Int, minor: Int): GitVersion {
        val found = version()
        if (!found.atLeast(major, minor)) {
            throw IllegalStateException(
                "git $major.$minor or newer is required, found ${found.raw}",
            )
        }
        return found
    }

    // ----------------------------------------------------- repository shape

    /** Repository forms this wrapper refuses to snapshot or index (D-53), detected by name. */
    public fun unsupportedForms(): Set<RepositoryForm> {
        val forms = LinkedHashSet<RepositoryForm>()
        if (Files.exists(repo.resolve(".gitmodules"))) forms.add(RepositoryForm.SUBMODULES)
        val sparse = exec(listOf("config", "--bool", "--get", "core.sparseCheckout"))
        if (sparse.exitCode == 0 && decode(sparse.stdout).trim() == "true") {
            forms.add(RepositoryForm.SPARSE_CHECKOUT)
        }
        return forms
    }

    private fun requireSupportedForm(operation: String) {
        unsupportedForms().firstOrNull()?.let { throw UnsupportedRepositoryForm(it, operation) }
    }

    // ----------------------------------------------------------------- reads

    /**
     * `git status --porcelain=v2 -z --branch`. Paths in the result are repository-relative with
     * forward slashes and are never quoted, because `-z` is in force.
     */
    public fun status(
        untrackedFiles: UntrackedFiles = UntrackedFiles.ALL,
        includeIgnored: Boolean = false,
    ): GitStatus {
        requireSupportedForm("status")
        val argv = mutableListOf(
            "status",
            "--porcelain=v2",
            "-z",
            "--branch",
            "--untracked-files=${untrackedFiles.flag}",
        )
        if (includeIgnored) argv.add("--ignored=matching")
        return parsePorcelainV2(decode(run(argv)))
    }

    /** `git rev-parse --verify <rev>`; throws [GitError] when the revision does not resolve. */
    public fun revParse(rev: String): ObjectId =
        ObjectId.parse(decode(run(listOf("rev-parse", "--verify", rev))))

    /**
     * The object a full ref name points at, or `null` when the ref does not exist. `show-ref
     * --verify` takes the exact ref name and never falls back to revision-expression lookup, so
     * this cannot silently resolve something other than the named ref.
     */
    public fun readRef(ref: String): ObjectId? {
        val result = exec(listOf("show-ref", "--verify", ref))
        if (result.exitCode != 0) return null
        val line = decode(result.stdout).trim()
        if (line.isEmpty()) return null
        return ObjectId.parseOrNull(line.substringBefore(' '))
    }

    internal fun searchFiles(): ByteArray = run(listOf("ls-files", "-z", "--cached", "--others", "--exclude-standard"))

    /** `git ls-files -s -z`, optionally narrowed by pathspec. */
    public fun lsFiles(pathspec: List<String> = emptyList()): List<LsFilesEntry> {
        requireSupportedForm("ls-files")
        val argv = mutableListOf("ls-files", "-s", "-z")
        if (pathspec.isNotEmpty()) {
            argv.add("--")
            argv.addAll(pathspec)
        }
        return splitNul(decode(run(argv))).map { record ->
            // <mode> SP <id> SP <stage> TAB <path>
            val tab = record.indexOf('\t')
            require(tab > 0) { "malformed ls-files record: '$record'" }
            val head = record.substring(0, tab).split(' ')
            require(head.size == 3) { "malformed ls-files record: '$record'" }
            LsFilesEntry(
                mode = FileMode.parse(head[0]),
                id = ObjectId.parse(head[1]),
                stage = head[2].toInt(),
                path = record.substring(tab + 1),
            )
        }
    }

    /** `git ls-tree -z` over one tree or commit. */
    public fun lsTree(tree: ObjectId, recursive: Boolean = false): List<TreeEntry> {
        val argv = mutableListOf("ls-tree", "-z")
        if (recursive) argv.add("-r")
        argv.add(tree.hex)
        return splitNul(decode(run(argv))).map { record ->
            // <mode> SP <kind> SP <id> TAB <path>
            val tab = record.indexOf('\t')
            require(tab > 0) { "malformed ls-tree record: '$record'" }
            val head = record.substring(0, tab).split(' ')
            require(head.size == 3) { "malformed ls-tree record: '$record'" }
            TreeEntry(
                mode = FileMode.parse(head[0]),
                kind = TreeEntryKind.parse(head[1]),
                id = ObjectId.parse(head[2]),
                path = record.substring(tab + 1),
            )
        }
    }

    /**
     * The raw patch text of `git diff` (worktree against index) or `git diff --cached` (index
     * against HEAD). External diff drivers and textconv are disabled so the output is the
     * repository's own, not a host configuration's.
     */
    public fun diff(pathspec: List<String> = emptyList(), cached: Boolean = false): String {
        val argv = mutableListOf("diff", "--no-color", "--no-ext-diff", "--no-textconv")
        if (cached) argv.add("--cached")
        if (pathspec.isNotEmpty()) {
            argv.add("--")
            argv.addAll(pathspec)
        }
        return decode(run(argv))
    }

    /** `git show <id>` as text: a commit with its patch, or a blob's decoded content. */
    public fun show(id: ObjectId): String =
        decode(run(listOf("show", "--no-color", "--no-ext-diff", "--no-textconv", id.hex)))

    /**
     * The exact bytes of a blob. No smudge filter and no end-of-line conversion runs on this path,
     * so the result is byte-identical to what `hashObject` stored (D-53).
     */
    public fun catFile(id: ObjectId): ByteArray = run(listOf("cat-file", "blob", id.hex))

    /**
     * The checkout form of each blob as `git cat-file --batch --filters` produces it for its path:
     * smudge filters and end-of-line conversion applied. One element per request, in order; null
     * for a missing object or a failed filter. A path containing CR or LF cannot be named on a batch line.
     *
     * Git versions differ on whether a batch header reports the filtered or the stored size, so a
     * record's length is taken from [expectedSizes] or from the header only where the next header
     * (or the end of output) confirms it. The first unconfirmed record and all later ones are read
     * one object per command.
     */
    internal fun catFileSmudged(requests: List<Pair<ObjectId, String>>, expectedSizes: List<Long>): List<ByteArray?> {
        require(expectedSizes.size == requests.size) { "one expected size per request" }
        if (requests.isEmpty()) return emptyList()
        val input = StringBuilder()
        for ((id, path) in requests) {
            require('\n' !in path && '\r' !in path) { "batch path contains a line break: '$path'" }
            input.append(id.hex).append(' ').append(path).append('\n')
        }
        val out = run(listOf("cat-file", "--batch", "--filters"), stdin = input.toString().toByteArray(StandardCharsets.UTF_8))
        val results = ArrayList<ByteArray?>(requests.size)
        var pos = 0
        for (i in requests.indices) {
            var eol = pos
            while (eol < out.size && out[eol] != NEWLINE) eol++
            if (eol >= out.size) break
            val header = String(out, pos, eol - pos, StandardCharsets.UTF_8).split(' ')
            if (header.first() != requests[i].first.hex) break
            if (header.size == 2 && header[1] == "missing") {
                results.add(null)
                pos = eol + 1
                continue
            }
            val start = eol + 1
            val next = requests.getOrNull(i + 1)?.first?.hex
            val length = listOfNotNull(expectedSizes[i], header.getOrNull(2)?.toLongOrNull())
                .firstOrNull { recordEnds(out, start, it, next) }?.toInt() ?: break
            results.add(out.copyOfRange(start, start + length))
            pos = start + length + 1
        }
        for (i in results.size until requests.size) {
            val (id, path) = requests[i]
            val single = exec(listOf("cat-file", "--filters", "--path=$path", id.hex))
            results.add(if (single.exitCode == 0) single.stdout else null)
        }
        return results
    }

    private fun recordEnds(out: ByteArray, start: Int, length: Long, next: String?): Boolean {
        if (length < 0 || start + length >= out.size) return false
        val end = (start + length).toInt()
        if (out[end] != NEWLINE) return false
        if (next == null) return end + 1 == out.size
        val prefix = "$next ".toByteArray(StandardCharsets.US_ASCII)
        return end + 1 + prefix.size <= out.size && prefix.indices.all { out[end + 1 + it] == prefix[it] }
    }

    // ---------------------------------------------------------------- writes

    /**
     * Hashes [bytes] exactly as given. With [noFilters] (the default) no clean filter and no
     * end-of-line conversion runs, so the resulting id is `sha1("blob <len> " + bytes)` and
     * the raw manifest stays the authority (D-53). With [write] the object is stored in the
     * repository's object database.
     */
    public fun hashObject(
        bytes: ByteArray,
        noFilters: Boolean = true,
        write: Boolean = false,
    ): ObjectId {
        val argv = mutableListOf("hash-object")
        if (noFilters) argv.add("--no-filters")
        if (write) argv.add("-w")
        argv.add("--stdin")
        return ObjectId.parse(decode(run(argv, stdin = bytes)))
    }

    /**
     * Replaces the contents of the temporary index at [tempIndex] with exactly [entries], via
     * `git update-index -z --index-info`.
     *
     * D-53: the temporary index is initialised deliberately from the manifest, so any pre-existing
     * file at [tempIndex] is discarded first and the resulting tree is exactly [entries]. The
     * repository's own index is never read or written; passing it is refused.
     */
    public fun updateIndex(tempIndex: Path, entries: List<IndexEntry>) {
        requireSupportedForm("update-index")
        val index = requireTemporaryIndex(tempIndex)
        Files.createDirectories(index.parent)
        Files.deleteIfExists(index)
        val records = StringBuilder()
        for (entry in entries) {
            records.append(entry.mode.octal).append(' ')
                .append(entry.id.hex).append('\t')
                .append(entry.path).append(' ')
        }
        run(
            listOf("update-index", "-z", "--index-info"),
            stdin = records.toString().toByteArray(StandardCharsets.UTF_8),
            indexFile = index,
        )
    }

    /** `git write-tree` against the temporary index written by [updateIndex]. */
    public fun writeTree(tempIndex: Path): ObjectId {
        requireSupportedForm("write-tree")
        val index = requireTemporaryIndex(tempIndex)
        return ObjectId.parse(decode(run(listOf("write-tree"), indexFile = index)))
    }

    /**
     * `git commit-tree`, with the message supplied on stdin so it needs no argv quoting. When
     * [authorIdentity] is given it is used for both the author and the committer.
     */
    public fun commitTree(
        tree: ObjectId,
        parents: List<ObjectId> = emptyList(),
        message: String,
        authorIdentity: Identity? = null,
    ): ObjectId {
        val argv = mutableListOf("commit-tree", tree.hex)
        for (parent in parents) {
            argv.add("-p")
            argv.add(parent.hex)
        }
        val env = if (authorIdentity == null) {
            emptyMap()
        } else {
            mapOf(
                "GIT_AUTHOR_NAME" to authorIdentity.name,
                "GIT_AUTHOR_EMAIL" to authorIdentity.email,
                "GIT_COMMITTER_NAME" to authorIdentity.name,
                "GIT_COMMITTER_EMAIL" to authorIdentity.email,
            )
        }
        return ObjectId.parse(
            decode(run(argv, stdin = message.toByteArray(StandardCharsets.UTF_8), extraEnv = env)),
        )
    }

    /**
     * Compare-and-swap on a ref (D-04): `git update-ref <ref> <new> <old>`, where [expectedOld] of
     * `null` means "the ref must not exist" and is sent as the all-zero id. A mismatch leaves the
     * ref unchanged and raises [RefUpdateRejected].
     */
    public fun updateRef(ref: String, new: ObjectId, expectedOld: ObjectId?) {
        val old = expectedOld ?: ObjectId.zeroLike(new)
        val argv = listOf("update-ref", ref, new.hex, old.hex)
        val result = exec(argv)
        if (result.exitCode == 0) return
        val command = listOf(executable) + argv
        if (isRefContention(result.stderr)) {
            throw RefUpdateRejected(ref, expectedOld, command, result.exitCode, result.stderr)
        }
        throw GitError(command, result.exitCode, result.stderr)
    }

    /**
     * The full ref [name] (normally `HEAD`) points at symbolically, e.g. `refs/heads/main`, or `null`
     * when it is detached or does not exist (`git symbolic-ref -q`).
     */
    public fun symbolicRef(name: String = "HEAD"): String? {
        val result = exec(listOf("symbolic-ref", "-q", name))
        if (result.exitCode != 0) return null
        return decode(result.stdout).trim().ifEmpty { null }
    }

    /**
     * `git push --porcelain <remote> <source>:<destination>` without force: the remote refuses a
     * non-fast-forward update, so a push can never rewrite the destination's history (§14.2).
     */
    public fun push(remote: String, source: String, destination: String) {
        require(remote.isNotBlank() && !remote.startsWith("-")) { "remote must be a name or path, got '$remote'" }
        require(source.isNotBlank() && !source.startsWith("+") && !source.startsWith("-") && ':' !in source) {
            "push source must be a plain rev, got '$source'"
        }
        require(destination.startsWith("refs/") && ':' !in destination) {
            "push destination must be a full ref, got '$destination'"
        }
        run(listOf("push", "--porcelain", remote, "$source:$destination"))
    }

    /** `git worktree add --detach <path> <commit>` (P5.1 writer cells). */
    public fun worktreeAdd(path: Path, commit: ObjectId) {
        run(
            listOf(
                "worktree",
                "add",
                "--detach",
                path.toAbsolutePath().normalize().toString(),
                commit.hex,
            ),
        )
    }

    /** `git worktree remove [--force] <path>`. */
    public fun worktreeRemove(path: Path, force: Boolean = false) {
        val argv = mutableListOf("worktree", "remove")
        if (force) argv.add("--force")
        argv.add(path.toAbsolutePath().normalize().toString())
        run(argv)
    }

    /** Registered workspace roots, including the main tree, for external-state validation. */
    // `worktree list -z` needs git 2.36; the newline form works from the D-04 minimum (2.20).
    internal fun worktreeRoots(): List<Path> =
        decode(run(listOf("worktree", "list", "--porcelain")))
            .lineSequence()
            .map { it.removeSuffix("\r") }
            .filter { it.startsWith("worktree ") }
            .toList()
            .map { Path.of(it.removePrefix("worktree ")).toAbsolutePath().normalize() }

    /**
     * Root commits reachable from any ref outside the harness's `refs/astrolabe/` namespace
     * (`git rev-list --max-parents=0 --exclude=<that namespace> --all`), sorted by name. An unborn repository has none. Part of the
     * repository identity that keys the external project state root (D-44): a shadow snapshot 0 is a
     * parentless commit, and counting it would move the store the moment the first campaign opened.
     */
    public fun rootCommits(): List<ObjectId> =
        decode(run(listOf("rev-list", "--max-parents=0", "--exclude=refs/astrolabe/*", "--all")))
            .lineSequence()
            .mapNotNull { ObjectId.parseOrNull(it) }
            .sortedBy { it.hex }
            .toList()

    /**
     * `git rev-parse --git-common-dir` resolved against [repo] and canonicalised. Every linked
     * worktree of a repository reports the same directory, which is what lets them share one
     * project store while keeping distinct workspace ids (D-44, D-15).
     */
    public fun commonDir(): Path {
        val printed = decode(run(listOf("rev-parse", "--git-common-dir"))).trim()
        val resolved = repo.resolve(printed).toAbsolutePath().normalize()
        return runCatching { resolved.toRealPath() }.getOrDefault(resolved)
    }

    // ------------------------------------------------------------- internals

    /** The user indexes no temporary-index operation may name: this worktree's and the main one's. */
    private val protectedIndexes: Set<Path> by lazy {
        setOf(canonical(gitDir.resolve("index")), canonical(commonDir().resolve("index")))
    }

    private fun requireTemporaryIndex(tempIndex: Path): Path {
        val index = tempIndex.toAbsolutePath().normalize()
        // D-53: the user's staged index is untouched, so a temp-index operation that names it is a
        // programming error rather than a git failure. The comparison is on what the filesystem
        // reports, never on the spelling: a Windows 8.3 short path and a symlinked temporary
        // directory are different spellings of one file, and a lexical check lets the user's index
        // through (observed on a CI runner whose TEMP is the short form of the home directory).
        require(canonical(index) !in protectedIndexes) {
            "refusing to use the repository index at $index as a temporary index — see D-53"
        }
        return index
    }

    /** [path] as the filesystem spells it; a path that does not exist yet resolves via its parent. */
    private fun canonical(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        runCatching { absolute.toRealPath() }.getOrNull()?.let { return it }
        val parent = absolute.parent ?: return absolute
        val name = absolute.fileName ?: return absolute
        return runCatching { parent.toRealPath().resolve(name) }.getOrDefault(absolute)
    }

    private fun run(
        argv: List<String>,
        stdin: ByteArray? = null,
        indexFile: Path? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): ByteArray {
        val result = exec(argv, stdin, indexFile, extraEnv)
        if (result.exitCode != 0) {
            throw GitError(listOf(executable) + argv, result.exitCode, result.stderr)
        }
        return result.stdout
    }

    private fun exec(
        argv: List<String>,
        stdin: ByteArray? = null,
        indexFile: Path? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Execution {
        val command = ArrayList<String>(argv.size + 1)
        command.add(executable)
        command.addAll(argv)
        val builder = ProcessBuilder(command).directory(repo.toFile())
        applyEnvironment(builder, indexFile, extraEnv)
        val process = try {
            builder.start()
        } catch (failure: IOException) {
            throw GitError(command, START_FAILED, "cannot start '$executable': ${failure.message}")
        }
        fun read(stream: java.io.InputStream, limit: Int): ByteArray = stream.use {
            val bytes = it.readNBytes(limit + 1)
            if (bytes.size > limit) throw IOException("git capture exceeds $limit bytes; no truncated data returned")
            bytes
        }
        fun <T> pump(name: String, block: () -> T): FutureTask<T> = FutureTask(java.util.concurrent.Callable(block)).also {
            Thread.ofVirtual().name(name).start(it)
        }
        val stdout = pump("git-stdout") { read(process.inputStream, maxOutputBytes) }
        val stderr = pump("git-stderr") { read(process.errorStream, minOf(maxOutputBytes, 1024 * 1024)) }
        val input = pump("git-stdin") { process.outputStream.use { if (stdin != null) it.write(stdin) } }
        val pumps = listOf(stdout, stderr, input)
        val descendants = LinkedHashMap<Long, ProcessHandle>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (true) {
                process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
                pumps.filter { it.isDone }.forEach { it.get() }
                if (!process.isAlive && pumps.all { it.isDone }) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("git deadline exceeded after $timeoutMillis ms")
                if (process.isAlive) process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)
                else Thread.sleep(10)
            }
            return Execution(process.exitValue(), stdout.get(), decode(stderr.get()))
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GitError(command, START_FAILED, "git interrupted; process terminated")
        } catch (failure: Exception) {
            val detail = failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName
            val diagnostic = if (stderr.isDone) runCatching { decode(stderr.get()).take(4096) }.getOrDefault("") else ""
            throw GitError(command, START_FAILED, "$detail\n$diagnostic")
        } finally {
            process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
            descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            pumps.forEach { it.cancel(true) }
            // Pipe close may contend with a pump; cleanup must not extend the command deadline.
            Thread.ofVirtual().name("git-close").start {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }

    private fun applyEnvironment(
        builder: ProcessBuilder,
        indexFile: Path?,
        extraEnv: Map<String, String>,
    ) {
        val environment = builder.environment()
        // Keep only what git needs to run and find the user's configuration; dropping the rest
        // also drops any ambient GIT_DIR / GIT_WORK_TREE / GIT_INDEX_FILE that would silently
        // redirect these commands away from `repo`.
        val kept = LinkedHashMap<String, String>()
        for (name in INHERITED_ENVIRONMENT) {
            environment[name]?.let { kept[name] = it }
        }
        environment.clear()
        environment.putAll(kept)
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["LC_ALL"] = "C"
        environment["GIT_OPTIONAL_LOCKS"] = "0"
        if (indexFile != null) {
            environment["GIT_INDEX_FILE"] = indexFile.toString()
        }
        environment.putAll(extraEnv)
    }

    private class Execution(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    private companion object {
        /** Exit code stand-in for "the process never ran". */
        const val START_FAILED = -1

        const val NEWLINE: Byte = 0x0A

        val INHERITED_ENVIRONMENT = listOf(
            "PATH", "PATHEXT", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "SystemRoot", "SystemDrive", "windir", "COMSPEC",
            "TEMP", "TMP", "TMPDIR", "USER", "LOGNAME", "SHELL",
        )
    }
}

private fun decode(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

/** `LC_ALL=C` keeps these messages in English, so matching them is stable across hosts. */
private fun isRefContention(stderr: String): Boolean =
    stderr.contains("but expected") ||
        stderr.contains("reference already exists") ||
        stderr.contains("unable to resolve reference")

private fun splitNul(text: String): List<String> =
    text.split(' ').filter { it.isNotEmpty() }

/**
 * Parses `git status --porcelain=v2 -z --branch` in a single pass. Under `-z` every header line
 * and every entry is NUL-terminated, and a `2` (rename/copy) entry spends a second NUL-terminated
 * field on the original path instead of the TAB-separated form used without `-z`.
 */
internal fun parsePorcelainV2(text: String): GitStatus {
    val fields = splitNul(text)
    var oid: ObjectId? = null
    var head: String? = null
    var upstream: String? = null
    var ahead: Int? = null
    var behind: Int? = null
    var sawBranchHeader = false
    val entries = ArrayList<StatusEntry>()
    var i = 0
    while (i < fields.size) {
        val field = fields[i]
        i++
        when (field.firstOrNull()) {
            '#' -> {
                sawBranchHeader = true
                val parts = field.split(' ', limit = 3)
                when (parts.getOrNull(1)) {
                    "branch.oid" -> oid = ObjectId.parseOrNull(parts.getOrElse(2) { "" })
                    "branch.head" -> head = parts.getOrElse(2) { "" }.takeUnless { it == "(detached)" }
                    "branch.upstream" -> upstream = parts.getOrElse(2) { "" }
                    "branch.ab" -> {
                        val counts = parts.getOrElse(2) { "" }.split(' ')
                        ahead = counts.getOrNull(0)?.removePrefix("+")?.toIntOrNull()
                        behind = counts.getOrNull(1)?.removePrefix("-")?.toIntOrNull()
                    }
                }
            }

            '1' -> {
                // 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                val p = field.split(' ', limit = 9)
                require(p.size == 9) { "malformed porcelain v2 ordinary entry: '$field'" }
                entries.add(
                    StatusEntry.Ordinary(
                        index = StatusCode.parse(p[1][0]),
                        worktree = StatusCode.parse(p[1][1]),
                        submodule = SubmoduleState.parse(p[2]),
                        headMode = FileMode.parse(p[3]),
                        indexMode = FileMode.parse(p[4]),
                        worktreeMode = FileMode.parse(p[5]),
                        headId = ObjectId.parse(p[6]),
                        indexId = ObjectId.parse(p[7]),
                        path = p[8],
                    ),
                )
            }

            '2' -> {
                // 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <X><score> <path> NUL <origPath>
                val p = field.split(' ', limit = 10)
                require(p.size == 10) { "malformed porcelain v2 rename entry: '$field'" }
                require(i < fields.size) { "porcelain v2 rename entry without an original path" }
                val origPath = fields[i]
                i++
                entries.add(
                    StatusEntry.Renamed(
                        index = StatusCode.parse(p[1][0]),
                        worktree = StatusCode.parse(p[1][1]),
                        submodule = SubmoduleState.parse(p[2]),
                        headMode = FileMode.parse(p[3]),
                        indexMode = FileMode.parse(p[4]),
                        worktreeMode = FileMode.parse(p[5]),
                        headId = ObjectId.parse(p[6]),
                        indexId = ObjectId.parse(p[7]),
                        origin = ChangeOrigin.parse(p[8][0]),
                        similarityPercent = p[8].substring(1).toInt(),
                        path = p[9],
                        origPath = origPath,
                    ),
                )
            }

            'u' -> {
                // u <XY> <sub> <m1> <m2> <m3> <mW> <h1> <h2> <h3> <path>
                val p = field.split(' ', limit = 11)
                require(p.size == 11) { "malformed porcelain v2 unmerged entry: '$field'" }
                entries.add(
                    StatusEntry.Unmerged(
                        index = StatusCode.parse(p[1][0]),
                        worktree = StatusCode.parse(p[1][1]),
                        submodule = SubmoduleState.parse(p[2]),
                        stage1Mode = FileMode.parse(p[3]),
                        stage2Mode = FileMode.parse(p[4]),
                        stage3Mode = FileMode.parse(p[5]),
                        worktreeMode = FileMode.parse(p[6]),
                        stage1Id = ObjectId.parse(p[7]),
                        stage2Id = ObjectId.parse(p[8]),
                        stage3Id = ObjectId.parse(p[9]),
                        path = p[10],
                    ),
                )
            }

            '?' -> entries.add(StatusEntry.Untracked(field.substring(2)))
            '!' -> entries.add(StatusEntry.Ignored(field.substring(2)))
            else -> throw IllegalArgumentException("unknown porcelain v2 record: '$field'")
        }
    }
    val branch = if (sawBranchHeader) BranchInfo(oid, head, upstream, ahead, behind) else null
    return GitStatus(branch, entries)
}
