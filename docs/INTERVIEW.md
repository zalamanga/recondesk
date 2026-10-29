# Catatan wawancara

## Penjelasan singkat

ReconDesk membandingkan dua CSV transaksi berdasarkan reference. Operator melihat hasil pencocokan, menyelidiki pengecualian, dan mengajukan penjelasan. Reviewer yang berbeda menyetujui atau menolak penjelasan itu. Keputusan mengubah status investigasi, bukan data transaksi dan bukan pergerakan uang.

Proyek ini menggunakan Java 17, Spring Boot 4.1.2, Spring JDBC, PostgreSQL 17, Flyway, React, dan TypeScript. Data contoh dan akun pengguna bersifat sintetis. Saya akan menyebutnya aplikasi latihan lokal, bukan sistem bank yang telah dipakai pelanggan.

## Alur demo yang dapat dijelaskan

1. Login sebagai operator dengan kredensial lokal yang dibuat saat inisialisasi.
2. Import `samples/internal.csv` dan `samples/partner.csv`. Masing-masing berisi 11 baris data. Hasilnya 10 kasus: 2 cocok dan 8 pengecualian terbuka.
3. Buka `DEMO-DIFF-AMOUNT`. Tunjukkan bahwa nilai dari kedua sumber tetap terlihat, lalu tulis penjelasan yang spesifik.
4. Login sebagai reviewer dan ambil keputusan dengan catatan. Persetujuan menghasilkan `RESOLVED`; penolakan mengembalikan kasus ke `OPEN`.
5. Tunjukkan riwayat proposal, audit, dan export. Jelaskan bahwa classification tetap menjelaskan hasil perbandingan awal.

Untuk demo kegagalan, ganti salah satu sumber dengan `samples/invalid/excess-precision.csv`. Import harus ditolak seluruhnya. Jangan mengklaim import berhasil bila UI baru selesai membaca file; keberhasilan berasal dari respons server setelah transaksi selesai.

## Mengapa reference menjadi kunci pencocokan?

Nominal yang sama dapat muncul pada transaksi berbeda. Sistem mengelompokkan reference terlebih dahulu, lalu membandingkan amount, date, dan currency. Reference yang berulang pada salah satu sumber menjadi `DUPLICATE`; sistem menyimpan seluruh baris dan tidak memilih baris yang kebetulan cocok.

Pendekatan ini sengaja terbatas. Sistem belum mengenali relasi one-to-many, split settlement, biaya, toleransi nominal, perbedaan zona waktu, atau konversi valuta. Menambahkan aturan itu perlu contoh domain, prioritas aturan, dan cara menjelaskan hasil kepada investigator.

## Mengapa BigDecimal dan string untuk uang?

Bilangan pecahan biner seperti `double` tidak selalu merepresentasikan nilai desimal persis. Backend memvalidasi desimal dan menggunakan `BigDecimal`; database menggunakan `numeric` dengan skala dua. JSON mengirim uang sebagai string dua desimal agar frontend tidak mengubahnya menjadi perhitungan floating point.

Tidak ada konversi IDR ke USD. Currency yang berbeda menjadi pengecualian dengan delta null. Nilai ringkasan duplicate juga null karena tidak ada satu pasangan baris yang sah untuk dihitung. Untuk kasus satu sisi, delta memakai nol pada sisi yang hilang dan classification tetap menjelaskan konteksnya.

## Mengapa Spring JDBC, bukan ORM?

Query untuk filter, pagination, agregasi jumlah, dan row lock menjadi bagian yang ingin diperlihatkan dalam proyek ini. Spring JDBC membuat SQL dan batas transaksi terlihat langsung. Pilihan ini berarti developer bertanggung jawab atas pemetaan hasil, query, dan migrasi skema. ORM juga dapat dipakai, tetapi bukan kebutuhan untuk model kecil ini.

Flyway memisahkan perubahan skema dari request aplikasi. Constraint database mendukung aturan service, misalnya satu proposal pending per kasus dan reviewer yang berbeda dari proposer.

## Bagaimana mencegah klik ganda dan request bersamaan?

Import memakai UUID `Idempotency-Key` per aktor. Server membandingkan hash nama run dan kedua byte stream. Retry identik mengembalikan run yang sudah commit. Kunci yang sama dengan payload berbeda ditolak. Frontend mempertahankan identitas import ketika respons belum jelas.

