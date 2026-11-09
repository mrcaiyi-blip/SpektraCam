"""Convert a compiled shader to an aligned C++ array."""
import struct
import sys
from pathlib import Path

source, target = map(Path, sys.argv[1:3])
words = [word for (word,) in struct.iter_unpack("<I", source.read_bytes())]
lines = ["#pragma once", "#include <cstdint>", f"inline constexpr uint32_t {target.stem}[] = {{"]
for start in range(0, len(words), 8):
    lines.append("    " + ", ".join(f"0x{word:08x}" for word in words[start:start + 8]) + ",")
lines.append("};")
target.write_text("\n".join(lines) + "\n")
