package net.colonists.player;

import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.legend.History;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Titles a player earns by their deeds, and the one the world knows them by. */
public final class Titles {
	private Titles() {}

	/** A title and how grand it is (the grandest is the one people use). */
	record Title(String name, int weight) {
	}

	static List<Title> earned(MinecraftServer server, ServerPlayerEntity p, PlayerData.Profile pr) {
		List<Title> t = new ArrayList<>();
		FactionManager fm = FactionManager.get(server);
		History.Legend legend = History.get(server).living(p.getUuid());
		if (legend != null) t.add(new Title(legend.title + ", " + legend.kind.label, 60 + legend.kind.rank * 10));
		for (Faction f : fm.all()) {
			if (p.getUuid().equals(f.rulerPlayer)) t.add(new Title(f.government.title + " of the " + f.name, 100));
			int bane = pr.stat("bane:" + f.id);
			if (bane >= 10) t.add(new Title("Bane of the " + f.name, 40 + Math.min(20, bane)));
		}
		if (pr.stat("khanSlain") > 0) t.add(new Title("Bane of the Khan", 95));
		if (pr.stat("slew:HERETIC") > 0) t.add(new Title("Hand of the Faith", 80));
		if (pr.stat("slew:GREAT_RAID") > 0) t.add(new Title("Bane of the Barbarians", 92));
		if (pr.stat("chiefSlain") > 0) t.add(new Title("Breaker of Clans", 78));
		if (pr.stat("backed:won") > 0) t.add(new Title("Kingmaker", 75));
		for (String k : new String[]{"GREAT", "IMPERATOR", "MESSENGER", "PRETENDER", "REVOLT", "GOLDEN"}) if (pr.stat("slew:" + k) > 0) t.add(new Title("Slayer of Legends", 70));
		if (pr.stat("ruled") > 0) t.add(new Title("Former Ruler", 35));
		if (pr.stat("coups") > 0) t.add(new Title("Kingbreaker", 55));
		int soldiers = pr.stat("soldiers");
		if (soldiers >= 30) t.add(new Title("Warlord", 50));
		else if (soldiers >= 10) t.add(new Title("the Soldier", 25));
		if (pr.stat("villages") >= 3) t.add(new Title("the Explorer", 22));
		if (pr.stat("contracts") >= 15) t.add(new Title("Champion of the Realms", 48));
		else if (pr.stat("contracts") >= 5) t.add(new Title("the Reliable", 20));
		if (pr.stat("mercenaryDays") >= 5) t.add(new Title("Sellsword", 24));
		if (pr.stat("betrayals") >= 2) t.add(new Title("the Oathbreaker", 45));
		if (pr.stat("freed") >= 3) t.add(new Title("the Liberator", 42));
		if (pr.stat("murders") >= 3) t.add(new Title("the Murderer", 44));
		if (pr.stat("pilgrimages") >= 3) t.add(new Title("the Pilgrim", 21));
		if (pr.stat("businesses") >= 2) t.add(new Title("Merchant Prince", 33));
		else if (pr.stat("properties") >= 3) t.add(new Title("the Landholder", 23));
		if (pr.retinue.size() >= 3) t.add(new Title("Lord of a Retinue", 30));
		if (pr.spouse != null) t.add(new Title("Spouse of " + pr.spouseName, 12));
		if (pr.bank >= 500) t.add(new Title("the Wealthy", 26));
		return t;
	}

	public static String best(MinecraftServer server, ServerPlayerEntity p) {
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		return earned(server, p, pr).stream().max((a, b) -> a.weight - b.weight).map(Title::name).orElse("");
	}

	/** Once a minute: announce newly earned titles. */
	public static void tick(MinecraftServer server) {
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
			pr.name = p.getName().getString();
			for (Title t : earned(server, p, pr)) {
				if (pr.titles.contains(t.name)) continue;
				pr.titles.add(t.name);
				pr.deed(day, "became known as " + t.name);
				p.sendMessage(Text.literal("✦ You have earned a title: " + t.name).formatted(Formatting.GOLD), false);
				if (t.weight >= 40) {
					FactionManager.broadcast(server, Text.literal("✦ " + pr.name + " is now known as " + t.name + ".").formatted(Formatting.GOLD));
				}
			}
		}
	}

	/** How a colonist addresses a player. */
	public static String greeting(MinecraftServer server, ServerPlayerEntity p, int standing, boolean wanted, net.minecraft.util.math.random.Random r) {
		String title = best(server, p);
		String name = p.getName().getString() + (title.isEmpty() ? "" : " " + title);
		if (wanted) return pick(r, "You've got some nerve showing your face here, " + name + ".", "Guards! It's " + name + "!");
		if (standing >= 60) return pick(r, "Hail, " + name + "! An honour.", "Look, everyone, it's " + name + "!", "Well met, " + name + ". You're always welcome here.");
		if (standing <= -40) return pick(r, "Keep walking, " + name + ".", "We know what you are, " + name + ".");
		if (!title.isEmpty() && r.nextInt(2) == 0) return pick(r, "Aren't you " + name + "?", "I've heard tales of " + name + ".");
		return pick(r, "Good day, traveller.", "Hello there.", "Fine weather, isn't it?");
	}

	private static String pick(net.minecraft.util.math.random.Random r, String... s) {
		return s[r.nextInt(s.length)];
	}

	public static String card(MinecraftServer server, ServerPlayerEntity p) {
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : pr.stats.entrySet()) {
			if (!e.getKey().contains(":")) sb.append(e.getKey()).append(' ').append(e.getValue()).append(", ");
		}
		return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "no deeds yet";
	}
}
