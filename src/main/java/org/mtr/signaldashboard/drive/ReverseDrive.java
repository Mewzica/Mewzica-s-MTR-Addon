package org.mtr.signaldashboard.drive;

import org.mtr.core.data.Vehicle;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反相器（换向手柄）状态。
 *
 * <h2>三档，和真车一样</h2>
 * {@link Reverser#REVERSE R} ↔ {@link Reverser#NEUTRAL N} ↔ {@link Reverser#FORWARD F}。
 * 客户端每 tick 上报<b>绝对档位</b>，服务端在 {@code VehicleMixin} 里应用：
 * <ul>
 *     <li><b>F</b> —— 什么都不做，列车按 MTR 自己的逻辑走；</li>
 *     <li><b>N</b> —— <b>切断牵引</b>：位置不动、速度清零，推油门也不走；</li>
 *     <li><b>R</b> —— 把 {@code railProgress} 的增量取反（倒着走）。</li>
 * </ul>
 *
 * <p>本类只负责「玩家想要哪一档」，不做任何位移。
 *
 * <h2>为什么不改速度来实现倒车</h2>
 * 让速度为负这条路被 MTR 4.0.5 的字节码堵死了：
 * <ol>
 *     <li>{@code Vehicle.simulate} 开头 {@code if (speed > 0) simulateMoving() else { speed = 0; simulateStopped() }}
 *         —— 速度不为正就被钳成 0；</li>
 *     <li>{@code simulateMoving} 手动分支里 {@code speedTarget} 在油门为负时被硬锁成 0；</li>
 *     <li>制动分支末尾 {@code Math.max(..., 4.0E-6)} —— 速度有正下界。</li>
 * </ol>
 * 所以改成<b>只翻转位置增量</b>：让 {@code simulateMoving} 照常跑完（加速度曲线、限速、
 * 制动、车门逻辑全部由 MTR 自己算），然后在它返回时把 {@code railProgress} 沿本 tick 的
 * 起点做镜像。这样速度表、限速、牵引/制动特性一个都不用自己重写。
 *
 * <h2>为什么按玩家 UUID 存、而不是按车辆</h2>
 * 车辆不需要出现在包里：服务端拿到玩家 UUID 后在自己的模拟数据里反查这辆车
 * （遍历 {@code VehicleExtraData} 的乘客列表，见 {@link #resolveReverser}）。
 * 这样客户端不需要知道任何服务端的车辆标识，也避免了伪造他人车辆的风险。
 *
 * <h2>联锁</h2>
 * 和真实机车一样，<b>行驶中不允许换向</b>。这一关只在两处判：
 * <ul>
 *     <li>{@code DriveInput}（客户端）—— 按车辆速度挡掉按键，并确认本机玩家是司机；</li>
 *     <li>{@code VehicleMixin}（服务端）—— 权威兜底。</li>
 * </ul>
 * ⚠️ <b>客户端的 {@code VehicleMixin} 故意不判这一关</b>：客户端车辆实例每次同步
 * 都会被换成新对象、档位字段回到默认的 F，再判联锁会让客户端永远拿不到 R。
 * 详见 {@code VehicleMixin} 的类注释。
 *
 * @author Mewzica
 */
public final class ReverseDrive {

	/**
	 * 「停稳」阈值（方块 / 毫秒）。
	 *
	 * <p>不能用 {@code speed == 0}：MTR 的制动下界和起步速度都是 {@code 4.0E-6}，
	 * 永远不会真的到 0。这里留两个数量级余量。
	 */
	public static final double STOPPED_SPEED = 1.0E-4D;

	/**
	 * 上报的有效期（毫秒）。
	 *
	 * <p>客户端在驾驶期间每 tick 发一次，所以正常情况这个值远不会到期。
	 * 一旦玩家下车（不再发包）或掉线，超时后自动当作「没有请求」→ 回前进位。
	 *
	 * <p>取 1000ms（20 tick）而不是 250ms：MTR 的渲染线程偶尔会卡顿一下
	 * （加载区块、开背包、切窗口），250ms 太容易误判成「司机走了」——
	 * 一旦误判，行驶中的列车会突然弹回前进位、以原速往前冲，非常吓人。
	 */
	private static final long INPUT_TIMEOUT_MILLIS = 1000L;

	/** 玩家 UUID → 最后一次上报的档位。MTR 模拟跑在独立线程，必须用并发容器。 */
	private static final Map<UUID, Input> INPUTS = new ConcurrentHashMap<>();

	private ReverseDrive() {
	}

	/** 服务端收到 {@code PacketReverser} 后调用。{@code position} 见 {@link Reverser}。 */
	public static void setReverser(UUID playerUuid, int position) {
		INPUTS.put(playerUuid, new Input(Reverser.of(position), System.currentTimeMillis()));
	}

	/**
	 * 解析某辆车当前该用哪一档。
	 *
	 * <p>判定条件：<b>车上有一位还在上报的乘客</b>（优先取 {@code isDriver()} 那位）。
	 *
	 * <h2>为什么必须留「任何乘客」这条兜底</h2>
	 * MTR 的 {@code isDriver} 就是<b>手上拿着司机钥匙</b>
	 * （{@code VehicleRidingMovement} 里 {@code isHoldingDriverKey = getValidHoldingKey(...) != null}）。
	 * 实测司机钥匙一离手（切换手持物品、开背包）这个标记立刻变 false，
	 * 档位会在列车高速倒车途中突然掉回 F，车猛地改回往前冲 —— 看着就像瞬移。
	 *
	 * <p>而「这位玩家还在上报」本身就说明他还在驾驶界面里（{@code DriveInput} 只在
	 * 驾驶 HUD 显示期间发包），所以用「车上有上报者」兜底比单看 {@code isDriver} 稳得多。
	 *
	 * <p>乘客不可能靠这条路径乱动车：<b>换档按键</b>在 {@code DriveInput} 里要求
	 * 本机玩家确实是司机，乘客的档位永远是默认的 F。
	 *
	 * @return 请求的档位；{@code null} = 车上没有还在上报的人
	 *         （调用方一律按 {@link Reverser#FORWARD} 处理）
	 */
	@Nullable
	public static Reverser resolveReverser(Vehicle vehicle) {
		if (INPUTS.isEmpty()) {
			return null;
		}

		final long now = System.currentTimeMillis();
		final Reverser[] driverInput = new Reverser[1];
		final Reverser[] anyRiderInput = new Reverser[1];

		vehicle.vehicleExtraData.iterateRidingEntities(ridingEntity -> {
			final Input input = INPUTS.get(ridingEntity.uuid);
			if (input == null || now - input.millis() > INPUT_TIMEOUT_MILLIS) {
				return;
			}
			if (ridingEntity.isDriver()) {
				if (driverInput[0] == null) {
					driverInput[0] = input.reverser();
				}
			} else if (anyRiderInput[0] == null) {
				anyRiderInput[0] = input.reverser();
			}
		});

		return driverInput[0] != null ? driverInput[0] : anyRiderInput[0];
	}

	/**
	 * {@link #resolveReverser} 返回 {@code null} 时，说明是哪一道关卡没过。
	 *
	 * <p>只在「档位从有变无」那一次调用，用于打一行日志——否则掉档位这种事
	 * 在游戏里只能看到车突然反向，完全猜不出原因。
	 *
	 * <p>判定顺序必须和 {@link #resolveReverser} 完全一致，否则日志会撒谎。
	 */
	public static String describeFailure(Vehicle vehicle) {
		if (INPUTS.isEmpty()) {
			return "no player has ever reported a reverser position";
		}

		final long now = System.currentTimeMillis();
		final boolean[] hasRider = new boolean[1];
		final boolean[] hasDriver = new boolean[1];
		final long[] newestMillis = new long[1];

		vehicle.vehicleExtraData.iterateRidingEntities(ridingEntity -> {
			hasRider[0] = true;
			if (ridingEntity.isDriver()) {
				hasDriver[0] = true;
			}
			final Input input = INPUTS.get(ridingEntity.uuid);
			if (input != null && input.millis() > newestMillis[0]) {
				newestMillis[0] = input.millis();
			}
		});

		if (!hasRider[0]) {
			return "nobody is riding this vehicle";
		}
		if (newestMillis[0] == 0L) {
			return "no rider has a registered input (player stopped driving)";
		}
		return "input timeout (" + (now - newestMillis[0]) + "ms > " + INPUT_TIMEOUT_MILLIS + "ms, isDriver=" + hasDriver[0] + ")";
	}

	/**
	 * 反相器档位。
	 *
	 * <p>{@code position} 就是真车手柄的档位顺序：{@code R(-1) → N(0) → F(+1)}，
	 * 上报时直接发这个整数，服务端用 {@link #of(int)} 还原。
	 */
	public enum Reverser {

		/** 后退位。 */
		REVERSE(-1, "R"),

		/** 中间位：切断牵引。 */
		NEUTRAL(0, "N"),

		/** 前进位。 */
		FORWARD(1, "F");

		/** 档位序号，直接用于网络传输。 */
		public final int position;

		/** 手柄刻度字母，只用于英文日志（界面文案走 {@code Lang}）。 */
		public final String letter;

		Reverser(int position, String letter) {
			this.position = position;
			this.letter = letter;
		}

		/** 把任意整数夹到三档之内。 */
		public static Reverser of(int position) {
			if (position <= REVERSE.position) {
				return REVERSE;
			}
			if (position >= FORWARD.position) {
				return FORWARD;
			}
			return NEUTRAL;
		}

		/**
		 * 往 {@code delta} 的方向推一档，到头就停住（不回卷）。
		 *
		 * <p>这就是真车手柄的行为：从 F 往 R 推一次只是到 N，再推一次才到 R。
		 */
		public Reverser step(int delta) {
			return of(position + Integer.signum(delta));
		}
	}

	/** 一位司机的反相器状态快照。 */
	private record Input(Reverser reverser, long millis) {
	}
}
