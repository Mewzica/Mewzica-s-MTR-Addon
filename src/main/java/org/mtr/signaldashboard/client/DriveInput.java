package org.mtr.signaldashboard.client;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.MTRSignalDashboardClient;
import org.mtr.signaldashboard.drive.ReverseDrive;
import org.mtr.signaldashboard.mixin.VehicleSchemaAccessor;
import org.mtr.signaldashboard.packet.PacketReverser;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 客户端按键：手动驾驶的<b>反相器</b>（换向手柄）。
 *
 * <h2>三档手柄，逐档推</h2>
 * 和真车的换向手柄一样，只有三个位置：<b>F</b>（前进）↔ <b>N</b>（中间）↔ <b>R</b>（后退）。
 * <ul>
 *     <li><b>PgUp</b> —— 往 F 方向推一档；</li>
 *     <li><b>PgDn</b> —— 往 R 方向推一档；</li>
 *     <li>到头就停住，不回卷。所以从 F 往 R 推要按两下（先到 N，再到 R）。</li>
 * </ul>
 *
 * <p>按键不直接产生位移——位移由服务端在 {@code VehicleMixin} 里做
 * （N = 切断牵引，R = 把 {@code railProgress} 的增量取反，见 {@code ReverseDrive}）。
 *
 * <h2>联锁：两道闸，位置很关键</h2>
 * <ol>
 *     <li><b>必须是司机</b> —— MTR 的 {@code isDriver} 就是「手上拿着司机钥匙」，
 *         天然挡住乘客。这道闸放在<b>本类</b>（换档动作）。</li>
 *     <li><b>列车必须停稳</b> —— 和真实机车一致，行驶中按 PgUp / PgDn 会被忽略。
 *         本类判一次，服务端在 {@code VehicleMixin} 里再判一次兜底。</li>
 * </ol>
 *
 * <p>⚠️ <b>「必须是司机」这道闸只挡换档动作，不挡发包</b>。发包照发（只要还在驾驶），
 * 因为服务端要靠「还在上报」来判断档位是否还有效。如果把发包也掐了，
 * 司机钥匙一离手档位就会掉回 F，列车会在高速倒车途中猛地反向 —— 看着像瞬移。
 *
 * <h2>为什么要每 tick 发包</h2>
 * 服务端按「最后一次收到的时间」判断司机是否还在（超时 1000ms 自动回前进位）。
 * 如果只在换档时发包，一旦丢包服务端就会永远停在错误的档位。
 * 每 tick 发一个 int 代价极低，却自带自愈能力；玩家下车后不再发包，
 * 反相器也会自动归位。
 *
 * <h2>客户端预测</h2>
 * MTR 客户端每帧都会跑一遍 {@code simulate} 做位置预测。本类在发包的同时，
 * 也把档位登记进 {@link ReverseDrive}（用本机玩家的 UUID），
 * 这样客户端那侧的 {@code VehicleMixin} 也能读到档位、跟着一起镜像位移。
 * 少了这一步，客户端会继续「往前预测」、再被服务端同步拽回来，
 * 表现就是倒车时位置<b>往前蹭 + 抖动</b>。
 *
 * <p>注册必须发生在 {@code REGISTRY_CLIENT.init()} <b>之前</b>：
 * MTR 的 Registry 是延迟注册的，{@code registerKeyBinding} 只是把请求入队，
 * 漏在 {@code init()} 之后就永远不会生效（MTR 自己的 {@code InitClient}
 * 也是先 {@code KeyBindings.init()} 再 flush）。
 *
 * @author Mewzica
 */
public final class DriveInput {

	/** GLFW_KEY_PAGE_UP。MTR 自己的按键也用裸数字，见 {@code org.mtr.mod.KeyBindings}。 */
	private static final int KEY_PAGE_UP = 266;

	/** GLFW_KEY_PAGE_DOWN。 */
	private static final int KEY_PAGE_DOWN = 267;

	/**
	 * 驾驶 HUD 消失多久之后就认为玩家不再驾驶。
	 * 取 500ms（10 个 tick）是为了容忍偶尔掉帧，又不至于在玩家放下司机钥匙后
	 * 还继续往服务端发包。
	 */
	private static final long DRIVING_TIMEOUT_MILLIS = 500L;

	private static KeyBinding forwardKey;
	private static KeyBinding reverseKey;

	/** 反相器档位。默认前进位，保持「上车就能开」的手感。 */
	private static ReverseDrive.Reverser reverser = ReverseDrive.Reverser.FORWARD;

	private static long lastDrivenMillis;

	/** 最近一次驾驶的车辆，用来读速度做联锁判断。 */
	@Nullable
	private static VehicleExtension lastVehicle;

	private DriveInput() {
	}

	/** 由客户端初始化流程调用，必须早于 {@code REGISTRY_CLIENT.init()}。 */
	public static void init() {
		forwardKey = MTRSignalDashboardClient.REGISTRY_CLIENT.registerKeyBinding(
				"key.mewsmtr.reverser_forward",
				KEY_PAGE_UP,
				"category.mewsmtr.keybinding"
		);
		reverseKey = MTRSignalDashboardClient.REGISTRY_CLIENT.registerKeyBinding(
				"key.mewsmtr.reverser_reverse",
				KEY_PAGE_DOWN,
				"category.mewsmtr.keybinding"
		);
		MTRSignalDashboardClient.REGISTRY_CLIENT.eventRegistryClient.registerStartClientTick(DriveInput::onClientTick);
	}

