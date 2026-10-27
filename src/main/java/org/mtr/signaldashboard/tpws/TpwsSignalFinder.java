package org.mtr.signaldashboard.tpws;

import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.World;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.signaldashboard.data.TpwsCoilConfig;

/**
 * 「自动检测附近信号机」的实现。
 *
 * <p>在方块坐标上做一次立方体扫描，找最近的 MTR 信号机方块实体，
 * 并把它的信号颜色一并读出来 —— 颜色很关键：一条轨道上可能挂着多个闭塞的
 * 多种颜色，只有绑定到<b>这一个</b>信号机的颜色，刹车判定才不会误伤。
 *
 * <p>只跑在服务端主线程（由 {@code PacketTpwsConfig.runServer} 调用），
 * 所以可以直接读方块实体，不用担心线程问题。
 *
 * @author Mewzica
 */
public final class TpwsSignalFinder {

	/** 扫描半径（方块）。信号机一般就在线圈旁边几格内，8 格足够宽松。 */
	public static final int SEARCH_RADIUS = 8;

	private TpwsSignalFinder() {
	}

	/**
	 * 找到的最近信号机。
	 *
	 * @param blockPos 信号机方块坐标（{@link BlockPos#asLong()} 编码）
	 * @param color    信号颜色（24 位 RGB），读不到时是 {@link TpwsCoilConfig#NO_COLOR}
	 */
	public record Found(long blockPos, int color) {
	}

	/**
	 * 在 {@code center} 周围搜索最近的 MTR 信号机。
	 *
	 * @return 找到的信号机；半径内没有则返回 {@code null}
	 */
	public static Found findNearest(World world, BlockPos center) {
		Found best = null;
		int bestDistanceSquared = Integer.MAX_VALUE;

		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					final int distanceSquared = dx * dx + dy * dy + dz * dz;
					if (distanceSquared >= bestDistanceSquared) {
						continue;
					}

					final long key = BlockPos.asLong(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					final BlockEntity blockEntity = world.getBlockEntity(BlockPos.fromLong(key));
					if (blockEntity == null || !(blockEntity.data instanceof BlockSignalBase.BlockEntityBase signalEntity)) {
						continue;
					}

					bestDistanceSquared = distanceSquared;
					best = new Found(key, firstColor(signalEntity.getSignalColors(false)));
				}
			}
		}

		return best;
	}

	/** 取信号机的第一个颜色；没有颜色（未连接信号）时返回 {@link TpwsCoilConfig#NO_COLOR}。 */
	private static int firstColor(IntAVLTreeSet colors) {
		return colors == null || colors.isEmpty() ? TpwsCoilConfig.NO_COLOR : colors.firstInt();
	}
}
