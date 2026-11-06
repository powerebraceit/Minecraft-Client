# Auto Builder — lokala .litematic-filer

Från **1.4.0** läser Aurora `.litematic` direkt. **Litematica och MaLiLib behövs
inte.** Filväljaren körs i Minecraft på spelarens dator; du behöver inte ladda upp
schemat till någon tjänst.

## Välj och placera schemat

1. Öppna en värld, tryck höger Shift, välj World och högerklicka på **Auto Builder**.
2. Klicka **Choose .litematic file...**. Filväljaren börjar i instansens
   `schematics`-mapp eller senast använda mapp. Klicka på en mapp för att öppna den
   och på en `.litematic`-fil för att välja den. Sökvägsfältet och **Go** kan öppna
   en annan mapp eller fil. **Up** går upp en mapp; **Home** öppnar din hemmapp.
   Efter filval visas materiallistan direkt. **Preview in world** stänger menyerna
   och visar schemats block på rätt plats.
3. Aktivera **Preview** om den är avstängd. Schemat visas med genomskinliga, texturerade
   blockmodeller. Röda ramar betyder saknat block, gula ramar betyder fel block
   eller fel blockstate. Blockens egna texturer behåller sina färger; endast ramen
   är röd/gul. Opaciteten är 70 % från början och kan ändras med **Preview opacity**.
   Ett rätt placerat block döljs, och visas igen om det tas bort. **G**, eller **Move schematic corner to your feet**, flyttar
   schemats gemensamma lägsta hörn till blockpositionen vid dina fötter.
4. Titta på en kista och tryck **V** för att registrera den som restock-kista.
   Samma tangent på samma kista tar bort den. Dubbelkistans två halvor räknas som
   ett inventory. Ändra **Restock color** i modulinställningarna för att välja färg.
5. Kontrollera previewn och starta med modulens av/på-knapp, **Start / stop build**
   eller en egen modul-hotkey. Stoppa med samma knapp/hotkey. En ny fil eller
   flyttning av hörnet stoppar ett pågående bygge. Starta sedan igen.

Om du aktiverar Auto Builder innan du valt en fil öppnas filväljaren automatiskt.
När filen valts försöker bygget starta. **Back** eller Escape avbryter starten.
Använd det manuella filvalet i inställningarna om du vill granska previewn först.
G- och V-tangenterna kan ändras. Egna modul-hotkeys har företräde vid en konflikt.
Byggen återupptas inte automatiskt efter en ny spelstart; välj filen igen.

## Materiallista och HUD

**Material list...** i Auto Builder-inställningarna visar alla material med
föremålsikoner. Scrolla för att se resten av en lång lista.

| Kolumn | Betydelse |
| --- | --- |
| Total | Föremål för hela schemat. |
| Left | Föremål som återstår för block som inte är rätt placerade. |
| Have | Föremål i spelarens inventory, inklusive hotbar. |
| Missing | `max(0, Left − Have)`. Kistornas innehåll ingår inte i denna kolumn. |

En kompakt material-HUD visar upp till sex återstående material och deras
**Left / Total**. Filnamnet visas på panelen och färdiga material finns kvar med
antalet noll återstående. Hela listan finns alltid i inställningarna. Panelen visas även
när du bara granskar schemat med byggmodulen avstängd. Dra den med **T**-chatten
öppen, precis som övriga HUD-paneler. Auto Builder-inställningarna har
**Materials HUD**, **Background**, textskugga, accent och återställning av position.
Preview och material-HUD kan slås av/på var för sig. Ett nytt filval slår på båda
igen så att det laddade schemat och dess lista blir synliga. Sparad HUD-position
behålls.

Listan byggs direkt från den valda filen och är oberoende av byggplanens krav.
Vatten- och lavakällor visas med respektive hinkikon. Vatten visar högst två
vattenhinkar, lava visar en hink per källblock som återstår. Flytande vätska kostar
inte extra hinkar. Andra block utan direkt föremål visas fortfarande som manuella
blockrader med `*`; de döljer inte övriga material.

Listan följer placeringar och borttagningar i världen. För stora scheman sprids
kontrollen över flera ticks; serverns enskilda blockuppdateringar uppdaterar den
berörda posten direkt. En befintlig enkel slab minskar materialbehovet för en
dubbel slab till ett föremål. Överdelar av dörrar och sängars huvud räknas inte
som egna extra föremål.

## Vatten, lava och återfyllning

Från **1.6.0** placerar byggaren vanliga vatten- och lavakällor genom spelets
vanliga hinkanvändning. Alla vanliga byggblock färdigställs först, lager för lager.
Därefter placeras vätskorna lager för lager, så att golv och inneslutning finns
innan vätskan hälls ut. Källblock räknas som hinkar; flödesblock skapas av spelets
vätskesimulering. Ta med källorna när du exporterar schemat.

För fler än två återstående vattenkällor behövs en återfyllningspool:

1. Förbered en **2×2-bassäng, en block djup**, med hela fasta block som botten och
   väggar. Sten eller glas fungerar. Poolens fyra vattenplatser ska vara fria, med luft
   ovanför. En befintlig grund 2×2-pool med fyra vattenkällor fungerar också.
2. Lägg poolen utanför schemats område, inom 12 block från spelaren vid byggstart.
   Byggaren söker efter en lämplig pool automatiskt och gräver inga hål åt dig.
3. För en annan plats: stå i poolens hörn med lägst X/Z, öppna Auto Builder och
   välj **Use water pool corner at your feet**. De andra platserna ligger åt öster
   och söder. Bindningen sparas för världen/dimensionen. **Clear water pool
   binding** återgår till automatisk sökning.
