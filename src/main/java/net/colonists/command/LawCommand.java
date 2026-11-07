package net.colonists.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.colonists.economy.Economy;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Stockpile;
import net.colonists.law.Courts;
import net.colonists.law.Justice;
import net.colonists.law.Law;
import net.colonists.law.Slavery;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;

/**
 * /colony laws [faction]          – a faction's law book
 * /colony wanted                  – where you are wanted, and for what
 * /colony bid <emeralds>          – bid at the slave auction closing soonest
 * /colony freedom [buy]           – your owner and the price of your freedom
 * /colony free <player>           – buy another player's freedom
 * /colony estate                  – (slave owners) move your estate to where you stand
 * /colony market                  – prices and the treasury of the town you are in
 * /colony shop <price> <batch>    – sell the contents of the chest you look at to the town
 * /colony shop remove             – close that shop
 */
public final class LawCommand {
	private LawCommand() {}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("colony")
				.then(CommandManager.literal("laws")
						.executes(ctx -> laws(ctx, null))
						.then(CommandManager.argument("faction", StringArgumentType.word())
								.suggests((ctx, b) -> CommandSource.suggestMatching(FactionManager.get(ctx.getSource().getServer()).all().stream().map(f -> f.id), b))
								.executes(ctx -> laws(ctx, StringArgumentType.getString(ctx, "faction")))))
				.then(CommandManager.literal("wanted").executes(LawCommand::wanted))
				.then(CommandManager.literal("bid")
						.then(CommandManager.argument("emeralds", IntegerArgumentType.integer(1)).executes(ctx -> {
							String err = Slavery.bid(ctx.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(ctx, "emeralds"));
							if (err != null) ctx.getSource().sendError(Text.literal(err));
							return err == null ? 1 : 0;
						})))
				.then(CommandManager.literal("freedom")
						.executes(LawCommand::freedom)
						.then(CommandManager.literal("buy").executes(ctx -> {
							String err = Slavery.buyFreedom(ctx.getSource().getPlayerOrThrow(), null);
							if (err != null) ctx.getSource().sendError(Text.literal(err));
							return err == null ? 1 : 0;
						})))
				.then(CommandManager.literal("free")
						.then(CommandManager.argument("player", StringArgumentType.word()).executes(ctx -> {
							ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "player"));
							if (target == null) {
								ctx.getSource().sendError(Text.literal("No such player online."));
								return 0;
							}
							String err = Slavery.buyFreedom(ctx.getSource().getPlayerOrThrow(), target.getUuid());
							if (err != null) ctx.getSource().sendError(Text.literal(err));
							return err == null ? 1 : 0;
						})))
				.then(CommandManager.literal("estate").executes(ctx -> {
					int n = Slavery.setEstate(ctx.getSource().getPlayerOrThrow());
					ctx.getSource().sendFeedback(() -> Text.literal(n == 0 ? "You don't own any slaves." : "Your estate is now here (" + n + " bound to it)."), false);
					return n;
				}))
				.then(CommandManager.literal("market").executes(LawCommand::market))
				.then(CommandManager.literal("shop")
						.then(CommandManager.literal("remove").executes(LawCommand::closeShop))
						.then(CommandManager.argument("price", IntegerArgumentType.integer(1, 64))
								.then(CommandManager.argument("batch", IntegerArgumentType.integer(1, 64)).executes(LawCommand::openShop))))
		);
	}

	private static int laws(CommandContext<ServerCommandSource> ctx, String factionId) {
		ServerCommandSource src = ctx.getSource();
		FactionManager fm = FactionManager.get(src.getServer());
		if (factionId == null && src.getPlayer() != null) {
			Settlement here = Courts.territoryAt(fm, src.getPlayer().getBlockPos(), src.getWorld().getTime());
			factionId = here != null ? here.faction.id : fm.playerFaction(src.getPlayer().getUuid());
		}
		Faction f = fm.get(factionId);
		if (f == null) {
			src.sendError(Text.literal("Which faction? /colony laws <faction> (or stand in one of their towns)"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("═══ Laws of the ").formatted(Formatting.DARK_GRAY).append(f.displayName())
				.append(Text.literal(" ═══").formatted(Formatting.DARK_GRAY)), false);
		for (Law l : Law.values()) {
			boolean on = f.laws.contains(l);
			src.sendFeedback(() -> Text.literal((on ? " ✔ " : " ✘ ") + l.label + " — " + l.description)
					.formatted(on ? Formatting.WHITE : Formatting.DARK_GRAY), false);
		}
		src.sendFeedback(() -> Text.literal((f.slavery ? " ✔ " : " ✘ ") + "Captivity — convicts and captives may be enslaved and sold")
				.formatted(f.slavery ? Formatting.DARK_RED : Formatting.DARK_GRAY), false);
		src.sendFeedback(() -> Text.literal("Sentences: small crimes are fined (" + Courts.FINE_PER_HEAT + " emeralds per heat); worse ones mean prison or the work camp; "
				+ "murder and treason mean the gallows (if there is a death penalty) or slavery (if captivity is practised).").formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int wanted(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		Justice j = Justice.get(p.getServer());
		FactionManager fm = FactionManager.get(p.getServer());
		int n = 0;
		for (Map.Entry<String, Map<UUID, Justice.Record>> e : j.records.entrySet()) {
			Justice.Record r = e.getValue().get(p.getUuid());
			Faction f = fm.get(e.getKey());
			if (r == null || f == null) continue;
			n++;
			String what = r.exiled ? "EXILED" : "wanted (heat " + r.heat + ", fine " + r.heat * Courts.FINE_PER_HEAT + ") for " + String.join(", ", r.charges);
			p.sendMessage(Text.literal("⚖ ").formatted(Formatting.RED).append(f.displayName()).append(Text.literal(": " + what).formatted(Formatting.RED)), false);
		}
		Justice.Prisoner pr = j.prisoners.get(p.getUuid());
		if (pr != null) p.sendMessage(Text.literal("You are serving a sentence: " + pr.sentence.label + ".").formatted(Formatting.GOLD), false);
		if (n == 0 && pr == null) p.sendMessage(Text.literal("Nobody is looking for you. For now.").formatted(Formatting.GREEN), false);
		return n;
	}

	private static int freedom(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		Justice j = Justice.get(p.getServer());
		Justice.Slave sl = j.slaves.get(p.getUuid());
		if (sl == null) {
			int owned = j.slavesOwnedBy(p.getUuid());
			p.sendMessage(Text.literal(owned > 0 ? "You are free, and you own " + owned + " slave(s). /colony estate sets where they are bound."
					: "You are free.").formatted(Formatting.GREEN), false);
			return 0;
		}
		p.sendMessage(Text.literal("⛓ You belong to " + (sl.ownerName.isEmpty() ? "the town" : sl.ownerName) + ". Estate: " + sl.estate.toShortString()
				+ ". Missed tasks: " + sl.missed + ". Freedom costs " + Slavery.freedomPrice(sl) + " emeralds — /colony freedom buy.").formatted(Formatting.GOLD), false);
		return 1;
	}

	private static int market(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		ServerWorld world = (ServerWorld) p.getWorld();
		FactionManager fm = FactionManager.get(world.getServer());
		Settlement s = Courts.territoryAt(fm, p.getBlockPos(), world.getTime());
		if (s == null) s = fm.nearestSettlement(p.getBlockPos(), null);
		if (s == null) {
			ctx.getSource().sendError(Text.literal("There are no towns."));
			return 0;
		}
		Settlement town = s;
		p.sendMessage(Text.literal("═══ Market of ").formatted(Formatting.DARK_GRAY).append(town.displayName())
				.append(Text.literal(" — treasury " + town.treasury + " emeralds" + (town.arrears > 0 ? " (wages unpaid!)" : "") + " ═══").formatted(Formatting.DARK_GRAY)), false);
		var chests = Stockpile.chests(world, town);
		for (Economy.Good g : Economy.Good.values()) {
			int stock = Stockpile.count(chests, g.is);
			int per = Economy.perEmerald(town, g, stock);
			p.sendMessage(Text.literal(String.format(" %-8s stock %4d   %d per emerald%s", g.name().toLowerCase(), stock, per,
					stock < g.target ? "  (wanted)" : "")).formatted(stock < g.target ? Formatting.YELLOW : Formatting.WHITE), false);
		}
		p.sendMessage(Text.literal("Trade at the market stall (right-click the merchant), or set up a shop chest here: /colony shop <price> <batch>.")
				.formatted(Formatting.GRAY), false);
		return 1;
	}

	private static BlockPos lookedAt(ServerPlayerEntity p) {
		HitResult hit = p.raycast(6, 0, false);
		return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
	}

	private static int openShop(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		BlockPos pos = lookedAt(p);
		if (pos == null) {
			ctx.getSource().sendError(Text.literal("Look at a chest."));
			return 0;
		}
		int price = IntegerArgumentType.getInteger(ctx, "price"), batch = IntegerArgumentType.getInteger(ctx, "batch");
		String err = Economy.openShop((ServerWorld) p.getWorld(), p, pos, price, batch);
		if (err != null) {
			ctx.getSource().sendError(Text.literal(err));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("Shop open: " + batch + " items for " + price + " emeralds. The town buys what it is short of, "
				+ "when your price is fair, and pays into the chest.").formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int closeShop(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		BlockPos pos = lookedAt(p);
		Justice j = Justice.get(p.getServer());
		Justice.Shop shop = pos == null ? null : j.shops.get(pos);
		if (shop == null || !shop.owner.equals(p.getUuid())) {
			ctx.getSource().sendError(Text.literal("Look at one of your shop chests."));
			return 0;
		}
		j.shops.remove(pos);
		j.markDirty();
		ctx.getSource().sendFeedback(() -> Text.literal("Shop closed. It earned " + shop.earned + " emeralds in all."), false);
		return 1;
	}
}
