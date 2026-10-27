package org.mtr.signaldashboard.data;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

/**
 * 一枚 TPWS 线圈（{@code tpws_grid} 方块）的配置。
 *
 * <p>两种工作模式，对应现实里 TPWS 的两类感应回路：
 *
 * <ul>
 *     <li><b>{@link #MODE_OVERSPEED}（超速防护）</b> —— 相当于 TPWS 的
 *         <i>OSS</i>（Overspeed Sensor）回路。列车经过线圈时如果速度超过阈值，
 *         立即施加紧急制动。阈值有两种给法：
 *         <ul>
 *             <li>{@link #SPEED_FIXED}：写死一个 km/h 值；</li>
 *             <li>{@link #SPEED_LINE}：取列车当前所在轨道的限速，再加上一个余量
 *                 （对应现实里 OSS 允许超速若干百分点的容差）。</li>
 *         </ul>
 *     </li>
 *     <li><b>{@link #MODE_SIGNAL}（信号联锁）</b> —— 相当于 TPWS 的
 *         <i>Arming + Trigger</i> 回路。线圈绑定一个附近的信号机，
 *         该信号机所在闭塞处于「占用（红灯）」时，列车压上线圈即触发紧急制动。
 *         <b>判定点从信号机节点挪到了线圈位置</b> —— 这正是 TPWS 相对
 *         纯信号系统的意义：信号机前方几十米处就有一道「绊索」，
 *         闯红灯的列车在线圈处就被拽停，而不是冲过信号机之后才反应。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 这个对象同时被两个线程访问：
 * <ul>
 *     <li><b>Minecraft 服务端线程</b> —— 方块实体读写 NBT、玩家在 GUI 里改配置；</li>
 *     <li><b>MTR 模拟线程</b> —— {@code Vehicle.simulate} 里读它来决定要不要刹车。</li>
 * </ul>
 * MTR 的模拟跑在自己的 {@code ScheduledExecutorService} 上，和 MC 主线程不是一个线程，
 * 所以这里所有字段都标了 {@code volatile}。字段全是 32/64 位的原始类型，
 * 不需要额外加锁；代价是可能读到「改了一半」的组合（比如模式已切到信号联锁、
 * 但信号坐标还没写进去）—— 这种瞬时不一致最多让某一 tick 少刹一次车，可以接受。
 *
 * @author Mewzica
 */
public final class TpwsCoilConfig {

	/** 超速防护模式。 */
	public static final int MODE_OVERSPEED = 0;
	/** 信号联锁模式。 */
	public static final int MODE_SIGNAL = 1;

	/** 阈值 = 固定 km/h 值。 */
	public static final int SPEED_FIXED = 0;
	/** 阈值 = 线路限速 + 余量。 */
	public static final int SPEED_LINE = 1;

	/** 双向触发（默认）。 */
	public static final int DIRECTION_BOTH = 0;
	/** 只拦「朝北（-Z）行驶」的列车。 */
	public static final int DIRECTION_NORTH = 1;
	/** 只拦「朝东（+X）行驶」的列车。 */
	public static final int DIRECTION_EAST = 2;
	/** 只拦「朝南（+Z）行驶」的列车。 */
	public static final int DIRECTION_SOUTH = 3;
	/** 只拦「朝西（-X）行驶」的列车。 */
	public static final int DIRECTION_WEST = 4;

	/** 方向的可选值个数（{@link #DIRECTION_BOTH} + 四个水平朝向）。 */
	public static final int DIRECTION_COUNT = 5;

	/** 未绑定信号机时 {@link #getSignalPos()} 的取值。 */
	public static final long NO_SIGNAL = 0L;

	/** 未能确定信号颜色时 {@link #getSignalColor()} 的取值。 */
	public static final int NO_COLOR = -1;

	/** 固定阈值模式的默认值（km/h）。 */
	public static final int DEFAULT_SPEED_KMH = 80;
	/** 线路限速模式的默认余量（km/h）。 */
	public static final int DEFAULT_MARGIN_KMH = 5;

	/** 固定阈值的调节步长（km/h）。 */
	public static final int SPEED_STEP = 5;
	/** 固定阈值的可调范围（km/h）。 */
	public static final int MIN_SPEED_KMH = 5;
	public static final int MAX_SPEED_KMH = 500;
	/** 余量的可调范围（km/h）。 */
	public static final int MIN_MARGIN_KMH = 0;
	public static final int MAX_MARGIN_KMH = 100;