4. Ha högst två vattenhinkar som startmaterial. Klienten placerar dem i diagonala
   hörn, väntar på fyra källor och fyller därefter tomma hinkar från en återskapande
   plats. Efter varje påfyllning krävs både serverns inventory-uppdatering och
   att poolens källa återskapas innan arbetet fortsätter.

Lava återfylls från dina registrerade restock-kistor och förbrukar en fylld hink
per källblock. Tomma hinkar behålls. Byggaren använder ingen oändlig lavakälla.

Poolen måste vara nåbar och innesluten. Om vatten avdunstar där det ska placeras,
om poolen skadas, om servern nekar en åtgärd eller om serverns regler hindrar
vattenkällor från att återskapas pausas bygget. Inga extra hinkar skapas av
klienten. Vattenfyllda block med egenskapen `waterlogged` använder fortfarande den
vanliga blockplacerarens krav; denna funktion gäller separata vätskekällor.

## Material och lager

- Start kräver allt återstående material i inventory eller inspekterade,
  registrerade kistor. Ingen blockplacering sker under materialinspektionen.
- Klienten går till kistorna och väntar på serverns containerinnehåll. Den gissar
  inte innehållet från klientens block entities.
- Saknat material för det aktuella lagret hämtas med vanliga inventory-klick.
  Efter en stacköverföring stängs och öppnas kistan igen för att kontrollera
  spelarens inventory mot serverns fullständiga synkronisering. Hela stackar kan
  flyttas; överskott ligger kvar i inventory.
- Ett lager slutförs före nästa. Serverns blockuppdateringar måste bekräfta både
  placeringen och rätt blockstate. Redan korrekt placerade block hoppas över.
- Trappor orienteras med spelets vanliga placeringsregler. En dubbel slab kräver
  två föremål och två placeringsklick. Dörrars överdel och sängars huvud räknas
  som tillhörande block, inte som extra föremål.
- Saknade material, full inventory, konfliktblock, världsbyte, död eller en
  misslyckad placering ger stopp/paus. En ny start gör om materialkontrollen.

## Filformat och begränsningar

Läsaren stöder `.litematic`-formatversion **5, 6 och 7**, negativa regionstorlekar,
flera regioner och både klassisk och modern blockpalett. Blocknamn och properties
måste vara giltiga i Minecraft 26.3. Äldre block konverteras inte generellt. Det gamla namnet `minecraft:chain`
översätts till `minecraft:iron_chain`, med blockets egenskaper bevarade.
`.schem` och `.schematic` stöds inte. Överlappande regioner nekas, även om en av
regionerna har luft i överlappningen. Endast blockmönstret används; entiteter,
inventarieinnehåll och block entity-NBT kopieras inte.

Gångvägen använder laddad, gångbar mark inom en begränsad sökning. Den kan ta enkla
steg och hoppa ett block, men bygger inga broar eller ställningar och gräver inga
vägar. Den flyger eller teleporterar inte. Svåra/höga byggen kan pausa när ett
block inte går att nå. Placeringar kräver stöd och en blockstate som går att
åstadkomma med vanliga placeringsklick; alla block och mekanismer stöds därför
inte. Fel blockstate accepteras inte som ett färdigt bygge.

Gränser: **262144 schemaceller**, **32 MiB komprimerad fil**, **64 MiB NBT-data**,
**32 registrerade kistor** och högst **8192 sökta gångnoder**. Preview visar högst
4096 saknade eller felplacerade block inom 48 block från kameran. Filväljaren listar högst 500
poster per mapp; använd en fullständig sökväg för övriga filer.

## Verifiering

`./gradlew build runSmoke` kör enhetstester och ett verkligt Minecraft 26.3-speltest
i en separat lokal värld. Speltestet väljer en skapad `.litematic`-fil genom
filväljaren utan Litematica/MaLiLib, provar automatisk filväljare och avbrytning,
G-ankring och texturerad preview. Röda/gula ramar, döljning av rätt placerade
block och återvisning efter borttagning verifieras tillsammans med materialantal
och material-HUD som kan flyttas i chatten. Kistans genomskinliga specialmodell
kontrolleras genom att verifiera faktisk geometriinlämning till renderaren. Det bygger sedan sten, en riktad trappa och en dubbel slab
i två lager i survival. Kistbindning, sparad färg, dubbelkisteidentitet, fysisk
gång, serverns inventory, faktisk restock, materialkonsumtion och spärren vid
saknade material verifieras också.

Läsarens sju enhetstester täcker bland annat negativa dimensioner, packade bitar
över long-gränser, flera regioner, felaktiga filer, klassisk blockpalett och
materialantal för dörrar/dubbla slabs. Dörrar och sängar har inte speltestats. Vätsketestet bygger tre vattenkällor
med endast två vattenhinkar via en återskapande pool, två lavakällor och en kedja
från en fil med det gamla namnet `chain`. Det kontrollerar även de tomma hinkarna
och den verkliga hämtningen av en lavahink från en restock-kista.
Sex ytterligare enhetstester täcker färgklassificering, materialändring vid
placering/borttagning, delvis placerade dubbla slabs, dörröverdelar, vatten i en blandad fil och byte av
valt schema. Speltestet jämför dessutom bilder med preview på/av och kontrollerar
att blockytans inre pixlar faktiskt ritas i vanligt och förbättrat transparensläge.
Testerna har körts på Linux med Java 25 och Fabric Loader 0.20.1. Windows, inloggad
multiplayer och andra modkombinationer har inte verifierats i den här miljön.

Vätskornas sju enhetstester kontrollerar hinkantal, källor/flöden, byggordning,
uppdaterade materialantal och säkra bassänger med sten eller glas.
