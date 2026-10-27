package org.mtr.signaldashboard.data;

import org.mtr.mapping.holder.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全服 TPWS 线圈索引：<b>维度 → 方块坐标 → 配置</b>。
 *
 * <h2>为什么需要这么一个全局表</h2>
 * 刹车判定发生在 MTR 的<b>模拟线程</b>里（{@code Vehicle.simulate}），
 * 那个线程手上只有 {@code org.mtr.core.data.Vehicle}，拿不到任何 {@code World}，
 * 更没法去读方块实体。所以线圈配置必须提前抽出来放在一个「不依赖世界对象」的表里，
 * 模拟线程直接查表。
 *
 * <p>反过来说，方块实体只在<b>服务端主线程</b>上被创建/读取，由它负责往这张表里登记。
 *
 * <h2>为什么不用「区块加载时登记、卸载时注销」</h2>
 * 因为「卸载时注销」做不到可靠：MTR 的映射层没有暴露方块实体的卸载回调，
 * 而且真注销了反而更糟 —— 列车是在<b>未加载的区块里照样跑</b>的，
 * 线圈一旦被注销，那一段就彻底失去防护。
 *
 * <p>所以这里采取<b>只增不减</b>的策略：登记之后一直留着，只有方块真的被挖掉
 * （{@code BlockTpwsGrid.onStateReplaced2}）才移除。代价是方块被
 * {@code /setblock}、爆炸等非正常途径移除时可能留下一条僵尸记录，
 * 后果仅仅是「那里多刹一次车」，不会崩也不会刷屏。
 *
 * <h2>维度键</h2>
 * 两边的维度字符串来源不同：
 * <ul>
 *     <li>线圈侧 —— {@code Init.getWorldId(World)}，格式是 {@code namespace/path}；</li>
 *     <li>列车侧 —— {@code Simulator.dimension}，理论上同一套，但不保证。</li>
 * </ul>
 * 所以统一走 {@link #canonicalDimension(String)} 归一化：把 {@code namespace:path}
 * 和 {@code namespace/path} 都压成纯 {@code path}。这样即使 MTR 哪天换了分隔符也不会失配。
 *
 * @author Mewzica
 */
public final class TpwsCoilRegistry {

	/** 维度 → （方块坐标 → 配置）。外层与内层都是并发容器，见类注释的线程说明。 */
	private static final Map<String, Map<Long, TpwsCoilConfig>> BY_DIMENSION = new ConcurrentHashMap<>();

	private TpwsCoilRegistry() {
	}

	/**
	 * 维度标识归一化：只保留最后一段。
	 *
	 * <p>{@code minecraft:overworld} / {@code minecraft/overworld} / {@code overworld}
	 * 全部归到 {@code overworld}。
	 */
	public static String canonicalDimension(String dimension) {
		if (dimension == null || dimension.isEmpty()) {
			return "";
		}
		final int index = Math.max(dimension.lastIndexOf(':'), dimension.lastIndexOf('/'));
		return index >= 0 ? dimension.substring(index + 1) : dimension;
	}

	/** 登记（或覆盖）一枚线圈。重复调用是幂等的。 */
	public static void put(String dimension, long blockPos, TpwsCoilConfig config) {
		BY_DIMENSION.computeIfAbsent(canonicalDimension(dimension), key -> new ConcurrentHashMap<>()).put(blockPos, config);
	}

	/** 注销一枚线圈。 */
	public static void remove(String dimension, long blockPos) {
		final Map<Long, TpwsCoilConfig> coils = BY_DIMENSION.get(canonicalDimension(dimension));
		if (coils != null) {
			coils.remove(blockPos);
		}
	}

	/** 该维度登记了多少枚线圈（调试用）。 */
	public static int size(String dimension) {
		final Map<Long, TpwsCoilConfig> coils = BY_DIMENSION.get(canonicalDimension(dimension));
		return coils == null ? 0 : coils.size();
	}

	/**
	 * 找出离给定世界坐标最近的线圈。
	 *
	 * <p>比较的是<b>方块中心</b>（{@code +0.5}）到列车头部的距离，半径用平方值比较以省一次开方。
	 *
	 * <p>性能：整个维度只有一张平表，线圈数量通常是个位数到几十。
	 * 没线圈时（绝大多数维度）直接空转返回，所以常驻开销约等于零。
	 *
	 * @param dimension 列车所在维度（会先归一化）
	 * @param x         列车头部世界 X
	 * @param y         列车头部世界 Y
	 * @param z         列车头部世界 Z
	 * @param radius    触发半径（方块）。列车每 tick 只走几厘米，2~3 格足够。
	 * @return 最近的线圈；半径内没有线圈时返回 {@code null}
	 */
	public static Coil findNear(String dimension, double x, double y, double z, double radius) {
		final Map<Long, TpwsCoilConfig> coils = BY_DIMENSION.get(canonicalDimension(dimension));
		if (coils == null || coils.isEmpty()) {
			return null;
		}

		final double radiusSquared = radius * radius;
		double bestDistanceSquared = radiusSquared;
		long bestKey = 0L;
		TpwsCoilConfig bestConfig = null;

		for (final Map.Entry<Long, TpwsCoilConfig> entry : coils.entrySet()) {
			final BlockPos pos = BlockPos.fromLong(entry.getKey());
			final double dx = pos.getX() + 0.5D - x;
			final double dy = pos.getY() + 0.5D - y;
			final double dz = pos.getZ() + 0.5D - z;
			final double distanceSquared = dx * dx + dy * dy + dz * dz;
			if (distanceSquared <= bestDistanceSquared) {
				bestDistanceSquared = distanceSquared;
				bestKey = entry.getKey();
				bestConfig = entry.getValue();
			}
		}

		return bestConfig == null ? null : new Coil(bestKey, bestConfig);
	}

	/**
	 * 一次查询的结果：坐标 + 配置。
	 *
	 * <p>坐标单独带出来是必要的 —— 上层要靠它识别「列车是刚压上线圈，还是已经在上面了」，
	 * 这个「进入 / 离开」的边沿判定决定了超速模式只拦一次而不是永久封锁。
	 */
	public record Coil(long blockPos, TpwsCoilConfig config) {
	}
}
