# BorsaTakip V5.1.28

## Hesaplama ve veri güvenilirliği

- Veri güveni artık gerçek veri özelliklerinden ve OHLCV kalite sonuçlarından hesaplanır.
- Nihai sinyal; teknik puan, veri güveni ve risk etkisini ayrı ayrı içerir.
- Eşit, zayıf veya birbirine çok yakın LONG/SHORT puanları NÖTR sonuç verir.
- Düz fiyat serisinde Wilder RSI değeri 50 olarak düzeltilmiştir.
- Destek ve direnç penceresinden güncel mum çıkarılmıştır.
- Hacim yalnız fiyat yönünü doğruladığında LONG veya SHORT puanına eklenir.
- Günlük veride yanlış adlandırılmış tüm dönem VWAP yerine 20 dönemlik VWMA yaklaşımı kullanılır.
- OHLCV doğrulama, sıralama ve tekilleştirme tek hesaplayıcıda birleştirilmiştir.
- Risk puanı ATR yanında aşağı yönlü oynaklık ve azami düşüşü de içerir.
- Doğrusal regresyon kanalında artık standart sapması iki parametreli model için n-2 ile hesaplanır.

## Testler

- Düz seri RSI, NÖTR yön, veri kalitesi cezası ve regresyon serbestlik derecesi testleri eklenmiştir.

## Sürüm

- versionCode: 78
- versionName: 5.1.28
