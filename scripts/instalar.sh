#!/bin/sh
# Compila o app e instala no Fire TV pela rede.
# Uso: ./scripts/instalar.sh 192.168.0.50   (IP do Fire TV)
set -e
IP="$1"
if [ -z "$IP" ]; then
  echo "Uso: $0 <ip-do-fire-tv>"
  exit 1
fi

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd "$(dirname "$0")/.."

./gradlew -q assembleDebug
adb connect "$IP:5555"
adb -s "$IP:5555" wait-for-device
adb -s "$IP:5555" install -r app/build/outputs/apk/debug/app-debug.apk
# Permite abrir apps em segundo plano no Fire OS 8 (Android 11)
adb -s "$IP:5555" shell appops set com.controletv SYSTEM_ALERT_WINDOW allow || true
adb -s "$IP:5555" shell am start -n com.controletv/.MainActivity
echo "Pronto. Olhe a TV."
