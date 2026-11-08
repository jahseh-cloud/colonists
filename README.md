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
- **Streets.** Streets are pathfound across the terrain from the plaza. They avoid climbs of more than one block, bend around hills, and briefly bridge water.
- **Compact towns.** A town fills in before it spreads:
  - When it runs out of roadside space, it first branches short side streets off its inner streets.
  - It only pushes a street outward once the core is full.
  - It never grows past its **reach**: about 18 blocks from the plaza plus a little more for every building. A hamlet stays a cluster, and a city earns its suburbs.
- **Buildings** are placed along the streets, facing them, on the best roadside spot: close to the centre, level, near water, sheltered in a valley. They huddle beside their neighbours.
  - Hamlets keep a yard between houses.
  - Towns and cities build wall to wall.
- **Houses are built for need:**
  - **Hamlets:** cabins, with a cottage for a family.
  - **Towns and cities with room:** cottages for single people, and two-storey stone houses for families and the well-off.
  - **Crowded towns:** once land near the centre runs short, people build up instead of out: narrow two-storey **row houses** in towns and three-storey **townhouses** in cities.
  - **The rich, rulers and slave owners:** manors and estates.
- **Passive growth.** Towns grow on their own every day, whether or not anyone is nearby: newcomers from the countryside, and children of the town.
  - **Rate by the realm's era:** 2% a day in the Timber era, 3% in Stone, 4% in Iron, and 5% in the Grand era. A town of 100 in the Stone era is 103 the next day.
  - **Faster:** an Age of plenty, a Golden Age, or the Hanging Gardens.
  - **Slower:** war, hunger, or too few houses (newcomers wait until more are built).
  - **Off-screen:** a town no one has loaded counts its new people anyway. They wait at the gate and walk in when the town loads (counted in its population meanwhile).
  - The `colonistsMaxPerFaction` gamerule still caps everything.
- **Evening gatherings.** At dusk, townsfolk down tools and gather at the tavern, or the plaza if there isn't one. Everyone there grows a little fonder of everyone else, so a close-knit town makes friends, couples and children faster.
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
- **Fields.** A young town has up to two small common fields (9×9). Once it has manors, they feed it.

### Manors

Farming happens on **manors**: lords' estates beyond the edge of town.

- **Founding.** A town of 5 or more people supports one manor, and one more for every 10 people (up to 5). The richest farmer (or another wealthy citizen) becomes its lord, claims a first large field well beyond the houses, and builds a manor house beside it.
- **Land that grows.** The estate spreads the way a town does. Once its newest field is mostly sown, it clears another one beside it, with a cart track between. Fields are 16–24 blocks on a side, and an estate holds up to 2 fields plus one per laborer (8 at most). The land must be fairly level, dry and clear of trees, and streets go round the fields.
- **Crops for the land:**
  - **Plains and forests:** wheat, carrots, potatoes, beetroot and pumpkins.
  - **Jungle:** melons, plus sugar cane by water.
  - **Deserts, savannas and badlands:** pumpkins and melons, plus sugar cane by water.
  - **Taiga and snow:** potatoes, berries and beetroot.
  - **Swamps, rivers and beaches:** sugar cane and potatoes.
- **Irrigation.** Fields are laid out with irrigation channels: one every nine rows for grain and roots, and one every third row for cane. Gourd stems have room to fruit.
- **Laborers.** The town's farmers become laborers on its manors. They dig channels, till, sow, harvest and resow, and the harvest goes to the town's stores.
  - Free laborers earn 2 emeralds a day from the lord.
  - Bonded laborers and serfs work for their keep.
  - The lord sells the harvest to the town treasury.
- **Unwatched fields.** Crops keep growing even when no player is near, and manor fields stay loaded with their town.
- `/colony manors` shows the manors of the nearest town: lords, fields and crops, laborers, and harvests.
- **On the atlas.** Every manor's fields are drawn on the map in its realm's colour, labelled like *Smith Manor (Kingdom of Red Arrows)*. Labels show when you're zoomed in.
  - The **Farms** map mode colours each field by its crop, with a key.
  - Hover a field for the estate's lord, crops, laborers and harvest. Click it for its page.
  - A town's page lists its manors.

### The lords of the manors

Lords are the realm's landed nobility, and they have power:

