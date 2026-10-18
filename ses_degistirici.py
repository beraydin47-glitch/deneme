#!/usr/bin/env python3
"""
Termux Ses Degistirici (MorphVOX benzeri)
Efektler: pitch (ince/kalin ses), 3 bant equalizer, distortion, robot,
yanki (echo), gurultu kapisi (noise gate), ses seviyesi.

Kullanim:
  python ses_degistirici.py canli  [--preset robot]          # mikrofon -> hoparlor (gercek zamanli)
  python ses_degistirici.py kayit  [--sure 10] [--preset ..] # kaydet, isle, cal (root gerekmez)
  python ses_degistirici.py dosya  giris.wav cikis.wav [--preset ..]
  python ses_degistirici.py presetler
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import wave

import numpy as np

try:
    from scipy.signal import lfilter
except ImportError:
    lfilter = None

ORNEK_HIZI = 44100
BLOK = 1024

PRESETLER = {
    "normal":    {},
    "kadin":     {"pitch": 1.45, "eq": (-3, 0, 3)},
    "erkek":     {"pitch": 0.72, "eq": (4, 0, -2)},
    "cocuk":     {"pitch": 1.7, "eq": (-4, 2, 3)},
    "sincap":    {"pitch": 2.0},
    "canavar":   {"pitch": 0.55, "dist": 6, "eq": (6, 0, -4)},
    "robot":     {"robot": 60, "dist": 2, "echo": (0.04, 0.4)},
    "radyo":     {"eq": (-12, 6, -12), "dist": 4},
    "seytan":    {"pitch": 0.6, "dist": 3, "echo": (0.18, 0.35), "eq": (5, 0, 0)},
    "yanki":     {"echo": (0.3, 0.45)},
    "uzayli":    {"pitch": 1.3, "robot": 30, "echo": (0.12, 0.3)},
}


# ---------------------------------------------------------------- efektler

class PitchShifter:
    """Gecikme hatti tabanli gercek zamanli pitch kaydirici (iki okuma kafasi + crossfade)."""

    def __init__(self, oran=1.0, pencere=2048):
        self.oran = oran
        self.W = pencere
        self.gecmis = np.zeros(pencere + 2, dtype=np.float32)
        self.faz = 0.0

    def isle(self, x):
        if abs(self.oran - 1.0) < 1e-3:
            self.gecmis = np.concatenate([self.gecmis, x])[-len(self.gecmis):]
            return x
        n, W, M = len(x), self.W, len(self.gecmis)
        tampon = np.concatenate([self.gecmis, x])
        adim = 1.0 - self.oran
        fazlar = np.mod(self.faz + adim * np.arange(1, n + 1), W)
        self.faz = fazlar[-1]
        idx = M + np.arange(n)
        cikis = np.zeros(n, dtype=np.float32)
        for d in (fazlar, np.mod(fazlar + W / 2, W)):
            konum = idx - d
            i0 = np.floor(konum).astype(np.int64)
            kesir = konum - i0
            ornek = tampon[i0] * (1 - kesir) + tampon[np.minimum(i0 + 1, len(tampon) - 1)] * kesir
            cikis += ornek * np.sin(np.pi * d / W) ** 2
        self.gecmis = tampon[-M:]
        return cikis


def biquad(tip, f0, kazanc_db, q=0.707, fs=ORNEK_HIZI):
    """RBJ Audio EQ Cookbook katsayilari."""
    A = 10 ** (kazanc_db / 40)
    w0 = 2 * np.pi * f0 / fs
    alfa = np.sin(w0) / (2 * q)
    c = np.cos(w0)
    if tip == "peak":
        b = [1 + alfa * A, -2 * c, 1 - alfa * A]
        a = [1 + alfa / A, -2 * c, 1 - alfa / A]
    elif tip == "low":
        sA = 2 * np.sqrt(A) * alfa
        b = [A * ((A + 1) - (A - 1) * c + sA), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sA)]
        a = [(A + 1) + (A - 1) * c + sA, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sA]
    else:  # high
        sA = 2 * np.sqrt(A) * alfa
        b = [A * ((A + 1) + (A - 1) * c + sA), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sA)]
        a = [(A + 1) - (A - 1) * c + sA, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sA]
    return np.array(b) / a[0], np.array(a) / a[0]


class Equalizer:
    """3 bant: bas (shelf 200Hz), orta (peak 1kHz), tiz (shelf 4kHz). Degerler dB."""

    def __init__(self, bas=0, orta=0, tiz=0):
        self.ayarla(bas, orta, tiz)

    def ayarla(self, bas, orta, tiz):
        self.degerler = (bas, orta, tiz)
        self.filtreler = []
        for tip, f, g in (("low", 200, bas), ("peak", 1000, orta), ("high", 4000, tiz)):
            if g != 0:
                b, a = biquad(tip, f, g, q=0.9 if tip == "peak" else 0.707)
                self.filtreler.append([b, a, np.zeros(2)])

    def isle(self, x):
        if not self.filtreler:
            return x
        if lfilter is None:
            return x  # scipy yoksa EQ devre disi
        for f in self.filtreler:
            x, f[2] = lfilter(f[0], f[1], x, zi=f[2])
        return x.astype(np.float32)


class Yanki:
    """Geri beslemeli echo. Gecikme blok boyutundan uzun olmali."""

    def __init__(self, sure=0.0, geri_besleme=0.0):
        self.ayarla(sure, geri_besleme)

    def ayarla(self, sure, geri_besleme):
        self.D = max(int(sure * ORNEK_HIZI), 0)
        self.fb = geri_besleme
        self.tampon = np.zeros(max(self.D, 1), dtype=np.float32)

    def isle(self, x):
        if self.D == 0 or self.fb == 0:
            return x
        cikis = np.empty_like(x)
        for bas in range(0, len(x), self.D):  # D'den kisa parcalarla vektorel isle
            parca = x[bas:bas + self.D]
            k = len(parca)
            y = parca + self.fb * self.tampon[:k]
            self.tampon = np.concatenate([self.tampon[k:], y])
            cikis[bas:bas + k] = y
        return cikis


class SesMotoru:
    def __init__(self):
        self.pitch = PitchShifter()
        self.eq = Equalizer()
        self.yanki = Yanki()
        self.dist = 0.0      # 0 = kapali, 1-10 arasi
        self.robot = 0.0     # Hz, 0 = kapali
        self.robot_faz = 0.0
        self.gate = 0.01     # bu RMS altini sustur
        self.ses = 1.0
        self.kilit = threading.Lock()

    def preset(self, ad):
        p = PRESETLER[ad]
        with self.kilit:
            self.pitch.oran = p.get("pitch", 1.0)
            self.eq.ayarla(*p.get("eq", (0, 0, 0)))
            self.dist = p.get("dist", 0.0)
            self.robot = p.get("robot", 0.0)
            self.yanki.ayarla(*p.get("echo", (0, 0)))

    def isle(self, x):
        x = x.astype(np.float32)
        with self.kilit:
            if np.sqrt(np.mean(x ** 2)) < self.gate:
                x = np.zeros_like(x)
            x = self.pitch.isle(x)
            x = self.eq.isle(x)
            if self.robot > 0:
                t = self.robot_faz + 2 * np.pi * self.robot * np.arange(len(x)) / ORNEK_HIZI
                self.robot_faz = (t[-1] + 2 * np.pi * self.robot / ORNEK_HIZI) % (2 * np.pi)
                x = x * np.sin(t).astype(np.float32)
            if self.dist > 0:
                surus = 1 + self.dist * 3
                x = np.tanh(x * surus) / np.tanh(surus) * 0.8
            x = self.yanki.isle(x)
            x = x * self.ses
        return np.clip(x, -1, 1).astype(np.float32)

    def durum(self):
        return (f"pitch={self.pitch.oran:.2f}  eq={self.eq.degerler}  dist={self.dist}  "
                f"robot={self.robot}Hz  echo=({self.yanki.D / ORNEK_HIZI:.2f}s, {self.yanki.fb})  "
                f"gate={self.gate}  ses={self.ses}")


# ---------------------------------------------------------------- wav yardimcilari

def wav_oku(yol):
    with wave.open(yol, "rb") as w:
        kanal, genislik, hiz = w.getnchannels(), w.getsampwidth(), w.getframerate()
        veri = w.readframes(w.getnframes())
    if genislik != 2:
        sys.exit("Sadece 16-bit WAV destekleniyor (ffmpeg ile donustur).")
    x = np.frombuffer(veri, dtype=np.int16).astype(np.float32) / 32768
    if kanal > 1:
        x = x.reshape(-1, kanal).mean(axis=1)
    if hiz != ORNEK_HIZI:
        yeni = np.arange(0, len(x), hiz / ORNEK_HIZI)
        x = np.interp(yeni, np.arange(len(x)), x).astype(np.float32)
    return x


def wav_yaz(yol, x):
    with wave.open(yol, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(ORNEK_HIZI)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())


def dosya_isle(motor, giris, cikis):
    x = wav_oku(giris)
    parcalar = [motor.isle(x[i:i + BLOK]) for i in range(0, len(x), BLOK)]
    wav_yaz(cikis, np.concatenate(parcalar) if parcalar else x)


# ---------------------------------------------------------------- modlar

YARDIM = """Komutlar (Enter ile):
  p 1.4          pitch orani (0.5 kalin ... 2.0 ince)
  eq 3 0 -2      bas orta tiz (dB, -15..15)
  d 5            distortion (0 kapali, 1-10)
  r 50           robot frekansi Hz (0 kapali)
  e 0.25 0.4     yanki: sure(sn) geri_besleme(0-0.9)
  g 0.02         noise gate esigi
  v 1.5          ses seviyesi
  preset robot   hazir ayar ({})
  s              durumu goster
  q              cikis""".format(", ".join(PRESETLER))


def komut_isle(motor, satir):
    k = satir.split()
    if not k:
        return True
    try:
        c, a = k[0], [float(v) for v in k[1:]] if k[0] != "preset" else k[1:]
        with motor.kilit:
            if c == "p":
                motor.pitch.oran = a[0]
            elif c == "eq":
                motor.eq.ayarla(*a[:3])
            elif c == "d":
                motor.dist = a[0]
            elif c == "r":
                motor.robot = a[0]
            elif c == "e":
                motor.yanki.ayarla(max(a[0], BLOK / ORNEK_HIZI) if a[0] > 0 else 0, min(a[1], 0.9))
            elif c == "g":
                motor.gate = a[0]
            elif c == "v":
                motor.ses = a[0]
        if c == "preset":
            motor.preset(a[0])
        elif c == "q":
            return False
        elif c in ("h", "?", "yardim"):
            print(YARDIM)
        print(motor.durum())
    except (IndexError, ValueError, KeyError) as hata:
        print("Hatali komut:", hata, "  ('h' yardim)")
    return True


def canli(motor):
    try:
        import sounddevice as sd
    except (ImportError, OSError) as hata:
        sys.exit(f"sounddevice yuklenemedi ({hata}). kurulum.sh'yi calistirin.")

    def geri_cagir(indata, outdata, frames, zaman, durum):
        if durum:
            print(durum, file=sys.stderr)
        outdata[:, 0] = motor.isle(indata[:, 0])

    print(YARDIM)
    print(motor.durum())
    print("Kulaklik takin! Yoksa hoparlorden mikrofona geri besleme (cizirti) olur.")
    with sd.Stream(samplerate=ORNEK_HIZI, blocksize=BLOK, channels=1, dtype="float32",
                   latency="low", callback=geri_cagir):
        while True:
            try:
                if not komut_isle(motor, input("> ")):
                    break
            except (EOFError, KeyboardInterrupt):
                break


def kayit(motor, sure):
    for arac in ("termux-microphone-record", "ffmpeg"):
        if not shutil.which(arac):
            sys.exit(f"'{arac}' bulunamadi. kurulum.sh'yi calistirin.")
    klasor = tempfile.mkdtemp()
    ham, ara = os.path.join(klasor, "ham.m4a"), os.path.join(klasor, "ham.wav")
    cikis = os.path.expanduser(f"~/storage/shared/ses_degisik_{os.getpid()}.wav")
    if not os.path.isdir(os.path.dirname(cikis)):
        cikis = os.path.expanduser(f"~/ses_degisik_{os.getpid()}.wav")
    print(f"{sure} saniye kayit... konusun!")
    subprocess.run(["termux-microphone-record", "-f", ham, "-l", str(sure)], check=True)
    import time
    time.sleep(sure + 1)
    subprocess.run(["termux-microphone-record", "-q"], capture_output=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", ham, "-ac", "1",
                    "-ar", str(ORNEK_HIZI), "-sample_fmt", "s16", ara], check=True)
    dosya_isle(motor, ara, cikis)
    print("Kaydedildi:", cikis, "(WhatsApp/Telegram'dan sesli mesaj/dosya olarak gonderebilirsiniz)")
    if shutil.which("termux-media-player"):
        subprocess.run(["termux-media-player", "play", cikis])


def main():
    ap = argparse.ArgumentParser(description="Termux ses degistirici")
    ap.add_argument("mod", choices=["canli", "kayit", "dosya", "presetler"])
    ap.add_argument("dosyalar", nargs="*")
    ap.add_argument("--preset", default="normal", choices=list(PRESETLER))
    ap.add_argument("--sure", type=int, default=10)
    arg = ap.parse_args()

    if arg.mod == "presetler":
        for ad, p in PRESETLER.items():
            print(f"{ad:10s} {p}")
        return
    if lfilter is None:
        print("Uyari: scipy yok, equalizer devre disi (pkg install python-scipy).")

    motor = SesMotoru()
    motor.preset(arg.preset)
    if arg.mod == "canli":
        canli(motor)
    elif arg.mod == "kayit":
        kayit(motor, arg.sure)
    else:
        if len(arg.dosyalar) != 2:
            sys.exit("Kullanim: dosya giris.wav cikis.wav")
        dosya_isle(motor, *arg.dosyalar)
        print("Yazildi:", arg.dosyalar[1])


if __name__ == "__main__":
    main()
