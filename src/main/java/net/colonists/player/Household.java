package net.colonists.player;

import net.colonists.ColonistsMod;
import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.CityGrowth;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History;
import net.colonists.legend.Legends;
import net.colonists.legend.Wonders;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A player's household: retinue, property and businesses, marriage and children, faith and pilgrimage. */
public final class Household {
	private Household() {}

	public static final int TERM_DAYS = 5, HIRE_COST = 15;
	static final Set<String> BUSINESSES = Set.of("tavern", "smithy", "bakery", "market", "barn", "lumber_camp", "animal_pen", "barracks");

	static long day(MinecraftServer server) {
		return server.getOverworld().getTimeOfDay() / 24000L;
	}

	static boolean pay(ServerPlayerEntity p, int amount) {
		if (p.isCreative()) return true;
		if (Inventories.remove(p.getInventory(), s -> s.isOf(Items.EMERALD), amount, true) < amount) return false;
		Inventories.remove(p.getInventory(), s -> s.isOf(Items.EMERALD), amount, false);
		return true;
	}

	/** The colonist the player is looking at (within 6 blocks), or null. */
	@Nullable
	public static ColonistEntity lookedAt(ServerPlayerEntity p) {
		Vec3d eye = p.getEyePos(), dir = p.getRotationVec(1f);
		Vec3d end = eye.add(dir.multiply(6));
		Box box = p.getBoundingBox().stretch(dir.multiply(6)).expand(1);
		EntityHitResult hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(p, eye, end, box, e -> e instanceof ColonistEntity && e.isAlive(), 36);
		return hit != null && hit.getEntity() instanceof ColonistEntity c ? c : null;
	}

	// ---------------------------------------------------------------- retinue

	public static int maxRetinue(MinecraftServer server, ServerPlayerEntity p) {
		return Math.min(6, 2 + PlayerData.of(server, p.getUuid()).titles.size() / 3);
	}

	public static String hire(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		ColonistEntity c = lookedAt(p);
		if (c == null) return "Look at the colonist you want to hire.";
		FactionManager fm = FactionManager.get(server);
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		if (p.getUuid().equals(c.liege)) {
			if (!pay(p, HIRE_COST)) return "Renewing " + c.getFirstName() + "'s service costs " + HIRE_COST + " emeralds.";
			c.liegeUntil = Math.max(c.liegeUntil, day(server)) + TERM_DAYS;
			c.say("Another " + TERM_DAYS + " days, then. Lead on.");
			return null;
		}
		if (c.isBaby() || c.isRuler() || c.liege != null || net.colonists.law.Courts.isPrisoner(server, c.getUuid())
				|| net.colonists.law.Justice.get(server).slaves.containsKey(c.getUuid())) return c.getFirstName() + " can't be hired.";
		if (fm.standing(p, c.getFactionId()) < 30) return c.getFirstName() + " doesn't trust you enough (standing 30 needed).";
		pr.retinue.removeIf(u -> !(p.getServerWorld().getEntity(u) instanceof ColonistEntity) && pr.retinue.size() > maxRetinue(server, p));
		if (pr.retinue.size() >= maxRetinue(server, p)) return "Your retinue is full (" + maxRetinue(server, p) + "). Earn more renown to lead more.";
		if (!pay(p, HIRE_COST)) return "Hiring costs " + HIRE_COST + " emeralds for " + TERM_DAYS + " days.";
		c.liege = p.getUuid();
		c.liegeUntil = day(server) + TERM_DAYS;
		c.following = true;
		pr.retinue.add(c.getUuid());
		pr.add("retinue", 1);
		pr.deed(day(server), "took " + c.fullName() + " into their service");
		c.say("I'm yours to command, " + p.getName().getString() + ".");
		return null;
	}

