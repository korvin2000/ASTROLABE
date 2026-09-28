package io.astrolabe.atlas

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path

/**
 * D-287: whether an unresolved bare JS/TS specifier is certainly external. It is when it names a Node builtin
 * or a package declared in the importer's nearest `package.json`, and no tsconfig/jsconfig `paths` alias or
 * `baseUrl` directory of the importer could map it to a local file. A malformed or unreadable manifest or
 * config answers "not external", so the importer stays incomplete; bundler aliases are not read and an
 * undeclared name is never external.
 */
internal class ScriptExternals(private val root: Path, private val known: Set<String>) {
    private val declared = HashMap<String, Set<String>?>()
    private val aliases = HashMap<String, Aliases?>()
    private val nearestCache = HashMap<Pair<String, List<String>>, String?>()

    fun isExternal(importer: String, specifier: String): Boolean {
        val name = packageName(specifier) ?: return false
        val directory = importer.substringBeforeLast('/', "")
        val alias = nearest(directory, CONFIGS)?.let { config(it, depth = 0) ?: return false }
        if (alias != null && alias.couldBeLocal(specifier, name, known)) return false
        if (name in NODE_BUILTINS) return true
        val manifest = nearest(directory, MANIFEST) ?: return false
        return name in (dependencies(manifest) ?: return false)
    }

    /** The nearest file named one of [names], walking up from [directory]; `tsconfig.json` beats a sibling `jsconfig.json`. */
    private fun nearest(directory: String, names: List<String>): String? {
        val key = directory to names
        if (key in nearestCache) return nearestCache[key]
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val found = names.map { prefix + it }.firstOrNull { it in known || readRelative(root, it) != null }
            ?: if (directory.isEmpty()) null else nearest(directory.substringBeforeLast('/', ""), names)
        nearestCache[key] = found
        return found
    }

    /** `null` when the manifest cannot be read or parsed. */
    private fun dependencies(manifest: String): Set<String>? {
        if (manifest in declared) return declared[manifest]
        val names = parse(manifest)?.let { json -> DEPENDENCY_SECTIONS.flatMapTo(HashSet()) { section -> (json[section] as? JsonObject)?.keys.orEmpty() } }
        declared[manifest] = names
        return names
    }

    /** `null` when the config (or a relative `extends` of it) cannot be read or parsed. */
    private fun config(path: String, depth: Int): Aliases? {
        if (depth > MAX_EXTENDS) return null
        if (path in aliases) return aliases[path]
        val json = parse(path)
        val options = json?.get("compilerOptions") as? JsonObject
        val directory = path.substringBeforeLast('/', "")
        val baseUrl = (options?.get("baseUrl") as? JsonPrimitive)?.contentOrNull
        val baseDirectory = baseUrl?.let { resolve(directory, it) }
        val extends = (json?.get("extends") as? JsonPrimitive)?.contentOrNull
        val result = when {
            json == null || (baseUrl != null && baseDirectory == null) -> null
            else -> {
                val own = Aliases((options?.get("paths") as? JsonObject)?.keys.orEmpty(), baseDirectory)
                if (extends == null || !extends.startsWith(".")) own
                else resolve(directory, extends)?.let { target -> config(if (target.endsWith(".json")) target else "$target.json", depth + 1) }
                    ?.let { parent -> Aliases(parent.patterns + own.patterns, own.baseDirectory ?: parent.baseDirectory) }
            }
        }
        aliases[path] = result
        return result
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(path: String): JsonObject? = try {
        readRelative(root, path)?.toString(Charsets.UTF_8)?.let { LENIENT.parseToJsonElement(it) as? JsonObject }
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Aliases(val patterns: Set<String>, val baseDirectory: String?) {
        fun couldBeLocal(specifier: String, name: String, known: Set<String>): Boolean {
            if (patterns.any { pattern ->
                    val star = pattern.indexOf('*')
                    if (star < 0) specifier == pattern else specifier.startsWith(pattern.substring(0, star)) && specifier.endsWith(pattern.substring(star + 1))
                }) return true
            val base = baseDirectory ?: return false
            val first = join(base, name)
            return known.any { it.startsWith("$first/") || it.startsWith("$first.") }
        }
    }

    companion object {
        private const val MAX_EXTENDS = 5
        private val CONFIGS = listOf("tsconfig.json", "jsconfig.json")
        private val MANIFEST = listOf("package.json")
        private val DEPENDENCY_SECTIONS = listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")

        @OptIn(ExperimentalSerializationApi::class)
        private val LENIENT = Json { allowComments = true; allowTrailingComma = true }

        /** Node's builtin modules (a fixed list; `node:`-prefixed specifiers are external before this). */
        internal val NODE_BUILTINS = setOf(
            "assert", "async_hooks", "buffer", "child_process", "cluster", "console", "constants", "crypto", "dgram",
            "diagnostics_channel", "dns", "domain", "events", "fs", "http", "http2", "https", "inspector", "module", "net",
            "os", "path", "perf_hooks", "process", "punycode", "querystring", "readline", "repl", "stream", "string_decoder",
            "sys", "timers", "tls", "trace_events", "tty", "url", "util", "v8", "vm", "wasi", "worker_threads", "zlib",
        )

        /** `@scope/pkg/sub` → `@scope/pkg`; `pkg/sub` → `pkg`; `null` for a malformed scoped name. */
        internal fun packageName(specifier: String): String? {
            val parts = specifier.split('/')
            return if (specifier.startsWith("@")) {
                if (parts.size < 2 || parts[0].length < 2 || parts[1].isEmpty()) null else "${parts[0]}/${parts[1]}"
            } else parts[0].takeIf { it.isNotEmpty() }
        }

        private fun join(directory: String, relative: String): String = if (directory.isEmpty()) relative else "$directory/$relative"

        /** [relative] against [directory] with `.` and `..` folded; `null` when it leaves the workspace root. */
        private fun resolve(directory: String, relative: String): String? {
            val out = ArrayList<String>()
            for (segment in join(directory, relative).replace('\\', '/').split('/')) when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out += segment
            }
            return out.joinToString("/")
        }
    }
}
