package net.colonists.client;

import net.colonists.entity.ColonistEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/** Renders colonists with the player model and the nine default player skins. */
public class ColonistRenderer extends BipedEntityRenderer<ColonistEntity, PlayerEntityModel<ColonistEntity>> {
	private static final Identifier[] SKINS = new Identifier[ColonistEntity.SKIN_COUNT];

	static {
		String[] names = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
		for (int i = 0; i < names.length; i++) {
			SKINS[i] = Identifier.ofVanilla("textures/entity/player/wide/" + names[i] + ".png");
		}
	}

	public ColonistRenderer(EntityRendererFactory.Context context) {
		super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		this.addFeature(new ArmorFeatureRenderer<>(this,
				new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
				new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
				context.getModelManager()));
	}

	@Override
	public Identifier getTexture(ColonistEntity entity) {
		return SKINS[Math.floorMod(entity.getSkin(), SKINS.length)];
	}
}
