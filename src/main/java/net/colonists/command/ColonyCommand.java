package net.colonists.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.colonists.ColonistsMod;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Politics;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.List;
import java.util.UUID;

/**
 * /colony spawn [count] [faction]   – spawn colonists (ops)
 * /colony factions                  – list factions, relations and your standing
 * /colony found <name>              – start your own faction
 * /colony join <faction>            – join a faction that likes you (standing 40+)
 * /colony leave                     – leave your faction
 * /colony war|peace|ally <faction>  – diplomacy, for the founder of a player faction
 */
public final class ColonyCommand {
	private ColonyCommand() {}

	private static final SuggestionProvider<ServerCommandSource> FACTIONS = (ctx, builder) ->
			CommandSource.suggestMatching(FactionManager.get(ctx.getSource().getServer()).all().stream().map(f -> f.id), builder);

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("colony")
				.then(CommandManager.literal("spawn").requires(s -> s.hasPermissionLevel(2))
						.executes(ctx -> spawn(ctx, 1, null))
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 32))
								.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), null))
								.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), StringArgumentType.getString(ctx, "faction"))))))
				.then(CommandManager.literal("factions").executes(ColonyCommand::list))
				.then(CommandManager.literal("manors").executes(ColonyCommand::manors))
				.then(CommandManager.literal("politics")
						.executes(ctx -> politics(ctx, null))
						.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
								.executes(ctx -> politics(ctx, StringArgumentType.getString(ctx, "faction")))))
				.then(CommandManager.literal("vote")
						.then(CommandManager.argument("citizen", StringArgumentType.greedyString()).executes(ColonyCommand::vote)))
				.then(CommandManager.literal("found")
						.then(CommandManager.argument("name", StringArgumentType.greedyString()).executes(ColonyCommand::found)))
				.then(CommandManager.literal("join")
						.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS).executes(ColonyCommand::join)))
				.then(CommandManager.literal("leave").executes(ColonyCommand::leave))
				.then(CommandManager.literal("war")
						.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS).executes(ctx -> diplomacy(ctx, -80))))
				.then(CommandManager.literal("peace")
						.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS).executes(ctx -> diplomacy(ctx, 0))))
				.then(CommandManager.literal("ally")
						.then(CommandManager.argument("faction", StringArgumentType.word()).suggests(FACTIONS).executes(ctx -> diplomacy(ctx, 60))))
		);
	}

	private static int spawn(CommandContext<ServerCommandSource> ctx, int count, String factionId) {
		ServerCommandSource source = ctx.getSource();
		ServerWorld world = source.getWorld();
		FactionManager fm = FactionManager.get(source.getServer());
		if (factionId != null && fm.get(factionId) == null) {
			source.sendError(Text.literal("No faction called '" + factionId + "'. See /colony factions."));
			return 0;
		}
		Vec3d pos = source.getPosition();
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			int x = (int) Math.floor(pos.x) + world.random.nextInt(9) - 4;
			int z = (int) Math.floor(pos.z) + world.random.nextInt(9) - 4;
			BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			ColonistEntity colonist = ColonistsMod.COLONIST.create(world);
			if (colonist == null) continue;
			colonist.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
			colonist.pendingFaction = factionId;
			colonist.initialize(world, world.getLocalDifficulty(at), SpawnReason.COMMAND, null);
			world.spawnEntity(colonist);
			spawned++;
		}
		int n = spawned;
		source.sendFeedback(() -> Text.literal("Spawned " + n + " colonist" + (n == 1 ? "" : "s") + "."), true);
		return spawned;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource source = ctx.getSource();
		FactionManager fm = FactionManager.get(source.getServer());
		List<Faction> factions = fm.all();
		if (factions.isEmpty()) {
			source.sendFeedback(() -> Text.literal("There are no factions yet. Spawn some colonists!").formatted(Formatting.GRAY), false);
			return 0;
		}
		ServerPlayerEntity player = source.getPlayer();
		String mine = player == null ? null : fm.playerFaction(player.getUuid());
		source.sendFeedback(() -> Text.literal("Factions:").formatted(Formatting.BOLD), false);
		for (Faction f : factions) {
			MutableText line = Text.literal(" • ").append(f.displayName())
					.append(Text.literal(" (" + f.id + ") — " + f.realmTitle() + ", " + f.government.label + (f.rulerName.isEmpty() ? "" : " under " + f.rulerName)
							+ ", " + fm.memberCount(f.id) + " people in " + f.settlements.size() + (f.settlements.size() == 1 ? " town" : " towns")
							+ (f.atWar ? ", AT WAR" : "")).formatted(Formatting.GRAY));
			if (player != null) {
				int standing = fm.standing(player, f.id);
				line.append(Text.literal(" — you: ").formatted(Formatting.GRAY))
						.append(Text.literal(FactionManager.describe(standing) + " " + standing).formatted(FactionManager.describeColor(standing)));
			}
			source.sendFeedback(() -> line, false);
			for (Faction other : factions) {
				if (other == f || other.id.compareTo(f.id) < 0) continue;
				int rel = fm.relation(f.id, other.id);
				if (Math.abs(rel) < 15) continue;
				MutableText relLine = Text.literal("     ↔ ").formatted(Formatting.DARK_GRAY).append(other.displayName())
						.append(Text.literal(": " + FactionManager.describe(rel) + " (" + rel + ")").formatted(FactionManager.describeColor(rel)));
				source.sendFeedback(() -> relLine, false);
			}
		}
		if (mine != null) {
			Faction f = fm.get(mine);
			if (f != null) source.sendFeedback(() -> Text.literal("You are a member of the ").append(f.displayName()), false);
		}
		return factions.size();
	}

	private static int politics(CommandContext<ServerCommandSource> ctx, String factionId) {
		ServerCommandSource source = ctx.getSource();
		FactionManager fm = FactionManager.get(source.getServer());
		ServerPlayerEntity player = source.getPlayer();
		if (factionId == null && player != null) factionId = fm.playerFaction(player.getUuid());
		Faction f = fm.get(factionId);
		if (f == null) {
			source.sendError(Text.literal("Which faction? /colony politics <faction>"));
			return 0;
		}
		List<Text> lines = new java.util.ArrayList<>();
		lines.add(Text.literal("═══ ").formatted(Formatting.DARK_GRAY).append(f.displayName().formatted(Formatting.BOLD))
				.append(Text.literal(" ═══").formatted(Formatting.DARK_GRAY)));
		lines.add(Text.literal("Realm: " + f.realmTitle() + " of " + f.settlements.size() + (f.settlements.size() == 1 ? " settlement" : " settlements"))
				.formatted(Formatting.YELLOW));
		lines.add(Text.literal("Government: " + f.government.label
				+ (f.rulerName.isEmpty() ? "" : "   " + f.government.title + ": " + f.rulerName)
				+ (f.government == Faction.Government.MONARCHY && !f.dynasty.isEmpty() ? " (House " + f.dynasty + ")" : "")));
		lines.add(Text.literal("Approval: " + f.approval + "%   War weariness: " + f.warWeariness
				+ "   Food in stores: " + f.totalFood()).formatted(f.approval < 30 ? Formatting.RED : Formatting.WHITE));
		lines.add(Text.literal("People: " + f.adults() + " adults, " + (f.citizens.size() - f.adults()) + " children   Houses: "
				+ f.totalHomes()));
		for (net.colonists.faction.Settlement st : f.settlements.values()) {
			MutableText row = Text.literal(st.isCapital() ? " ★ " : " • ").formatted(Formatting.GOLD).append(st.displayName())
					.append(Text.literal(" — " + st.rank.label + ", pop. " + st.population() + ", " + st.homes.size() + " houses, treasury " + st.treasury
							+ (st.cityCenter == null ? "" : " @ " + st.cityCenter.getX() + ", " + st.cityCenter.getZ())
							+ (st.wallPlanned && !st.wallBuilt ? ", wall " + st.wallProgress + "%" : "")
							+ (st.isCapital() ? "" : ", loyalty " + st.loyalty + "%")
							+ (st.occupation > 0 ? ", UNDER SIEGE" : "")
							+ (st.rank.ordinal() < Faction.Rank.CITY_STATE.ordinal() ? " (next: " + nextRank(st) + ")" : "")).formatted(Formatting.GRAY));
			lines.add(row);
		}
		net.colonists.faction.Settlement goal = fm.settlement(f.warGoal);
		if (goal != null && goal.faction != null) {
			lines.add(Text.literal("⚔ War goal: ").formatted(Formatting.RED).append(goal.displayName())
					.append(Text.literal(" of the ").formatted(Formatting.RED)).append(goal.faction.displayName())
					.append(Text.literal(goal.occupation > 0 && goal.occupier.equals(f.id)
							? " (siege " + goal.occupation * 100 / net.colonists.faction.Empire.SIEGE_POINTS + "%)" : "").formatted(Formatting.RED)));
		}
		StringBuilder jobs = new StringBuilder("Trades: ");
		for (ColonistEntity.Job job : ColonistEntity.Job.values()) {
			if (job == ColonistEntity.Job.NONE) continue;
			jobs.append(job.label).append(" ").append(f.countJob(job.name())).append("  ");
		}
		lines.add(Text.literal(jobs.toString()).formatted(Formatting.GRAY));
		lines.add(Text.literal("Laws: captivity " + (f.slavery ? "PRACTISED (" + f.captives + " captives)" : "forbidden"))
				.formatted(f.slavery ? Formatting.DARK_RED : Formatting.GREEN));
		java.util.Map<String, Integer> deaths = f.recentDeaths(source.getWorld().getTimeOfDay() / 24000L, 7);
		if (!deaths.isEmpty()) {
			StringBuilder sb = new StringBuilder("Deaths (last 7 days): ");
			deaths.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
					.forEach(e -> sb.append(e.getValue()).append(" ").append(e.getKey()).append(", "));
			lines.add(Text.literal(sb.substring(0, sb.length() - 2)).formatted(Formatting.GRAY));
		}
		Faction lord = fm.get(f.overlord);
		if (lord != null) lines.add(Text.literal("Tributary of the ").append(lord.displayName()));
		for (Faction other : fm.all()) {
			if (other.overlord.equals(f.id)) lines.add(Text.literal("Receives tribute from the ").append(other.displayName()));
		}
		if (f.government == Faction.Government.REPUBLIC) {
			long next = Math.max(0, Politics.ELECTION_INTERVAL - (source.getWorld().getTime() - f.lastElection)) / 1200;
			lines.add(Text.literal("Next election in about " + next + " minutes. Members can /colony vote <name>.").formatted(Formatting.AQUA));
		}
		for (Text t : lines) source.sendFeedback(() -> t, false);
		return 1;
	}

	private static String nextRank(net.colonists.faction.Settlement f) {
		return switch (f.rank) {
			case VILLAGE -> "Town at 6 houses";
			case TOWN -> "City at 15 houses";
			case CITY -> "City-State once the wall is finished";
			case CITY_STATE -> "";
		};
	}

	private static int vote(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(ctx.getSource().getServer());
		Faction f = fm.get(fm.playerFaction(player.getUuid()));
		if (f == null || f.government != Faction.Government.REPUBLIC) {
			ctx.getSource().sendError(Text.literal("You must be a member of a Republic to vote."));
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "citizen").trim();
		for (java.util.Map.Entry<UUID, Faction.Citizen> e : f.citizens.entrySet()) {
			if (!e.getValue().child && (e.getValue().fullName().equalsIgnoreCase(name) || e.getValue().name.equalsIgnoreCase(name))) {
				f.playerVotes.put(player.getUuid(), e.getKey());
				fm.markDirty();
				String chosen = e.getValue().fullName();
				ctx.getSource().sendFeedback(() -> Text.literal("Your vote for " + chosen + " will count at the next election."), false);
				return 1;
			}
		}
		ctx.getSource().sendError(Text.literal("No adult citizen called '" + name + "' in the " + f.name + "."));
		return 0;
	}

	private static int found(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(ctx.getSource().getServer());
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.length() < 2 || name.length() > 32) {
			ctx.getSource().sendError(Text.literal("Pick a name between 2 and 32 characters."));
			return 0;
		}
		Faction f = fm.createFaction(player.getRandom(), name);
		f.founderPlayer = player.getUuid();
		f.rulerPlayer = player.getUuid();
		f.rulerName = player.getName().getString();
		fm.setPlayerFaction(player.getUuid(), f.id);
		fm.markDirty();
		FactionManager.broadcast(ctx.getSource().getServer(), Text.literal(player.getName().getString() + " has founded the ")
				.formatted(Formatting.YELLOW).append(f.displayName()).append(Text.literal(". Recruit colonists by sneak-right-clicking them with an emerald.").formatted(Formatting.YELLOW)));
		return 1;
	}

	private static int join(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(ctx.getSource().getServer());
		Faction f = fm.get(StringArgumentType.getString(ctx, "faction"));
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction."));
			return 0;
		}
		int standing = fm.standing(player, f.id);
		if (standing < 40 && !player.isCreative()) {
			ctx.getSource().sendError(Text.literal("The " + f.name + " don't trust you enough yet (standing " + standing
					+ ", need 40). Give their members gifts: food, iron, emeralds, diamonds."));
			return 0;
		}
		fm.setPlayerFaction(player.getUuid(), f.id);
		Faction joined = f;
		ctx.getSource().sendFeedback(() -> Text.literal("You have joined the ").append(joined.displayName())
				.append(". Its members will defend you, and its enemies are now yours."), false);
		return 1;
	}

	private static int leave(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(ctx.getSource().getServer());
		String id = fm.playerFaction(player.getUuid());
		if (id == null) {
			ctx.getSource().sendError(Text.literal("You are not in a faction."));
			return 0;
		}
		fm.setPlayerFaction(player.getUuid(), null);
		Faction f = fm.get(id);
		if (f != null && player.getUuid().equals(f.rulerPlayer)) {
			f.rulerPlayer = null;
			f.ruler = null;
		}
		if (f != null) {
			fm.changeRep(ctx.getSource().getServer(), player, id, -10);
			if (fm.memberCount(id) == 0 && f.members.isEmpty()) fm.removeMember(ctx.getSource().getServer(), id, UUID.randomUUID());
		}
		ctx.getSource().sendFeedback(() -> Text.literal("You left your faction."), false);
		return 1;
	}

	private static int diplomacy(CommandContext<ServerCommandSource> ctx, int value) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		FactionManager fm = FactionManager.get(ctx.getSource().getServer());
		String mine = fm.playerFaction(player.getUuid());
		Faction own = fm.get(mine);
		if (own == null || !player.getUuid().equals(own.rulerPlayer)) {
			ctx.getSource().sendError(Text.literal("Only the ruler of your faction can do that. (/colony found <name>, or take power from the atlas)"));
			return 0;
		}
		Faction other = fm.get(StringArgumentType.getString(ctx, "faction"));
		if (other == null || other == own) {
			ctx.getSource().sendError(Text.literal("No such faction."));
			return 0;
		}
		if (value > 0 && fm.relation(own.id, other.id) < 15) {
			ctx.getSource().sendError(Text.literal("The " + other.name + " won't ally with you until relations are at least Friendly (15)."));
			return 0;
		}
		if (value == 0 && fm.relation(own.id, other.id) < -70) {
			ctx.getSource().sendError(Text.literal("The " + other.name + " refuse to talk peace while the wounds are this fresh."));
			return 0;
		}
		fm.setRelation(ctx.getSource().getServer(), own.id, other.id, value);
		return 1;
	}

	/** The manors of the nearest town: lords, fields and crops, laborers, and what they have grown. */
	private static int manors(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		FactionManager fm = FactionManager.get(src.getServer());
		net.colonists.faction.Settlement s = fm.nearestSettlement(net.minecraft.util.math.BlockPos.ofFloored(src.getPosition()), null);
		if (s == null) {
			src.sendError(Text.literal("There is no town nearby."));
			return 0;
		}
		if (s.manors.isEmpty()) {
			src.sendFeedback(() -> Text.literal(s.name + " has no manors yet (a town of 5 or more founds its first).").formatted(Formatting.GRAY), false);
			return 0;
		}
		src.sendFeedback(() -> Text.literal("═══ Manors of " + s.name + " ═══").formatted(Formatting.GOLD), false);
		for (net.colonists.farm.Manor m : s.manors) {
			StringBuilder crops = new StringBuilder();
			for (net.colonists.farm.Manor.Field f : m.fields) {
				if (crops.length() > 0) crops.append(", ");
				crops.append(net.colonists.farm.Manors.Crop.of(f.crop).label).append(" ").append(f.width()).append("×").append(f.depth());
			}
			src.sendFeedback(() -> Text.literal("🌾 " + m.name).formatted(Formatting.YELLOW)
					.append(Text.literal(" — lord " + (m.lordName.isEmpty() ? "none" : m.lordName) + ", " + m.laborers.size() + " laborers, "
							+ m.area() + " blocks of land, " + m.harvestedTotal + " crops brought in").formatted(Formatting.GRAY)), false);
			src.sendFeedback(() -> Text.literal("    Fields: " + crops).formatted(Formatting.DARK_GREEN), false);
		}
		return s.manors.size();
	}
}