	public static List<ColonistEntity> retinue(ServerPlayerEntity p) {
		List<ColonistEntity> list = new ArrayList<>();
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		for (UUID u : pr.retinue) {
			if (p.getServerWorld().getEntity(u) instanceof ColonistEntity c && c.isAlive() && p.getUuid().equals(c.liege)) list.add(c);
		}
		return list;
	}

	// ---------------------------------------------------------------- property

	public record Lot(Settlement town, BlockPos origin, String kind) {
	}

	@Nullable
	public static Lot lotAt(MinecraftServer server, BlockPos pos) {
		FactionManager fm = FactionManager.get(server);
		for (Settlement s : fm.allSettlements()) {
			if (s.cityCenter == null || s.cityCenter.getSquaredDistance(pos) > 160 * 160) continue;
			for (Map.Entry<BlockPos, String> e : s.buildingTypes.entrySet()) {
				int[] r = CityGrowth.footprint(s, e.getKey());
				if (pos.getX() >= r[0] && pos.getX() <= r[2] && pos.getZ() >= r[1] && pos.getZ() <= r[3]) {
					return new Lot(s, e.getKey(), s.homes.containsKey(e.getKey()) ? "home" : e.getValue());
				}
			}
		}
		return null;
	}

	@Nullable
	static PlayerData.Property ownerOf(MinecraftServer server, Lot lot, UUID[] who) {
		for (Map.Entry<UUID, PlayerData.Profile> e : PlayerData.get(server).profiles.entrySet()) {
			for (PlayerData.Property p : e.getValue().properties) {
				if (p.town == lot.town.id && p.lot == lot.origin.asLong()) {
					if (who != null) who[0] = e.getKey();
					return p;
				}
			}
		}
		return null;
	}

	/** Does this player own the lot at this position? (Their own property is not theirs to steal from.) */
	public static boolean ownsAt(MinecraftServer server, UUID player, BlockPos pos) {
		PlayerData.Profile pr = PlayerData.get(server).profiles.get(player);
		if (pr == null || pr.properties.isEmpty()) return false;
		Lot lot = lotAt(server, pos);
		if (lot == null) return false;
		for (PlayerData.Property p : pr.properties) if (p.town == lot.town.id && p.lot == lot.origin.asLong()) return true;
		return false;
	}

	static int price(Lot lot) {
		if (!lot.kind.equals("home")) return lot.kind.equals("market") ? 90 : 60;
		Blueprint bp = Blueprints.get(lot.town.buildingTypes.getOrDefault(lot.origin, "cabin"));
		if (bp == Blueprints.ESTATE) return 90;
		if (bp == Blueprints.MANOR) return 60;
		if (bp == Blueprints.STONE_HOUSE) return 40;
		if (bp == Blueprints.COTTAGE) return 25;
		return 15;
	}

	public static String buy(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		Lot lot = lotAt(server, p.getBlockPos());
		if (lot == null) return "Stand on the lot of a house or workshop in a colonist town.";
		if (lot.town.village) return "The free men do not sell their homes.";
		FactionManager fm = FactionManager.get(server);
		if (lot.town.faction == null || fm.standing(p, lot.town.faction.id) < 10) return "The " + (lot.town.faction == null ? "town" : lot.town.faction.name) + " won't sell to you (standing 10 needed).";
		if (!lot.town.built.contains(lot.origin)) return "That isn't finished yet.";
		if (ownerOf(server, lot, null) != null) return "Someone already owns it.";
		if (lot.kind.equals("home") && lot.town.homes.get(lot.origin) != null) return "Someone lives there. Only empty houses are for sale.";
		if (!lot.kind.equals("home") && !BUSINESSES.contains(lot.kind)) return "That building is not for sale.";
		int price = price(lot);
		if (!pay(p, price)) return "It costs " + price + " emeralds.";
		lot.town.treasury += price;
		PlayerData.Property prop = new PlayerData.Property();
		prop.town = lot.town.id;
		prop.lot = lot.origin.asLong();
		prop.kind = lot.kind;
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		pr.properties.add(prop);
		pr.add("properties", 1);
		if (BUSINESSES.contains(lot.kind)) pr.add("businesses", 1);
		if (lot.kind.equals("home")) lot.town.homes.put(lot.origin, p.getUuid());
		pr.deed(day(server), "bought " + (lot.kind.equals("home") ? "a house" : "the " + lot.kind.replace('_', ' ')) + " in " + lot.town.name);
		fm.markDirty();
		return null;
	}

