#!/usr/bin/env python3
"""Validate this documentation package, never the proposed agent runtime.

Python 3.10+; standard library only. No example code or external command is run.
"""
from __future__ import annotations
import collections
import difflib
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

from doclib import sections, render_fragment, sha

ROOT = Path(__file__).resolve().parent.parent
AUDIT = ROOT / 'audit'


def read_json(name: str):
    return json.loads((AUDIT / name).read_text(encoding='utf-8'))


def unfenced(text: str) -> tuple[str, bool]:
    """Return Markdown outside code fences, with inline-code literals removed."""
    out: list[str] = []
    fence: tuple[str, int] | None = None
    for line in text.splitlines():
        m = re.match(r'^\s*(`{3,}|~{3,})(.*)$', line)
        if m:
            token, tail = m.groups()
            if fence is None:
                fence = token[0], len(token)
            elif token[0] == fence[0] and len(token) >= fence[1] and not tail.strip():
                fence = None
            continue
        if fence is None:
            out.append(re.sub(r'(`+).*?\1', '', line))
    return '\n'.join(out), fence is None


def anchors_of(text: str) -> set[str]:
    explicit = re.findall(r'<a\s+id=["\']([^"\']+)["\']\s*>', text)
    result = set(explicit)
    plain, _ = unfenced(text)
    seen: collections.Counter[str] = collections.Counter()
    for line in plain.splitlines():
        m = re.match(r'^#{1,6}\s+(.+?)(?:\s+#+)?$', line)
        if not m:
            continue
        title = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', m.group(1)).lower()
        title = re.sub(r'<[^>]+>', '', title)
        slug = re.sub(r'[^\w\- ]', '', title, flags=re.UNICODE).replace(' ', '-')
        n = seen[slug]
        result.add(slug if n == 0 else f'{slug}-{n}')
        seen[slug] += 1
    return result


