# B133 Settings UI — Source Preview

Bu dal, Ayarlar ekranının kaynak kod düzeyinde görsel ve işlevsel yeniden tasarım ön izlemesidir.

## Uygulananlar
- Ayarlar ekranı koyu lacivert, kart tabanlı kontrol merkezi düzenine taşındı.
- Üst bölümde sürüm rozeti, sistem durumu ve çalışma modu özeti eklendi.
- Hesap ve Bağlantılar bölümü mevcut gerçek HTTPS backend ve API anahtarı akışıyla yeniden düzenlendi.
- API anahtarının mevcut Android Keystore tabanlı şifreli saklama davranışı korunur.
- Deneysel sağlayıcı ve Yahoo gecikmeli BIST yedeği anahtarları korunur.
- Analiz ve Tarama bölümünde mevcut yenileme süresi ayarı korunur.
- Bildirim ve Uyarılar bölümünde mevcut fırsat bildirim anahtarı korunur.
- Uygulama ve Görünüm bölümü yalnız mevcut koyu tema/Türkçe arayüz durumunu gösterir; yeni tema veya dil motoru eklenmedi.
- Sistem Tanılama, veri mimarisi ve TradingView güvenli görüntüleme bilgileri aynı gerçek davranışlar üzerinden gösterilir.
- Alt navigasyon ve diğer ekranların çalışma yapısı değiştirilmedi.

## Kapsam dışı
- APK üretilmedi.
- versionCode/versionName değiştirilmedi.
- Yeni backend, API, repository, database veya navigation mantığı eklenmedi.
- Mock/sahte piyasa verisi eklenmedi.
- Ayar sıfırlama, tema seçimi, dil seçimi, simülasyon modu ve yeni bildirim geçmişi ekranları henüz eklenmedi.

## Değişen çalışma dosyaları
- `app/src/main/java/tr/borsatakip/v5/ui/SettingsActivity.kt`
- `app/src/main/res/layout/activity_settings.xml`

Sonraki tasarım değişiklikleri bu dal üzerinde uygulanabilir; APK, kullanıcı nihai tasarımı onayladıktan sonra üretilmelidir.
