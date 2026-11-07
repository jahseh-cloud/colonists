package net.colonists.client;

import net.colonists.entity.CaptiveEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.VillagerClothingFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.util.Identifier;

/** Captives look exactly like the villagers they were, profession outfit and all. */
public class CaptiveRenderer extends MobEntityRenderer<CaptiveEntity, VillagerResemblingModel<CaptiveEntity>> {
	private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/villager/villager.png");

	public CaptiveRenderer(EntityRendererFactory.Context context) {
		super(context, new VillagerResemblingModel<>(context.getPart(EntityModelLayers.VILLAGER)), 0.5f);
		this.addFeature(new VillagerClothingFeatureRenderer<>(this, context.getResourceManager(), "villager"));
	}

	@Override
	public Identifier getTexture(CaptiveEntity entity) {
		return TEXTURE;
	}
}
