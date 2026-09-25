# Belge Tezgâhı — BT Kurulum ve Yönetim Rehberi

Bu klasör, programı şirket bilgisayarlarına dağıtmak ve merkezi olarak yönetmek için gerekenleri içerir.

| Dosya | Ne işe yarar |
|---|---|
| `Install-DocumentWorkbench.ps1` | Sessiz kurulum + merkezi politikayı kurma + politika klasörünü kilitleme |
| `policy.example.properties` | Tüm politika ayarlarının açıklamalı örneği |

## 1. Kurulum dosyasını üretmek (geliştirici bilgisayarında, bir kez)

```
gradlew packageInstaller      → build\jpackage\DocumentWorkbench-1.0.0.msi   (WiX Toolset 3.x gerekir)
gradlew packageApp            → build\jpackage\DocumentWorkbench\             (kurulumsuz, taşınabilir klasör)
```

Her iki çıktı da kendi Java'sını içerir; bilgisayarlara ayrıca Java kurulmaz. Bellek ayarı (512 MB) ve diğer
çalışma ayarları başlatıcıya gömülüdür, kullanıcı yanlış ayarla başlatamaz. İki başlatıcı vardır:

* `DocumentWorkbench.exe` — kullanıcı arayüzü
* `dwb-cli.exe` — konsol düğümü (ağ paylaşımı) ve BT araçları (`--hash-passphrase`)

**Gereksinim:** 64-bit Windows 10 veya 11. (Windows 7/8.1 ve 32-bit Windows desteklenmez.)

## 2. Sessiz kurulum

Yönetici olarak açılmış PowerShell'de:

```powershell
# Hazır bir politika dosyasıyla:
.\Install-DocumentWorkbench.ps1 -Installer .\DocumentWorkbench-1.0.0.msi -Policy .\policy.properties

# Ya da politikayı parametrelerle oluşturarak:
.\Install-DocumentWorkbench.ps1 -Installer .\DocumentWorkbench-1.0.0.msi `
    -DisableAI -DisableWeb -ReportFolder '\\sunucu\it\dwb-saglik' `
    -AdminPassphraseHash 'pbkdf2$120000$...'
```

Betik: `.msi`'yi diyalogsuz kurar (günlük: `%TEMP%\DocumentWorkbench-install.log`), politikayı
`%ProgramData%\DocumentWorkbench\policy.properties` konumuna yazar ve klasörü kilitler
(Administrators/SYSTEM: tam, Users: yalnız okuma). Intune, SCCM, PDQ veya GPO başlangıç betiğiyle dağıtılabilir;
başarıda çıkış kodu 0'dır.

Diğer parametreler: `-DisableLan`, `-RequireLanSecret`, `-SupportFolder`, `-MemoryCeiling 0.80`, `-Workers 1`,
`-PolicyVersion`, `-SkipInstall` (yalnız politikayı günceller).

Kaldırma: Ayarlar → Uygulamalar, ya da `msiexec /x DocumentWorkbench-1.0.0.msi /qn`.
Kullanıcı verileri (`%LOCALAPPDATA%\DocumentWorkbench`: projeler, indeksler) kaldırmada silinmez.

## 3. Merkezi politika

* Politikada yazan her ayar **kullanıcı tarafından değiştirilemez**; Yönetim Panelinde 🔒 ile gösterilir,
  terminal komutları reddeder ve reddetme denetim kaydına yazılır.
* Yazılmayan ayarlar eskisi gibi kullanıcıya kalır. Politika dosyası yoksa program politikasız çalışır.
* Değişiklikler programın bir sonraki açılışında geçerli olur.
* Tüm anahtarlar: `policy.example.properties`.

**Yönetici parolası oluşturma** (parolanın kendisi hiçbir yere yazılmaz):

```
"C:\Program Files\DocumentWorkbench\dwb-cli.exe" --hash-passphrase
```

Çıkan `admin.passphrase.hash=...` satırını politikaya ekleyin.

**Önemli:** Politika yalnızca dosya izinleri kadar güçlüdür. Users grubunun yazma izni varsa program bunu
Yönetim Panelinde ve denetim kaydında uyarı olarak gösterir. Kurulum betiği izinleri doğru ayarlar.

## 4. Yönetim Paneli

Programda **⋯ → Yönetim ve Tanılama…** menüsü:

