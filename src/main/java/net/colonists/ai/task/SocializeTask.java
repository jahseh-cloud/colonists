package net.colonists.ai.task;

import net.colonists.entity.ColonistBrain;
import net.colonists.entity.ColonistEntity;
import net.colonists.faction.Faction;
import net.colonists.faction.FactionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;

/** Walks over to another colonist for a chat; shares food with the hungry; builds goodwill between factions. */
public class SocializeTask extends Task {
	private static final String[] SAME = {
			"Morning, %s! Found any iron lately?",
			"%s, have you seen the size of that creeper by the trees?",
			"I could really use a nap, %s.",
			"Nice work today, %s.",
			"%s! Want to go mining later?",
			"We should build a wall around the village, %s.",
			"Got any bread, %s? Just asking.",
			"The %2$s are going to do great things, %s."};
	private static final String[] FRIENDLY = {
			"Greetings, %s. The %2$s are always glad to see you.",
			"Trade is better than war, wouldn't you say, %s?",
			"Your people build fine houses, %s."};
	private static final String[] TENSE = {
			"Keep your distance, %s.",
			"I'm watching you, %s.",
			"Tell your leaders the %2$s won't be pushed around, %s."};

	private final ColonistEntity partner;
	private int chatTicks;

	public SocializeTask(ColonistEntity colonist, ColonistEntity partner) {
		super(colonist);
		this.partner = partner;
	}

	@Override
	public String describe() {
		return "Chatting with " + this.partner.getFirstName();
	}

	@Override
	public int maxTicks() {
		return 600;
	}

	@Override
	public void tick() {
		if (!this.partner.isAlive() || this.partner.isSleeping() || this.partner.getTarget() != null) {
			this.done = true;
			return;
		}
		if (!this.approach(this.partner, 2.5, 0.9)) {
			return;
		}
		this.partner.getLookControl().lookAt(this.colonist, 30f, 30f);
		this.partner.getNavigation().stop();
		ServerWorld world = (ServerWorld) this.colonist.getWorld();
		FactionManager fm = this.colonist.factions();
		if (fm == null) {
			this.done = true;
			return;
		}
		int relation = fm.relation(this.colonist.getFactionId(), this.partner.getFactionId());
		if (this.chatTicks == 0 && this.colonist.getRandom().nextInt(3) == 0) {
			String[] lines = relation >= 100 ? SAME : relation > -15 ? FRIENDLY : TENSE;
			Faction mine = fm.get(this.colonist.getFactionId());
			String line = String.format(lines[this.colonist.getRandom().nextInt(lines.length)],
					this.partner.getFirstName(), mine == null ? "colony" : mine.name);
			this.colonist.say(line);
		}
		if (this.chatTicks % 20 == 0) {
			world.spawnParticles(relation >= 0 ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
					this.colonist.getX(), this.colonist.getEyeY() + 0.5, this.colonist.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
		}
		if (++this.chatTicks >= 80) {
			// Share food with a hungry friend.
			if (relation >= FactionManager.ALLY && this.partner.hunger <= 10
					&& ColonistBrain.count(this.colonist.getInventory(), ColonistBrain.FOOD) >= 3) {
				ItemStack food = ColonistBrain.takeOne(this.colonist.getInventory(), ColonistBrain.FOOD);
				ColonistBrain.give(this.partner, food);
				world.spawnParticles(ParticleTypes.HEART, this.partner.getX(), this.partner.getEyeY() + 0.5, this.partner.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
				this.colonist.say("Here, " + this.partner.getFirstName() + ", take some " + food.getName().getString() + ".");
			}
			// Talking across faction lines slowly builds (or erodes) trust.
			if (relation < 100) {
				int delta = relation > FactionManager.WAR ? 2 : 1;
				fm.changeRelation(world.getServer(), this.colonist.getFactionId(), this.partner.getFactionId(), delta);
			}
			// Friendship, and sometimes romance.
			if (relation > FactionManager.WAR) {
				int gain = relation >= 100 ? 8 : 3;
				this.colonist.addAffinity(this.partner.getUuid(), gain);
				this.partner.addAffinity(this.colonist.getUuid(), gain);
			}
			if (relation >= 100 && !this.colonist.isBaby() && !this.partner.isBaby()
					&& this.colonist.partner == null && this.partner.partner == null && !this.colonist.isFamily(this.partner)
					&& this.colonist.affinityWith(this.partner.getUuid()) >= 20 && this.partner.affinityWith(this.colonist.getUuid()) >= 20
					&& this.colonist.getRandom().nextInt(2) == 0) {
				this.colonist.marry(this.partner);
			}
			long now = world.getTime();
			this.colonist.lastSocialTime = now;
			this.partner.lastSocialTime = now;
			this.done = true;
		}
	}
}
