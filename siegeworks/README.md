# Siegeworks

Medieval siege engines and vehicles for Minecraft 1.21.1 (Fabric). Every engine is crewed by hand: load it, wind it crank by crank, aim, and release.

**Download:** https://github.com/jahseh-cloud/colonists/releases/download/siegeworks-latest/siegeworks-1.0.0.jar (needs Fabric API).

## Working an engine

| Action | How |
|---|---|
| Place an engine | Use its item on the ground. It faces the way you look. |
| Get on | Use the engine. |
| Load / turn the crank (from the ground) | Sneak and use the engine. Hold ammunition to load it. |
| Load / turn the crank (while riding) | **G** (rebindable). Hold ammunition to load it. |
| Release (fire, swing, cross) | **R** (rebindable), while riding. |
| Aim | Look around while riding. Turret engines swing slowly to follow you. |
| Tow it | Ride a horse and use the engine to hitch or unhitch it. Heavy engines slow the horse. |
| Push it | Walk into it. Heavy engines barely move. |
| Get off | Sneak. |

Winding takes work: each turn of the crank makes you a little hungrier. While you ride, a status line shows what is loaded and how far it is wound.

## The engines

- **Ballista:** a giant crossbow. Load a bolt and wind it four turns. Bolts pierce four bodies, hit harder the further the ballista is wound, and smash through glass and leaves.
- **Catapult:** load a catapult stone, plain cobblestone (weaker) or a Naphtha pot, and wind it six turns. Your look sets the angle: look level for a low, flat shot or up for a high lob (15–70°). The winding sets the force (range of about 70 blocks). Stones crater what they hit. Stronger blocks are less likely to break, and obsidian never does.
- **Battering ram:** a covered ram on wheels. Drive it with WASD, crank it back two turns, then swing. Each hit wears down the blocks in front of the iron head. Gates, doors and wood give way first, stone takes longer, and obsidian never breaks. Up to three more crew can sit inside, and each adds weight to the blow. The roof shelters the crew from arrows.
- **Siege tower:** taller than most walls. One driver at the bottom and five crew on the top floor, all safe from arrows (the crew can still shoot out). At the wall, crank the drawbridge down and press **R** to leap across onto the wall.
- **Cart:** 27 slots of cargo and two seats. Pull it by hand or hitch it to a horse. Sneak and use it, or press **G** while riding, to open it.

Engines are wooden. They break under axes, fire and catapult stones, and they drop themselves when destroyed.

## Naphtha

A clay pot of fire that water can't put out. Throw it or fire it from a catapult. It burns on the ground, on roofs and on the surface of water. Water won't flow into it or wash it away, and a water bucket does nothing. It sets fire to everything flammable nearby and creeps across water. To put it out, use sand, red sand or dirt on it, place a block over it, or wait for it to burn out (about 30 seconds).

## Crafting

| Item | Recipe |
|---|---|
| Ballista | crossbow; planks, iron ingot, planks; logs at the bottom corners |
| Catapult | log (top right); string, log, string; planks, iron ingot, planks |
| Battering ram | 3 wool; log, log, iron block; planks at the bottom corners |
| Siege tower | planks, ladder, planks (×2 rows); log, chain, log |
| Cart | planks, _, planks; planks, chest, planks; logs at the bottom corners |
| Ballista bolt ×4 | iron ingot, stick, feather (stacked) |
| Catapult stone ×2 | 4 cobblestone |
| Naphtha pot ×2 | flower pot + coal + gunpowder + string |

## Gamerule

`/gamerule siegeworksBlockDamage false` stops siege weapons from breaking blocks. They still hurt bodies and other engines.

## AI crews and Colonists

Any mob tagged `siegeworks_crew` that stands next to an engine with an `AutoTarget` (an `[I; x, y, z]` int array in its NBT) works the engine on its own:
- Ballistae and catapults load, wind, aim and fire.
- Rams roll forward and batter whatever stops them.
- Towers roll up and lower the drawbridge.

With **Colonists** installed, armies on campaign bring up rams, catapults, ballistae and siege towers (at most two per siege), crew them, and work them against the town. A crewed engine speeds up the siege. When the town falls, its engines stand down and stay on the field, ready for a player to take.
