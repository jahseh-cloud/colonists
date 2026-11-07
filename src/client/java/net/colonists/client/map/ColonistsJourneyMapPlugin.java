package net.colonists.client.map;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.colonists.ColonistsMod;
import net.colonists.net.CityMapPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Draws each colonist city's territory on JourneyMap's minimap and fullscreen map. Only loaded when JourneyMap is installed. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class ColonistsJourneyMapPlugin implements IClientPlugin {
	private IClientAPI api;

	@Override
	public String getModId() {
		return ColonistsMod.MOD_ID;
	}

	@Override
	public void initialize(IClientAPI api) {
		this.api = api;
		ClientCities.addListener(() -> MinecraftClient.getInstance().execute(this::redraw));
		ColonistsMod.LOGGER.info("Colonists: JourneyMap integration enabled");
	}

	private void redraw() {
		if (this.api == null) return;
		try {
			this.api.removeAll(ColonistsMod.MOD_ID);
			for (CityMapPayload.CityInfo c : ClientCities.get()) {
				int border = ClientCities.borderColor(c);
				ShapeProperties shape = new ShapeProperties()
						.setStrokeWidth(c.walled() ? 3 : 2)
						.setStrokeColor(border).setStrokeOpacity(0.9f)
						.setFillColor(c.color()).setFillOpacity(0.18f);
				TextProperties text = new TextProperties()
						.setColor(c.color())
						.setBackgroundColor(0x000000).setBackgroundOpacity(0.5f)
						.setOpacity(1f)
						.setFontShadow(true);
				int y = c.centerY();
				MapPolygon area = new MapPolygon(
						new BlockPos(c.minX(), y, c.maxZ() + 1),
						new BlockPos(c.maxX() + 1, y, c.maxZ() + 1),
						new BlockPos(c.maxX() + 1, y, c.minZ()),
						new BlockPos(c.minX(), y, c.minZ()));
				PolygonOverlay overlay = new PolygonOverlay(ColonistsMod.MOD_ID, World.OVERWORLD, shape, area);
				overlay.setOverlayGroupName("Colonist cities")
						.setLabel((c.capital() ? "★ " : "") + c.name() + " (" + c.rank() + ", pop. " + c.population() + ")")
						.setTitle(c.name() + " — " + c.rank() + (c.capital() ? " (capital)" : "")
								+ "\n" + c.realm()
								+ "\nPopulation: " + c.population()
								+ (c.capital() ? "" : "\nLoyalty: " + c.loyalty() + "%")
								+ (c.besieged() ? "\n⚔ Under siege!" : "")
								+ (c.ruler().isEmpty() ? "" : "\n" + c.ruler())
								+ "\n" + ClientCities.relationLabel(c))
						.setTextProperties(text);
				this.api.show(overlay);
			}
		} catch (Throwable t) {
			ColonistsMod.LOGGER.warn("Colonists: could not draw cities on JourneyMap", t);
		}
	}
}
