package io.astrolabe.atlas

/**
 * Where [ImportGraph] takes each file's [Outline] from. The default is the atlas' own tier-0 parse;
 * an optional tier-1 index (P5.4.1 `index-treesitter`) supplies syntax-tree outlines instead, which
 * is what lets the graph be [IndexTier.Syntax] and a blast narrow (§7.3, D-88).
 */
public fun interface OutlineSource {
    public fun outline(path: String): Outline
}