def main() -> int:
    errors: list[str] = []
    counts: dict[str, int | float | bool] = {}

    def check(condition: bool, message: str):
        if not condition:
            errors.append(message)

    sources = read_json('source-manifest.json')
    for record in sources:
        p = ROOT / record['path']
        check(p.is_file(), f'Missing source: {p}')
        if not p.is_file():
            continue
        data = p.read_bytes()
        check(len(data) == record['bytes'], f'Source size: {record["path"]}')
        check(hashlib.sha256(data).hexdigest() == record['sha256'], f'Source hash: {record["path"]}')
    counts['original_sources_verified'] = len(sources)

    original = (ROOT/'sources/SOTA-BEST-MIXED-AGENT.md').read_text(encoding='utf-8')
    revised = original
    edits = read_json('edits.json')
    for n, edit in enumerate(edits, 1):
        found = revised.count(edit['old'])
        if found != edit['count']:
            raise ValueError(f'Edit {n} {edit["change_id"]}: expected {edit["count"]} occurrences; found {found}')
        revised = revised.replace(edit['old'], edit['new'])
    counts['content_edit_operations_replayed'] = len(edits)
    counts['correction_groups'] = len({e['change_id'] for e in edits})

    expected_diff = ''.join(difflib.unified_diff(
        original.splitlines(True), revised.splitlines(True),
        fromfile='sources/SOTA-BEST-MIXED-AGENT.md', tofile='corrected-logical-baseline.md'))
    check(expected_diff == (AUDIT/'semantic-changes.patch').read_text(encoding='utf-8'), 'Content-only diff does not match replay')

    old_sections = {s['id']: s for s in sections(original)}
    new_sections = {s['id']: s for s in sections(revised)}
    mapping_rows = read_json('section-map.json')
    mapping = {r['id']: r for r in mapping_rows}
    check(len(mapping_rows) == len(mapping), 'Duplicate section in map')
    check(set(old_sections) == set(new_sections) == set(mapping), 'Section identity coverage changed')
    check(old_sections['0.4']['text'] == new_sections['0.4']['text'], 'Original twelve design commitments changed')
    counts['original_design_commitments_unchanged'] = True
    counts['section_blocks_mapped'] = len(mapping)

    docs = read_json('document-manifest.json')
    found_sections: collections.Counter[str] = collections.Counter()
    blocks: dict[str, tuple[str, str]] = {}
    block_pattern = re.compile(r'<!-- source-section: ([\w.\-]+) -->\n(.*?)<!-- end-source-section: \1 -->', re.S)
    for doc in docs:
        p = ROOT/doc['file']
        text = p.read_text(encoding='utf-8')
        check(len(text.encode('utf-8')) == doc['bytes'], f'Document size: {doc["file"]}')
        check(len(re.findall(r'\S+', text)) == doc['words'], f'Document words: {doc["file"]}')
        check(len(text.splitlines()) == doc['lines'], f'Document lines: {doc["file"]}')
        if 'sha256' in doc:
            check(sha(text) == doc['sha256'], f'Document hash: {doc["file"]}')
        actual_ids = []
        for sid, fragment in block_pattern.findall(text):
            actual_ids.append(sid)
            found_sections[sid] += 1
            blocks[sid] = (doc['file'], fragment)
        check(actual_ids == doc['sections'], f'Document section order: {doc["file"]}')
    for sid, record in mapping.items():
        check(found_sections[sid] == 1, f'Section {sid} is not present exactly once')
        check(sha(old_sections[sid]['text']) == record['original_sha256'], f'Original section hash: {sid}')
        check(sha(new_sections[sid]['text']) == record['corrected_sha256'], f'Corrected section hash: {sid}')
        check(old_sections[sid]['start_line'] == record['original_start_line'] and old_sections[sid]['end_line'] == record['original_end_line'], f'Original line map: {sid}')
        expected = render_fragment(new_sections[sid], record['file'], mapping)
        check(sha(expected) == record['rendered_sha256'], f'Rendered section hash: {sid}')
        if sid in blocks:
            file, fragment = blocks[sid]
            check(file == record['file'] and fragment == expected, f'Migrated content mismatch: {sid}')
    counts['subsystem_documents_verified'] = len(docs)
    counts['max_subsystem_bytes'] = max(d['bytes'] for d in docs)
    counts['entrypoint_words'] = len((ROOT/'SOTA-BEST-MIXED-AGENT.md').read_text(encoding='utf-8').split())

    stats = read_json('change-stats.json')
    old_words = re.findall(r'\S+', original)
    new_words = re.findall(r'\S+', revised)
    unchanged = sum(b.size for b in difflib.SequenceMatcher(a=old_words, b=new_words, autojunk=False).get_matching_blocks())
    check(stats['original_bytes'] == len(original.encode()) and stats['revised_logical_bytes'] == len(revised.encode()), 'Logical byte statistics mismatch')
    check(stats['original_words'] == len(old_words) and stats['revised_logical_words'] == len(new_words), 'Logical word statistics mismatch')
    check(stats['unchanged_original_words'] == unchanged, 'Word-retention statistic mismatch')
    counts['original_word_retention_pct'] = round(100 * unchanged / len(old_words), 2)

    md_files = [p for p in ROOT.rglob('*.md') if 'sources' not in p.relative_to(ROOT).parts]
    cache: dict[Path, set[str]] = {}
    checked_links = 0
    for p in md_files:
        text = p.read_text(encoding='utf-8')
        plain, balanced = unfenced(text)
        check(balanced, f'Unclosed code fence: {p.relative_to(ROOT)}')
        explicit = re.findall(r'<a\s+id=["\']([^"\']+)["\']\s*>', plain)
        check(len(explicit) == len(set(explicit)), f'Duplicate explicit anchor: {p.relative_to(ROOT)}')
        for target in re.findall(r'(?<!!)\[[^\]\n]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)', plain):
            u = urlsplit(target)
            if u.scheme or u.netloc:
                continue
            dest = (p.parent/unquote(u.path)).resolve() if u.path else p
            checked_links += 1
            check(dest.is_relative_to(ROOT), f'Link escapes package: {p.relative_to(ROOT)} -> {target}')
            check(dest.exists(), f'Broken local link: {p.relative_to(ROOT)} -> {target}')
            if u.fragment and dest.is_file() and dest.suffix == '.md':
                if dest not in cache:
                    cache[dest] = anchors_of(dest.read_text(encoding='utf-8'))
                check(unquote(u.fragment) in cache[dest], f'Broken anchor: {p.relative_to(ROOT)} -> {target}')
    counts['active_markdown_files_checked'] = len(md_files)
    counts['local_links_checked'] = checked_links

    report = {
        'version': '1.0.1-proposal',
        'review_date': '2026-09-20',
        'scope': 'Static documentation integrity and traceability only',
        'status': 'passed' if not errors else 'failed',
        'counts': counts,
        'errors': errors,
        'runtime_tests_executed': False,
        'provider_integration_tests_executed': False,
        'benchmarks_executed': False,
        'excluded_from_link_validation': 'Immutable originals under sources/: inherited links intentionally preserved',
    }
    (AUDIT/'validation-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError) as exc:
        print(f'Documentation validation failed: {exc}', file=sys.stderr)
        raise SystemExit(1)
