package net.colonists.legend;

import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History.Event;
import net.colonists.legend.History.Legend;
import net.minecraft.block.BlockState;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;

/**
 * Villagers keep chronicles. A village's librarian writes down the deeds of its region and the legends of the world,
 * as the village sees them: its friends are great, its enemies are butchers, its faith is true and others false.
 */
public final class Chronicles {
	private Chronicles() {}

	private static final String[] SCRIBES = {"Aldo", "Berta", "Cedric", "Dagny", "Edwin", "Frida", "Gunnar", "Hilde", "Ivo", "Jutta",
			"Konrad", "Liesel", "Magnus", "Nell", "Osric", "Petra", "Quill", "Rosalind", "Sigrid", "Tobin"};
	private static final java.util.Map<Integer, Long> LAST_DAY = new java.util.HashMap<>();
	private static final String[] ORDINAL = {"first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth"};

	/** Once a minute: villages with a librarian and news to tell write a new volume (at most one a day). */
	public static void tick(MinecraftServer server, FactionManager fm) {
		ServerWorld world = server.getOverworld();
		History h = History.get(server);
		long day = world.getTimeOfDay() / 24000L;
		for (Settlement s : fm.allSettlements()) {
			if (!s.village || s.cityCenter == null || s.faction == null || s.freemen <= 0) continue;
			if (!world.isChunkLoaded(s.cityCenter.getX() >> 4, s.cityCenter.getZ() >> 4)) continue;
			if (s.chronicled < 0) s.chronicled = h.firstIndex();
			List<Event> news = new ArrayList<>();
			long index = h.firstIndex();
			for (Event e : h.events) {
				if (index++ < s.chronicled) continue;
				if (relevant(s, e)) news.add(e);
			}
			if (news.size() < 3 && news.stream().noneMatch(e -> e.legend != null)) continue;
			if (LAST_DAY.getOrDefault(s.id, -1L) == day) continue;
			VillagerEntity scribe = world.getEntitiesByClass(VillagerEntity.class, new Box(s.cityCenter).expand(80, 32, 80),
					v -> v.isAlive() && v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN).stream().findFirst().orElse(null);
			if (scribe == null) continue;
			s.chronicled = h.eventCount;
			LAST_DAY.put(s.id, day);
			s.volumes++;
			ItemStack book = write(server, fm, h, s, news, scribe, world.getRandom());
			// The librarian sells copies; one is left on a lectern for anyone to read.
			scribe.getOffers().add(new TradeOffer(new TradedItem(Items.EMERALD, 2 + s.volumes % 3), book.copy(), 4, 5, 0.05f));
			placeOnLectern(world, s.cityCenter, book.copy());
			FactionManager.broadcastNear(world, s.cityCenter, 96, Text.literal("📖 The chroniclers of " + s.name + " have written a new volume.")
					.formatted(net.minecraft.util.Formatting.GRAY));
		}
	}

	private static boolean relevant(Settlement s, Event e) {
		if (e.legend != null || e.type == History.EventType.NEW_FAITH || e.type == History.EventType.PROPHET
				|| e.type == History.EventType.HOLY_WAR || e.type == History.EventType.PROPHECY_FULFILLED) return true;
		if (e.actor.equals(s.faction.id) || e.target.equals(s.faction.id)) return true;
		return Math.hypot(e.x - s.cityCenter.getX(), e.z - s.cityCenter.getZ()) < 700;
	}

	private static void placeOnLectern(ServerWorld world, BlockPos center, ItemStack book) {
		for (BlockPos p : BlockPos.iterate(center.add(-40, -8, -40), center.add(40, 8, 40))) {
			BlockState st = world.getBlockState(p);
			if (st.getBlock() instanceof LecternBlock && !st.get(LecternBlock.HAS_BOOK) && world.getBlockEntity(p) instanceof LecternBlockEntity) {
				LecternBlock.putBookIfAbsent(null, world, p.toImmutable(), st, book);
				return;
			}
		}
	}

	// ---------------------------------------------------------------- writing

