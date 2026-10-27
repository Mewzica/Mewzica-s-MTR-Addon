package org.mtr.signaldashboard.mixin;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.mapping.holder.BlockEntityAbstractMapping;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.signaldashboard.data.TpwsCoilRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让被 TPWS 接管的信号机<b>完全脱离</b> MTR 的信号显示逻辑。
 *
 * <h2>MTR 原本怎么决定亮不亮</h2>
 * {@code RenderSignalBase} 每帧做这么一件事：
 * <pre>
 *   colors   = blockEntity.getSignalColors(side)        // 这台信号机负责哪些颜色
 *   occupied = aspectState.occupiedColors ∩ colors ≠ ∅  // 其中有没有被闭塞占用的
 *   aspect   = blockEntity.getActualAspect(occupied, side)
 *   render(..., aspect, side)
 * </pre>
 * 而 {@code getActualAspect} 的原始实现是：
 * <ul>
 *     <li>{@code occupied} → 记下时间，返回 <b>1</b>（红灯）；</li>
 *     <li>否则看距上次占用多久：&lt; 2 秒返回 2，2~4 秒返回 3，<b>超过 4 秒返回 0（灭灯）</b>。</li>
 * </ul>
 * 也就是说 MTR 的信号机<b>平时是灭的</b>，只有列车压上去的那几秒才亮 ——
 * 这是「和 TPWS 连上了但灯不亮」的第二层原因（第一层是信号机没配颜色）。
 *
 * <h2>接管后</h2>
 * 只要这台信号机被某个 TPWS 线圈当作联锁对象，亮灭就完全由本模组决定：
 * <ul>
 *     <li>该线圈范围内有列车 → <b>1</b>（红）；</li>
 *     <li>没有列车 → <b>2</b>（两灯信号机是绿灯，三/四灯是黄灯）。</li>
 * </ul>
 * 注意这里<b>不再看 MTR 算出来的 {@code occupied}</b>：那个值的含义是
 * 「这台信号机配置的颜色里有没有被占用的」，而 TPWS 接管的前提就是
 * 玩家不想去配 MTR 那套颜色。继续用它会导致信号机永远读不到占用、
 * 永远显示绿灯，等于没接管。
 *
 * <p>返回 2 而不是 3 是刻意的：{@code aspect} 必须小于信号机的灯数
 * （{@code RenderSignalBase} 里有 {@code aspect < aspects} 的边界判断），
 * 而这里拿不到 {@code aspects}；2 对所有型号都安全，且在两灯信号机上正好是绿灯。
 *
 * <p>「线圈范围内有没有车」用的是客户端自己那份车辆表
 * （{@code MinecraftClientData.vehicles}），不依赖服务端同步 —— 渲染本来就在客户端做，
 * 用本地预测的位置最跟手。
 *
 * <p><b>这个方法只在客户端被调用</b>（唯一的调用方 {@code RenderSignalBase} 是渲染类），
 * 所以这里引用客户端类型是安全的；专用服务器上方法体不会执行，也就不会触发类加载。
 *
 * @author Mewzica
 */
@Mixin(BlockSignalBase.BlockEntityBase.class)
public abstract class SignalBlockEntityMixin {

	/** 红灯：线圈范围内有列车。 */
	private static final int ASPECT_OCCUPIED = 1;

	/** 绿灯（两灯型号）/ 黄灯（三、四灯型号）：TPWS 已接管且线圈范围内无车。 */
	private static final int ASPECT_CLEAR = 2;

	/** 判定「线圈范围内有车」的水平半径（方块），和刹车判定用的量级一致。 */
	private static final double DETECT_RADIUS = 3.0D;

	/**
	 * 接管后用来占位的颜色。
	 *
	 * <p>{@code RenderSignalBase} 是<b>按颜色</b>遍历着画灯的：{@code getSignalColors}
	 * 返回空集时，那台信号机一个灯都不会画 —— 光改 {@code getActualAspect} 没用。
	 * 所以被接管时必须给它一个非空的颜色集合，哪怕我们根本不看这个颜色值。
	 *
	 * <p>选绿色是因为 MTR 还会用这个颜色画一个小色块指示器（{@code colorIndicatorHeight}），
	 * 绿色在那里看着最正常。
	 *
	 * <p><b>不要修改这个对象</b> —— 它是所有被接管信号机共用的。
	 */
	private static final IntAVLTreeSet FALLBACK_COLORS = new IntAVLTreeSet(new int[]{0x00FF00});

	/**
	 * 被接管的信号机，若没有配置任何颜色，就给它一个占位颜色。
	 *
	 * <p>这样做的副作用：MTR 自己的信号机配置界面会显示这个颜色。
	 * 被 TPWS 接管的信号机本来就不该再用 MTR 那套配置，可以接受。
	 */
	@Inject(method = "getSignalColors", at = @At("RETURN"), cancellable = true, remap = false)
	private void mewsmtr$ensureColor(boolean secondSide, CallbackInfoReturnable<IntAVLTreeSet> callbackInfo) {
		final IntAVLTreeSet colors = callbackInfo.getReturnValue();
		if (colors != null && !colors.isEmpty()) {
			return;
		}

		final BlockPos signalPos = ((BlockEntityAbstractMapping) (Object) this).getPos2();
		if (signalPos == null || TpwsCoilRegistry.findBySignal(signalPos.asLong()) == null) {
			return;
		}

		callbackInfo.setReturnValue(FALLBACK_COLORS);
	}

	@Inject(method = "getActualAspect", at = @At("HEAD"), cancellable = true, remap = false)
	private void mewsmtr$overrideAspect(boolean occupied, boolean secondSide, CallbackInfoReturnable<Integer> callbackInfo) {
		final BlockPos signalPos = ((BlockEntityAbstractMapping) (Object) this).getPos2();
		if (signalPos == null) {
			return;
		}

		final TpwsCoilRegistry.Coil coil = TpwsCoilRegistry.findBySignal(signalPos.asLong());
		if (coil == null) {
			// 没被任何线圈接管：保持 MTR 原本的亮灭逻辑
			return;
		}

		callbackInfo.setReturnValue(isCoilOccupied(coil) ? ASPECT_OCCUPIED : ASPECT_CLEAR);
	}

	/** 线圈水平范围内是否有列车（只看车头，和刹车判定保持一致）。 */
	private static boolean isCoilOccupied(TpwsCoilRegistry.Coil coil) {
		final MinecraftClientData data = MinecraftClientData.getInstance();
		if (data == null) {
			return false;
		}

		final BlockPos coilPos = BlockPos.fromLong(coil.blockPos());
		final double centerX = coilPos.getX() + 0.5D;
		final double centerZ = coilPos.getZ() + 0.5D;
		final double radiusSquared = DETECT_RADIUS * DETECT_RADIUS;

		for (final VehicleExtension vehicle : data.vehicles) {
			final Vector head = vehicle.getHeadPosition();
			if (head == null) {
				continue;
			}
			final double dx = centerX - head.x;
			final double dz = centerZ - head.z;
			if (dx * dx + dz * dz <= radiusSquared) {
				return true;
			}
		}

		return false;
	}
}
