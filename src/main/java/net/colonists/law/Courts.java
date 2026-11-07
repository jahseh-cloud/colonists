package net.colonists.law;

import net.colonists.build.Blueprint;
import net.colonists.build.Blueprints;
import net.colonists.economy.Economy;
import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.colonists.faction.Stockpile;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crime and punishment: witnesses report crimes, guards subdue (not kill) the wanted, and the town's ruler hands
 * down a sentence: a fine, prison, the work camp, slavery, exile or the gallows.
 */
public final class Courts {
	private Courts() {}

	public static final int FINE_PER_HEAT = 4;
	/** Health a subdued criminal is left with. */
	private static final float SUBDUED = 4f;

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register(Courts::onBreak);
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world instanceof ServerWorld sw && hand == Hand.MAIN_HAND) onUseBlock(sw, player, hit.getBlockPos());
			return ActionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world instanceof ServerWorld sw && entity instanceof ColonistEntity c) onAttack(sw, player, c);
			return ActionResult.PASS;
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Courts::allowDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register(Courts::afterDeath);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTicks() % 20 == 0) tick(server);
		});
	}

	// ---------------------------------------------------------------- territory & witnesses

	/** The settlement whose streets, fields or walls contain this spot, if any. */
	@Nullable
	public static Settlement territoryAt(FactionManager fm, BlockPos pos, long now) {
		for (Settlement s : fm.allSettlements()) {
			if (s.faction != null && s.cityCenter != null && s.contains(pos, now, 0)) return s;
		}
		return null;
	}

	public static boolean isMember(FactionManager fm, PlayerEntity p, Faction f) {
		return f.id.equals(fm.playerFaction(p.getUuid()));
	}

	@Nullable
	static ColonistEntity witness(ServerWorld world, Faction f, LivingEntity criminal) {
		return world.getEntitiesByClass(ColonistEntity.class, criminal.getBoundingBox().expand(16, 8, 16),
						c -> c != criminal && c.isAlive() && !c.isBaby() && !c.isSleeping() && c.getFactionId().equals(f.id)
								&& !isPrisoner(world.getServer(), c.getUuid()) && c.canSee(criminal))
				.stream().min(Comparator.comparingDouble(c -> c.squaredDistanceTo(criminal))).orElse(null);
	}

	public static boolean isPrisoner(MinecraftServer server, UUID who) {
		return Justice.get(server).prisoners.containsKey(who);
	}

	@Nullable
	public static Justice.Prisoner prisoner(MinecraftServer server, UUID who) {
		return Justice.get(server).prisoners.get(who);
	}

	// ---------------------------------------------------------------- reporting

	/** Records a crime if it is against this town's laws and (unless needWitness is false) somebody saw it. */
	public static void report(ServerWorld world, @Nullable Settlement s, LivingEntity criminal, Law.Crime crime, boolean needWitness) {
		if (s == null || s.faction == null) return;
		Faction f = s.faction;
		if (crime.law != null && !f.laws.contains(crime.law)) return;
		FactionManager fm = FactionManager.get(world.getServer());
		Justice j = Justice.get(world.getServer());
		if (criminal instanceof PlayerEntity p) {
			if (p.isCreative() || p.isSpectator()) return;
			if (p.getUuid().equals(f.founderPlayer)) return;
			if (fm.standing(p, f.id) <= FactionManager.WAR) return; // that's war, not crime
		} else if (criminal instanceof ColonistEntity c) {
			if (c.isBaby() || fm.relation(f.id, c.getFactionId()) <= FactionManager.WAR) return;
		} else {
			return;
		}
		if (j.prisoners.containsKey(criminal.getUuid())) return;
		ColonistEntity w = witness(world, f, criminal);
		if (needWitness && w == null) return;

		long now = world.getTime();
		Justice.Record r = j.recordFor(f.id, criminal.getUuid());
		r.lastSeen = now;
		if (r.lastCrime.equals(crime.name()) && now - r.lastCrimeTick < 200) return; // the same spree
		r.name = criminal instanceof ColonistEntity c ? c.fullName() : criminal.getName().getString();
		r.player = criminal instanceof PlayerEntity;
		r.heat += crime.heat;
		if (r.charges.size() < 8) r.charges.add(crime.label);
		r.lastCrime = crime.name();
		r.lastCrimeTick = now;
		j.markDirty();

		if (w != null) w.say(crime.shout);
		if (criminal instanceof ServerPlayerEntity p) {
			p.sendMessage(Text.literal("⚖ You are wanted by the ").formatted(Formatting.RED).append(f.displayName())
					.append(Text.literal(" for " + crime.label + " (heat " + r.heat + "). Surrender to a guard (sneak + right-click), pay a fine of "
							+ r.heat * FINE_PER_HEAT + " emeralds (right-click a guard holding them), or run.").formatted(Formatting.RED)), false);
		} else {
			FactionManager.broadcastNear(world, criminal.getBlockPos(), 64, Text.literal("⚖ " + r.name + " is wanted in ").formatted(Formatting.GOLD)
					.append(s.displayName()).append(Text.literal(" for " + crime.label + ".").formatted(Formatting.GOLD)));
		}
		alertGuards(world, f, criminal);
	}

	static void alertGuards(ServerWorld world, Faction f, LivingEntity criminal) {
		for (ColonistEntity g : world.getEntitiesByClass(ColonistEntity.class, criminal.getBoundingBox().expand(48, 16, 48),
				g -> g.job == ColonistEntity.Job.GUARD && g.isAlive() && g.getFactionId().equals(f.id) && g.getTarget() == null
						&& !isPrisoner(world.getServer(), g.getUuid()))) {
			g.setTarget(criminal);
		}
	}

	/** Guards go after people wanted by their faction (to arrest them) and exiles inside their towns (to kill them). */
	public static boolean wantedBy(ColonistEntity guard, LivingEntity e) {
		if (guard.job != ColonistEntity.Job.GUARD || !(guard.getWorld() instanceof ServerWorld world)) return false;
		if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return false;
		if (!(e instanceof PlayerEntity) && !(e instanceof ColonistEntity)) return false;
		Justice j = Justice.get(world.getServer());
		if (j.prisoners.containsKey(e.getUuid())) return false;
		Justice.Record r = j.record(guard.getFactionId(), e.getUuid());
		if (r == null) return false;
		if (r.heat > 0) return true;
		if (r.exiled) {
			Settlement s = guard.settlement();
			return s != null && s.contains(e.getBlockPos(), world.getTime(), 8);
		}
		return false;
	}

	// ---------------------------------------------------------------- arrest & sentence

	public static void arrest(ServerWorld world, ColonistEntity guard, LivingEntity criminal, int discount) {
		FactionManager fm = FactionManager.get(world.getServer());
		Justice jr = Justice.get(world.getServer());
		if (jr.slaves.containsKey(criminal.getUuid())) {
			jr.clear(guard.getFactionId(), criminal.getUuid());
			criminal.setHealth(Math.max(criminal.getHealth(), 6f));
			for (MobEntity m : world.getEntitiesByClass(MobEntity.class, criminal.getBoundingBox().expand(48), m -> m.getTarget() == criminal)) m.setTarget(null);
			guard.say("Back to your master, runaway.");
			Slavery.returnRunaway(world, jr, criminal);
			return;
		}
		Faction f = fm.get(guard.getFactionId());
		if (f == null) return;
		Settlement s = f.nearestTo(criminal.getBlockPos());
		if (s == null) return;
		Justice j = Justice.get(world.getServer());
		Justice.Record r = j.record(f.id, criminal.getUuid());
		int heat = Math.max(1, (r == null ? 1 : r.heat) - discount);
		String charges = r == null || r.charges.isEmpty() ? "disorder" : String.join(", ", r.charges);
		j.clear(f.id, criminal.getUuid());

		criminal.setHealth(Math.max(criminal.getHealth(), 6f));
		for (MobEntity m : world.getEntitiesByClass(MobEntity.class, criminal.getBoundingBox().expand(48), m -> m.getTarget() == criminal)) m.setTarget(null);
		if (criminal instanceof MobEntity m) m.setTarget(null);
		guard.say("You're under arrest!");
		sentence(world, fm, j, f, s, criminal, heat, charges);
	}

	static void sentence(ServerWorld world, FactionManager fm, Justice j, Faction f, Settlement s, LivingEntity criminal, int heat, String charges) {
		Faction.Citizen ruler = f.rulerCitizen();
		int kindness = ruler == null ? 50 : ruler.kindness;
		BlockPos prison = s.builtOf("prison"), camp = s.builtOf("work_camp"), gallows = s.builtOf("gallows");
		int fine = heat * FINE_PER_HEAT;
		String name = criminal instanceof ColonistEntity c ? c.fullName() : criminal.getName().getString();
		String verdict;
		if (heat >= 8 && f.laws.contains(Law.DEATH_PENALTY) && gallows != null) {
			imprison(world, j, f, s, criminal, name, Justice.Sentence.DEATH, s.site(gallows).at(Blueprints.GALLOWS.workstation), heat);
			verdict = "to hang on the gallows";
		} else if (heat >= 5 && f.slavery && kindness < 45 && Slavery.canEnslave(world, criminal)) {
			Slavery.sell(world, j, f, s, criminal, name);
			verdict = "to be sold into slavery";
		} else if (heat <= 2 && pay(criminal, fine, s)) {
			verdict = "to a fine of " + fine + " emeralds";
		} else if (camp != null && heat >= 3) {
			imprison(world, j, f, s, criminal, name, Justice.Sentence.CAMP, s.site(camp).at(Blueprints.WORK_CAMP.workstation), heat);
			verdict = "to hard labour in the work camp";
		} else if (prison != null) {
			List<BlockPos> cells = Blueprints.PRISON.cells;
			int taken = (int) j.prisoners.values().stream().filter(p -> p.settlement == s.id && p.sentence == Justice.Sentence.JAIL).count();
			imprison(world, j, f, s, criminal, name, Justice.Sentence.JAIL, s.site(prison).at(cells.get(taken % cells.size())), heat);
			verdict = "to " + Math.max(1, Math.min(heat * 5, 60)) + " minutes in prison";
		} else if (pay(criminal, fine, s)) {
			verdict = "to a fine of " + fine + " emeralds";
		} else {
			exile(world, j, f, s, criminal);
			verdict = "to exile from the lands of the " + f.name;
		}
		fm.markDirty();
		FactionManager.broadcastNear(world, s.cityCenter == null ? criminal.getBlockPos() : s.cityCenter, 192,
				Text.literal("⚖ " + name + " has been arrested in ").formatted(Formatting.GOLD).append(s.displayName())
						.append(Text.literal(" for " + charges + " and sentenced " + verdict + ".").formatted(Formatting.GOLD)));
	}

	static void imprison(ServerWorld world, Justice j, Faction f, Settlement s, LivingEntity e, String name, Justice.Sentence sentence, BlockPos place, int heat) {
		long now = world.getTime();
		Justice.Prisoner p = new Justice.Prisoner();
		p.who = e.getUuid();
		p.player = e instanceof PlayerEntity;
		p.name = name;
		p.faction = f.id;
		p.settlement = s.id;
		p.sentence = sentence;
		p.place = place.toImmutable();
		switch (sentence) {
			case JAIL -> p.releaseTime = now + Math.max(1, Math.min(heat * 5, 60)) * 1200L;
			case CAMP -> {
				p.quota = heat * 24;
				p.releaseTime = now + (p.player ? heat * 12000L : heat * 4000L);
				if (e instanceof PlayerEntity pl && pl.getInventory().count(Items.STONE_PICKAXE) == 0 && pl.getInventory().count(Items.IRON_PICKAXE) == 0) {
					pl.giveItemStack(new ItemStack(Items.STONE_PICKAXE));
				}
			}
			case DEATH -> p.executeAt = now + 200;
			default -> {
			}
		}
		j.prisoners.put(p.who, p);
		j.markDirty();
		teleport(e, place);
		if (e instanceof ServerPlayerEntity sp) {
			sp.sendMessage(Text.literal(switch (sentence) {
				case JAIL -> "⛓ You have been thrown in prison. Serve your time, or find a way out without the guards seeing.";
				case CAMP -> "⛏ You have been sent to the work camp. Mine " + p.quota + " stone inside the fence to earn your release.";
				case DEATH -> "☠ You have been condemned to hang.";
				default -> "";
			}).formatted(Formatting.RED), false);
		}
	}

	static void exile(ServerWorld world, Justice j, Faction f, Settlement s, LivingEntity e) {
		Justice.Record r = j.recordFor(f.id, e.getUuid());
		r.name = e instanceof ColonistEntity c ? c.fullName() : e.getName().getString();
		r.player = e instanceof PlayerEntity;
		r.heat = 0;
		r.exiled = true;
		r.exiledUntil = world.getTime() + 5 * 24000L;
		j.markDirty();
		if (s.cityCenter != null) {
			int[] b = s.bounds(world.getTime());
			Vec3d dir = e.getPos().subtract(Vec3d.ofCenter(s.cityCenter)).multiply(1, 0, 1);
			if (dir.lengthSquared() < 1) dir = new Vec3d(1, 0, 0);
			dir = dir.normalize();
			double reach = Math.max(b[2] - b[0], b[3] - b[1]) / 2.0 + 24;
			int x = (int) (s.cityCenter.getX() + dir.x * reach), z = (int) (s.cityCenter.getZ() + dir.z * reach);
			teleport(e, new BlockPos(x, net.colonists.faction.City.surfaceYOr(world, x, z, s.cityCenter.getY()), z));
		}
		if (e instanceof ColonistEntity c) {
			c.leaveForExile();
		} else if (e instanceof ServerPlayerEntity sp) {
			sp.sendMessage(Text.literal("You have been exiled. The guards of the ").formatted(Formatting.RED).append(f.displayName())
					.append(Text.literal(" will kill you on sight inside their towns for the next five days.").formatted(Formatting.RED)), false);
		}
	}

	public static void teleport(LivingEntity e, BlockPos pos) {
		if (e instanceof ServerPlayerEntity sp) {
			sp.networkHandler.requestTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, sp.getYaw(), sp.getPitch());
		} else {
			e.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, e.getYaw(), e.getPitch());
			if (e instanceof MobEntity m) m.getNavigation().stop();
		}
	}

	/** Takes emeralds from a player's inventory or a colonist's purse into the town treasury. */
	public static boolean pay(LivingEntity e, int amount, Settlement s) {
		if (amount <= 0) return true;
		if (e instanceof PlayerEntity p) {
			if (Inventories.remove(p.getInventory(), st -> st.isOf(Items.EMERALD), amount, true) < amount) return false;
			Inventories.remove(p.getInventory(), st -> st.isOf(Items.EMERALD), amount, false);
		} else if (e instanceof ColonistEntity c) {
			if (!Economy.spend(c, amount)) return false;
		} else {
			return false;
		}
		s.treasury += amount;
		return true;
	}

	// ---------------------------------------------------------------- enforcement (every second)

	static void tick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		long now = world.getTime();
		Justice j = Justice.get(server);
		FactionManager fm = FactionManager.get(server);
		for (Justice.Prisoner p : new ArrayList<>(j.prisoners.values())) {
			LivingEntity e = p.player ? server.getPlayerManager().getPlayer(p.who)
					: world.getEntity(p.who) instanceof ColonistEntity c ? c : null;
			Settlement s = fm.settlement(p.settlement);
			if (s == null || s.faction == null || !s.faction.id.equals(p.faction)) {
				// The town fell or changed hands: its prison is thrown open.
				j.prisoners.remove(p.who);
				j.markDirty();
				if (e instanceof ServerPlayerEntity sp) sp.sendMessage(Text.literal("The prison doors are thrown open. You are free!").formatted(Formatting.GREEN), false);
				continue;
			}
			if (e == null || !e.isAlive()) continue;
			double d = Math.sqrt(e.getBlockPos().getSquaredDistance(p.place));
			switch (p.sentence) {
				case DEATH -> {
					if (d > 1.5) teleport(e, p.place);
					if (now >= p.executeAt) execute(world, fm, j, p, e, s);
				}
				case JAIL, CAMP -> {
					if (now >= p.releaseTime) {
						release(world, j, p, e, s, "You have served your sentence.");
						continue;
					}
					double radius = p.sentence == Justice.Sentence.JAIL ? 2.5 : 5.5;
					if (d > radius) {
						ColonistEntity guard = world.getEntitiesByClass(ColonistEntity.class, e.getBoundingBox().expand(20, 8, 20),
										g -> g.job == ColonistEntity.Job.GUARD && g.isAlive() && g.getFactionId().equals(p.faction) && g.canSee(e)
												&& !j.prisoners.containsKey(g.getUuid()))
								.stream().findFirst().orElse(null);
						if (guard != null || d > 200) {
							teleport(e, p.place);
							if (guard != null) {
								p.releaseTime += 1200;
								guard.say("Back in there, you!");
							}
						} else if (d > 48) {
							j.prisoners.remove(p.who);
							Justice.Record r = j.recordFor(p.faction, p.who);
							r.name = p.name;
							r.player = p.player;
							r.heat += Law.Crime.JAILBREAK.heat;
							r.lastSeen = now;
							r.charges.add(Law.Crime.JAILBREAK.label);
							FactionManager.broadcastNear(world, e.getBlockPos(), 256, Text.literal("🔓 " + p.name + " has escaped from ")
									.formatted(Formatting.GOLD).append(s.displayName()).append(Text.literal("!").formatted(Formatting.GOLD)));
							j.markDirty();
							continue;
						}
					}
					if (p.sentence == Justice.Sentence.CAMP) campWork(world, j, p, e, s);
					if (e instanceof ServerPlayerEntity sp) {
						long left = Math.max(0, (p.releaseTime - now) / 1200);
						sp.sendMessage(Text.literal(p.sentence == Justice.Sentence.JAIL
								? "⛓ Imprisoned in " + s.name + " — about " + left + " min left"
								: "⛏ Work camp: " + p.delivered + "/" + p.quota + " stone delivered (or " + left + " min)").formatted(Formatting.GRAY), true);
					}
				}
				case SLAVE -> {
					if (d > 3) teleport(e, p.place); // held at the auction block
				}
			}
		}

		if (now % 1200 == 0) {
			for (Map<UUID, Justice.Record> m : j.records.values()) {
				m.values().removeIf(r -> (!r.exiled && now - r.lastSeen > 36000) || (r.exiled && now > r.exiledUntil));
			}
			j.markDirty();
		}
		if (now % 100 == 0 && world.isNight()) curfew(world, fm);
		Slavery.tick(server, world, j, fm, now);
	}

	private static void campWork(ServerWorld world, Justice j, Justice.Prisoner p, LivingEntity e, Settlement s) {
		if (e instanceof PlayerEntity pl) {
			int n = Inventories.remove(pl.getInventory(), ColonistBrain.STONE_MATS, 576, false);
			if (n <= 0) return;
			p.delivered += n;
			ItemStack rest = new ItemStack(Items.COBBLESTONE, Math.min(64, n));
			for (Inventory inv : Stockpile.chests(world, s)) {
				rest = ColonistBrain.insert(inv, rest);
				if (rest.isEmpty()) break;
			}
			j.markDirty();
			if (p.delivered >= p.quota) release(world, j, p, e, s, "Your quota is met. You are free to go.");
		} else if (world.getTime() % 1200 < 20) {
			ItemStack produce = new ItemStack(Items.COBBLESTONE, 8);
			for (Inventory inv : Stockpile.chests(world, s)) {
				produce = ColonistBrain.insert(inv, produce);
				if (produce.isEmpty()) break;
			}
		}
	}

	static void release(ServerWorld world, Justice j, Justice.Prisoner p, LivingEntity e, Settlement s, String message) {
		j.prisoners.remove(p.who);
		j.markDirty();
		BlockPos out = null;
		if (p.sentence == Justice.Sentence.JAIL) {
			BlockPos prison = s.builtOf("prison");
			if (prison != null) out = Blueprints.PRISON.doorstep(s.site(prison));
		} else {
			BlockPos camp = s.builtOf("work_camp");
			if (camp != null) out = Blueprints.WORK_CAMP.doorstep(s.site(camp));
		}
		if (out != null) teleport(e, out);
		if (e instanceof ServerPlayerEntity sp) sp.sendMessage(Text.literal(message).formatted(Formatting.GREEN), false);
		if (e instanceof ColonistEntity c) c.say("Free at last. I've learned my lesson.");
	}

	static void execute(ServerWorld world, FactionManager fm, Justice j, Justice.Prisoner p, LivingEntity e, Settlement s) {
		j.prisoners.remove(p.who);
		j.markDirty();
		FactionManager.broadcastNear(world, p.place, 256, Text.literal("☠ " + p.name + " was hanged in ").formatted(Formatting.DARK_RED)
				.append(s.displayName()).append(Text.literal(" for their crimes.").formatted(Formatting.DARK_RED)));
		if (s.faction != null) s.faction.approval = Math.max(0, s.faction.approval - 2);
		if (e instanceof ColonistEntity c) c.forcedDeathCause = "execution";
		e.damage(world.getDamageSources().genericKill(), Float.MAX_VALUE);
	}

	/** Strangers inside a walled city at night break the curfew (where there is one). */
	private static void curfew(ServerWorld world, FactionManager fm) {
		long now = world.getTime();
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isCreative() || p.isSpectator()) continue;
			for (Settlement s : fm.allSettlements()) {
				if (s.faction == null || !s.wallBuilt || !s.faction.laws.contains(Law.TRESPASS)) continue;
				if (isMember(fm, p, s.faction) || fm.standing(p, s.faction.id) >= 20) continue;
				if (s.contains(p.getBlockPos(), now, -2)) report(world, s, p, Law.Crime.TRESPASS, true);
			}
		}
	}

	// ---------------------------------------------------------------- hooks

	private static void onBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity be) {
		if (!(world instanceof ServerWorld sw)) return;
		FactionManager fm = FactionManager.get(sw.getServer());
		Justice j = Justice.get(sw.getServer());
		if (j.prisoners.containsKey(player.getUuid()) || j.slaves.containsKey(player.getUuid())) return;
		Settlement s = territoryAt(fm, pos, sw.getTime());
		if (s == null || isMember(fm, player, s.faction)) return;
		if (state.getBlock() instanceof CropBlock || be instanceof Inventory) report(sw, s, player, Law.Crime.THEFT, true);
		else if (fm.isNearAnyHome(pos)) report(sw, s, player, Law.Crime.VANDALISM, true);
	}

	private static void onUseBlock(ServerWorld world, PlayerEntity player, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof Inventory)) return;
		FactionManager fm = FactionManager.get(world.getServer());
		Justice j = Justice.get(world.getServer());
		Justice.Shop shop = j.shops.get(pos);
		if (shop != null && shop.owner.equals(player.getUuid())) return;
		Settlement s = territoryAt(fm, pos, world.getTime());
		if (s == null || isMember(fm, player, s.faction) || !fm.isNearAnyHome(pos)) return;
		if (fm.standing(player, s.faction.id) >= FactionManager.ALLY) return;
		report(world, s, player, Law.Crime.THEFT, true);
	}

	private static void onAttack(ServerWorld world, PlayerEntity player, ColonistEntity victim) {
		FactionManager fm = FactionManager.get(world.getServer());
		Faction f = fm.get(victim.getFactionId());
		if (f == null) return;
		Justice j = Justice.get(world.getServer());
		// Fighting someone the town wants anyway, or a prisoner, is not a crime.
		if (j.heat(f.id, victim.getUuid()) > 0 || j.prisoners.containsKey(victim.getUuid())) return;
		Settlement s = f.nearestTo(victim.getBlockPos());
		boolean member = isMember(fm, player, f);
		report(world, s, player, member && f.laws.contains(Law.TREASON) ? Law.Crime.TREASON : Law.Crime.ASSAULT, true);
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity.getWorld() instanceof ServerWorld world)) return true;
		Justice j = Justice.get(world.getServer());
		if (source.getAttacker() instanceof PlayerEntity p) {
			Justice.Slave slave = j.slaves.get(p.getUuid());
			if (slave != null && entity.getUuid().equals(slave.owner)) {
				p.sendMessage(Text.literal("You dare not raise a hand against your master.").formatted(Formatting.DARK_RED), true);
				return false;
			}
		}
		if (source.getAttacker() instanceof ColonistEntity guard && guard.job == ColonistEntity.Job.GUARD
				&& (entity instanceof PlayerEntity || entity instanceof ColonistEntity)
				&& j.heat(guard.getFactionId(), entity.getUuid()) > 0 && entity.getHealth() - amount <= SUBDUED) {
			arrest(world, guard, entity, 0);
			return false;
		}
		return true;
	}

	private static void afterDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.getWorld() instanceof ServerWorld world)) return;
		FactionManager fm = FactionManager.get(world.getServer());
		Justice j = Justice.get(world.getServer());
		if (entity instanceof ColonistEntity victim) {
			if (j.prisoners.remove(victim.getUuid()) != null) j.markDirty();
			Slavery.onDeath(world, j, fm, victim);
			if ("execution".equals(victim.forcedDeathCause)) return;
			Faction f = fm.get(victim.getFactionId());
			if (f == null) return;
			Settlement s = f.nearestTo(victim.getBlockPos());
			if (source.getAttacker() instanceof PlayerEntity p) {
				report(world, s, p, isMember(fm, p, f) && f.laws.contains(Law.TREASON) ? Law.Crime.TREASON : Law.Crime.MURDER, true);
			} else if (source.getAttacker() instanceof ColonistEntity killer && killer.job != ColonistEntity.Job.GUARD
					&& j.heat(f.id, victim.getUuid()) <= 0) {
				report(world, s, killer, Law.Crime.MURDER, true);
			}
		} else if (entity instanceof AnimalEntity && source.getAttacker() instanceof PlayerEntity p) {
			Settlement s = territoryAt(fm, entity.getBlockPos(), world.getTime());
			if (s != null && !isMember(fm, p, s.faction) && fm.isNearAnyHome(entity.getBlockPos())) report(world, s, p, Law.Crime.POACHING, true);
		}
	}

	// ---------------------------------------------------------------- talking to guards

	/** Right-clicking a guard while wanted: pay the fine, try a bribe (sneaking), or surrender (sneaking, empty hand). */
	public static boolean interactGuard(ColonistEntity guard, ServerPlayerEntity player, Hand hand) {
		ServerWorld world = (ServerWorld) guard.getWorld();
		Justice j = Justice.get(world.getServer());
		int heat = j.heat(guard.getFactionId(), player.getUuid());
		if (heat <= 0) return false;
		Faction f = FactionManager.get(world.getServer()).get(guard.getFactionId());
		Settlement s = f == null ? null : f.nearestTo(guard.getBlockPos());
		if (s == null) return false;
		ItemStack held = player.getStackInHand(hand);
		if (held.isOf(Items.EMERALD)) {
			if (player.isSneaking()) {
				int bribe = Math.max(3, heat * 2);
				int greed = guard.ambition - guard.kindness;
				if (held.getCount() >= bribe && greed > 0 && guard.getRandom().nextInt(100) < 40 + greed) {
					held.decrement(bribe);
					Economy.earn(guard, bribe);
					j.clear(f.id, player.getUuid());
					guard.setTarget(null);
					guard.say("I didn't see anything. Now get out of my sight.");
				} else {
					guard.say(held.getCount() < bribe ? "That's all? Insulting." : "You think I can be bought?");
					Justice.Record r = j.recordFor(f.id, player.getUuid());
					r.heat += 1;
					r.charges.add("attempted bribery");
					j.markDirty();
				}
			} else {
				int fine = heat * FINE_PER_HEAT;
				if (held.getCount() >= fine) {
					held.decrement(fine);
					s.treasury += fine;
					j.clear(f.id, player.getUuid());
					guard.setTarget(null);
					guard.say("Fine paid. Behave yourself.");
				} else {
					guard.say("Your fine is " + fine + " emeralds.");
				}
			}
			return true;
		}
		if (held.isEmpty() && player.isSneaking()) {
			guard.say("A wise choice.");
			arrest(world, guard, player, 1);
			return true;
		}
		return false;
	}
}
