# Termux Ses Değiştirici

MorphVOX benzeri ses değiştirici: pitch (ince/kalın), 3 bantlı equalizer, distortion, robot, yankı, noise gate ve hazır ayarlar (kadin, erkek, cocuk, sincap, canavar, robot, radyo, seytan, yanki, uzayli).

## Kurulum (Termux)
1. F-Droid'den **Termux** ve **Termux:API** uygulamalarını kurun. Termux:API'ye mikrofon izni verin.
2. `bash kurulum.sh`

## Kullanım
```bash
python ses_degistirici.py presetler                          # hazır ayarları listeler
python ses_degistirici.py kayit --sure 10 --preset canavar   # kaydet → işle → çal (root gerekmez)
python ses_degistirici.py dosya giris.wav cikis.wav --preset kadin
python ses_degistirici.py canli --preset robot               # gerçek zamanlı, kulaklık takın
```
Canlı modda ayarları anlık değiştirebilirsiniz: `p 1.4`, `eq 3 0 -2`, `d 5`, `r 50`, `e 0.25 0.4`, `preset seytan`, `q`.

## Sesli aramalar hakkında
Android, rootlu olmayan bir telefonda uygulamaların arama sırasında mikrofon sesini değiştirmesine izin vermez. Bu yüzden ne Termux ne de başka bir uygulama, normal bir GSM/WhatsApp aramasına doğrudan "sanal mikrofon" ekleyemez. Kullanabileceğiniz yollar:
- **İki cihaz + kablo:** Termux'ta `canli` modu çalıştırın. Telefonun kulaklık çıkışını bir TRRS adaptörü ya da kablo ile aramayı yaptığınız ikinci cihazın (telefon veya PC) mikrofon girişine bağlayın.
- **Bilgisayar:** Discord, WhatsApp Web veya Telegram Desktop ile aramayı bilgisayardan yapın. Değiştirilmiş sesi VB-Cable gibi bir sanal ses kablosuyla mikrofon olarak verin.
- **Sesli mesaj:** `kayit` modunun ürettiği WAV dosyasını sohbete dosya olarak gönderin.
- **Root:** Rootlu cihazlarda Magisk modülleriyle mümkün olabilir, ancak cihaza göre değişir.

Not: Termux uygulamasının kendisinde mikrofon izni yoktur. `module-sles-source` hata verirse canlı mod mikrofonu okuyamaz. Bu durumda `kayit` modu Termux:API üzerinden yine çalışır.
