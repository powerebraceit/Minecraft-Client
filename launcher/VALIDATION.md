# Verifiering av Aurora Launcher 1.0.1

Den distribuerade JAR-filen byggdes med JDK 25 och Java 17 som kompilatormål.
Följande kommando slutfördes med exitkod 0 på Linux under Xvfb:

```bash
JAVA_HOME=/workspace/.tools/jdk-25.0.5.0.3 DISPLAY=:100 bash launcher/check.sh --gui
```

| Kontroll | Antal | Vad som verifierades |
| --- | ---: | --- |
| JSON | 52 | Unicode, siffror, felaktig input, djup och serialisering |
| Inställningar | 20 | Isolerade standardmappar, sparning, laddning och felaktiga filer |
| Aurora-installation | 9 | Medföljande mod, checksumma, backup, rollback och övriga moddar |
| Java-runtime | 25 | Versioner, verifierad installation, avbrott och rollback med testmanifest |
| Auth och session | 132 | Simulerad Microsoft/Xbox/Minecraft-kedja, kodutbyte med PKCE, licens, fel, refresh och fillagring |
| Webbläsarinloggning | 82 | Riktig lokal HTTP-callback, state, PKCE, ogiltiga svar, avbrott, timeout och stängning |
| Minecraft/Fabric | 22 | Installation från testmetadata, hash, regler, natives, argument och avbrott |
| Java-process | 55 | Riktiga ofarliga JVM-processer, argument, privata filer och städning |
| Swing-gränssnitt | 102 | Riktiga klick, navigation, sparvärden, upptagetläge, webbläsar-/koddialog och storlekar |
| Produktionens startpunkt | 28 | Riktig controller, app-ID-spärr, sparade inställningar och stängning |
| Produktionens webbläsarflöde | 67 | Riktig controller och lokal callback med simulerade kontosvar, session, utloggning och nekad licens |
| **Totalt** | **594** | |

Auth-testet använder simulerade svar. UI-testet använder en inaktiv testcontroller;
testet av startpunkten använder produktionscontrollern med tomt app-ID och
verifierar att inga kontoanrop, nedladdningar eller spelprocesser startas.
Processkontrollerna startar en Java-klass som återger sina argument, aldrig spelet.
Testklasserna ingår inte i den distribuerade JAR-filen.

Webbläsarflödets separata integrationstest använder den riktiga controllern,
Swing-knapparna, HTTP-callbacken och sessionsfilen. En testfunktion ersätter
webbläsaren och skickar ett lokalt svar; alla Microsoft-, Xbox- och Minecraft-svar
är simulerade. Det verifierar automatisk inloggning efter godkända testkontroller,
nekad inloggning utan Java-licens och utloggning som bevarar övriga filer.
Dess kontoskärmbild märks uttryckligen som simulerad. Det är inte en riktig
Microsoft-inloggning eller ett test med ett registrerat Aurora-app-ID.

## Tidigare kontroll av officiella metadata

Installationsdelen kontrollerades också under launcher 1.0.0. En separat
kontroll gjorde 16 riktiga HTTPS-anrop med produktens Java-transport
och miljöns proxy-/CA-inställningar enbart som JVM-flaggor. Inga proxyadresser
hårdkodades i produkten. Den verifierade Minecraft 26.3:s manifest och checksumma,
alla sju Fabric-biblioteks SHA-256-adresser samt fem Mojang Java 25-manifest
för Linux, Windows och macOS med totalt 2 265 filer. Ingen spel- eller
Java-runtime-binär hämtades i detta moment.

Minecraft-installerns kontroll kördes också med faktiska 26.3-/Fabric-manifest,
vilket gav ytterligare sex kontroller av plattformsregler och kommandon på
Windows, macOS och Linux utöver dess 22 testmetadata-kontroller.

JAR-filens startmanifest verifierades med `java -jar … --version`.
Den medföljande Aurora-modden har samma SHA-256 som den tidigare utgivna 1.10.0.
Arkivet innehåller inga Corz-klasser, qProtect-komponenter, ursprungliga
native-bibliotek eller testklasser.

## Kvar att verifiera

Utgivaren behöver ett registrerat och godkänt Microsoft-app-ID. **Riktig
kontoinloggning, licensverifiering mot ett användarkonto och spelstart genom denna
nya launcher har inte testats.** Simulerade svar garanterar inte API-åtkomst.
Windows-/macOS-körning, deras fönstermiljöer och Windows ACL-grenen har inte
verifierats på respektive operativsystem. En fullständig första nedladdning
av spel/resurser/Java har inte körts genom launchern här.

Aurora-modden har egna tidigare speltester, beskrivna i projektets README.
Det är separat testning och ska inte tolkas som en färdigtestad ny launcher.