	static ItemStack write(MinecraftServer server, FactionManager fm, History h, Settlement village, List<Event> news, VillagerEntity scribe, Random random) {
		String scribeName = SCRIBES[Math.floorMod(scribe.getUuid().hashCode(), SCRIBES.length)];
		String title = "Chronicle of " + village.name + " " + (village.volumes <= 10 ? "" : "") + "Vol. " + village.volumes;
		if (title.length() > 32) title = "Chronicle of " + village.name;
		if (title.length() > 32) title = title.substring(0, 32);
		List<String> pages = new ArrayList<>();
		String opening = "Here begins the " + (village.volumes <= ORDINAL.length ? ORDINAL[village.volumes - 1] : village.volumes + "th")
				+ " book of the chronicle of " + village.name + ", set down by " + scribeName + ", " + village.faction.name.replace("Free Men", "a free man")
				+ ".\n\n";
		StringBuilder page = new StringBuilder(opening);
		for (Event e : news) {
			String line = sentence(h, fm, village, e, random);
			if (line == null) continue;
			if (page.length() + line.length() > 230) {
				pages.add(page.toString());
				page = new StringBuilder();
			}
			page.append(line).append("\n\n");
		}
		page.append(closing(random));
		pages.add(page.toString());
		List<RawFilteredPair<Text>> content = new ArrayList<>();
		for (String p : pages) content.add(RawFilteredPair.of(Text.literal(p)));
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(title),
				scribeName + " of " + village.name, 0, content, true));
		return book;
	}

	private static String closing(Random random) {
		String[] c = {"So it was written, and so it was.", "May those who read this remember.", "Here the writing ends, until there is more to tell.",
				"The rest is not yet known to us."};
		return c[random.nextInt(c.length)];
	}

	/** -2 (hated) to +2 (beloved): how the village feels about a faction. */
	private static int stance(FactionManager fm, History h, Settlement village, String factionId) {
		if (factionId.isEmpty()) return 0;
		Faction mine = village.faction, other = fm.get(factionId);
		if (other == null) return 0;
		if (mine == other) return 2;
		int n = 0;
		int rel = fm.relation(mine.id, other.id);
		if (rel >= 50) n++;
		if (rel <= -50) n -= 2;
		else if (rel < -15) n--;
		if (!mine.religion.isEmpty() && mine.religion.equals(other.religion)) n++;
		else if (!mine.religion.isEmpty() && !other.religion.isEmpty()) {
			History.Religion theirs = h.religion(other.religion);
			if (theirs != null && !theirs.parent.isEmpty() && theirs.parent.equals(mine.religion)) n--; // heretics
		}
		return Math.max(-2, Math.min(2, n));
	}

	private static String person(History h, Event e, int stance) {
		if (e.legend == null) return e.person;
		Legend l = h.legends.get(e.legend);
		if (l == null) return e.person;
		if (stance >= 0) return l.styled();
		String[] cursed = switch (l.kind) {
			case HERO -> new String[]{"the Butcher", "the Wolf", "Bloodhand"};
			case EMPEROR -> new String[]{"the Tyrant", "the Usurper", "the Grasping"};
			case ADVENTURER -> new String[]{"the Vagrant", "the Thief of Roads"};
			case PROPHET, PROPHET_KING -> new String[]{"the False Prophet", "the Deceiver", "the Heretic"};
			case PROPHET_CONQUEROR -> new String[]{"the Scourge", "the Destroyer", "the Fanatic"};
		};
		return l.name + " " + cursed[Math.floorMod(l.who.hashCode(), cursed.length)];
	}

	private static String day(Event e) {
		return "In the " + (e.day + 1) + (suffix(e.day + 1)) + " day";
	}

	private static String suffix(long n) {
		if (n % 100 >= 11 && n % 100 <= 13) return "th";
		return switch ((int) (n % 10)) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
	}

	static String sentence(History h, FactionManager fm, Settlement village, Event e, Random random) {
		int sa = stance(fm, h, village, e.actor), st = stance(fm, h, village, e.target);
		String who = person(h, e, sa);
		String actor = e.actorName.isEmpty() ? "a people" : "the " + e.actorName;
		String target = e.targetName.isEmpty() ? "their foes" : "the " + e.targetName;
		String when = day(e);
		return switch (e.type) {
			case FOUNDED -> when + ", " + actor + " raised the first roofs of " + e.place + (sa > 0 ? ", and it was good." : sa < 0 ? ", on land that was never theirs." : ".");
			case CONQUERED -> sa > st
					? when + ", " + actor + " delivered " + e.place + " from " + target + ", and the town rejoiced."
					: sa < st ? when + ", " + e.place + " fell to the savagery of " + actor + ". " + capital(target) + " wept for it."
					: when + ", " + e.place + " passed from " + target + " to " + actor + " by force of arms.";
			case CEDED -> e.extra.equals("faith")
					? when + ", the faithful of " + e.place + " opened their gates to " + who + (sa >= 0 ? ", and were blessed." : ", led astray by false words.")
					: when + ", " + e.place + " was given to " + actor + " by treaty.";
			case WAR -> when + ", " + actor + " made war upon " + target + (sa > st ? ", and justly." : st > sa ? ", in their wickedness." : ".");
			case HOLY_WAR -> {
				String allies = e.extra.contains("|") ? e.extra.substring(e.extra.indexOf('|') + 1) : "";
				String faith = e.extra.contains("|") ? e.extra.substring(0, e.extra.indexOf('|')) : e.extra;
				String lead = e.person.isEmpty() ? actor : who;
				yield sa > 0 ? when + ", " + lead + " raised the holy banner of " + faith + " against " + target + (allies.isEmpty() ? "." : ", and " + allies + " marched beside them.")
						: when + ", " + lead + " fell upon " + target + " in the name of " + faith + ", a war of zealots" + (allies.isEmpty() ? "." : ", with " + allies + " at their side.");
			}
			case PEACE -> when + ", " + actor + " and " + target + " laid down their arms.";
			case CROWNED -> when + ", " + who + " took the throne of " + actor + (sa > 0 ? ", to great joy." : sa < 0 ? ". Woe to their subjects." : ".");
			case REBELLION -> when + ", the people of " + e.place + " rose against " + target + (st < 0 ? " and threw off their chains." : ", ungrateful as they were.");
			case EXECUTION -> when + ", " + e.person + " was hanged in " + e.place + (sa > 0 ? " for their crimes." : sa < 0 ? ", a cruel death." : ".");
			case LEGEND_RISES -> when + ", " + who + " of " + actor + " became known as " + (e.extra.startsWith("A") || e.extra.startsWith("E") ? "an " : "a ")
					+ e.extra + (sa > 0 ? ". Songs are sung of them still." : sa < 0 ? ". May they be forgotten." : ".");
			case LEGEND_DIES -> when + ", " + who + " died" + (e.targetName.isEmpty() ? "" : " by the hand of " + e.targetName)
					+ (sa > 0 ? ". The whole land mourned." : sa < 0 ? ", and few wept." : ".");
			case PROPHET -> when + ", " + who + " saw a vision and spoke for " + e.extra + (sa > 0 ? ". Blessed is the day." : sa < 0 ? ", or so their followers claim." : ".");
			case NEW_FAITH -> when + ", " + e.person + " turned from " + e.targetName + " and founded " + e.extra + (sa > 0 ? ", a truer path." : sa < 0 ? ", a heresy." : ".");
			case CONVERSION -> when + ", " + actor + " embraced " + e.extra + ".";
			case PROPHECY_FULFILLED -> when + ", the words of " + e.person + " came true: " + e.place + " fell, as it was foretold.";
			case MIRACLE -> when + ", " + who + (sa >= 0 ? " worked a miracle before many witnesses." : " claimed a miracle, though wise folk doubted it.");
			case VILLAGE_SEIZED -> when + ", " + e.person + " seized the free village of " + e.place + (sa > 0 ? " and brought it order." : " and lorded over its people.");
			case EXODUS -> when + ", " + e.person + " left " + e.place + " with a few companions, seeking a land of their own.";
			case FALL -> when + ", " + actor + " were no more.";
		};
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
