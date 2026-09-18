#!/usr/bin/env python3
"""
Packaging script for Offline English -> Bangla Translation Model.
Creates a verified distribution zip archive containing all required files:
- encoder_model.onnx
- decoder_model.onnx
- decoder_with_past_model.onnx
- source.spm
- target.spm
- vocab.json
- source_pieces.json
- model_manifest.json
- config.json, generation_config.json, tokenizer_config.json, special_tokens_map.json

Verifies file presence and SHA-256 checksums.
"""

import sys
import os
import json
import hashlib
import zipfile
from pathlib import Path

REQUIRED_FILES = [
    "encoder_model.onnx",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx",
    "source.spm",
    "target.spm",
    "vocab.json",
    "source_pieces.json",
    "model_manifest.json",
]

OPTIONAL_CONFIG_FILES = [
    "config.json",
    "generation_config.json",
    "tokenizer_config.json",
    "special_tokens_map.json"
]

def calculate_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def package_model(source_dir: Path, output_zip: Path):
    print(f"=== Packaging Translation Model from {source_dir} ===")
    if not source_dir.exists():
        print(f"ERROR: Source directory does not exist: {source_dir}", file=sys.stderr)
        sys.exit(1)

    # 1. Verify required files
    missing = []
    for fname in REQUIRED_FILES:
        fpath = source_dir / fname
        if not fpath.exists() or fpath.stat().st_size == 0:
            missing.append(fname)

    if missing:
        print(f"ERROR: Missing required files in {source_dir}: {missing}", file=sys.stderr)
        sys.exit(1)

    # 2. Check manifest if present
    manifest_path = source_dir / "model_manifest.json"
    manifest_data = None
    if manifest_path.exists():
        with open(manifest_path, "r", encoding="utf-8") as mf:
            manifest_data = json.load(mf)
        print(f"Loaded manifest: id={manifest_data.get('id')}, model={manifest_data.get('modelName')}")

    # 3. Create zip archive
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    all_files_to_pack = REQUIRED_FILES + [f for f in OPTIONAL_CONFIG_FILES if (source_dir / f).exists()]

    total_uncompressed = 0
    print(f"Packing {len(all_files_to_pack)} files into {output_zip}...")
    with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for fname in sorted(all_files_to_pack):
            fpath = source_dir / fname
            size = fpath.stat().st_size
            sha = calculate_sha256(fpath)
            total_uncompressed += size
            print(f"  + {fname:<30} ({size:>10,} bytes) [SHA: {sha[:12]}...]")

            # Verify against manifest if present
            if manifest_data and "sha256" in manifest_data and fname in manifest_data["sha256"]:
                expected_sha = manifest_data["sha256"][fname]
                if expected_sha and sha != expected_sha:
                    print(f"ERROR: Checksum mismatch for {fname}! Expected {expected_sha}, got {sha}", file=sys.stderr)
                    sys.exit(1)

            zf.write(fpath, arcname=fname)

    archive_size = output_zip.stat().st_size
    archive_sha = calculate_sha256(output_zip)

    print("\n=== Package Complete ===")
    print(f"Archive:            {output_zip.resolve()}")
    print(f"Compressed Size:    {archive_size:,} bytes ({round(archive_size / (1024 * 1024), 2)} MB)")
    print(f"Uncompressed Size:  {total_uncompressed:,} bytes ({round(total_uncompressed / (1024 * 1024), 2)} MB)")
    print(f"Archive SHA-256:    {archive_sha}")
    print("========================\n")
    return archive_size, archive_sha

if __name__ == "__main__":
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/en_bn_work/int8")
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("dist/translation_en_bn_v1.0.zip")
    package_model(src, out)
