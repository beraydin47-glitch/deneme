package com.beraydin.sesdegistirici;

/**
 * Efekt zinciri: noise gate -> pitch -> 3 bant EQ -> robot -> distortion -> yanki -> ses seviyesi.
 * Ayarlar UI thread'inden degisir, ses thread'i her blokta okur (volatile).
 */
public class SesMotoru {
    public volatile float pitch = 1f;      // 0.5 .. 2.0
    public volatile float bas = 0f;        // dB
    public volatile float orta = 0f;       // dB
    public volatile float tiz = 0f;        // dB
    public volatile float dist = 0f;       // 0 .. 10
    public volatile float robot = 0f;      // Hz, 0 = kapali
    public volatile float yankiSure = 0f;  // saniye
    public volatile float yankiGeri = 0f;  // 0 .. 0.9
    public volatile float gate = 0.01f;    // RMS esigi
    public volatile float ses = 1f;        // 0 .. 3

    private final int fs;

    // pitch
    private final int pencere;
    private final float[] pTampon = new float[8192];
    private int pYaz;
    private double faz;

    // EQ
    private final Biquad[] eq = {new Biquad(), new Biquad(), new Biquad()};
    private float eqBas = Float.NaN, eqOrta = Float.NaN, eqTiz = Float.NaN;

    // robot
    private double robotFaz;

    // yanki
    private final float[] yTampon;
    private int yYaz;

    public SesMotoru(int ornekHizi) {
        fs = ornekHizi;
        pencere = ornekHizi / 22;               // ~45 ms
        yTampon = new float[ornekHizi * 2];     // en fazla 2 sn
    }

    public void isle(float[] x, int n) {
        float oran = pitch, d = dist, r = robot, fb = yankiGeri, v = ses;
        int gecikme = Math.min((int) (yankiSure * fs), yTampon.length - 1);
        eqGuncelle();

        double toplam = 0;
        for (int i = 0; i < n; i++) toplam += x[i] * x[i];
        boolean sessiz = Math.sqrt(toplam / Math.max(n, 1)) < gate;

        float surus = 1 + d * 3;
        float tanhSurus = (float) Math.tanh(surus);
        double robotAdim = 2 * Math.PI * r / fs;
        int maske = pTampon.length - 1;

        for (int i = 0; i < n; i++) {
            float s = sessiz ? 0f : x[i];

            // pitch: iki okuma kafasi, sin^2 crossfade
            pTampon[pYaz] = s;
            if (Math.abs(oran - 1f) > 0.001f) {
                faz += 1.0 - oran;
                if (faz >= pencere) faz -= pencere;
                if (faz < 0) faz += pencere;
                float y = 0;
                for (int k = 0; k < 2; k++) {
                    double dl = k == 0 ? faz : (faz + pencere / 2.0) % pencere;
                    double konum = pYaz - dl;
                    int i0 = (int) Math.floor(konum);
                    float kesir = (float) (konum - i0);
                    float a = pTampon[i0 & maske], b = pTampon[(i0 + 1) & maske];
                    double g = Math.sin(Math.PI * dl / pencere);
                    y += (a + (b - a) * kesir) * (float) (g * g);
                }
                s = y;
            }
            pYaz = (pYaz + 1) & maske;

            for (Biquad f : eq) s = f.isle(s);

            if (r > 0) {
                s *= (float) Math.sin(robotFaz);
                robotFaz += robotAdim;
                if (robotFaz > 2 * Math.PI) robotFaz -= 2 * Math.PI;
            }

            if (d > 0) s = (float) Math.tanh(s * surus) / tanhSurus * 0.8f;

            if (gecikme > 0 && fb > 0) {
                int oku = yYaz - gecikme;
                if (oku < 0) oku += yTampon.length;
                s += fb * yTampon[oku];
            }
            yTampon[yYaz] = s;
            yYaz = (yYaz + 1) % yTampon.length;

            s *= v;
            x[i] = s > 1f ? 1f : (s < -1f ? -1f : s);
        }
    }

    private void eqGuncelle() {
        float b = bas, o = orta, t = tiz;
        if (b == eqBas && o == eqOrta && t == eqTiz) return;
        eqBas = b; eqOrta = o; eqTiz = t;
        eq[0].ayarla(Biquad.ALCAK, 200, b, 0.707, fs);
        eq[1].ayarla(Biquad.TEPE, 1000, o, 0.9, fs);
        eq[2].ayarla(Biquad.YUKSEK, 4000, t, 0.707, fs);
    }

    /** RBJ Audio EQ Cookbook biquad (transposed direct form II). */
    static class Biquad {
        static final int ALCAK = 0, TEPE = 1, YUKSEK = 2;
        private double b0 = 1, b1, b2, a1, a2, z1, z2;
        private boolean aktif;

        void ayarla(int tip, double f0, double db, double q, int fs) {
            aktif = db != 0;
            if (!aktif) return;
            double A = Math.pow(10, db / 40), w0 = 2 * Math.PI * f0 / fs;
            double c = Math.cos(w0), alfa = Math.sin(w0) / (2 * q), sA = 2 * Math.sqrt(A) * alfa;
            double nb0, nb1, nb2, na0, na1, na2;
            if (tip == TEPE) {
                nb0 = 1 + alfa * A; nb1 = -2 * c; nb2 = 1 - alfa * A;
                na0 = 1 + alfa / A; na1 = -2 * c; na2 = 1 - alfa / A;
            } else if (tip == ALCAK) {
                nb0 = A * ((A + 1) - (A - 1) * c + sA);
                nb1 = 2 * A * ((A - 1) - (A + 1) * c);
                nb2 = A * ((A + 1) - (A - 1) * c - sA);
                na0 = (A + 1) + (A - 1) * c + sA;
                na1 = -2 * ((A - 1) + (A + 1) * c);
                na2 = (A + 1) + (A - 1) * c - sA;
            } else {
                nb0 = A * ((A + 1) + (A - 1) * c + sA);
                nb1 = -2 * A * ((A - 1) + (A + 1) * c);
                nb2 = A * ((A + 1) + (A - 1) * c - sA);
                na0 = (A + 1) - (A - 1) * c + sA;
                na1 = 2 * ((A - 1) - (A + 1) * c);
                na2 = (A + 1) - (A - 1) * c - sA;
            }
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0;
        }

        float isle(float x) {
            if (!aktif) return x;
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return (float) y;
        }
    }
}
