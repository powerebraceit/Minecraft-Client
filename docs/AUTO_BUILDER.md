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

Listan och byggplanen byggs från samma recept för den valda filens block.
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
klienten. Vattenfyllda block med egenskapen `waterlogged` byggs i två steg:
först blocket, sedan vatten. De använder samma hinkbudget och återfyllningspool.
Både blockmodellen och vattnet visas i deras genomskinliga preview.

## Material och lager

- Start kräver allt återstående material i inventory eller inspekterade,
  registrerade kistor. Ingen blockplacering sker under materialinspektionen.
- Klienten går till kistorna och väntar på serverns containerinnehåll. Den gissar
  inte innehållet från klientens block entities.
- Material som redan finns i inventory används först inom det aktuella lagret.
  Lagret behöver inte få plats samtidigt. Restock hämtar en exakt portion som
  ryms i kompatibla stackar och lediga platser, med nästa byggmaterials behov först.
  Sedan fortsätter bygget på samma lager och hämtar fler portioner vid behov.
- Efter en överföring återförs överskott till kistan. Kistan stängs och öppnas igen
  för att kontrollera den exakta ökningen mot serverns fullständiga synkronisering.
  Ett ofullständigt men användbart parti räcker för att fortsätta bygga.
- Om ett block saknar stöd eller fri placering provas andra block i samma lager.
  Efter en bekräftad placering provas väntande block igen. Nästa steg i ett
  flerstegsrecept får inte börja innan föregående steg bekräftats.
- Gångsökningen söker fria placeringsytor från gångbara positioner, provar andra
  positioner när ett placeringsläge nekas och tar hänsyn till spelarens kropp.
  Blockens verkliga delytor används, inklusive tunna och sammansatta former.
  Placering ovanpå/mot en kista sker med sneak; sneak släpps inför restock.
- Ett lager slutförs före nästa. Serverns blockuppdateringar måste bekräfta både
  placeringen och rätt blockstate. Redan korrekt placerade block hoppas över.
- Trappor orienteras med spelets vanliga placeringsregler. En dubbel slab kräver
  två föremål och två placeringsklick. Dörrars överdel och sängars huvud räknas
  som tillhörande block, inte som extra föremål.
- Saknade material, full inventory utan användbara byggföremål eller ledigt utrymme, konfliktblock, världsbyte, död eller en
  misslyckad placering ger stopp/paus. En ny start gör om materialkontrollen.

## Byggtempo och normal räckvidd i 1.9.0

Tillgängliga, närliggande block inom det aktuella lagret väljs först. När en gång
eller siktning har börjat behålls det valda målet så att byggaren inte byter håll
när avstånden ändras. Gångens riktning återställs före rörelseinput; siktning mot
ett block kan inte styra nästa gångsteg åt ett annat håll.

Finns en giltig placeringsyta från spelarens nuvarande plats börjar ingen
sökning efter en annan plats. Gångsökningen använder en kortaste väg inom en
begränsad korridor nära målet och undviker tomma vänteticks mellan delmål.
Block med riktningsberoende state väntar in rotationen; sneak väntar in input.
Vanliga block får ingen sådan extra siktesväntan. Högst ett byggklick skickas var
fjärde tick, motsvarande cirka fem block per sekund vid 20 TPS. Serverbekräftelse,
gång, materialbyten, restock och komplicerade former kan ge lägre faktisk takt.

Räckvidden är **min(4,5 block, spelarens räckviddsattribut)**, mätt från ögat till
den verkliga klickytan. Ett högre attribut från en annan mod utökar inte byggarens
räckvidd. Block och hinkar måste vara inom räckvidd och synliga vid själva klicket.

## Flerstegsblock och registerstöd i 1.7.0

- Alla registrerade krukväxter använder **blomkruka + växt**. En redan placerad
  tom kruka kräver endast växten. Röd svamp i kruka är inget separat inventory-item.
- Alla 17 ljustårtor använder **tårta + rätt ljus**. Tända ljus och ljustårtor
  behöver dessutom en **fire charge**. Vattenfyllda ljus kan inte förbli tända.
