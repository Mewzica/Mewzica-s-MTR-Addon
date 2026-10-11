package org.mtr.signaldashboard.data;

import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.mapping.holder.BlockPos;

/**
 * 一条轨道在网络上传输时的轻量快照。
 *
 * <p>服务端持有真正的 {@link Rail} 对象，但整个 {@code Rail} 反序列化开销较大，
 * 客户端也只需要"画出走向 + 显示信号颜色"这几个字段，所以这里做一次投影。
 *
 * <h2>真实路径是怎么来的</h2>
 * {@code Rail.getPosition1()/getPosition2()} 是 {@code protected} 的，外部包调不到，
 * 但这不影响我们：{@link Rail#railMath} 是 <b>public</b> 字段，而
 * {@link RailMath#getPosition(double, boolean)} 也是 public 的。
 *
 * <p>实测（tools/probe/RailMathProbe.java）确认：
 * <ul>
 *     <li>第一个参数是<b>沿轨道的弧长</b>（单位：方块），会被内部 clamp 到
 *         {@code [0, getLength()]}</li>
 *     <li>返回值是<b>绝对世界坐标</b>，且已包含 {@code +0.5} 的方块中心偏移</li>
 *     <li>{@code getPosition(0, false)} 恰好等于
 *         {@code (position1.x + 0.5, position1.y, position1.z + 0.5)}</li>
 *     <li>{@code getPosition(getLength(), false)} 等于 position2 的对应点</li>
 * </ul>
 *
 * <p>所以：沿弧长等距采样就能得到轨道<b>真实的曲线走向</b>（含弯道），
 * 而把首尾采样点向下取整就能还原出<b>精确的端点方块坐标</b>——
 * 这正是 {@code SignalModification} 需要的输入，也是让共享端点的两条轨道
 * 在画面上真正"接上"的关键。
 *
 * @author Mewzica
 */
public final class SignalRailData {

	/** 单条轨道最多采样多少个线段（防止超长轨道把包撑爆）。 */
	private static final int MAX_SEGMENTS = 96;

	/** 起点方块坐标（BlockPos.asLong 编码，精确值，用于提交信号修改）。 */
	public final long pos1;
	/** 终点方块坐标（BlockPos.asLong 编码，精确值）。 */
	public final long pos2;

	/**
	 * 真实路径折线，扁平存放：{@code [x0,y0,z0, x1,y1,z1, ...]}。
	 *
	 * <p>坐标是绝对世界坐标（已含 +0.5 偏移），与 {@code RailMath.getPosition} 一致。
	 * 长度至少为 6（两个点）。
	 */
	public final float[] path;

	/** 轨道总长度（方块）。 */
	public final double length;

	/** 该轨道上所有信号灯的颜色集合（RGB）。 */
	public final int[] signalColors;

	/** 当前处于"被占用"状态的信号颜色集合（RGB）。 */
	public final int[] blockedColors;

	/** 是否有列车/预留正在占用该轨道。 */
	public final boolean occupied;

	/** 是否为站台轨道。 */
	public final boolean isPlatform;
	/** 是否为存车线轨道。 */
	public final boolean isSiding;

	/** 轨道限速（km/h）。 */
	public final double speedLimit;

	/** 轨道曲线形状序号，用于画面上区分直轨与弯轨。 */
	public final int shapeOrdinal;

	public SignalRailData(
			long pos1, long pos2,
			float[] path, double length,
			int[] signalColors, int[] blockedColors,
			boolean occupied,
			boolean isPlatform, boolean isSiding,
			double speedLimit, int shapeOrdinal
	) {
		this.pos1 = pos1;
		this.pos2 = pos2;
		this.path = path;
		this.length = length;
		this.signalColors = signalColors;
		this.blockedColors = blockedColors;
		this.occupied = occupied;
		this.isPlatform = isPlatform;
		this.isSiding = isSiding;
		this.speedLimit = speedLimit;
		this.shapeOrdinal = shapeOrdinal;
	}

	/**
	 * 从服务端的 {@link Rail} 构建快照。
	 *
	 * <p>会沿轨道弧长采样出真实走向的折线，并从中还原精确端点。
	 */
	public static SignalRailData fromRail(Rail rail) {
		final RailMath math = rail.railMath;
		final double length = math.getLength();

		// 采样：每 1 格一个线段，至少 1 段，最多 MAX_SEGMENTS 段。
		final int segments = Math.max(1, Math.min(MAX_SEGMENTS, (int) Math.ceil(length)));
		final float[] path = new float[(segments + 1) * 3];
		for (int i = 0; i <= segments; i++) {
			final double s = length * i / segments;
			final Vector v = math.getPosition(s, false);
			path[i * 3] = (float) v.x;
			path[i * 3 + 1] = (float) v.y;
			path[i * 3 + 2] = (float) v.z;
		}

		// 精确端点：getPosition 返回的是 position + 0.5，向下取整即还原方块坐标。
		final Vector start = math.getPosition(0, false);
		final Vector end = math.getPosition(length, false);
		final long encoded1 = BlockPos.asLong(
				(int) Math.floor(start.x),
				(int) Math.round(start.y),
				(int) Math.floor(start.z)
		);
		final long encoded2 = BlockPos.asLong(
				(int) Math.floor(end.x),
				(int) Math.round(end.y),
				(int) Math.floor(end.z)
		);

		// 收集信号颜色
		final IntAVLTreeSet colors = rail.getSignalColors();
		final int[] colorArray = new int[colors.size()];
		int index = 0;
		for (final int color : colors) {
			colorArray[index++] = color;
		}

		// 收集当前被占用的信号颜色
		final LongArrayList blocked = new LongArrayList();
		rail.iterateCurrentlyBlockedSignalColors(blocked::add);
		final int[] blockedArray = new int[blocked.size()];
		for (int i = 0; i < blocked.size(); i++) {
			blockedArray[i] = (int) blocked.getLong(i);
		}

		return new SignalRailData(
				encoded1,
				encoded2,
				path,
				length,
				colorArray,
				blockedArray,
				!blocked.isEmpty(),
				rail.isPlatform(),
				rail.isSiding(),
				rail.getSpeedLimitKilometersPerHour(true),
				math.getShape().ordinal()
		);
	}

	public BlockPos getBlockPos1() {
		return BlockPos.fromLong(pos1);
	}

	public BlockPos getBlockPos2() {
		return BlockPos.fromLong(pos2);
	}

	/** 折线点数量。 */
	public int getPointCount() {
		return path.length / 3;
	}

	/** 取第 i 个折线点的 X。 */
	public float getPathX(int i) {
		return path[i * 3];
	}

	/** 取第 i 个折线点的 Y。 */
	public float getPathY(int i) {
		return path[i * 3 + 1];
	}

	/** 取第 i 个折线点的 Z。 */
	public float getPathZ(int i) {
		return path[i * 3 + 2];
	}
}
