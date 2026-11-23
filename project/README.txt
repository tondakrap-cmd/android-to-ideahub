TABULE LINK 0.6.0 — WINDOWS KLIENT, PŘEVZETÍ SDÍLENÍ A TRVALÝ PŘÍJEM
====================================================================

Účel
Zrcadlení obrazovky a zvuku Android tabletu i Windows 10/11 notebooku
na Huawei IdeaHub Pro 65 (a další Android displeje) přes místní síť.
Nevyžaduje cloud, služby Google ani proprietární IdeaShare.

Stav
Verze 0.6.0 rozšiřuje ověřenou verzi 0.4.0/0.4.1 o Windows vysílač
(TabuleLink-Windows-0.6.0.exe), LAN vyhledávání + 6místný PIN pro
notebooky, střídání více spárovaných klientů s okamžitým převzetím
obrazovky a režim trvalého příjmu na pozadí včetně spuštění po startu
tabule. Android APK prošlo 210 a Windows aplikace 95 automatickými testy.

Změny v 0.6.0 (oproti 0.4.1)
- Více klientů a okamžité převzetí sdílení (ReceiverService):
  - Nepřetržitá accept smyčka během běžící prezentace.
  - Nový ověřený klient (128bitový klíč nebo 6místný PIN) plynule odpojí
    předchozího prezentujícího a ihned převezme obraz i zvuk tabule.
- Trvalý příjem a automatické spuštění po startu tabule (BootReceiver):
  - Foreground služba ReceiverService (specialUse) drží porty 42521/42522
    trvale na pozadí a při připojení prezentujícího sama otevře obrazovku.
  - Volitelné automatické otevření QR/PIN obrazovky po startu tabule.
- LAN vyhledávání + 6místný PIN (DiscoveryProtocol):
  - UDP discovery na portu 42522 (bez vysílání tajného klíče/PINu) a
    velký 6místný PIN na tabuli s ochranou proti hádání pro snadné
    první spárování Windows notebooku.
- Windows vysílač (složka windows/ -> TabuleLink-Windows-0.6.0.exe):
  - Nativní Windows 10/11 WinForms x64 aplikace (.NET Framework 4.8),
    GPU ddagrab / gdigrab přes ffmpeg, nativní WASAPI Loopback zvuk,
    4 režimy kvality (960p–1920p), živý posuvník hlasitosti a tray režim.

POUŽITÍ
1. Na tabuli (a tablet) nainstalujte Tabule-Link-0.6.0.apk.
2. Na Windows notebooku spusťte TabuleLink-Windows-0.6.0.exe.
2. Připojte tablet a tabuli ke stejné místní síti. Tabule může být
   připojena kabelem a tablet Wi-Fi, pokud tato síť dovoluje jejich
   vzájemné spojení. Samotný internet není potřebný.
3. Na tabuli spusťte Tabule Link a zvolte „Přijímat obraz“.
   Nechte tuto obrazovku otevřenou. Zobrazí se QR kód.
4. Na tabletu nastavte orientaci na šířku. Spusťte Tabule Link,
   zvolte „Vysílat obraz“ a povolte fotoaparát pro načtení QR kódu.
5. Načtěte QR kód z tabule. V systémovém dialogu potvrďte sdílení
   obrazovky. Pro zobrazení všech aplikací zvolte celou obrazovku,
   pokud Android nabízí i možnost sdílet jen jednu aplikaci.
6. Na tabletu přejděte na plochu nebo do aplikace, kterou chcete
   ukazovat. Na tabuli se zobrazí obraz tabletu.
7. Vysílání ukončíte tlačítkem v aplikaci, akcí „Ukončit“ v oznámení
   nebo systémovým ovladačem sdílení obrazovky. Příjem ukončíte
   tlačítkem „Zavřít příjem“ na tabuli.

Omezení první verze
- Pouze obraz. Bez zvuku, ovládání tabletu z tabule a další plochy.
- Delší strana obrazu nejvýše 1280 bodů, cílově 25 snímků/s,
  datový tok přibližně 2,5 Mb/s. Skutečná plynulost závisí na síti
  a hardwarovém kodéru a dekodéru obou zařízení.
- Android 8.0 / API 26 a novější. Nevyžaduje služby Google.
- Příjem musí zůstat otevřený na tabuli. Přechodem na jinou aplikaci
  nebo uzamčením tabule se příjem ukončí.
- Orientaci nastavte před spuštěním. Po otočení může vzniknout
  prázdný okraj; pro nejlepší obraz ukončete a spusťte sdílení znovu.
