#!/bin/sh
# Baixa as bibliotecas grandes que não ficam no git (reconhecimento de voz da legenda automática).
set -e
cd "$(dirname "$0")/.."
mkdir -p app/libs
AAR=app/libs/sherpa-onnx-1.13.8.aar
if [ ! -f "$AAR" ]; then
  echo "Baixando sherpa-onnx…"
  curl -sL -o "$AAR" https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
fi
