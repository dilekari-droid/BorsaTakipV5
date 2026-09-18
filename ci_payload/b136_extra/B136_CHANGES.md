# B136 — BIST Tarama durum/UI tutarlılığı

## Yapılanlar
- BIST Tarama ana durum kartı artık yalnız gerçek manuel tarama çalışırken “BIST hisseleri taranıyor” gösterir.
- `STOPPED` kullanıcı metni kaldırıldı; manuel iptal `Durduruldu` olarak ve gri durum noktasıyla gösterilir.
- Durum noktası çalışma durumuna göre dinamik renklendirilir: çalışma yeşil, hazırlık sarı, hata kırmızı, bekleme/durdurma gri.
- Otomatik tarama açıkken ana kart `Otomatik tarama açık • manuel tarama beklemede` ayrımını gösterir; manuel STOPPED ile otomatik açık durumu birbirine karıştırılmaz.
- Otomatik tarama son durumu kullanıcıya Türkçe karşılıkla gösterilir.
- Yahoo yedek/gecikmeli kaynakta 0 ilerlemede “analiz hazır” iddiası kaldırıldı; analiz başlatılmadı/beklemede olarak gösterilir.
- Yedek analiz başlat düğmesi küçük ekranlarda taşmayı azaltmak için kısaltıldı; provider uyarısı kaynak/detay alanında korunur.
- Hero kart sabit 148dp yerine `wrap_content + minHeight=148dp` oldu; uzun açıklama kesilmez.
- Scroll içeriğinin alt boşluğu 36dp yapıldı; alt bilgi kartının bottom navigation yanında kesilme riski azaltıldı.
- Bottom navigation standardı geri getirildi: `Ana Sayfa / Hisseler / VİOP / Favoriler / Ayarlar`. BIST Tarama, `Hisseler` alanı altında aktif vurgulanır; VİOP sekmesi yeniden adlandırılmaz.
- Sürüm `versionCode=136`, `versionName=5.2.5`.

## Korunanlar
- BIST tarama algoritması, provider router, Yahoo fallback, gerçek backend doğrulama zinciri değiştirilmedi.
- MTF cache fix (B135), VİOP algoritması, favoriler, ayarlar ve scheduler mantığı değiştirilmedi.
- Sahte veri veya sahte başarı durumu eklenmedi.
