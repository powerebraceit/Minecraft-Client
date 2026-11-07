# Microsoft-inloggning i Aurora Launcher

Du behöver ett Microsoft-konto som äger **Minecraft Java Edition** för att spela.
Launchern behöver dessutom ett **Application (client) ID** som identifierar
Aurora-programmet hos Microsoft. Det är en offentlig identifierare i GUID-format,
exempelvis `12345678-1234-1234-1234-123456789abc`. Exemplet är ingen fungerande app.

**Det är launcherns utgivare som registrerar appen en gång. Vanliga spelare ska
sedan kunna logga in med sina egna Minecraft-konton genom samma app-ID.** Ett
Minecraft-konto innebär inte att man redan har registrerat en launcher-app.

Den här versionen innehåller ett konfigurerbart inloggningsflöde men levereras
utan ett registrerat och godkänt Aurora-app-ID. Utgivaren behöver först registrera
och konfigurera ett giltigt ID. Ett konto som äger spelet räcker därför inte för
att använda den ännu okonfigurerade inloggningen.

## För utgivaren: registrera Aurora

1. Följ Microsofts [guide för appregistrering](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app).
   Du behöver tillgång till en Microsoft Entra-miljö där ditt konto har rätt att
   registrera appar. Microsofts guide beskriver aktuella krav på Azure-konto,
   prenumeration, katalog och roll. Ett privat Minecraft-konto ger inte automatiskt
   dessa utvecklarbehörigheter.
2. Öppna **App registrations → New registration** i Microsoft Entra och registrera
   en app för Aurora Launcher. Välj en kontotyp som tillåter **personliga
   Microsoft-konton**. Alternativet för enbart personliga konton fungerar för
   detta ändamål; alternativet med både organisationskonton och personliga konton
   kan också användas. Aurora använder Microsofts `consumers`-slutpunkt.
3. Kopiera **Application (client) ID** från appens översikt. Det är detta offentliga
   GUID du ska ange i Auroras **Inställningar → Microsoft app-ID**.
4. Under **Authentication → Advanced settings** aktiverar du **Allow public client
   flows → Yes** och sparar. Följ Microsofts aktuella
   [konfiguration för skrivbordsappar](https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-app-configuration).
   Device-code-flödet behöver ingen redirect-URI. **Skapa eller distribuera ingen
   client secret** för den här offentliga skrivbordsappen.
5. Kontrollera att appen får använda de tjänster den behöver. Aurora begär
   `XboxLive.signin offline_access` och använder därefter Xbox Live, XSTS och
   Minecraft Services. En registrering eller en allmän Microsoft Graph-behörighet
   innebär inte automatiskt tillgång till dessa Minecraft-tjänster. Microsofts
   aktuella villkor kan kräva separat åtkomstgodkännande för launcher-appen.
   Om Microsoft-inloggningen lyckas men Minecraft/Xbox nekar appen, kontrollera
   appens behörigheter och aktuell väg för utvecklaråtkomst via
   [Minecrafts officiella support](https://help.minecraft.net/).
6. Testa den registrerade appen med ett konto som äger Minecraft Java. När utgivaren
   distribuerar en förkonfigurerad version ska spelare använda det offentliga
   Aurora-app-ID:t och sina egna konton; de ska inte behöva skapa varsin app.

Ett app-ID från en annan launcher ska inte kopieras som ersättning för Auroras
egen registrering.

## Så loggar spelaren in när appen är konfigurerad

1. Klicka **Logga in med Microsoft** i Aurora.
2. Öppna den Microsoft-adress som visas och ange den korta engångskoden.
3. Logga in och godkänn begäran på **Microsofts webbsida**. Ange aldrig ditt
   Microsoft-lösenord eller någon client secret i launcherns inställningar.
4. Aurora kontrollerar Minecraft-licensen och Java-profilen. När profilnamnet visas
   kan du starta spelet. Om koden går ut eller inloggningen avbryts börjar du om
   med en ny kod; gamla koder återanvänds inte.

Microsoft beskriver protokollet i
[dokumentationen för OAuth device-code-flödet](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code).

## Sparad session och utloggning

Aurora sparar Microsofts **refresh token** i en separat fil i användarens
`.aurora-launcher`-mapp. Den används för att återställa sessionen utan att fråga
om lösenord. Minecrafts access token hålls i minnet under körningen.

Sessionsfilen skrivs atomiskt. På POSIX-system används filrättigheterna `0600`;
på Windows begränsas filens ACL till filägaren när filsystemet stöder det.
**Detta är en behörighetsbegränsad fil, inte kryptering och inte en integration
med operativsystemets lösenordsvalv.** Dela inte sessionsfilen och lägg den inte
i GitHub, ett felmeddelande eller en skärmbild.

**Logga ut** tar bort den sparade sessionen. Det tar inte bort Minecraft-kontot
eller spelets licens och är inte samma sak som att återkalla appens behörighet
hos Microsoft. En ogiltig eller återkallad refresh token ska leda till en ny
Microsoft-inloggning, utan upprepade automatiska försök.

## Verifiering i den här miljön

Inloggningskedjan och sessionslagringen har testats med deterministiska,
simulerade Microsoft-, Xbox- och Minecraft-svar. Inga riktiga lösenord eller
kontotokens användes i testerna. **En riktig Microsoft-inloggning och spelstart
med användarkonto har inte kunnat sluttestas utan ett registrerat Aurora-app-ID.**
Att protokolltesterna passerar bevisar inte att Microsoft har godkänt appen.
