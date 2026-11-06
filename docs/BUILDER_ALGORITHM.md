# Java-logik för Auto Schematic Builder

Logiken är uppdelad i en ren Java-kärna och en Minecraft-adapter. Den befintliga
klienten använder **Fabric, Minecraft Java 26.3 och Java 25**. A*-motorn och
placeringskön importerar inga Fabric-, Forge- eller Minecraft-klasser. En
Forge-version behöver en egen adapter för världsläsning, rörelse, interaktioner
och serverhändelser; en färdig Forge-mod ingår inte här.

## Ansvar och källkod

| Klass | Ansvar |
| --- | --- |
| [AStarPathfinder](../src/main/java/dev/aurora/core/builder/AStarPathfinder.java) | Sökning mellan säkra ståpositioner med flera möjliga mål. |
| [TerrainSafety](../src/main/java/dev/aurora/minecraft/builder/TerrainSafety.java) | Kollision, underlag, vätskor och farliga block i Minecraft-världen. |
| [WalkNavigator](../src/main/java/dev/aurora/minecraft/builder/WalkNavigator.java) | Välj giltiga ståplatser, följ vägen med vanliga rörelseinput och planera om. |
| [BuildSession](../src/main/java/dev/aurora/core/builder/BuildSession.java) | Lager, receptordning, materialpartier och väntan på serverbekräftelse. |
| [NativePlacement](../src/main/java/dev/aurora/minecraft/builder/NativePlacement.java) | Verklig blockyta, yaw/pitch, vanilla-placeringsregler och ett validerat klick. |
| [BuildExitGuard](../src/main/java/dev/aurora/minecraft/builder/BuildExitGuard.java) | Kontrollera att den tänkta placeringen lämnar en verifierad utväg. |
| [BuilderRuntime](../src/main/java/dev/aurora/minecraft/builder/BuilderRuntime.java) | Koppla ihop komponenterna med Minecraft-klienten. |

## A*: sök en ståplats, inte blockets position

`Cell` beskriver spelarens fötter i heltalskoordinater. `Terrain.canStand` måste
kräva fritt utrymme för kropp och huvud, ett bärande golv och ofarlig terräng.
Oladdade områden är inte gångbara. `Terrain.isClear` används även för det utrymme
som kroppen passerar vid ett steg upp eller ned.

Placeringens mål är en **mängd giltiga ståplatser**. Minecraft-adaptern tar fram
platser nära det önskade blocket och kontrollerar räckvidd och verklig sikt till
placeringsytan. A* väljer den billigaste nåbara ståplatsen. Om själva blockets
position ligger bakom en vägg eller är upptagen kan en annan ståplats fortfarande
fungera. En närliggande plats utan sikt eller med lava blir aldrig ett godkänt mål.

Kostnaden är 10 för ett vågrätt steg och 14 för ett steg med en blocks höjdskillnad.
Heuristiken underskattar kostnaden till närmaste mål, och lika prioriteringar har
en stabil ordning. Motorn tillåter inte diagonal hörngenomskärning, flygning eller
fall över flera block. Sökningen har både koordinatgränser och nodbudget.
`Result.reached()` måste vara sant innan `Result.path()` används: ett ofärdigt
sökresultat är aldrig en tillåtelse att börja gå.

## Körbart exempel på den rena Java-kärnan

Exemplet nedan går att kompilera med Java 25 tillsammans med de tre länkade
kärnklasserna. Det demonstrerar A*, en placeringskö och en **simulerad**
serverbekräftelse. Det ändrar ingen Minecraft-värld. Den verkliga
`BuildSession.Driver`-implementationen finns i `BuilderRuntime`.

