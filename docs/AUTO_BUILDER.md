# Auto Builder — experimentell beta

**1.3.0-beta.1 är en testversion.** Inventories, kistbindning, gångvägar, faktiska
materialöverföringar och serverbekräftat lagerbygge har testats i Minecraft 26.3
survival med en explicit tvåblocksplan. Kopplingen till en verklig Litematica-
placering har **inte** körts i spel: miljön blockerar fortfarande nedladdningen av
Litematica/MaLiLib. Preview, hörnankring och Litematicas Easy Place måste verifieras
med dessa moddar innan funktionen kan betraktas som färdig.

## Krav och användning

Installera Litematica för **26.3** (0.29.x) och motsvarande MaLiLib (0.30.x) separat.
Aurora startar även utan dem, men Auto Builder kan då inte aktiveras.

1. Ladda ett `.litematic`-schema i Litematica och välj dess aktiverade placering.
2. Öppna Aurora med höger Shift, välj World och högerklicka på **Auto Builder**.
3. Slå på **Preview**. Knappen **Move schematic corner to your feet**, eller
   **G** som standard, flyttar placeringens lägsta hörn till dina fötter. Låsta
   placeringar/koordinater måste låsas upp först. Inställningen respekterar
   subregionernas transformationer och väljer deras gemensamma min-hörn.
4. Titta på en kista och tryck **V** för att registrera den som restock-kista.
   Tryck på samma kista igen för att ta bort den. Tangenten kan ändras i modulens
   inställningar. Egna modul-hotkeys från tidigare versioner har företräde vid en tangentkonflikt. Dubbelkistans två halvor räknas som samma inventory.
5. Ändra **Restock color** i Auto Builder-inställningarna för att välja markeringens
   färg. Markeringarna visas även när bygget är avstängt.
6. Starta med **Start / stop build**, eller bind en modul-hotkey och använd den när
   menyn är stängd. Stoppa med samma knapp/hotkey. Byggen återupptas aldrig automatiskt
   efter en ny spelstart.

## Material och lager

- Byggstart kräver allt återstående material i inventory eller inspekterade,
  registrerade kistor. Ingen blockplacering sker under materialinspektionen.
- Klienten går till kistorna och väntar på serverns synkroniserade containerinnehåll.
  Den gissar inte innehållet från klientens block entities.
- Saknat material hämtas ur kistorna med vanliga inventory-klick och serverns
  revision kvitterar överföringen. Den återstående delen av det aktuella lagret
  styr vilka material som hämtas. Hela stackar kan flyttas; överskott ligger kvar
  i spelarens inventory.
- Ett lager slutförs före nästa. En klientprediktion räcker inte: blockpaket från
  servern måste bekräfta varje begärd placering och rätt blockstate.
- Litematicas placement-position-provider väljer exakt det planerade blocket under
  Easy Place-anropet och avregistreras efteråt, så fri raytrace inte väljer nästa lager.
- Saknade material, full inventory, konfliktblock, ändrad placering, världsbyte,
  död eller misslyckad placering ger stopp/paus. En ny start gör om materialkontrollen.

## Begränsningar

Gångvägen använder endast laddad, gångbar mark inom en begränsad sökning. Den kan
ta enkla steg och hoppa ett block, men bygger inga broar eller ställningar, gräver
inte vägar och flyger/teleporterar inte. Svåra/höga byggen kan därför pausa när ett
block inte går att nå. Dagens gräns är 262144 schemaceller, 32 registrerade kistor
och högst 8192 sökta gångnoder. Överlappande placeringar och subregioner nekas.

Schemat bygger block, inte inventarieinnehåll, entiteter eller all block entity-NBT.
Orienterade eller sammansatta block måste fungera med Litematicas Easy Place och
serverns placeringsregler; vid felaktig blockstate pausas bygget. Ingen multiplayer-
server eller annan modkombination har verifierats här.

## Verifiering och återstående arbete

Kör `./gradlew build runSmoke`. Speltestet körs i en egen lokal värld och går igenom
kistbindning, sparad färg, dubbelkisteidentitet, fysisk gång, serverns inventory,
faktisk restock, materialkonsumtion i survival, två serverbekräftade lager och
nekad start när material saknas. Detta använder en **explicit testplan**, inte en
Litematica-fil, och resultatfilen säger det uttryckligen.

Nästa nödvändiga kontroll är ett riktigt `.litematic`-schema med Litematica och
MaLiLib installerat: preview, G-ankring, roterade subregioner, Easy Place-provider,
fler materialtyper och en paus/omstart efter saknade resurser. Nedladdning från
`masa.dy.fi` och `api.modrinth.com` har fortfarande nekats med proxyns 403. Nya domäner
(`masa.dy.fi`, `api.modrinth.com`, `cdn.modrinth.com`) ligger redan i miljöutkastet;
det behöver aktiveras via miljöinställningarna för att slutföra dessa tester.
