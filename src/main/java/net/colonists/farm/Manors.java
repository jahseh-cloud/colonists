package net.colonists.farm;

import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.build.Site;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.CityGrowth;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.farm.Manor.Field;
import net.colonists.world.WorldEdge;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manors: lords' farming estates on the edge of town. A well-off farmer founds one, claims a large field beyond the
 * houses, and from then on the estate grows like a town does, field by field, with crops to suit the land. The town's
 * farmers work its fields as laborers (paid by the lord, unless they are bonded); the harvest goes to the town's stores,
 * and the lord sells it to the town for emeralds.
 */
public final class Manors {
	private Manors() {}

	// ---------------------------------------------------------------- crops and the layout of a field

	public enum Layout { ROWS, GOURD, CANE, BERRY }

	public enum Crop {
		WHEAT("wheat", Layout.ROWS), CARROT("carrots", Layout.ROWS), POTATO("potatoes", Layout.ROWS), BEETROOT("beetroot", Layout.ROWS),
		MELON("melons", Layout.GOURD), PUMPKIN("pumpkins", Layout.GOURD), SUGAR_CANE("sugar cane", Layout.CANE), SWEET_BERRIES("berries", Layout.BERRY);

		public final String label;
		public final Layout layout;

		Crop(String label, Layout layout) {
			this.label = label;
			this.layout = layout;
		}

		public static Crop of(String id) {
			try {
				return valueOf(id.toUpperCase());
			} catch (IllegalArgumentException e) {
				return WHEAT;
			}
		}

		public BlockState planted() {
			return switch (this) {
				case WHEAT -> Blocks.WHEAT.getDefaultState();
				case CARROT -> Blocks.CARROTS.getDefaultState();
				case POTATO -> Blocks.POTATOES.getDefaultState();
				case BEETROOT -> Blocks.BEETROOTS.getDefaultState();
				case MELON -> Blocks.MELON_STEM.getDefaultState();
				case PUMPKIN -> Blocks.PUMPKIN_STEM.getDefaultState();
				case SUGAR_CANE -> Blocks.SUGAR_CANE.getDefaultState();
				case SWEET_BERRIES -> Blocks.SWEET_BERRY_BUSH.getDefaultState();
			};
		}

		@Nullable
		public Block fruit() {
			return this == MELON ? Blocks.MELON : this == PUMPKIN ? Blocks.PUMPKIN : null;
		}
	}

	public enum Role { CHANNEL, CROP, EMPTY }

	/** What a spot in a field is for: an irrigation channel, a crop, or the space a gourd grows into. */
	public static Role role(Field f, int x, int z) {
		int dx = x - f.x0, dz = z - f.z0;
		return switch (Crop.of(f.crop).layout) {
			case ROWS -> dz % 9 == 4 ? Role.CHANNEL : Role.CROP;
			case GOURD -> dz % 9 == 4 ? Role.CHANNEL : dx % 3 == 0 ? Role.CROP : Role.EMPTY;
			case CANE -> dz % 3 == 1 ? Role.CHANNEL : Role.CROP;
			case BERRY -> dz % 2 == 0 ? Role.CROP : Role.EMPTY;
		};
	}

	/** The working height of a column: where a crop stands (skipping fruit, and the like, lying on top). */
	public static int columnY(World world, int x, int z) {
		int y = City.surfaceY(world, x, z);
		for (int i = 0; i < 3; i++) {
			BlockState below = world.getBlockState(new BlockPos(x, y - 1, z));
			if (below.isOf(Blocks.MELON) || below.isOf(Blocks.PUMPKIN) || below.isOf(Blocks.SUGAR_CANE)) y--;
			else break;
		}
		return y;
	}

