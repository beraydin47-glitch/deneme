#!/data/data/com.termux/files/usr/bin/bash
# Termux kurulum betigi
set -e
pkg update -y
pkg install -y python python-numpy python-scipy portaudio pulseaudio ffmpeg termux-api
pip install sounddevice
termux-setup-storage || true

# PulseAudio'yu baslat (canli mod icin hoparlor + mikrofon)
pulseaudio --kill 2>/dev/null || true
pulseaudio --start --exit-idle-time=-1
pactl load-module module-sles-source 2>/dev/null || echo "Uyari: mikrofon kaynagi yuklenemedi (README'ye bakin)."

echo
echo "Kurulum tamam. Ornekler:"
echo "  python ses_degistirici.py presetler"
echo "  python ses_degistirici.py kayit --sure 10 --preset canavar"
echo "  python ses_degistirici.py canli --preset robot"
