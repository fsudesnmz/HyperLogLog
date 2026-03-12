import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * HyperLogLog (HLL) - Cardinality Estimation (Küme Büyüklüğü Tahmini)
 *
 * Teorik Hata Analizi:
 *   Standart hata = 1.04 / sqrt(m)
 *   m = kova sayısı (2^b), b = bit sayısı
 *
 *   b=4  → m=16    → hata ≈ %26.0
 *   b=8  → m=256   → hata ≈  %6.5
 *   b=10 → m=1024  → hata ≈  %3.25
 *   b=12 → m=4096  → hata ≈  %1.625
 *   b=14 → m=16384 → hata ≈  %0.8125
 */
public class HyperLogLog {

    // ─────────────────────────────────────────────────────────────────────────
    // 1.  YAPILANDIRICI VE TEMEL ALANLAR
    // ─────────────────────────────────────────────────────────────────────────

    /** Kova (bucket/register) sayısı: m = 2^b */
    private final int m;

    /** Kova indeksini belirlemek için kullanılan bit sayısı */
    private final int b;

    /**
     * Register dizisi:
     *   registers[i] = i. kovada görülen en büyük ardışık sıfır sayısı + 1
     */
    private final byte[] registers;

    /**
     * @param b  Kova bit genişliği (4 ≤ b ≤ 16)
     *           Örnek: b=10 → 1024 kova, ≈ %3.25 standart hata
     */
    public HyperLogLog(int b) {
        if (b < 4 || b > 16) {
            throw new IllegalArgumentException("b değeri 4 ile 16 arasında olmalıdır.");
        }
        this.b = b;
        this.m = 1 << b;            // 2^b
        this.registers = new byte[m];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.  HASH FONKSİYONU  (yüksek kaliteli, 64-bit)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MurmurHash3 benzeri 64-bit karma (mixing) tabanlı hash.
     * Herhangi bir harici bağımlılık gerektirmez.
     * Düşük çarpışma oranı ve iyi bit dağılımı sağlar.
     */
    private static long hash64(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;       // FNV offset basis
        for (byte b : data) {
            h ^= (b & 0xFFL);
            h *= 0x100000001b3L;            // FNV prime
        }
        // Finalization karıştırma (avalanche effect)
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3.  KOVAYA EKLEME  (add / bucketing + register güncelleme)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bir öğeyi HLL yapısına ekler.
     *
     * Adımlar:
     *   1. 64-bit hash üret
     *   2. İlk b bit → kova indeksi (j)
     *   3. Kalan (64-b) bit → ardışık sıfır sayısını say (rho)
     *   4. registers[j] = max(registers[j], rho)
     */
    public void add(String item) {
        long hash = hash64(item);

        // Adım 2: kova indeksi – ilk b bit (en yüksek anlamlı)
        int bucketIndex = (int) (hash >>> (64 - b)) & (m - 1);

        // Adım 3: kalan bitlerdeki ardışık baştaki sıfır sayısı + 1
        // Kalan kısmı sola kaydır, ardından "numberOfLeadingZeros" kullan
        long remaining = (hash << b) | (1L << (b - 1)); // b bitlik maske ekle
        int rho = Long.numberOfLeadingZeros(remaining) + 1;

        // Adım 4: maksimum değeri sakla
        if (rho > registers[bucketIndex]) {
            registers[bucketIndex] = (byte) rho;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.  KARDİNALİTE TAHMİNİ  (harmonik ortalama + düzeltme faktörleri)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tahmini eleman sayısını (kardinalite) döndürür.
     *
     * Formül:
     *   E = α_m * m² * ( Σ 2^(-registers[i]) )^(-1)
     *
     * Düzeltmeler:
     *   - Küçük aralık (Small Range Correction): E ≤ 2.5*m → LinearCounting
     *   - Büyük aralık (Large Range Correction): E > 2^32/30 → log düzeltme
     */
    public long estimate() {
        // Harmonik ortalama
        double harmonicSum = 0.0;
        for (int i = 0; i < m; i++) {
            harmonicSum += Math.pow(2.0, -registers[i]);
        }

        double alphaMM = alpha() * m * m;
        double rawEstimate = alphaMM / harmonicSum;

        // ── Küçük Aralık Düzeltmesi (Linear Counting) ──────────────────────
        if (rawEstimate <= 2.5 * m) {
            long zeroRegisters = countZeroRegisters();
            if (zeroRegisters > 0) {
                // Linear Counting formülü: m * ln(m / V)
                return Math.round(m * Math.log((double) m / zeroRegisters));
            }
        }

        // ── Büyük Aralık Düzeltmesi ─────────────────────────────────────────
        double twoTo32 = Math.pow(2, 32);
        if (rawEstimate > twoTo32 / 30.0) {
            return Math.round(-twoTo32 * Math.log(1.0 - rawEstimate / twoTo32));
        }

        // ── Normal Aralık ───────────────────────────────────────────────────
        return Math.round(rawEstimate);
    }

    /**
     * α_m: kova sayısına göre önyargı düzeltme sabiti.
     * Flajolet ve diğerlerinin (2007) makalesinden alınmıştır.
     */
    private double alpha() {
        switch (m) {
            case 16:  return 0.673;
            case 32:  return 0.697;
            case 64:  return 0.709;
            default:  return 0.7213 / (1.0 + 1.079 / m);
        }
    }

    /** Sıfır değerli register sayısını döndürür. */
    private long countZeroRegisters() {
        long count = 0;
        for (byte r : registers) {
            if (r == 0) count++;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5.  BİRLEŞTİRME  (merge / union)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * İki HLL yapısını veri kaybı olmadan birleştirir.
     *
     * Kural: her kova için maksimum değer alınır.
     *   registers[i] = max(this.registers[i], other.registers[i])
     *
     * Bu işlem, iki farklı veri kümesinin birleşiminin kardinalitesini
     * doğrudan tahmin etmeyi mümkün kılar.
     *
     * @param other  Birleştirilecek diğer HLL yapısı (aynı b parametresine sahip olmalı)
     * @return       Birleştirilmiş yeni HLL nesnesi
     */
    public HyperLogLog merge(HyperLogLog other) {
        if (this.b != other.b) {
            throw new IllegalArgumentException(
                    "Birleştirilecek HLL yapıları aynı b (kova bit) değerine sahip olmalıdır."
            );
        }
        HyperLogLog merged = new HyperLogLog(this.b);
        for (int i = 0; i < m; i++) {
            merged.registers[i] = (byte) Math.max(this.registers[i], other.registers[i]);
        }
        return merged;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6.  YARDIMCI METOTLAR
    // ─────────────────────────────────────────────────────────────────────────

    public int getBucketCount()  { return m; }
    public int getBitWidth()     { return b; }

    /** Teorik standart hata: 1.04 / sqrt(m) */
    public double theoreticalError() {
        return 1.04 / Math.sqrt(m);
    }

    @Override
    public String toString() {
        return String.format(
                "HyperLogLog[b=%d, m=%d, theoreticalError=%.4f (%.2f%%)]",
                b, m, theoreticalError(), theoreticalError() * 100
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7.  TEORİK ANALİZ YARDIMCISI
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Farklı b değerleri için teorik hata tablosu yazdırır.
     * Kova sayısı arttıkça hatanın nasıl azaldığını gösterir.
     */
    public static void printTheoreticalErrorTable() {
        System.out.println("╔══════╦══════════╦══════════════════════╦══════════════════╗");
        System.out.println("║  b   ║  m=2^b   ║  Standart Hata       ║  Bellek (byte)   ║");
        System.out.println("╠══════╬══════════╬══════════════════════╬══════════════════╣");
        for (int bi = 4; bi <= 16; bi++) {
            int mi = 1 << bi;
            double err = 1.04 / Math.sqrt(mi);
            System.out.printf("║  %-3d ║  %-7d ║  %.6f  (%.2f%%)  ║  %-15d  ║%n",
                    bi, mi, err, err * 100, mi);
        }
        System.out.println("╚══════╩══════════╩══════════════════════╩══════════════════╝");
        System.out.println("Formül: StdError = 1.04 / sqrt(m)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8.  DEMO  (main)
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("          HyperLogLog – Cardinality Estimation Demo            ");
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        // ── 8.1  Teorik Hata Tablosu ────────────────────────────────────────
        System.out.println("【1】 TEORİK HATA ANALİZİ: m (kova sayısı) arttıkça hata azalır\n");
        printTheoreticalErrorTable();

        // ── 8.2  Temel Kullanım ─────────────────────────────────────────────
        System.out.println("\n【2】 TEMEL KULLANIM (b=10, m=1024)\n");
        HyperLogLog hll = new HyperLogLog(10);
        System.out.println(hll);

        int[] testSizes = {100, 1_000, 10_000, 100_000, 1_000_000};
        for (int size : testSizes) {
            HyperLogLog h = new HyperLogLog(10);
            Set<String> realSet = new HashSet<>();
            for (int i = 0; i < size; i++) {
                String item = "eleman_" + i;
                h.add(item);
                realSet.add(item);
            }
            long estimated = h.estimate();
            long actual    = realSet.size();
            double error   = Math.abs(estimated - actual) / (double) actual * 100.0;
            System.out.printf("  Gerçek: %7d  |  Tahmin: %7d  |  Hata: %.2f%%%n",
                    actual, estimated, error);
        }

        // ── 8.3  Farklı b Değerleri Karşılaştırması ─────────────────────────
        System.out.println("\n【3】 FARKLI b DEĞERLERİ KARŞILAŞTIRMASI (50.000 eleman)\n");
        int dataSize = 50_000;
        System.out.printf("  %-5s  %-8s  %-10s  %-10s  %-12s  %-12s%n",
                "b", "m", "Gerçek", "Tahmin", "Hata%", "Teorik Hata%");
        System.out.println("  " + "─".repeat(65));

        for (int bi = 4; bi <= 14; bi += 2) {
            HyperLogLog h = new HyperLogLog(bi);
            Set<String> real = new HashSet<>();
            for (int i = 0; i < dataSize; i++) {
                String item = "item_" + i;
                h.add(item);
                real.add(item);
            }
            long est    = h.estimate();
            long actual = real.size();
            double err  = Math.abs(est - actual) / (double) actual * 100.0;
            double theo = h.theoreticalError() * 100.0;
            System.out.printf("  %-5d  %-8d  %-10d  %-10d  %-12.2f  %-12.2f%n",
                    bi, 1 << bi, actual, est, err, theo);
        }

        // ── 8.4  BİRLEŞTİRME (Merge/Union) ──────────────────────────────────
        System.out.println("\n【4】 BİRLEŞTİRME (MERGE) ÖZELLİĞİ\n");

        HyperLogLog hll1 = new HyperLogLog(12);
        HyperLogLog hll2 = new HyperLogLog(12);
        Set<String> union = new HashSet<>();

        // hll1: 0–29999
        for (int i = 0; i < 30_000; i++) {
            hll1.add("veri_" + i);
            union.add("veri_" + i);
        }
        // hll2: 20000–49999  (10000 ortak eleman)
        for (int i = 20_000; i < 50_000; i++) {
            hll2.add("veri_" + i);
            union.add("veri_" + i);
        }

        HyperLogLog merged = hll1.merge(hll2);

        System.out.println("  HLL-1 kardinalite tahmini  : " + hll1.estimate()  + "  (gerçek: 30000)");
        System.out.println("  HLL-2 kardinalite tahmini  : " + hll2.estimate()  + "  (gerçek: 30000)");
        System.out.println("  Gerçek birleşim boyutu     : " + union.size()      + "  (40000 benzersiz eleman)");
        System.out.println("  Birleştirilmiş HLL tahmini : " + merged.estimate());
        double mergeError = Math.abs(merged.estimate() - union.size()) / (double) union.size() * 100.0;
        System.out.printf("  Birleşim hatası            : %.2f%%%n", mergeError);

        // ── 8.5  Küçük/Büyük Aralık Düzeltmeleri ────────────────────────────
        System.out.println("\n【5】 DÜZELTME FAKTÖRLERİ (küçük ve büyük aralıklar)\n");

        HyperLogLog hSmall = new HyperLogLog(12); // m=4096
        int smallCount = 50;
        for (int i = 0; i < smallCount; i++) hSmall.add("kucuk_" + i);
        System.out.printf("  Küçük veri seti (%d eleman): tahmin=%d%n",
                smallCount, hSmall.estimate());

        HyperLogLog hLarge = new HyperLogLog(12);
        int largeCount = 5_000_000;
        Random rng = new Random(42);
        for (int i = 0; i < largeCount; i++) hLarge.add(Long.toString(rng.nextLong()));
        System.out.printf("  Büyük veri seti (~%,d eleman): tahmin=%,d%n",
                largeCount, hLarge.estimate());

        System.out.println("\n═══════════════════════════════════════════════════════════════");
        System.out.println("                        Demo tamamlandı.                       ");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}