	/**
	 * 由驾驶 HUD 在确认「确实在驾驶」时调用（每帧一次），刷新驾驶状态的时间戳。
	 *
	 * <h2>为什么不用「车辆对象换了」来判断换车</h2>
	 * MTR 客户端的 {@code VehicleExtension} 实例在数据同步时会被替换，<b>引用并不稳定</b>。
	 * 早先的版本拿 {@code vehicle != lastVehicle} 当作「换了一辆车」的依据，
	 * 结果玩家一动油门（触发同步）就被误判成换车，反相器被打回前进位——
	 * 日志里会刷出一串 {@code boarded a different vehicle}，车根本倒不了。
	 *
	 * <p>改成判断<b>驾驶是否中断过</b>：两次调用间隔超过
	 * {@link #DRIVING_TIMEOUT_MILLIS}，说明中间下过车（或真的换了车），
	 * 这时才把手柄归位。连续驾驶时每帧都会调用，间隔只有十几毫秒，不会误触发。
	 */
	public static void onDriving(VehicleExtension vehicle) {
		final long now = System.currentTimeMillis();
		if (now - lastDrivenMillis > DRIVING_TIMEOUT_MILLIS) {
			if (reverser != ReverseDrive.Reverser.FORWARD) {
				MTRSignalDashboard.LOGGER.info("[client] Reverser reset to FORWARD (driving session restarted)");
			}
			reverser = ReverseDrive.Reverser.FORWARD;
		}
		lastVehicle = vehicle;
		lastDrivenMillis = now;
	}

	/** @return 玩家当前是否坐在司机位上（驾驶 HUD 还在显示）。 */
	public static boolean isDriving() {
		return System.currentTimeMillis() - lastDrivenMillis < DRIVING_TIMEOUT_MILLIS;
	}

	/** @return 反相器当前档位（供驾驶 HUD 显示）。 */
	public static ReverseDrive.Reverser getReverser() {
		return reverser;
	}

	private static void onClientTick() {
		if (forwardKey == null || reverseKey == null) {
			return;
		}

		// 无论是否在驾驶都要把按键计数消费掉，
		// 否则走路时误按的键会一直挂在队列里，等上车停稳后突然生效。
		final boolean forwardPressed = forwardKey.wasPressed();
		final boolean reversePressed = reverseKey.wasPressed();

		final boolean driving = isDriving();
		if (driving && (forwardPressed || reversePressed)) {
			if (!isLocalPlayerDriver()) {
				// 乘客不能动车。MTR 的 isDriver 就是「手上拿着司机钥匙」，
				// 服务端那侧故意放宽了这道闸（见 ReverseDrive），所以闸门放在这里。
				MTRSignalDashboard.LOGGER.info("[client] Reverser key ignored: local player is not the driver");
			} else if (isStopped()) {
				final ReverseDrive.Reverser next = forwardPressed ? reverser.step(1) : reverser.step(-1);
				if (next != reverser) {
					reverser = next;
					MTRSignalDashboard.LOGGER.info("[client] Reverser -> {}", reverser.letter);
				}
			} else {
				MTRSignalDashboard.LOGGER.info("[client] Reverser key ignored: train still moving (interlock)");
			}
		}

		if (driving) {
			// 客户端自己也登记一份。MTR 客户端会做位置预测（每帧跑一遍 simulate），
			// 那段逻辑要用本机档位；单人游戏里客户端与服务端共用同一个静态表，
			// 值相同、没有副作用，多人游戏里则是客户端预测的唯一来源。
			final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
			if (player != null) {
				ReverseDrive.setReverser(player.getUuid(), reverser.position);
			}
			MTRSignalDashboardClient.REGISTRY_CLIENT.sendPacketToServer(new PacketReverser(reverser.position));
		}
	}

	/**
	 * @return 列车是否已经停稳。用 {@code VehicleSchema.speed}（单位方块/毫秒）判断，
	 *         阈值与服务端共用 {@link ReverseDrive#STOPPED_SPEED}，保证两边判断一致。
	 */
	private static boolean isStopped() {
		final VehicleExtension vehicle = lastVehicle;
		return vehicle != null && ((VehicleSchemaAccessor) vehicle).mewsmtr$getSpeed() <= ReverseDrive.STOPPED_SPEED;
	}

	/**
	 * @return 本机玩家是不是这辆车的司机。
	 *
	 * <p>MTR 的 {@code isDriver} 对应「手上拿着司机钥匙」，所以它同时也是
	 * 「乘客不能操纵列车」的天然闸门。服务端那侧（{@link ReverseDrive}）故意放宽了这道闸
	 * ——钥匙一离手就掉档位，会让列车在高速倒车途中猛地反向——所以把闸门挪到这里：
	 * <b>档位状态宽松保留，但换档动作严格限司机</b>。
	 */
	private static boolean isLocalPlayerDriver() {
		final VehicleExtension vehicle = lastVehicle;
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (vehicle == null || player == null) {
			return false;
		}

		final UUID localUuid = player.getUuid();
		final boolean[] isDriver = new boolean[1];
		vehicle.vehicleExtraData.iterateRidingEntities(ridingEntity -> {
			if (ridingEntity.isDriver() && localUuid.equals(ridingEntity.uuid)) {
				isDriver[0] = true;
			}
		});
		return isDriver[0];
	}
}