	/** Crops that suit the land around a spot. */
	public static List<Crop> cropsFor(ServerWorld world, BlockPos at) {
		RegistryEntry<Biome> b = world.getBiome(at);
		String id = b.getKey().map(k -> k.getValue().getPath()).orElse("plains");
		boolean water = CityGrowthAccess.waterNearby(world, at.getX(), at.getZ(), 12) > 2;
		List<Crop> list = new ArrayList<>();
		if (b.isIn(BiomeTags.IS_JUNGLE)) {
			list.add(Crop.MELON);
			list.add(Crop.MELON);
			if (water) list.add(Crop.SUGAR_CANE);
			list.add(Crop.POTATO);
		} else if (id.contains("desert") || b.isIn(BiomeTags.IS_BADLANDS) || b.isIn(BiomeTags.IS_SAVANNA)) {
			list.add(Crop.PUMPKIN);
			list.add(Crop.MELON);
			if (water) list.add(Crop.SUGAR_CANE);
			list.add(Crop.WHEAT);
		} else if (b.isIn(BiomeTags.IS_TAIGA) || id.contains("snow") || id.contains("grove") || id.contains("frozen")) {
			list.add(Crop.POTATO);
			list.add(Crop.POTATO);
			list.add(Crop.SWEET_BERRIES);
			list.add(Crop.BEETROOT);
		} else if (id.contains("swamp") || b.isIn(BiomeTags.IS_RIVER) || b.isIn(BiomeTags.IS_BEACH)) {
			list.add(Crop.SUGAR_CANE);
			list.add(Crop.POTATO);
			list.add(Crop.WHEAT);
		} else {
			list.add(Crop.WHEAT);
			list.add(Crop.WHEAT);
			list.add(Crop.CARROT);
			list.add(Crop.POTATO);
			list.add(Crop.BEETROOT);
			list.add(Crop.PUMPKIN);
		}
		return list;
	}

	// ---------------------------------------------------------------- lookups

	@Nullable
	public static Manor of(Settlement s, UUID who) {
		for (Manor m : s.manors) if (who.equals(m.lord) || m.laborers.contains(who)) return m;
		return null;
	}

	@Nullable
	public static Manor at(FactionManager fm, int x, int z) {
		for (Settlement s : fm.allSettlements()) for (Manor m : s.manors) if (m.fieldAt(x, z) != null) return m;
		return null;
	}

	/** True if a column is inside one of this town's manor fields (streets go round them). */
	public static boolean onLand(Settlement s, int x, int z) {
		for (Manor m : s.manors) for (Field f : m.fields) if (f.contains(x, z, 0)) return true;
		return false;
	}

	/** True if a column is inside any manor's fields (with a margin). */
	public static boolean onManorLand(FactionManager fm, int x, int z, int margin) {
		for (Settlement s : fm.allSettlements()) for (Manor m : s.manors) for (Field f : m.fields) if (f.contains(x, z, margin)) return true;
		return false;
	}

	// ---------------------------------------------------------------- the estate year (once a minute)

