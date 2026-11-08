package net.colonists.client.atlas;

import net.colonists.atlas.AtlasPayloads;
import net.colonists.atlas.AtlasPayloads.FactionInfo;
import net.colonists.atlas.AtlasPayloads.TownInfo;
import net.colonists.faction.Statecraft;
import net.colonists.law.Law;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The political atlas: a parchment map of realms and borders with map modes, and — for a ruler — the levers of state:
 * taxes, garrisons, colonies, war and peace, alliances, laws and the form of government.
 */
public class AtlasScreen extends Screen {
	private enum Mode {POLITICAL, DIPLOMATIC, WEALTH, LOYALTY, FAITH}

	private enum Panel {NONE, REALM, LAWS, DIPLOMACY, LEGENDS}

	private static final int[] ZOOMS = {1, 2, 4, 8, 16, 32};
	private static final int SIDEBAR = 160, TOPBAR = 22;
	private static final int PARCHMENT = 0xFFE9DCB5, INK = 0xFF3B2A1A, INK_SOFT = 0xFF6B5638;

	// Remembered between openings.
	private static double centerX = Double.NaN, centerZ;
	private static int zoom = 3;
	private static Mode mode = Mode.POLITICAL;
	private static int colonySource = -1;

	private Panel panel = Panel.NONE;
	@Nullable private TownInfo selected;
	@Nullable private int[] selectedLand;
	private int fundChoice;
	private int govChoice = 1;
	private int refresh;
	private final Map<String, FactionInfo> factionById = new HashMap<>();

	public AtlasScreen() {
		super(Text.literal("Political Atlas"));
	}

	@Override
	protected void init() {
		if (Double.isNaN(centerX) && this.client != null && this.client.player != null) {
			centerX = this.client.player.getX();
			centerZ = this.client.player.getZ();
		}
		AtlasClient.requestData();
		this.rebuild();
	}

	void onData() {
		this.factionById.clear();
		if (AtlasClient.data != null) for (FactionInfo f : AtlasClient.data.factions()) this.factionById.put(f.id(), f);
		if (this.selected != null && AtlasClient.data != null) {
			int id = this.selected.id();
			this.selected = AtlasClient.data.towns().stream().filter(t -> t.id() == id).findFirst().orElse(null);
		}
		this.rebuild();
	}

