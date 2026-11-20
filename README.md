# Tabule Link

Lehká Android aplikace pro **šifrované zrcadlení obrazovky a zvuku** z Android tabletu (např. Samsung Galaxy Tab) na velký Android displej (ověřeno na **Huawei IdeaHub Pro 65**) přes místní síť — bez cloudu, bez služeb Google a bez proprietárního IdeaShare.

Stejný instalační soubor (`.apk`) slouží jako **vysílač** (na tabletu) i **přijímač** (na tabuli).

## Hlavní funkce (v0.4.1)

- **Nízkolatenční přenos obrazu (H.264 / MediaCodec)**
  - 4 volitelné režimy kvality:
    - `Rychlá odezva` — 960p · 3 Mb/s · 30 fps
    - `Vyvážený` *(výchozí)* — 1280p · 5 Mb/s · 30 fps
    - `Vysoká ostrost` — 1600p · 8 Mb/s · 30 fps
    - `Ultra ostrost` — 1920p · 12 Mb/s · 30 fps (1920 × 1200 na 16:10 tabletu)
  - Ochrana proti zahlcení na 120Hz tabletech (`KEY_MAX_FPS_TO_ENCODER`), nízkolatenční fronta s adaptivním bitrate (`FrameQueue`), přeskakování zastaralých snímků na přijímači (*drain-to-latest*) a podpora nízkolatenčních příznaků dekodéru (včetně Huawei HiSilicon).
- **Přenos interního zvuku aplikací (Android 10+)**
  - Zachytávání zvuku aplikací přes `AudioPlaybackCaptureConfiguration` (YouTube, videa, prezentace — bez snímání mikrofonu), kódování **AAC-LC 48 kHz stereo (128 kb/s)** a přehrávání přes nízkolatenční `AudioTrack`.
  - **Nezávislá regulace hlasitosti** na tabletu (v reálném čase i během přehrávání, s tlačítkem *Ztlumit*) i na tabuli — nemění systémovou hlasitost tabule nastavenou pro videokonference.
- **Zabezpečené spojení a rychlé obnovení bez QR kódu**
  - Přímé šifrované **TLS 1.2** spojení v LAN s ověřením SHA-256 otisku certifikátu tabule a 128bitovým přístupovým klíčem.
  - První spárování načtením QR kódu (nebo vložením ručního kódu). Tabule používá výchozí stálý port `42521` a trvalý klíč (lze kdykoli obnovit tlačítkem *Nový klíč*), takže příště se tablet připojí jedním klepnutím bez skenování QR kódu.
- **Přehled síťových rozhraní a diagnostika**
  - Zobrazení všech IPv4 rozhraní s maskou podsítě (`/24`) na tabletu i na tabuli s prioritizací aktivní Wi-Fi/Ethernet sítě před hotspotem/Wi-Fi Direct.
  - Rychlé ovládání okna (*Uklidit do pozadí* bez přerušení vysílání, *Ukončit aplikaci*) a vestavěná diagnostika pro ladění.

## Rychlé použití

1. Nainstalujte [releases/Tabule-Link-0.4.1.apk](releases/Tabule-Link-0.4.1.apk) na tabuli i na tablet (Android 8.0 / API 26 a novější; přenos zvuku vyžaduje na tabletu Android 10+).
2. Připojte obě zařízení ke stejné místní síti (Wi-Fi nebo Ethernet v témže segmentu).
3. Na tabuli otevřete **Tabule Link → Přijímat obraz a zvuk**. Zkontrolujte zobrazenou IP adresu (případně přepněte síťové rozhraní).
4. Na tabletu otevřete **Tabule Link → Načíst QR kód**, načtěte QR kód z tabule a potvrďte sdílení celé obrazovky.
5. Při dalším připojení už na tabletu stačí klepnout na uloženou kartu **⚡ Připojit**.

## Struktura projektu

- `project/` — zdrojový kód Android aplikace (čistá Java + Android framework, bez AndroidX; jediná externí knihovna je přibalený `ZXing Core 3.5.3`).
  - `src/cz/ideamirror/app/` — implementace aplikace (`MainActivity`, `ReceiveActivity`, `ScanActivity`, `ShareService`, `AudioPlayer`, `Wire`, `TlsSupport`, `Packet`, `FrameQueue`, `NetUtil`, `PairCode`, `AudioUtil`, `Ui`).
  - `tests/cz/ideamirror/app/CoreTest.java` — přenosné automatické testy (202 kontrol: párování, TLS 1.2 handshake v paměti, framing, `FrameQueue`, IPv4 podsítě, `AudioUtil`, QR roundtrip).
  - `build.py`, `test.py` — sestavení a testy bez Gradlu pomocí JDK 17 a Android SDK Build-Tools.
  - `build.gradle`, `settings.gradle` — konfigurace pro otevření v Android Studiu.
- `Build.ps1`, `Test.ps1` — PowerShell spouštěče pro Windows (při použití lokální složky `toolchain/`).
- `releases/` — aktuální sestavené testovací APK (`Tabule-Link-0.4.1.apk`) a přehled kontrol.

## Sestavení a testy

### Přenosné testy (JDK 17 + Python)
```bash
python project/test.py --java-home <cesta-k-JDK17>
```

### Sestavení APK bez Gradlu (JDK 17 + Android SDK API 35 + Python)
```bash
python project/build.py \
  --java-home <cesta-k-JDK17> \
  --build-tools <cesta-k-SDK/build-tools/35.0.0> \
  --android-jar <cesta-k-SDK/platforms/android-35/android.jar> \
  --output releases/Tabule-Link.apk
```
Skript při prvním spuštění vygeneruje vlastní lokální podpisový klíč v `project/.signing/` (který je vyloučen v `.gitignore` a nepublikuje se).

Alternativně lze složku `project/` otevřít přímo v Android Studiu (JDK 17, `compileSdk 35`, AGP 8.7.3).

## Licence

Autorský kód projektu je publikován pod licencí **MIT** (viz [LICENSE](LICENSE) a [project/LICENSE.txt](project/LICENSE.txt)).
Přibalená knihovna **ZXing Core 3.5.3** je pod licencí **Apache 2.0** (viz [project/THIRD-PARTY-NOTICES.txt](project/THIRD-PARTY-NOTICES.txt)).
