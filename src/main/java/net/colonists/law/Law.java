package net.colonists.law;

/** The laws a faction can keep on its books. */
public enum Law {
	THEFT("Theft", "taking from the town's chests, stores and fields"),
	VANDALISM("Vandalism", "breaking the town's buildings"),
	ASSAULT("Assault", "attacking a citizen"),
	MURDER("Murder", "killing a citizen"),
	POACHING("Poaching", "killing the town's livestock"),
	TRESPASS("Curfew", "strangers inside the walls at night"),
	TREASON("Treason", "members taking up arms against their own people"),
	DEATH_PENALTY("Death penalty", "the worst criminals hang on the gallows");

	public final String label, description;

	Law(String label, String description) {
		this.label = label;
		this.description = description;
	}

	/** A specific offence and how much it adds to the criminal's "heat". */
	public enum Crime {
		THEFT(Law.THEFT, "theft", 2, "Stop, thief!"),
		VANDALISM(Law.VANDALISM, "vandalism", 1, "Hey! Stop wrecking the place!"),
		ASSAULT(Law.ASSAULT, "assault", 3, "Guards! Someone's attacking us!"),
		MURDER(Law.MURDER, "murder", 8, "Murder! Murder! Guards!"),
		POACHING(Law.POACHING, "poaching", 2, "Those are our animals!"),
		TRESPASS(Law.TRESPASS, "breaking curfew", 1, "You there! No strangers inside the walls after dark."),
		TREASON(Law.TREASON, "treason", 7, "Traitor! Seize them!"),
		RESISTING(null, "resisting arrest", 2, "Don't make this harder than it needs to be!"),
		JAILBREAK(null, "escaping prison", 4, "A prisoner has escaped!"),
		RUNAWAY(null, "running away from their master", 4, "A slave has run off!");

		/** The law that must be on the books for this to be a crime (null = always). */
		public final Law law;
		public final String label;
		public final int heat;
		public final String shout;

		Crime(Law law, String label, int heat, String shout) {
			this.law = law;
			this.label = label;
			this.heat = heat;
			this.shout = shout;
		}
	}
}
