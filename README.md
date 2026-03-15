# Tugas Besar 1 IF2211 - Strategi Algoritma
Pemanfaatan Algoritma Greedy dalam Pembuatan Bot Permainan Battlecode 2025.

## 🤖 Penjelasan Singkat Algoritma Greedy

Proyek ini mendemonstrasikan tiga pendekatan heuristik *Greedy* yang berbeda untuk memecahkan persoalan optimasi pada Battlecode 2025:

### 1. Bot (utama `main_bots`) - Greedy berdasarkan Pewarnaan Peta
Bot utama kami berfokus pada **dominasi cakupan wilayah (*coverage*)** 
* **Heuristik:** Menggunakan algoritma penyebaran pasukan berbasis kuadran agar unit tidak menumpuk. Bot secara rakus mengecat petak netral/musuh yang dipijaknya setiap giliran dan menggunakan prediksi simetri peta untuk menekan langsung ke garis depan musuh.
* **Kelebihan:** Sangat cepat dan stabil dalam mencapai *objective* utama permainan (>70% area)

### 2. Bot Alternatif 1 (`alternative_bots_1`) - Greedy Berdasarkan Sumber Daya
Bot ini berfokus pada **infrastruktur dan sumber daya** 
* **Heuristik:** `Soldier` akan mencari dan mengklaim *ruin* terdekat secepat mungkin. `Tower` mengimplementasikan *Dynamic Spawning* yang memprioritaskan unit ekonomi di awal permainan, sebelum beralih mencetak unit serang (`Splasher`) saat infrastruktur sudah matang. Dilengkapi dengan komunikasi 16-bit untuk berbagi koordinat *ruin*.
* **Kelebihan:** Pengumpulan sumber daya (*chips*) sangat masif di pertengahan hingga akhir permainan

### 3. Bot Alternatif Bot 2 (`alternative_bots_2`) - Greedy berdasarkan Daya Hancur
Bot ini berfokus pada **agresi dan daya hancur lawan**
* **Heuristik:** `Mopper` bertindak sebagai ujung tombak predator yang memburu bot musuh dan memprioritaskan area yang menghasilkan curian cat tertinggi. `Tower` memprioritaskan *spawn* unit `Mopper` & `Splasher` di petak yang paling dekat dengan markas musuh.
* **Kelebihan:** Sangat mematikan di fase awal permainan dan mampu membuat sumber daya musuh jatuh

## 💻 Requirement Program
Untuk melakukan kompilasi dan menjalankan bot ini, lingkungan pengembangan Anda memerlukan:
1. **Java Development Kit (JDK)**: Versi 17 atau yang lebih baru. Pastikan *environment variable* `JAVA_HOME` sudah terkonfigurasi
3. **Battlecode 2025 Client**: Untuk memutar *replay* dan menjalankan visualisasi pertandingan

## 🛠️ Langkah-langkah Build dan Kompilasi

Proyek ini menggunakan *Gradle wrapper* bawaan sehingga Anda tidak perlu menginstal Gradle secara manual. Buka terminal pada *root directory* proyek ini dan jalankan perintah berikut:

**Untuk Pengguna Windows:**
```bash
# Membersihkan build sebelumnya dan melakukan kompilasi ulang
gradlew clean build
```

**Untuk Pengguna Mac / Linux:**
```bash
# Memberikan akses eksekusi pada wrapper (jika belum)
chmod +x gradlew

# Membersihkan build sebelumnya dan melakukan kompilasi ulang
./gradlew clean build
```

Jika kompilasi berhasil, pesan **BUILD SUCCESSFUL** akan muncul. Bot siap dijalankan dan dipertandingkan melalui aplikasi Battlecode Client.


## 👥 Identitas Pembuat (Tim rumahsti)
| Nama | NIM |
| :--- | :--- |
| Fudhail Fayyadh | 18222121 |
| Harfhan Ikhtiar Ahmad Ridzky | 18222123 |
| Muhammad Dzaky Atha Fadhilah | 18222124 |