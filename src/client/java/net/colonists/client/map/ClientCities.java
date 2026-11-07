package net.colonists.client.map;

import net.colonists.net.CityMapPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The latest city data received from the server, shared by the map integrations. */
public final class ClientCities {
	private ClientCities() {}

	private static List<CityMapPayload.CityInfo> cities = List.of();
	private static int version;
	private static final List<Runnable> LISTENERS = new ArrayList<>();

	public static List<CityMapPayload.CityInfo> get() {
		return cities;
	}

	public static int version() {
		return version;
	}

	/** Called on the client thread. Listeners only hear about real changes. */
	public static void update(List<CityMapPayload.CityInfo> next) {
		if (Objects.equals(cities, next)) return;
		cities = List.copyOf(next);
		version++;
		for (Runnable r : LISTENERS) r.run();
	}

	public static void clear() {
		update(List.of());
	}

	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	/** Territory colour for this player's point of view: faction colour, tinted red at war and green for allies. */
	public static int borderColor(CityMapPayload.CityInfo c) {
		if (c.standing() <= -50) return 0xFF3030;
		if (c.standing() >= 50) return 0x30FF60;
		return c.color();
	}

	public static String relationLabel(CityMapPayload.CityInfo c) {
		int s = c.standing();
		if (s >= 100) return "Your city";
		if (s >= 50) return "Allied";
		if (s >= 15) return "Friendly";
		if (s > -15) return "Neutral";
		if (s > -50) return "Tense";
		return "AT WAR";
	}
}
