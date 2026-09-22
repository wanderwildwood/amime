#!/usr/bin/env python3
"""Take the companion protocol's numbers out of MeshCore's source.

This app implements MeshCore's companion protocol rather than vendoring any of it, because
there is nothing to vendor: MeshCore is C++ firmware for an ESP32 and the official phone app
is Flutter. What can be taken from upstream instead of copied by hand is the part that is
pure fact — the opcodes — and that is what this does.

It writes a manifest of every `#define` the protocol uses, which `UpstreamConstantsTest`
checks `Codes.kt` against. The constants stay hand-written, because the comments around them
carry what the numbers do not: which ones the documentation gets wrong, which are sentinels
rather than counts, and why. What stops being hand-maintained is whether they are *right*.

    tools/update-meshcore-constants.py [path-to-MeshCore-checkout]

Run it after pulling upstream. A diff in the manifest is a protocol change to read rather
than a file to wave through.
"""

import re
import subprocess
import sys
from pathlib import Path

# Where the numbers live upstream. The companion example holds the command, response and
# push codes; the rest sit in the headers the firmware shares with every other build.
SOURCES = [
    "examples/companion_radio/MyMesh.cpp",
    "src/helpers/TxtDataHelpers.h",
    "src/helpers/AdvertDataHelpers.h",
    "src/helpers/ContactInfo.h",
    "src/helpers/BaseChatMesh.h",
    "src/MeshCore.h",
]

# Only the families this app speaks. A `#define` for a pin number is not protocol.
WANTED = re.compile(
    r"^\s*#define\s+("
    r"CMD_\w+|RESP_CODE_\w+|RESP_ALLOWED_\w+|PUSH_CODE_\w+|ERR_CODE_\w+|"
    r"TXT_TYPE_\w+|ADV_TYPE_\w+|OUT_PATH_UNKNOWN|MAX_PATH_SIZE|MAX_TEXT_LEN|"
    r"PUB_KEY_SIZE|MAX_PACKET_PAYLOAD|CIPHER_BLOCK_SIZE"
    r")\s+(\(?[^/\n]+?)\s*(?://.*)?$"
)

# Some are arithmetic over other constants rather than literals — MAX_TEXT_LEN is
# (10*CIPHER_BLOCK_SIZE), and it is the one that decides how long a message may be, so
# leaving it unresolved would leave the most consequential number unchecked. Names are
# substituted from what is already known and the pass repeats while it is still making
# progress. Anything still unresolved is named in the manifest rather than dropped, so a
# value that quietly stops being readable is visible.
ARITHMETIC = re.compile(r"^[\d\s()*+\-]+$")


def resolve(raw: str, known: dict[str, int]) -> int | None:
    text = raw.strip()
    if re.fullmatch(r"0[xX][0-9a-fA-F]+", text):
        return int(text, 16)
    if re.fullmatch(r"\d+", text):
        return int(text)
    for name, value in known.items():
        text = re.sub(rf"\b{re.escape(name)}\b", str(value), text)
    if ARITHMETIC.fullmatch(text):
        return int(eval(text))  # noqa: S307 — digits and operators only, checked above
    return None


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/projects/amime-reference")
    if not (root / "src" / "MeshCore.h").is_file():
        print(f"not a MeshCore checkout: {root}", file=sys.stderr)
        return 1

    revision = subprocess.run(
        ["git", "-C", str(root), "log", "-1", "--format=%H %ci"],
        capture_output=True, text=True, check=False,
    ).stdout.strip() or "unknown"

    found: dict[str, int] = {}
    raw_values: dict[str, str] = {}
    for relative in SOURCES:
        path = root / relative
        if not path.is_file():
            print(f"missing upstream file: {relative}", file=sys.stderr)
            return 1
        for line in path.read_text(errors="replace").splitlines():
            match = WANTED.match(line)
            if not match:
                continue
            name, raw = match.group(1), match.group(2)
            # First definition wins: the headers are included in an order where a later
            # redefinition would be a compile warning upstream, not a different value.
            raw_values.setdefault(name, raw)

    # Repeat while anything new resolves, so a constant defined in terms of another does not
    # depend on which file was read first.
    while True:
        progress = False
        for name, raw in raw_values.items():
            if name in found:
                continue
            value = resolve(raw, found)
            if value is not None:
                found[name] = value
                progress = True
        if not progress:
            break

    unresolved = [
        f"{name} = {raw.strip()}" for name, raw in sorted(raw_values.items())
        if name not in found
    ]

    out = Path(__file__).resolve().parent.parent / "app/src/test/resources/meshcore-constants.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        f.write("# The companion protocol's numbers, read out of MeshCore rather than typed.\n")
        f.write("# Written by tools/update-meshcore-constants.py — do not edit by hand.\n")
        f.write("# Upstream: https://github.com/meshcore-dev/MeshCore\n")
        f.write(f"# Commit:   {revision}\n")
        f.write(f"# Files:    {', '.join(SOURCES)}\n")
        for name in unresolved:
            f.write(f"# Not a plain number, so not checked: {name}\n")
        f.write("\n")
        for name in sorted(found):
            f.write(f"{name}={found[name]}\n")

    print(f"{len(found)} constants -> {out}")
    if unresolved:
        print(f"{len(unresolved)} not resolvable and recorded as comments")
    return 0


if __name__ == "__main__":
    sys.exit(main())
