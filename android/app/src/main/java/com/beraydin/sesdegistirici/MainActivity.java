package com.beraydin.sesdegistirici;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int ORNEK_HIZI = 48000;
    private static final int BLOK = 512;
    private static final int IZIN_KODU = 1;

    private static final int RENK_ARKA = 0xFF121218;
    private static final int RENK_KART = 0xFF1E1E28;
    private static final int RENK_VURGU = 0xFF7C5CFF;
    private static final int RENK_KAYIT = 0xFFE5484D;

    private final SesMotoru motor = new SesMotoru(ORNEK_HIZI);
    private final List<Ayar> ayarlar = new ArrayList<>();
    private final Map<String, float[]> presetler = new LinkedHashMap<>();

    private volatile boolean calisiyor;
    private volatile boolean dinle = true;
    private volatile RandomAccessFile kayitDosyasi;
    private Thread sesThread;
    private File sonKayit;
    private Uri sonKayitUri;

    private Button baslatBtn, kayitBtn, paylasBtn;
    private TextView durumYazi;
    private SharedPreferences tercihler;

    /** Bir kaydirma cubugu: ekrandaki etiket + motor alani. */
    private abstract class Ayar {
        final String anahtar, ad, birim;
        final float min, max, adim, varsayilan;
        SeekBar cubuk;
        TextView etiket;

        Ayar(String anahtar, String ad, String birim, float min, float max, float adim, float varsayilan) {
            this.anahtar = anahtar; this.ad = ad; this.birim = birim;
            this.min = min; this.max = max; this.adim = adim; this.varsayilan = varsayilan;
        }

        abstract void uygula(float deger);

        float deger() { return min + cubuk.getProgress() * adim; }

        void ayarla(float deger) { cubuk.setProgress(Math.round((deger - min) / adim)); }

        void etiketGuncelle() {
            float d = deger();
            String metin = adim >= 1 ? String.valueOf(Math.round(d)) : String.format(Locale.US, adim >= 0.1f ? "%.1f" : "%.2f", d);
            etiket.setText(ad + ":  " + metin + birim);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(RENK_ARKA);
        tercihler = getSharedPreferences("ayarlar", MODE_PRIVATE);

        ayarlar.add(new Ayar("pitch", "Ses tonu (pitch)", "x", 0.5f, 2.0f, 0.01f, 1f) { void uygula(float d) { motor.pitch = d; } });
        ayarlar.add(new Ayar("bas", "Bas", " dB", -15, 15, 1, 0) { void uygula(float d) { motor.bas = d; } });
        ayarlar.add(new Ayar("orta", "Orta", " dB", -15, 15, 1, 0) { void uygula(float d) { motor.orta = d; } });
        ayarlar.add(new Ayar("tiz", "Tiz", " dB", -15, 15, 1, 0) { void uygula(float d) { motor.tiz = d; } });
        ayarlar.add(new Ayar("dist", "Distortion", "", 0, 10, 0.1f, 0) { void uygula(float d) { motor.dist = d; } });
        ayarlar.add(new Ayar("robot", "Robot", " Hz", 0, 200, 1, 0) { void uygula(float d) { motor.robot = d; } });
        ayarlar.add(new Ayar("yankiSure", "Yankı süresi", " sn", 0, 1, 0.01f, 0) { void uygula(float d) { motor.yankiSure = d; } });
        ayarlar.add(new Ayar("yankiGeri", "Yankı şiddeti", "", 0, 0.9f, 0.01f, 0) { void uygula(float d) { motor.yankiGeri = d; } });
        ayarlar.add(new Ayar("gate", "Gürültü kapısı", "", 0, 0.1f, 0.001f, 0.01f) { void uygula(float d) { motor.gate = d; } });
        ayarlar.add(new Ayar("ses", "Ses seviyesi", "x", 0, 3, 0.1f, 1) { void uygula(float d) { motor.ses = d; } });

        // pitch, bas, orta, tiz, dist, robot, yankiSure, yankiGeri, gate, ses
        presetler.put("Normal",  new float[]{1f, 0, 0, 0, 0, 0, 0, 0, 0.01f, 1});
        presetler.put("Kadın",   new float[]{1.45f, -3, 0, 3, 0, 0, 0, 0, 0.01f, 1});
        presetler.put("Erkek",   new float[]{0.72f, 4, 0, -2, 0, 0, 0, 0, 0.01f, 1});
        presetler.put("Çocuk",   new float[]{1.7f, -4, 2, 3, 0, 0, 0, 0, 0.01f, 1});
        presetler.put("Sincap",  new float[]{2f, 0, 0, 0, 0, 0, 0, 0, 0.01f, 1});
        presetler.put("Canavar", new float[]{0.55f, 6, 0, -4, 6, 0, 0, 0, 0.01f, 1});
        presetler.put("Robot",   new float[]{1f, 0, 0, 0, 2, 60, 0.04f, 0.4f, 0.01f, 1});
        presetler.put("Radyo",   new float[]{1f, -12, 6, -12, 4, 0, 0, 0, 0.01f, 1});
        presetler.put("Şeytan",  new float[]{0.6f, 5, 0, 0, 3, 0, 0.18f, 0.35f, 0.01f, 1});
        presetler.put("Yankı",   new float[]{1f, 0, 0, 0, 0, 0, 0.3f, 0.45f, 0.01f, 1});
        presetler.put("Uzaylı",  new float[]{1.3f, 0, 0, 0, 0, 30, 0.12f, 0.3f, 0.01f, 1});

        setContentView(arayuzOlustur());
        for (Ayar a : ayarlar) a.ayarla(tercihler.getFloat(a.anahtar, a.varsayilan));
    }

    // ------------------------------------------------------------------ arayuz

    private View arayuzOlustur() {
        ScrollView kaydir = new ScrollView(this);
        kaydir.setBackgroundColor(RENK_ARKA);
        LinearLayout kok = new LinearLayout(this);
        kok.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        kok.setPadding(p, p, p, p);
        kaydir.addView(kok);

        TextView baslik = yazi("Ses Değiştirici", 26, Color.WHITE);
        baslik.setTypeface(Typeface.DEFAULT_BOLD);
        kok.addView(baslik);
        durumYazi = yazi("Kapalı", 14, 0xFFAAAAB8);
        durumYazi.setPadding(0, dp(4), 0, dp(12));
        kok.addView(durumYazi);

        baslatBtn = buton("▶  BAŞLAT", RENK_VURGU);
        baslatBtn.setOnClickListener(v -> { if (calisiyor) durdur(); else baslatIzinli(); });
        kok.addView(baslatBtn, tamGenislik(dp(56)));

        CheckBox dinleKutu = new CheckBox(this);
        dinleKutu.setText("Kendi sesimi duy (kulaklık takın)");
        dinleKutu.setTextColor(Color.WHITE);
        dinleKutu.setChecked(true);
        dinleKutu.setOnCheckedChangeListener((b, secili) -> dinle = secili);
        kok.addView(dinleKutu);

        LinearLayout kayitSatir = new LinearLayout(this);
        kayitSatir.setPadding(0, dp(8), 0, dp(8));
        kayitBtn = buton("●  KAYDET", RENK_KAYIT);
        kayitBtn.setOnClickListener(v -> kayitDegistir());
        paylasBtn = buton("PAYLAŞ", 0xFF2E7D5B);
        paylasBtn.setEnabled(false);
        paylasBtn.setAlpha(0.4f);
        paylasBtn.setOnClickListener(v -> paylas());
        LinearLayout.LayoutParams yarim = new LinearLayout.LayoutParams(0, dp(48), 1);
        yarim.setMargins(0, 0, dp(8), 0);
        kayitSatir.addView(kayitBtn, yarim);
        kayitSatir.addView(paylasBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        kok.addView(kayitSatir);

        kok.addView(bolumBasligi("Hazır sesler"));
        HorizontalScrollView presetKaydir = new HorizontalScrollView(this);
        presetKaydir.setHorizontalScrollBarEnabled(false);
        LinearLayout presetSatir = new LinearLayout(this);
        for (Map.Entry<String, float[]> e : presetler.entrySet()) {
            Button b = buton(e.getKey(), RENK_KART);
            b.setOnClickListener(v -> presetUygula(e.getValue()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMargins(0, 0, dp(8), 0);
            presetSatir.addView(b, lp);
        }
        presetKaydir.addView(presetSatir);
        kok.addView(presetKaydir);

        String[] bolumler = {"Ses tonu", "Equalizer", null, null, "Efektler", null, null, null, "Diğer", null};
        LinearLayout kart = null;
        for (int i = 0; i < ayarlar.size(); i++) {
            if (bolumler[i] != null) {
                kok.addView(bolumBasligi(bolumler[i]));
                kart = new LinearLayout(this);
                kart.setOrientation(LinearLayout.VERTICAL);
                kart.setPadding(dp(14), dp(10), dp(14), dp(10));
                kart.setBackground(yuvarlak(RENK_KART));
                kok.addView(kart, tamGenislik(LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            kart.addView(cubukOlustur(ayarlar.get(i)));
        }

        Button sifirla = buton("Ayarları sıfırla", RENK_KART);
        sifirla.setOnClickListener(v -> presetUygula(presetler.get("Normal")));
        LinearLayout.LayoutParams sp = tamGenislik(dp(48));
        sp.setMargins(0, dp(16), 0, 0);
        kok.addView(sifirla, sp);

        TextView bilgi = yazi("Not: Android, telefon ve WhatsApp aramalarında uygulamaların mikrofon sesini "
                + "değiştirmesine izin vermez. Değişmiş sesi göndermek için KAYDET ile kaydedip PAYLAŞ ile "
                + "sesli mesaj olarak gönderebilir ya da kulaklık çıkışını kabloyla başka bir cihazın mikrofonuna "
                + "bağlayabilirsiniz.", 12, 0xFF8888A0);
        bilgi.setPadding(0, dp(16), 0, dp(24));
        kok.addView(bilgi);
        return kaydir;
    }

    private View cubukOlustur(Ayar a) {
        LinearLayout satir = new LinearLayout(this);
        satir.setOrientation(LinearLayout.VERTICAL);
        satir.setPadding(0, dp(6), 0, dp(6));
        a.etiket = yazi(a.ad, 15, Color.WHITE);
        a.cubuk = new SeekBar(this);
        a.cubuk.setMax(Math.round((a.max - a.min) / a.adim));
        a.cubuk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int ilerleme, boolean kullanici) {
                float d = a.deger();
                a.uygula(d);
                a.etiketGuncelle();
                tercihler.edit().putFloat(a.anahtar, d).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        // Etikete uzun basinca varsayilana don
        a.etiket.setOnLongClickListener(v -> { a.ayarla(a.varsayilan); return true; });
        satir.addView(a.etiket);
        satir.addView(a.cubuk);
        a.uygula(a.deger());
        a.etiketGuncelle();
        return satir;
    }

    private void presetUygula(float[] degerler) {
        for (int i = 0; i < ayarlar.size(); i++) ayarlar.get(i).ayarla(degerler[i]);
    }

    private TextView bolumBasligi(String metin) {
        TextView t = yazi(metin.toUpperCase(new Locale("tr")), 12, RENK_VURGU);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(8));
        return t;
    }

    private TextView yazi(String metin, int boyut, int renk) {
        TextView t = new TextView(this);
        t.setText(metin);
        t.setTextSize(boyut);
        t.setTextColor(renk);
        return t;
    }

    private Button buton(String metin, int renk) {
        Button b = new Button(this);
        b.setText(metin);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(yuvarlak(renk));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private GradientDrawable yuvarlak(int renk) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(renk);
        g.setCornerRadius(dp(12));
        return g;
    }

    private LinearLayout.LayoutParams tamGenislik(int yukseklik) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, yukseklik);
    }

    private int dp(int deger) {
        return Math.round(deger * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ ses

    private void baslatIzinli() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, IZIN_KODU);
            return;
        }
        baslat();
    }

    @Override
    public void onRequestPermissionsResult(int kod, String[] izinler, int[] sonuclar) {
        if (kod == IZIN_KODU && sonuclar.length > 0 && sonuclar[0] == PackageManager.PERMISSION_GRANTED) {
            baslat();
        } else {
            Toast.makeText(this, "Mikrofon izni gerekli", Toast.LENGTH_LONG).show();
        }
    }

    private void baslat() {
        calisiyor = true;
        baslatBtn.setText("■  DURDUR");
        durumYazi.setText("Açık — konuşun");
        sesThread = new Thread(this::sesDongusu, "ses");
        sesThread.setPriority(Thread.MAX_PRIORITY);
        sesThread.start();
    }

    private void durdur() {
        if (kayitDosyasi != null) kayitDegistir();
        calisiyor = false;
        if (sesThread != null) {
            try { sesThread.join(1000); } catch (InterruptedException ignored) {}
            sesThread = null;
        }
        baslatBtn.setText("▶  BAŞLAT");
        durumYazi.setText("Kapalı");
    }

    @SuppressLint("MissingPermission") // izin baslatIzinli() icinde kontrol ediliyor
    private void sesDongusu() {
        int girisBoyut = Math.max(AudioRecord.getMinBufferSize(ORNEK_HIZI,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOK * 4);
        int cikisBoyut = Math.max(AudioTrack.getMinBufferSize(ORNEK_HIZI,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BLOK * 4);
        AudioRecord kayit = null;
        AudioTrack cal = null;
        try {
            kayit = new AudioRecord(MediaRecorder.AudioSource.MIC, ORNEK_HIZI,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, girisBoyut);
            AudioTrack.Builder tb = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(ORNEK_HIZI)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(cikisBoyut)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            cal = tb.build();

            kayit.startRecording();
            cal.play();

            short[] ham = new short[BLOK];
            short[] sessizlik = new short[BLOK];
            float[] f = new float[BLOK];
            byte[] bayt = new byte[BLOK * 2];
            while (calisiyor) {
                int n = kayit.read(ham, 0, BLOK);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) f[i] = ham[i] / 32768f;
                motor.isle(f, n);
                for (int i = 0; i < n; i++) ham[i] = (short) (f[i] * 32767);
                cal.write(dinle ? ham : sessizlik, 0, n);

                RandomAccessFile raf = kayitDosyasi;
                if (raf != null) {
                    for (int i = 0; i < n; i++) {
                        bayt[2 * i] = (byte) ham[i];
                        bayt[2 * i + 1] = (byte) (ham[i] >> 8);
                    }
                    synchronized (this) {
                        if (kayitDosyasi != null) raf.write(bayt, 0, n * 2);
                    }
                }
            }
        } catch (Exception e) {
            String mesaj = e.getMessage();
            runOnUiThread(() -> {
                Toast.makeText(this, "Ses hatası: " + mesaj, Toast.LENGTH_LONG).show();
                durdur();
            });
        } finally {
            if (kayit != null) { try { kayit.stop(); } catch (Exception ignored) {} kayit.release(); }
            if (cal != null) { try { cal.stop(); } catch (Exception ignored) {} cal.release(); }
        }
    }

    // ------------------------------------------------------------------ kayit

    private void kayitDegistir() {
        if (kayitDosyasi == null) {
            if (!calisiyor) baslatIzinli();
            try {
                String ad = "ses_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
                sonKayit = new File(getCacheDir(), ad);
                RandomAccessFile raf = new RandomAccessFile(sonKayit, "rw");
                raf.setLength(0);
                raf.write(new byte[44]); // baslik sonra doldurulacak
                kayitDosyasi = raf;
                kayitBtn.setText("■  KAYDI BİTİR");
                durumYazi.setText("Kaydediliyor…");
            } catch (IOException e) {
                Toast.makeText(this, "Kayıt açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            RandomAccessFile raf = kayitDosyasi;
            synchronized (this) { kayitDosyasi = null; }
            kayitBtn.setText("●  KAYDET");
            durumYazi.setText(calisiyor ? "Açık — konuşun" : "Kapalı");
            try {
                wavBasligiYaz(raf);
                raf.close();
                kayitKaydet();
            } catch (IOException e) {
                Toast.makeText(this, "Kayıt hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void wavBasligiYaz(RandomAccessFile raf) throws IOException {
        int veri = (int) raf.length() - 44;
        raf.seek(0);
        raf.writeBytes("RIFF");
        raf.writeInt(Integer.reverseBytes(36 + veri));
        raf.writeBytes("WAVEfmt ");
        raf.writeInt(Integer.reverseBytes(16));
        raf.writeShort(Short.reverseBytes((short) 1));          // PCM
        raf.writeShort(Short.reverseBytes((short) 1));          // mono
        raf.writeInt(Integer.reverseBytes(ORNEK_HIZI));
        raf.writeInt(Integer.reverseBytes(ORNEK_HIZI * 2));     // bayt/sn
        raf.writeShort(Short.reverseBytes((short) 2));          // blok hizalama
        raf.writeShort(Short.reverseBytes((short) 16));         // bit
        raf.writeBytes("data");
        raf.writeInt(Integer.reverseBytes(veri));
    }

    /** Onbellekteki kaydi Muzik/SesDegistirici klasorune kopyalar. */
    private void kayitKaydet() throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, sonKayit.getName());
            cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SesDegistirici");
            sonKayitUri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (sonKayitUri == null) throw new IOException("MediaStore");
            try (OutputStream out = getContentResolver().openOutputStream(sonKayitUri)) {
                kopyala(out);
            }
            Toast.makeText(this, "Kaydedildi: Müzik/SesDegistirici/" + sonKayit.getName(), Toast.LENGTH_LONG).show();
        } else {
            File klasor = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "");
            File hedef = new File(klasor, sonKayit.getName());
            try (OutputStream out = new FileOutputStream(hedef)) {
                kopyala(out);
            }
            sonKayitUri = null;
            Toast.makeText(this, "Kaydedildi: " + hedef.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
        paylasBtn.setEnabled(sonKayitUri != null);
        paylasBtn.setAlpha(sonKayitUri != null ? 1f : 0.4f);
    }

    private void kopyala(OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(sonKayit)) {
            byte[] tampon = new byte[8192];
            int n;
            while ((n = in.read(tampon)) > 0) out.write(tampon, 0, n);
        }
    }

    private void paylas() {
        if (sonKayitUri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_STREAM, sonKayitUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Sesi gönder"));
    }

    @Override
    protected void onDestroy() {
        durdur();
        super.onDestroy();
    }
}
