#!/bin/bash
set -e

ASSETS_DIR="app/src/main/assets"
mkdir -p "$ASSETS_DIR"

echo "Downloading BGE-Small ONNX quantized embedding model..."
curl -L -o "$ASSETS_DIR/bge_small_quant.onnx" \
  "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"

echo "Downloading WordPiece vocab..."
curl -L -o "$ASSETS_DIR/vocab.txt" \
  "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/vocab.txt"

echo "Assets populated successfully."
