#!/bin/sh
# Publica uma nova versão no GitHub. A TV baixa sozinha (verifica a cada 6 h,
# ou na hora pelo botão "Procurar atualização" nos Ajustes do celular).
#
# Antes de rodar, aumente em versao.properties:
#   apkVersion  se mudou código Kotlin (a TV reinstala o app)
#   webVersion  se mudou só as telas (a TV troca as páginas sem reinstalar)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

APK_VERSION=$(grep '^apkVersion=' versao.properties | cut -d= -f2)
WEB_VERSION=$(grep '^webVersion=' versao.properties | cut -d= -f2)
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
TAG="v$APK_VERSION.$WEB_VERSION"
BASE="https://github.com/$REPO/releases/latest/download"
OUT=build/release
rm -rf "$OUT" && mkdir -p "$OUT"

./gradlew -q assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/controle-tv.apk"
(cd app/src/main/assets/web && zip -qr "../../../../../$OUT/web.zip" . -x ".DS_Store")
cat > "$OUT/update.json" <<JSON
{
  "apkVersion": $APK_VERSION,
  "webVersion": $WEB_VERSION,
  "apk": "$BASE/controle-tv.apk",
  "web": "$BASE/web.zip"
}
JSON

gh release create "$TAG" "$OUT/controle-tv.apk" "$OUT/web.zip" "$OUT/update.json" \
  --title "Controle TV $TAG" --notes "${1:-Nova versão}"
echo "Publicado $TAG"