```java
import dev.aurora.core.builder.AStarPathfinder;
import dev.aurora.core.builder.AStarPathfinder.*;
import dev.aurora.core.builder.BuildPlan;
import dev.aurora.core.builder.BuildSession;
import java.util.*;

public final class AlgorithmExample {
    public static void main(String[] args) {
        Set<Cell> obstacles = new HashSet<>();
        for (int z = -1; z <= 1; z++) {
            obstacles.add(new Cell(2, 0, z));
            obstacles.add(new Cell(2, 1, z));
        }
        Terrain terrain = new Terrain() {
            // Detta exempel har ett säkert, plant golv under y=0.
            public boolean canStand(Cell feet) {
                return feet.y() == 0 && !obstacles.contains(feet);
            }
            public boolean isClear(Cell cell) {
                return cell.y() >= 0 && !obstacles.contains(cell);
            }
        };
        Result route = AStarPathfinder.search(
            new Cell(0, 0, 0),
            List.of(new Cell(3, 0, -2), new Cell(3, 0, 2)),
            terrain, new Bounds(-2, -1, -3, 6, 2, 3), 512);
        if (!route.reached()) throw new IllegalStateException(route.status().name());
        System.out.println("Ståplats: " + route.reachedGoal().orElseThrow());

        var action = new BuildPlan.Block(4, 0, 0, "minecraft:stone", 1, "minecraft:stone");
        Set<BuildPlan.Block> serverConfirmed = new HashSet<>();
        BuildSession.Driver adapter = new BuildSession.Driver() {
            public String worldIdentity() { return "example-world"; }
            public boolean placed(BuildPlan.Block block) { return serverConfirmed.contains(block); }
            public Map<String, Integer> inventory() { return Map.of("minecraft:stone", 1); }
            public BuildSession.Stock stock() { return new BuildSession.Stock(Map.of(), true); }
            public BuildSession.Restock restock(Map<String, Integer> wanted) {
                return BuildSession.Restock.BLOCKED;
            }
            public boolean preparePlacement(BuildPlan.Block block) {
                // Spelets adapter går till ståplatsen och validerar yta/rotation här.
                return route.reached();
            }
            public void place(BuildPlan.Block block) {
                System.out.println("Skicka ett validerat klick; vänta sedan på servern.");
            }
        };
        BuildSession queue = new BuildSession(new BuildPlan(List.of(action)), 100);
        queue.start(adapter);
        queue.tick(adapter);
        if (queue.phase() != BuildSession.Phase.WAITING_ACK) throw new AssertionError();
        // Endast som demonstration: en riktig adapter använder serverns blockpaket.
        serverConfirmed.add(action);
        queue.tick(adapter);
        if (queue.state() != BuildSession.State.COMPLETE) throw new AssertionError();
    }
}
```

Exemplet finns även i [examples/AlgorithmExample.java](../examples/AlgorithmExample.java). Kör från repots rot:

```bash
javac -d build/example src/main/java/dev/aurora/core/builder/AStarPathfinder.java src/main/java/dev/aurora/core/builder/BuildPlan.java src/main/java/dev/aurora/core/builder/BuildSession.java examples/AlgorithmExample.java
java -cp build/example AlgorithmExample
```

I en riktig adapter ska `preparePlacement` returnera `false` medan spelaren går
eller siktar. När den returnerar `true` får `place` skicka ett enda klick. Ett
lokalt förutsagt block får inte läggas till som serverbekräftat.

## Omplanering utan cirkelgång

`WalkNavigator.walkToPlacement(target, acceptableFeet)` använder ett ändligt antal
säkra ståplatser och den verkliga placeringskontrollen som målfilter. Om den
nuvarande säkra platsen redan fungerar börjar ingen onödig gång. Ett mål behålls
under rörelsen i stället för att bytas när små avståndsskillnader uppstår.

Anropa `WalkNavigator.invalidate(changedPos)` vid relevanta blockuppdateringar.
Det gäller även botens egna placeringar. Nästa steg och återstående väg läses mot
den aktuella världen; en gammal väg genom ett nyplacerat block är inte giltig.
Stillastående eller rörelse utan förbättrat avstånd till delmålet räknas som
utebliven framgång. Den misslyckade kanten kan då undvikas vid en begränsad
omplanering. När försöksgränsen nås stoppas arbetet med en orsak i stället för att
slumpmässiga gångriktningar provas.

Rörelse görs genom vanliga framåt-/hoppinput. Gångriktningen hålls skild från den
rotation som används för att sikta på ett block. Ingen hastighet skrivs direkt,
och ingen teleportering ingår i byggalgoritmen.

