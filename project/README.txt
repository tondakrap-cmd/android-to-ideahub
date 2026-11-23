TABULE LINK 0.7.0 — ANDROID PROJEKT
===================================

Uživatelský popis, instalace a sestavení: viz ../README.md, změny: ../CHANGELOG.md.

Zdrojový projekt
- Čistá Java + Android framework (bez AndroidX a služeb Google), minSdk 26, targetSdk 35.
- Jediná přibalená knihovna: ZXing Core 3.5.3 (Apache 2.0) pro QR kódy.
- Gradle: otevřete složku v Android Studiu (JDK 17, Android SDK 35, AGP 8.7.3).
- Bez Gradlu: python build.py --java-home <JDK17> --build-tools <SDK/build-tools/35.0.0>
  --android-jar <SDK/platforms/android-35/android.jar> --output <výsledné.apk>
  Skript vytvoří soukromý podpisový klíč v .signing (nepublikovat, pro aktualizace zachovat).
- Testy: python test.py --java-home <JDK17>

Protokol v2 (podrobně v src/cz/ideamirror/app/Proto.java)
- TLS 1.2, certifikát tabule ověřený otiskem SHA-256 (QR kód / LAN vyhledávání).
- Úvodní zpráva klienta: writeUTF("TL2|METODA|idZařízení|údaj|jméno|schopnosti")
  KEY   = klíč zařízení (128 bit, vydaný tabulí, tabule ukládá jen hash)
  TOKEN = jednorázový token z QR kódu (tabule po použití vygeneruje nový)
  PAKE  = SPAKE2 s 6místným PINem (RFC 3526 2048 bit); potvrzení zahrnuje otisk certifikátu.
- Odpověď tabule: int 0x54424c33 + writeUTF("OK|id|klíč|název tabule|schopnosti") / "ERR|kód|zpráva".
- Hlavička videa: int 0x54424c31, šířka, výška. Tabule odpoví 0x54424c35 (čekám na schválení)*,
  pak 0x54424c34 (start) nebo 0x54424c36 + chybová zpráva.
- Pakety: int velikost, int příznaky, long čas v µs, data. Příznaky: 1 klíčový snímek, 2 konfigurace
  kodeku, 16 zvuk (AAC-LC), 32 řídicí příkaz (UTF-8: pause, resume, bye, fmt:Š:V, kf,
  end:takeover|closed|revoked, t:d|m|u:x:y). Udržovací paket má velikost 0.
- Starý klient 0.4–0.6: writeUTF(32hex sdílený klíč) → int 0x54424c31 (stále podporováno).
- LAN vyhledávání: UDP 42522, "TABULELINK_DISCOVER_V1" → "TABULELINK_OFFER_V1\tnázev\tport\totisk\tIP"
  (nikdy neobsahuje klíč, token ani PIN).

Oprávnění a soukromí
- Fotoaparát jen pro čtení QR kódu. Mikrofon (RECORD_AUDIO) jen pro zachytávání zvuku aplikací
  přes MediaProjection (Android 10+). Žádná analytika, reklamy ani cloud.

Copyright 2026. Autorský kód je pod licencí MIT, viz LICENSE.txt a THIRD-PARTY-NOTICES.txt.
