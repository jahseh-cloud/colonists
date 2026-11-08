package net.colonists.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.legend.History;
import net.colonists.legend.Tenet;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * /colony religions        – the faiths of the world, their teachings and prophets
 * /colony legends          – the world's legends, living and dead
 * /colony history [count]  – the plain record of recent events
 */
public final class LegendCommand {
	private LegendCommand() {}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("colony")
				.then(CommandManager.literal("religions").executes(LegendCommand::religions))
				.then(CommandManager.literal("legends").executes(LegendCommand::legends))
				.then(CommandManager.literal("ages").executes(LegendCommand::ages))
				.then(CommandManager.literal("wonders").executes(LegendCommand::wonders))
				.then(CommandManager.literal("banner").executes(LegendCommand::banner))
				.then(CommandManager.literal("crises").executes(LegendCommand::crises)
						.then(CommandManager.literal("start").requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
										.suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(new String[]{"messenger", "khan", "imperator", "great"}, b))
										.executes(LegendCommand::startCrisis))))
				.then(CommandManager.literal("history")
						.executes(ctx -> history(ctx, 12))
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 60)).executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "count"))))));
	}

	private static int crises(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		if (h.crises.isEmpty()) {
			src.sendFeedback(() -> Text.literal("The world has known no great crisis yet.").formatted(Formatting.GRAY), false);
			return 0;
		}
		for (History.Crisis c : h.crises) {
			String label = net.colonists.legend.Crises.Type.valueOf(c.type).label;
			String state = !c.active() ? "ended day " + (c.ended + 1) : c.figure == null ? "the Caliphs rule (" + c.caliphs + " so far)" : "ONGOING";
			src.sendFeedback(() -> Text.literal("☄ " + label + ": " + c.figureName).formatted(c.active() ? Formatting.RED : Formatting.GRAY)
					.append(Text.literal(" — began day " + (c.started + 1) + ", " + state).formatted(Formatting.GRAY)), false);
		}
		for (History.Fracture fr : h.fractures) src.sendFeedback(() -> Text.literal("💔 The " + fr.name + " broke apart on day " + (fr.day + 1)
				+ (fr.claimed ? "; a restorer has risen." : "; a restorer may rise around day " + (fr.restorerDay + 1) + ".")).formatted(Formatting.GOLD), false);
		return h.crises.size();
	}

	private static int startCrisis(CommandContext<ServerCommandSource> ctx) {
		String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type").toUpperCase();
		net.colonists.legend.Crises.Type t;
		try {
			t = net.colonists.legend.Crises.Type.valueOf(name);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Text.literal("Unknown crisis. Try messenger, khan, imperator or great."));
			return 0;
		}
		String fail = net.colonists.legend.Crises.start(ctx.getSource().getServer(), t, null);
		if (fail != null) {
			ctx.getSource().sendError(Text.literal(fail));
			return 0;
		}
		return 1;
	}

	private static int religions(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		FactionManager fm = FactionManager.get(src.getServer());
		if (h.religions.isEmpty()) {
			src.sendFeedback(() -> Text.literal("No faiths yet.").formatted(Formatting.GRAY), false);
			return 0;
		}
		for (History.Religion r : h.religions.values()) {
			List<String> realms = new ArrayList<>();
			for (Faction f : fm.all()) if (f.religion.equals(r.id)) realms.add(f.name);
			History.Legend prophet = r.prophet == null ? null : h.legends.get(r.prophet);
			History.Religion parent = h.religion(r.parent);
			src.sendFeedback(() -> Text.literal("☩ " + r.name).withColor(r.color).formatted(Formatting.BOLD), false);
			for (Tenet t : r.tenets) src.sendFeedback(() -> Text.literal("   " + t.label + " — " + t.description).formatted(Formatting.GRAY), false);
			src.sendFeedback(() -> Text.literal("   Founded day " + (r.foundedDay + 1) + " by " + r.founder + (parent == null ? "" : ", from " + parent.name)
					+ ". Prophet: " + (prophet == null ? "none (one may rise around day " + (r.nextProphetDay + 1) + ")" : prophet.styled() + ", " + prophet.kind.label)).formatted(Formatting.GRAY), false);
			src.sendFeedback(() -> Text.literal("   Realms: " + (realms.isEmpty() ? "none" : String.join(", ", realms))).formatted(Formatting.GRAY), false);
		}
		return h.religions.size();
	}

	private static int legends(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		if (h.legends.isEmpty()) {
			src.sendFeedback(() -> Text.literal("No legends yet. Deeds, and a little fortune, make them.").formatted(Formatting.GRAY), false);
			return 0;
		}
		List<History.Legend> list = new ArrayList<>(h.legends.values());
		list.sort((a, b) -> b.kind.rank - a.kind.rank);
		for (History.Legend l : list) {
			History.Religion r = h.religion(l.religion);
			src.sendFeedback(() -> Text.literal((l.alive ? "✦ " : "† ") + l.styled()).formatted(l.kind.prophet() ? Formatting.LIGHT_PURPLE : Formatting.GOLD)
					.append(Text.literal(" — " + l.kind.label + " of the " + l.factionName + (r == null ? "" : ", " + r.name)
							+ (l.alive ? "" : " (died day " + (l.died + 1) + ")") + (l.player ? " [player]" : "")).formatted(Formatting.GRAY)), false);
		}
		return list.size();
	}

	private static int ages(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		FactionManager fm = FactionManager.get(src.getServer());
		String age = net.colonists.legend.Ages.name(h);
		src.sendFeedback(() -> Text.literal("⌛ This is " + age + ".").formatted(Formatting.AQUA, Formatting.BOLD), false);
		src.sendFeedback(() -> Text.literal(String.format("   Milestone: %s · Fortune %s (%.0f) · Faith %s (%.0f) · War %s (%.0f)",
				net.colonists.legend.Ages.MILESTONES[h.milestone],
				h.prosperity > 0 ? "golden" : h.prosperity < 0 ? "dark" : "steady", h.prosperityMeter,
				h.faith > 0 ? "fervent" : h.faith < 0 ? "reasoned" : "quiet", h.faithMeter,
				h.strife > 0 ? "strife" : h.strife < 0 ? "peace" : "uneasy", h.strifeMeter)).formatted(Formatting.GRAY), false);
		for (Faction f : fm.all()) {
			if (f.freeVillage) continue;
			src.sendFeedback(() -> Text.literal("   ").append(f.displayName()).append(Text.literal(": " + net.colonists.legend.Ages.ERAS[Math.min(3, f.era)] + " Era").formatted(Formatting.GRAY)), false);
		}
		return 1;
	}

	private static int wonders(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		FactionManager fm = FactionManager.get(src.getServer());
		for (net.colonists.legend.Wonders.Type t : net.colonists.legend.Wonders.Type.values()) {
			History.Wonder w = h.wonders.get(t.name());
			String state;
			if (w != null) {
				net.colonists.faction.Settlement s = fm.settlement(w.settlement);
				state = (w.ruined ? "IN RUINS at " : "stands in ") + (s == null ? "a lost city" : s.name + (s.faction == null ? "" : " (" + s.faction.name + ")"))
						+ ", raised day " + (w.builtDay + 1);
			} else {
				List<String> builders = new ArrayList<>();
				for (Faction f : fm.all()) if (f.wonderProject.equals(t.name())) builders.add(f.name);
				state = builders.isEmpty() ? "not yet begun (needs the " + net.colonists.legend.Ages.ERAS[t.era] + " Era)" : "being raised by " + String.join(", ", builders);
			}
			String line = state;
			src.sendFeedback(() -> Text.literal("◆ " + t.label()).formatted(w != null && !w.ruined ? Formatting.GOLD : Formatting.GRAY)
					.append(Text.literal(" — " + line + ". " + t.effect).formatted(Formatting.GRAY)), false);
		}
		return h.wonders.size();
	}

	private static int banner(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		net.minecraft.server.network.ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(p.getServer());
		Faction f = fm.get(fm.playerFaction(p.getUuid()));
		if (f == null || f.banner.isEmpty()) {
			ctx.getSource().sendError(Text.literal("You fly no nation's banner. Join or found one first."));
			return 0;
		}
		p.giveItemStack(net.colonists.faction.Banners.item(p.getWorld().getRegistryManager(), f));
		ctx.getSource().sendFeedback(() -> Text.literal("You take up the banner of the ").append(f.displayName()), false);
		return 1;
	}

	private static int history(CommandContext<ServerCommandSource> ctx, int count) {
		ServerCommandSource src = ctx.getSource();
		History h = History.get(src.getServer());
		int from = Math.max(0, h.events.size() - count);
		for (int i = from; i < h.events.size(); i++) {
			History.Event e = h.events.get(i);
			String who = e.person.isEmpty() ? e.actorName : e.person + (e.actorName.isEmpty() ? "" : " (" + e.actorName + ")");
			String line = "Day " + (e.day + 1) + " · " + e.type.name().toLowerCase().replace('_', ' ') + " · " + who
					+ (e.targetName.isEmpty() ? "" : " → " + e.targetName) + (e.place.isEmpty() ? "" : " @ " + e.place)
					+ (e.extra.isEmpty() ? "" : " · " + e.extra.replace("|", "; "));
			src.sendFeedback(() -> Text.literal(line).formatted(Formatting.GRAY), false);
		}
		return h.events.size() - from;
	}
}
