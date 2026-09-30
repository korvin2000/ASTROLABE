"""Replay the request bodies the AI Gate SDK sent (from the probe's wire.log) straight to OpenRouter and keep
every raw SSE frame, so a corrupted tool call can be attributed to the provider or to the SDK's stream assembly.

Usage (key in OPENROUTER_API_KEY):
    python scripts/probe/or_raw_capture.py <probe-out-dir> [--n 4] [--no-stream-too]

Input:  <probe-out-dir>/wire.log  (java.util.logging output of `net.ai.gate.wire` at WireLog.BODIES)
Output: <probe-out-dir>/raw/<scenario>-<i>.sse.txt   raw frames, one per line, exactly as received
        <probe-out-dir>/raw/summary.jsonl             per call: tool calls as assembled from the frames, plus flags
"""
import json
import os
import re
import sys
import time
import urllib.request

URL = "https://openrouter.ai/api/v1/chat/completions"


def bodies(wire_log):
    """Distinct request bodies in the wire log, keyed by the scenario marker in their last user message."""
    text = open(wire_log, encoding="utf-8", errors="replace").read()
    out = {}
    for m in re.finditer(r"POST \S+/chat/completions .*?(\{.*)", text):
        raw = m.group(1)
        try:
            body, _ = json.JSONDecoder().raw_decode(raw)
        except json.JSONDecodeError:
            continue
        last = ""
        for msg in reversed(body.get("messages", [])):
            if msg.get("role") == "user":
                last = msg.get("content") if isinstance(msg.get("content"), str) else json.dumps(msg.get("content"))
                break
        if "Record the blocker now" in last:
            key = "blocked-explicit"
        elif "turn 1/40" in last:
            key = "first-turn"
        elif "turn 4/40" in last:
            key = "masked-run"
        else:
            key = "other"
        out.setdefault(key, body)
    return out


def call(body, key, stream):
    body = dict(body)
    body["stream"] = stream
    if stream:
        body.setdefault("stream_options", {"include_usage": True})
    req = urllib.request.Request(
        URL,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "Accept": "text/event-stream" if stream else "application/json",
            "HTTP-Referer": "https://astrolabe.local/probe",
            "X-Title": "astrolabe-probe",
        },
        method="POST",
    )
    started = time.time()
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = resp.read().decode("utf-8", errors="replace")
    return data, time.time() - started


def assemble(frames):
    """Assemble tool calls the way a client would, recording index presence and every fragment."""
    calls = {}
    order = []
    finish = None
    provider = None
    for line in frames:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            break
        try:
            chunk = json.loads(payload)
        except json.JSONDecodeError:
            continue
        provider = chunk.get("provider", provider)
        for choice in chunk.get("choices", []):
            finish = choice.get("finish_reason") or finish
            delta = choice.get("delta") or {}
            for tc in delta.get("tool_calls", []) or []:
                idx = tc.get("index")
                k = "tool" + str(idx if idx is not None else 0)
                c = calls.setdefault(k, {"id": None, "name": None, "args": "", "fragments": 0, "index_missing": 0})
                if k not in order:
                    order.append(k)
                if tc.get("id"):
                    c["id"] = tc["id"]
                fn = tc.get("function") or {}
                if fn.get("name"):
                    c["name"] = (c["name"] or "") + fn["name"] if c["name"] and c["fragments"] else fn["name"]
                if fn.get("arguments"):
                    c["args"] += fn["arguments"]
                    c["fragments"] += 1
                if idx is None:
                    c["index_missing"] += 1
    return [calls[k] for k in order], finish, provider


def flags_of(calls):
    flags = []
    for c in calls:
        name = c.get("name") or "?"
        args = c.get("args", "")
        try:
            parsed = json.loads(args)
        except json.JSONDecodeError:
            flags.append(f"{name}: args not valid JSON")
            parsed = None
        if "<arg_key>" in args or "<arg_value>" in args:
            flags.append(f"{name}: native GLM tool tokens leaked")
        if isinstance(parsed, dict):
            for k in parsed:
                if any(ch in k for ch in "<>\n\r"):
                    flags.append(f"{name}: garbled key {k!r}")
            if name in ("state", "task", "kb") and "op" not in parsed:
                flags.append(f"{name}: missing op")
            if isinstance(parsed.get("patch"), str):
                flags.append("state: patch sent as a string")
        if c.get("index_missing"):
            flags.append(f"{name}: {c['index_missing']} delta(s) without index")
    return flags


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    out_dir = sys.argv[1]
    n = int(sys.argv[sys.argv.index("--n") + 1]) if "--n" in sys.argv else 4
    also_plain = "--no-stream-too" not in sys.argv
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        sys.exit("OPENROUTER_API_KEY is not set")
    found = bodies(os.path.join(out_dir, "wire.log"))
    if not found:
        sys.exit("no request bodies in wire.log (was the probe run with WireLog.BODIES?)")
    raw_dir = os.path.join(out_dir, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    print(f"scenarios: {sorted(found)}; model: {next(iter(found.values())).get('model')}")
    with open(os.path.join(raw_dir, "summary.jsonl"), "w", encoding="utf-8") as summary:
        for scenario, body in found.items():
            for i in range(1, n + 1):
                for stream in ([True, False] if also_plain else [True]):
                    tag = f"{scenario}-{'stream' if stream else 'plain'}-{i}"
                    try:
                        data, secs = call(body, key, stream)
                    except Exception as e:  # noqa: BLE001 - keep going, record the failure
                        rec = {"tag": tag, "error": str(e)}
                        summary.write(json.dumps(rec, ensure_ascii=False) + "\n")
                        print(tag, "ERROR", e)
                        continue
                    path = os.path.join(raw_dir, tag + (".sse.txt" if stream else ".json"))
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(data)
                    if stream:
                        frames = data.splitlines()
                        calls, finish, provider = assemble(frames)
                    else:
                        j = json.loads(data)
                        provider = j.get("provider")
                        ch = (j.get("choices") or [{}])[0]
                        finish = ch.get("finish_reason")
                        calls = [{"id": tc.get("id"), "name": (tc.get("function") or {}).get("name"),
                                  "args": (tc.get("function") or {}).get("arguments", ""), "fragments": 1, "index_missing": 0}
                                 for tc in (ch.get("message") or {}).get("tool_calls", []) or []]
                    rec = {"tag": tag, "provider": provider, "finish": finish, "secs": round(secs, 1),
                           "calls": [{k: v for k, v in c.items() if k != "fragments"} | {"fragments": c["fragments"]} for c in calls],
                           "flags": flags_of(calls)}
                    summary.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    summary.flush()
                    print(tag, provider, finish, [c["name"] for c in calls], rec["flags"])


if __name__ == "__main__":
    main()
