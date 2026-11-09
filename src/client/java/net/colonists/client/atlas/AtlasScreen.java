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
	private enum Mode {POLITICAL, DIPLOMATIC, WEALTH, LOYALTY, FAITH, FARMS}

	private enum Panel {NONE, REALM, LAWS, DIPLOMACY, LEGENDS}

	private static final int[] ZOOMS = {1, 2, 4, 8, 16, 32};
	private static final int TOPBAR = 24;
	private static final int PARCHMENT = 0xFFE9DCB5, INK = 0xFF3B2A1A, INK_SOFT = 0xFF6B5638, RUBRIC = 0xFF7A2E22;
	private static final int SIDE_BG = 0xFFDCCB9C, BAR_BG = 0xFFC9B585;

	// Remembered between openings.
	private static double centerX = Double.NaN, centerZ;
	private static int zoom = 3;
	private static Mode mode = Mode.POLITICAL;
	private static int colonySource = -1;

	private Panel panel = Panel.NONE;
	@Nullable private TownInfo selected;
	@Nullable private AtlasPayloads.ManorInfo selectedManor;
	@Nullable private int[] selectedLand;
	private int fundChoice;
	private int govChoice = 1;
	private int refresh;
	/** The sidebar body scrolls; actions sit in a fixed area beneath it. */
	private int scroll, contentHeight, actionsTop;
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
		if (this.selectedManor != null && AtlasClient.data != null) {
			AtlasPayloads.ManorInfo old = this.selectedManor;
			this.selectedManor = AtlasClient.data.manors().stream().filter(m -> m.town() == old.town() && m.lord().equals(old.lord())).findFirst().orElse(null);
		}
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

	private int sidebarWidth() {
		return MathHelper.clamp((int) (this.width * 0.3), 172, 240);
	}

	private int mapRight() {
		return this.width - this.sidebarWidth();
	}

	private void rebuild() {
		this.clearChildren();
		// Map modes, as tabs sized to their names (shrunk evenly if the map is narrow).
		String[] labels = {"Realms", "Relations", "Wealth", "Loyalty", "Faith", "Farms"};
		int[] widths = new int[labels.length];
		int total = 0;
		for (int i = 0; i < labels.length; i++) total += widths[i] = this.textRenderer.getWidth(labels[i]) + 14;
		double fit = Math.min(1.0, (this.mapRight() - 8 - 2 * labels.length) / (double) total);
		int x = 4;
		for (int i = 0; i < labels.length; i++) {
			Mode m = Mode.values()[i];
			int w = Math.max(24, (int) (widths[i] * fit));
			this.addDrawableChild(new ParchmentButton(x, 3, w, 18, Text.literal(labels[i]), b -> {
				mode = m;
				this.rebuild();
			}, mode == m));
			x += w + 2;
		}
		// Sidebar tabs, evenly across the sidebar.
		int sx = this.mapRight() + 4, sw = this.sidebarWidth() - 8;
		Panel[] tabs = {Panel.REALM, Panel.LAWS, Panel.DIPLOMACY, Panel.LEGENDS};
		String[] tabNames = {"Realm", "Laws", "Nations", "Lore"};
		int tw = (sw - 3 * 2) / 4;
		for (int i = 0; i < 4; i++) {
			Panel p = tabs[i];
			this.addDrawableChild(new ParchmentButton(sx + i * (tw + 2), 3, i == 3 ? sw - 3 * (tw + 2) : tw, 18, Text.literal(tabNames[i]), b -> this.toggle(p), this.panel == p));
		}
		int w = sw;

		AtlasPayloads.Data d = AtlasClient.data;
		this.actionsTop = this.height - 4;
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
				if (!this.manorsOf(d.myFaction()).isEmpty()) actions.add(btn("Gift the lords (20)", () -> this.order(Statecraft.Order.GIFT_LORDS, 0, 20, 0, 0)));
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
		// Actions in a grid at the foot of the sidebar: one column when few, two when many, never more than half its height.
		int cols = actions.size() > 4 ? 2 : 1;
		int rows = (actions.size() + cols - 1) / cols;
		int maxRows = Math.max(1, (this.height - TOPBAR) / 2 / 20);
		rows = Math.min(rows, maxRows);
		int bw = cols == 1 ? w : (w - 2) / 2;
		this.actionsTop = actions.isEmpty() ? this.height - 4 : y - rows * 20 - 6;
		for (int i = 0; i < actions.size() && i < rows * cols; i++) {
			ButtonWidget b = actions.get(i);
			int r = i / cols, c = i % cols;
			b.setDimensions(bw, 18);
			b.setPosition(sx + c * (bw + 2), this.actionsTop + 6 + r * 20);
			this.addDrawableChild(b);
		}
	}

	private ButtonWidget btn(String label, Runnable action) {
		return new ParchmentButton(0, 0, 100, 18, Text.literal(label), b -> action.run(), false);
	}

	private void toggle(Panel p) {
		this.panel = this.panel == p ? Panel.NONE : p;
		this.scroll = 0;
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
		this.drawManors(ctx);
		this.drawWars(ctx);
		this.drawTowns(ctx);
		this.drawWonders(ctx);
		this.drawPlayer(ctx);
		ctx.disableScissor();
		// The map's frame: a double ink rule.
		int mr = this.mapRight();
		ctx.fill(0, 0, this.width, TOPBAR, 0xFFCDB98A);
		ctx.fill(0, TOPBAR - 1, this.width, TOPBAR, INK);
		ctx.fill(2, TOPBAR + 2, mr - 2, TOPBAR + 3, 0x66000000 | (INK & 0xFFFFFF));
		ctx.fill(2, this.height - 3, mr - 2, this.height - 2, 0x66000000 | (INK & 0xFFFFFF));
		ctx.fill(2, TOPBAR + 2, 3, this.height - 2, 0x66000000 | (INK & 0xFFFFFF));
		ctx.fill(mr - 3, TOPBAR + 2, mr - 2, this.height - 2, 0x66000000 | (INK & 0xFFFFFF));
		// The sidebar.
		ctx.fill(mr, TOPBAR - 1, mr + 1, this.height, INK);
		ctx.fill(mr + 1, TOPBAR, this.width, this.height, SIDE_BG);
		this.drawSidebar(ctx);
		this.drawLegend(ctx);
		if (AtlasClient.data != null) {
			String age = AtlasClient.data.age();
			String text = "⌛ " + Character.toUpperCase(age.charAt(0)) + age.substring(1);
			int w = this.textRenderer.getWidth(text);
			int cx = mr / 2;
			ctx.fill(cx - w / 2 - 6, TOPBAR + 5, cx + w / 2 + 6, TOPBAR + 18, INK);
			ctx.fill(cx - w / 2 - 5, TOPBAR + 6, cx + w / 2 + 5, TOPBAR + 17, PARCHMENT);
			ctx.drawText(this.textRenderer, text, cx - w / 2, TOPBAR + 8, INK, false);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		if (this.inMap(mouseX, mouseY)) {
			TownInfo hover = this.townAt(mouseX, mouseY);
			AtlasPayloads.ManorInfo manor = hover == null ? this.manorAt(mouseX, mouseY) : null;
			if (hover != null) ctx.drawTooltip(this.textRenderer, this.tooltip(hover), mouseX, mouseY);
			else if (manor != null) ctx.drawTooltip(this.textRenderer, this.manorTooltip(manor), mouseX, mouseY);
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
			case FARMS -> lerp(f.color(), 0xE9DCB5, 0.55);
		};
	}

	// ---------------------------------------------------------------- manors

	static int cropColour(String crop) {
		return switch (crop) {
			case "wheat" -> 0xD8B640;
			case "carrot" -> 0xE07A20;
			case "potato" -> 0xB89A5A;
			case "beetroot" -> 0xA03040;
			case "melon" -> 0x6AA830;
			case "pumpkin" -> 0xE08A10;
			case "sugar_cane" -> 0x88C060;
			case "sweet_berries" -> 0xB02050;
			default -> 0xC0A060;
		};
	}

	static String cropName(String crop) {
		return switch (crop) {
			case "carrot" -> "carrots";
			case "potato" -> "potatoes";
			case "melon" -> "melons";
			case "pumpkin" -> "pumpkins";
			case "sugar_cane" -> "sugar cane";
			case "sweet_berries" -> "berries";
			default -> crop;
		};
	}

	private List<AtlasPayloads.ManorInfo> manorsOf(String faction) {
		List<AtlasPayloads.ManorInfo> list = new ArrayList<>();
		if (AtlasClient.data != null) for (AtlasPayloads.ManorInfo m : AtlasClient.data.manors()) if (m.faction().equals(faction)) list.add(m);
		return list;
	}

	/** The landed lords' favour in a realm (weighted by land), or -1. */
	private int lordsFavour(String faction) {
		int sum = 0, w = 0;
		for (AtlasPayloads.ManorInfo m : this.manorsOf(faction)) {
			int k = Math.max(1, m.fields().length / 4);
			sum += m.favour() * k;
			w += k;
		}
		return w == 0 ? -1 : sum / w;
	}

	/** Fields drawn as ploughed strips: the realm's colour (or the crop's, in Farms mode), with the estate's name when close. */
	private void drawManors(DrawContext ctx) {
		if (AtlasClient.data == null) return;
		for (AtlasPayloads.ManorInfo m : AtlasClient.data.manors()) {
			FactionInfo f = this.factionById.get(m.faction());
			int realm = f == null ? 0x888888 : f.color();
			int[] r = m.fields();
			int labelX = 0, labelY = Integer.MAX_VALUE;
			for (int i = 0; i + 3 < r.length; i += 4) {
				int c = mode == Mode.FARMS ? cropColour(m.crops().get(i / 4)) : lerp(realm, 0xD8C080, 0.35);
				int x0 = this.sx(r[i]), y0 = this.sy(r[i + 1]), x1 = Math.max(x0 + 1, this.sx(r[i + 2] + 1)), y1 = Math.max(y0 + 1, this.sy(r[i + 3] + 1));
				ctx.fill(x0, y0, x1, y1, (mode == Mode.FARMS ? 0xD0000000 : 0x90000000) | c);
				// Furrows.
				if (y1 - y0 >= 6) for (int y = y0 + 2; y < y1 - 1; y += 3) ctx.fill(x0 + 1, y, x1 - 1, y + 1, 0x40000000 | darken(c));
				int edge = 0xFF000000 | darken(c);
				ctx.fill(x0, y0, x1, y0 + 1, edge);
				ctx.fill(x0, y1 - 1, x1, y1, edge);
				ctx.fill(x0, y0, x0 + 1, y1, edge);
				ctx.fill(x1 - 1, y0, x1, y1, edge);
				if (y0 < labelY) {
					labelY = y0;
					labelX = (x0 + x1) / 2;
				}
			}
			boolean chosen = this.selectedManor != null && this.selectedManor.name().equals(m.name()) && this.selectedManor.town() == m.town();
			// The manor house: a small hall mark (hollow while it is still going up).
			int hx = this.sx(m.seatX()), hy = this.sy(m.seatZ());
			int hr = zoom <= 1 ? 3 : 2;
			ctx.fill(hx - hr - 1, hy - hr - 1, hx + hr + 1, hy + hr + 1, INK);
			ctx.fill(hx - hr, hy - hr, hx + hr, hy + hr, m.hall() ? 0xFF000000 | lerp(realm, 0xFFFFFF, 0.2) : PARCHMENT);
			if (zoom <= 2 || chosen || mode == Mode.FARMS && zoom <= 3) {
				String label = m.name() + " (" + m.allegiance() + ")";
				int w = this.textRenderer.getWidth(label);
				int ly = labelY - 11;
				// The family's arms beside the name, when close enough to see.
				net.minecraft.item.ItemStack arms = zoom <= 1 ? this.bannerItem(m.banner(), m.name()) : null;
				int armsW = arms != null ? 14 : 0;
				int left = labelX - (w + armsW) / 2;
				ctx.fill(left - 2, ly - 1, left + w + armsW + 2, ly + 9, chosen ? 0xEEF4E9C8 : 0xBBE9DCB5);
				if (arms != null) {
					ctx.getMatrices().push();
					ctx.getMatrices().translate(left - 1, ly - 3, 0);
					ctx.getMatrices().scale(0.8f, 0.8f, 1f);
					ctx.drawItem(arms, 0, 0);
					ctx.getMatrices().pop();
				}
				ctx.drawText(this.textRenderer, label, left + armsW, ly, chosen ? RUBRIC : INK_SOFT, false);
			}
		}
	}

	@Nullable
	private AtlasPayloads.ManorInfo manorAt(double mx, double my) {
		if (AtlasClient.data == null) return null;
		double wx = this.wx(mx), wz = this.wz(my);
		double pad = this.scale() * 2; // a couple of pixels of grace when zoomed out
		for (AtlasPayloads.ManorInfo m : AtlasClient.data.manors()) {
			int[] r = m.fields();
			for (int i = 0; i + 3 < r.length; i += 4) if (wx >= r[i] - pad && wx <= r[i + 2] + 1 + pad && wz >= r[i + 1] - pad && wz <= r[i + 3] + 1 + pad) return m;
		}
		return null;
	}

	private List<Text> manorTooltip(AtlasPayloads.ManorInfo m) {
		List<Text> l = new ArrayList<>();
		l.add(Text.literal(m.name()).formatted(Formatting.BOLD));
		FactionInfo f = this.factionById.get(m.faction());
		l.add(Text.literal(m.allegiance()).withColor(f == null ? 0xAAAAAA : f.color()));
		l.add(Text.literal("Lord: " + (m.lord().isEmpty() ? "none" : m.lord()) + " · near " + m.townName()));
		l.add(Text.literal(this.cropsSummary(m)));
		l.add(Text.literal(m.laborers() + " laborers · " + m.harvested() + " crops brought in · favour " + m.favour()));
		return l;
	}

	private String cropsSummary(AtlasPayloads.ManorInfo m) {
		StringBuilder b = new StringBuilder();
		int area = 0;
		java.util.LinkedHashMap<String, Integer> byCrop = new java.util.LinkedHashMap<>();
		int[] r = m.fields();
		for (int i = 0; i + 3 < r.length; i += 4) {
			int a = (r[i + 2] - r[i] + 1) * (r[i + 3] - r[i + 1] + 1);
			area += a;
			byCrop.merge(m.crops().get(i / 4), a, Integer::sum);
		}
		for (var e : byCrop.entrySet()) {
			if (b.length() > 0) b.append(", ");
			b.append(cropName(e.getKey()));
		}
		return (r.length / 4) + (r.length / 4 == 1 ? " field" : " fields") + " (" + area + " blocks): " + b;
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
				case "War camp" -> 3;
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
			if (t.rank().equals("War camp")) ctx.drawText(this.textRenderer, "⚔", x - 3, y - r - 10, 0xFF7A1F1F, false);
			else if (t.capital()) ctx.drawText(this.textRenderer, "★", x - 3, y - r - 10, 0xFFB8860B, false);
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

	private final Map<String, net.minecraft.item.ItemStack> bannerCache = new HashMap<>();

	@Nullable
	private net.minecraft.item.ItemStack bannerItem(String banner, String name) {
		if (banner.isEmpty() || this.client == null || this.client.world == null) return null;
		return this.bannerCache.computeIfAbsent(banner + "|" + name, k -> {
			try {
				return net.colonists.faction.Banners.item(this.client.world.getRegistryManager(), banner, name);
			} catch (RuntimeException e) {
				return net.minecraft.item.ItemStack.EMPTY;
			}
		});
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

	/** Bottom-left of the map: a scale bar, and a key for the current map mode. */
	private void drawLegend(DrawContext ctx) {
		int blocks = (int) (64 * this.scale());
		int x = 8, y = this.height - 14;
		List<String> keys = new ArrayList<>();
		List<Integer> colours = new ArrayList<>();
		switch (mode) {
			case DIPLOMATIC -> {
				keys.add("Yours"); colours.add(0xD4A017);
				keys.add("Allied"); colours.add(0x3E9E4A);
				keys.add("Wary"); colours.add(0xC06A30);
				keys.add("At war"); colours.add(0xB03030);
			}
			case WEALTH -> {
				keys.add("Poor"); colours.add(0x8A6A4A);
				keys.add("Rich"); colours.add(0xE0B020);
			}
			case LOYALTY -> {
				keys.add("Restless"); colours.add(0xB03030);
				keys.add("Loyal"); colours.add(0x3E9E4A);
			}
			case FARMS -> {
				java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
				if (AtlasClient.data != null) for (AtlasPayloads.ManorInfo m : AtlasClient.data.manors()) seen.addAll(m.crops());
				for (String c : seen) {
					keys.add(cropName(c));
					colours.add(cropColour(c));
				}
				if (seen.isEmpty()) {
					keys.add("No manors yet");
					colours.add(0xC0A060);
				}
			}
			default -> {
			}
		}
		int boxW = 74 + this.textRenderer.getWidth(blocks + " blocks");
		int kx = 0;
		for (String k : keys) kx += 12 + this.textRenderer.getWidth(k) + 6;
		boxW = Math.max(boxW, kx + 6);
		int boxH = keys.isEmpty() ? 14 : 26;
		ctx.fill(x - 3, this.height - 6 - boxH, x - 3 + boxW, this.height - 6, 0xCCE9DCB5);
		ctx.fill(x, y, x + 64, y + 2, INK);
		ctx.fill(x, y - 2, x + 1, y + 4, INK);
		ctx.fill(x + 63, y - 2, x + 64, y + 4, INK);
		ctx.drawText(this.textRenderer, blocks + " blocks", x + 70, y - 3, INK, false);
		int ky = y - 14, kxx = x;
		for (int i = 0; i < keys.size(); i++) {
			ctx.fill(kxx, ky, kxx + 9, ky + 9, INK);
			ctx.fill(kxx + 1, ky + 1, kxx + 8, ky + 8, 0xFF000000 | colours.get(i));
			ctx.drawText(this.textRenderer, keys.get(i), kxx + 12, ky + 1, INK, false);
			kxx += 12 + this.textRenderer.getWidth(keys.get(i)) + 6;
		}
	}

	/** A line of the sidebar: a heading, a paragraph, a labelled bar, a coloured key, or a gap. */
	private record Row(int kind, Text text, int value, int colour) {
		static final int HEADING = 0, TEXT = 1, BAR = 2, KEY = 3, GAP = 4, TITLE = 5;
	}

	private static Row heading(String s) {
		return new Row(Row.HEADING, Text.literal(s), 0, RUBRIC);
	}

	private static Row text(String s, int colour) {
		return new Row(Row.TEXT, Text.literal(s), 0, colour);
	}

	private static Row text(Text t) {
		return new Row(Row.TEXT, t, 0, INK);
	}

	private static Row barRow(String label, int value, int colour) {
		return new Row(Row.BAR, Text.literal(label), value, colour);
	}

	private static Row key(String s, int colour) {
		return new Row(Row.KEY, Text.literal(s), 0, colour);
	}

	private static final Row GAP_ROW = new Row(Row.GAP, Text.empty(), 0, 0);

	private List<Row> sidebarRows(@Nullable AtlasPayloads.Data d) {
		List<Row> rows = new ArrayList<>();
		if (d == null) {
			rows.add(text("Unrolling the map…", INK_SOFT));
			return rows;
		}
		if (this.panel == Panel.LEGENDS) {
			rows.add(heading("Wonders of the world"));
			if (d.wonders().isEmpty()) rows.add(text("None yet raised.", INK_SOFT));
			for (AtlasPayloads.WonderInfo wi : d.wonders()) {
				rows.add(key(wi.name(), wi.ruined() ? 0x8A7A60 : 0xD4A017));
				rows.add(text("   " + wi.town() + (wi.owner().isEmpty() ? "" : ", " + wi.owner()) + (wi.ruined() ? " (in ruins)" : ""), INK_SOFT));
			}
			rows.add(GAP_ROW);
			rows.add(heading("Faiths of the world"));
			if (d.religions().isEmpty()) rows.add(text("No faith has yet been founded.", INK_SOFT));
			for (AtlasPayloads.ReligionInfo r : d.religions()) {
				rows.add(key(r.name(), r.color()));
				rows.add(text("   " + r.tenets() + (r.parent().isEmpty() ? "" : " (from " + r.parent() + ")"), INK_SOFT));
				rows.add(text("   " + r.realms() + (r.realms() == 1 ? " realm" : " realms") + (r.prophet().isEmpty() ? "" : " · prophet " + r.prophet()), INK_SOFT));
			}
			rows.add(GAP_ROW);
			rows.add(heading("Legends"));
			if (d.legends().isEmpty()) rows.add(text("None yet. Deeds and fortune make legends.", INK_SOFT));
			for (AtlasPayloads.LegendInfo l : d.legends()) {
				rows.add(text((l.alive() ? "✦ " : "† ") + l.name(), l.alive() ? INK : 0xFF8A7A60));
				rows.add(text("   " + l.kind() + " of the " + l.faction(), INK_SOFT));
			}
			return rows;
		}
		if (this.panel == Panel.DIPLOMACY) {
			rows.add(heading("Nations of the world"));
			for (FactionInfo f : d.factions()) {
				rows.add(key(f.name(), f.color()));
				String rel = f.mine() ? "your realm" : f.relation() <= -50 ? "at war" : f.relation() >= 50 ? "allied" : "relations " + f.relation();
				rows.add(text("   " + f.title() + " · " + f.people() + " people · " + rel, f.relation() <= -50 && !f.mine() ? 0xFFB03030 : INK_SOFT));
			}
			return rows;
		}
		if (this.panel == Panel.LAWS) {
			FactionInfo f = this.mine();
			rows.add(heading("Law book"));
			if (f == null) {
				rows.add(text("You belong to no realm.", INK_SOFT));
			} else if (d.ruler()) {
				rows.add(text("Toggle the laws of your realm below. ✔ is in force.", INK_SOFT));
			} else {
				for (Law l : Law.values()) rows.add(text((this.hasLaw(l) ? "✔ " : "✘ ") + l.label, this.hasLaw(l) ? INK : 0xFF8A7A60));
				rows.add(text((f.slavery() ? "✔ " : "✘ ") + "Captivity", f.slavery() ? 0xFF7A1F1F : 0xFF8A7A60));
				rows.add(GAP_ROW);
				rows.add(text("Only the ruler can change the laws.", INK_SOFT));
			}
			return rows;
		}
		if (this.panel == Panel.NONE && this.selectedManor != null) {
			AtlasPayloads.ManorInfo m = this.selectedManor;
			FactionInfo f = this.factionById.get(m.faction());
			rows.add(new Row(Row.TITLE, Text.literal(m.name()), 0, f == null ? 0x888888 : f.color()));
			rows.add(text(m.allegiance(), INK));
			rows.add(text("Estate of " + (m.lord().isEmpty() ? "no one (lying fallow)" : m.lord()) + ", feeding " + m.townName(), INK_SOFT));
			rows.add(text(m.hall() ? "The manor house stands at " + m.seatX() + ", " + m.seatZ() : "The manor house is still being raised", INK_SOFT));
			rows.add(GAP_ROW);
			rows.add(barRow("Favour", m.favour(), m.favour() >= 50 ? 0x3E9E4A : m.favour() >= 25 ? 0xC06A30 : 0xB03030));
			rows.add(text(m.laborers() + " laborers · " + m.harvested() + " crops brought in", INK));
			rows.add(GAP_ROW);
			rows.add(heading("Fields"));
			int[] r = m.fields();
			for (int i = 0; i + 3 < r.length; i += 4) {
				rows.add(key(cropName(m.crops().get(i / 4)) + "  " + (r[i + 2] - r[i] + 1) + "×" + (r[i + 3] - r[i + 1] + 1), cropColour(m.crops().get(i / 4))));
			}
			rows.add(GAP_ROW);
			rows.add(text("A content lord sends levies to the realm's wars; an angry one refuses new taxes, and many angry lords may rise against the crown.", INK_SOFT));
			return rows;
		}
		if (this.panel == Panel.REALM || (this.selected == null && this.selectedLand == null)) {
			FactionInfo f = this.mine();
			if (f == null) {
				rows.add(heading("No realm"));
				rows.add(text("Found one (/colony found) or join one (/colony join) to take part in its politics.", INK_SOFT));
				rows.add(GAP_ROW);
				rows.add(text("Click a town to see it. Drag to pan, scroll to zoom.", INK_SOFT));
				return rows;
			}
			rows.add(new Row(Row.TITLE, Text.literal(f.name()), 0, f.color()));
			rows.add(text(f.title() + " · " + f.government(), INK));
			if (!f.ruler().isEmpty()) rows.add(text("Ruler: " + f.ruler(), INK));
			rows.add(text(net.colonists.legend.Ages.ERAS[Math.max(0, Math.min(3, f.era()))] + " Era", INK_SOFT));
			AtlasPayloads.ReligionInfo faith = this.faith(f.religion());
			if (faith != null) rows.add(key(faith.name(), faith.color()));
			rows.add(text(d.ruler() ? "You rule this realm." : d.heir() ? "You are heir to the throne." : "You are a member.", INK_SOFT));
			rows.add(GAP_ROW);
			rows.add(heading("Standing"));
			rows.add(barRow("People", f.approval(), 0x3E9E4A));
			rows.add(barRow("Army", f.army(), 0x8A3B2A));
			rows.add(barRow("Court", f.court(), 0x6A4A9A));
			int lords = this.lordsFavour(f.id());
			if (lords >= 0) {
				rows.add(barRow("Lords", lords, 0xB8860B));
				rows.add(text(this.manorsOf(f.id()).size() + " landed lords" + (lords < 40 ? " · they refuse new taxes" : lords >= 50 ? " · they send levies to war" : ""), INK_SOFT));
			}
			rows.add(GAP_ROW);
			rows.add(heading("Holdings"));
			rows.add(text("Treasury: " + f.treasury() + " emeralds", INK));
			rows.add(text(f.people() + " people in " + d.towns().stream().filter(t -> t.faction().equals(f.id())).count() + " towns", INK));
			if (f.atWar()) rows.add(text("⚔ At war", 0xFFB03030));
			if (!f.overlord().isEmpty()) rows.add(text("Pays tribute to the " + this.nameOf(f.overlord()), INK));
			rows.add(GAP_ROW);
			rows.add(text("Click a town to see it; click open land to plan a colony. Drag to pan, scroll to zoom.", INK_SOFT));
			return rows;
		}
		if (this.selected != null) {
			TownInfo t = this.selected;
			FactionInfo f = this.factionById.get(t.faction());
			rows.add(new Row(Row.TITLE, Text.literal((t.capital() ? "★ " : "") + t.name()), 0, f == null ? 0x888888 : f.color()));
			rows.add(text(t.rank() + " of the " + (f == null ? "?" : f.name()), INK));
			AtlasPayloads.ReligionInfo faith = f == null ? null : this.faith(f.religion());
			if (faith != null) rows.add(key(faith.name(), faith.color()));
			if (t.besieged()) rows.add(text("⚑ Under siege!", 0xFFB03030));
			rows.add(GAP_ROW);
			rows.add(barRow("Loyalty", t.loyalty(), t.loyalty() >= 50 ? 0x3E9E4A : 0xB03030));
			rows.add(text("Population " + t.population() + " · guards " + t.guards(), INK));
			rows.add(text("Treasury " + t.treasury() + " emeralds", INK));
			if (t.taxRate() >= 0) rows.add(text("Taxes " + t.taxRate() + "%", INK));
			if (t.garrison() >= 0) rows.add(text("Garrison order: " + t.garrison(), INK));
			if (t.autonomy()) rows.add(text("Self-governing", INK_SOFT));
			if (!t.priority().isEmpty()) rows.add(text("Building first: " + t.priority().replace('_', ' '), INK));
			List<AtlasPayloads.ManorInfo> estates = new ArrayList<>();
			if (AtlasClient.data != null) for (AtlasPayloads.ManorInfo m : AtlasClient.data.manors()) if (m.town() == t.id()) estates.add(m);
			if (!estates.isEmpty()) {
				rows.add(GAP_ROW);
				rows.add(heading("Manors"));
				for (AtlasPayloads.ManorInfo m : estates) {
					rows.add(key(m.name(), m.favour() >= 50 ? 0x3E9E4A : m.favour() >= 25 ? 0xC06A30 : 0xB03030));
					rows.add(text("   " + (m.lord().isEmpty() ? "no lord" : m.lord()) + " · " + this.cropsSummary(m), INK_SOFT));
				}
			}
			rows.add(GAP_ROW);
			rows.add(text("At " + t.x() + ", " + t.z(), INK_SOFT));
			return rows;
		}
		rows.add(heading("Open land"));
		rows.add(text("At " + this.selectedLand[0] + ", " + this.selectedLand[1], INK_SOFT));
		if (d.ruler()) rows.add(text("Send settlers here to found a new town.", INK_SOFT));
		return rows;
	}

	private void drawSidebar(DrawContext ctx) {
		int x = this.mapRight() + 8, top = TOPBAR + 6, w = this.sidebarWidth() - 16;
		int bottom = Math.max(top + 20, this.actionsTop - 2);
		AtlasPayloads.Data d = AtlasClient.data;
		List<Row> rows = this.sidebarRows(d);
		// The nation's banner sits by the title.
		FactionInfo shown = this.selected != null && this.panel == Panel.NONE ? this.factionById.get(this.selected.faction()) : this.mine();
		net.minecraft.item.ItemStack banner = this.panel == Panel.NONE || this.panel == Panel.REALM ? this.bannerOf(shown) : null;
		if (this.panel == Panel.NONE && this.selectedManor != null) banner = this.bannerItem(this.selectedManor.banner(), this.selectedManor.name());
		if (banner != null && banner.isEmpty()) banner = null;
		ctx.enableScissor(this.mapRight() + 1, top - 2, this.width, bottom);
		int y = top - this.scroll;
		for (Row r : rows) {
			switch (r.kind()) {
				case Row.TITLE -> {
					int start = y, tx = x;
					if (banner != null) {
						ctx.drawItem(banner, x, y);
						tx += 20;
					}
					ctx.fill(tx, y + 2, tx + 8, y + 10, INK);
					ctx.fill(tx + 1, y + 3, tx + 7, y + 9, 0xFF000000 | r.colour());
					for (var line : this.textRenderer.wrapLines(r.text().copy().formatted(Formatting.BOLD), w - (tx - x) - 12)) {
						ctx.drawText(this.textRenderer, line, tx + 12, y + 3, INK, false);
						y += 10;
					}
					y = Math.max(y + 6, start + 20);
				}
				case Row.HEADING -> {
					y += 2;
					ctx.drawText(this.textRenderer, r.text().copy().formatted(Formatting.BOLD), x, y, RUBRIC, false);
					ctx.fill(x, y + 10, x + w, y + 11, 0x667A2E22);
					y += 14;
				}
				case Row.TEXT -> {
					for (var line : this.textRenderer.wrapLines(r.text(), w)) {
						ctx.drawText(this.textRenderer, line, x, y, r.colour() == 0 ? INK : r.colour(), false);
						y += 10;
					}
				}
				case Row.KEY -> {
					ctx.fill(x, y, x + 8, y + 8, INK);
					ctx.fill(x + 1, y + 1, x + 7, y + 7, 0xFF000000 | r.colour());
					for (var line : this.textRenderer.wrapLines(r.text(), w - 12)) {
						ctx.drawText(this.textRenderer, line, x + 12, y, INK, false);
						y += 10;
					}
				}
				case Row.BAR -> {
					int labelW = 44, barX = x + labelW, barW = w - labelW - 22;
					ctx.drawText(this.textRenderer, r.text(), x, y + 1, INK, false);
					ctx.fill(barX, y + 1, barX + barW, y + 8, INK);
					ctx.fill(barX + 1, y + 2, barX + barW - 1, y + 7, BAR_BG);
					int fill = (int) ((barW - 2) * MathHelper.clamp(r.value(), 0, 100) / 100.0);
					ctx.fill(barX + 1, y + 2, barX + 1 + fill, y + 7, 0xFF000000 | r.colour());
					String v = String.valueOf(r.value());
					ctx.drawText(this.textRenderer, v, x + w - this.textRenderer.getWidth(v), y + 1, INK_SOFT, false);
					y += 12;
				}
				default -> y += 6;
			}
		}
		ctx.disableScissor();
		this.contentHeight = y + this.scroll - top;
		int view = bottom - top;
		int maxScroll = Math.max(0, this.contentHeight - view);
		if (this.scroll > maxScroll) this.scroll = maxScroll;
		// A thin scroll indicator when the page runs long.
		if (maxScroll > 0) {
			int trackX = this.width - 4;
			ctx.fill(trackX, top, trackX + 2, bottom, 0x33000000);
			int thumb = Math.max(12, view * view / this.contentHeight);
			int ty = top + (int) ((view - thumb) * (this.scroll / (double) maxScroll));
			ctx.fill(trackX, ty, trackX + 2, ty + thumb, INK);
			if (this.scroll < maxScroll) ctx.drawText(this.textRenderer, "▾", this.width - 12, bottom - 9, INK_SOFT, false);
		}
		// The rule above the actions.
		if (this.actionsTop < this.height - 4) ctx.fill(this.mapRight() + 6, this.actionsTop + 1, this.width - 6, this.actionsTop + 2, 0x663B2A1A);
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
			AtlasPayloads.ManorInfo manor = t == null ? this.manorAt(mx, my) : null;
			this.panel = Panel.NONE;
			this.selectedManor = null;
			this.scroll = 0;
			if (t != null) {
				this.selected = t;
				this.selectedLand = null;
			} else if (manor != null) {
				this.selectedManor = manor;
				this.selected = null;
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
		if (mx >= this.mapRight() && my > TOPBAR) {
			this.scroll = Math.max(0, this.scroll - (int) (v * 20));
			return true;
		}
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
