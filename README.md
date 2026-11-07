# Colonists — autonomous Steves for Minecraft (Fabric 1.21.1)

Colonists are player-shaped NPCs who live their own lives. Nobody controls them. Each one:

- **Gathers**: fells real trees (not your log cabin), mines exposed stone and ore, pulls grass for seeds, hunts cows/pigs/sheep/chickens when hungry, and picks up loot.
- **Crafts**: logs → planks → sticks → wooden, then stone, then iron tools; smelts raw meat and ore; makes bread, torches, beds and chests; wears iron armour once it can afford it.
- **Builds**: claims a lot in its city, levels it and builds a 5×5 cabin (log pillars, plank walls and roof, a door, a torch and a bed when it has them). It repairs the house when it's damaged.
- **Survives**: gets hungry and eats, starves without food, heals when fed, sleeps in its bed at night (or shelters indoors), runs from creepers and flees when badly hurt. Zombies and skeletons hunt colonists too.
- **Farms**: tills a 3×4 plot next to the house, plants wheat/carrots/potatoes/beetroot, and harvests and replants.
- **Stores**: builds a chest at home and stashes its surplus there.
- **Fights**: defends itself and its friends, attacks monsters nearby, and fights enemy factions on sight.
- **Socialises**: walks over to chat with neighbours (in chat, if you're nearby), shares food with hungry friends, and builds goodwill across faction lines.

## Cities

Settlements grow with the land, not on a grid:

- **Founding.** A new city's plaza goes on a level, dry spot near its founder, preferably beside water and down in a valley.
- **Streets.** Streets are pathfound across the terrain from the plaza. They avoid climbs of more than one block, bend around hills, and briefly bridge water. When a city runs out of roadside space, it lays a new street from one of its outer ends toward the most inviting nearby ground (level, low-lying and close to water). The result: towns string out along rivers and fill valleys.
- **Buildings and fields** are placed along the streets, turned to face them, on the best roadside spot: close to the centre, level, near water, sheltered in a valley. Fields prefer the outskirts and flat, well-watered ground.
- **No size limit.** Cities grow as big as births, housing and food allow (`colonistsMaxPerFaction` 0 = unlimited).

Every settlement grows through four ranks:

| Rank | Requirement |
|---|---|
| Village | fewer than 6 houses |
| Town | 6 houses |
| City | 15 houses. Builders start a stone **wall** around everything built so far, with gates where streets cross it. |
| City-State | 15 houses **and** a finished wall |

Promotions (and setbacks, if houses or the wall are lost) are announced in chat.

- **City wall.** It is three blocks high and made of cobblestone (or deepslate or blackstone), following the terrain. Miners stockpile extra stone while it goes up, and builders fetch it from the stores (or quarry it themselves when the stores run dry). Hillsides count as wall, and gates open automatically when a new street needs to pass through.

- **Plaza.** The first colonist picks a spot for the city centre. Builders level a 9×9 square and add a well, a bell, a banner in the faction's colour, corner lamps, and the **city stockpile** chests.
- **Roads and lamps.** Builders pave the streets as dirt paths, clear trees in the way, bridge water, and put a lamp post beside each house.
- **Fields.** Communal fields (9×9, with a water channel across the middle). Farmers claim one for every four citizens.
- **Shared economy.** Workers haul their surplus (logs, stone, ore, wheat, food) to the stockpile. Hungry colonists eat from it, and builders draw stone and coal from it.
- **Moving away.** A faction founded near another city (for example, rebels) walks about 160 blocks away before it builds.

## Buildings

Buildings are made from whatever wood and stone the builder has. They have pitched stair roofs, glass windows (boarded up until someone smelts glass), and furniture inside.

| Homes | Rank | |
|---|---|---|
| Cabin | Village | 5×5 log-and-plank cabin with a vegetable garden |
| Cottage | Town | stone footing, taller walls, kitchen corner |
| Stone house | City | stone ground floor, timber upper storey, two beds |
| Manor | City-State (and every ruler from City up) | 9×7, stone stair roof, two beds |

| Public building | Built from | Used by |
|---|---|---|
| Town hall | Village (2+ people) | the ruler; lectern and benches |
| Lumber camp | Village (3+) | lumberjacks |
| Barn | Village (4+) | farmers |
| Animal pen | Town | ranchers |
| Smithy | Town | blacksmiths; forge and anvil |
| Bakery | Town | cooks; smoker and barrels |
| Tavern | Town | everyone |
| Barracks | City | guards |
| Market | City | (merchants, coming later) |

Builders (or everyone, in a village without one) put up public buildings in order and keep them repaired.

## Jobs

| Job | Work |
|---|---|
| Farmer | Communal fields (9×9, with a water channel) |
| Rancher | Leads animals into the pen on a lead, breeds them, shears sheep, culls the herd for meat |
| Lumberjack | Fells whole trees at once and replants saplings |
| Miner | Digs the stepped quarry outside town, and from Town rank on the mine |
| Blacksmith | Turns iron from the stores into iron tools and armour for the city |
| Cook | Turns wheat and raw meat into bread and cooked food |
| Builder | Public buildings, roads, lamps, the plaza and the wall |
| Guard | Patrols, keeps night watch, and fights |

Specialist jobs open up once their workplace is built, and someone from a crowded trade switches over. Colonists pick up better tools and armour from the stores when the blacksmith has made them.

## Terraforming

How much the land gets reshaped grows with the settlement's rank, and stays modest:

| Rank | Lot levelling | Quarry | Mine |
|---|---|---|---|
| Village | slopes up to 2 blocks | 7×7, 4 deep | none |
| Town | up to 3, with stone footings as retaining walls | 9×9, 7 deep | staircase to y=24, 24-block gallery |
| City | up to 4 | 11×11, 10 deep | to y=12, 40-block gallery |
| City-State | up to 5 | 13×13, 13 deep | to y=-16, 64-block gallery |

- Builders cut down rises and fill hollows only under the building's footprint.
- The quarry sits off to the side of town, away from every lot and road. It's dug in layers with a staircase down one edge and fenced, with torches on the corners.
- Mines run from the bottom of the quarry. Miners light them, plug water and lava, bridge holes, and follow ore veins they uncover.
- Builders only ever reshape natural terrain, never anything a player built.

## Families

- Every colonist has a first name, a **surname** and three **traits**: ambition, aggression and kindness.
- Colonists who chat often become friends. Close friends in the same faction (not family) may **marry**.
- Married couples with a house and food to spare have **children**. Children inherit the family surname and a mix of their parents' traits. They play, follow their parents around and live in the family house.
- After one Minecraft day a child **grows up**, takes a job and builds a house of its own.
- Colonists live **25–30 in-game days** (`colonistsLifespanMinDays` / `colonistsLifespanMaxDays`), then die of old age. Their belongings go into the house chest, and a spouse or child without a house of their own inherits it.
- **Jobs** are given out by what the city needs: farmers, woodcutters (who replant saplings), miners, builders and guards. Guards patrol the streets and keep night watch in shifts.

## Politics

Once a minute, every city-state's politics updates:

- **Government.** Villages, towns and unwalled cities are ruled by a **Chief**. Only on becoming a **City-State** do they reform into a **Kingdom** (founding a dynasty) or a **Republic**, depending on the ruler's temperament.
- **Succession.** In a monarchy, power passes within the ruling house. A Republic holds **elections** every three days: candidates are the most ambitious and popular citizens, and every adult votes for the one closest to their own temperament. Players in a Republic can `/colony vote`.
- **Approval** depends on housing, food in the stores, war weariness, tribute and captive-taking (which kind citizens dislike). Low approval leads to **coups** (an aggressive plotter seizes the throne), **revolutions** (a Republic is declared), and **schisms** where an ambitious citizen leads followers away to found a rival city.
- **Rulers drive foreign policy.** Aggressive or ambitious rulers declare war on weaker neighbours, and kind rulers grow closer to each other. Exhausted sides make peace. A badly beaten faction **surrenders and becomes a tributary**, sending part of its stockpile to its overlord every half day, until it is strong enough to rise up for independence.
- **Laws.** A cruel, aggressive ruler may decree that the city takes **captives**. A kind ruler, or a deeply unpopular regime, abolishes it.
- The ruler's name tag shows a crown (♔). `/colony politics <faction>` shows everything.

## Empires and conquest

Factions play like a grand-strategy game:

- **Settlements.** A faction can own many villages, towns and cities. Each one grows and ranks up on its own, and has its own name, people, stores, fields and wall. The first one is the **capital** (★). A faction with 2+ settlements is a **Kingdom**; with 4+ and a City-State among them it is an **Empire**.
- **Colonies.** When a settlement has 12+ adults and 6+ houses, the faction sends out about five settlers every few days. The homeless go first, then a builder, a farmer and a guard, and couples travel together. They found a new settlement under the same flag 150–300 blocks away, on the best river or valley spot they can find.
- **War goals.** Wars are fought for land. Each faction at war picks the nearest enemy settlement as its objective and announces the march.
- **Armies.** Guards are always soldiers. In wartime, bold colonists who are not farmers or builders get conscripted. Soldiers fight soldiers (and hostile players). **Civilians are never targeted**, because after the conquest they will be your people.
- **Sieges.** An army that holds the enemy plaza with no defenders nearby takes the town after about 90 soldier-seconds. The town **changes hands intact**: houses, fields, stores and people all go to the conqueror. If the capital falls, the loser moves its capital elsewhere. A faction is wiped out only when its last town falls.
- **Treaties.** A war-weary loser with more than one town can buy peace by ceding the town under attack.
- **Loyalty and rebellion.** A conquered town starts at 20% loyalty, which recovers over time (faster with a garrison of guards). Unpopular rulers, sieges and distance from the capital (600+ blocks) erode it. Below 12% the town may revolt, either back to its old masters or into a new independent faction, and the war starts again.
- **Off-screen.** When no player is nearby, settlers and armies make their long journeys "off-screen" and arrive at their destination. Settlements stay loaded, so sieges and battles happen even when you are far away.
- **Map.** JourneyMap and Xaero's show every settlement with its population. They also show the realm it belongs to, whether it is the capital, its loyalty and whether it is under siege.

## Captives

In cities where captivity is law, guards raid nearby villager settlements and bring villagers back as **captives**. Iron golems defend their village. Captives keep their villager appearance and profession and do forced labour on the fields or plaza, with their produce going into the stockpile. Captives go free (turning back into the original villager, trades and all) when:

- a player releases them by right-clicking with an empty hand (their captors resent it, other cities approve),
- they escape while unwatched,
- the captives **revolt**, which happens when approval is low or guards are outnumbered,
- the city abolishes captivity, or
- their city falls.

## Factions and you

- Relations run from -100 to +100. At **-50** two cities are at **war**, and at **+50** they are **allies**. Changes are announced in chat.
- Right-click a colonist with an empty hand to see its job, spouse, traits, needs and inventory, and your standing.
- Gifts raise your standing; hitting or killing colonists lowers it. Join a faction at standing 40+ with `/colony join`, or found your own with `/colony found <name>` and recruit colonists by sneak-right-clicking them with an emerald. Founders can use `/colony war|peace|ally`.

## Off-screen life

Minecraft only runs mobs in loaded chunks. With `colonistsKeepCitiesLoaded` (on by default), the mod keeps the heart of every city loaded, so colonists keep working, building and raising families while you're far away. The area grows with rank: 3×3 chunks for a village, up to 9×9 for a city-state, plus the quarry. Colonists who wander beyond that area pause until a player comes near. Politics (elections, wars, tribute) runs everywhere regardless. Turn the rule off if many cities strain your server.

## Map mods (optional)

- **JourneyMap:** each city's territory is drawn on the minimap and fullscreen map, shaded in the faction's colour and labelled with its name and rank. Hover over it to see the ruler and your standing. Borders turn red at war and green for allies, and the outline follows the city wall once one is planned.
- **Xaero's Minimap:** a waypoint set called *Colonist cities* has one waypoint per city plaza, named with its rank and your relation, and kept up to date as cities change. (This uses Xaero's internals, so if a future Xaero version is incompatible, the integration switches itself off rather than crashing.)

