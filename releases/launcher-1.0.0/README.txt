Aurora Launcher 1.0.0 – förhandsversion

1. Installera Java 17 eller senare om det saknas: https://adoptium.net/
2. Starta AuroraLauncher-1.0.0.jar eller Starta Aurora.bat.
3. Läs MICROSOFT_LOGIN.md. Utgivaren behöver registrera och konfigurera ett
   eget offentligt Microsoft Application (client) ID innan inloggningen fungerar.
   Ditt vanliga Minecraft/Microsoft-konto är inte ett app-ID. Ingen client secret
   eller något lösenord ska skrivas in i launchern.
4. När appen har godkänts: logga in med ditt Minecraft-konto och välj
   Installera & spela. Java 25 för spelet hämtas vid behov.

Paketet installerar Minecraft Java 26.3, Fabric 0.20.1 och Aurora-modden 1.10.0.
Standardinstansen är separat från .minecraft. Tidigare identifierade Aurora-
moddar säkerhetskopieras i mods/.aurora-backups vid installation.

340 lokala kontroller passerade. Riktig kontoinloggning och spelstart genom
launchern är ännu inte verifierade utan app-ID. Windows/macOS har inte körts här.
Sessionsfilen med refresh token är behörighetsbegränsad, inte krypterad.

Källkod och guider: https://github.com/powerebraceit/Minecraft-Client/tree/main/launcher
Verifiering: https://github.com/powerebraceit/Minecraft-Client/blob/main/launcher/VALIDATION.md
Poppins-typsnittets licens finns i OFL-Poppins.txt och inne i JAR-filen.
