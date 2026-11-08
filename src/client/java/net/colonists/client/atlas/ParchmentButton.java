package net.colonists.client.atlas;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** A button drawn as an inked parchment tab, to match the atlas (and never spill its label outside its box). */
public class ParchmentButton extends ButtonWidget {
	private final boolean chosen;

	public ParchmentButton(int x, int y, int w, int h, Text message, PressAction action, boolean chosen) {
		super(x, y, w, h, message, action, DEFAULT_NARRATION_SUPPLIER);
		this.chosen = chosen;
	}

	@Override
	protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
		int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
		int bg = this.chosen ? 0xFF7A2E22 : !this.active ? 0xFFD8CBA6 : this.isHovered() ? 0xFFD6C08C : 0xFFE6D7AC;
		ctx.fill(x, y, x + w, y + h, 0xFF3B2A1A);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
		if (!this.chosen) {
			ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0x55FFFFFF);
			ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x33000000);
		}
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		String label = this.getMessage().getString();
		if (tr.getWidth(label) > w - 6) label = tr.trimToWidth(label, w - 8) + "…";
		int col = this.chosen ? 0xFFF4E9C8 : this.active ? 0xFF3B2A1A : 0xFF8A7A60;
		ctx.drawText(tr, label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, col, false);
	}
}
