#!/usr/bin/env bash
set -e

SOURCE_DIR="${1:-/tmp/en_bn_work/int8}"
OUTPUT_ZIP="${2:-dist/translation_en_bn_v1.0.zip}"

echo "Packaging translation model from $SOURCE_DIR into $OUTPUT_ZIP..."
python3 scripts/package_translation_model.py "$SOURCE_DIR" "$OUTPUT_ZIP"
