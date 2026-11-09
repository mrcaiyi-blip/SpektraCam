#!/usr/bin/env python3
"""Bake a common illustration or photo through all bundled preview LUTs."""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import zlib

PROJECT = Path(__file__).resolve().parents[2]
ROOT = PROJECT / "assets/spektra"
SRGB_TO_PROPHOTO = (
    (.529388470712, .330231217730, .140627167763),
    (.098305088514, .873483901238, .028146056090),
    (.016851063808, .117696843742, .865673268566),
)
WIDTH, HEIGHT = 320, 200
PATCHES = [
    (0.73, 0.22, 0.13), (0.78, 0.43, 0.12), (0.76, 0.66, 0.27),
    (0.32, 0.55, 0.32), (0.13, 0.44, 0.51), (0.33, 0.30, 0.61),
]


def mix(a, b, amount):
    return tuple(x + (y - x) * amount for x, y in zip(a, b))


def illustration(x, y):
    """Encoded sRGB illustration, with color, white and shadow references."""
    light = max(0, 1 - math.hypot((x - .27) * .7, (y - .13) * .9))
    color = mix((.23, .31, .36), (.66, .76, .77), light)
    if y > .69:
        color = mix((.22, .16, .13), (.65, .47, .31), light)
        shadow = max(0, 1 - ((x - .65) / .27) ** 2 - ((y - .73) / .085) ** 2)
        color = mix(color, (.08, .07, .06), .55 * shadow)

    # Two rounded forms and a set of colored chips on a tabletop.
    for cx, cy, radius, base in [
        (.38, .54, .19, (.82, .27, .13)),
        (.67, .49, .235, (.77, .73, .66)),
    ]:
        dx, dy = (x - cx) * WIDTH / HEIGHT / radius, (y - cy) / radius
        distance = dx * dx + dy * dy
        if distance < 1:
            z = math.sqrt(1 - distance)
            shade = min(1, max(.06, .29 + .64 * (-.48 * dx - .55 * dy + .68 * z)))
            gloss = .38 * max(0, -.4 * dx - .48 * dy + .78 * z) ** 35
            color = tuple(min(1, value * shade + gloss) for value in base)
    if .073 < x < .927 and .82 < y < .925:
        chip = (x - .073) / (.854 / 8)
        if chip % 1 < .90:
            color = (PATCHES + [(.91, .91, .91), (.10, .10, .10)])[int(chip)]
    return color


def decode(v):
    return v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4


def shape(v):
    return max(0, min(1, 12.92 * v if v <= .0031308 else 1.055 * v ** (1 / 2.4) - .055))


def coords(rgb, size):
    linear = [decode(v) for v in rgb]
    values = [shape(sum(c * v for c, v in zip(row, linear))) * (size - 1) for row in SRGB_TO_PROPHOTO]
    r, g, b = [min(size - 2, int(value)) for value in values]
    fr, fg, fb = [value - low for value, low in zip(values, (r, g, b))]
    indices = []
    weights = []
    for dz, wz in [(0, 1 - fb), (1, fb)]:
        for dy, wy in [(0, 1 - fg), (1, fg)]:
            for dx, wx in [(0, 1 - fr), (1, fr)]:
                indices.append(3 * (((b + dz) * size + g + dy) * size + r + dx))
                weights.append(wx * wy * wz)
    return indices, weights


def chunk(name, data):
    return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))


def write_png(path, rgb):
    scanlines = b"".join(b"\0" + rgb[y * WIDTH * 3:(y + 1) * WIDTH * 3] for y in range(HEIGHT))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
    png += chunk(b"sRGB", b"\0")
    png += chunk(b"IDAT", zlib.compress(scanlines, 9))
    path.write_bytes(png + chunk(b"IEND", b""))


def bake_illustrations():
    source = ROOT / "preview_luts"
    output = PROJECT / "build/illustrated-samples"
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((source / "manifest.json").read_text())
    size = manifest["size"]
    coordinates = [coords(illustration((x + .5) / WIDTH, (y + .5) / HEIGHT), size)
                   for y in range(HEIGHT) for x in range(WIDTH)]
    for look in manifest["looks"]:
        key = look["film"]
        lut = array("H", (source / (key + ".plut")).read_bytes()[16:])
        if sys.byteorder != "little":
            lut.byteswap()
        rgb = bytearray()
        for indices, weights in coordinates:
            for channel in range(3):
                rgb.append(round(sum(lut[i + channel] * w for i, w in zip(indices, weights)) / 257))
        write_png(output / (key + ".png"), rgb)


def photo_coordinates(photo, size, np):
    encoded = np.asarray(photo, dtype=np.float64) / 255
    linear = np.where(encoded <= .04045, encoded / 12.92, ((encoded + .055) / 1.055) ** 2.4)
    prophoto = np.maximum(linear @ np.asarray(SRGB_TO_PROPHOTO).T, 0)
    shaped = np.where(prophoto <= .0031308, 12.92 * prophoto, 1.055 * prophoto ** (1 / 2.4) - .055)
    positions = np.clip(shaped, 0, 1) * (size - 1)
    lower = np.minimum(positions.astype(np.int32), size - 2)
    fractions = positions - lower
    r, g, b = np.moveaxis(lower, -1, 0)
    fr, fg, fb = np.moveaxis(fractions, -1, 0)
    return [
        (((b + dz) * size + g + dy) * size + r + dx, (wx * wy * wz)[..., None])
        for dz, wz in ((0, 1 - fb), (1, fb))
        for dy, wy in ((0, 1 - fg), (1, fg))
        for dx, wx in ((0, 1 - fr), (1, fr))
    ]


def write_hashes(directory, filenames):
    hashes = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in filenames}
    (directory / "SHA256SUMS.json").write_text(json.dumps(hashes, sort_keys=True, indent=2) + "\n")


def bake_photo(path):
    import numpy as np
    from PIL import Image, ImageOps

    with Image.open(path) as original:
        photo = ImageOps.exif_transpose(original).convert("RGB")
    photo.thumbnail((640, 640), Image.Resampling.LANCZOS)
    coordinates = {}
    for source, output, variants in [
        (ROOT / "preview_luts", ROOT / "samples", False),
        (ROOT / "print_variants", ROOT / "film_samples", True),
    ]:
        output.mkdir(exist_ok=True)
        samples = []
        manifest = json.loads((source / "manifest.json").read_text())
        lut_files = ["manifest.json"]
        for look in manifest["looks"]:
            key = look["film"] + "--" + look["print"] if variants else look["film"]
            size = look.get("size", manifest.get("size"))
            if size not in coordinates:
                coordinates[size] = photo_coordinates(photo, size, np)
            lut_path = source / (key + ".plut")
            lut = np.frombuffer(lut_path.read_bytes(), dtype="<u2", offset=16).reshape(-1, 3)
            colors = sum(lut[indices] * weights for indices, weights in coordinates[size])
            rgb = np.rint(colors / 257).astype(np.uint8)
            name = key + ".jpg"
            Image.fromarray(rgb).save(output / name, quality=92, subsampling=0, optimize=True)
            samples.append(name)
            lut_files.append(lut_path.name)
        if variants:
            write_hashes(source, lut_files)
        write_hashes(output, samples)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photo", "--ios-photo", type=Path, metavar="SRGB_JPEG",
                        help="Bake default and alternate print sample photos. Requires Pillow and NumPy.")
    args = parser.parse_args()
    if args.photo:
        bake_photo(args.photo)
    else:
        bake_illustrations()


if __name__ == "__main__":
    main()
