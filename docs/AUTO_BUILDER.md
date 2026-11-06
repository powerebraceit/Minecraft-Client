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
3. Aktivera **Preview** om den är avstängd. Schemat visas med blå, genomskinliga
   blockformer. **G**, eller **Move schematic corner to your feet**, flyttar
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
måste vara giltiga i Minecraft 26.3. Äldre block konverteras inte automatiskt.
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
4096 saknade block inom 48 block från spelaren. Filväljaren listar högst 500
poster per mapp; använd en fullständig sökväg för övriga filer.

## Verifiering

`./gradlew build runSmoke` kör enhetstester och ett verkligt Minecraft 26.3-speltest
i en separat lokal värld. Speltestet väljer en skapad `.litematic`-fil genom
filväljaren utan Litematica/MaLiLib, provar automatisk filväljare och avbrytning,
G-ankring och preview. Det bygger sedan sten, en riktad trappa och en dubbel slab
i två lager i survival. Kistbindning, sparad färg, dubbelkisteidentitet, fysisk
gång, serverns inventory, faktisk restock, materialkonsumtion och spärren vid
saknade material verifieras också.

Läsarens sex enhetstester täcker bland annat negativa dimensioner, packade bitar
över long-gränser, flera regioner, felaktiga filer, klassisk blockpalett och
materialantal för dörrar/dubbla slabs. Dörrar och sängar har inte speltestats.
Testerna har körts på Linux med Java 25 och Fabric Loader 0.20.1. Windows, inloggad
multiplayer och andra modkombinationer har inte verifierats i den här miljön.
