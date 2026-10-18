#!/data/data/com.termux/files/usr/bin/bash
# Termux'un kuruldugu kaynaga (F-Droid / GitHub) uygun Termux:API APK'sini indirir
# ve Android kurulum ekranini acar. Imzalar ayni kaynaktan olmazsa APK yuklenmez.
set -e
pkg install -y curl jq termux-tools >/dev/null

KAYNAK="${TERMUX_APP__APK_RELEASE:-${TERMUX_APK_RELEASE:-$(termux-info 2>/dev/null | grep -i -o "APK_RELEASE[=:]* *[A-Z_]*" | head -1)}}"
echo "Termux kaynagi: ${KAYNAK:-bilinmiyor}"

case "$KAYNAK" in
  *GITHUB*)
    URL=$(curl -fsSL https://api.github.com/repos/termux/termux-api/releases/latest \
          | jq -r '.assets[].browser_download_url' | grep '\.apk$' | head -1) ;;
  *PLAY*)
    echo "Termux Google Play'den kurulmus. Termux:API'yi de Play Store'dan kurun:"
    echo "https://play.google.com/store/apps/details?id=com.termux.api"
    termux-open-url "https://play.google.com/store/apps/details?id=com.termux.api" || true
    exit 0 ;;
  *)
    KOD=$(curl -fsSL https://f-droid.org/api/v1/packages/com.termux.api | jq -r '.suggestedVersionCode')
    URL="https://f-droid.org/repo/com.termux.api_${KOD}.apk" ;;
esac

echo "Indiriliyor: $URL"
APK="$HOME/termux-api.apk"
curl -fL --progress-bar -o "$APK" "$URL"

# Dosyalar uygulamasindan da acilabilsin diye Indirilenler'e kopyala
if [ -d "$HOME/storage/downloads" ] || termux-setup-storage; then
  sleep 2
  cp "$APK" "$HOME/storage/downloads/" 2>/dev/null && echo "Kopyalandi: Indirilenler/termux-api.apk"
fi

echo "Kurulum ekrani aciliyor..."
termux-open "$APK"
