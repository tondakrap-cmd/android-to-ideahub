# Tabule Link

Lehká aplikace pro **šifrované zrcadlení obrazovky a zvuku** z **Android tabletu** i z **Windows 10/11 notebooku** na velký Android displej (ověřeno na **Huawei IdeaHub Pro 65**) přes místní síť. Funguje bez cloudu, bez služeb Google a bez proprietárního IdeaShare nebo USB donglu.

> **Verze 0.7.2** (Android): nezhasínání obrazovky během vysílání, výchozí sdílení celé obrazovky, automatické skrytí aplikace po zahájení vysílání a oprava obrazu z Google Pixel. Windows zůstává ve verzi 0.7.0.
>
> **Verze 0.7.0**: nový bezpečnější protokol, přepracované rozhraní na všech zařízeních, více tabulí, pauza, automatické znovupřipojení, rozdělená obrazovka, schvalování převzetí a ovládání notebooku dotykem z tabule. Podrobnosti jsou v [CHANGELOG.md](CHANGELOG.md).

## Hlavní funkce

### Tabule (Android přijímač, Huawei IdeaHub)
- **Přehledná obrazovka čitelná z dálky:** velký název tabule, QR kód, 6místný PIN a tři kroky návodu. Technická nastavení jsou v panelu **⚙ Správa**, který lze zamknout PINem správce.
- **Bezpečné párování:**
  - Každé zařízení dostane **vlastní klíč** a v Správě ho lze jednotlivě **odvolat**.
  - QR kód obsahuje **jednorázový token** a PIN se po každém spárování mění.
  - PIN se ověřuje protokolem **SPAKE2**: neposílá se po síti a podvržená tabule ho nezjistí.
  - Chráněno proti hádání (zámky pro každou adresu zvlášť i celkově).
- **Převzetí obrazovky jedním klepnutím** jako u USB tlačítka. Volitelně **potvrzení převzetí na tabuli** (moderátorský režim).
- **Rozdělená obrazovka:** dva prezentující vedle sebe.
- **Zobrazení jména prezentujícího**, odznak „⏸ pozastaveno“. Ovládací prvky se při prezentaci samy schovají.
- **Trvalý příjem na pozadí** i po startu tabule. Po 10 minutách nečinnosti se obrazovka zavře, služba dál naslouchá a obraz se nevypaluje.
- Prezentace **přežije skrytí obrazovky příjmu**: po návratu obraz okamžitě naváže.
- **Dotykové ovládání sdíleného notebooku** přímo z tabule (musí ho povolit tabule i notebook).

### Tablet (Android vysílač)
- **Moje tabule:** seznam spárovaných tabulí, u každé jedním klepnutím **Sdílet**. Pokud tabule změní IP adresu, aplikace ji sama najde v síti.
- **Přidání tabule:** naskenovat QR, **najít v síti a zadat PIN**, nebo vložit kód.
- **Živá karta** během vysílání: pauza (zmrazení obrazu), ztlumení a ukončení.
- **Automatické znovupřipojení** po krátkém výpadku Wi-Fi (bez nového souhlasu se sdílením).
- **Otočení tabletu za běhu:** obraz se přizpůsobí bez černých okrajů.
- Zvuk aplikací (AAC, Android 10+), 4 režimy kvality, režim nízké latence Wi-Fi.

### Windows notebook (vysílač)
- Responzivní okno se **správným škálováním 125/150 %** (per-monitor DPI).
- **Jeden seznam tabulí:** uložené i nalezené v síti, se stavem online. Nespárovanou tabuli stačí doplnit PINem.
- **Sdílení monitoru nebo jednoho okna.** Monitor se správně určí i u notebooků se dvěma grafickými kartami.
- **Hardwarové kódování** (NVIDIA NVENC, Intel Quick Sync, AMD AMF) s automatickým přechodem na procesor (x264). Klíčový snímek každou sekundu, takže obraz po výpadku nezamrzne na několik sekund.
- **Mini lišta** (Pauza · Ztlumit · Ukončit), kterou záznam obrazovky nezachytí, a **klávesové zkratky**:
  - Ctrl+Alt+S: ukončit
  - Ctrl+Alt+P: pozastavit
  - Ctrl+Alt+M: ztlumit zvuk
- Systémový zvuk (WASAPI Loopback) s hlasitostí pro tabuli a volitelným **ztlumením reproduktorů notebooku**.
- Automatické znovupřipojení a upozornění v oznamovací oblasti při odpojení nebo převzetí.
- Klíče k tabulím jsou uloženy šifrovaně (Windows DPAPI).

## Rychlé použití