## Yta, rotation och serverbekräftelse

`NativePlacement.find(target, step)` testar verkliga delytor i blockens former.
För varje kandidat beräknas yaw/pitch och en ny siktstråle. Strålen måste träffa
rätt stödblock och `Direction`; samma träff används för
`BlockPlaceContext`. Föremålets egna vanilla-regler måste sedan förutsäga det
önskade resultatet, inklusive riktning för exempelvis trappor, kistor och kolvar.
Det går alltså inte att återanvända en gammal träff medan spelaren tittar åt ett
annat håll.

`Attempt.aim()` ställer rotation och eventuell sneak-input. Efter att nödvändig
rotation/input hunnit slå igenom validerar `Attempt.visible()` den aktuella
siktstrålen och det förväntade placeringsresultatet igen. `Attempt.click()` gör
samma kontroll precis före interaktionen. Ett vanligt rotationspaket skickas före
interaktionen, så servern använder samma yaw/pitch, följt av den nya faktiska träffen.
Räckvidden är högst `min(4.5, spelarens räckviddsattribut)`, från ögat till ytan.

Kön växlar mellan `SELECTING`, `PREPARING`, `WAITING_ACK` och
`FETCHING_MATERIALS`. Först när `Driver.placed(pending)` observerat rätt
serverbekräftade resultat släpps nästa åtgärd fram. Timeout pausar arbetet; samma
klick upprepas inte blint. Det undviker dubbla placeringar när servern svarar sent.
Grannegenskaper som staketkopplingar får stabiliseras under bygget, men den
slutliga kontrollen kräver exakta blockstates.

## Byggordning och utväg

`BuildSession` färdigställer lägre lager före högre inom respektive byggfas.
Tillgängligt material används först, närliggande block prioriteras och nästa steg
i ett flerstegsrecept måste vänta på föregående bekräftade steg. Block som saknar
stöd skjuts upp inom lagret och provas igen när andra placeringar ändrat läget.
Vätskor körs efter strukturfasen så att inneslutningen hinner byggas.

Enbart närmaste-block-sortering skyddar inte mot att sista dörröppningen stängs.
`BuildExitGuard` minns därför en nåbar, säker utgång utanför schemats horisontella
område, gärna vid restock. Den tänkta placeringen läggs i en **skrivskyddad
överlagring** av världen. A* måste kunna hitta en utväg även efter denna ändring.
Överlagringen skickar inga blockändringar till spelet.

Om den aktuella ståplatsen skulle bli instängd kan en annan placering från
utsidan väljas. Om ingen sådan säker ståplats kan verifieras skjuts blocket upp
eller bygget pausas. Detta är en konservativ, lokal kontroll med begränsad
sökbudget, inte en garanti att varje tänkbar ritning kan byggas automatiskt.
Nodbudget som tar slut räknas aldrig som en bevisad utväg.

## Tester och praktiska gränser

Enhetstester jämför A*:s kostnad med Dijkstra i en labyrint och kontrollerar flera
mål, otillgängliga mål, höjdskillnader, huvudutrymme, stabil ordning och nodbudget.
Terrängtester kontrollerar bland annat golv, lava, eld och farliga ståplatser.
Kötester täcker lagergränser, stabilt gångmål, receptordning och uteblivna eller
fördröjda bekräftelser. Utvägstester provar att stänga en sista passage och att
behålla en alternativ utgång.

Speltesterna använder faktiska vanilla-interaktioner och serverbekräftade
slutstates. De provar också riktningsberoende block, portionsvis restock,
placeringsräckvidd och rörelse. En fristående mod behöver fortfarande egna
kontroller för aktuell Minecraft-version, laddare och eventuella andra moddar.
Algoritmen bygger inte broar, gräver inte väg och accepterar inte oladdad terräng
som säker.


Verifierat i version **1.10.0** med **97 enhetstester**, det fristående Java-exemplet
och hela Minecraft 26.3-survivaltestet. Speltestet planerade om runt en nytillkommen
två block hög vägg och lava, och servern bekräftade exakt riktning för trappa,
kista och kolv. Även befintliga lager-, inventory-, räckvidds- och vätsketester
passerade.
