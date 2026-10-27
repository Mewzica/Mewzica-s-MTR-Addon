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
	 * <p>挑选规则是<b>先看有没有颜色，再看距离</b>：MTR 的信号机只有在自己的
	 * 配置界面里选过颜色之后才会亮，没颜色的信号机 {@code signalColors} 是空集，
	 * 灯永远不亮、TPWS 也永远判不出红灯。如果只按距离挑，旁边正好有一台没配置的
	 * 信号机就会把真正能用的那台挤掉，玩家看到的现象就是「连上了但灯不亮」。
	 *
	 * @return 找到的信号机；半径内没有则返回 {@code null}
	 */
	public static Found findNearest(World world, BlockPos center) {
		Found best = null;
		int bestDistanceSquared = Integer.MAX_VALUE;
		boolean bestHasColor = false;

		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					final int distanceSquared = dx * dx + dy * dy + dz * dz;

					// 已经有带颜色的候选时，无颜色的直接跳过；同类之间才比距离
					final boolean canBeatByDistance = distanceSquared < bestDistanceSquared;
					if (!canBeatByDistance) {
						continue;
					}

					final long key = BlockPos.asLong(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					final BlockEntity blockEntity = world.getBlockEntity(BlockPos.fromLong(key));
					if (blockEntity == null || !(blockEntity.data instanceof BlockSignalBase.BlockEntityBase signalEntity)) {
						continue;
					}

					final int color = readColor(signalEntity);
					final boolean hasColor = color != TpwsCoilConfig.NO_COLOR;
					if (bestHasColor && !hasColor) {
						continue;
					}

					bestDistanceSquared = distanceSquared;
					bestHasColor = hasColor;
					best = new Found(key, color);
				}
			}
		}

		return best;
	}

	/**
	 * 读信号机的颜色，正反两面都看。
	 *
	 * <p>双面信号机的前后两面可以配不同的颜色（{@code setData} 的最后一个参数就是
	 * {@code isBackSide}），只读正面会漏掉配在背面的那一半。
	 */
	private static int readColor(BlockSignalBase.BlockEntityBase signalEntity) {
		final int front = firstColor(signalEntity.getSignalColors(false));
		return front != TpwsCoilConfig.NO_COLOR ? front : firstColor(signalEntity.getSignalColors(true));
	}

	/** 取信号机的第一个颜色；没有颜色（未连接信号）时返回 {@link TpwsCoilConfig#NO_COLOR}。 */
	private static int firstColor(IntAVLTreeSet colors) {
		return colors == null || colors.isEmpty() ? TpwsCoilConfig.NO_COLOR : colors.firstInt();
	}
}
