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
				.then(CommandManager.literal("history")
						.executes(ctx -> history(ctx, 12))
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 60)).executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "count"))))));
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
