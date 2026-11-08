package net.siegeworks.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.siegeworks.entity.BallistaEntity;
import net.siegeworks.entity.BatteringRamEntity;
import net.siegeworks.entity.CartEntity;
import net.siegeworks.entity.CatapultEntity;
import net.siegeworks.entity.SiegeEngineEntity;
import net.siegeworks.entity.SiegeTowerEntity;

/**
 * Siege engines drawn as carpentry: every beam, wheel and panel is a vanilla block, scaled and turned into place, so the
 * engines wear the same wood, iron and wool as the world around them (and follow resource packs).
 */
public final class EngineRenderers {
	private EngineRenderers() {}

	static final BlockState LOG_Y = Blocks.OAK_LOG.getDefaultState();
	static final BlockState LOG_X = Blocks.OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X);
	static final BlockState LOG_Z = Blocks.OAK_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
	static final BlockState SPRUCE_LOG_Z = Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
	static final BlockState WHEEL = Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X);
	static final BlockState SPRUCE = Blocks.SPRUCE_PLANKS.getDefaultState();
	static final BlockState OAK = Blocks.OAK_PLANKS.getDefaultState();
	static final BlockState DARK = Blocks.DARK_OAK_PLANKS.getDefaultState();
	static final BlockState IRON = Blocks.IRON_BLOCK.getDefaultState();
	static final BlockState ROPE = Blocks.WHITE_WOOL.getDefaultState();
	static final BlockState HIDE = Blocks.BROWN_WOOL.getDefaultState();
	static final BlockState CHAIN = Blocks.CHAIN.getDefaultState();

	public abstract static class Base<T extends SiegeEngineEntity> extends EntityRenderer<T> {
		protected final BlockRenderManager blocks;
		protected MatrixStack m;
		protected VertexConsumerProvider v;
		protected int light;

		protected Base(EntityRendererFactory.Context ctx, float shadow) {
			super(ctx);
			this.blocks = ctx.getBlockRenderManager();
			this.shadowRadius = shadow;
		}

		@Override
		public void render(T e, float yaw, float td, MatrixStack matrices, VertexConsumerProvider vertices, int light) {
			this.m = matrices;
			this.v = vertices;
			this.light = light;
			matrices.push();
			float y = MathHelper.lerpAngleDegrees(td, e.prevYaw, e.getYaw());
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-y));
			int hurt = e.hurtTime();
			if (hurt > 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(hurt * 1.7f) * hurt * 0.35f));
			draw(e, td);
			matrices.pop();
			super.render(e, yaw, td, matrices, vertices, light);
		}

		protected abstract void draw(T e, float td);

		@Override
		public Identifier getTexture(T e) {
			return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
		}

		protected float anim(T e, float td) {
			return MathHelper.lerp(td, e.prevAnim, e.anim);
		}

		/** A block stretched into a box from (x, y, z) of size (sx, sy, sz). */
		protected void box(BlockState s, double x, double y, double z, double sx, double sy, double sz) {
			m.push();
			m.translate(x, y, z);
			m.scale((float) sx, (float) sy, (float) sz);
			blocks.renderBlockAsEntity(s, m, v, light, OverlayTexture.DEFAULT_UV);
			m.pop();
		}

		/** A beam of thickness t from a to b. */
		protected void bar(BlockState s, Vec3d a, Vec3d b, double t) {
			Vec3d d = b.subtract(a);
			double len = d.length();
			if (len < 1e-4) return;
			m.push();
			m.translate(a.x, a.y, a.z);
			m.multiply(RotationAxis.POSITIVE_Y.rotation((float) Math.atan2(d.x, d.z)));
			m.multiply(RotationAxis.POSITIVE_X.rotation((float) -Math.asin(d.y / len)));
			box(s, -t / 2, -t / 2, 0, t, t, len);
			m.pop();
		}

		/** A wheel of radius r centred at (x, y, z), turned by how far the engine has rolled. */
		protected void wheel(T e, float td, double x, double y, double z, double r, double thick) {
			float rolled = MathHelper.lerp(td, e.prevRolled, e.rolled);
			m.push();
			m.translate(x, y, z);
			m.multiply(RotationAxis.POSITIVE_X.rotation((float) (rolled / r)));
			box(WHEEL, -thick / 2, -r, -r, thick, 2 * r, 2 * r);
			box(IRON, -thick / 2 - 0.02, -0.08, -0.08, thick + 0.04, 0.16, 0.16);
			m.pop();
		}
	}

	// ---------------------------------------------------------------- ballista

	public static class Ballista extends Base<BallistaEntity> {
		public Ballista(EntityRendererFactory.Context ctx) {
			super(ctx, 0.8f);
		}

		@Override
		protected void draw(BallistaEntity e, float td) {
			for (double x : new double[]{-0.62, 0.62}) for (double z : new double[]{-0.55, 0.55}) wheel(e, td, x, 0.25, z, 0.25, 0.12);
			box(LOG_X, -0.62, 0.2, 0.5, 1.24, 0.1, 0.1);
			box(LOG_X, -0.62, 0.2, -0.6, 1.24, 0.1, 0.1);
			box(SPRUCE, -0.55, 0.22, -0.85, 1.1, 0.15, 1.7);
			box(LOG_Y, -0.12, 0.37, -0.12, 0.24, 0.5, 0.24);
			float pull = anim(e, td);
			m.push();
			m.translate(0, 0.9, 0);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(e.aim()));
			box(DARK, -0.1, -0.07, -0.95, 0.2, 0.14, 1.75);
			box(IRON, -0.32, -0.12, 0.42, 0.64, 0.24, 0.2);
			box(LOG_X, -0.25, -0.15, -0.9, 0.5, 0.1, 0.1); // the winch
			float bend = (float) Math.toRadians(8 + 24 * pull);
			for (int side : new int[]{-1, 1}) {
				m.push();
				m.translate(0.3 * side, 0, 0.5);
				m.multiply(RotationAxis.POSITIVE_Y.rotation(bend * side));
				box(LOG_X, side < 0 ? -0.75 : 0, -0.05, -0.05, 0.75, 0.1, 0.1);
				m.pop();
			}
			double tipX = 0.3 + 0.75 * Math.cos(bend), tipZ = 0.5 - 0.75 * Math.sin(bend);
			double nockZ = tipZ - 0.85 * pull;
			Vec3d nock = new Vec3d(0, 0.06, nockZ);
			bar(ROPE, new Vec3d(-tipX, 0, tipZ), nock, 0.035);
			bar(ROPE, new Vec3d(tipX, 0, tipZ), nock, 0.035);
			if (e.ammo() == SiegeEngineEntity.AMMO_BOLT) {
				box(OAK, -0.035, 0.07, nockZ, 0.07, 0.07, 1.25);
				box(IRON, -0.06, 0.045, nockZ + 1.25, 0.12, 0.12, 0.18);
			}
			m.pop();
		}
	}

	// ---------------------------------------------------------------- catapult

	public static class Catapult extends Base<CatapultEntity> {
		public Catapult(EntityRendererFactory.Context ctx) {
			super(ctx, 1.2f);
		}

		@Override
		protected void draw(CatapultEntity e, float td) {
			for (double x : new double[]{-1.07, 1.07}) for (double z : new double[]{-0.85, 0.85}) wheel(e, td, x, 0.4, z, 0.4, 0.15);
			box(LOG_Z, -0.95, 0.32, -1.3, 0.25, 0.25, 2.6);
			box(LOG_Z, 0.7, 0.32, -1.3, 0.25, 0.25, 2.6);
			for (double z : new double[]{-1.25, -0.5, 0.95}) box(SPRUCE, -0.7, 0.36, z, 1.4, 0.15, 0.25);
			for (double x : new double[]{-0.95, 0.7}) {
				box(LOG_Y, x, 0.57, 0.25, 0.25, 1.55, 0.25);
				bar(SPRUCE, new Vec3d(x + 0.125, 0.57, -0.75), new Vec3d(x + 0.125, 1.9, 0.3), 0.14);
			}
			box(LOG_X, -0.95, 2.1, 0.25, 1.9, 0.25, 0.25);
			box(Blocks.HAY_BLOCK.getDefaultState(), -0.4, 2.0, 0.15, 0.8, 0.35, 0.12);
			box(HIDE, -0.7, 0.6, -0.5, 1.4, 0.3, 0.3); // the twisted sinew skein
			box(LOG_X, -0.7, 0.55, -1.25, 1.4, 0.22, 0.22); // windlass
			box(OAK, -0.82, 0.42, -1.2, 0.08, 0.45, 0.08);
			box(OAK, 0.74, 0.42, -1.2, 0.08, 0.45, 0.08);
			float angle = anim(e, td);
			double a = Math.toRadians(angle);
			if (e.tension() > 0.02f) {
				Vec3d mid = new Vec3d(0, 0.75 + 1.2 * Math.cos(a), -0.35 + 1.2 * Math.sin(a));
				bar(ROPE, new Vec3d(0, 0.66, -1.14), mid, 0.05);
			}
			m.push();
			m.translate(0, 0.75, -0.35);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
			box(LOG_Y, -0.12, -0.1, -0.12, 0.24, 2.3, 0.24);
			box(SPRUCE, -0.32, 2.15, -0.32, 0.64, 0.18, 0.64);
			box(SPRUCE, -0.32, 2.33, -0.32, 0.64, 0.12, 0.08);
			box(SPRUCE, -0.32, 2.33, 0.24, 0.64, 0.12, 0.08);
			BlockState shot = switch (e.ammo()) {
				case SiegeEngineEntity.AMMO_STONE -> Blocks.STONE.getDefaultState();
				case SiegeEngineEntity.AMMO_RUBBLE -> Blocks.COBBLESTONE.getDefaultState();
				case SiegeEngineEntity.AMMO_NAPHTHA -> Blocks.TERRACOTTA.getDefaultState();
				default -> null;
			};
			if (shot != null) box(shot, -0.2, 2.33, -0.2, 0.4, 0.4, 0.4);
			m.pop();
		}
	}

	// ---------------------------------------------------------------- battering ram

	public static class Ram extends Base<BatteringRamEntity> {
		public Ram(EntityRendererFactory.Context ctx) {
			super(ctx, 1.1f);
		}

		@Override
		protected void draw(BatteringRamEntity e, float td) {
			for (double x : new double[]{-0.95, 0.95}) for (double z : new double[]{-1.0, 1.0}) wheel(e, td, x, 0.35, z, 0.35, 0.14);
			box(SPRUCE_LOG_Z, -0.9, 0.3, -1.45, 0.22, 0.22, 2.9);
			box(SPRUCE_LOG_Z, 0.68, 0.3, -1.45, 0.22, 0.22, 2.9);
			for (double x : new double[]{-0.9, 0.68}) for (double z : new double[]{-1.45, 1.23}) box(LOG_Y, x, 0.52, z, 0.22, 1.6, 0.22);
			box(LOG_Z, -0.9, 2.1, -1.45, 0.22, 0.22, 2.9);
			box(LOG_Z, 0.68, 2.1, -1.45, 0.22, 0.22, 2.9);
			box(LOG_Z, -0.11, 2.62, -1.5, 0.22, 0.22, 3.0);
			box(LOG_X, -0.9, 2.1, -0.95, 1.8, 0.2, 0.2);
			box(LOG_X, -0.9, 2.1, 0.75, 1.8, 0.2, 0.2);
			for (int side : new int[]{-1, 1}) {
				m.push();
				m.translate(0, 2.7, -1.55);
				m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-27f * side));
				box(HIDE, side < 0 ? -1.2 : 0, -0.06, 0, 1.2, 0.08, 3.1);
				m.pop();
			}
			double dz = anim(e, td);
			box(LOG_Z, -0.22, 0.85, -1.6 + dz, 0.44, 0.44, 3.25);
			box(IRON, -0.3, 0.77, 1.65 + dz, 0.6, 0.6, 0.35);
			box(CHAIN, -0.05, 1.29, -0.9 + dz * 0.5, 0.1, 0.85, 0.1);
			box(CHAIN, -0.05, 1.29, 0.75 + dz * 0.5, 0.1, 0.85, 0.1);
		}
	}

	// ---------------------------------------------------------------- siege tower

	public static class Tower extends Base<SiegeTowerEntity> {
		public Tower(EntityRendererFactory.Context ctx) {
			super(ctx, 1.6f);
		}

		@Override
		protected void draw(SiegeTowerEntity e, float td) {
			for (double x : new double[]{-1.5, 1.5}) for (double z : new double[]{-0.9, 0.9}) wheel(e, td, x, 0.5, z, 0.5, 0.18);
			box(SPRUCE, -1.4, 0.4, -1.4, 2.8, 0.2, 2.8);
			for (double x : new double[]{-1.4, 1.15}) for (double z : new double[]{-1.4, 1.15}) box(LOG_Y, x, 0.6, z, 0.25, 7.25, 0.25);
			box(SPRUCE, -1.4, 0.6, -1.15, 0.1, 7.0, 2.3);
			box(SPRUCE, 1.3, 0.6, -1.15, 0.1, 7.0, 2.3);
			box(SPRUCE, -1.15, 2.2, -1.4, 2.3, 5.4, 0.1);
			box(SPRUCE, -1.15, 0.6, 1.3, 2.3, 4.9, 0.1);
			box(HIDE, -1.2, 0.6, 1.4, 2.4, 4.9, 0.06);
			box(OAK, -1.4, 2.9, -1.4, 2.8, 0.12, 2.8);
			box(OAK, -1.4, 5.5, -1.4, 2.8, 0.15, 2.8);
			for (double x : new double[]{-1.4, 0.9}) for (double z : new double[]{-1.4, 0.9}) box(SPRUCE, x, 7.6, z, 0.5, 0.45, 0.5);
			float angle = anim(e, td);
			double a = Math.toRadians(angle);
			m.push();
			m.translate(0, 5.5, 1.35);
			m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
			box(OAK, -1.15, 0, -0.06, 2.3, SiegeTowerEntity.BRIDGE, 0.12);
			box(SPRUCE, -1.15, 0.3, 0.06, 2.3, 0.1, 0.06);
			box(SPRUCE, -1.15, 1.5, 0.06, 2.3, 0.1, 0.06);
			m.pop();
			Vec3d tip = new Vec3d(0, 5.5 + SiegeTowerEntity.BRIDGE * Math.cos(a), 1.35 + SiegeTowerEntity.BRIDGE * Math.sin(a));
			for (int side : new int[]{-1, 1}) bar(CHAIN, new Vec3d(1.12 * side, 7.7, 1.25), tip.add(1.05 * side, 0, 0), 0.06);
		}
	}

	// ---------------------------------------------------------------- cart

	public static class Cart extends Base<CartEntity> {
		public Cart(EntityRendererFactory.Context ctx) {
			super(ctx, 0.8f);
		}

		@Override
		protected void draw(CartEntity e, float td) {
			wheel(e, td, -0.72, 0.55, -0.1, 0.55, 0.12);
			wheel(e, td, 0.72, 0.55, -0.1, 0.55, 0.12);
			box(LOG_X, -0.72, 0.5, -0.15, 1.44, 0.1, 0.1);
			box(OAK, -0.6, 0.55, -0.95, 1.2, 0.1, 1.9);
			box(SPRUCE, -0.62, 0.65, -0.95, 0.08, 0.4, 1.9);
			box(SPRUCE, 0.54, 0.65, -0.95, 0.08, 0.4, 1.9);
			box(SPRUCE, -0.62, 0.65, -0.97, 1.24, 0.4, 0.08);
			box(SPRUCE, -0.62, 0.65, 0.89, 1.24, 0.4, 0.08);
			bar(LOG_Z, new Vec3d(-0.45, 0.62, 0.9), new Vec3d(-0.45, 0.45, 2.3), 0.08);
			bar(LOG_Z, new Vec3d(0.45, 0.62, 0.9), new Vec3d(0.45, 0.45, 2.3), 0.08);
			if (e.flag()) {
				box(Blocks.BARREL.getDefaultState(), -0.4, 0.65, -0.85, 0.5, 0.55, 0.5);
				box(Blocks.HAY_BLOCK.getDefaultState(), 0.05, 0.65, -0.35, 0.45, 0.4, 0.5);
			}
		}
	}
}
