Aurora Launcher 1.0.1 – förhandsversion

Den här versionen öppnar Microsofts inloggning direkt i webbläsaren och tar
emot svaret automatiskt. Ingen engångskod behöver kopieras. Minecraft-licensen
kontrolleras innan kontot visas som inloggat.

1. Installera Java 17 eller senare om det saknas: https://adoptium.net/
2. Starta AuroraLauncher-1.0.1.jar eller Starta Aurora.bat.
3. Utgivaren måste först registrera en egen Microsoft-app, ange http://localhost
   som returadress för skrivbordsappen och konfigurera dess offentliga app-ID i
   Aurora. Den här utgåvan innehåller inget förregistrerat app-ID. Läs
   MICROSOFT_LOGIN.md. Registreringen görs en gång, inte av varje spelare.
4. Klicka Logga in med Microsoft, logga in i webbläsaren och vänta på kontrollen.
5. Välj Installera & spela. Java 25 för spelet hämtas vid behov.

Paketet installerar Minecraft Java 26.3, Fabric 0.20.1 och Aurora-modden 1.10.0.
Använd en separat instans. Äldre identifierade Aurora-moddar säkerhetskopieras
vid installation. Befintliga inställningar kan återanvändas; befintlig
appregistrering behöver returadressen http://localhost för det nya flödet.

594 lokala kontroller passerade med simulerade Microsoft/Xbox/Minecraft-svar.
Riktig inloggning och spelstart genom launchern är inte sluttestade utan app-ID.
Windows/macOS har inte körts här. Sessionsfilen med refresh token är
behörighetsbegränsad, inte krypterad. Skriv inget lösenord eller client secret
inne i Aurora; lösenordet används bara på Microsofts egen sida.

Guider och källkod: https://github.com/powerebraceit/Minecraft-Client/tree/main/launcher
Poppins-typsnittets licens finns i OFL-Poppins.txt och inne i JAR-filen.
