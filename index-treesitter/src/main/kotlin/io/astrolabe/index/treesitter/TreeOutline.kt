package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Declaration
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.Outline
import org.treesitter.TSNode

/**
 * Tier-1 outlines from a syntax tree (§7.2): the tier-0 vocabulary and export rules of
 * [io.astrolabe.atlas.Declaration], with spans taken from the tree instead of estimated.
 *
 * A declaration's span starts at its name's line, so leading decorators and annotations stay
 * outside it exactly as at tier 0, and ends at the node's last line (D-212). Import and export
 * entries span their whole statement. [Outline.complete] is `true` only for an error-free tree.
 */
internal class TreeOutline private constructor(private val bytes: ByteArray) {
    private enum class Scope { Top, Container, Body }

    private val entries = ArrayList<Declaration>()
    private var namespace: String? = null

    companion object {
        fun of(path: String, grammar: Grammar, root: TSNode, bytes: ByteArray): Outline {
            val builder = TreeOutline(bytes)
            when (grammar) {
                Grammar.Python -> {
                    for (child in root.named()) builder.python(child, Scope.Top, direct = true)
                    builder.namespace = pythonModule(path)
                }
                Grammar.JavaScript, Grammar.TypeScript, Grammar.Tsx -> for (child in root.named()) builder.script(child, Scope.Top, exported = false)
                Grammar.Java -> for (child in root.named()) builder.java(child, Scope.Top)
                Grammar.Kotlin -> for (child in root.named()) builder.kotlin(child, Scope.Top)
            }
            builder.entries.sortWith(compareBy({ it.from }, { it.name }))
            return Outline(
                path = path,
                language = grammar.language,
                entries = builder.entries.toList(),
                namespace = builder.namespace,
                tier = IndexTier.Syntax,
                complete = !root.hasError(),
            )
        }

        /** `pay/handlers/user.py` → `pay.handlers.user`; `pay/__init__.py` → `pay` (the tier-0 rule). */
        private fun pythonModule(path: String): String? {
            if (!path.endsWith(".py") && !path.endsWith(".pyi")) return null
            val parts = path.substringBeforeLast('.').split('/').filter { it.isNotEmpty() }
            val trimmed = if (parts.lastOrNull() == "__init__") parts.dropLast(1) else parts
            return trimmed.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        private val SCRIPT_CONTAINERS = setOf(
            "class_declaration", "abstract_class_declaration", "interface_declaration",
            "type_alias_declaration", "enum_declaration",
        )
        private val SCRIPT_FUNCTIONS = setOf("function_declaration", "generator_function_declaration", "function_signature")
        private val SCRIPT_MEMBERS = setOf("method_definition", "method_signature", "abstract_method_signature")
        private val JAVA_TYPES = setOf(
            "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration",
        )
        private val JAVA_MEMBERS = setOf(
            "method_declaration", "constructor_declaration", "compact_constructor_declaration",
            "annotation_type_element_declaration",
        )
        private val KOTLIN_HIDDEN = setOf("private", "internal", "protected")
    }

    // ------------------------------------------------------------------ Python

    private fun python(node: TSNode, scope: Scope, direct: Boolean) {
        when (node.type) {
            "import_statement" -> {
                for (i in 0 until node.childCount) {
                    if (node.getFieldNameForChild(i) != "name") continue
                    val name = node.getChild(i)
                    val target = if (name.type == "aliased_import") name.field("name") else name
                    target?.let { import(text(it), node) }
                }
            }

            "import_from_statement" -> node.field("module_name")?.let { import(text(it), node) }
            "future_import_statement" -> import("__future__", node)
            "decorated_definition" -> node.field("definition")?.let { python(it, scope, direct) }
            "class_definition" -> {
                val name = node.field("name") ?: return
                val exported = scope == Scope.Top && direct && !text(name).startsWith('_')
                declare(DeclarationKind.Class, name, node, exported)
                node.field("body")?.let { body -> for (child in body.named()) python(child, Scope.Container, direct = false) }
            }

            "function_definition" -> {
                val name = node.field("name") ?: return
                val top = scope == Scope.Top
                val exported = top && direct && !text(name).startsWith('_')
                declare(if (top) DeclarationKind.Function else DeclarationKind.Method, name, node, exported)
                node.field("body")?.let { body -> for (child in body.named()) python(child, Scope.Body, direct = false) }
            }

            "expression_statement" -> {
                if (scope != Scope.Top || !direct) return
                val assignment = node.named().firstOrNull { it.type == "assignment" } ?: return
                val left = assignment.field("left")?.takeIf { it.type == "identifier" } ?: return
                if (assignment.field("right") == null) return
                declare(DeclarationKind.Const, left, node, exported = !text(left).startsWith('_'))
            }

            else -> for (child in node.named()) python(child, scope, direct = false)
        }
    }

    // --------------------------------------------------------- JavaScript/TS

    private fun script(node: TSNode, scope: Scope, exported: Boolean) {
        val type = node.type
        when {
            type == "import_statement" -> node.field("source")?.let { import(unquote(it), node) }
            type == "export_statement" -> {
                node.field("source")?.let { import(unquote(it), node) }
                node.named().firstOrNull { it.type == "export_clause" }?.let { clause ->
                    for (specifier in clause.named()) {
                        if (specifier.type != "export_specifier") continue
                        val name = (specifier.field("alias") ?: specifier.field("name"))?.let(::text) ?: continue
                        if (name != "default") add(DeclarationKind.Export, name, line(node), endLine(node), exported = true)
                    }
                }
                val declaration = node.field("declaration")
                if (declaration != null) {
                    script(declaration, scope, exported = true)
                } else {
                    for (child in node.named()) if (child.type != "export_clause") script(child, scope, exported = false)
                }
            }

            type in SCRIPT_FUNCTIONS -> {
                val name = node.field("name")
                val top = scope == Scope.Top
                if (name != null) {
                    declare(if (top) DeclarationKind.Function else DeclarationKind.Method, name, node, exported && top)
                }
                node.field("body")?.let { script(it, Scope.Body, exported = false) }
            }

            type in SCRIPT_CONTAINERS -> {
                node.field("name")?.let { declare(DeclarationKind.Class, it, node, exported) }
                node.field("body")?.let { body -> for (child in body.named()) script(child, Scope.Container, exported = false) }
            }

            type in SCRIPT_MEMBERS -> {
                node.field("name")?.let { declare(DeclarationKind.Method, it, node, exported = false) }
                node.field("body")?.let { script(it, Scope.Body, exported = false) }
            }

            type == "lexical_declaration" || type == "variable_declaration" -> {
                for (declarator in node.named()) {
                    if (declarator.type != "variable_declarator") continue
                    val name = declarator.field("name")
                    if (scope == Scope.Top && name != null && name.type == "identifier") {
                        add(DeclarationKind.Const, text(name), line(name), maxOf(endLine(node), line(name)), exported)
                    }
                    declarator.field("value")?.let { script(it, Scope.Body, exported = false) }
                }
            }

            type == "call_expression" -> {
                val function = node.field("function")
                val argument = node.field("arguments")?.named()?.firstOrNull()
                if (function != null && text(function) == "require" && argument != null && argument.type == "string") {
                    import(unquote(argument), node)
                }
                for (child in node.named()) script(child, scope, exported = false)
            }

            type == "ambient_declaration" -> for (child in node.named()) script(child, scope, exported)
            else -> for (child in node.named()) script(child, scope, exported = false)
        }
    }

    // -------------------------------------------------------------------- Java

    private fun java(node: TSNode, scope: Scope) {
        val type = node.type
        when {
            type == "package_declaration" ->
                namespace = node.named().firstOrNull { it.type == "scoped_identifier" || it.type == "identifier" }?.let(::text)

            type == "import_declaration" ->
                node.named().firstOrNull { it.type == "scoped_identifier" || it.type == "identifier" }?.let { import(text(it), node) }

            type in JAVA_TYPES -> {
                node.field("name")?.let { declare(DeclarationKind.Class, it, node, exported = hasModifier(node, "public")) }
                node.field("body")?.let { body -> walkJavaBody(body) }
            }

            type in JAVA_MEMBERS -> {
                node.field("name")?.let { declare(DeclarationKind.Method, it, node, exported = false) }
                node.field("body")?.let { java(it, Scope.Body) }
            }

            (type == "field_declaration" || type == "constant_declaration") && scope == Scope.Container -> {
                for (i in 0 until node.childCount) {
                    if (node.getFieldNameForChild(i) != "declarator") continue
                    val declarator = node.getChild(i)
                    declarator.field("name")?.let { add(DeclarationKind.Other, text(it), line(it), maxOf(endLine(node), line(it)), exported = false) }
                    declarator.field("value")?.let { java(it, Scope.Body) }
                }
            }

            else -> for (child in node.named()) java(child, scope)
        }
    }

    /** A type body's members; an enum's members sit one level down, in `enum_body_declarations`. */
    private fun walkJavaBody(body: TSNode) {
        for (child in body.named()) {
            if (child.type == "enum_body_declarations") walkJavaBody(child) else java(child, Scope.Container)
        }
    }

    private fun hasModifier(node: TSNode, modifier: String): Boolean {
        val modifiers = node.named().firstOrNull { it.type == "modifiers" } ?: return false
        return modifiers.all().any { it.type == modifier }
    }

    // ------------------------------------------------------------------ Kotlin

    private fun kotlin(node: TSNode, scope: Scope) {
        when (node.type) {
            "package_header" -> namespace = node.named().firstOrNull { it.type == "identifier" }?.let(::text)
            // An `import_header` runs on to the next token; its identifier is where the import ends.
            "import_header" -> node.named().firstOrNull { it.type == "identifier" }?.let { import(text(it), node, it) }
            "class_declaration", "object_declaration" -> {
                node.named().firstOrNull { it.type == "type_identifier" }?.let { declare(DeclarationKind.Class, it, node, visible(node)) }
                for (child in node.named()) {
                    when (child.type) {
                        "primary_constructor" -> for (parameter in child.named()) {
                            if (parameter.type != "class_parameter") continue
                            if (parameter.named().none { it.type == "binding_pattern_kind" }) continue
                            parameter.named().firstOrNull { it.type == "simple_identifier" }
                                ?.let { declare(DeclarationKind.Other, it, parameter, exported = false) }
                        }

                        "class_body", "enum_class_body" -> for (member in child.named()) kotlin(member, Scope.Container)
                        else -> Unit
                    }
                }
            }

            "companion_object" -> for (child in node.named()) {
                if (child.type == "class_body") for (member in child.named()) kotlin(member, Scope.Container)
            }

            "function_declaration" -> {
                val top = scope == Scope.Top
                node.named().firstOrNull { it.type == "simple_identifier" }?.let {
                    declare(if (top) DeclarationKind.Function else DeclarationKind.Method, it, node, top && visible(node))
                }
                node.named().firstOrNull { it.type == "function_body" }?.let { kotlin(it, Scope.Body) }
            }

            "property_declaration" -> {
                val name = node.named().firstOrNull { it.type == "variable_declaration" }
                    ?.named()?.firstOrNull { it.type == "simple_identifier" }
                if (name != null) {
                    when (scope) {
                        Scope.Top -> declare(DeclarationKind.Const, name, node, visible(node))
                        Scope.Container -> declare(DeclarationKind.Other, name, node, exported = false)
                        Scope.Body -> Unit
                    }
                }
                for (child in node.named()) if (child.type != "variable_declaration" && child.type != "modifiers") kotlin(child, Scope.Body)
            }

            "type_alias" -> node.named().firstOrNull { it.type == "type_identifier" }?.let { declare(DeclarationKind.Class, it, node, visible(node)) }
            else -> for (child in node.named()) kotlin(child, scope)
        }
    }

    private fun visible(node: TSNode): Boolean {
        val modifiers = node.named().firstOrNull { it.type == "modifiers" } ?: return true
        return modifiers.named().none { it.type == "visibility_modifier" && text(it) in KOTLIN_HIDDEN }
    }

    // ----------------------------------------------------------------- common

    private fun import(target: String, statement: TSNode, last: TSNode = statement) {
        if (target.isNotEmpty()) add(DeclarationKind.Import, target, line(statement), endLine(last), exported = false)
    }

    private fun declare(kind: DeclarationKind, name: TSNode, node: TSNode, exported: Boolean) {
        val from = line(name)
        add(kind, text(name), from, maxOf(endLine(node), from), exported)
    }

    private fun add(kind: DeclarationKind, name: String, from: Int, to: Int, exported: Boolean) {
        val cleaned = name.trim().trim('`')
        if (cleaned.isNotEmpty()) entries += Declaration(kind, cleaned, from, to, exported)
    }

    private fun text(node: TSNode): String {
        val start = node.startByte.coerceIn(0, bytes.size)
        val end = node.endByte.coerceIn(start, bytes.size)
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    /** A string literal's content: its fragments, without the quotes. */
    private fun unquote(node: TSNode): String = text(node).trim().trim('"', '\'', '`')

    private fun line(node: TSNode): Int = node.startPoint.row + 1

    /** The last line a node occupies; a node ending at column 0 ends on the line before. */
    private fun endLine(node: TSNode): Int {
        val end = node.endPoint
        val start = node.startPoint
        return (if (end.column == 0 && end.row > start.row) end.row else end.row + 1)
    }
}

private fun TSNode.field(name: String): TSNode? = getChildByFieldName(name)?.takeUnless { it.isNull }

private fun TSNode.named(): List<TSNode> = (0 until namedChildCount).map { getNamedChild(it) }

private fun TSNode.all(): List<TSNode> = (0 until childCount).map { getChild(it) }
