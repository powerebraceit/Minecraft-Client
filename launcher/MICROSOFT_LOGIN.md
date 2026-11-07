# Microsoft-inloggning i Aurora Launcher 1.0.1

Aurora öppnar Microsoft-inloggningen i din vanliga webbläsare och tar emot svaret
lokalt i launchern. Du behöver inte kopiera någon engångskod. Flödet använder
**OAuth authorization code med PKCE** för en offentlig skrivbordsapp.

För att spela behöver du ett Microsoft-konto som äger **Minecraft Java Edition**.
Launchern behöver dessutom ett registrerat **Application (client) ID** som
identifierar Aurora hos Microsoft. Ett Minecraft-konto innehåller inte detta
app-ID. ID:t är offentligt och är varken ett lösenord eller en client secret.

**Det är launcherns utgivare som registrerar appen en gång. Spelarna loggar sedan
in med sina egna Minecraft-konton genom samma Aurora-app.** Den här versionen
levereras utan ett registrerat Aurora-app-ID; utgivaren måste konfigurera ett
giltigt ID innan riktig inloggning fungerar. Ett exempel-ID från dokumentation
eller en annan launchers ID ska inte användas som ersättning.

## För utgivaren: skaffa åtkomst till en egen katalog

Microsofts [guide för appregistrering](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app)
kräver ett Azure-konto med aktiv prenumeration och minst rollen **Application
Developer**, eller motsvarande rätt att registrera appar i en Microsoft
Entra-katalog. Ett privat Microsoft- eller Minecraft-konto ger inte automatiskt
den åtkomsten. Om du redan har en katalog, välj den i portalens katalogväxlare
innan du öppnar **App registrations**. Den kan exempelvis heta **Default
Directory**, men namnet kan vara ett annat.

