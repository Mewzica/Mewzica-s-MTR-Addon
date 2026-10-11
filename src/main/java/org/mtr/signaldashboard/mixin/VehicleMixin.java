package org.mtr.signaldashboard.mixin;

import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.drive.ReverseDrive;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 反相器（换向手柄）的实现：三档 {@code F / N / R}。
 *
 * <ul>
 *     <li><b>F</b> 前进位 —— 什么都不做，列车按 MTR 自己的逻辑走；</li>
 *     <li><b>N</b> 中间位 —— <b>切断牵引</b>：位置钉住、速度清零，推油门也不走；</li>
 *     <li><b>R</b> 后退位 —— 把列车的位置增量取反（倒着走）。</li>
 * </ul>
 *
 * <h2>注入点为什么是两个方法</h2>
 * <ul>
 *     <li>{@code simulate} 的 <b>HEAD</b>：每 tick 更新一次本车的反相器位置
 *         （从 {@link ReverseDrive} 反查司机请求）；</li>
 *     <li>{@code simulateMoving} 的 <b>HEAD / RETURN</b>：包住整段行驶计算，
 *         在 RETURN 处把 {@code railProgress} 沿本 tick 的起点做镜像。</li>
 * </ul>
 *
 * <p><b>为什么必须挂在 {@code simulateMoving} 上，而不能挂在 {@code simulate} 的 RETURN 上</b>：
 * {@code simulate} 内部在调用完 {@code simulateMoving} 之后、返回之前，
 * 会先执行 {@code writeVehiclePositions(pathIndex, ...)} 把 {@code railProgress}
 * 换算成世界坐标广播出去。等 {@code simulate} 返回时位置早就发出去了，再改就晚了。
 * 挂在 {@code simulateMoving} 的 RETURN 上则正好卡在两者之间——镜像完的位置
 * 会被随后的 {@code writeVehiclePositions} 和 {@code getTimeAlongRoute} 一并采用。
 *
 * <h2>镜像的算法</h2>
 * <pre>
 * 记进入 simulateMoving 前的位置为 P0，MTR 算完后的位置为 P1（P1 &ge; P0，因为速度恒为正），
 * 则本 tick 的前进量 delta = P1 - P0；把它取反写回：
 *     railProgress = P0 - delta = 2 * P0 - P1
 * </pre>
 * 于是列车的加速曲线、限速、制动距离、车门与停站计时全都还是 MTR 自己那套，
 * 只是位移方向反了。
 *
 * <h2>客户端也必须一起镜像（★ 别再挡掉客户端）</h2>
 * MTR 的客户端会<b>自己做位置预测</b>：{@code MainRenderer} 每帧调用
 * {@code MinecraftClientData.vehicles.forEach(vehicle -> vehicle.simulate(millisElapsed))}
 * （见 {@code MainRenderer} 源码），所以客户端的 {@code railProgress} 是按<b>帧</b>
 * 往前推进的，再由 {@code PacketUpdateVehiclesLifts} 送来的服务端权威值做小幅修正。
 *
 * <p>如果只让服务端镜像、客户端不镜像，客户端的预测就还是<b>往前</b>走：
 * 每帧往前蹭一点，每 tick 又被服务端同步拽回去 —— 表现就是
 * 「位置往前蹭 + 抖动」，正是用户报的那个 bug。
 *
 * <p><b>镜像操作是相对的、可组合的</b>：{@code railProgress = 2 * P0 - P1}，
 * 其中 {@code P0} 是<b>本次调用</b>的起点。客户端在自己的 {@code P0} 上做同样的变换，
 * 得到的正是「服务端那一 tick 的镜像结果」的预测值，两边一致，
 * <b>不会</b>出现「反向走两步」。所以客户端也要走这段逻辑。
 *
 * <p>客户端每帧调用一次（{@code millisElapsed} 约为帧时长），服务端每 tick 一次
 * （50ms），两边各算各的，互不干扰。
 *
 * <h2>★ 客户端还必须跳过「行驶中不许换档」的联锁</h2>
 * 光让客户端镜像还不够。MTR 每次同步都会把客户端的 {@code VehicleExtension}
 * <b>换成新实例</b>（{@code PacketUpdateVehiclesLifts} 里直接 {@code new}），
 * 于是 {@code @Unique} 字段 {@code mewsmtr$reverser} 每次都回到默认的
 * {@code FORWARD}。这时若联锁因为「车在动」而拒绝把 F 改成 R，客户端就<b>永远</b>
 * 拿不到 R —— 实测日志里服务端一路 {@code mirroring}，客户端只有孤零零两条。
 *
 * <p>所以联锁只在服务端生效（权威兜底）。客户端只需要知道「司机的手柄在哪一档」，
 * 输入侧的联锁由 {@code DriveInput} 负责（它按车辆速度挡住按键）。
 *
 * <p><b>关于 {@code remap = false}</b>：{@code org.mtr.core.*} 是 MTR 自己的类、
 * 不参与 Minecraft 的混淆，必须关掉重映射，否则注解处理器查不到映射会直接编译失败。
 *
 * @author Mewzica
 */
