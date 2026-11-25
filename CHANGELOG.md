# Změny

## Připravováno: Windows (zatím nevydáno, Android beze změny 0.7.2)

### Novinky ve Windows aplikaci
- **Vysílat jako nový monitor.** Nová volba v poli „Zdroj obrazu“. Tabule se připojí jako nový virtuální monitor, tedy rozšířená plocha 1920 × 1080 vpravo od monitorů notebooku, ne jako kopie stávajícího monitoru. Okna na ni přetáhnete a obrazovka notebooku zůstane volná.
  - Používá open-source ovladač [Virtual Display Driver](https://github.com/VirtualDrivers/Virtual-Display-Driver) (MIT). Když chybí, aplikace to česky oznámí a nabídne jednorázovou instalaci: winget stáhne podepsaný balíček a Tabule Link z něj ovladač nainstaluje přes `devcon` s oprávněním správce. S oprávněním správce se nic nespouští přímo ze složky, kam může zapisovat běžný uživatel. Soubory se nejdřív zkopírují do složky přístupné jen správcům a ověří se podpisy (`devcon.exe` od Microsoftu, katalog ovladače od SignPath Foundation). Zakázaný ovladač aplikace rozpozná a nabídne Správce zařízení.
  - Ověřeno na Windows 11 se třemi monitory: připojení v 1920 × 1080 vpravo od ostatních monitorů, zachytávání přes ddagrab i gdigrab a odpojení, to vše bez oprávnění správce.
  - Monitor se připojuje a odpojuje přes Windows API SetDisplayConfig, takže vysílání oprávnění správce nepotřebuje. Mimo vysílání je monitor odpojený. Po pádu aplikace se odpojí při jejím příštím spuštění. Monitor, který si uživatel připojil sám, zůstane beze změny.
  - Zachytávání (ddagrab podle názvu monitoru přes DXGI, záložně gdigrab) i dotykové ovládání z tabule míří na souřadnice nového monitoru. Pokud monitor během vysílání někdo odpojí, vysílání skončí se srozumitelnou zprávou.
  - Tabule Link si pamatuje, který monitor připojil, a po vysílání nebo po pádu odpojí jen ten. Další virtuální monitory, které používáte jinde, zůstanou připojené. Když se připojení nepodaří dokončit, monitor se hned zase odpojí.
  - Po výběru nového monitoru aplikace upozorní, že přenos se řídí polem „Kvalita a odezva“ (plné 1920 × 1080 jen při kvalitě Ultra).
  - Instalace ovladače nejde spustit dvakrát současně a balíček ovladače se najde i při jiném pojmenování složek (x64/x86).
- Diagnostika ukazuje u nového monitoru jeho název, rozlišení a polohu na ploše.
- Testy ffmpeg vypisují při selhání chybový výstup ffmpeg. CI se při pushi spouští jen pro `main` a značky `v*` (pull requesty mají vlastní běh), takže se kontroly na PR nespouštějí dvakrát.

### Testy
- Nové testy: umístění nového monitoru vedle stávajících (i se zápornými souřadnicemi), výběr rozlišení, přepočet dotyků na souřadnice nového monitoru a rozpoznání ovladače podle ID hardwaru.

## 0.7.2 (Android versionCode 12 · Windows beze změny 0.7.0)

### Novinky na tabletu / telefonu (vysílající zařízení)
- **Nezhasínat obrazovku**: přepínač v kartě „Právě vysíláte“ vedle tlačítek Pozastavit / Ztlumit / Ukončit (a také v Nastavení přenosu). Zařízení během vysílání neusne ani se nezamkne, i když je v popředí jiná aplikace. Lze přepnout i za běhu. Výchozí stav: zapnuto.
- **Vždy sdílet celou obrazovku**: na Androidu 14 a novějším se rovnou nabídne sdílení celé obrazovky místo jedné aplikace. Po vypnutí volby nabídne Android obě možnosti. Výchozí stav: zapnuto.
- **Po zahájení vysílání skrýt aplikaci**: Tabule Link se sám minimalizuje, jakmile tabule začne přijímat obraz. Když se připojení nepodaří, aplikace zůstane otevřená a ukáže chybu. Výchozí stav: zapnuto.

Obsahuje také všechny opravy z nevydané verze 0.7.1 níže.

## 0.7.1 (Android versionCode 11 · Windows beze změny 0.7.0)

### Opravy
- **Tabule nezobrazila obraz z Google Pixel (Android 17, kodér Exynos) nebo po otočení telefonu spojení skončilo timeoutem.**
  - Dekodér na tabuli je nastavený na adaptivní přehrávání pro obě orientace, takže změna rozlišení při otočení telefonu už nevyžaduje jeho zastavení a nové spuštění. Na HiSilicon (Android 9) se zastavení mohlo zaseknout, tabule pak přestala číst data a telefon skončil na timeout.
  - Když dekodér dostává klíčové snímky, ale 2,5 s nic nevykreslí, tabule ho sama vytvoří znovu v jednodušším režimu (nízká latence HiSilicon → základní → výchozí).
  - Uvolnění dekodéru běží v pomocném vlákně s časovým limitem, takže zaseknutý dekodér nikdy nezablokuje příjem dat.
- **Telefon:** rozměry obrazovky po otočení se čtou přímo z displeje, protože metriky okna služby se na Androidu 12+ mohou zpožďovat. Řádek „Fáze“ v diagnostice po připojení ukazuje „vysílání“.

### Diagnostika
- Tabule i telefon v diagnostice ukazují **posledních 14 událostí** (připojení, otočení, změny rozlišení, nové nastavení dekodéru, pauza, přerušení spojení) a telefon i počet žádostí tabule o klíčový snímek.

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
- Ověření a výměna QR tokenu i PINu probíhají pod jedním zámkem: ani dvě souběžná spojení se stejným tokenem nebo PINem nespárují dvě zařízení.
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