	public static void tick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		FactionManager fm = FactionManager.get(server);
		long day = world.getTimeOfDay() / 24000L;
		boolean changed = false;
		for (Settlement s : fm.allSettlements()) {
			if (s.village || s.cityCenter == null || s.faction == null || s.faction.freeVillage) continue;
			if (!world.isChunkLoaded(s.cityCenter.getX() >> 4, s.cityCenter.getZ() >> 4)) continue;
			changed |= foundManors(world, fm, s, world.getRandom());
			for (Manor m : s.manors) {
				changed |= tend(world, fm, s, m, day, world.getRandom());
			}
			changed |= assignLaborers(s);
		}
		if (world.getTimeOfDay() % 24000L < 1300) for (Faction f : fm.all()) if (!f.freeVillage) baronsRevolt(server, f, world.getRandom());
		if (changed) fm.markDirty();
	}

	/** How many manors a town can support. */
	public static int capacity(Settlement s) {
		int pop = s.population();
		return pop < 5 ? 0 : Math.min(5, 1 + pop / 10);
	}

	static boolean foundManors(ServerWorld world, FactionManager fm, Settlement s, Random random) {
		if (s.manors.size() >= capacity(s) || random.nextInt(3) != 0) return false;
		Faction f = s.faction;
		UUID lord = null;
		int best = Integer.MIN_VALUE;
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			Faction.Citizen c = e.getValue();
			if (c.child || c.bonded || c.settlement != s.id || e.getKey().equals(f.ruler)) continue;
			if (isLord(s, e.getKey())) continue;
			boolean farmer = "FARMER".equals(c.job);
			if (!farmer && c.wealth < 40) continue;
			int score = c.wealth + c.ambition / 2 + (farmer ? 30 : 0);
			if (score > best) {
				best = score;
				lord = e.getKey();
			}
		}
		if (lord == null) return false;
		Faction.Citizen c = f.citizens.get(lord);
		Manor m = new Manor();
		m.id = s.manors.stream().mapToInt(x -> x.id).max().orElse(0) + 1;
		m.lord = lord;
		m.lordName = c.fullName();
		m.name = c.surname + " Manor";
		m.banner = net.colonists.faction.Banners.random(random);
		m.founded = world.getTimeOfDay() / 24000L;
		Field first = claimField(world, fm, s, m, random);
		if (first == null) return false;
		m.fields.add(first);
		s.manors.add(m);
		FactionManager.broadcastNear(world, s.cityCenter, 160, Text.literal("🌾 " + c.fullName() + " founds " + m.title() + " outside " + s.name
				+ ", with a first field of " + Crop.of(first.crop).label + " (" + first.width() + "×" + first.depth() + ").").formatted(Formatting.GOLD));
		return true;
	}

	static boolean isLord(Settlement s, UUID who) {
		for (Manor m : s.manors) if (who.equals(m.lord)) return true;
		return false;
	}

	/** The town's farmers become laborers on its manors (the least-manned estate first); leavers are struck off. */
	static boolean assignLaborers(Settlement s) {
		if (s.manors.isEmpty()) return false;
		boolean changed = false;
		Faction f = s.faction;
		for (Manor m : s.manors) {
			changed |= m.laborers.removeIf(u -> {
				Faction.Citizen c = f.citizens.get(u);
				return c == null || c.settlement != s.id || !"FARMER".equals(c.job) || u.equals(m.lord);
			});
		}
		for (Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			Faction.Citizen c = e.getValue();
			if (c.child || c.settlement != s.id || !"FARMER".equals(c.job) || of(s, e.getKey()) != null) continue;
			Manor least = null;
			for (Manor m : s.manors) if (least == null || m.laborers.size() < least.laborers.size()) least = m;
			least.laborers.add(e.getKey());
			changed = true;
		}
		return changed;
	}

	static boolean tend(ServerWorld world, FactionManager fm, Settlement s, Manor m, long day, Random random) {
		boolean changed = false;
		Faction f = s.faction;
		// A lord who died or left: the estate passes to its richest laborer (or lies fallow).
		if (m.lord == null || !f.citizens.containsKey(m.lord) || f.citizens.get(m.lord).settlement != s.id) {
			UUID heir = null;
			int best = -1;
			for (UUID u : m.laborers) {
				Faction.Citizen c = f.citizens.get(u);
				if (c != null && !c.bonded && c.wealth > best) {
					best = c.wealth;
					heir = u;
				}
			}
			String oldFamily = m.lordName.isEmpty() ? "" : m.title();
			m.lord = heir;
			m.lordName = heir == null ? "" : f.citizens.get(heir).fullName();
			if (heir != null && !m.title().equals(oldFamily)) m.banner = net.colonists.faction.Banners.random(random);
			if (heir != null) m.laborers.remove(heir);
			changed = true;
		}
		if (m.banner.isEmpty()) {
			m.banner = net.colonists.faction.Banners.random(random);
			changed = true;
		}
		Faction.Citizen lord = m.lord == null ? null : f.citizens.get(m.lord);
		// Payday: wages to free laborers, and the harvest sold to the town.
		if (lord != null && day != m.lastPayday) {
			m.lastPayday = day;
			for (UUID u : m.laborers) {
				Faction.Citizen c = f.citizens.get(u);
				if (c == null || c.bonded) continue; // serfs and the bonded work for their keep
				if (lord.wealth >= 2) {
					lord.wealth -= 2;
					c.wealth += 2;
				}
			}
			m.favour = MathHelper.clamp(m.favour + (favourTarget(s, m) - m.favour) / 4, 0, 100);
			int sale = Math.min(s.treasury, m.harvested / 8);
			s.treasury -= sale;
			lord.wealth += sale;
			m.harvested = 0;
			syncPurse(world, m.lord, lord.wealth);
			changed = true;
		}
		// The manor house.
		if (m.seat == null && !m.fields.isEmpty() && random.nextInt(2) == 0) changed |= placeSeat(world, fm, s, m, random);
		if (m.seat != null && m.lord != null && world.getEntity(m.lord) instanceof ColonistEntity lordEntity && !m.seat.equals(lordEntity.home)) {
			if (s.built.contains(m.seat)) {
				// The hall stands (raised by the laborers, perhaps): the lord moves in, and his old house goes to someone in need.
				if (lordEntity.home != null) s.homes.put(lordEntity.home, null);
				lordEntity.home = m.seat;
				lordEntity.bed = null;
				lordEntity.chest = null;
				if (m.seat.equals(lordEntity.buildSite)) lordEntity.buildSite = null;
				fm.claimHome(s, m.seat, m.lord);
				lordEntity.say("At last, " + m.title() + " is finished. Welcome to my hall!");
				changed = true;
			} else if (lordEntity.buildSite == null) {
				lordEntity.buildSite = m.seat;
				fm.claimHome(s, m.seat, m.lord);
				lordEntity.say("Time I lived on my own land. I'm building " + m.title() + ".");
			}
		}
		// A barn and laborers' cottages beside the house; the family's arms over the house and every field.
		if (m.seat != null && m.outbuildings.size() < 3 && random.nextInt(2) == 0) changed |= placeOutbuilding(world, fm, s, m, random);
		for (BlockPos ob : m.outbuildings) {
			if (s.built.contains(ob) && !"barn".equals(s.buildingTypes.get(ob)) && !s.homes.containsKey(ob)) {
				s.homes.put(ob, null); // a cottage for a laborer
				changed = true;
			}
		}
		if (!m.banner.isEmpty()) flyArms(world, s, m);
		// The land grows when the fields are worked.
		int cap = Math.min(10, 3 + m.laborers.size());
		if (m.fields.size() < cap && !m.laborers.isEmpty() && planted(world, m.fields.get(m.fields.size() - 1), random) >= 0.6) {
			Field next = claimField(world, fm, s, m, random);
			if (next != null) {
				m.fields.add(next);
				changed = true;
				FactionManager.broadcastNear(world, next.center(), 96, Text.literal("🌾 " + m.name + " clears a new field for " + Crop.of(next.crop).label
						+ " (" + next.width() + "×" + next.depth() + ").").formatted(Formatting.GREEN));
			}
		}
		// Crops keep growing while nobody is near enough to see them (random ticks need players close by).
		for (Field fld : m.fields) catchUp(world, fld, random);
		return changed;
	}

	static void syncPurse(ServerWorld world, @Nullable UUID who, int wealth) {
		if (who != null && world.getEntity(who) instanceof ColonistEntity c) c.purse = wealth;
	}

	/** The share of a field's crop spots with something growing in them (sampled). */
	public static double planted(ServerWorld world, Field fld, Random random) {
		int crops = 0, growing = 0;
		for (int i = 0; i < 48; i++) {
			int x = fld.x0 + random.nextInt(fld.width()), z = fld.z0 + random.nextInt(fld.depth());
			if (role(fld, x, z) != Role.CROP || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
			crops++;
			BlockState s = world.getBlockState(new BlockPos(x, columnY(world, x, z), z));
			if (s.getBlock() instanceof CropBlock || s.getBlock() instanceof StemBlock || s.isOf(Blocks.SUGAR_CANE) || s.isOf(Blocks.SWEET_BERRY_BUSH)) growing++;
		}
		return crops == 0 ? 0 : growing / (double) crops;
	}

	static void catchUp(ServerWorld world, Field fld, Random random) {
		BlockPos c = fld.center();
		if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) return;
		for (PlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(c.getX(), p.getY(), c.getZ()) < 140 * 140) return;
		int samples = fld.area() / 10;
		Crop crop = Crop.of(fld.crop);
		for (int i = 0; i < samples; i++) {
			int x = fld.x0 + random.nextInt(fld.width()), z = fld.z0 + random.nextInt(fld.depth());
			if (!world.isChunkLoaded(x >> 4, z >> 4) || role(fld, x, z) != Role.CROP) continue;
			BlockPos pos = new BlockPos(x, columnY(world, x, z), z);
			BlockState s = world.getBlockState(pos);
			if (s.getBlock() instanceof CropBlock cb) {
				if (!cb.isMature(s) && random.nextInt(2) == 0) world.setBlockState(pos, cb.withAge(Math.min(cb.getMaxAge(), ageOf(s) + 1)), Block.NOTIFY_LISTENERS);
			} else if (s.getBlock() instanceof StemBlock) {
				int age = s.get(Properties.AGE_7);
				if (age < 7) world.setBlockState(pos, s.with(Properties.AGE_7, age + 1), Block.NOTIFY_LISTENERS);
				else if (crop.fruit() != null && random.nextInt(3) == 0) {
					Direction d = Direction.Type.HORIZONTAL.random(random);
					BlockPos at = pos.offset(d);
					BlockState ground = world.getBlockState(at.down());
					if (world.getBlockState(at).isAir() && (ground.isOf(Blocks.FARMLAND) || ground.isIn(BlockTags.DIRT))) world.setBlockState(at, crop.fruit().getDefaultState());
				}
			} else if (s.isOf(Blocks.SUGAR_CANE)) {
				if (world.getBlockState(pos.up()).isAir() && !world.getBlockState(pos.down()).isOf(Blocks.SUGAR_CANE)) world.setBlockState(pos.up(), Blocks.SUGAR_CANE.getDefaultState());
			} else if (s.isOf(Blocks.SWEET_BERRY_BUSH)) {
				int age = s.get(SweetBerryBushBlock.AGE);
				if (age < 3) world.setBlockState(pos, s.with(SweetBerryBushBlock.AGE, age + 1), Block.NOTIFY_LISTENERS);
			}
		}
	}

	static int ageOf(BlockState s) {
		for (var p : s.getProperties()) {
			if (p.getName().equals("age") && s.get(p) instanceof Integer i) return i;
		}
		return 0;
	}

	// ---------------------------------------------------------------- the lords' politics

	/**
	 * What a lord's mood drifts toward. Lords hate taxes and endless wars, like cheap labor (bonded folk and captives),
	 * like a crown that rewards them, and are proud of a big estate.
	 */
	static int favourTarget(Settlement s, Manor m) {
		Faction f = s.faction;
		int tax = s.taxRate >= 0 ? s.taxRate : (int) Math.round(net.colonists.economy.Economy.defaultTax(f) * 100);
		int t = 60 - (tax - 10) * 2;
		if (f.atWar) t -= 6 + f.warWeariness;
		if (f.slavery) t += 6;
		if (f.laws.contains(net.colonists.law.Law.DEATH_PENALTY)) t += 2;
		if (f.government == Faction.Government.REPUBLIC) t += 4; // lords sit in the senate
		t += Math.min(8, m.fields.size() * 2);
		if (m.lord != null && m.lord.equals(f.ruler)) t = 100;
		return t;
	}

	/** The manors of a whole realm. */
	public static List<Manor> ofRealm(Faction f) {
		List<Manor> list = new ArrayList<>();
		for (Settlement s : f.settlements.values()) for (Manor m : s.manors) if (m.lord != null) list.add(m);
		return list;
	}

	/** The landed lords' mood toward the crown, or -1 if the realm has none. Weighted by how much land each holds. */
	public static int lordsFavour(Faction f) {
		int sum = 0, weight = 0;
		for (Manor m : ofRealm(f)) {
			int w = Math.max(1, m.fields.size());
			sum += m.favour * w;
			weight += w;
		}
		return weight == 0 ? -1 : sum / weight;
	}

	/** Content lords send their levies to the realm's wars: extra siege strength. */
	public static int levies(Faction f) {
		int n = 0;
		for (Manor m : ofRealm(f)) if (m.favour >= 50 && !m.laborers.isEmpty()) n++;
		return Math.min(3, (n + 1) / 2);
	}

	/** Every lord's mood shifts at once (the crown raised taxes, paid a bonus, and so on). */
	public static void moveFavour(Faction f, int by) {
		for (Manor m : ofRealm(f)) m.favour = MathHelper.clamp(m.favour + by, 0, 100);
	}

	/** True if this citizen holds land. */
	public static boolean isLandowner(Faction f, UUID who) {
		for (Manor m : ofRealm(f)) if (who.equals(m.lord)) return true;
		return false;
	}

	/** Lords pushed too far rise together: the angriest claims the crown (a Pretender's civil war of the barons). */
	static void baronsRevolt(MinecraftServer server, Faction f, Random random) {
		List<Manor> lords = ofRealm(f);
		if (lords.size() < 2 || f.settlements.size() < 2 || lordsFavour(f) >= 20 || random.nextInt(8) != 0) return;
		Manor angriest = lords.stream().min(java.util.Comparator.comparingInt(x -> x.favour)).orElse(null);
		if (angriest == null || angriest.lord == null || angriest.lord.equals(f.ruler)) return;
		net.colonists.legend.Crises.baronsRevolt(server, f, angriest.lord, angriest.title());
	}

	// ---------------------------------------------------------------- claiming land

	/**
	 * A new field for the manor: its first one well beyond the houses, then each new one beside an existing field (with
	 * a cart track between). The land must be fairly level, dry, clear of trees and of everyone else's lots and fields.
	 */
	@Nullable
	public static Field claimField(ServerWorld world, FactionManager fm, Settlement s, Manor m, Random random) {
		int reach = CityGrowth.reach(s);
		for (int attempt = 0; attempt < 24; attempt++) {
			// Big fields first; in rough country, a lord settles for a smaller one rather than none.
			boolean big = attempt < 14;
			int w = big ? 24 + random.nextInt(13) : 18 + random.nextInt(7), d = big ? 24 + random.nextInt(13) : 18 + random.nextInt(7);
			int x0, z0;
			if (m.fields.isEmpty()) {
				double ang = random.nextDouble() * Math.PI * 2;
				double dist = reach + 10 + random.nextInt(16) + Math.max(w, d) / 2.0;
				x0 = s.cityCenter.getX() + (int) (Math.cos(ang) * dist) - w / 2;
				z0 = s.cityCenter.getZ() + (int) (Math.sin(ang) * dist) - d / 2;
			} else {
				Field from = m.fields.get(random.nextInt(m.fields.size()));
				switch (random.nextInt(4)) {
					case 0 -> {
						x0 = from.x1 + 3;
						z0 = from.z0 + random.nextInt(7) - 3;
					}
					case 1 -> {
						x0 = from.x0 - 3 - w;
						z0 = from.z0 + random.nextInt(7) - 3;
					}
					case 2 -> {
						x0 = from.x0 + random.nextInt(7) - 3;
						z0 = from.z1 + 3;
					}
					default -> {
						x0 = from.x0 + random.nextInt(7) - 3;
						z0 = from.z0 - 3 - d;
					}
				}
			}
			int x1 = x0 + w - 1, z1 = z0 + d - 1;
			if (!fieldFits(world, fm, s, x0, z0, x1, z1, reach)) continue;
			List<Crop> crops = cropsFor(world, new BlockPos((x0 + x1) / 2, 64, (z0 + z1) / 2));
			Crop crop = crops.get(random.nextInt(crops.size()));
			// Variety: avoid sowing the same crop as the last field when there is a choice.
			if (!m.fields.isEmpty() && crop.name().equalsIgnoreCase(m.fields.get(m.fields.size() - 1).crop) && crops.size() > 1) crop = crops.get(random.nextInt(crops.size()));
			return new Field(x0, z0, x1, z1, crop.name().toLowerCase());
		}
		return null;
	}

	static boolean fieldFits(ServerWorld world, FactionManager fm, Settlement s, int x0, int z0, int x1, int z1, int reach) {
		for (int x = x0; x <= x1; x += 15) for (int z = z0; z <= z1; z += 15) if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
		if (!world.isChunkLoaded(x1 >> 4, z1 >> 4) || !WorldEdge.playable(x0, z0, 10) || !WorldEdge.playable(x1, z1, 10)) return false;
		// Beyond this town's houses, and well clear of every town's lots, streets and fields.
		double cx = (x0 + x1) / 2.0, cz = (z0 + z1) / 2.0;
		for (Settlement o : fm.allSettlements()) {
			if (o.cityCenter == null) continue;
			double d = Math.hypot(cx - o.cityCenter.getX(), cz - o.cityCenter.getZ());
			int keep = (o == s ? reach : CityGrowth.reach(o)) + 4 + Math.max(x1 - x0, z1 - z0) / 2;
			if (d < keep) return false;
			for (int[] r : CityGrowth.footprints(o)) if (r[0] - 3 <= x1 && r[2] + 3 >= x0 && r[1] - 3 <= z1 && r[3] + 3 >= z0) return false;
			for (BlockPos r : o.roads) if (r.getX() >= x0 - 1 && r.getX() <= x1 + 1 && r.getZ() >= z0 - 1 && r.getZ() <= z1 + 1) return false;
			for (Manor m : o.manors) for (Field f : m.fields) if (f.overlaps(x0, z0, x1, z1, 2)) return false;
		}
		// Fairly level, mostly dry, few trees.
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0, trees = 0, n = 0, soil = 0;
		for (int x = x0; x <= x1; x += 3) {
			for (int z = z0; z <= z1; z += 3) {
				int y = City.surfaceY(world, x, z);
				BlockState top = world.getBlockState(new BlockPos(x, y - 1, z));
				n++;
				if (top.getFluidState().isIn(FluidTags.WATER) || top.getFluidState().isIn(FluidTags.LAVA)) wet++;
				else {
					min = Math.min(min, y);
					max = Math.max(max, y);
				}
				if (top.isIn(BlockTags.LOGS) || top.isIn(BlockTags.LEAVES)) trees++;
				if (top.isIn(BlockTags.DIRT) || top.isIn(BlockTags.SAND) || top.isOf(Blocks.FARMLAND) || top.isOf(Blocks.SNOW) || top.isOf(Blocks.GRAVEL)) soil++;
			}
		}
		return n > 0 && wet * 5 <= n && trees * 6 <= n && soil * 10 >= n * 6 && max - min <= 3;
	}

	/** The manor house goes beside the first field, on the side toward town. */
	public static boolean placeSeat(ServerWorld world, FactionManager fm, Settlement s, Manor m, Random random) {
		Blueprint bp = Blueprints.MANOR_HOUSE;
		Field f = m.fields.get(0);
		int w = bp.width, d = bp.depth;
		List<int[]> spots = new ArrayList<>();
		BlockPos c = f.center();
		spots.add(new int[]{c.getX() - w / 2, f.z0 - 3 - d});
		spots.add(new int[]{c.getX() - w / 2, f.z1 + 3});
		spots.add(new int[]{f.x0 - 3 - w, c.getZ() - d / 2});
		spots.add(new int[]{f.x1 + 3, c.getZ() - d / 2});
		// Nearest to town first.
		spots.sort((a, b) -> Double.compare(Math.hypot(a[0] - s.cityCenter.getX(), a[1] - s.cityCenter.getZ()), Math.hypot(b[0] - s.cityCenter.getX(), b[1] - s.cityCenter.getZ())));
		for (int[] spot : spots) {
			BlockPos origin = lot(world, fm, s, bp, spot[0], spot[1]);
			if (origin == null) continue;
			s.rotations.put(origin, BlockRotation.NONE);
			s.buildingTypes.put(origin, bp.id);
			m.seat = origin;
			Site seat = new Site(origin, BlockRotation.NONE);
			CityGrowth.connect(world, fm, s, bp.doorstep(seat).offset(seat.front(), 2));
			return true;
		}
		return false;
	}

	/** The family's banner: two before the manor house, and one at the corner of each field nearest town. */
	public static void flyArms(ServerWorld world, Settlement s, Manor m) {
		String who = m.title();
		if (m.seat != null && s.built.contains(m.seat)) {
			Site seat = new Site(m.seat, s.rotations.getOrDefault(m.seat, BlockRotation.NONE));
			net.colonists.faction.Banners.fly(world, new BlockPos[]{seat.at(-1, 0, -2), seat.at(-1, 0, -3)}, m.banner, who);
			net.colonists.faction.Banners.fly(world, new BlockPos[]{seat.at(Blueprints.MANOR_HOUSE.width, 0, -2), seat.at(Blueprints.MANOR_HOUSE.width, 0, -3)}, m.banner, who);
		}
		for (Field f : m.fields) {
			int cx = Math.abs(f.x0 - s.cityCenter.getX()) < Math.abs(f.x1 - s.cityCenter.getX()) ? f.x0 - 1 : f.x1 + 1;
			int cz = Math.abs(f.z0 - s.cityCenter.getZ()) < Math.abs(f.z1 - s.cityCenter.getZ()) ? f.z0 - 1 : f.z1 + 1;
			net.colonists.faction.Banners.fly(world, new BlockPos[]{new BlockPos(cx, 64, cz), new BlockPos(cx, 64, cz + (cz < f.z0 ? -1 : 1))}, m.banner, who);
		}
	}

	/** A barn, then two laborers' cottages, beside the manor house. */
	public static boolean placeOutbuilding(ServerWorld world, FactionManager fm, Settlement s, Manor m, Random random) {
		Blueprint bp = m.outbuildings.isEmpty() ? Blueprints.BARN : Blueprints.CABIN;
		Blueprint house = Blueprints.MANOR_HOUSE;
		int sx = m.seat.getX(), sz = m.seat.getZ();
		List<int[]> spots = new ArrayList<>();
		spots.add(new int[]{sx - 3 - bp.width, sz});
		spots.add(new int[]{sx + house.width + 3, sz});
		spots.add(new int[]{sx - 3 - bp.width, sz + house.depth - bp.depth});
		spots.add(new int[]{sx + house.width + 3, sz + house.depth - bp.depth});
		spots.add(new int[]{sx - 3 - bp.width - 8, sz});
		spots.add(new int[]{sx + house.width + 3 + 8, sz});
		for (int[] spot : spots) {
			BlockPos origin = lot(world, fm, s, bp, spot[0], spot[1]);
			if (origin == null) continue;
			s.rotations.put(origin, BlockRotation.NONE);
			s.buildingTypes.put(origin, bp.id);
			m.outbuildings.add(origin);
			return true;
		}
		return false;
	}

	/** A free lot for a blueprint at a spot on the estate (not on anyone's lot, street or field), or null. */
	@Nullable
	static BlockPos lot(ServerWorld world, FactionManager fm, Settlement s, Blueprint bp, int x, int z) {
		Site site = new Site(new BlockPos(x, 0, z), BlockRotation.NONE);
		int[] rect = site.rect(0, -1, bp.lotWidth() - 1, bp.depth - 1);
		if (!world.isChunkLoaded(rect[0] >> 4, rect[1] >> 4) || !world.isChunkLoaded(rect[2] >> 4, rect[3] >> 4)) return null;
		if (!WorldEdge.playable(rect[0], rect[1], 8) || !WorldEdge.playable(rect[2], rect[3], 8)) return null;
		for (int px = rect[0]; px <= rect[2]; px += 4) for (int pz = rect[1]; pz <= rect[3]; pz += 4) if (CityGrowth.occupied(fm, px, pz, 1)) return null;
		if (CityGrowth.occupied(fm, rect[2], rect[3], 1)) return null;
		for (Settlement o : fm.allSettlements()) for (Manor om : o.manors) for (Field fld : om.fields) if (fld.overlaps(rect[0], rect[1], rect[2], rect[3], 1)) return null;
		Integer floor = City.floorFor(world, rect[0], rect[1], rect[2], rect[3], 2);
		return floor == null ? null : site.origin().withY(floor);
	}

	/** A shim so this package can use the town planner's water survey. */
	static final class CityGrowthAccess {
		static int waterNearby(World world, int x, int z, int r) {
			return CityGrowth.waterAround(world, x, z, r);
		}
	}
}