Both are optional. The mod works the same without them. In multiplayer, players need Colonists installed on their client to see cities on their map.

## Commands

| Command | What it does |
|---|---|
| `/colony spawn [count] [faction]` | Spawn colonists at your position (op) |
| `/colony factions` | List factions, governments, rulers, population, relations and your standing |
| `/colony politics [faction]` | Settlement rank and wall progress, government, ruler, approval, jobs, laws, captives, tribute, next election |
| `/colony vote <citizen>` | Vote in your Republic's next election |
| `/colony found <name>` | Found your own faction |
| `/colony join <faction>` / `/colony leave` | Join or leave a faction |
| `/colony war\|peace\|ally <faction>` | Diplomacy (player-faction founders) |

You can also use the **Colonist Spawn Egg** in the Spawn Eggs creative tab.

## Game rules

| Rule | Default | |
|---|---|---|
| `colonistsBreakBlocks` | true | Allow chopping, mining and farming |
| `colonistsBuild` | true | Allow building houses and chests |
| `colonistsChatter` | true | Colonists talk in chat near you |
| `colonistsGrow` | true | Births and newcomers |
| `colonistsMaxPerFaction` | 0 | Population cap per city (0 = unlimited) |
| `colonistsLifespanMinDays` / `MaxDays` | 25 / 30 | Lifespan range in in-game days |
| `colonistsKeepCitiesLoaded` | true | Keep city cores loaded so they live on while you're away |