	public static String sell(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		Lot lot = lotAt(server, p.getBlockPos());
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		UUID[] who = new UUID[1];
		PlayerData.Property prop = lot == null ? null : ownerOf(server, lot, who);
		if (prop == null || !p.getUuid().equals(who[0])) return "Stand on a lot you own.";
		pr.properties.remove(prop);
		int back = price(lot) / 2;
		int paid = Math.min(back, lot.town.treasury);
		lot.town.treasury -= paid;
		p.giveItemStack(new ItemStack(Items.EMERALD, paid));
		if (lot.kind.equals("home") && p.getUuid().equals(lot.town.homes.get(lot.origin))) lot.town.homes.put(lot.origin, null);
		return null;
	}

	public static String rent(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		Lot lot = lotAt(server, p.getBlockPos());
		UUID[] who = new UUID[1];
		PlayerData.Property prop = lot == null ? null : ownerOf(server, lot, who);
		if (prop == null || !p.getUuid().equals(who[0]) || !prop.kind.equals("home")) return "Stand in a house you own.";
		if (prop.rented) return "It is already let.";
		prop.rented = true;
		lot.town.homes.put(lot.origin, null); // a homeless colonist will move in and pay rent
		return null;
	}

	// ---------------------------------------------------------------- marriage

	public static String propose(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		ColonistEntity c = lookedAt(p);
		if (c == null) return "Look at the one you wish to marry.";
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		if (pr.spouse != null) return "You are already married.";
		if (c.isBaby() || c.partner != null) return c.getFirstName() + " is not free to marry.";
		FactionManager fm = FactionManager.get(server);
		int standing = fm.standing(p, c.getFactionId());
		if (standing < 50) return c.getFirstName() + " hardly knows you (standing 50 needed).";
		int chance = 30 + (standing - 50) + c.kindness / 4;
		if (c.getRandom().nextInt(100) >= chance) {
			fm.changeRep(server, p, c.getFactionId(), -3);
			c.say("I'm flattered, but... no.");
			return null;
		}
		c.partner = p.getUuid();
		c.partnerName = p.getName().getString();
		pr.spouse = c.getUuid();
		pr.spouseName = c.fullName();
		fm.changeRep(server, p, c.getFactionId(), 15);
		pr.deed(day(server), "married " + c.fullName() + " of the " + (fm.get(c.getFactionId()) == null ? "wilds" : fm.get(c.getFactionId()).name));
		c.say("Yes! A thousand times yes!");
		FactionManager.broadcastNear(p.getServerWorld(), c.getBlockPos(), 160, Text.literal("💍 " + p.getName().getString() + " and " + c.fullName() + " are wed!").formatted(Formatting.LIGHT_PURPLE));
		return null;
	}

	/** A colonist married to a player has a child (called from Society). Returns true if a child was born. */
	public static boolean childOfPlayer(ServerWorld world, ColonistEntity mother) {
		if (mother.partner == null) return false;
		ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(mother.partner);
		if (p == null || p.getServerWorld() != world || p.squaredDistanceTo(mother) > 48 * 48) return false;
		PlayerData.Profile pr = PlayerData.of(world.getServer(), p.getUuid());
		if (!mother.getUuid().equals(pr.spouse)) return false;
		ColonistEntity baby = ColonistsMod.COLONIST.create(world);
		if (baby == null) return false;
		BlockPos at = mother.getBlockPos();
		baby.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		baby.pendingFaction = mother.getFactionId();
		baby.pendingSettlement = mother.settlementId;
		baby.bornTo(mother, null);
		baby.setSurname(p.getName().getString());
		baby.initialize(world, world.getLocalDifficulty(at), net.minecraft.entity.SpawnReason.BREEDING, null);
		world.spawnEntity(baby);
		pr.children.add(baby.getUuid());
		pr.add("children", 1);
		pr.deed(day(world.getServer()), "welcomed a child, " + baby.fullName());
		p.sendMessage(Text.literal("👶 Your child " + baby.fullName() + " is born!").formatted(Formatting.LIGHT_PURPLE), false);
		return true;
	}