| Sekme | İçerik |
|---|---|
| Genel | Sürüm, bilgisayar, politika durumu, bellek, yapay zekâ durumu, yönetici oturumu (kilit aç/kilitle), GC |
| Politika | Geçerli tüm ayarlar ve kaynakları (🔒 merkezi), hariç tutmalar, bellek tavanı, işçi sayısı |
| Sağlık | Sağlık denetimi bulguları, ölçümler, indeks dosyasının sağlamlığı |
| Denetim Kaydı | Son kayıtlar, süzme, tarih aralığıyla dışa aktarma (yönetici oturumu gerekir) |
| Destek | Destek paketi oluşturma, paylaşılan klasöre sağlık raporu |

Panel, her değişikliği terminal komutu olarak çalıştırır; böylece yetki denetimi ve kayıt tek yerde yapılır.

## 5. Destek paketi

Kullanıcı sorun bildirdiğinde: **Yönetim Paneli → Destek → Destek Paketi Oluştur** (veya terminalde
`support-bundle`). Tek bir zip dosyası üretir: sürüm, bilgisayar, bellek, politika, indeks sağlığı, indeks dosyası
kontrolü, proje listesi ve son 500 denetim kaydı.

**İçermez:** belge içeriği, arama metinleri (denetim kaydındaki sorgular `<redacted>` yapılır), dosya içerikleri.
Denetim kayıtlarında dosya yolları bulunur (erişim sorunlarını teşhis için gereklidir).

Varsayılan konum: politikadaki `support.folder`, yoksa kullanıcının masaüstü.

## 6. Paylaşılan klasöre sağlık raporu (isteğe bağlı)

Politikada `report.folder` tanımlanırsa her bilgisayar belirtilen aralıkla
`<bilgisayar>-<kullanıcı>.txt` dosyasını yazar/günceller: sürüm, bellek, belge sayısı, sağlık bulgu kodları,
politika sürümü, AI kotası. Belge adı veya içeriği yoktur. Klasöre Users grubunun **yazma** izni olmalıdır.
Bilgisayar ağ dışındaysa rapor atlanır, bir sonraki seferde yazılır. Sunucu gerekmez.

Terminalde: `health-report` (durum), `health-report --now` (hemen yaz).

## 7. Ağ paylaşımı: Sıfır Güven modu (varsayılan)

Her bilgisayar ilk açılışta kendi **Ed25519** anahtar çiftini üretir. Özel anahtar diske açık yazılmaz: işletim
sisteminin makine kimliğinden (Windows `MachineGuid`, Linux `/etc/machine-id`, macOS `IOPlatformUUID`) türetilen
anahtarla AES-GCM ile mühürlenip `device.key` dosyasında tutulur; dosya başka bilgisayara kopyalanırsa açılmaz (klonlanmış
diskte yeni kimlik üretilir ve cihaz yeniden eşleştirilmelidir). Cihazın ağdaki kimliği açık anahtarının SHA-256 parmak
izidir.

* **Kör/sağır başlangıç:** Eşleştirilmemiş bir cihaz bağlanamaz (el sıkışmada soket açıklamasız kapanır), duyuruları
  dikkate alınmaz, katalog görmez. Katalog hiçbir zaman multicast ile yayılmaz; yalnızca kimliği doğrulanmış cihaza,
  o cihazın görebileceği kadarıyla TCP üzerinden verilir.
* **Eşleştirme (PIN):** Yetkili bilgisayarda `lan-pair --new` (misafir için `--guest`, departman için `--dept FINANCE`)
  6 haneli, 5 dakika geçerli, **tek denemelik** bir PIN üretir. Yeni bilgisayarda `lan-pair <adres>:<port> <PIN>`.
  İki ekranda çıkan **doğrulama kodu** aynı olmalıdır; farklıysa araya giren vardır: `lan-devices --remove <cihaz>`.
* **Roller:** `FULL_PEER` (katalog + çekme + gönderme) ve `RESTRICTED_GUEST` (katalog yok, gönderemez; yalnızca
  kendisine bilerek gönderilen ya da `lan-acl <belge> --grant <cihaz>` ile tek seferlik izin verilen dosyayı alır; izin
  ilk doğrulanmış aktarımla düşer). Değiştirmek: `lan-devices --role <cihaz> RESTRICTED_GUEST`, `lan-devices --dept <cihaz> HR`.