- Dubbla slabs, snölager, flera ljus, sjögurkor, sköldpaddsägg och block med
  segmentmängd byggs med ett serverbekräftat klick per föremål.
- Fulla kittlar använder kittel + rätt fylld hink; pudersnö använder pudersnöhink.
  Delvis fyllda kittlar är manuella moment.
- Väggfacklor, väggskyltar och väggskallar får rätt föremål via spelets register
  och använder föremålets egna placeringsregler. Näckrosblad använder spelets
  särskilda placering ovanpå vatten efter vätskefasen.
- Föremål och mellanresultat är gemensamma för byggaren, restock och materiallistan.
  Ett senare korrekt steg räknar tidigare steg som klara. Tas blocket bort
  återkommer materialbehovet.

Registertestet går igenom **1286 registrerade block och 35723 blockstates** utan
okända vanilla-id:n eller receptfel. [Hela rapporten](placement-registry-26.3.tsv)
visar default-recept och manuella undantag. Testet bekräftar materialresolvern,
inte automatisk placering i alla möjliga omgivningar. Moddade block måste finnas
installerade i samma spelversion; okända mod-id:n ger fortfarande ett tydligt filfel.

Block utan direkt placerbart föremål får en manuell materialrad i stället för att
hela planen nekas. Grannkopplingar, redstone och tillväxtegenskaper får uppdateras
av spelet efter placering. Slutkontrollen kräver exakta blockstates: kvarstående
skillnader pausas efter två sekunder och syns fortsatt i previewn. Portaler,
pistonhuvuden, tillväxt, ofullständigt valda dörrar/växter och block entity-data
kan kräva manuellt arbete. Byggaren skapar inga föremål eller otillåtna states.

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

Gränser: **262144 schemaceller och högst 262144 placeringsåtgärder**, **32 MiB komprimerad fil**, **64 MiB NBT-data**,
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


Recepttesterna kontrollerar alla krukväxter och ljustårtor, delvis planterade
krukor, lagerantal, vattenfyllning, registrerade föremål för väggvarianter/frön och
manuella block. Survival-testet läser en riktig `.litematic` och bygger röd svamp
i kruka, ekplanta i kruka, tänd blå ljustårta, fyra röda ljus, tre snölager,
väggfackla, vattenfylld järnkedja och näckrosblad. Det kräver serverbekräftade
mellanresultat, exakta slutstates, noll återstående material och återlämnad tom hink.


I 1.8.0 verifieras dessutom **72 block i samma lager med en enda användbar
inventory-plats** (35 andra fulla stackar ligger kvar). Testet börjar vid
`-384, -60, -628`, bygger först med medtaget material och hämtar sedan flera
serverbekräftade portioner. Ingen placering på nästa lager får ske innan hela
första lagret bekräftats. En väggfackla måste vänta på sitt stöd, och ett block
placeras ovanpå en kista med sneak. Ett negativt fall med saknat stöd måste
pausa och lämna nästa lager orört. Det ursprungliga schemat bakom en
användarskärmbild finns inte i repot; testerna återskapar dessa felklasser.


Tempo- och räckviddstestet placerar tolv block från en enda upphöjd plats utan
att flytta spelaren, med fyra ticks mellan klicken. Ett andra fall börjar åtta
block från målet och mäter rak gång, total gångsträcka och faktisk klickräckvidd.
Även med testattributet åtta måste spelaren gå fram och hålla klicket inom 4,5.
Gränsvärden, lägre attribut, diagonal räckvidd, val av närliggande mål och ett
stabilt gångmål täcks av separata enhetstester.


Mätt i den lokala 1.9.0-körningen: **12 block på 46 ticks (2,3 sekunder)** utan
gång. Målet åtta block bort nåddes på en rak **3,86 blocks gångväg**, med faktisk
klickräckvidd **4,494 block**, trots räckviddsattributet åtta. Hela speltestet och
**73 enhetstester** passerade. Dessa siffror gäller testfallen på den lokala
servern; restock, terräng och serverfördröjning påverkar andra byggen.
