package net.colonists.ai.task;

import net.colonists.entity.ColonistEntity;
import net.colonists.faction.City;
import net.colonists.faction.Empire;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.colonists.faction.Settlement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;

/**
 * A soldier's part in a war of conquest: march to the enemy town, fight its defenders, then hold the plaza
 * until the town surrenders. Civilians are left alone — they will be our people soon.
 */
public class CampaignTask extends Task {
	private final int goalId;
	private BlockPos hop;
	private boolean besieging;
	private String townName = "the enemy";

	/** A Siegeworks engine this soldier is crewing (if that mod is installed). */
	@org.jetbrains.annotations.Nullable
	private net.minecraft.entity.Entity engine;
	private int crewTicks;

	public CampaignTask(ColonistEntity colonist, int goalId) {
		super(colonist);
		this.goalId = goalId;
	}

	@Override
	public String describe() {
		return (this.besieging ? "Besieging " : "Marching on ") + this.townName;
	}

	@Override
	public ColonistEntity.ToolKind tool() {
		return ColonistEntity.ToolKind.WEAPON;
	}

	@Override
	public boolean stopsAtNight() {
		return false;
	}

	@Override
	public int maxTicks() {
		return 6000;
	}

	@Override
	public void start() {
		if (this.colonist.getRandom().nextInt(3) == 0) {
			this.colonist.say(switch (this.colonist.getRandom().nextInt(3)) {
				case 0 -> "To arms! We march for glory.";
				case 1 -> "Their town will fly our banner by nightfall.";
				default -> "Spare the townsfolk. It's the soldiers we're after.";
			});
		}
	}

	@Override
	public void tick() {
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		FactionManager fm = this.colonist.factions();
		Faction realm = fm == null ? null : fm.get(this.colonist.getFactionId());
		Settlement goal = fm == null ? null : fm.settlement(this.goalId);
		if (realm == null || goal == null || goal.faction == realm || goal.cityCenter == null
				|| !Empire.atWarWith(fm, realm, goal.faction) || !this.colonist.isSoldier()) {
			this.done = true;
			return;
		}
		this.townName = goal.name;
		BlockPos center = goal.cityCenter;
		Vec3d here = this.colonist.getPos();
		double dist = Math.hypot(here.x - center.getX() - 0.5, here.z - center.getZ() - 0.5);

		// Far away and unwatched: the army's march happens off-screen, arriving at the edge of the town.
		if (dist > 56 && this.ticks % 20 == 1 && !Empire.watched(world, this.colonist, 128)) {
			Vec3d dir = here.subtract(Vec3d.ofCenter(center)).multiply(1, 0, 1);
			if (dir.lengthSquared() < 1) dir = new Vec3d(1, 0, 0);
			dir = dir.normalize();
			int x = center.getX() + (int) (dir.x * 36) + this.colonist.getRandom().nextInt(7) - 3;
			int z = center.getZ() + (int) (dir.z * 36) + this.colonist.getRandom().nextInt(7) - 3;
			int y = City.surfaceYOr(world, x, z, center.getY());
			this.colonist.refreshPositionAndAngles(x + 0.5, y, z + 0.5, this.colonist.getYaw(), 0f);
			this.colonist.getNavigation().stop();
			this.hop = null;
			return;
		}

		// Engage any defender in sight first.
		if (this.ticks % 20 == 0 && dist < 72) {
			ColonistEntity defender = world.getEntitiesByClass(ColonistEntity.class, this.colonist.getBoundingBox().expand(24, 8, 24),
							o -> o.isAlive() && o.isSoldier() && Empire.atWarWith(fm, realm, fm.get(o.getFactionId())))
					.stream().min(Comparator.comparingDouble(this.colonist::squaredDistanceTo)).orElse(null);
			if (defender != null) {
				this.colonist.setTarget(defender);
				this.done = true; // the combat goal takes over; we come back to the campaign afterwards
				return;
			}
		}

		// With Siegeworks: crew a siege engine, or bring one up.
		if (net.colonists.compat.SiegeworksCompat.PRESENT && dist > 10 && dist < 60) {
			if (this.engine != null && (!this.engine.isAlive() || this.crewTicks > 2400)) this.engine = null;
			if (this.engine == null && this.ticks % 40 == 0) {
				this.engine = net.colonists.compat.SiegeworksCompat.idleEngine(world, goal, this.colonist);
				if (this.engine == null && this.colonist.getRandom().nextInt(10) == 0)
					this.engine = net.colonists.compat.SiegeworksCompat.bringEngine(world, goal, realm, this.colonist);
				this.crewTicks = 0;
			}
			if (this.engine != null) {
				this.crewTicks++;
				this.besieging = false;
				if (this.colonist.squaredDistanceTo(this.engine) > 2.5 * 2.5) this.approach(this.engine.getBlockPos(), 1.5, 1.0);
				else this.colonist.getNavigation().stop();
				if (this.ticks % 600 == 0) this.colonist.say("Heave!");
				return;
			}
		}

		if (dist > 6) {
			this.besieging = false;
			if (this.hop == null || this.colonist.getBlockPos().getSquaredDistance(this.hop) < 4 * 4 || this.stuck()) {
				Vec3d there = Vec3d.ofCenter(center);
				Vec3d dir = there.subtract(here).multiply(1, 0, 1).normalize();
				double step = Math.min(20, dist);
				double angle = (this.colonist.getRandom().nextDouble() - 0.5) * (this.stuck() ? 2.0 : 0.4);
				double dx = dir.x * Math.cos(angle) - dir.z * Math.sin(angle);
				double dz = dir.x * Math.sin(angle) + dir.z * Math.cos(angle);
				int x = net.colonists.world.WorldEdge.clamp((int) Math.floor(here.x + dx * step)), z = net.colonists.world.WorldEdge.clamp((int) Math.floor(here.z + dz * step));
				this.hop = new BlockPos(x, City.surfaceYOr(world, x, z, this.colonist.getBlockY()), z);
				this.resetMovement();
			}
			this.approach(this.hop, 2.0, 1.1);
			return;
		}

		// Holding the plaza. No defenders within the town: every second here brings the surrender closer.
		this.besieging = true;
		this.colonist.getNavigation().stop();
		if (this.ticks % 20 == 0) {
			boolean defended = !world.getEntitiesByClass(ColonistEntity.class, this.colonist.getBoundingBox().expand(40, 12, 40),
					o -> o.isAlive() && o.isSoldier() && o.getFactionId().equals(goal.faction.id)).isEmpty();
			// A village's iron golems stand by its free men.
			if (goal.village) {
				for (net.minecraft.entity.passive.IronGolemEntity g : world.getEntitiesByClass(net.minecraft.entity.passive.IronGolemEntity.class,
						this.colonist.getBoundingBox().expand(40, 12, 40), net.minecraft.entity.passive.IronGolemEntity::isAlive)) {
					defended = true;
					if (g.getTarget() == null) g.setTarget(this.colonist);
				}
			}
			if (!defended) Empire.besiege(world, fm, goal, realm, net.colonists.legend.Legends.siegePower(world.getServer(), this.colonist, realm));
			if (this.ticks % 400 == 0) this.colonist.say("Lay down your arms, " + goal.name + "!");
		}
	}
}
