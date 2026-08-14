#!/usr/bin/env python3
"""Build a metadata-only opencv-contrib wheel backed by its headless build."""

from __future__ import annotations

import base64
import csv
import hashlib
import io
import sys
import zipfile
from pathlib import Path

VERSION = "4.10.0.84"
DIST_INFO = f"opencv_contrib_python-{VERSION}.dist-info"
WHEEL_NAME = f"opencv_contrib_python-{VERSION}-py3-none-any.whl"


def digest(data: bytes) -> str:
    encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=")
    return "sha256=" + encoded.decode("ascii")


def main(output_directory: str) -> None:
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    files = {
        f"{DIST_INFO}/METADATA": (
            "Metadata-Version: 2.1\n"
            "Name: opencv-contrib-python\n"
            f"Version: {VERSION}\n"
            "Summary: Compatibility metadata for opencv-contrib-python-headless\n"
            f"Requires-Dist: opencv-contrib-python-headless=={VERSION}\n"
            "\n"
        ).encode(),
        f"{DIST_INFO}/WHEEL": (
            "Wheel-Version: 1.0\n"
            "Generator: videomind-opencv-contrib-shim\n"
            "Root-Is-Purelib: true\n"
            "Tag: py3-none-any\n"
            "\n"
        ).encode(),
    }
    record_path = f"{DIST_INFO}/RECORD"
    record = io.StringIO(newline="")
    writer = csv.writer(record, lineterminator="\n")
    for path, data in files.items():
        writer.writerow((path, digest(data), len(data)))
    writer.writerow((record_path, "", ""))
    files[record_path] = record.getvalue().encode()
    with zipfile.ZipFile(output / WHEEL_NAME, "w", zipfile.ZIP_DEFLATED) as wheel:
        for path, data in files.items():
            wheel.writestr(path, data)


if __name__ == "__main__":
    main(sys.argv[1])
