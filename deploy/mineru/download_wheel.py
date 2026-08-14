#!/usr/bin/env python3
"""Download a large immutable wheel in verified, resumable HTTP ranges."""

from __future__ import annotations

import argparse
import hashlib
import os
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

USER_AGENT = "pip/25.0 videomind-wheel-fetcher/1.0"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def content_length(url: str) -> int:
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        length = int(response.headers.get("Content-Length", "0"))
        if length <= 0 or "bytes" not in response.headers.get("Accept-Ranges", "").lower():
            raise RuntimeError("wheel server does not support byte ranges")
        return length


def download_part(url: str, target: Path, start: int, end: int) -> None:
    expected = end - start + 1
    if target.exists() and target.stat().st_size == expected:
        return
    temporary = target.with_suffix(target.suffix + ".tmp")
    for attempt in range(1, 6):
        try:
            request = urllib.request.Request(
                url,
                headers={"Range": f"bytes={start}-{end}", "User-Agent": USER_AGENT},
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status != 206:
                    raise RuntimeError(f"range request returned HTTP {response.status}")
                with temporary.open("wb") as stream:
                    while block := response.read(1024 * 1024):
                        stream.write(block)
            if temporary.stat().st_size != expected:
                raise RuntimeError("range length mismatch")
            os.replace(temporary, target)
            return
        except Exception:
            temporary.unlink(missing_ok=True)
            if attempt == 5:
                raise
            time.sleep(attempt * 2)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists() and sha256(args.output) == args.sha256:
        print(f"Using verified cached wheel: {args.output.name}", flush=True)
        return
    args.output.unlink(missing_ok=True)

    length = content_length(args.url)
    workers = max(1, min(args.workers, 16, length))
    chunk = (length + workers - 1) // workers
    ranges: list[tuple[int, int, Path]] = []
    for index in range(workers):
        start = index * chunk
        if start >= length:
            break
        end = min(length - 1, start + chunk - 1)
        ranges.append((start, end, args.output.with_suffix(args.output.suffix + f".part{index:02d}")))

    print(f"Downloading {args.output.name} ({length} bytes) in {len(ranges)} ranges", flush=True)
    with ThreadPoolExecutor(max_workers=len(ranges)) as executor:
        futures = {
            executor.submit(download_part, args.url, target, start, end): target
            for start, end, target in ranges
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            future.result()
            print(f"Completed range {completed}/{len(ranges)}", flush=True)

    with args.output.open("wb") as output:
        for _, _, part in ranges:
            with part.open("rb") as stream:
                while block := stream.read(1024 * 1024):
                    output.write(block)
    if args.output.stat().st_size != length or sha256(args.output) != args.sha256:
        args.output.unlink(missing_ok=True)
        raise RuntimeError("wheel SHA-256 verification failed")
    for _, _, part in ranges:
        part.unlink(missing_ok=True)
    print(f"Verified SHA-256 for {args.output.name}", flush=True)


if __name__ == "__main__":
    main()
