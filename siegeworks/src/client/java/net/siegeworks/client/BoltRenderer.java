package net.siegeworks.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ProjectileEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.siegeworks.entity.BallistaBoltEntity;

/** A ballista bolt: an arrow, twice as long and thick. */
public class BoltRenderer extends ProjectileEntityRenderer<BallistaBoltEntity> {
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/projectiles/arrow.png");

	public BoltRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	@Override
	public void render(BallistaBoltEntity e, float yaw, float tickDelta, MatrixStack m, VertexConsumerProvider v, int light) {
		m.push();
		m.scale(2f, 2f, 2f);
		super.render(e, yaw, tickDelta, m, v, light);
		m.pop();
	}

	@Override
	public Identifier getTexture(BallistaBoltEntity e) {
		return TEXTURE;
	}
}
