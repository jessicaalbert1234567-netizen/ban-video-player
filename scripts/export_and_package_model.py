#!/usr/bin/env python3
"""
Self-contained script for GitHub Actions CI / local reproduction:
1. Downloads shhossain/opus-mt-en-to-bn from HuggingFace
2. Exports to ONNX using optimum-cli or transformers/onnx
3. Quantizes encoder and decoder models to INT8 (dynamic QUInt8)
4. Generates source_pieces.json and model_manifest.json
5. Packages into dist/translation_en_bn_v1.0.zip
6. Verifies SHA-256 and outputs publication details
"""

import sys
import os
import subprocess
import shutil
from pathlib import Path

def run(cmd, cwd=None):
    print(f"Running: {' '.join(cmd) if isinstance(cmd, list) else cmd}")
    subprocess.run(cmd, shell=isinstance(cmd, str), check=True, cwd=cwd)

def main():
    work_dir = Path("/tmp/en_bn_export")
    work_dir.mkdir(parents=True, exist_ok=True)
    int8_dir = work_dir / "int8"
    int8_dir.mkdir(parents=True, exist_ok=True)

    # Check if int8 files already exist locally (e.g. from local run)
    if Path("/tmp/en_bn_work/int8/encoder_model.onnx").exists():
        print("Using existing local converted models from /tmp/en_bn_work/int8...")
        source_dir = Path("/tmp/en_bn_work/int8")
    else:
        print("Exporting shhossain/opus-mt-en-to-bn to ONNX...")
        run("optimum-cli export onnx --model shhossain/opus-mt-en-to-bn --task seq2seq-lm-with-past /tmp/en_bn_export/fp32")

        print("Quantizing to INT8...")
        import onnx
        from onnxruntime.quantization import quantize_dynamic, QuantType

        fp32_dir = work_dir / "fp32"
        for onnx_file in ["encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"]:
            inp = fp32_dir / onnx_file
            out = int8_dir / onnx_file
            print(f"Quantizing {onnx_file} -> {out}...")
            quantize_dynamic(
                model_input=str(inp),
                model_output=str(out),
                weight_type=QuantType.QUInt8,
                per_channel=False,
                reduce_range=False
            )

        # Copy auxiliary files
        for fname in ["source.spm", "target.spm", "vocab.json", "config.json", "generation_config.json", "tokenizer_config.json", "special_tokens_map.json"]:
            src_f = fp32_dir / fname
            if src_f.exists():
                shutil.copy2(src_f, int8_dir / fname)

        # Generate source_pieces.json if needed
        sp_file = int8_dir / "source.spm"
        pieces_file = int8_dir / "source_pieces.json"
        if sp_file.exists() and not pieces_file.exists():
            import sentencepiece.sentencepiece_model_pb2 as sp_pb2
            import json
            sp_proto = sp_pb2.ModelProto()
            sp_proto.ParseFromString(sp_file.read_bytes())
            pieces_dict = {p.piece: float(p.score) for p in sp_proto.pieces}
            pieces_file.write_text(json.dumps(pieces_dict, ensure_ascii=False, indent=2), encoding="utf-8")

        # Copy manifest
        manifest_src = Path("app/src/main/assets/models/translation_en_bn/model_manifest.json")
        if manifest_src.exists():
            shutil.copy2(manifest_src, int8_dir / "model_manifest.json")

        source_dir = int8_dir

    # Package model
    from package_translation_model import package_model
    dist_dir = Path("dist")
    dist_dir.mkdir(parents=True, exist_ok=True)
    out_zip = dist_dir / "translation_en_bn_v1.0.zip"
    package_model(source_dir, out_zip)

    # Also copy manifest to dist for direct release asset inspection
    manifest_f = source_dir / "model_manifest.json"
    if manifest_f.exists():
        shutil.copy2(manifest_f, dist_dir / "model_manifest.json")

if __name__ == "__main__":
    # Add script dir to python path
    sys.path.insert(0, str(Path(__file__).parent))
    main()