	@Override
	public void tick() {
		if (++this.refresh % 60 == 0) AtlasClient.requestData();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---------------------------------------------------------------- layout & buttons

	private int mapRight() {
		return this.width - SIDEBAR;
	}

	private void rebuild() {
		this.clearChildren();
		int x = 4;
		int modeW = Math.max(34, Math.min(56, (this.mapRight() - 8) / Mode.values().length - 2));
		for (Mode m : Mode.values()) {
			String label = switch (m) {
				case POLITICAL -> "Realms";
				case DIPLOMATIC -> "Relations";
				case WEALTH -> "Wealth";
				case LOYALTY -> "Loyalty";
				case FAITH -> "Faith";
			};
			this.addDrawableChild(ButtonWidget.builder(Text.literal((mode == m ? "▶ " : "") + label), b -> {
				mode = m;
				this.rebuild();
			}).dimensions(x, 2, modeW, 18).build());
			x += modeW + 2;
		}
		int sx = this.mapRight() + 4, w = SIDEBAR - 8;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Realm"), b -> this.toggle(Panel.REALM)).dimensions(sx, 2, 36, 18).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Laws"), b -> this.toggle(Panel.LAWS)).dimensions(sx + 37, 2, 32, 18).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Nations"), b -> this.toggle(Panel.DIPLOMACY)).dimensions(sx + 70, 2, 42, 18).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Lore"), b -> this.toggle(Panel.LEGENDS)).dimensions(sx + 113, 2, w - 113, 18).build());

		AtlasPayloads.Data d = AtlasClient.data;
		if (d == null) return;
		boolean ruler = d.ruler();
		int y = this.height - 4;
		List<ButtonWidget> actions = new ArrayList<>();

		if (this.panel == Panel.REALM) {
			if (ruler) {
				String[] govs = {"Chiefdom", "Kingdom", "Republic"};
				actions.add(btn("Proclaim: " + govs[this.govChoice], () -> this.order(Statecraft.Order.GOVERNMENT, 0, this.govChoice, 0, 0)));
				actions.add(btn("↻ Change form", () -> {
					this.govChoice = (this.govChoice + 1) % 3;
					this.rebuild();
				}));
				actions.add(btn("Pay army a bonus (20)", () -> this.order(Statecraft.Order.BONUS, 0, 20, 0, 0)));
				actions.add(btn("Abdicate", () -> this.order(Statecraft.Order.ABDICATE, 0, 0, 0, 0)));
			} else if (d.member()) {
				if (d.canStand()) actions.add(btn("Stand for election", () -> this.order(Statecraft.Order.STAND, 0, 0, 0, 0)));
				if (d.coupChance() >= 0) actions.add(btn("Stage a coup (" + d.coupChance() + "%)", () -> this.order(Statecraft.Order.COUP, 0, 0, 0, 0)));
			}
		} else if (this.panel == Panel.LAWS && ruler) {
			for (Law l : Law.values()) {
				actions.add(btn((this.hasLaw(l) ? "✔ " : "✘ ") + l.label, () -> this.order(Statecraft.Order.LAW, 0, l.ordinal(), 0, 0)));
			}
			FactionInfo mine = this.mine();
			actions.add(btn(((mine != null && mine.slavery()) ? "✔ " : "✘ ") + "Captivity", () -> this.order(Statecraft.Order.CAPTIVITY, 0, 0, 0, 0)));
		} else if (this.panel == Panel.NONE && ruler && this.selected != null) {
			TownInfo t = this.selected;
			boolean own = t.faction().equals(d.myFaction());
			FactionInfo them = this.factionById.get(t.faction());
			boolean war = them != null && !own && them.relation() <= -50;
			if (own) {
				actions.add(btn("Taxes −", () -> this.order(Statecraft.Order.TAX, t.id(), -2, 0, 0)));
				actions.add(btn("Taxes +", () -> this.order(Statecraft.Order.TAX, t.id(), 2, 0, 0)));
				actions.add(btn("Garrison −", () -> this.order(Statecraft.Order.GARRISON, t.id(), -1, 0, 0)));
				actions.add(btn("Garrison +", () -> this.order(Statecraft.Order.GARRISON, t.id(), 1, 0, 0)));
				actions.add(btn(t.autonomy() ? "Revoke autonomy" : "Grant autonomy", () -> this.order(Statecraft.Order.AUTONOMY, t.id(), 0, 0, 0)));
				String project = Statecraft.PROJECTS[this.fundChoice % Statecraft.PROJECTS.length].replace('_', ' ');
				actions.add(btn("Fund " + project + " (" + Statecraft.FUND_COST + ")", () -> this.order(Statecraft.Order.FUND, t.id(), this.fundChoice, 0, 0)));
				actions.add(btn("↻ Other project", () -> {
					this.fundChoice = (this.fundChoice + 1) % Statecraft.PROJECTS.length;
					this.rebuild();
				}));
				actions.add(btn(colonySource == t.id() ? "★ Sends settlers" : "Send settlers from here", () -> {
					colonySource = t.id();
					this.rebuild();
				}));
				if (this.anyWar()) actions.add(btn("Cede for peace", () -> this.order(Statecraft.Order.PEACE_CEDE_OURS, t.id(), 0, 0, 0)));
			} else if (them != null) {
				if (!war) {
					actions.add(btn("Declare war (" + d.armySupportWar() + "%)", () -> this.order(Statecraft.Order.WAR, t.id(), 0, 0, 0)));
					actions.add(btn("Propose alliance", () -> this.order(Statecraft.Order.ALLY, t.id(), 0, 0, 0)));
					actions.add(btn("Demand tribute", () -> this.order(Statecraft.Order.DEMAND_VASSAL, t.id(), 0, 0, 0)));
				} else {
					actions.add(btn("March on " + t.name(), () -> this.order(Statecraft.Order.WAR, t.id(), 0, 0, 0)));
					actions.add(btn("Peace: white", () -> this.order(Statecraft.Order.PEACE_WHITE, t.id(), 0, 0, 0)));
					actions.add(btn("Peace: take " + t.name(), () -> this.order(Statecraft.Order.PEACE_CEDE_THEIRS, t.id(), 0, 0, 0)));
					actions.add(btn("Peace: make vassal", () -> this.order(Statecraft.Order.PEACE_VASSAL, t.id(), 0, 0, 0)));
				}
			}
		} else if (this.panel == Panel.NONE && ruler && this.selectedLand != null) {
			int[] land = this.selectedLand;
			int source = this.colonySourceFor(land[0], land[1]);
			TownInfo from = this.town(source);
			if (from != null) {
				actions.add(btn("Found colony from " + from.name(), () -> this.order(Statecraft.Order.COLONIZE, source, 0, land[0], land[1])));
			}
		}
		for (int i = actions.size() - 1; i >= 0; i--) {
			y -= 20;
			ButtonWidget b = actions.get(i);
			b.setDimensions(w, 18);
			b.setPosition(sx, y);
			this.addDrawableChild(b);
		}
	}

	private ButtonWidget btn(String label, Runnable action) {
		return ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(0, 0, 100, 18).build();
	}

	private void toggle(Panel p) {
		this.panel = this.panel == p ? Panel.NONE : p;
		this.rebuild();
	}

	private void order(Statecraft.Order o, int a, int b, int x, int z) {
		ClientPlayNetworking.send(new AtlasPayloads.Order(o.name(), a, b, x, z));
	}

	@Nullable
	private FactionInfo mine() {
		return AtlasClient.data == null ? null : this.factionById.get(AtlasClient.data.myFaction());
	}

	private boolean hasLaw(Law l) {
		FactionInfo f = this.mine();
		return f != null && (f.laws() & (1 << l.ordinal())) != 0;
	}

	private boolean anyWar() {
		FactionInfo f = this.mine();
		return f != null && f.atWar();
	}

	@Nullable
	private TownInfo town(int id) {
		if (AtlasClient.data == null) return null;
		return AtlasClient.data.towns().stream().filter(t -> t.id() == id).findFirst().orElse(null);
	}

	private int colonySourceFor(int x, int z) {
		AtlasPayloads.Data d = AtlasClient.data;
		if (d == null) return -1;
		TownInfo chosen = this.town(colonySource);
		if (chosen != null && chosen.faction().equals(d.myFaction())) return chosen.id();
		TownInfo best = null;
		double bestD = Double.MAX_VALUE;
		for (TownInfo t : d.towns()) {
			if (!t.faction().equals(d.myFaction())) continue;
			double dd = (t.x() - x) * (double) (t.x() - x) + (t.z() - z) * (double) (t.z() - z);
			if (dd < bestD) {
				bestD = dd;
				best = t;
			}
		}
		return best == null ? -1 : best.id();
	}

	// ---------------------------------------------------------------- coordinates

	private double scale() {
		return ZOOMS[zoom];
	}

	private int mapCX() {
		return this.mapRight() / 2;
	}

	private int mapCY() {
		return TOPBAR + (this.height - TOPBAR) / 2;
	}

	private int sx(double wx) {
		return (int) Math.floor(this.mapCX() + (wx - centerX) / this.scale());
	}

	private int sy(double wz) {
		return (int) Math.floor(this.mapCY() + (wz - centerZ) / this.scale());
	}

	private double wx(double sx) {
		return centerX + (sx - this.mapCX()) * this.scale();
	}

	private double wz(double sy) {
		return centerZ + (sy - this.mapCY()) * this.scale();
	}

	private boolean inMap(double mx, double my) {
		return mx >= 0 && mx < this.mapRight() && my >= TOPBAR && my < this.height;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, PARCHMENT);
		ctx.enableScissor(0, TOPBAR, this.mapRight(), this.height);
		this.drawTerrain(ctx);
		this.drawTerritories(ctx);
		this.drawWars(ctx);
		this.drawTowns(ctx);
		this.drawWonders(ctx);
		this.drawPlayer(ctx);
		ctx.disableScissor();
		// Frame and sidebar.
		ctx.fill(0, TOPBAR - 1, this.mapRight(), TOPBAR, INK);
		ctx.fill(this.mapRight(), 0, this.mapRight() + 1, this.height, INK);
		ctx.fill(this.mapRight() + 1, 0, this.width, this.height, 0xFFDCCB9C);
		this.drawSidebar(ctx);
		this.drawScaleBar(ctx);
		if (AtlasClient.data != null) {
			String age = AtlasClient.data.age();
			String text = "⌛ " + Character.toUpperCase(age.charAt(0)) + age.substring(1);
			int w = this.textRenderer.getWidth(text);
			ctx.fill(this.mapRight() / 2 - w / 2 - 4, TOPBAR + 2, this.mapRight() / 2 + w / 2 + 4, TOPBAR + 13, 0xCCE9DCB5);
			ctx.drawText(this.textRenderer, text, this.mapRight() / 2 - w / 2, TOPBAR + 4, INK, false);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		if (this.inMap(mouseX, mouseY)) {
			TownInfo hover = this.townAt(mouseX, mouseY);
			if (hover != null) ctx.drawTooltip(this.textRenderer, this.tooltip(hover), mouseX, mouseY);
		}
	}

	private int cellFor(double scale) {
		int cell = 4;
		while (cell < scale * 2 && cell < 256) cell *= 2;
		return cell;
	}

	private void drawTerrain(DrawContext ctx) {
		int cell = this.cellFor(this.scale());
		int span = cell * AtlasPayloads.Tile.SIZE;
		int tx0 = Math.floorDiv((int) Math.floor(this.wx(0)), span), tx1 = Math.floorDiv((int) Math.floor(this.wx(this.mapRight())), span);
		int tz0 = Math.floorDiv((int) Math.floor(this.wz(TOPBAR)), span), tz1 = Math.floorDiv((int) Math.floor(this.wz(this.height)), span);
		for (int tx = tx0; tx <= tx1; tx++) {
			for (int tz = tz0; tz <= tz1; tz++) {
				int x0 = this.sx((double) tx * span), y0 = this.sy((double) tz * span);
				int x1 = this.sx((double) (tx + 1) * span), y1 = this.sy((double) (tz + 1) * span);
				Identifier tex = AtlasClient.tile(tx, tz, cell);
				if (tex == null) {
					// Not drawn yet: try the next coarser survey, else leave blank parchment.
					Identifier coarse = cell < 256 ? AtlasClient.tile(Math.floorDiv(tx, 2), Math.floorDiv(tz, 2), cell * 2) : null;
					if (coarse != null) {
						int u = Math.floorMod(tx, 2) * 16, v = Math.floorMod(tz, 2) * 16;
						ctx.drawTexture(coarse, x0, y0, x1 - x0, y1 - y0, u, v, 16, 16, 32, 32);
					}
					continue;
				}
				ctx.drawTexture(tex, x0, y0, x1 - x0, y1 - y0, 0, 0, 32, 32, 32, 32);
			}
		}
		// A faint wash so the ink of borders reads well.
		ctx.fill(0, TOPBAR, this.mapRight(), this.height, 0x22E9DCB5);
	}

	private int colourFor(TownInfo t) {
		FactionInfo f = this.factionById.get(t.faction());
		if (f == null) return 0x888888;
		return switch (mode) {
			case POLITICAL -> f.color();
			case DIPLOMATIC -> f.mine() ? 0xD4A017 : f.relation() >= 50 ? 0x3E9E4A : f.relation() <= -50 ? 0xB03030
					: lerp(0xC06A30, 0x9AA040, (f.relation() + 50) / 100.0);
			case WEALTH -> lerp(0x8A6A4A, 0xE0B020, MathHelper.clamp(t.treasury() / 200.0, 0, 1));
			case LOYALTY -> lerp(0xB03030, 0x3E9E4A, t.loyalty() / 100.0);
			case FAITH -> this.faithColour(f.religion());
		};
	}

	private int faithColour(String religion) {
		if (AtlasClient.data == null) return 0x888888;
		for (AtlasPayloads.ReligionInfo r : AtlasClient.data.religions()) if (r.id().equals(religion)) return r.color();
		return 0x888888;
	}

	@Nullable
	private AtlasPayloads.ReligionInfo faith(String id) {
		if (AtlasClient.data == null) return null;
		for (AtlasPayloads.ReligionInfo r : AtlasClient.data.religions()) if (r.id().equals(id)) return r;
		return null;
	}

	private static int lerp(int a, int b, double t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private void drawTerritories(DrawContext ctx) {
		if (AtlasClient.data == null) return;
		for (TownInfo t : AtlasClient.data.towns()) {
			int c = this.colourFor(t);
			int x0 = this.sx(t.minX()), y0 = this.sy(t.minZ()), x1 = this.sx(t.maxX() + 1), y1 = this.sy(t.maxZ() + 1);
			if (x1 - x0 < 2) {
				x0 -= 1;
				x1 += 1;
			}
			if (y1 - y0 < 2) {
				y0 -= 1;
				y1 += 1;
			}
			ctx.fill(x0, y0, x1, y1, 0x60000000 | c);
			int edge = 0xFF000000 | darken(c);
			ctx.fill(x0, y0, x1, y0 + 1, edge);
			ctx.fill(x0, y1 - 1, x1, y1, edge);
			ctx.fill(x0, y0, x0 + 1, y1, edge);
			ctx.fill(x1 - 1, y0, x1, y1, edge);
		}
	}

	private static int darken(int c) {
		return lerp(c, 0x2A1E12, 0.45);
	}

	private void drawWars(DrawContext ctx) {
		AtlasPayloads.Data d = AtlasClient.data;
		if (d == null) return;
		for (FactionInfo f : d.factions()) {
			if (f.warGoal() < 0) continue;
			TownInfo goal = this.town(f.warGoal());
			if (goal == null) continue;
			TownInfo from = null;
			double best = Double.MAX_VALUE;
			for (TownInfo t : d.towns()) {
				if (!t.faction().equals(f.id())) continue;
				double dd = Math.hypot(t.x() - goal.x(), t.z() - goal.z());
				if (dd < best) {
					best = dd;
					from = t;
				}
			}
			if (from == null) continue;
			int ax = this.sx(from.x()), ay = this.sy(from.z()), bx = this.sx(goal.x()), by = this.sy(goal.z());
			int steps = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / 4));
			int col = 0xFF000000 | darken(f.color());
			for (int i = 0; i < steps; i += 2) {
				int px = ax + (bx - ax) * i / steps, py = ay + (by - ay) * i / steps;
				ctx.fill(px - 1, py - 1, px + 1, py + 1, col);
			}
			ctx.drawText(this.textRenderer, "⚔", bx - 3, by - 14, 0xFFB02020, false);
		}
	}

	private void drawTowns(DrawContext ctx) {
		if (AtlasClient.data == null) return;
		for (TownInfo t : AtlasClient.data.towns()) {
			int x = this.sx(t.x()), y = this.sy(t.z());
			int r = switch (t.rank()) {
				case "Village" -> 2;
				case "Town" -> 3;
				case "City" -> 4;
				default -> 5;
			};
			ctx.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, INK);
			ctx.fill(x - r, y - r, x + r, y + r, 0xFF000000 | this.colourFor(t));
			if (t == this.selected) {
				ctx.fill(x - r - 3, y - r - 3, x + r + 3, y - r - 2, 0xFFFFFFFF);
				ctx.fill(x - r - 3, y + r + 2, x + r + 3, y + r + 3, 0xFFFFFFFF);
			}
			if (t.capital()) ctx.drawText(this.textRenderer, "★", x - 3, y - r - 10, 0xFFB8860B, false);
			if (t.besieged()) ctx.drawText(this.textRenderer, "⚑", x + r + 1, y - r - 8, 0xFFD04020, false);
			if (zoom <= 3 || t.capital()) {
				String label = t.name();
				int w = this.textRenderer.getWidth(label);
				ctx.drawText(this.textRenderer, label, x - w / 2, y + r + 3, INK, false);
			}
		}
		if (this.selectedLand != null) {
			int x = this.sx(this.selectedLand[0]), y = this.sy(this.selectedLand[1]);
			ctx.drawText(this.textRenderer, "✚", x - 3, y - 4, 0xFF7A1F1F, false);
		}
	}

	private void drawWonders(DrawContext ctx) {
		if (AtlasClient.data == null) return;
		for (AtlasPayloads.WonderInfo w : AtlasClient.data.wonders()) {
			int x = this.sx(w.x()), y = this.sy(w.z());
			boolean building = w.name().endsWith("(being built)");
			int col = w.ruined() ? 0xFF7A7060 : building ? 0xFFB8A070 : 0xFFD4A017;
			ctx.fill(x - 3, y - 3, x + 3, y + 3, INK);
			ctx.fill(x - 2, y - 2, x + 2, y + 2, col);
			if (zoom <= 2 && !building) {
				String label = w.name();
				ctx.drawText(this.textRenderer, label, x - this.textRenderer.getWidth(label) / 2, y - 13, w.ruined() ? 0xFF7A7060 : 0xFF8A6A10, false);
			}
		}
	}

	@Nullable
	private net.minecraft.item.ItemStack bannerOf(@Nullable FactionInfo f) {
		if (f == null || f.banner().isEmpty() || this.client == null || this.client.world == null) return null;
		try {
			return net.colonists.faction.Banners.item(this.client.world.getRegistryManager(), f.banner(), f.name());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void drawPlayer(DrawContext ctx) {
		if (this.client == null || this.client.player == null) return;
		int x = this.sx(this.client.player.getX()), y = this.sy(this.client.player.getZ());
		ctx.fill(x - 2, y - 2, x + 2, y + 2, 0xFF000000);
		ctx.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF);
	}

	private void drawScaleBar(DrawContext ctx) {
		int blocks = (int) (64 * this.scale());
		int y = this.height - 10;
		ctx.fill(6, y, 6 + 64, y + 2, INK);
		ctx.drawText(this.textRenderer, blocks + " blocks", 74, y - 3, INK, false);
	}

	private void drawSidebar(DrawContext ctx) {
		int x = this.mapRight() + 6, y = TOPBAR + 4, w = SIDEBAR - 12;
		AtlasPayloads.Data d = AtlasClient.data;
		List<Text> lines = new ArrayList<>();
		if (d == null) {
			lines.add(Text.literal("Unrolling the map...").formatted(Formatting.ITALIC));
		} else if (this.panel == Panel.LEGENDS) {
			lines.add(Text.literal("Wonders of the world").formatted(Formatting.BOLD));
			if (d.wonders().isEmpty()) lines.add(Text.literal("None yet raised.").withColor(0x6B5638));
			for (AtlasPayloads.WonderInfo w : d.wonders()) {
				lines.add(Text.literal((w.ruined() ? "✖ " : "◆ ") + w.name()).withColor(w.ruined() ? 0x8A7A60 : 0x8A6A10));
				lines.add(Text.literal("  " + w.town() + (w.owner().isEmpty() ? "" : ", " + w.owner()) + (w.ruined() ? " (in ruins)" : "")).withColor(0x6B5638));
			}
			lines.add(Text.literal(""));
			lines.add(Text.literal("Faiths of the world").formatted(Formatting.BOLD));
			for (AtlasPayloads.ReligionInfo r : d.religions()) {
				lines.add(Text.literal("☩ ").withColor(r.color()).append(Text.literal(r.name()).withColor(0x3B2A1A)));
				lines.add(Text.literal("  " + r.tenets() + (r.parent().isEmpty() ? "" : " (from " + r.parent() + ")")).withColor(0x6B5638));
				lines.add(Text.literal("  " + r.realms() + " realms" + (r.prophet().isEmpty() ? "" : " · prophet: " + r.prophet())).withColor(0x6B5638));
			}
			lines.add(Text.literal(""));
			lines.add(Text.literal("Legends").formatted(Formatting.BOLD));
			if (d.legends().isEmpty()) lines.add(Text.literal("None yet. Deeds and fortune make legends.").withColor(0x6B5638));
			for (AtlasPayloads.LegendInfo l : d.legends()) {
				lines.add(Text.literal((l.alive() ? "✦ " : "† ") + l.name()).withColor(l.alive() ? 0x3B2A1A : 0x8A7A60));
				lines.add(Text.literal("  " + l.kind() + " of the " + l.faction()).withColor(0x6B5638));
			}
		} else if (this.panel == Panel.DIPLOMACY) {
			lines.add(Text.literal("Nations of the world").formatted(Formatting.BOLD));
			for (FactionInfo f : d.factions()) {
				lines.add(Text.literal("■ ").withColor(f.color()).append(Text.literal(f.name()).withColor(0x3B2A1A)));
				String rel = f.mine() ? "your realm" : f.relation() <= -50 ? "at war (" + f.relation() + ")" : f.relation() >= 50 ? "allied (" + f.relation() + ")" : "relations " + f.relation();
				lines.add(Text.literal("  " + f.title() + ", " + f.people() + " people, " + rel).withColor(0x6B5638));
			}
		} else if (this.panel == Panel.LAWS) {
			FactionInfo f = this.mine();
			lines.add(Text.literal("Law book").formatted(Formatting.BOLD));
			if (f == null) {
				lines.add(Text.literal("You belong to no realm."));
			} else {
				for (Law l : Law.values()) lines.add(Text.literal((this.hasLaw(l) ? "✔ " : "✘ ") + l.label).withColor(this.hasLaw(l) ? 0x3B2A1A : 0x8A7A60));
				lines.add(Text.literal((f.slavery() ? "✔ " : "✘ ") + "Captivity").withColor(f.slavery() ? 0x7A1F1F : 0x8A7A60));
				if (!d.ruler()) lines.add(Text.literal("Only the ruler can change the laws.").formatted(Formatting.ITALIC));
			}
		} else if (this.panel == Panel.REALM || (this.selected == null && this.selectedLand == null)) {
			FactionInfo f = this.mine();
			if (f == null) {
				lines.add(Text.literal("You belong to no realm.").formatted(Formatting.BOLD));
				lines.add(Text.literal("Found one (/colony found) or join one (/colony join) to take part in its politics.").withColor(0x6B5638));
			} else {
				lines.add(Text.literal(f.title() + " of the " + f.name()).formatted(Formatting.BOLD));
				lines.add(Text.literal(f.government() + (f.ruler().isEmpty() ? "" : " — " + f.ruler())));
				lines.add(Text.literal(net.colonists.legend.Ages.ERAS[Math.max(0, Math.min(3, f.era()))] + " Era").withColor(0x6B5638));
				AtlasPayloads.ReligionInfo faith = this.faith(f.religion());
				if (faith != null) lines.add(Text.literal("☩ " + faith.name()).withColor(faith.color()));
				lines.add(Text.literal(d.ruler() ? "You rule this realm." : d.heir() ? "You are heir to the throne." : "You are a member.").formatted(Formatting.ITALIC));
				lines.add(Text.literal("People  " + bar(f.approval())));
				lines.add(Text.literal("Army    " + bar(f.army())));
				lines.add(Text.literal("Court   " + bar(f.court())));
				lines.add(Text.literal("Treasury " + f.treasury() + " emeralds"));
				lines.add(Text.literal(f.people() + " people in " + d.towns().stream().filter(t -> t.faction().equals(f.id())).count() + " towns"));
				if (f.atWar()) lines.add(Text.literal("⚔ At war").withColor(0xB03030));
				if (!f.overlord().isEmpty()) lines.add(Text.literal("Pays tribute to the " + this.nameOf(f.overlord())));
				lines.add(Text.literal(""));
				lines.add(Text.literal("Click a town to see it; click open land to plan a colony. Drag to pan, scroll to zoom.").withColor(0x6B5638));
			}
		} else if (this.selected != null) {
			TownInfo t = this.selected;
			FactionInfo f = this.factionById.get(t.faction());
			lines.add(Text.literal((t.capital() ? "★ " : "") + t.name()).formatted(Formatting.BOLD));
			lines.add(Text.literal(t.rank() + " of the " + (f == null ? "?" : f.name())));
			AtlasPayloads.ReligionInfo faith = f == null ? null : this.faith(f.religion());
			if (faith != null) lines.add(Text.literal("☩ " + faith.name()).withColor(faith.color()));
			lines.add(Text.literal("Population " + t.population()));
			lines.add(Text.literal("Loyalty " + bar(t.loyalty())));
			lines.add(Text.literal("Treasury " + t.treasury() + "   Guards " + t.guards()));
			if (t.taxRate() >= 0) lines.add(Text.literal("Taxes " + t.taxRate() + "%"));
			if (t.garrison() >= 0) lines.add(Text.literal("Garrison order: " + t.garrison()));
			if (t.autonomy()) lines.add(Text.literal("Self-governing"));
			if (!t.priority().isEmpty()) lines.add(Text.literal("Building first: " + t.priority().replace('_', ' ')));
			if (t.besieged()) lines.add(Text.literal("⚑ Under siege!").withColor(0xB03030));
			lines.add(Text.literal(t.x() + ", " + t.z()).withColor(0x6B5638));
		} else if (this.selectedLand != null) {
			lines.add(Text.literal("Open land").formatted(Formatting.BOLD));
			lines.add(Text.literal(this.selectedLand[0] + ", " + this.selectedLand[1]).withColor(0x6B5638));
			if (d.ruler()) lines.add(Text.literal("Send settlers here to found a new town.").withColor(0x6B5638));
		}
		// The nation's banner, top right of the sidebar.
		FactionInfo shown = this.selected != null && this.panel == Panel.NONE ? this.factionById.get(this.selected.faction()) : this.mine();
		net.minecraft.item.ItemStack banner = this.bannerOf(shown);
		if (banner != null && this.panel != Panel.LEGENDS && this.panel != Panel.DIPLOMACY) {
			ctx.drawItem(banner, this.width - 22, TOPBAR + 4);
			w -= 20;
		}
		for (Text t : lines) {
			for (var row : this.textRenderer.wrapLines(t, w)) {
				ctx.drawText(this.textRenderer, row, x, y, INK, false);
				y += 10;
			}
		}
	}

	private String nameOf(String factionId) {
		FactionInfo f = this.factionById.get(factionId);
		return f == null ? factionId : f.name();
	}

	private static String bar(int v) {
		int n = MathHelper.clamp(v / 10, 0, 10);
		return "▮".repeat(n) + "▯".repeat(10 - n) + " " + v;
	}

	private List<Text> tooltip(TownInfo t) {
		FactionInfo f = this.factionById.get(t.faction());
		List<Text> l = new ArrayList<>();
		l.add(Text.literal((t.capital() ? "★ " : "") + t.name()).formatted(Formatting.BOLD));
		if (f != null) l.add(Text.literal(f.title() + " of the " + f.name()).withColor(f.color()));
		l.add(Text.literal(t.rank() + " · pop. " + t.population()));
		l.add(Text.literal("Loyalty " + t.loyalty() + "% · treasury " + t.treasury()));
		if (t.besieged()) l.add(Text.literal("Under siege!").formatted(Formatting.RED));
		return l;
	}

	// ---------------------------------------------------------------- input

	@Nullable
	private TownInfo townAt(double mx, double my) {
		if (AtlasClient.data == null) return null;
		TownInfo best = null;
		double bestD = 64;
		for (TownInfo t : AtlasClient.data.towns()) {
			double dd = Math.pow(this.sx(t.x()) - mx, 2) + Math.pow(this.sy(t.z()) - my, 2);
			if (dd < bestD) {
				bestD = dd;
				best = t;
			}
		}
		if (best != null) return best;
		// Inside a town's borders counts too.
		double wx = this.wx(mx), wz = this.wz(my);
		for (TownInfo t : AtlasClient.data.towns()) {
			if (wx >= t.minX() && wx <= t.maxX() && wz >= t.minZ() && wz <= t.maxZ()) return t;
		}
		return null;
	}

	private boolean dragged;

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		this.dragged = false;
		return this.inMap(mx, my);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (super.mouseReleased(mx, my, button)) return true;
		if (!this.dragged && button == 0 && this.inMap(mx, my)) {
			TownInfo t = this.townAt(mx, my);
			this.panel = Panel.NONE;
			if (t != null) {
				this.selected = t;
				this.selectedLand = null;
			} else {
				this.selected = null;
				this.selectedLand = new int[]{(int) this.wx(mx), (int) this.wz(my)};
			}
			this.rebuild();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (button == 0 && this.inMap(mx, my)) {
			if (Math.abs(dx) + Math.abs(dy) > 0.5) this.dragged = true;
			centerX -= dx * this.scale();
			centerZ -= dy * this.scale();
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		if (!this.inMap(mx, my)) return super.mouseScrolled(mx, my, h, v);
		double wxBefore = this.wx(mx), wzBefore = this.wz(my);
		zoom = MathHelper.clamp(zoom + (v > 0 ? -1 : 1), 0, ZOOMS.length - 1);
		// Keep the spot under the cursor in place.
		centerX += wxBefore - this.wx(mx);
		centerZ += wzBefore - this.wz(my);
		this.rebuild();
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (this.client != null && this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			this.close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
