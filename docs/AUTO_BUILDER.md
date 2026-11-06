# Auto Builder: pågående arbete

Auto Builder är **inte en spelbar funktion i version 1.2.0**. Högerklicksinställningarna
är färdiga, men Litematica-integrationen och automatisk restock är ännu inte färdiga.

## Avsedd funktion

- Använd den valda, aktiverade placeringen i Litematica för Minecraft 26.3.
- Visa Litematicas riktiga schematic-preview. En knapp flyttar byggnadens lägsta
  hörn till spelarens fötter, även för roterade placeringar och flera subregioner.
- Kontrollera allt återstående material före byggstart. Redan rätt placerade block
  behöver inga nya material. Spelarens inventory och inspekterade restock-kistor
  räknas tillsammans; okända kistor får aldrig räknas som tillgängligt material.
- Bygg nerifrån och upp, ett lager i taget. Serverbekräftad placering krävs före
  nästa block/lager. Felaktig placering får inte tyst godkännas.
- Bind en tangent för att registrera kistan som spelaren tittar på. Spara dimension
  och position, undvik dubbelräkning av dubbelkistor och markera valda kistor med
  en färg som kan ändras i Auto Builders högerklicksinställningar.
- Hämta saknat lagermaterial genom att gå till en registrerad kista, öppna den,
  läsa dess synkroniserade inventory och flytta relevanta stackar. Vänta på serverns
  inventory-uppdateringar och gå tillbaka för att fortsätta bygga samma lager.
- Ge begriplig pausstatus vid saknat material, full inventory, blockerad väg,
  otillgänglig placering, byte av värld eller misslyckad blockplacering.

## Kod som finns

`core/builder/BuildPlan.java` ger en oföränderlig lagerordning och materialräkning.
`core/builder/BuildSession.java` ger materialspärr, lagerbarriär, restock-tillstånd,
väntan på bekräftelse, timeout och stopp vid världsbyte. Fem logiktester täcker
materialbrist, okända kistor, restock före placering, korrekt lagerordning och
återställning. Detta är planeringskod; den ansluts ännu inte till spelet eller en
påhittad Auto Builder-modul i menyn.

## Inspekterad Litematica-kod

Officiella 26.3-källor finns i Sakura-Ryokos Litematica och MaLiLib-repon:

- `DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement()`
- `SchematicPlacement.getOrigin()`, `getSubRegionBoxes(...)` och
  `setOrigin(BlockPos, IStringConsumer)`; låsta placeringar måste respekteras.
- `SchematicWorldHandler.getSchematicWorld()` för previewns transformerade block.
- `MaterialCache.getRequiredBuildItemForState(...)` för korrekt materialmappning.
- `EasyPlaceUtils.handleEasyPlaceWithMessage()` använder spelarens riktiga sikte,
  räckvidd och klickposition. Returvärdet är inte ett kvitto på lyckad placering;
  verklig blockstate måste kontrolleras efteråt.

## Återstående för implementation och verifiering

Hämta kompatibla Litematica/MaLiLib-JAR-filer och lås deras versioner. Implementera
placement-snapshot och preview/fötter-ankring, registrering och rendering av kistor,
vägval och rörelse, verkliga containeröverföringar och blockplacering samt
inställningsraderna. Kör sedan integrationstest i survival med ett litet flerlager-schema,
korrekta och saknade material, färgbyte, restock och världsbyte. Publicera ingen
funktion som klar innan dessa kontroller har körts.

I den aktuella molnmiljön nekades hämtning från `masa.dy.fi` och `api.modrinth.com`
med HTTP-proxyns 403. `masa.dy.fi`, `api.modrinth.com` och `cdn.modrinth.com` har
lagts till i miljöns konfigurationsutkast men behöver aktiveras genom miljöinställningarna.
