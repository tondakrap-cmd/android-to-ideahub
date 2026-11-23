# Změny

## 0.7.0 (Android versionCode 10 · Windows 0.7.0)

### Opravy chyb
- **Windows: zamrzání obrazu až na 5 s po zahlcení sítě.** Klíčový snímek se nově posílá každou 1 s (dříve 5 s). Fronta po zahození snímků čeká nejvýše 1 s.
- **Windows: rozpadlé okno při škálování 125/150 %.** Okno používá per-monitor DPI (V2), responzivní rozvržení (`TableLayoutPanel`) a `AutoScaleMode.Dpi`. Souřadnice gdigrab i dotyků jsou ve fyzických pixelech.
- **Tabule: souběh při startu služby** (boot + automatické otevření obrazovky) vytvářel dva servery a náhodný port. Všechny požadavky na start, zastavení i reset nyní běží na jednom řídicím vlákně. Port 42521 se při obsazení opakovaně zkouší a nepřechází se potichu na náhodný.
- **Windows: vyhledávání tabulí končilo po 250 ms ticha.** Nově čeká celý časový limit a připojuje se na adresu, ze které tabule skutečně odpověděla (tabule s Ethernetem, Wi-Fi i hotspotem).
- **Uložené spojení přestalo fungovat po změně IP tabule.** Tabule se identifikuje otiskem certifikátu a při nedostupnosti se sama dohledá v síti.
- **Tablet ukládal tabuli ještě před úspěšným spojením** a přepsal tím dobré uložené spojení. Nově se ukládá až po ověření.
- **Záložní TLS certifikát platil jen 1 rok** a po roce by se změnil otisk tabule. Nově platí 30 let a platnost se nekontroluje (klienti ověřují otisk). AndroidKeyStore se po startu několikrát zkouší, než se použije záloha.
- **Windows: ddagrab mohl zachytit jiný monitor** (pořadí monitorů ≠ pořadí výstupů DXGI). Monitor se nově mapuje podle názvu zařízení přes DXGI, včetně správného GPU u notebooků se dvěma grafickými kartami.
- **Prezentace končila, když se na tabuli otevřela jiná aplikace.** Spojení nyní zůstává; po návratu se dekodér obnoví a tabule si vyžádá klíčový snímek.
- **Výpadky zvuku na tabuli** při zaseknutém dekodéru videa. Dekodér nyní nikdy neblokuje čtení ze sítě déle než přibližně 60 ms.
- **Mírné zkreslení poměru stran:** rozměry se zaokrouhlují na nejbližší násobek 16, ne dolů (1366×768 → 1280×720 místo 1280×704).
- Drobnosti: text notifikace podle režimu, únik GDI ikony na Windows, nadpis chybových dialogů, `test.py` a `build.py` fungují i na Linuxu a macOS.

### Bezpečnost (protokol v2)
- **Klíč pro každé zařízení**: tabule ukládá jen jeho hash a v Správě lze zařízení jednotlivě odvolat.
- **QR kód obsahuje jednorázový token**, ne trvalý klíč. **PIN se mění** po každém spárování a po opakovaných chybných pokusech.
- **PIN přes SPAKE2** (RFC 3526, 2048 bit): PIN se neposílá a ověření zahrnuje otisk certifikátu tabule, takže podvržená tabule v síti dostane nejvýše jeden pokus a nemůže párování přeposlat.
- **Ochrana proti hádání**: exponenciální zámek pro každou adresu plus globální limit, takže útočník nezablokuje ostatní.
- **Moderátorský režim**: převzetí obrazovky musí potvrdit někdo u tabule.
- **PIN správce** zamyká panel Správa.
- Windows ukládá klíče šifrovaně (DPAPI).
- Starý protokol 0.6 (sdílený klíč) zůstává kvůli kompatibilitě, dokud ho nezrušíte tlačítkem „Odvolat všechna zařízení“. Nešifrované zadání PINu (0.5/0.6) bylo odstraněno.

### Nové funkce
- **Volba role při prvním spuštění** (tabule / vysílač). Tabule otevírá rovnou obrazovku příjmu.
- **Nové rozhraní tabule**: velký název, QR a PIN čitelné z dálky, panel Správa, automatické skrývání ovládání, jméno prezentujícího, odznak pauzy a zavření obrazovky po 10 min nečinnosti.
- **Více tabulí** na tabletu i notebooku (přejmenovat, odebrat), seznam s online stavem na Windows.
- **Vyhledání v síti + PIN i na Android tabletu.**
- **Pauza / zmrazení obrazu** (tablet, notebook, mini lišta, Ctrl+Alt+P).
- **Automatické znovupřipojení** po krátkém výpadku sítě (30 s), bez přetahování s jiným prezentujícím.
- **Rozdělená obrazovka**: dva prezentující vedle sebe; zvuk hraje od posledního.
- **Ovládání notebooku dotykem z tabule** (zpětný kanál, `SendInput`). Musí ho povolit tabule i notebook.
- **Otočení tabletu za běhu**: kodér se přenastaví a tabule změní rozměr obrazu.
- **Windows:** sdílení jednoho okna, hardwarové kodéry NVENC/QSV/AMF s automatickým přechodem na procesor, automatický restart zachytávání (UAC, změna rozlišení), mini lišta neviditelná pro záznam, globální klávesové zkratky, upozornění v oznamovací oblasti, ztlumení reproduktorů notebooku a jméno notebooku zobrazené na tabuli.
- **Wi-Fi v režimu nízké latence** na tabletu i tabuli během přenosu.
- **CI (GitHub Actions)**: testy a sestavení APK i EXE při každém pushi.

### Omezení
- Dotykové ovládání funguje jen pro notebook s Windows. Android tablet by vyžadoval službu přístupnosti.
- Ztlumení reproduktorů notebooku: u některých ovladačů zvuku může ztlumit i zvuk posílaný na tabuli. V takovém případě volbu vypněte.
- Zařízení 0.6 nepodporují pauzu, znovupřipojení, otočení ani zpětný kanál.