### Tabule
1. Nainstalujte APK (viz [Sestavení](#sestavení-a-testy)) a spusťte **Tabule Link**. Při prvním spuštění zvolte **„Tohle je TABULE“**.
2. V **⚙ Správa** pojmenujte tabuli (např. „Zasedačka 2.14“) a zapněte **Trvalý příjem na pozadí**.

### Tablet
1. Při prvním spuštění zvolte **„Tohle zařízení VYSÍLÁ“**.
2. **Naskenujte QR kód** z tabule, nebo **Najít v síti (PIN)**. Příště už stačí klepnout na **Sdílet**.

### Windows notebook
1. Spusťte `TabuleLink-Windows-0.7.0.exe`. Pokud chybí FFmpeg, klikněte v horním pruhu na **Nainstalovat FFmpeg**.
2. Vyberte tabuli v seznamu, zadejte **PIN** z tabule a klikněte na **Spárovat a sdílet**. Příště stačí **⚡ Sdílet**.

## Kompatibilita se staršími verzemi
- Zařízení spárovaná s verzí 0.6 **fungují dál** (sdílený klíč z 0.6 tabule stále přijímá). Odvoláte je tlačítkem **Odvolat všechna zařízení**.
- Nová párování vyžadují verzi 0.7 na obou stranách. Klient 0.7 se k tabuli 0.6 připojí přes QR nebo ruční kód (bez nových funkcí).
- Párování PINem z Windows 0.6 už tabule 0.7 nepřijímá (nebylo odolné proti podvržené tabuli). Aktualizujte notebook na 0.7.

## Struktura projektu
- `project/`: Android aplikace (čistá Java + Android framework, ZXing Core 3.5.3).
  - Protokol v2: `Proto`, `Spake2`, `DeviceRegistry`, `AuthLimiter`, `BoardStore`.
  - Přijímač: `ReceiverService`, `VideoSink`, `ReceiveActivity`.
  - Vysílač: `ShareService`, `ClientLink`, `MainActivity`.
  - Přenosné testy: `tests/` (300 kontrol).
- `windows/`: Windows vysílač (C# 5 / .NET Framework 4.8 WinForms).
  - Protokol: `Protocol.cs`, `Proto2.cs`.
  - Relace: `TabuleSession.cs`.
  - Okna: `MainForm.cs`, `MiniBar.cs`, `Theme.cs`.
  - Nativní vrstva: `Native.cs` (DXGI, SendInput, Core Audio, DPAPI).
  - Testy: `tests/` (123 kontrol, včetně společného testovacího vektoru SPAKE2 s Javou).
- `.github/workflows/ci.yml`: při každém pushi spustí testy a sestaví APK a EXE (stáhnete je v záložce *Actions → Artifacts*).
- `releases/`: starší vydání 0.6.x a záznamy kontrol.

## Sestavení a testy

### Testy (JDK 17 + Python, Windows/Linux/macOS)
```bash
python project/test.py --java-home <cesta-k-JDK17>
```
Windows: `./Test-Windows.ps1` (vestavěný `csc.exe` a `ffmpeg` v PATH).

### APK bez Gradlu (JDK 17 + Android SDK 35 + Python)
```bash
python project/build.py \
  --java-home <cesta-k-JDK17> \
  --build-tools <cesta-k-SDK/build-tools/35.0.0> \
  --android-jar <cesta-k-SDK/platforms/android-35/android.jar> \
  --output releases/Tabule-Link-0.7.2.apk
```
**Podpis vydaných verzí.** Od verze 0.7.0 se všechna vydaná APK podepisují **finálním klíčem** `project/.signing/release/tabule-link-release.p12` (heslo v `password.txt` vedle, alias `tabule-link`, RSA 4096, platnost do 2126). Klíč **není v gitu** – uchovávejte jeho zálohu mimo počítač; bez něj nelze vydat aktualizaci a uživatelé by museli aplikaci odinstalovat. Otisk certifikátu (SHA-256): `96:C0:4A:5A:98:27:17:FB:C7:19:2A:75:02:49:58:49:CA:C3:52:13:D7:32:EF:16:81:25:69:55:A5:FF:DC:17`.

- Pokud finální klíč existuje, `build.py` ho použije automaticky; s `--release` sestavení bez něj selže. Jinou cestu lze zadat proměnnými `TABULE_KEYSTORE` a `TABULE_KEYSTORE_PASS_FILE` (platí i pro Gradle).
- Každá nová verze musí mít **vyšší `versionCode`** (v `AndroidManifest.xml` i `build.gradle`), jinak se nenainstaluje jako aktualizace.
- Bez finálního klíče (např. v CI) se APK podepíše dočasným testovacím klíčem – takové APK nejde nainstalovat přes vydanou verzi.

Alternativně lze složku `project/` otevřít v Android Studiu (JDK 17, `compileSdk 35`, AGP 8.7.3).

### Windows EXE
```powershell
./Build-Windows.ps1
```

## Licence
Autorský kód projektu je pod licencí **MIT** (viz [LICENSE](LICENSE)). Přibalená knihovna **ZXing Core 3.5.3** je pod licencí **Apache 2.0** (viz [project/THIRD-PARTY-NOTICES.txt](project/THIRD-PARTY-NOTICES.txt)).
