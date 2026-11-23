# Tabule Link

Lehká aplikace pro **šifrované zrcadlení obrazovky a zvuku** z **Android tabletu** i z **Windows 10/11 notebooku** na velký Android displej (ověřeno na **Huawei IdeaHub Pro 65**) přes místní síť — bez cloudu, bez služeb Google a bez proprietárního IdeaShare / USB donglu.

## Hlavní funkce (v0.6.0)

- **Android vysílač & přijímač (`releases/Tabule-Link-0.6.0.apk`)**
  - Stejné APK slouží na tabuli jako přijímač i na tabletu jako vysílač.
  - **Více klientů a okamžité převzetí sdílení (styl USB tlačítka):** více tabletů i Windows notebooků může mít uložené připojení k jedné tabuli; kliknutím na *Připojit* další prezentující čistě odpojí předchozího a okamžitě převezme obrazovku i zvuk (až po platném TLS + klíč/PIN ověření).
  - **Trvalý příjem na pozadí a start po zapnutí tabule:** volitelný režim trvalého příjmu (`ReceiverService` + `BootReceiver`) — tabule naslouchá nepřetržitě i po restartu a při připojení prezentujícího automaticky zobrazí obraz na celou obrazovku (volitelně otevře obrazovku QR/PIN rovnou po startu).
  - 4 režimy kvality obrazu (H.264 nízkolatenční profil, 30 fps): `960p · 3 Mb/s`, `1280p · 5 Mb/s`, `1600p · 8 Mb/s`, `1920p · 12 Mb/s`.
  - Přenos interního zvuku aplikací (AAC-LC 48 kHz stereo, Android 10+) s živou regulací hlasitosti na tabletu i na tabuli (nemění systémovou hlasitost videokonference tabule).
  - Přímé **TLS 1.2** spojení s ověřením SHA-256 otisku certifikátu, stálý port `42521`, uložené spojení bez opakovaného skenování QR kódu, **LAN vyhledávání + 6místný PIN** pro snadné spárování Windows notebooku.
- **Windows vysílač (`releases/TabuleLink-Windows-0.6.3.exe`)**
  - Nativní Windows 10/11 aplikace (.NET Framework 4.8, bez instalace Pythonu) ve stejném tmavém designu.
  - Automaticky najde tabuli v LAN → první spárování 6místným PINem (nebo ruční vložení `tabulelink://…`) → další připojení i převzetí tabule jedním kliknutím.
  - Výběr monitoru, 4 režimy kvality (`960p`–`1920p`), rychlé GPU zachytávání plochy (`ddagrab` přes `ffmpeg` s automatickým fallbackem na `gdigrab`).
  - Nativní zachytávání systémového zvuku Windows (**WASAPI Loopback**, bez nutnosti Stereo Mixu) + živý posuvník hlasitosti a ztlumení + minimalizace do oznamovací oblasti (tray).

## Rychlé použití

### Android (tabule i tablet)
1. Nainstalujte [releases/Tabule-Link-0.6.0.apk](releases/Tabule-Link-0.6.0.apk) na tabuli Huawei IdeaHub Pro 65 (a případně aktualizujte tablet).
2. Na tabuli otevřete **Tabule Link** — na levé kartě můžete rovnou zapnout **Trvalý příjem na pozadí (i po startu)** a volitelně **Po startu rovnou otevřít obrazovku QR/PIN**, nebo klepnout na **Přijímat obraz a zvuk**.

### Windows notebook → tabule
1. Spusťte [releases/TabuleLink-Windows-0.6.3.exe](releases/TabuleLink-Windows-0.6.3.exe).
2. Pokud na notebooku chybí `ffmpeg.exe`, klikněte přímo v aplikaci na **Nainstalovat FFmpeg** (automaticky nainstaluje přes `winget`) nebo **Vybrat .exe** (případně položte `ffmpeg.exe` do stejné složky vedle aplikace).
3. Aplikace sama najde tabuli v síti — zadejte **6místný PIN** zobrazený na tabuli a klikněte na **Spárovat a vysílat**.
4. Příště už stačí jediné kliknutí na **⚡ Připojit** (včetně převzetí obrazovky od jiného prezentujícího).

## Struktura projektu

- `project/` — Android aplikace (čistá Java + Android framework, ZXing Core 3.5.3) + 210 přenosných testů (`Test.ps1`).
- `windows/` — Windows vysílač (C# / .NET Framework 4.8 WinForms, WASAPI Loopback, H.264 Annex-B & AAC ADTS parser) + 95 automatických testů (`Test-Windows.ps1`).
- `Build.ps1`, `Test.ps1` — sestavení a testy Android APK.
- `Build-Windows.ps1`, `Test-Windows.ps1` — sestavení a testy Windows `.exe` pomocí vestavěného `csc.exe`.
- `releases/` — aktuální vydání (`Tabule-Link-0.6.0.apk`, `TabuleLink-Windows-0.6.3.exe`, záznam kontrol).

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