- Chráněná videa a zabezpečené aplikace mohou zobrazovat černý obraz.
- Používá IPv4. Nepodporuje připojení přes internet nebo automatické
  vyjednání spojení mezi oddělenými sítěmi.

KDYŽ SPOJENÍ NEFUNGUJE
- Ověřte, že na tabuli zůstává otevřený režim příjmu.
- Wi-Fi pro hosty často blokuje komunikaci mezi zařízeními. Použijte
  síť, která komunikaci dovoluje, případně požádejte správce o povolení
  přímého TCP spojení z tabletu na adresu a port v párovacím kódu.
  Aplikace používá při každém otevření příjmu nově přidělený port.
- Pokud má tabule více síťových adres, klepněte na adresu pod QR kódem
  a vyberte adresu sítě dostupné z tabletu. Načtěte aktualizovaný QR kód.
- Při změně sítě zavřete příjem a znovu jej otevřete.
- Pokud nejde načíst QR kód, přibližte tablet nebo změňte vzdálenost.
  Případně na tabuli zobrazte a zkopírujte ruční párovací kód a vložte
  jej na tabletu do „Vložit párovací kód ručně“.
- Po přerušení spojení spusťte vysílání znovu a načtěte QR kód.
- Při chybě na tabletu zvolte „Zkopírovat diagnostiku“. Pro ladění
  pomůže přesný text chyby na tabuli, verze systému a informace,
  zda byl obraz černý, zaseknutý, nebo se připojení vůbec nevytvořilo.

Oprávnění a soukromí
- Fotoaparát se používá pouze při čtení QR kódu na vysílajícím zařízení.
- Android si samostatně vyžádá souhlas se sdílením obrazovky.
- Aplikace nepotřebuje mikrofon, přístupnost ani správu zařízení.
- Přenos probíhá přímo přes TLS 1.2. Otisk certifikátu tabule a náhodný
  128bitový přístupový klíč se předávají v QR kódu. Klíč se obnoví při
  novém otevření příjmu. QR kód ani ruční párovací kód veřejně nesdílejte.
- Aplikace obraz nezapisuje do souborů, neposílá ho do cloudu a nemá
  reklamy ani analytiku. Při sdílení celé obrazovky se mohou zobrazit
  oznámení; podle potřeby zapněte režim Nerušit.
- Aplikace má vlastní testovací podpis. Nejde o aplikaci vydanou
  výrobcem Huawei nebo Samsung ani o aplikaci z Google Play.

ZDROJOVÝ PROJEKT A SESTAVENÍ
Projekt používá Java a Android framework bez AndroidX a služeb Google.
Jediná přibalená knihovna je ZXing Core 3.5.3 pro QR kódy (Apache 2.0).

Lze otevřít jako Gradle projekt v Android Studiu (JDK 17, Android SDK 35,
Android Gradle Plugin 8.7.3, kompatibilní Gradle 8.9). Poté sestavit APK.
Projekt neobsahuje Gradle wrapper ani privátní podpisový klíč dodaného APK.
Nově podepsaná sestava s jiným klíčem vyžaduje odinstalaci původní aplikace.

Alternativně sestavit bez Gradlu pomocí Pythonu a oficiálních SDK nástrojů:
python build.py --java-home <JDK17> --build-tools <SDK/build-tools/35.0.0> --android-jar <SDK/platforms/android-35/android.jar> --output <výsledné.apk>
Skript pro Windows vygeneruje a uchová nový soukromý podpisový klíč
v adresáři .signing. Ten se nemá publikovat. Pro aktualizace jej zachovejte.

Přenosové a QR testy:
python test.py --java-home <JDK17>

Protokol
TLS 1.2, certifikát přijímače ověřený otiskem SHA-256 z QR kódu.
První zpráva klienta: DataOutputStream.writeUTF(přístupový klíč).
Potvrzení přijímače: int 0x54424c31.
Hlavička videa: int 0x54424c31, int šířka, int výška.
Pakety: int velikost, int příznaky MediaCodec, long čas v mikrosekundách,
poté velikost bytů H.264. Konfigurační data kodeku používají příznak 2.
Udržovací paket má velikost 0, příznaky 0 a čas 0.
Příjemce omezuje velikost paketu na 2 MiB a ověřuje rozlišení před
spuštěním dekodéru. Přijímá vždy jednoho vysílatele.

Copyright 2026. Autorský kód tohoto projektu je k dispozici pod licencí MIT.
Viz LICENSE.txt a THIRD-PARTY-NOTICES.txt.
