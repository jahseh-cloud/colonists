package net.colonists.legend;

import net.colonists.law.Law;

import java.util.EnumSet;
import java.util.Set;

/**
 * A teaching of a faith. Tenets shape how believers behave (war, money, mercy, children, wandering) and which laws a
 * realm that holds the faith may keep. Opposed tenets never appear in the same religion.
 */
public enum Tenet {
	SWORD("Expansion by the sword", "Believers spread the faith by conquest; holy wars are blessed.", null),
	PEACE("The way of peace", "Believers shun wars of choice and prize quiet years.", SWORD),
	ASCETIC("Poverty of spirit", "Wealth is a burden: no grand houses, light taxes, no buying or selling of people.", null),
	PROSPERITY("Blessed are the industrious", "Trade and toil are holy; towns produce and earn more.", ASCETIC),
	MERCY("Mercy above judgement", "No gallows; sentences are gentler.", null),
	ORDER("The law is sacred", "Strict laws, curfews and the gallows for the worst.", MERCY),
	FREEDOM("All are born free", "No one may be owned; runaways find refuge.", null),
	DOMINION("The chosen people", "Unbelievers may be taken as captives.", FREEDOM),
	FERTILITY("Be fruitful", "Large families are a blessing; children are born sooner.", null),
	WANDERING("The road is the way", "Believers travel and settle far; adventurers are honoured.", null);

	public final String label, description;
	/** The tenet this one cannot share a religion with (or null). */
	public final Tenet opposes;

	Tenet(String label, String description, Tenet opposes) {
		this.label = label;
		this.description = description;
		this.opposes = opposes;
	}

	public boolean clashesWith(Tenet o) {
		return this.opposes == o || o.opposes == this;
	}

	/** Laws a realm of this faith may not keep. */
	public Set<Law> forbids() {
		return switch (this) {
			case MERCY -> EnumSet.of(Law.DEATH_PENALTY);
			case PEACE -> EnumSet.noneOf(Law.class);
			default -> EnumSet.noneOf(Law.class);
		};
	}

	/** Laws a realm of this faith must keep. */
	public Set<Law> requires() {
		return switch (this) {
			case ORDER -> EnumSet.of(Law.TRESPASS, Law.TREASON);
			default -> EnumSet.noneOf(Law.class);
		};
	}

	/** Whether keeping captives (slavery) is allowed (null = no opinion). */
	public Boolean captivity() {
		return switch (this) {
			case FREEDOM, ASCETIC -> false;
			case DOMINION -> true;
			default -> null;
		};
	}
}
