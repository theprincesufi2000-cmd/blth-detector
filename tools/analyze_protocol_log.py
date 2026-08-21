#!/usr/bin/env python3
"""Summarize Bluetooth Inspector Pro JSON evidence logs.

Usage:
    python3 tools/analyze_protocol_log.py bluetooth_protocol_log.json
"""
import json
import sys
from collections import Counter, defaultdict

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 tools/analyze_protocol_log.py <log.json>")

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

events = data.get("events", [])
print("Device:", data.get("device", "unknown"))
print("Address:", data.get("address", ""))
print("Events:", len(events))
print()

writes = [e for e in events if e.get("direction") == "WRITE"]
print("WRITE commands:")
for i, e in enumerate(writes, 1):
    print(f"  {i:03d}  {e.get('uuid','')}  {e.get('hex','')}  {e.get('status','')}")

print("\nDirection counts:")
for k, v in Counter(e.get("direction", "") for e in events).most_common():
    print(f"  {k:16s} {v}")

rx = defaultdict(list)
for e in events:
    if e.get("direction") in ("NOTIFY", "READ"):
        rx[e.get("uuid", "")].append(e.get("hex", ""))

print("\nRX UUIDs:")
for uuid, values in rx.items():
    counts = Counter(values)
    print(f"  {uuid}")
    for value, count in counts.most_common(10):
        print(f"    {value or '<empty>'}  x{count}")
