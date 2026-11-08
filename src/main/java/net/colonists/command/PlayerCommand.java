package net.colonists.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.legend.History;
import net.colonists.player.Contracts;
import net.colonists.player.Household;
import net.colonists.player.PlayerData;
import net.colonists.player.Titles;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * /colony me                         – your renown card
 * /colony deeds                      – your Book of Deeds
 * /colony contracts                  – the notice board of the town you're in
 * /colony contract accept|abandon <n>, /colony contract list
 * /colony hire                       – hire (or renew) the colonist you look at as a retainer
 * /colony retinue [follow|stay|dismiss]
 * /colony property [buy|sell|rent]   – houses and workshops in colonist towns
 * /colony collect                    – withdraw rent and takings from the bankers
 * /colony propose                    – ask the colonist you look at to marry you
 * /colony faith [join <faith>]       – your faith; /colony pray at holy places
 */
public final class PlayerCommand {
	private PlayerCommand() {}

	private static int run(CommandContext<ServerCommandSource> ctx, Function<ServerPlayerEntity, String> action, String success) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		String err = action.apply(p);
		if (err != null) {
			ctx.getSource().sendError(Text.literal(err));
			return 0;
		}
		if (success != null) ctx.getSource().sendFeedback(() -> Text.literal(success).formatted(Formatting.GREEN), false);
		return 1;
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("colony")
				.then(CommandManager.literal("me").executes(PlayerCommand::me))
				.then(CommandManager.literal("deeds").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					p.giveItemStack(Household.bookOfDeeds(p));
					return 1;
				}))
				.then(CommandManager.literal("contracts").executes(PlayerCommand::board))
				.then(CommandManager.literal("contract")
						.then(CommandManager.literal("list").executes(PlayerCommand::myContracts))
						.then(CommandManager.literal("accept").then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(ctx -> run(ctx, p -> {
							Settlement s = Contracts.nearestTown(p);
							return s == null ? "There is no town here to take work from." : Contracts.accept(p, s, IntegerArgumentType.getInteger(ctx, "n"));
						}, null))))
						.then(CommandManager.literal("abandon").then(CommandManager.argument("n", IntegerArgumentType.integer(1))
								.executes(ctx -> run(ctx, p -> Contracts.abandon(p, IntegerArgumentType.getInteger(ctx, "n")), null)))))
				.then(CommandManager.literal("hire").executes(ctx -> run(ctx, Household::hire, null)))
				.then(CommandManager.literal("retinue")
						.executes(PlayerCommand::retinue)
						.then(CommandManager.literal("follow").executes(ctx -> retinueOrder(ctx, "follow")))
						.then(CommandManager.literal("stay").executes(ctx -> retinueOrder(ctx, "stay")))
						.then(CommandManager.literal("dismiss").executes(ctx -> retinueOrder(ctx, "dismiss"))))
				.then(CommandManager.literal("property")
						.executes(PlayerCommand::property)
						.then(CommandManager.literal("buy").executes(ctx -> run(ctx, Household::buy, "It's yours.")))
						.then(CommandManager.literal("sell").executes(ctx -> run(ctx, Household::sell, "Sold, for half what you paid.")))
						.then(CommandManager.literal("rent").executes(ctx -> run(ctx, Household::rent, "The house is let: a tenant will move in and pay you rent."))))
				.then(CommandManager.literal("collect").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
					int n = pr.bank;
					while (pr.bank > 0) {
						int k = Math.min(64, pr.bank);
						p.giveItemStack(new ItemStack(Items.EMERALD, k));
						pr.bank -= k;
					}
					ctx.getSource().sendFeedback(() -> Text.literal("The bankers hand you " + n + " emeralds."), false);
					return n;
				}))
				.then(CommandManager.literal("propose").executes(ctx -> run(ctx, Household::propose, null)))
				.then(CommandManager.literal("pray").executes(ctx -> run(ctx, Household::pray, null)))
				.then(CommandManager.literal("faith")
						.executes(PlayerCommand::faith)
						.then(CommandManager.literal("join").then(CommandManager.argument("faith", StringArgumentType.word())
								.suggests((c, b) -> CommandSource.suggestMatching(History.get(c.getSource().getServer()).religions.keySet(), b))
								.executes(ctx -> run(ctx, p -> Household.joinFaith(p, StringArgumentType.getString(ctx, "faith")), "You have taken up a new faith.")))))
		);
	}

	private static int me(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		var server = p.getServer();
		PlayerData.Profile pr = PlayerData.of(server, p.getUuid());
		FactionManager fm = FactionManager.get(server);
		History h = History.get(server);
		String title = Titles.best(server, p);
		p.sendMessage(Text.literal("═══ " + p.getName().getString() + (title.isEmpty() ? "" : " " + title) + " ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
		History.Legend l = h.living(p.getUuid());
		if (l != null) p.sendMessage(Text.literal("✦ Remembered as " + l.styled() + ", " + l.kind.label).formatted(Formatting.LIGHT_PURPLE), false);
		int[] r = h.renown(p.getUuid());
		p.sendMessage(Text.literal(String.format("Renown — adventure %d · valour %d · rule %d · piety %d", r[0], r[1], r[2], r[3])).formatted(Formatting.GRAY), false);
		if (!pr.titles.isEmpty()) p.sendMessage(Text.literal("Titles: " + String.join(", ", pr.titles)).formatted(Formatting.YELLOW), false);
		Faction mine = fm.get(fm.playerFaction(p.getUuid()));
		History.Religion faith = h.religion(pr.religion.isEmpty() && mine != null ? mine.religion : pr.religion);
		p.sendMessage(Text.literal("Realm: " + (mine == null ? "none" : mine.name) + "   Faith: " + (faith == null ? "none" : faith.name)
				+ "   Bank: " + pr.bank + " emeralds").formatted(Formatting.WHITE), false);
		p.sendMessage(Text.literal("Family: " + (pr.spouse == null ? "unmarried" : "married to " + pr.spouseName) + (pr.children.isEmpty() ? "" : ", " + pr.children.size() + " children")
				+ "   Retinue: " + Household.retinue(p).size() + "/" + Household.maxRetinue(server, p) + "   Property: " + pr.properties.size()
				+ "   Contracts: " + pr.contracts.size() + "/" + Contracts.MAX_ACTIVE).formatted(Formatting.WHITE), false);
		p.sendMessage(Text.literal("Deeds: " + Titles.card(server, p)).formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int board(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		Settlement s = Contracts.nearestTown(p);
		if (s == null) {
			ctx.getSource().sendError(Text.literal("There is no town here. Find a notice board in a colonist town."));
			return 0;
		}
		List<PlayerData.Contract> board = Contracts.board(p.getServer(), s);
		p.sendMessage(Text.literal("═══ Notice board of " + s.name + " ═══").formatted(Formatting.GOLD), false);
		if (board.isEmpty()) p.sendMessage(Text.literal("No work today.").formatted(Formatting.GRAY), false);
		for (int i = 0; i < board.size(); i++) {
			p.sendMessage(Text.literal(" " + (i + 1) + ". " + Contracts.describe(p.getServer(), board.get(i))).formatted(Formatting.WHITE), false);
		}
		p.sendMessage(Text.literal("Take one with /colony contract accept <number>.").formatted(Formatting.GRAY), false);
		return board.size();
	}

	private static int myContracts(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		if (pr.contracts.isEmpty()) p.sendMessage(Text.literal("You have no contracts.").formatted(Formatting.GRAY), false);
		for (int i = 0; i < pr.contracts.size(); i++) {
			p.sendMessage(Text.literal(" " + (i + 1) + ". " + Contracts.describe(p.getServer(), pr.contracts.get(i))), false);
		}
		return pr.contracts.size();
	}

	private static int retinue(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		List<ColonistEntity> list = Household.retinue(p);
		long day = p.getServer().getOverworld().getTimeOfDay() / 24000L;
		if (list.isEmpty()) p.sendMessage(Text.literal("You have no retainers nearby. Hire one with /colony hire (" + Household.HIRE_COST + " emeralds for " + Household.TERM_DAYS + " days).").formatted(Formatting.GRAY), false);
		for (ColonistEntity c : list) {
			String term = c.liegeUntil == Long.MAX_VALUE ? "sworn for life" : Math.max(0, c.liegeUntil - day) + " days left";
			p.sendMessage(Text.literal(" • " + c.fullName() + " — level " + c.retainerLevel() + ", " + c.retainerKills + " kills, "
					+ (int) c.getHealth() + "/" + (int) c.getMaxHealth() + " hp, " + term + (c.following ? "" : ", holding a post")), false);
		}
		return list.size();
	}

	private static int retinueOrder(CommandContext<ServerCommandSource> ctx, String order) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		int n = 0;
		for (ColonistEntity c : Household.retinue(p)) {
			n++;
			switch (order) {
				case "follow" -> c.following = true;
				case "stay" -> {
					c.following = false;
					c.guardPost = c.getBlockPos();
				}
				default -> {
					if (c.liegeUntil == Long.MAX_VALUE) continue; // your own children don't leave
					c.liege = null;
					pr.retinue.remove(c.getUuid());
					c.say("Farewell, then.");
				}
			}
		}
		int count = n;
		ctx.getSource().sendFeedback(() -> Text.literal(count + " retainers: " + order + "."), false);
		return n;
	}

	private static int property(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		FactionManager fm = FactionManager.get(p.getServer());
		Household.Lot here = Household.lotAt(p.getServer(), p.getBlockPos());
		if (here != null) {
			UUID owner = here.town().homes.get(here.origin());
			p.sendMessage(Text.literal("You stand on " + (here.kind().equals("home") ? "a house" : "the " + here.kind().replace('_', ' ')) + " in " + here.town().name
					+ (here.kind().equals("home") && owner == null ? " — empty, for sale (/colony property buy)" : "") + ".").formatted(Formatting.GRAY), false);
		}
		if (pr.properties.isEmpty()) p.sendMessage(Text.literal("You own no property.").formatted(Formatting.GRAY), false);
		for (PlayerData.Property prop : pr.properties) {
			Settlement s = fm.settlement(prop.town);
			p.sendMessage(Text.literal(" • " + (prop.kind.equals("home") ? "House" : prop.kind.replace('_', ' ')) + " in " + (s == null ? "?" : s.name)
					+ (prop.rented ? " (let to a tenant)" : "")), false);
		}
		p.sendMessage(Text.literal("Bank: " + pr.bank + " emeralds (/colony collect).").formatted(Formatting.GRAY), false);
		return pr.properties.size();
	}

	private static int faith(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		PlayerData.Profile pr = PlayerData.of(p.getServer(), p.getUuid());
		History h = History.get(p.getServer());
		History.Religion r = h.religion(pr.religion);
		p.sendMessage(Text.literal(r == null ? "You follow no faith. /colony faith join <faith> (see /colony religions)."
				: "You follow " + r.name + ". Piety " + h.renown(p.getUuid())[History.PIETY] + ". Pray daily at holy places with /colony pray.").formatted(Formatting.LIGHT_PURPLE), false);
		return 1;
	}
}