Om Entra-portalen visar **AADSTS50020** och katalogen **Microsoft Services** vid
inloggning med ditt privata konto, beskriver Microsoft detta som att kontot saknar
en kopplad katalog för administration. Microsofts
[officiella felsökning, orsak 1](https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/app-integration/error-code-AADSTS50020-user-account-identity-provider-does-not-exist#cause-1users-log-in-to-microsoft-entra-admin-center-by-using-personal-microsoft-accounts)
anger följande väg:

1. Öppna [Azure-konto](https://azure.microsoft.com/en-us/free/) och välj att skapa
   ett kostnadsfritt konto om du är berättigad. Följ Microsofts registrering och
   aktuella villkor; telefon-, identitets- eller betalningskortsverifiering kan
   krävas. Granska villkoren innan du slutför registreringen.
2. En egen Entra-katalog skapas tillsammans med Azure-kontot enligt Microsofts
   guide. Välj den katalogen i portalen, i stället för **Microsoft Services**.
3. Öppna därefter **App registrations** och registrera Aurora enligt nästa avsnitt.
   Har du en befintlig organisationskatalog men saknar behörighet, behöver dess
   administratör ge dig rätt åtkomst eller registrera appen.

Detta är inte samma sak som att skapa ytterligare kataloger via **Manage tenants**.
Microsofts [aktuella regler för att skapa en ny tenant](https://learn.microsoft.com/en-us/entra/fundamentals/create-new-tenant)
begränsar nya Workforce-tenants där till betalkunder; gratis- och provkunder kan
sakna rätt att skapa fler. Ett köpt Minecraft-konto upphäver inte de reglerna.
Registrera inte ett nytt konto eller en ny prenumeration om du redan har en lämplig
katalog med rätt behörighet.

## För utgivaren: registrera Aurora för webbläsarinloggning

1. Öppna **Microsoft Entra → App registrations → New registration** i din egen
   katalog. Välj **Personal Microsoft accounts only**, eller alternativet som
   även tillåter organisationskonton och personliga Microsoft-konton. Enbart
   organisationskonton passar inte vanliga privata Minecraft-konton. Aurora
   använder Microsofts `consumers`-slutpunkt för personliga konton.
2. Under **Authentication → Add a platform** väljer du **Mobile and desktop
   applications**. Lägg till exakt **`http://localhost`** som redirect-URI och
   spara. Välj skrivbordsplattformen; det här flödet använder ingen webbserver
   med client secret och ingen SPA-registrering. Microsofts
   [konfiguration för skrivbordsappar](https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-app-configuration)
   anger `http://localhost` för systemets webbläsare.
3. Under **Authentication → Advanced settings** anger du **Allow public client
   flows → Yes** och sparar. **Skapa eller distribuera ingen client secret.**
   En skrivbordsapp kan inte hålla en sådan hemlighet säker.
4. Kopiera **Application (client) ID** från appens översikt till Auroras
   **Inställningar → Microsoft app-ID** och spara. Detta är det offentliga app-ID:t,
   inte katalogens tenant-ID, ett Object ID, kontots e-postadress eller ett lösenord.
5. Kontrollera att appen får använda de tjänster den behöver. Aurora begär
   `XboxLive.signin offline_access` och använder därefter Xbox Live, XSTS och
   Minecraft Services. En registrering eller en allmän Microsoft Graph-behörighet
   innebär inte automatiskt tillgång till Minecraft-tjänsterna. Om Microsoft-
   inloggningen lyckas men Xbox eller Minecraft nekar appen, kontrollera appens
   aktuella krav och eventuell utvecklaråtkomst via
   [Minecrafts officiella support](https://help.minecraft.net/).
6. Sluttesta den registrerade appen med ett konto som äger Minecraft Java Edition.
   Spelare som får en förkonfigurerad Aurora-version ska kunna använda samma
   offentliga app-ID med sina egna konton utan att registrera varsin launcher-app.

Aurora väljer en ledig lokal port för varje försök. Den faktiska adressen är
`http://localhost:<port>/`, där `<port>` ersätts av det valda portnumret. Du ska
inte skriva platshållaren `<port>` eller varje tänkbart portnummer i portalen.
Microsofts [regler för localhost-redirect](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url#localhost-exceptions)
ignorerar porten vid matchning av `localhost`, medan sökvägen fortfarande måste
stämma. Därför ska registreringen använda rotadressen ovan och inte exempelvis
`/callback`. Microsoft skickar tillägg som `code` och `state` i svaret; lägg inte
in dessa parametrar i den registrerade adressen.

## Så loggar spelaren in när appen är konfigurerad

1. Klicka **Logga in med Microsoft** i Aurora. Webbläsaren öppnas på Microsofts
   inloggningssida. Medan försöket pågår kan du välja **Öppna Microsoft igen**
   i Aurora om du har stängt webbläsarfliken. Vid fel på webbläsarstarten visas
   ett felmeddelande; kontrollera din standardwebbläsare och börja om.
2. Välj Microsoft-kontot som äger Minecraft Java och godkänn begäran på
   **Microsofts webbsida**. Skriv aldrig Microsoft-lösenord eller en client secret
   i Auroras inställningar.
3. Efter godkännandet återgår webbläsaren till launcherns tillfälliga lokala
   callback. Låt Aurora vara öppen under försöket. Du behöver inte kopiera en kod,
   token eller webbläsaradress tillbaka till launchern.
4. Aurora kontrollerar Minecraft-licensen och Java-profilen. När profilnamnet visas
   kan du starta spelet. Om du avbryter eller försöket går ut, starta ett nytt
   inloggningsförsök; återanvänd inte en gammal callback-adress.

Den lokala adressen används för att lämna tillbaka svaret till Aurora på samma
dator. Microsoft-lösenordet behandlas av Microsoft. PKCE knyter den tillfälliga
auktoriseringskoden till det inloggningsförsök Aurora startade. Microsoft beskriver
protokollet och kravet att offentliga appar inte använder client secrets i
[dokumentationen för authorization-code-flödet](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).

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

Inloggningskedjan och sessionslagringen testas med deterministiska, simulerade
Microsoft-, Xbox- och Minecraft-svar. Inga riktiga lösenord eller kontotokens
används i testerna. **Riktig webbläsarinloggning och spelstart med användarkonto
har inte kunnat sluttestas eftersom ett registrerat Aurora-app-ID saknas.**
Att protokolltesterna passerar bevisar inte att Microsoft har godkänt appen eller
att ett visst konto kan använda hela kedjan.