## Building the mod

You need **JDK 21 or newer** (JDK 25 recommended). Get it from https://adoptium.net.

```
cd colonists
./gradlew build          # Windows: gradlew.bat build
```

The mod jar is created at `build/libs/colonists-1.0.0.jar`. Copy it into `.minecraft/mods`, along with **Fabric API** for 1.21.1, and launch the game with the **Fabric Loader** for 1.21.1.

To test in a development client without installing anything: `./gradlew runClient`.

**No local setup?** Push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`) builds the jar on every push; download it from the run's *Artifacts*.

## Code map

- `ColonistsMod`: registration, game rules, commands, the diplomacy timer
- `entity/ColonistEntity`: the NPC: needs, inventory, combat, interaction, save data
- `entity/ColonistBrain`: virtual crafting table and inventory helpers
- `entity/Society`: births, newcomers and schisms
- `entity/CaptiveEntity`: captive villagers and forced labour
- `faction/City`: plaza layout, walls and terrain checks
- `faction/CityGrowth`: terrain-following streets and lot placement
- `build/*`: blueprints, rotation and materials
- `faction/Politics`: government, succession, elections, approval, coups, laws, wars, tribute
- `faction/Stockpile`: the shared city stores
- `ai/WorkGoal`: the planner that decides what to do next, by priority
- `ai/task/*`: Harvest, BuildHouse, Farm, PublicWorks, Stockpile, Raid, Patrol, Child, Migrate, Sleep, Shelter, Store, Socialize, CollectItems
- `faction/*`: factions, relations and player standing (saved with the world)
- `client/*`: colonists use the player model with the nine default skins; captives use the villager model
