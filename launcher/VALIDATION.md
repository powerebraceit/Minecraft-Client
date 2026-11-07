# Verifiering av Aurora Launcher 1.0.0

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
| Auth och session | 50 | Simulerad Microsoft/Xbox/Minecraft-kedja, licens, fel, refresh och fillagring |
| Minecraft/Fabric | 22 | Installation från testmetadata, hash, regler, natives, argument och avbrott |
| Java-process | 55 | Riktiga ofarliga JVM-processer, argument, privata filer och städning |
| Swing-gränssnitt | 79 | Riktiga klick, navigation, sparvärden, upptagetläge, dialog och storlekar |
| Produktionens startpunkt | 28 | Riktig controller, app-ID-spärr, sparade inställningar och stängning |
| **Totalt** | **340** | |

Auth-testet använder simulerade svar. UI-testet använder en inaktiv testcontroller;
testet av startpunkten använder produktionscontrollern med tomt app-ID och
verifierar att inga kontoanrop, nedladdningar eller spelprocesser startas.
Processkontrollerna startar en Java-klass som återger sina argument, aldrig spelet.
Testklasserna ingår inte i den distribuerade JAR-filen.

## Officiella metadata

En separat kontroll gjorde 16 riktiga HTTPS-anrop med produktens Java-transport
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