Proposal dan keputusan mengirim `expectedVersion`. Service mengunci baris kasus, memeriksa versi dan status, lalu memperbarui kasus, proposal, dan audit dalam satu transaksi. Request yang sudah kedaluwarsa mendapat `409`. Menonaktifkan tombol ketika request berlangsung membantu UX, tetapi server tetap menjadi penjaga aturan.

## Mengapa ada operator dan reviewer?

Tujuannya memisahkan orang yang mengajukan penjelasan dan yang mengesahkan. Server memeriksa role dan identitas aktor. Menyembunyikan tombol saja tidak cukup karena klien dapat mengirim request sendiri. Kedua role boleh membaca seluruh workspace dalam versi lokal ini; sistem belum menyediakan isolasi organisasi atau cakupan akses per run.

## Bagaimana session dan CSRF ditangani?

Browser menyimpan session opaque melalui cookie HttpOnly dan SameSite=Lax. Database menyimpan hash token. Request perubahan memerlukan origin yang tepat serta token CSRF dari session. Logout mencabut session saat ini. Login mempunyai pembatasan percobaan.

Jawaban ini tidak berarti sistem memiliki MFA, SSO, atau sertifikasi keamanan. Pengujian lokal dan kontrol aplikasi memiliki lingkup yang terbatas. Audit tidak memiliki endpoint edit atau hapus, tetapi administrator database masih memiliki kemampuan mengubah penyimpanan.

## Mengapa tidak memakai Spring Batch?

Import saat ini dibatasi 1 MiB dan 1.000 baris per sumber, lalu dijalankan sinkron dan atomik. Untuk lingkup itu, alur request lebih mudah dibaca dan hasil langsung tersedia setelah commit.

Jika file membesar, saya akan memisahkan upload durable, job record, worker, checkpoint, retry, dan publikasi hasil. Saya perlu menentukan apakah validasi seluruh file harus selesai sebelum penyimpanan final, bagaimana job yang gagal dilanjutkan, serta bagaimana idempotency bekerja lintas worker. Menjalankan method dalam thread background saja belum menyelesaikan masalah tersebut.

## Apa yang diuji dan apa buktinya?

Lihat [VERIFICATION.md](VERIFICATION.md) untuk perintah dan hasil yang benar-benar dijalankan. Jelaskan kategori yang relevan: validasi CSV, pencocokan, transaksi import, role dan CSRF, retry idempotency, konflik versi, serta UI dengan browser. Jangan menghafal jumlah test atau angka throughput yang tidak tercatat pada versi repository yang sedang didemokan.

Skenario penting adalah dua request import dengan key sama menghasilkan satu run, keputusan bersamaan tidak menghasilkan keputusan ganda, dan CSV invalid tidak meninggalkan sebagian data. Bukti sebuah skenario harus berasal dari test atau observasi yang tercatat, bukan dari keberadaan method bernama mirip.

## Bagaimana menjelaskan case study SQL?

[Case study SQL](benchmarks/README.md) memakai 250.000 row sintetis dalam tabel dan database sementara, terpisah dari skema aplikasi. Query melakukan agregasi harian lalu window function untuk running total. Script membandingkan hasil lengkap sebelum dan setelah penambahan index `(account_id, transaction_date) INCLUDE (amount)`; hasilnya harus sama.

Plan yang tersimpan berubah dari sequential scan menjadi bitmap index/heap scan. Saya akan menjelaskan predicate, jumlah row yang dibaca, dan buffer counter sebelum membahas timing. Ini satu pasangan pengukuran lokal, dengan cache dan beban mesin yang dapat berbeda, bukan janji speedup umum atau bukti kapasitas import API. Batas import aplikasi tetap 1.000 data row per sumber.

## Batasan dan pekerjaan berikutnya

Prioritas berikutnya bergantung pada kebutuhan pemakai. Untuk pemakaian tim sungguhan, kebutuhan awal meliputi autentikasi organisasi, pembatasan akses, backup dan restore yang diuji, kebijakan retensi, deployment HTTPS, serta observability. Untuk data besar, kebutuhan awal adalah job durable dan batas sumber daya. Untuk rekonsiliasi yang lebih kompleks, kebutuhan awal adalah aturan domain dan contoh kasus yang dapat diverifikasi.

Saya tidak akan menyebut proyek ini production-ready hanya karena build lulus atau tampilan berfungsi. Saya dapat menjelaskan lingkup yang sudah dibuat, menunjukkan bukti yang ada, dan menyebutkan keputusan yang masih terbuka.
