package io.astrolabe.delegate

import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkspaceId
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Durable bytes survive removal of every temporary worktree. An open publication rolls back. */
internal object IntegrationPublication {
    private const val PREFIX = "publication:"

    @Serializable
    private data class Entry(val path: String, val before: Digest?, val after: Digest?, val permissions: Set<String>? = null)

    @Serializable
    private data class Manifest(val workspace: WorkspaceId, val entries: List<Entry>)

    fun stage(workspace: Workspace, changes: List<Pair<String, ByteArray?>>, blobs: BlobStore, ids: Identities): String {
        val entries = changes.map { (path, bytes) ->
            val resolved = workspace.resolve(path) as? PathResolution.Resolved ?: error("cannot stage $path")
            val before = workspace.bytes(resolved)?.let { blobs.put(it, BlobKind.PREIMAGE, ids) }
            val after = bytes?.let { blobs.put(it, BlobKind.POSTIMAGE, ids) }
            val permissions = if (before != null && Files.getFileAttributeView(resolved.real, java.nio.file.attribute.PosixFileAttributeView::class.java) != null)
                Files.getPosixFilePermissions(resolved.real).map { it.name }.toSet() else null
            Entry(path, before, after, permissions)
        }
        val manifest = blobs.put(Json.encodeToString(Manifest.serializer(), Manifest(workspace.id, entries)).toByteArray(), BlobKind.PACKET, ids)
        return PREFIX + manifest.hex
    }

    fun recover(workspace: Workspace, registry: VersionRegistry, blobs: BlobStore, intents: IntentJournal) {
        for (intent in intents.open().filter { it.actionId == "integrate" && it.argv.any { arg -> arg.startsWith(PREFIX) } }) {
            rollback(workspace, registry, blobs, intents, intent)
        }
    }

    fun rollback(workspace: Workspace, registry: VersionRegistry, blobs: BlobStore, intents: IntentJournal, intent: Intent) {
        val reference = intent.argv.single { it.startsWith(PREFIX) }
        val manifest = Json.decodeFromString(Manifest.serializer(), String(blobs.get(Digest(reference.removePrefix(PREFIX)))))
        check(manifest.workspace == workspace.id) { "publication belongs to another workspace" }
        if (intents.get(intent.intentId)?.status != IntentStatus.Unknown) intents.update(intent.intentId, IntentStatus.Unknown)
        // Check every path before restoring any: never overwrite a later host edit.
        for (entry in manifest.entries) {
            val version = registry.read(entry.path)?.version?.digest
            check(version == entry.before || version == entry.after) { "publication recovery conflicts with ${entry.path}; durable backups retained" }
        }
        for (entry in manifest.entries.asReversed()) {
            if (registry.read(entry.path)?.version?.digest == entry.before) continue
            replace(workspace, entry.path, entry.before?.let(blobs::get))
            entry.permissions?.let { permissions ->
                val target = workspace.resolve(entry.path, io.astrolabe.workspace.Intent.Mutate) as? PathResolution.Resolved ?: error("recovery path refused")
                Files.setPosixFilePermissions(target.real, permissions.map { java.nio.file.attribute.PosixFilePermission.valueOf(it) }.toSet())
            }
        }
        for (entry in manifest.entries) {
            registry.change(entry.path, entry.after?.let { io.astrolabe.id.FileVersion(it) }, entry.before?.let { io.astrolabe.id.FileVersion(it) }, "integration rollback")
        }
        intents.reconcile(intent.intentId, "restored all preimages from $reference")
    }

    fun replace(workspace: Workspace, path: String, bytes: ByteArray?) {
        val resolved = workspace.resolve(path, io.astrolabe.workspace.Intent.Mutate) as? PathResolution.Resolved
            ?: error("publication path refused: $path")
        if (bytes == null) { Files.deleteIfExists(resolved.real); return }
        Files.createDirectories(resolved.real.parent)
        val temporary = Files.createTempFile(resolved.real.parent, ".astrolabe-integration-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (Files.exists(resolved.real) && Files.getFileAttributeView(resolved.real, java.nio.file.attribute.PosixFileAttributeView::class.java) != null)
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(resolved.real))
            Files.move(temporary, resolved.real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