	// ---------------------------------------------------------------- faith and pilgrimage

	public static String joinFaith(ServerPlayerEntity p, String id) {
		History h = History.get(p.getServer());
		History.Religion r = h.religion(id);
		if (r == null) return "No such faith. See /colony religions.";
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		if (r.id.equals(pr.religion)) return "You already follow " + r.name + ".";
		pr.religion = r.id;
		pr.deed(day(p.getServer()), "embraced " + r.name);
		return null;
	}

	/** Prayer at a holy place (a cathedral, shrine or pantheon, or the town of your faith's prophet) once a day. */
	public static String pray(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		long day = day(server);
		if (pr.lastPilgrimageDay == day) return "You have already made your devotions today.";
		History h = History.get(server);
		FactionManager fm = FactionManager.get(server);
		String place = null;
		for (Wonders.Type t : new Wonders.Type[]{Wonders.Type.CATHEDRAL, Wonders.Type.SHRINE, Wonders.Type.PANTHEON}) {
			History.Wonder w = h.wonders.get(t.name());
			if (w != null && !w.ruined && BlockPos.fromLong(w.origin).getSquaredDistance(p.getBlockPos()) < 40 * 40) place = t.label();
		}
		History.Religion faith = h.religion(pr.religion);
		if (place == null && faith != null && faith.prophet != null) {
			for (Faction f : fm.all()) {
				Faction.Citizen c = f.citizens.get(faith.prophet);
				Settlement s = c == null ? null : fm.settlement(c.settlement);
				if (s != null && s.cityCenter != null && s.cityCenter.getSquaredDistance(p.getBlockPos()) < 48 * 48) place = "the prophet's town of " + s.name;
			}
		}
		if (place == null) return "Pray at a holy place: a Great Cathedral, a Shrine of the Prophet, the Pantheon, or the town where your prophet lives.";
		pr.lastPilgrimageDay = day;
		pr.add("pilgrimages", 1);
		Legends.earn(server, p.getUuid(), History.PIETY, 10);
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 6000, 0));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 24000, 0));
		pr.deed(day, "made a pilgrimage to " + place);
		p.sendMessage(Text.literal("✧ You pray at " + place + ". You feel blessed.").formatted(Formatting.LIGHT_PURPLE), false);
		return null;
	}

	// ---------------------------------------------------------------- the Book of Deeds

	public static ItemStack bookOfDeeds(ServerPlayerEntity p) {
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		List<String> pages = new ArrayList<>();
		String title = Titles.best(p.getServer(), p);
		StringBuilder page = new StringBuilder("The Deeds of " + p.getName().getString() + (title.isEmpty() ? "" : ",\n" + title) + "\n\n");
		List<String> deeds = pr.deeds.size() > 80 ? pr.deeds.subList(pr.deeds.size() - 80, pr.deeds.size()) : pr.deeds;
		if (deeds.isEmpty()) page.append("Nothing worth the ink, yet.");
		for (String d : deeds) {
			if (page.length() + d.length() > 220) {
				pages.add(page.toString());
				page = new StringBuilder();
			}
			page.append(d).append("\n\n");
		}
		pages.add(page.toString());
		List<RawFilteredPair<Text>> content = new ArrayList<>();
		for (String s : pages.subList(0, Math.min(100, pages.size()))) content.add(RawFilteredPair.of(Text.literal(s)));
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		String t = "Deeds of " + p.getName().getString();
		book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(t.length() > 32 ? t.substring(0, 32) : t),
				p.getName().getString(), 0, content, true));
		return book;
	}

	// ---------------------------------------------------------------- once a minute

	public static void tick(MinecraftServer server) {
		long day = day(server);
		FactionManager fm = FactionManager.get(server);
		ServerWorld world = server.getOverworld();
		PlayerData data = PlayerData.get(server);
		for (Map.Entry<UUID, PlayerData.Profile> e : data.profiles.entrySet()) {
			UUID who = e.getKey();
			PlayerData.Profile pr = e.getValue();
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(who);
			// The daily round: rent, business takings, the goodwill of fellow believers; confiscations.
			if (pr.lastPaidDay != day) {
				pr.lastPaidDay = day;
				for (PlayerData.Property prop : new ArrayList<>(pr.properties)) {
					Settlement s = fm.settlement(prop.town);
					if (s == null || s.faction == null) {
						pr.properties.remove(prop);
						continue;
					}
					if (p != null && fm.standing(p, s.faction.id) <= FactionManager.WAR) {
						pr.properties.remove(prop);
						if (prop.kind.equals("home") && who.equals(s.homes.get(BlockPos.fromLong(prop.lot)))) s.homes.put(BlockPos.fromLong(prop.lot), null);
						p.sendMessage(Text.literal("The " + s.faction.name + " have seized your property in " + s.name + ".").formatted(Formatting.RED), false);
						continue;
					}
					int income = 0;
					if (BUSINESSES.contains(prop.kind)) income = prop.kind.equals("market") ? 5 : 3;
					else if (prop.rented && s.homes.get(BlockPos.fromLong(prop.lot)) != null) income = 1;
					income = Math.min(income, s.treasury);
					s.treasury -= income;
					pr.bank += income;
				}
				if (p != null && !pr.religion.isEmpty()) {
					for (Faction f : fm.all()) if (f.religion.equals(pr.religion)) fm.changeRep(server, p, f.id, 1);
				}
				// Widowed?
				if (pr.spouse != null && fm.all().stream().noneMatch(f -> f.citizens.containsKey(pr.spouse))) {
					pr.deed(day, "was widowed: " + pr.spouseName + " has died");
					if (p != null) p.sendMessage(Text.literal("🕯 Your spouse " + pr.spouseName + " has died.").formatted(Formatting.GRAY), false);
					pr.spouse = null;
					pr.spouseName = "";
				}
			}
			if (p == null) continue;
			// Grown children swear to follow their parent.
			for (UUID child : pr.children) {
				if (p.getServerWorld().getEntity(child) instanceof ColonistEntity c && !c.isBaby() && c.liege == null) {
					c.liege = who;
					c.liegeUntil = Long.MAX_VALUE;
					c.following = true;
					if (!pr.retinue.contains(child)) pr.retinue.add(child);
					c.say("I'm grown now, " + (c.getRandom().nextBoolean() ? "parent" : p.getName().getString()) + ". Let me ride with you.");
				}
			}
			// Terms of service end.
			for (UUID u : new ArrayList<>(pr.retinue)) {
				Entity ent = p.getServerWorld().getEntity(u);
				if (ent instanceof ColonistEntity c && who.equals(c.liege) && day > c.liegeUntil) {
					c.liege = null;
					c.say("My term of service is over. Farewell, " + p.getName().getString() + ".");
					pr.retinue.remove(u);
				} else if (ent != null && !(ent instanceof ColonistEntity c2 && who.equals(c2.liege))) {
					pr.retinue.remove(u);
				}
			}
			pr.retinue.removeIf(u -> fm.all().stream().noneMatch(f -> f.citizens.containsKey(u)) && world.getEntity(u) == null && p.getServerWorld().getEntity(u) == null);
		}
		data.markDirty();
	}
}
