"""Deterministic document migration helpers; no runtime-agent implementation."""
from __future__ import annotations
import re,posixpath,hashlib

def sha(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()

def sections(text: str) -> list[dict]:
    lines = text.splitlines(keepends=True)
    starts = [(0,'meta','Source metadata and title')]
    fence = None
    for i,line in enumerate(lines):
        fm = re.match(r'^\s*(`{3,}|~{3,})',line)
        if fm:
            char = fm.group(1)[0]
            if fence is None: fence=char
            elif fence == char: fence=None
            continue
        if fence: continue
        m = re.match(r'^#{2,3} (?:(\d+(?:\.\d+)*)\.?\s|Appendix ([A-Z])\.)',line)
        if m:
            sid=m.group(1) if m.group(1) else 'appendix-'+m.group(2).lower()
            starts.append((i,sid,line.lstrip('#').strip()))
    result=[]
    for j,(start,sid,title) in enumerate(starts):
        end=starts[j+1][0] if j+1<len(starts) else len(lines)
        result.append({'id':sid,'title':title,'start_line':start+1,'end_line':end,'text':''.join(lines[start:end])})
    if ''.join(x['text'] for x in result)!=text: raise ValueError('Section partition did not preserve bytes')
    if len({x['id'] for x in result}) != len(result): raise ValueError('Duplicate section id')
    return result

def anchor(sid: str) -> str:
    return 'sec-'+sid.replace('.','-')

def rel(current: str, target: str) -> str:
    return posixpath.relpath(target,posixpath.dirname(current) or '.')

def render_fragment(fragment: dict, current: str, mapping: dict) -> str:
    """Add explicit anchors and link unqualified section references outside code.
    Source labels in backticks are preserved verbatim; source register resolves them.
    """
    if fragment['id']=='meta':
        return '<a id="sec-meta"></a>\n\n```text\n'+fragment['text'].rstrip()+'\n```\n'
    parts=[]; fence=None
    for line in fragment['text'].splitlines(keepends=True):
        fm=re.match(r'^\s*(`{3,}|~{3,})',line)
        if fm:
            char=fm.group(1)[0]
            if fence is None: fence=char
            elif fence==char: fence=None
            parts.append(line); continue
        if fence:
            parts.append(line); continue
        chunks=re.split(r'(`+[^`]*`+)',line)
        for i in range(0,len(chunks),2):
            def sub(m):
                sid=m.group(1)
                if sid not in mapping: return m.group(0)
                target=mapping[sid]['file']
                url=('#'+anchor(sid)) if target==current else rel(current,target)+'#'+anchor(sid)
                return '['+m.group(0)+']('+url+')'
            chunks[i]=re.sub(r'§(\d+(?:\.\d+)*)',sub,chunks[i])
        parts.append(''.join(chunks))
    return '<a id="'+anchor(fragment['id'])+'"></a>\n\n'+''.join(parts).rstrip()+'\n'