* **Belge erişim listesi:** `lan-acl <belge> --grant dept:HR` ya da `--grant <cihaz>`; listesi olan belgeyi yalnızca
  listedekiler görür ve çekebilir, diğerlerinin isteği soket düzeyinde reddedilir.
* **Yarım kalan aktarım:** Devam ettirmeden önce eldeki parçanın son 64 KB bloğunun SHA-256 özeti karşı tarafla
  karşılaştırılır; uyuşmazsa aktarım baştan başlar. Tamamlanan dosya her durumda SHA-256 ile doğrulanır.
* **Eski mod:** Tüm bilgisayarlarda `DWB_TRUST=legacy` (ya da politikada `lan.trustMode=legacy`) eski protokole
  döner (açık mod veya `DWB_SECRET`). İki mod birbiriyle konuşmaz; geçişi tüm bilgisayarlarda birlikte yapın.
* **Şifreli tünel:** El sıkışmada her oturum için tek kullanımlık X25519 anahtarları (cihaz imzasıyla bağlı) değiş
  tokuş edilir; HKDF-SHA256 ile yön başına bir AES-256 anahtarı türetilir. El sıkışmadan sonraki her bayt (istek,
  dosya adı, hash, dosya içeriği) 64 KB'lık AES-GCM çerçeveleriyle gider; çerçeve sayacı tekrar/yer değiştirme
  saldırılarını engeller. Ağı dinleyen biri yalnızca cihaz açık anahtarlarını ve aktarım boyutunu görebilir.
  Eski mod (`DWB_TRUST=legacy`) şifrelenmez.

**Arayüzden:** Yönetim Paneli → **Cihazlar** sekmesi: “➕ Yeni Cihaz Eşleştir” (tek tıkla PIN, geri sayım, karşı
cihaz bağlanınca doğrulama kodu), “Bir Cihaza Katıl…”, güvenilen cihaz tablosu (rol ve departman doğrudan
değiştirilir, “Güvenden Çıkar” tek tık) ve belge erişim listeleri. Durum çubuğundaki rozet modu gösterir
(“🔒 Zero-Trust aktif [v3 şifreli]” / “Legacy Mod”); tıklanınca bu sekme açılır. Biten her aktarım durum çubuğunda
kilitle (şifreli) ya da uyarıyla (legacy, şifresiz) birkaç saniye gösterilir.

**Yönetici kilidi:** “Yeni Cihaz Eşleştir” (`lan-pair --new`) ve “Güvenden Çıkar” (`lan-devices --remove`) yönetici
oturumu ister; kilit kapalıysa buton 🔒 ile görünür ve tıklanınca yönetici parolası sorulur. Reddedilen denemeler
denetim kaydına `SECURITY` olarak yazılır. Yönetici parolası (`admin.passphrase.hash`) ya da dağıtım anahtarı
tanımlı değilse kilit de yoktur; kurumsal kurulumda parolayı merkezi politikada tanımlayın. İstisna: doğrulama
kodları farklı çıktığında “Kodlar farklı — Güvenden çıkar” son 5 dakikada eşleşmiş cihazı kilitsiz geri alır
(araya giren birini hemen kesmek için); kurulu cihazları kaldırmak her zaman yönetici ister.

Konsol düğümünde (`dwb-cli`) aynı işlemler: `devices`, `pair --new`, `pair <eş|adres:port> <PIN>`, `acl`.

## 8. Sık kullanılan BT komutları (terminal: Ctrl+J)

```
admin status                 erişim durumu + geçerli politika özeti
admin unlock <parola>        yönetici oturumu (kayda '***' olarak geçer)
audit --health               sağlık denetimi
audit --export --since 2026-09-01    uyum raporu için denetim kaydı dışa aktarımı
blacklist --list             geçerli hariç tutmalar
stale / ocr-needed           değişmiş kaynaklar / taranmış PDF'ler
index --verify               indeks dosyasının sağlamlığı
support-bundle               destek paketi
lan                          ağ paylaşımı durumu, mod ve cihaz parmak izi
lan-devices [--pending]      güvenilen cihazlar / ağda görülen eşleştirilmemiş cihazlar
lan-pair --new [--guest]     tek kullanımlık eşleştirme PIN'i (5 dakika)
lan-acl <belge> --grant <cihaz|dept:AD>   belge bazında erişim listesi
```
