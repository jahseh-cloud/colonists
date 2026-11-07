package net.colonists.client.map;

import net.colonists.ColonistsMod;
import net.colonists.net.CityMapPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keeps a "Colonist cities" waypoint set in Xaero's Minimap with one waypoint per city.
 * Only loaded when Xaero's Minimap is installed. Uses Xaero's internal classes, so any
 * incompatibility with a future Xaero version simply switches the integration off.
 */
public final class XaeroCompat {
	private XaeroCompat() {}

	private static final String SET_NAME = "Colonist cities";
	private static final int[] XAERO_COLORS = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF0000, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static boolean broken;
	private static int appliedVersion = -1;
	private static Object appliedWorld;

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (broken || client.world == null || client.world.getTime() % 40 != 0) return;
			try {
				apply(client);
			} catch (Throwable t) {
				broken = true;
				ColonistsMod.LOGGER.warn("Colonists: Xaero's Minimap integration disabled (incompatible Xaero version?)", t);
			}
		});
		ColonistsMod.LOGGER.info("Colonists: Xaero's Minimap integration enabled");
	}

	private static void apply(MinecraftClient client) {
		if (!World.OVERWORLD.equals(client.world.getRegistryKey())) return;
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null) return;
		MinimapWorld world = session.getWorldManager().getAutoWorld();
		if (world == null) return;
		if (appliedVersion == ClientCities.version() && appliedWorld == world) return;

		WaypointSet set = world.getWaypointSet(SET_NAME);
		if (set == null) {
			set = WaypointSet.Builder.begin().setName(SET_NAME).build();
			world.addWaypointSet(set);
		}
		List<Waypoint> old = new ArrayList<>();
		for (Waypoint w : set.getWaypoints()) old.add(w);
		Iterator<Waypoint> it = set.getWaypoints().iterator();
		while (it.hasNext()) {
			it.next();
			it.remove();
		}
		for (CityMapPayload.CityInfo c : ClientCities.get()) {
			String name = (c.capital() ? "★ " : "") + c.name() + " (" + c.realm() + ", " + c.rank() + ", pop. " + c.population()
					+ (c.besieged() ? ", under siege" : "") + ", " + ClientCities.relationLabel(c) + ")";
			Waypoint w = new Waypoint(c.centerX(), c.centerY() + 2, c.centerZ(), name, initials(c.name()),
					WaypointColor.fromIndex(closestColor(ClientCities.borderColor(c))), WaypointPurpose.NORMAL, false, true);
			set.add(w);
		}
		appliedVersion = ClientCities.version();
		appliedWorld = world;
	}

	private static String initials(String name) {
		StringBuilder sb = new StringBuilder();
		for (String part : name.split(" ")) {
			if (!part.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(part.charAt(0)));
		}
		return sb.length() == 0 ? "C" : sb.toString();
	}

	private static int closestColor(int rgb) {
		int best = 15;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < XAERO_COLORS.length; i++) {
			int c = XAERO_COLORS[i];
			long dr = ((c >> 16) & 255) - ((rgb >> 16) & 255), dg = ((c >> 8) & 255) - ((rgb >> 8) & 255), db = (c & 255) - (rgb & 255);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}
}
