#!/usr/bin/env python3
"""Check that classes looked up by ONNX JNI survive Android code shrinking."""

import argparse
import re
import struct
import zipfile


def defined_classes(dex):
    if not dex.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")

    def uint(offset):
        return struct.unpack_from("<I", dex, offset)[0]

    strings, types = uint(0x3C), uint(0x44)
    count, definitions = uint(0x60), uint(0x64)
    result = set()
    for i in range(count):
        type_index = uint(definitions + i * 32)
        string_index = uint(types + type_index * 4)
        offset = uint(strings + string_index * 4)
        while dex[offset] & 0x80:  # Skip the ULEB128 UTF-16 length.
            offset += 1
        offset += 1
        result.add(dex[offset:dex.index(b"\0", offset)].decode("utf-8"))
    return result


def verify(path):
    classes, required = set(), set()
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                classes.update(defined_classes(apk.read(name)))
            elif name.endswith("libonnxruntime4j_jni.so"):
                required.update(
                    "L" + name.decode("ascii") + ";"
                    for name in re.findall(
                        rb"\b(ai/onnxruntime/[A-Za-z0-9_$]+)\x00", apk.read(name)
                    )
                )
    if not required:
        raise ValueError("No ONNX JNI class references found in the APK")
    missing = sorted(required - classes)
    if missing:
        raise ValueError("Native bindings refer to missing/renamed classes: " + ", ".join(missing))
    print(f"{path}: all {len(required)} ONNX JNI class references have DEX definitions")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+")
    for path in parser.parse_args().apk:
        verify(path)
