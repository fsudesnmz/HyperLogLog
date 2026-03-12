# 🔢 HyperLogLog – Cardinality Estimation in Java
---

## 📌 İçindekiler

- [Proje Hakkında](#-proje-hakkında)
- [HyperLogLog Nedir?](#-hyperloglog-nedir)
- [Özellikler](#-özellikler)
- [Algoritma Analizi](#-algoritma-analizi)
- [Kurulum ve Çalıştırma](#-kurulum-ve-çalıştırma)
- [Kullanım](#-kullanım)
- [Teorik Hata Analizi](#-teorik-hata-analizi)
- [Proje Yapısı](#-proje-yapısı)
- [Kaynaklar](#-kaynaklar)

---

## 📖 Proje Hakkında

Bu proje, **Büyük Veri Analitiği** dersi kapsamında HyperLogLog (HLL) algoritmasının sıfırdan Java ile implementasyonunu içermektedir.

**Çözülen Problem:** Milyonlarca kayıt içeren bir veri kümesinde kaç farklı (benzersiz) eleman olduğunu bulmak, tam sayım yöntemleriyle `O(n)` bellek gerektirir. HyperLogLog bu problemi yalnızca **birkaç kilobyte** bellekle ve `%1–3` hata payıyla çözer.

---

## 🧠 HyperLogLog Nedir?

HyperLogLog, Philippe Flajolet ve ekibi tarafından 2007 yılında yayımlanan bir olasılıksal algoritmadır. Temel fikir şudur:

> Bir hash fonksiyonundan geçirilen rastgele bir değerin başında `k` adet ardışık sıfır görülmesi, yaklaşık `2^k` farklı elemanın varlığına işaret eder.

### Nasıl Çalışır?

```
Girdi Elemanı
     │
     ▼
┌──────────────┐
│ Hash (64-bit) │   →   0110 1001 0101 0010 ...
└──────────────┘
     │
     ├──► İlk b bit → Kova İndeksi (j)
     │
     └──► Kalan bitler → Ardışık Sıfır Sayısı (ρ)
                              │
                              ▼
                    registers[j] = max(registers[j], ρ)
                              │
                              ▼
              E = α_m · m² · (Σ 2^(-registers[i]))⁻¹
```

---

## ✨ Özellikler

| Özellik | Açıklama |
|--------|----------|
| **Hash Fonksiyonu** | FNV-1a 64-bit + finalization karıştırması (avalanche effect) |
| **Bucketing** | `b` bit ile `m = 2^b` kovaya bölme |
| **Register Yapısı** | Her kovada maksimum ρ değeri takibi |
| **Harmonik Ortalama** | Önyargısız kardinalite tahmini |
| **Küçük Aralık Düzeltmesi** | Linear Counting (E ≤ 2.5·m için) |
| **Büyük Aralık Düzeltmesi** | Logaritmik 2³² taşma telafisi |
| **Birleştirme (Merge)** | İki HLL yapısının veri kaybı olmadan union tahmini |
| **Teorik Analiz** | `StdError = 1.04/√m` formülüyle hata tablosu |

---

## 📐 Algoritma Analizi

### Zaman Karmaşıklığı

| İşlem | Karmaşıklık |
|-------|-------------|
| `add(item)` | O(1) |
| `estimate()` | O(m) |
| `merge(other)` | O(m) |

### Alan Karmaşıklığı

```
Tam Sayım  →  O(n)    [n = eleman sayısı]
HyperLogLog →  O(m)    [m = kova sayısı, sabit!]
```

### Harmonik Ortalama Formülü

$$E = \alpha_m \cdot m^2 \cdot \left( \sum_{i=1}^{m} 2^{-\text{registers}[i]} \right)^{-1}$$

### Düzeltme Faktörleri

```
E ≤ 2.5·m    →  LinearCounting:  m · ln(m/V)
              [V = sıfır register sayısı]

E > 2³²/30   →  -2³² · ln(1 - E/2³²)

Diğer        →  E  (ham tahmin)
```

### Teorik Hata Tablosu

| b | m = 2^b | Standart Hata | Bellek |
|---|---------|---------------|--------|
| 4 | 16 | %26.00 | 16 B |
| 8 | 256 | %6.50 | 256 B |
| 10 | 1.024 | %3.25 | 1 KB |
| 12 | 4.096 | %1.63 | 4 KB |
| 14 | 16.384 | %0.81 | 16 KB |
| 16 | 65.536 | %0.41 | 64 KB |

> **Formül:** `StandartHata = 1.04 / √m`
> **Kural:** m 4 katına çıktığında hata 2'ye bölünür → O(1/√m)

---

## 🚀 Kurulum ve Çalıştırma

### Gereksinimler

- Java 11 veya üzeri
- Herhangi bir IDE (IntelliJ IDEA, Eclipse, VS Code)

### Derleme ve Çalıştırma

```bash
# Repoyu klonla
git clone https://github.com/KULLANICI_ADI/hyperloglog-java.git
cd hyperloglog-java

# Derle
javac HyperLogLog.java

# Çalıştır
java HyperLogLog
```

### Beklenen Çıktı

```
═══════════════════════════════════════════════════════════════
          HyperLogLog – Cardinality Estimation Demo
═══════════════════════════════════════════════════════════════

【1】 TEORİK HATA ANALİZİ ...
【2】 TEMEL KULLANIM (b=10, m=1024) ...
  Gerçek:     100  |  Tahmin:      98  |  Hata: 2.00%
  Gerçek:    1000  |  Tahmin:    1012  |  Hata: 1.20%
  Gerçek:   10000  |  Tahmin:   10230  |  Hata: 2.30%
  ...
【4】 BİRLEŞTİRME (MERGE) ...
【5】 DÜZELTME FAKTÖRLERİ ...
```

---

## 💻 Kullanım

```java
// 1. Temel Kullanım
HyperLogLog hll = new HyperLogLog(10); // b=10, m=1024 kova
hll.add("kullanici_1");
hll.add("kullanici_2");
hll.add("kullanici_1"); // tekrar ekleme etkisizdir
long tahminiSayi = hll.estimate();

// 2. İki HLL'yi Birleştirme (Merge)
HyperLogLog hll1 = new HyperLogLog(12);
HyperLogLog hll2 = new HyperLogLog(12);
// ... her iki yapıya veri ekle ...
HyperLogLog birlesik = hll1.merge(hll2);
long unionKardinalitesi = birlesik.estimate();

// 3. Teorik Hata Bilgisi
double hata = hll.theoreticalError(); // ≈ 0.0325 (%3.25)
```

---

## 📊 Teorik Hata Analizi

Kova sayısı `m` arttıkça tahmin hatası `1/√m` oranında azalır:

```
m=16     ████████████████████████████  %26.00
m=64     ████████████████             %13.00
m=256    ████████                     %6.50
m=1024   ████                         %3.25
m=4096   ██                           %1.63
m=16384  █                            %0.81
```

Bu ilişki, bellek-hassasiyet dengesini optimize etmek için kullanılır.

---

## 📁 Proje Yapısı

```
hyperloglog-java/
│
└── HyperLogLog.java     # Tüm bileşenleri içeren tek dosya
    ├── hash64()          # FNV-1a 64-bit hash fonksiyonu
    ├── add()             # Bucketing + register güncelleme
    ├── estimate()        # Harmonik ortalama + düzeltme faktörleri
    ├── merge()           # İki HLL birleştirme
    ├── theoreticalError()# Teorik hata hesabı
    └── main()            # Demo ve testler
```

---

## 📚 Kaynaklar

- Flajolet, P., Fusy, É., Gandouet, O., & Meunier, F. (2007). [HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm](http://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf). *DMTCS Proceedings*.
- [Wikipedia – HyperLogLog](https://en.wikipedia.org/wiki/HyperLogLog)
- [Redis HyperLogLog Implementasyonu](https://redis.io/docs/data-types/probabilistic/hyperloglogs/)

---

## 📄 Lisans

Bu proje MIT Lisansı altında lisanslanmıştır.

---