@Mixin(value = Vehicle.class, remap = false)
public class VehicleMixin {

	/**
	 * MTR 标记「这是客户端副本」的字段。final 字段必须配 {@code @Final} 才符合 Mixin 的校验。
	 *
	 * <p>只用来给日志加 {@code [client]} / {@code [server]} 前缀 —— 单人游戏两边同 JVM，
	 * 日志混在一起看不出是谁打的。<b>不再</b>用它挡掉客户端（见类注释）。
	 */
	@Shadow(remap = false)
	@Final
	private boolean isClientside;

	/** 本车反相器档位。由 {@code simulate} 的 HEAD 每 tick 刷新。 */
	@Unique
	private ReverseDrive.Reverser mewsmtr$reverser = ReverseDrive.Reverser.FORWARD;

	/** 本 tick 进入 {@code simulateMoving} 之前的 {@code railProgress}，镜像 / 冻结的基准。 */
	@Unique
	private double mewsmtr$progressBeforeMove;

	/** 本 tick 实际生效的档位（在 HEAD 缓存下来，避免 RETURN 时重复反查）。 */
	@Unique
	private ReverseDrive.Reverser mewsmtr$appliedThisMove = ReverseDrive.Reverser.FORWARD;

	/** 诊断用：上一次已经把「换向被联锁挡住」打进日志了，避免行驶期间每 tick 刷屏。 */
	@Unique
	private boolean mewsmtr$loggedBlocked;

	/**
	 * 诊断用：镜像日志的时间节流（毫秒）。
	 *
	 * <p>不能按「每 N 次调用打一条」来节流 —— 客户端每 tick 都会被换成新实例，
	 * 实例字段计数器跟着归零，结果退化成每 tick 刷一条。所以用静态时间戳。
	 */
	@Unique
	private static final long MIRROR_LOG_INTERVAL_MILLIS = 1000L;

	/** 上一次打镜像日志的时间戳；客户端与服务端共用这一个节流。 */
	@Unique
	private static long mewsmtr$lastMirrorLogMillis;

	@SuppressWarnings("rawtypes")
	@Inject(method = "simulate", at = @At("HEAD"), remap = false)
	private void mewsmtr$updateReverser(long millisElapsed, ObjectArrayList vehiclePositions, Long2LongAVLTreeMap vehicleTimesAlongRoute, CallbackInfo callbackInfo) {
		final ReverseDrive.Reverser requested = ReverseDrive.resolveReverser((Vehicle) (Object) this);
		if (requested == null) {
			// 没司机 / 上报超时 —— 一律回前进位
			if (mewsmtr$reverser != ReverseDrive.Reverser.FORWARD) {
				mewsmtr$reverser = ReverseDrive.Reverser.FORWARD;
				mewsmtr$loggedBlocked = false;
				MTRSignalDashboard.LOGGER.info("{} Reverser released on vehicle {} -> FORWARD ({})", mewsmtr$side(), mewsmtr$vehicleTag(), ReverseDrive.describeFailure((Vehicle) (Object) this));
			}
			return;
		}

		// 联锁：只有停稳时才接受换档，行驶中保持原档位不变。
		//
		// ★★ 客户端必须跳过这一关。
		// MTR 每次数据同步都会把客户端的 VehicleExtension 换成**新实例**
		// （PacketUpdateVehiclesLifts 里直接 new VehicleExtension(...)），
		// 于是 @Unique 字段 mewsmtr$reverser 每次都回到默认的 FORWARD。
		// 如果这里再因为「车在动」而拒绝把 F 改成 R，客户端就永远拿不到 R，
		// 于是客户端继续按 MTR 的方式往前预测、服务端却在往后同步 ——
		// 画面表现就是「车往前蹭 / 倒车方向是反的」。
		//
		// 输入侧的联锁在 DriveInput 里已经判过一次（停稳才允许换档），
		// 服务端这一关才是权威兜底；客户端不需要重复判断，它只需要
		// 「司机的手柄现在在哪一档」这个事实。
		final double speed = ((VehicleSchemaAccessor) (Object) this).mewsmtr$getSpeed();
		if (isClientside || speed <= ReverseDrive.STOPPED_SPEED) {
			if (mewsmtr$reverser != requested) {
				mewsmtr$reverser = requested;
				// 客户端这次「变化」多半只是新实例的默认值被覆盖，DriveInput 已经
				// 在按键那一刻打过 [client] Reverser -> X 了，这里只让服务端出声。
				if (!isClientside) {
					MTRSignalDashboard.LOGGER.info("{} Reverser set to {} on vehicle {} (speed={})", mewsmtr$side(), requested.letter, mewsmtr$vehicleTag(), speed);
				}
			}
			mewsmtr$loggedBlocked = false;
		} else if (mewsmtr$reverser != requested && !mewsmtr$loggedBlocked) {
			// 只打一次：行驶中每 tick 都会走到这里，否则日志会被刷爆
			mewsmtr$loggedBlocked = true;
			MTRSignalDashboard.LOGGER.info("{} Reverser change to {} refused on vehicle {}: still moving (speed={})", mewsmtr$side(), requested.letter, mewsmtr$vehicleTag(), speed);
		}
	}

