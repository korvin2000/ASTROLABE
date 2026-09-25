package io.astrolabe.index.treesitter

import io.astrolabe.tool.edit.SyntaxResult
import org.treesitter.TSNode

/**
 * The D-10 syntax verdict of one tree: both ERROR and MISSING nodes count. The first one in
 * document order names the line; the message counts all of them up to a bound.
 */
internal object TreeSyntax {
    private const val MAX_REPORTED = 20

    fun of(root: TSNode): SyntaxResult {
        if (!root.hasError()) return SyntaxResult.Ok
        val found = ArrayList<TSNode>()
        collect(root, found)
        val first = found.firstOrNull()
            // has_error without a located node: still an error, never an Ok.
            ?: return SyntaxResult.Error(null, "tree-sitter: syntax error")
        val line = first.startPoint.row + 1
        val column = first.startPoint.column + 1
        val what = if (first.isMissing) "missing \"${first.type}\"" else "unexpected syntax (ERROR node)"
        val more = if (found.size > 1) " (${countLabel(found.size)} syntax errors)" else ""
        return SyntaxResult.Error(line, "tree-sitter: $what at line $line column $column$more")
    }

    private fun countLabel(count: Int): String = if (count >= MAX_REPORTED) "$MAX_REPORTED+" else "$count"

    private fun collect(node: TSNode, found: MutableList<TSNode>) {
        val count = node.childCount
        for (i in 0 until count) {
            if (found.size >= MAX_REPORTED) return
            val child = node.getChild(i)
            when {
                child.isMissing || child.isError -> found += child
                child.hasError() -> collect(child, found)
            }
        }
    }
}
