package net.colonists.faction;

import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.BannerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Every nation flies its own banner: a random field and two to four charges, raised on its plazas and worn by its soldiers. */
public final class Banners {
	private Banners() {}

	private static final String[] PATTERNS = {"stripe_bottom", "stripe_top", "stripe_left", "stripe_right", "stripe_center", "stripe_middle",
			"stripe_downright", "stripe_downleft", "small_stripes", "cross", "straight_cross", "diagonal_left", "diagonal_right",
			"diagonal_up_left", "diagonal_up_right", "half_vertical", "half_vertical_right", "half_horizontal", "half_horizontal_bottom",
			"square_bottom_left", "square_bottom_right", "square_top_left", "square_top_right", "triangle_bottom", "triangle_top",
			"triangles_bottom", "triangles_top", "circle", "rhombus", "border", "curly_border", "gradient", "gradient_up", "bricks"};

	/** "base|pattern:color;pattern:color" */
	public static String random(Random r) {
		DyeColor[] colors = DyeColor.values();
		DyeColor base = colors[r.nextInt(colors.length)];
		StringBuilder sb = new StringBuilder(base.getName()).append('|');
		int n = 2 + r.nextInt(3);
		DyeColor last = base;
		for (int i = 0; i < n; i++) {
			DyeColor c;
			do {
				c = colors[r.nextInt(colors.length)];
			} while (c == last);
			last = c;
			if (i > 0) sb.append(';');
			sb.append(PATTERNS[r.nextInt(PATTERNS.length)]).append(':').append(c.getName());
		}
		return sb.toString();
	}

	public static DyeColor base(String banner) {
		String b = banner.contains("|") ? banner.substring(0, banner.indexOf('|')) : banner;
		return DyeColor.byName(b, DyeColor.WHITE);
	}

	public static BannerPatternsComponent patterns(DynamicRegistryManager reg, String banner) {
		BannerPatternsComponent.Builder b = new BannerPatternsComponent.Builder();
		if (!banner.contains("|")) return b.build();
		Registry<BannerPattern> registry = reg.get(RegistryKeys.BANNER_PATTERN);
		for (String layer : banner.substring(banner.indexOf('|') + 1).split(";")) {
			String[] p = layer.split(":");
			if (p.length != 2) continue;
			var entry = registry.getEntry(RegistryKey.of(RegistryKeys.BANNER_PATTERN, Identifier.ofVanilla(p[0])));
			if (entry.isPresent()) b.add(entry.get(), DyeColor.byName(p[1], DyeColor.BLACK));
		}
		return b.build();
	}

	public static ItemStack item(DynamicRegistryManager reg, Faction f) {
		return item(reg, f.banner, f.name);
	}

	public static ItemStack item(DynamicRegistryManager reg, String banner, String nation) {
		ItemStack stack = new ItemStack(BannerBlock.getForColor(base(banner)));
		stack.set(DataComponentTypes.BANNER_PATTERNS, patterns(reg, banner));
		stack.set(DataComponentTypes.ITEM_NAME, Text.literal("Banner of the " + nation));
		return stack;
	}

	public static boolean isBanner(ItemStack s) {
		return s.isIn(ItemTags.BANNERS);
	}

	/** Raises (or replaces) the realm's banner on a settlement's plaza, or beside a village's bell. */
	public static void raise(ServerWorld world, Settlement s) {
		if (s.faction == null || s.cityCenter == null || s.faction.banner.isEmpty()) return;
		BlockPos c = s.cityCenter;
		if (!world.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) return;
		BlockPos[] spots = s.village
				? new BlockPos[]{c.east(2), c.west(2), c.north(2), c.south(2)}
				: new BlockPos[]{c.add(-City.PLAZA_HALF, 0, -City.PLAZA_HALF), c.add(City.PLAZA_HALF, 0, -City.PLAZA_HALF),
				c.add(-City.PLAZA_HALF, 0, City.PLAZA_HALF + 1), c.add(City.PLAZA_HALF, 0, City.PLAZA_HALF + 1)};
		BlockPos at = null;
		for (BlockPos p : spots) {
			for (int dy = -2; dy <= 2 && at == null; dy++) {
				if (world.getBlockState(p.up(dy)).getBlock() instanceof AbstractBannerBlock) at = p.up(dy);
			}
			if (at != null) break;
		}
		DyeColor base = base(s.faction.banner);
		BannerPatternsComponent patterns = patterns(world.getRegistryManager(), s.faction.banner);
		if (at != null && world.getBlockEntity(at) instanceof BannerBlockEntity be
				&& be.getColorForState() == base && Objects.equals(be.getPatterns(), patterns)) return; // already flying
		if (at == null) {
			for (BlockPos p : spots) {
				BlockPos top = p.withY(City.surfaceYOr(world, p.getX(), p.getZ(), c.getY()));
				if (world.getBlockState(top).isAir() && world.getBlockState(top.down()).isSolidBlock(world, top.down())) {
					at = top;
					break;
				}
			}
		}
		if (at == null) return;
		BlockState state = BannerBlock.getForColor(base).getDefaultState().with(Properties.ROTATION, world.getRandom().nextInt(16));
		world.setBlockState(at, state);
		if (world.getBlockEntity(at) instanceof BannerBlockEntity be) {
			ItemStack stack = item(world.getRegistryManager(), s.faction);
			be.readFrom(stack, base);
			be.markDirty();
			world.updateListeners(at, state, state, 3);
		}
	}

	@Nullable
	public static String of(@Nullable Faction f) {
		return f == null ? null : f.banner;
	}
}