- **Favour.** Each lord has a favour toward the crown (0–100) that drifts daily.
  - **Taxes** lower it: every rise in a town's tax costs favour, and every cut wins some back.
  - **Long wars** lower it.
  - **Cheap labor** (bonded folk and captives) and harsh laws raise it.
  - **A Republic** raises it, because lords sit in its senate.
  - **A big estate** raises it.
- **The court.** Where there are manors, the lords' favour weighs most in the court's opinion, and the court in turn weighs into every order.
- **No taxation without consent.** If the lords' favour falls below 40, they refuse any new tax rise.
- **Levies.** Content lords (favour 50+) send their laborers to the realm's wars, giving extra siege strength.
- **Votes.** In a Republic, each landowner casts three votes in elections.
- **The barons' revolt.** If the lords' favour in a realm of two or more towns stays below 20, the angriest lord may claim the crown. The towns that follow him rise in a civil war, the Pretender crisis with the barons behind it. You can back either side.
- **Winning them back.** A ruler can **Gift the lords** (20 emeralds from the treasury, in the atlas Realm panel), and cutting taxes helps too.
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

## The edge of the world

- **Size.** The world is **3072 × 3072 blocks**, from −1536 to +1536 on both axes. Change it in `config/colonists.json` with `"worldSize": 3072`; 0 makes it endless.
- **The edge.** Past the edge there is nothing: open **void**, like skyblock, with no wall. In the last 70 blocks or so the land **crumbles**: ground gives way to floating fragments of crust hanging over the void.
- **Other dimensions.** The Nether ends at an eighth of the distance (±192), and the End at the same distance as the overworld.
- **Colonists keep away.** They don't found towns, claim lots or march near the edge. The atlas shows the void beyond it.

## Ages and eras

**World ages.** The world moves through **milestone ages** in order: Founding, Kingdoms, Faith, Empires, Wonders. **Cycles** also swing back and forth on their own:

| Cycle | Swings toward one side when… | …or the other when… |
|---|---|---|
| **Golden ↔ Dark** | Peace, approval and full treasuries bring a Golden Age: more production, more children, more legends, better loyalty | Wars, conquests and fallen legends bring a Dark Age: the reverse, and prophets come sooner |
| **Faith ↔ Reason** | Prophets and holy wars bring an Age of Faith: zeal, and prophets sooner | Long years without prophets bring an Age of Reason: calmer rulers, and prophets later |
| **Strife ↔ Peace** | Many wars bring an Age of Strife | Few wars bring an Age of Peace |

- **Naming.** The world is called after all three, e.g. *"the Golden Age of Empires"* or *"the Dark Age of Strife"*.
- **Holding.** A cycle must hold for at least four days before it can turn again.
- **Where it shows.** Each age is announced and opens a new chapter in the chronicles. `/colony ages` shows the current age.

**Realm eras.** Each realm climbs through **Timber → Stone → Iron → Grand**:
- **Stone:** a Town.
- **Iron:** a City with a smithy.
- **Grand:** a City-State with a full treasury or a wonder.

Each era adds 5% production. Grand-era realms build in stone even for ordinary folk, and greater wonders need higher eras.

## Wonders

**Fourteen wonders, one of each per world.**

| Wonder | Effect |
|---|---|
| Great Cathedral | Faith spreads faster, prophets come sooner |
| Great Library | Legends come easier |
| Imperial Palace | Loyalty everywhere, court content |
| Great Forge | Soldiers shrug off blows |
| Mausoleum | Kin inherit legends' renown |
| Pantheon | No holy wars against it; faiths coexist |
| Grand Bazaar | Trade and newcomers |
| Observatory | Adventurers thrive |
| Colossus | Trade and production soar |
| Hanging Gardens | Children and happiness |
| Arena | Approval, heroes made faster |
| Great Wall | Its city takes three times longer to besiege |
| Grand Harbor | Colonies and caravans reach farther |
| Shrine of the Prophet | Pilgrimage, piety and conversion |