	/** 日志前缀：单人游戏客户端与服务端同 JVM，日志混在一起要靠这个区分。 */
	@Unique
	private String mewsmtr$side() {
		return isClientside ? "[client]" : "[server]";
	}

	/** 日志里区分车辆用的短标识；同一辆车在一次运行内稳定，不追求跨重启一致。 */
	@Unique
	private String mewsmtr$vehicleTag() {
		return Integer.toHexString(System.identityHashCode(this));
	}

	@SuppressWarnings("rawtypes")
	@Inject(method = "simulateMoving", at = @At("HEAD"), remap = false)
	private void mewsmtr$beforeMove(long millisElapsed, ObjectArrayList vehiclePositions, int pathIndex, CallbackInfo callbackInfo) {
		mewsmtr$appliedThisMove = mewsmtr$reverser;
		if (mewsmtr$appliedThisMove != ReverseDrive.Reverser.FORWARD) {
			// N 要钉住位置、R 要镜像位置，两者都需要本 tick 的起点
			mewsmtr$progressBeforeMove = ((VehicleSchemaAccessor) (Object) this).mewsmtr$getRailProgress();
		}
	}

	@SuppressWarnings("rawtypes")
	@Inject(method = "simulateMoving", at = @At("RETURN"), remap = false)
	private void mewsmtr$applyReverser(long millisElapsed, ObjectArrayList vehiclePositions, int pathIndex, CallbackInfo callbackInfo) {
		final ReverseDrive.Reverser applied = mewsmtr$appliedThisMove;
		if (applied == ReverseDrive.Reverser.FORWARD) {
			return;
		}

		final VehicleSchemaAccessor schema = (VehicleSchemaAccessor) (Object) this;
		final double progressAfterMove = schema.mewsmtr$getRailProgress();
		final double delta = progressAfterMove - mewsmtr$progressBeforeMove;

		// ★ 位移合理性检查 —— 防止把 MTR 自己的「吸附跳变」镜像成一次瞬移。
		//
		// MTR 的 simulateMoving 末尾有一句上界吸附：
		//     railProgress += speed * millisElapsed;
		//     if (railProgress >= stoppingPoint) { railProgress = stoppingPoint; speed = 0; ... }
		// 正常行驶时 delta ≈ speed * millisElapsed；一旦这句吸附触发（换档瞬间、
		// 停站判定变化、帧时长抖动），delta 会突然变成一个很大的值。
		// 那种情况下再镜像（2 * P0 - P1）等于把跳变放大一倍，画面上就是「列车瞬移」。
		// 所以：位移不合理时本 tick 不镜像，原样交给 MTR。
		final double expected = Math.abs(schema.mewsmtr$getSpeed()) * millisElapsed;
		if (delta <= 0.0D || delta > expected * 3.0D + 0.5D) {
			final long now = System.currentTimeMillis();
			if (now - mewsmtr$lastMirrorLogMillis >= MIRROR_LOG_INTERVAL_MILLIS) {
				mewsmtr$lastMirrorLogMillis = now;
				MTRSignalDashboard.LOGGER.info("{} Reverser skipped mirroring on vehicle {}: implausible delta {} (expected about {})", mewsmtr$side(), mewsmtr$vehicleTag(), delta, expected);
			}
			return;
		}

		if (applied == ReverseDrive.Reverser.NEUTRAL) {
			// N 档：切断牵引 —— 位置钉住、速度清零。MTR 下一 tick 会因此改走
			// simulateStopped，但每 tick 都会被这里再钉一次，净效果就是「推油门也不走」。
			schema.mewsmtr$setRailProgress(mewsmtr$progressBeforeMove);
			schema.mewsmtr$setSpeed(0.0D);
			return;
		}

		// R 档：把本 tick 的位移取反
		final double mirrored = 2.0D * mewsmtr$progressBeforeMove - progressAfterMove;

		final long now = System.currentTimeMillis();
		if (now - mewsmtr$lastMirrorLogMillis >= MIRROR_LOG_INTERVAL_MILLIS) {
			mewsmtr$lastMirrorLogMillis = now;
			MTRSignalDashboard.LOGGER.info("{} Reverser mirroring on vehicle {}: railProgress {} -> {} (mirrored to {})", mewsmtr$side(), mewsmtr$vehicleTag(), mewsmtr$progressBeforeMove, progressAfterMove, mirrored);
		}

		if (mirrored <= 0.0D) {
			// 已经退到路径起点：钉在 0 并清掉速度，
			// 免得速度表还在跑、车却一动不动（看着像卡住了）
			schema.mewsmtr$setRailProgress(0.0D);
			schema.mewsmtr$setSpeed(0.0D);
		} else {
			schema.mewsmtr$setRailProgress(mirrored);
		}
	}
}
