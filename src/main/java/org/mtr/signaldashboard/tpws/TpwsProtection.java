package org.mtr.signaldashboard.tpws;

import org.mtr.core.data.Data;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.SoundCategory;
import org.mtr.mapping.registry.SoundEventRegistryObject;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.Sounds;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.data.TpwsCoilRegistry;
import org.mtr.signaldashboard.mixin.NameColorDataBaseSchemaAccessor;
import org.mtr.signaldashboard.mixin.VehicleSchemaAccessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TPWS 防护的核心判定 —— 由 {@code VehicleMixin} 每 tick 调用一次。
 *
 * <h2>两种模式的触发语义不一样，这是故意的</h2>
 * <ul>
 *     <li><b>超速（{@link TpwsCoilConfig#MODE_OVERSPEED}）—— 边沿触发</b>。
 *         只在列车<b>刚压上线圈</b>的那一刻比一次速度：超了就把速度清零，
 *         然后按住 3 秒放行。<b>不能</b>做成电平触发 —— 那样列车被刹停后会一直
 *         停在线圈上、速度永远是 0，如果阈值设得低而这里又是必经之路，车就再也走不了了。
 *         现实里的 TPWS 也是「拦一次、司机复位后放行」，不是永久封锁。</li>
 *     <li><b>信号联锁（{@link TpwsCoilConfig#MODE_SIGNAL}）—— 电平触发</b>。
 *         只要「列车还压在线圈上」并且「绑定信号机的闭塞还是红的」，就一直按着。
 *         这正是要的效果：<b>判定点从信号机节点挪到线圈位置</b>，
 *         列车在线圈处就被拽停，红灯不灭就出不去；信号转绿自动放行。</li>
 * </ul>
 *
 * <h2>刹车怎么做</h2>
 * 把 {@code VehicleSchema.speed} 直接置 0。这是「紧急制动」在这个模组里的
 * 唯一含义 —— MTR 没有单独的紧急制动状态，速度是位置变化率的唯一来源，
 * 清零即等于本 tick 位移为 0。放在 {@code simulate} 的 HEAD 做，
 * MTR 随后的行驶计算拿到的速度就是 0，列车原地不动。
 *
 * <h2>线程</h2>
 * 本类的方法只在 MTR 模拟线程（服务端）或客户端渲染线程上被调用。
 * 单机时两者同进程不同线程，所以下面几张表都用并发容器。
 *
 * @author Mewzica
 */
public final class TpwsProtection {

	/**
	 * 触发半径（方块）。
	 *
	 * <p>列车在 80 km/h 下每 tick 前进约 1.1 格，理论上 1.5 格就够；
	 * 但客户端是<b>按帧</b>模拟的，帧率低时单帧位移更大，取 2.5 格留余量。
	 * 也不能太大，否则同一段线上相邻的两枚线圈会互相干扰。
	 */
	public static final double TRIGGER_RADIUS = 2.5D;

	/** 超速触发后按住制动的时长（毫秒）。到点放行，让列车自己重新起步。 */
	public static final long OVERSPEED_HOLD_MILLIS = 3000L;

	/** 同一个音效两次播放的最小间隔（毫秒），防止帧率抖动时连着播两遍。 */
	private static final long SOUND_COOLDOWN_MILLIS = 1500L;

	/** 车辆当前所处的线圈（状态键 → 线圈坐标编码）。用来识别「进入 / 离开」。 */
	private static final Map<Long, Long> CURRENT_COIL = new ConcurrentHashMap<>();

	/** 超速模式下一次制动保持到什么时候（状态键 → 时间戳）。 */
	private static final Map<Long, Long> OVERSPEED_HOLD_UNTIL = new ConcurrentHashMap<>();

	/** 每辆车上次播起动音 / 报警音的时间戳。 */
	private static final Map<Long, Long> LAST_START_SOUND = new ConcurrentHashMap<>();
	private static final Map<Long, Long> LAST_ALARM_SOUND = new ConcurrentHashMap<>();

	private TpwsProtection() {
	}

	/** 一次判定的结果，主要给日志和音效用。 */
	public enum Outcome {
		/** 附近没有线圈，或列车不在触发范围内。 */
		NONE,
		/** 压上线圈但没触发。 */
		ARMED,
		/** 超速触发。 */
		TRIPPED_OVERSPEED,
		/** 信号联锁触发（SPAD = Signal Passed At Danger）。 */
		TRIPPED_SPAD
	}

	/**
	 * 每 tick 调用一次。
	 *
	 * @param vehicle    被模拟的车辆
	 * @param clientSide 是否是客户端副本。客户端<b>只播声音、不改速度</b>
	 */
	public static Outcome tick(Vehicle vehicle, boolean clientSide) {
		final long stateKey = stateKey(vehicle.getId(), clientSide);
		final Vector head = vehicle.getHeadPosition();
		if (head == null) {
			return Outcome.NONE;
		}

		final String dimension = getDimension(vehicle);
		final TpwsCoilRegistry.Coil coil = TpwsCoilRegistry.findNear(dimension, head.x, head.y, head.z, TRIGGER_RADIUS);

		if (coil == null) {
			// 离开所有线圈：清掉边沿状态，下次经过重新计
			leaveCoil(stateKey);
			return Outcome.NONE;
		}

		final boolean justEntered = !Long.valueOf(coil.blockPos()).equals(CURRENT_COIL.put(stateKey, coil.blockPos()));
		final TpwsCoilConfig config = coil.config();

		if (justEntered && clientSide) {
			playSound(stateKey, head, Sounds.TPWS_START, LAST_START_SOUND, 0.6F);
		}

		final Outcome outcome = config.isSignalMode()
				? evaluateSignal(vehicle, config)
				: evaluateOverspeed(vehicle, config, head, justEntered, stateKey);

		if (clientSide) {
			if (outcome == Outcome.TRIPPED_OVERSPEED) {
				playSound(stateKey, head, Sounds.TPWS_OVERSPEED, LAST_ALARM_SOUND, 1.0F);
			} else if (outcome == Outcome.TRIPPED_SPAD) {
				playSound(stateKey, head, Sounds.TPWS_SPAD, LAST_ALARM_SOUND, 1.0F);
			}
			// 客户端只负责听个响；真正的刹车由服务端权威执行
			return outcome;
		}

		if (outcome == Outcome.TRIPPED_OVERSPEED || outcome == Outcome.TRIPPED_SPAD) {
			((VehicleSchemaAccessor) (Object) vehicle).mewsmtr$setSpeed(0.0D);
		}

		return outcome;
	}

	/**
	 * 状态表用的键。
	 *
	 * <p><b>必须把客户端/服务端区分开</b>：单人游戏里两边同进程，
	 * 客户端那份 {@code Vehicle} 和服务端那份是<b>两个对象但 id 相同</b>
	 * （id 来自存档数据）。如果共用一张表，客户端每帧的写入会把服务端刚记下的
	 * 「刚进入线圈」边沿吃掉 —— 表现就是超速防护在单人游戏里时灵时不灵。
	 *
	 * <p>做法是把 id 左移一位、最低位放 client/server 标记。
	 */
	private static long stateKey(long vehicleId, boolean clientSide) {
		return vehicleId << 1 | (clientSide ? 1L : 0L);
	}

	/** 超速模式：边沿触发 + 3 秒保持。 */
	private static Outcome evaluateOverspeed(Vehicle vehicle, TpwsCoilConfig config, Vector head, boolean justEntered, long stateKey) {
		final long now = System.currentTimeMillis();
		final long holdUntil = OVERSPEED_HOLD_UNTIL.getOrDefault(stateKey, 0L);

		if (now < holdUntil) {
			// 还在按住：继续刹
			return Outcome.TRIPPED_OVERSPEED;
		}

		if (!justEntered) {
			// 已经在线圈上、又没在按住 —— 这一趟已经拦过了，别再拦
			return Outcome.ARMED;
		}

		final double speedKmh = getSpeedKilometersPerHour(vehicle);
		final double thresholdKmh = resolveThreshold(config, vehicle, head);

		if (speedKmh > thresholdKmh) {
			OVERSPEED_HOLD_UNTIL.put(stateKey, now + OVERSPEED_HOLD_MILLIS);
			MTRSignalDashboard.LOGGER.info(
					"TPWS overspeed trip on vehicle {}: {} km/h > {} km/h",
					Long.toHexString(vehicle.getId()), Math.round(speedKmh), Math.round(thresholdKmh)
			);
			return Outcome.TRIPPED_OVERSPEED;
		}

		return Outcome.ARMED;
	}

	/** 信号联锁模式：电平触发 —— 红灯不灭就一直按着。 */
	private static Outcome evaluateSignal(Vehicle vehicle, TpwsCoilConfig config) {
		if (!config.hasSignal()) {
			// 还没绑信号机：装上了但不动作，避免刚放下就被莫名其妙刹停
			return Outcome.ARMED;
		}

		if (!isLinkedSignalBlocked(vehicle, config)) {
			return Outcome.ARMED;
		}

		return Outcome.TRIPPED_SPAD;
	}

	// ------------------------------------------------------------------
	// 阈值与信号状态
	// ------------------------------------------------------------------

	/** 算出本次要比较的阈值（km/h）。 */
	private static double resolveThreshold(TpwsCoilConfig config, Vehicle vehicle, Vector head) {
		if (!config.isLineSpeedMode()) {
			return config.getSpeedLimitKmh();
		}
		final Rail rail = findRailNear(vehicle, head.x, head.y, head.z);
		if (rail == null) {
			// 找不到轨道（比如在车厂里）——退回固定值，至少不会完全不设防
			return config.getSpeedLimitKmh();
		}
		return rail.getSpeedLimitKilometersPerHour(true) + config.getMarginKmh();
	}

	/**
	 * 判断绑定的信号机所在闭塞是否「红」（有信号颜色处于占用状态）。
	 *
	 * <p>如果链接时成功读到了信号机的颜色，就只认那个颜色；
	 * 否则退化成「该轨道任一闭塞被占用即视为红」。
	 */
	private static boolean isLinkedSignalBlocked(Vehicle vehicle, TpwsCoilConfig config) {
		final Data data = getData(vehicle);
		if (data == null) {
			return false;
		}

		final BlockPos signalPos = BlockPos.fromLong(config.getSignalPos());
		final org.mtr.core.data.Position signalPosition = new org.mtr.core.data.Position(
				signalPos.getX(), signalPos.getY(), signalPos.getZ()
		);

		for (final Rail rail : data.rails) {
			if (rail == null || !rail.isValid() || !rail.closeTo(signalPosition, 2.0D)) {
				continue;
			}

			final boolean[] blocked = {false};
			rail.iterateCurrentlyBlockedSignalColors(color -> {
				if (!config.hasSignalColor() || (int) color == config.getSignalColor()) {
					blocked[0] = true;
				}
			});
			if (blocked[0]) {
				return true;
			}
		}

		return false;
	}

	/** 找出离给定世界坐标最近的轨道（用于取线路限速）。 */
	public static Rail findRailNear(Vehicle vehicle, double x, double y, double z) {
		final Data data = getData(vehicle);
		if (data == null) {
			return null;
		}

		final org.mtr.core.data.Position position = new org.mtr.core.data.Position(
				(long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z)
		);

		Rail best = null;
		double bestDistance = 4.0D;
		for (final Rail rail : data.rails) {
			if (rail == null || !rail.isValid()) {
				continue;
			}
			final double distance = distanceToRail(rail, position);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = rail;
			}
		}
		return best;
	}

	/** 轨道到某个方块的粗略距离：沿弧长取 5 个采样点，取最近的那个。 */
	private static double distanceToRail(Rail rail, org.mtr.core.data.Position position) {
		final double length = rail.railMath.getLength();
		double best = Double.MAX_VALUE;
		for (int i = 0; i <= 4; i++) {
			final Vector point = rail.railMath.getPosition(length * i / 4.0D, false);
			final double dx = point.x - position.getX() - 0.5D;
			final double dy = point.y - position.getY() - 0.5D;
			final double dz = point.z - position.getZ() - 0.5D;
			best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return best;
	}

	// ------------------------------------------------------------------
	// 杂项
	// ------------------------------------------------------------------

	/** 速度字段单位是<b>方块/毫秒</b>，1 方块 = 1 米，所以 ×3600 得 km/h。 */
	private static double getSpeedKilometersPerHour(Vehicle vehicle) {
		return ((VehicleSchemaAccessor) (Object) vehicle).mewsmtr$getSpeed() * 3600.0D;
	}

	/** 从车辆反查它所属的 {@link Data}（MTR 的 schema 基类里存着这个引用）。 */
	private static Data getData(Vehicle vehicle) {
		try {
			return ((NameColorDataBaseSchemaAccessor) (Object) vehicle).mewsmtr$getData();
		} catch (ClassCastException exception) {
			return null;
		}
	}

	/**
	 * 车辆所在维度的标识。
	 *
	 * <p>{@link Data} 本身不带维度，只有 {@link Simulator} 才有 —— 所以先看
	 * 实际类型是不是模拟器。拿不到时返回空串，此时
	 * {@link TpwsCoilRegistry#findNear} 会去查「空维度」那张表（通常是空的），
	 * 也就是不触发 —— 宁可不动，也不要跨维度误刹。
	 */
	private static String getDimension(Vehicle vehicle) {
		final Data data = getData(vehicle);
		return data instanceof Simulator simulator ? simulator.dimension : "";
	}

	private static void leaveCoil(long stateKey) {
		if (CURRENT_COIL.remove(stateKey) != null) {
			OVERSPEED_HOLD_UNTIL.remove(stateKey);
		}
	}

	/**
	 * 在客户端播放音效。
	 *
	 * <p>为什么声音放客户端、刹车放服务端：声音是「贴耳」的体验，客户端本来就在
	 * 逐帧预测所有列车的位置，本地播最跟手；而刹车必须由服务端权威执行，
	 * 否则各客户端各刹各的会直接打架。
	 *
	 * <p><b>绝不能在服务端调到这个方法</b> —— {@link MinecraftClient} 是客户端专属类，
	 * 专用服务器上没有，一旦解析到就会 {@code NoClassDefFoundError}。
	 * 所以调用点全部套在 {@code if (clientSide)} 里。
	 */
	private static void playSound(long stateKey, Vector head, SoundEventRegistryObject sound, Map<Long, Long> cooldownMap, float volume) {
		final long now = System.currentTimeMillis();
		if (now - cooldownMap.getOrDefault(stateKey, 0L) < SOUND_COOLDOWN_MILLIS) {
			return;
		}
		cooldownMap.put(stateKey, now);

		final MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		final ClientWorld world = client.getWorldMapped();
		if (world == null) {
			return;
		}

		sound.ifPresent(soundEvent -> world.playSound(
				(PlayerEntity) null,
				head.x, head.y, head.z,
				soundEvent,
				SoundCategory.BLOCKS,
				volume,
				1.0F
		));
	}
}