- **Who builds them.** Great realms (Cities in the Stone era or later) choose one that suits their character and faith, and race to build it.
- **How they're built.** Builders raise them **block by block**: 15–31 blocks across, up to 35 high, and over 3000 blocks for the Cathedral. It takes many days.
- **Who holds them.** Whoever holds the city holds the wonder.
- **Losing them.** A wonder that loses 40% of its blocks (to TNT, say) **falls into ruin** until it's repaired. A conqueror may **raze** it: Prophet Conquerors and holy warriors raze the temples of other faiths, and brutal rulers raze anything.
- **Finding them.** `/colony wonders` lists them. The atlas marks them and lists them under Lore.

## Banners

- **A banner of its own.** Every nation gets a random banner when it's founded: a field colour and two to four charges.
- **Where it flies.** It flies on every plaza (and beside every village bell) and is swapped when a town changes hands. Soldiers without a helmet wear it into battle.
- **Getting one.** Members can take one with `/colony banner`. The atlas shows each realm's banner.

## Faiths, legends and chronicles

**Religions**
- **Origins.** The world begins with a few old faiths. Every realm and every person holds one, and a realm converts when most of its people (and its ruler) have.
- **Tenets.** Each faith holds two of these, and opposed tenets never share a faith:

| Tenet | Effect |
|---|---|
| Expansion by the sword | Far more warlike, above all against unbelievers. Holy wars. |
| The way of peace | Rarely starts wars. |
| Poverty of spirit | No grand houses, less production, no slavery. |
| Blessed are the industrious | +25% production. |
| Mercy above judgement | No death penalty. |
| The law is sacred | Curfew and treason laws are required. |
| All are born free | No slavery. |
| The chosen people | Slavery is encouraged. |
| Be fruitful | Children come sooner. |
| The road is the way | Colonies twice as often, and adventurers come easier. |

- **Laws follow the faith.** AI rulers write their laws within it. A player ruler is refused any law the faith forbids.
- **Holy wars.** Realms of the sword wage them on unbelievers. Holy warriors tire slowly, take towns faster, and convert what they conquer.

**Legends** (player or colonist, built by deeds plus a roll of fortune)
- **Adventurer.** Earned by long journeys, founding colonies and discovering villages. Comes home with treasure.
- **Hero.** Earned by felling enemy soldiers and holding sieges. Nearby soldiers fight harder, and towns fall faster. Their death shakes their army.
- **Emperor.** Earned by ruling a true Empire and conquering towns.
- **Prophet.** The rarest. At most one per faith at a time, rising every 25–40 days. Players can be chosen too: piety from gifts and from freeing slaves improves the odds.
  - **Reform.** About a third of prophets **reform or split** their faith into a new one. One teaching is dropped and another taken up.
  - **Preaching.** Prophets spread their faith through every town within 400 blocks, utter **prophecies** that may come true, and work the occasional **miracle**.
  - **Prophet King.** A prophet who takes a throne (the faithful may crown them) founds a **Theocracy**. Faithful towns abroad may come over to them peacefully.
  - **Prophet Conqueror.** A warlike Prophet King proclaims a **Great Holy War**. Every realm of the faith joins in, their soldiers never tire, and sieges go much faster.

**Chronicles**
- **Writers.** Village librarians write **chronicles**: real written books, volume after volume.
- **Contents.** Each covers the region's events and the world's legends, told from the village's point of view. Friends and fellow believers are "the Great"; enemies are "the Butcher" and "the False Prophet"; heretics are named as such.
- **Getting them.** Buy them from the librarian, or read them on village lecterns.
- **Commands.** `/colony religions`, `/colony legends` and `/colony history` show the plain record. The atlas has a **Faith** map mode and a **Lore** panel.

## Crises

Every so often the world is shaken by a figure greater than any legend. Crises start on their own, are more likely in the right age, and at most three run at once. Operators can begin one with `/colony crises start <messenger|khan|imperator|great|pretender|revolt|golden|heretic>`. `/colony crises` lists past and present crises and broken empires.