	// ---- NBT 键 ----
	private static final String KEY_MODE = "Mode";
	private static final String KEY_SPEED_MODE = "SpeedMode";
	private static final String KEY_SPEED_LIMIT = "SpeedLimit";
	private static final String KEY_MARGIN = "Margin";
	private static final String KEY_SIGNAL_POS = "SignalPos";
	private static final String KEY_SIGNAL_COLOR = "SignalColor";
	private static final String KEY_DIRECTION = "Direction";

	private volatile int mode;
	private volatile int speedMode;
	private volatile int speedLimitKmh;
	private volatile int marginKmh;
	/**
	 * 触发方向：{@link #DIRECTION_BOTH} 或四个水平朝向之一。
	 *
	 * <p>现实里的 TPWS 是<b>单向</b>的 —— 只有按行车方向驶来的列车才需要被拦。
	 * 双向触发在单线区段上会把反向正常运行的列车也拽停，所以这个开关是必要的。
	 *
	 * <p>存的是<b>绝对世界方向</b>而不是「相对方块朝向」，因为映射层的
	 * {@code BlockState.get} 返回的是 vanilla 枚举、不做 holder 包装，
	 * 从方块实体里读朝向要多绕一层；而绝对方向对玩家来说也更直观
	 * （线圈可以横跨轨道，触发方向本来就不必等于模型朝向）。
	 */
	private volatile int direction;
	/** 绑定的信号机方块坐标（{@link BlockPos#asLong()} 编码），{@link #NO_SIGNAL} 表示未绑定。 */
	private volatile long signalPos;
	/** 绑定的信号颜色（24 位 RGB），{@link #NO_COLOR} 表示未确定。 */
	private volatile int signalColor;

	public TpwsCoilConfig() {
		this(MODE_OVERSPEED, SPEED_FIXED, DEFAULT_SPEED_KMH, DEFAULT_MARGIN_KMH, NO_SIGNAL, NO_COLOR, DIRECTION_BOTH);
	}

	public TpwsCoilConfig(int mode, int speedMode, int speedLimitKmh, int marginKmh, long signalPos, int signalColor) {
		this(mode, speedMode, speedLimitKmh, marginKmh, signalPos, signalColor, DIRECTION_BOTH);
	}

	public TpwsCoilConfig(int mode, int speedMode, int speedLimitKmh, int marginKmh, long signalPos, int signalColor, int direction) {
		this.mode = mode == MODE_SIGNAL ? MODE_SIGNAL : MODE_OVERSPEED;
		this.speedMode = speedMode == SPEED_LINE ? SPEED_LINE : SPEED_FIXED;
		this.speedLimitKmh = clamp(speedLimitKmh, MIN_SPEED_KMH, MAX_SPEED_KMH);
		this.marginKmh = clamp(marginKmh, MIN_MARGIN_KMH, MAX_MARGIN_KMH);
		this.signalPos = signalPos;
		this.signalColor = signalColor;
		this.direction = normalizeDirection(direction);
	}

	/** 深拷贝 —— 发给客户端编辑的那一份必须是副本，否则玩家点「取消」也会改到服务端数据。 */
	public TpwsCoilConfig copy() {
		return new TpwsCoilConfig(mode, speedMode, speedLimitKmh, marginKmh, signalPos, signalColor, direction);
	}

	// ------------------------------------------------------------------
	// 访问器
	// ------------------------------------------------------------------

	public int getMode() {
		return mode;
	}

	public void setMode(int mode) {
		this.mode = mode == MODE_SIGNAL ? MODE_SIGNAL : MODE_OVERSPEED;
	}

	public boolean isSignalMode() {
		return mode == MODE_SIGNAL;
	}

	public int getSpeedMode() {
		return speedMode;
	}

	public void setSpeedMode(int speedMode) {
		this.speedMode = speedMode == SPEED_LINE ? SPEED_LINE : SPEED_FIXED;
	}

	public boolean isLineSpeedMode() {
		return speedMode == SPEED_LINE;
	}

	public int getSpeedLimitKmh() {
		return speedLimitKmh;
	}

	public void setSpeedLimitKmh(int speedLimitKmh) {
		this.speedLimitKmh = clamp(speedLimitKmh, MIN_SPEED_KMH, MAX_SPEED_KMH);
	}

	/** 按步长调整固定阈值。 */
	public void nudgeSpeedLimitKmh(int steps) {
		setSpeedLimitKmh(speedLimitKmh + steps * SPEED_STEP);
	}

	public int getMarginKmh() {
		return marginKmh;
	}

	public void setMarginKmh(int marginKmh) {
		this.marginKmh = clamp(marginKmh, MIN_MARGIN_KMH, MAX_MARGIN_KMH);
	}