- **The Messenger** (from day 15; three times likelier in an Age of Faith): the mightiest prophet. He founds a new faith with three teachings, always including the sword or the way of peace. His realm becomes a **Caliphate**. His word spreads 1000 blocks, and faithful towns of other realms defect to him. If his faith holds the sword, he wages great holy wars. After he dies, **Caliphs** succeed him one by one. By the third Caliph the faithful may quarrel: a **schism** splits off a rival faith and half of the realm.
- **The Khan** (from day 10; likelier in an Age of Strife): a **Horde** rides out of empty land with a dozen armoured riders, and more join every day, up to 40. It is always at war with its nearest neighbour, takes towns fast, and feels no war weariness. When the Khan dies, the horde breaks into Khanates. The player who kills him is hailed as **Bane of the Khan**, the highest title a player can earn.
- **The Great**: the ambitious ruler of a small nation declares war on the largest empire and conquers town after town. Conquered towns stay loyal to him. Within 10–16 days a fever takes him, and his generals divide his realm.
- **The Imperator** (a "Restitutor"): 5–15 days after a great realm breaks apart, the heir holding most of its towns swears to rebuild it. He wars on whoever holds the old towns and preaches his faith in every town he takes. Holding 80% of them is a **Restoration**.

- **The Pretender** (from day 8): in a large, unhappy realm, someone claims to be the true heir. Half its discontented towns rise for him, and the civil war is on. More towns may declare for him as it goes. If he takes the whole realm, he takes its name and throne. If he dies, his towns go back to the crown. After 20 days with no winner, an uneasy peace leaves the realm divided.
- **The Peasants' Revolt** (from day 6): in the most miserable realm (unloved rulers, heavy taxes, slavery), the common folk of a town rise up and found a **Free Commune**, a republic with no slaves and no gallows. Every slave in its towns, players included, is freed. The revolt can spread to other unhappy towns. If the commune lasts 15 days, it is free for good, and its old lords lower their taxes.
- **The Golden Age** (likelier in an Age of Prosperity): a good crisis. For 10–15 days a happy, peaceful realm under its Magnificent ruler earns 50% more, builds twice as fast and produces legends twice as often. It ends in a new era for the realm, unless war cuts it short.
- **The False Prophet** (only while a Messenger's faith is strong): a deceiver preaches a heresy of the great faith and leads his town into a breakaway sect. He tries to lure nearby faithful towns over. Every realm of the true faith wages holy war on him. Kill him for the **Hand of the Faith** title. If he survives 20 days, his heresy endures.

In a civil war or revolt, players can pick a side with `/colony crises back rebels` or `/colony crises back crown`. If your side wins, you get 50 emeralds in your bank and the **Kingmaker** title.

Chroniclers write about all of it: crises, fractures, restorations, Caliphs and schisms. Killing any crisis figure earns a player "Slayer of Legends".

## Free villages

- **Vanilla villages become small realms** of free men, discovered as players come near. Each gets a name ("the Free Men of Ashford"), borders drawn around its houses, and a population count on the maps. It has its own relations, and its trade adds to its treasury. Villagers are called *Free Man / Free Woman of …* when you look at them, and their trading and breeding are untouched.
- **Colonists live in villages.** Each village starts with 2–3 colonist guests and draws newcomers over time, up to about half as many as the villagers. Guests never build, pave or dig inside the village, because it's the villagers' home.
- **What the guests do, over time:**
  - **A — Seize the village.** An ambitious, aggressive leader with at least two followers takes over and proclaims a new faction, and the village's iron golems turn on them. A kind leader makes the villagers *subjects* who pay dues. A cruel one puts up to six of them in chains.
  - **B — Leave** to found a people of their own. An ambitious leader sets out with up to three companions.
  - **C — Stay loyal**, until something happens. **Grievances** push guests toward A or B: guests outnumbering villagers, raids on the village, and the death of a fellow guest.
- **Villages can be conquered.** Factions can declare war on free villages and take them by siege like any town. A conquered village keeps its villagers as subjects, and their dues go into its new owner's treasury. Raiding a free village for captives sours relations with it, and colonists never raid their own subjects.

## Empires and conquest

Factions play like a grand-strategy game:

- **Settlements.** A faction can own many villages, towns and cities. Each one grows and ranks up on its own, and has its own name, people, stores, fields and wall. The first one is the **capital** (★). A faction with 2+ settlements is a **Kingdom**; with 4+ and a City-State among them it is an **Empire**.
- **Colonies.** When a settlement has 12+ adults and 6+ houses, the faction sends out about five settlers every few days. The homeless go first, then a builder, a farmer and a guard, and couples travel together. They found a new settlement under the same flag 50–300 blocks away (towns may grow until their borders touch), on the best river or valley spot they can find.
- **War goals.** Wars are fought for land. Each faction at war picks the nearest enemy settlement as its objective and announces the march.
- **Armies.** Guards are always soldiers. In wartime, bold colonists who are not farmers or builders get conscripted. Soldiers fight soldiers (and hostile players). **Civilians are never targeted**, because after the conquest they will be your people.
- **Sieges.** An army that holds the enemy plaza with no defenders nearby takes the town after about 240 soldier-seconds (a few minutes for a small army; heroes and holy warriors are faster). The town **changes hands intact**: houses, fields, stores and people all go to the conqueror. If the capital falls, the loser moves its capital elsewhere. A faction is wiped out only when its last town falls.
- **Treaties.** A war-weary loser with more than one town can buy peace by ceding the town under attack.
- **Loyalty and rebellion.** A conquered town starts at 20% loyalty, which recovers over time (faster with a garrison of guards). Unpopular rulers, sieges and distance from the capital (600+ blocks) erode it. Below 12% the town may revolt, either back to its old masters or into a new independent faction, and the war starts again.
- **Off-screen.** When no player is nearby, settlers and armies make their long journeys "off-screen" and arrive at their destination. Settlements stay loaded, so sieges and battles happen even when you are far away.
- **Map.** JourneyMap and Xaero's show every settlement with its population. They also show the realm it belongs to, whether it is the capital, its loyalty and whether it is under siege.

## The political atlas

Press **G**, or right-click a **Political Atlas** (crafted from a book, a map and an emerald), to open a parchment map of the world.

**Reading the map**
- **Terrain** is sketched straight from the world generator, so you can see continents and oceans you haven't explored. Drawing it loads no chunks.
- **Borders** are shown for every town, with capitals starred. Dotted lines show armies marching to their war goals, and a flag marks a town under siege.
- **Map modes:** Realms, Relations (your diplomacy in colour), Wealth (treasuries), Loyalty and Faith.
- **Hover** a town for a summary. **Click** it for details. **Drag** to pan and **scroll** to zoom.
- **Layout.** Map modes are tabs along the top, and a key for the current mode sits at the bottom left beside the scale bar. The sidebar on the right has tabs for Realm, Laws, Nations and Lore.
  - Its page scrolls (scroll with the mouse over it), and standings are drawn as bars.
  - Your orders sit in their own area at the foot of the sidebar, in two columns when there are many, so nothing overlaps.

**Ruling.** Everyone can read the atlas, but only a ruler can give orders. There are four ways to take power:
1. **Found** a faction (`/colony found`).
2. **Win an election** in a Republic you belong to. Click *Stand for election*; your standing and the senate's mood decide the vote.
3. **Stage a coup** in a faction you belong to. This needs 50+ standing, and your odds go up the more the current ruler is disliked. Fail, and you're wanted for treason.
4. **Inherit the throne.** A kind monarch names a trusted member with 80+ standing as heir.

**What a ruler can order**
- **In your own towns:**
  - Raise or lower taxes.
  - Set the size of the garrison.
  - Grant autonomy: lower taxes and faster loyalty, but the town ignores your orders.
  - Pay 25 emeralds to fund a building that gets built first, including a wall.
  - Choose which town sends settlers, then click open land to found a colony there.
  - Cede a town to buy peace.
- **Toward foreign towns:**
  - Declare war and set that town as your armies' goal.
  - Propose an alliance, or demand that they pay you tribute.
  - Offer peace: a white peace, taking that town, or making them a vassal. The enemy accepts if they're losing or tired of war.
- **Realm and Laws panels:**
  - Change your form of government.
  - Pay the army a bonus.
  - Toggle each law, including captivity.
  - Abdicate.

**Orders need support.** Every order is weighed against the **army**, the **court** (nobles, council or senate) and the **people**. Your government decides how much each counts: the army matters most in a kingdom, the senate in a republic, and the army and the people in a chiefdom. Popular orders always go through. Unpopular ones may be refused, and you'll be told how much support they had.
- The **army** wants its pay, enjoys victories (if it's aggressive), and tires of long wars. Bonuses help.
- The **court** dislikes high taxes, an empty treasury, and (if it's kind) harsh laws.
- **Losing them is dangerous.** If the army's opinion drops below 20, a military coup becomes likely. If the people and the court both turn on you, expect an uprising. In a Republic, you can simply be voted out.

## Law and order

- **Law books.** Every faction has laws that its ruler writes and rewrites. Everyone bans theft, vandalism, assault and murder. Towns and stricter rulers add poaching. Aggressive rulers and monarchies add a night-time curfew in walled cities and treason. A cruel, aggressive ruler brings in the **death penalty**, and only a kind ruler abolishes it. `/colony laws` shows the current book.
- **Crime needs a witness.** If a colonist sees you, it counts. That covers taking from chests or fields, breaking buildings, hitting or killing citizens, or killing their livestock. You become **wanted** with a "heat" level. Unseen crimes go unpunished. Colonists commit crimes too: poor, hungry, unkind ones steal from their neighbours' larders, and hotheads start brawls.
- **Guards arrest, they don't kill.** They subdue the wanted, beating them down to a few hearts, then drag them to justice. While wanted, you can:
  - **pay the fine** by right-clicking a guard holding emeralds,
  - **try a bribe** by sneak-right-clicking with emeralds (greedy guards may take it, honest ones add a charge),
  - **surrender** by sneak-right-clicking with an empty hand (a lighter sentence),
  - or **run**. Heat fades after a day and a half.
- **Sentences.** Small crimes get a fine. Larger ones mean **prison**: a barred cell where you serve your time. Guards who see you outside your cell put you back and add time. Get 48 blocks away unseen and you've escaped, which makes you wanted again. At 3+ heat in a city with a **work camp**, you mine a quota of stone inside the fence instead. Murder and treason mean the **gallows** when there is a death penalty. Under captivity laws with a harsh ruler, the sentence is **slavery**. With no prison and no money, the sentence is **exile**: guards kill you on sight in that faction's towns for five days.
- **New buildings:** prison (towns with 8+ people), gallows (when the death penalty is law), work camp (cities).
- `/colony wanted` shows where you're wanted and for what.

## Economy

- **Emeralds.** Every colonist has a purse, and every town has a treasury.
- **Daily payday:**
  - **Production:** the town earns from its work, plus a bonus if it has a market.
  - **Taxes** on wealth: 10% in a chiefdom, 15% in a monarchy (nobles exempt), 8% in a republic.
  - **Wages:** 1–4 emeralds by trade, more for the ruler and nobles, none for slaves.
  - **An empty treasury** leaves workers unpaid, so approval drops and war weariness rises.
- **Classes.** Poor (under 8), Common, Rich (60+). The rich build stone houses and manors. Slave owners build **estates**. Rich citizens buy slaves and gain influence in republics. The poor are the ones who steal.
- **Prices follow supply.** Plenty is cheap, scarcity is dear, and food costs more in wartime. `/colony market` shows prices and the treasury of the town you're standing in.
- **Merchants** work the market stall. Right-click one to trade: the town sells its surpluses and buys what it lacks.
- **Caravans** move food, timber, stone and iron between towns of the same realm and between allies, paid from the treasuries.
- **Your own shop.** Put goods in a chest inside a town, look at it, and run `/colony shop <price> <batch>`. The town buys what it's short of, if your price is within 25% of the market rate, and pays emeralds into the chest. `/colony shop remove` closes it.
- **Money moves around.** Vassals pay a tenth of their treasury as tribute. Wealth is inherited, and a conquered town's treasury goes to the conqueror. You can gift emeralds to any colonist.

## Slavery

- A convict sentenced to slavery goes on the **auction block** at the town market for one minute. The town's rich bid, and players can bid with `/colony bid <emeralds>`. With no buyer, the convict belongs to the town.
- **An enslaved player** is bound to within 48 blocks (about three chunks) of their owner's estate. They can only break blocks there and can't hurt their owner. Each day they get a **task** (timber, the harvest, stone, coal, iron) to hand in at the estate. Missing one brings a whipping and slowness. Straying past the line brings a warning, and then they're a **runaway** that guards hunt. A caught runaway is dragged back, and their price goes up.
- **Ways to freedom:**
  - buy it with `/colony freedom buy`, or someone else buys it with `/colony free <player>`,
  - your owner dies and their will frees you (kind owners do this),
  - the faction abolishes captivity,
  - or you escape to a town that forbids slavery, which grants **refuge** and sours relations between the two factions.
- Players who buy slaves set where they're bound with `/colony estate`. The `colonistsEnslavePlayers` gamerule turns player slavery off.

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
| `colonistsEnslavePlayers` | `true` | Convicted players can be sold into slavery |
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

## Your own life

**Renown and titles.** Your deeds earn titles, and the world calls you by your grandest. Examples:
- **From fighting:** *the Soldier*, *Warlord*, *Bane of the Iron Badgers*.
- **From getting around:** *the Explorer*, *Champion of the Realms*, *Sellsword*.
- **From conscience (or the lack of it):** *the Oathbreaker*, *the Liberator*, *the Murderer*.
- **From faith and land:** *the Pilgrim*, *Merchant Prince*, *Lord of a Retinue*.
- **From power:** *Kingbreaker*, or your ruling title.

Colonists greet you by name and title, warmly or coldly as you deserve. `/colony me` shows your renown card. `/colony deeds` gives you a **Book of Deeds** that writes itself as you live.

**Contracts.** Every town keeps a notice board (`/colony contracts`, then `/colony contract accept <n>`). You can hold three at a time, and they pay from the town's treasury along with standing and renown:

| Contract | What to do |
|---|---|
| Supply | Bring goods the town lacks to its plaza |
| Clear the wilds | Slay monsters near the town |
| Bounty | Bring down a wanted outlaw |
| War service | Kill soldiers of the town's enemy |
| Mercenary | Fight for a realm for three days. Desert, or turn on your employers, and you're an Oathbreaker. |
| Courier | Carry a letter to a distant town |
| Espionage | Watch a rival's plaza unseen for 30 seconds (sneak). A guard who spots you ends it and reports you. |
| Assassination | Kill a rival realm's ruler |
| Stone for the wonder | Bring building stone for a wonder |

**Retinue.**
- **Hiring:** `/colony hire` the colonist you're looking at, for 15 emeralds per 5 days. You need standing 30 with their people. You can lead 2 retainers, plus one for every 3 titles, up to 6.
- **What they do:** retainers follow you, catch up if left behind, and fight whoever fights you. They gain levels (more health and damage) as they kill.
- **Orders:** `/colony retinue follow|stay|dismiss`.

**Property.**
- **Buying:** stand on an empty house or a workshop (tavern, smithy, bakery, market, barn, pen, barracks) and `/colony property buy`. Houses cost 15–90 emeralds by size; workshops cost 60–90.
- **Income:** workshops pay you a share of the town's takings every day. `/colony property rent` lets a house to a colonist tenant for rent. Collect with `/colony collect`.
- **Your own stuff:** you can't be charged with theft or vandalism on your own property.
- **Seizure:** a realm that goes to war with you seizes what you own there.

**Marriage.**
- **Proposing:** `/colony propose` to the colonist you're looking at. You need standing 50, and a little luck.
- **Children:** when you're near your spouse, the two of you have children who carry your name. When they grow up, they swear to follow you for life.

**Faith.**
- **Choosing a faith:** `/colony faith join <faith>`. Fellow believers' realms warm to you every day.
- **Prayer:** `/colony pray` once a day at a Great Cathedral, a Shrine of the Prophet, the Pantheon, or your prophet's town. It grants piety, regeneration and luck.
- **Prophecy:** piety improves your chance of being chosen as your faith's next prophet.

## Self-test

Every push also runs a headless server on CI (`./gradlew runSelftest`, which sets `-Dcolonists.selftest=true`). It builds two colonist peoples and a village, then fast-forwards five in-game days. Along the way it forces:
- a war, a crime and an arrest, and the arrival of prophets;
- a round-trip of every save file;
- market offers, atlas terrain and chronicles;
- a fake player ruling a realm and issuing every kind of order.

It finishes by checking the world for inconsistencies. Any exception fails the run. The log ends with a summary and the full history of what happened.

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

## Siegeworks (companion mod)

[Siegeworks](siegeworks/README.md) is a separate mod built from this repo: ballistae, catapults, battering rams, siege towers, carts and Naphtha, all crewed by hand. It works on its own. With both installed, Colonists armies bring up and crew siege engines in their sieges.
Download: https://github.com/jahseh-cloud/colonists/releases/download/siegeworks-latest/siegeworks-1.0.0.jar