	/** 按 1 km/h 调整余量（余量范围小，步长用 1 更顺手）。 */
	public void nudgeMarginKmh(int delta) {
		setMarginKmh(marginKmh + delta);
	}

	public long getSignalPos() {
		return signalPos;
	}

	public boolean hasSignal() {
		return signalPos != NO_SIGNAL;
	}

	public void setSignal(long signalPos, int signalColor) {
		this.signalPos = signalPos;
		this.signalColor = signalColor;
	}

	public void clearSignal() {
		this.signalPos = NO_SIGNAL;
		this.signalColor = NO_COLOR;
	}

	public int getSignalColor() {
		return signalColor;
	}

	public boolean hasSignalColor() {
		return signalColor != NO_COLOR;
	}

	public int getDirection() {
		return direction;
	}

	public void setDirection(int direction) {
		this.direction = normalizeDirection(direction);
	}

	public boolean isDirectionBoth() {
		return direction == DIRECTION_BOTH;
	}

	/** 循环切到下一个方向（双向 → 北 → 东 → 南 → 西 → 双向）。 */
	public void cycleDirection() {
		this.direction = (direction + 1) % DIRECTION_COUNT;
	}

	/** 把越界的值收回到 {@link #DIRECTION_BOTH}，避免存档里出现野值。 */
	private static int normalizeDirection(int direction) {
		return direction >= DIRECTION_BOTH && direction < DIRECTION_COUNT ? direction : DIRECTION_BOTH;
	}

	// ------------------------------------------------------------------
	// 序列化
	// ------------------------------------------------------------------

	/** 写入方块实体 NBT。键名带模组前缀由调用方负责（方块实体的 tag 本来就是私有的）。 */
	public void writeCompoundTag(CompoundTag tag) {
		tag.putInt(KEY_MODE, mode);
		tag.putInt(KEY_SPEED_MODE, speedMode);
		tag.putInt(KEY_SPEED_LIMIT, speedLimitKmh);
		tag.putInt(KEY_MARGIN, marginKmh);
		tag.putLong(KEY_SIGNAL_POS, signalPos);
		tag.putInt(KEY_SIGNAL_COLOR, signalColor);
		tag.putInt(KEY_DIRECTION, direction);
	}

	/** 从方块实体 NBT 读取。缺字段时保留当前值，兼容旧存档。 */
	public void readCompoundTag(CompoundTag tag) {
		if (tag.contains(KEY_MODE)) {
			setMode(tag.getInt(KEY_MODE));
		}
		if (tag.contains(KEY_SPEED_MODE)) {
			setSpeedMode(tag.getInt(KEY_SPEED_MODE));
		}
		if (tag.contains(KEY_SPEED_LIMIT)) {
			setSpeedLimitKmh(tag.getInt(KEY_SPEED_LIMIT));
		}
		if (tag.contains(KEY_MARGIN)) {
			setMarginKmh(tag.getInt(KEY_MARGIN));
		}
		if (tag.contains(KEY_SIGNAL_POS)) {
			signalPos = tag.getLong(KEY_SIGNAL_POS);
		}
		if (tag.contains(KEY_SIGNAL_COLOR)) {
			signalColor = tag.getInt(KEY_SIGNAL_COLOR);
		}
		if (tag.contains(KEY_DIRECTION)) {
			setDirection(tag.getInt(KEY_DIRECTION));
		}
	}

	public void write(PacketBufferSender sender) {
		sender.writeInt(mode);
		sender.writeInt(speedMode);
		sender.writeInt(speedLimitKmh);
		sender.writeInt(marginKmh);
		sender.writeLong(signalPos);
		sender.writeInt(signalColor);
		sender.writeInt(direction);
	}

	public static TpwsCoilConfig read(PacketBufferReceiver receiver) {
		return new TpwsCoilConfig(
				receiver.readInt(),
				receiver.readInt(),
				receiver.readInt(),
				receiver.readInt(),
				receiver.readLong(),
				receiver.readInt(),
				receiver.readInt()
		);
	}

	private static int clamp(int value, int min, int max) {
		return value < min ? min : Math.min(value, max);
	}

	@Override
	public String toString() {
		return "TpwsCoilConfig{mode=" + mode
				+ ", speedMode=" + speedMode
				+ ", speedLimit=" + speedLimitKmh
				+ ", margin=" + marginKmh
				+ ", signalPos=" + signalPos
				+ ", signalColor=" + Integer.toHexString(signalColor)
				+ ", direction=" + direction + '}';
	}
}
