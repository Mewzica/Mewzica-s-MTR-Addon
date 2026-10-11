package org.mtr.signaldashboard.mixin;

import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.DrivingGuiRenderer;
import org.mtr.signaldashboard.client.DrivingHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 把 MTR 驾驶 HUD 里的「当前驾驶车辆」接出来，并在它画完之后叠加我们自己的信息。
 *
 * <h2>为什么必须注入而不是自己注册 HUD 回调</h2>
 * MTR 的 {@code DrivingGuiRenderer} 是这样工作的：
 * <ul>
 *     <li>{@code VehicleExtension.simulate()} 在玩家<b>正乘坐该车</b>时调用
 *         {@code DrivingGuiRenderer.setVehicle(this)}，把车辆存进私有静态字段
 *         {@code vehicle}；</li>
 *     <li>{@code render()} 里用这个字段决定要不要画速度表，<b>并在方法末尾把它置回
 *         {@code null}</b>。</li>
 * </ul>
 * 所以我们没法在渲染之后再去读那个字段（已经是 {@code null} 了）。
 * 这里在 {@code render} 的 HEAD 先把车辆<b>抄一份</b>到自己手里，
 * 在 RETURN 再画——此时 MTR 的矩阵 push/pop 已经配平，坐标状态是干净的。
 *
 * <p>好处是「什么时候该显示」完全跟着 MTR 走：只有当玩家拿着司机钥匙坐在车里
 * （{@code vehicle != null} 且屏幕未打开）时 MTR 才会真正执行渲染体，
 * 我们不必重复实现一遍判定条件。
 *
 * <p><b>关于 {@code remap = false}</b>：Mixin 注解处理器默认会拿 Minecraft 的混淆
 * 映射表去重映射注入目标。但 {@code org.mtr.mod.*} 是 MTR 自己的类，
 * <b>不参与混淆</b>，映射表里当然查不到，于是报
 * {@code Unable to locate obfuscation mapping for @Inject target render}。
 * 关掉重映射即可（这也是注入非 Minecraft 类时的标准做法）。
 *
 * @author Mewzica
 */
@Mixin(value = DrivingGuiRenderer.class, remap = false)
public class DrivingGuiRendererMixin {

	/** MTR 私有的当前驾驶车辆字段。 */
	@Shadow(remap = false)
	@Nullable
	private static VehicleExtension vehicle;

	@Inject(method = "render", at = @At("HEAD"), remap = false)
	private static void mewsmtr$captureVehicle(GraphicsHolder graphicsHolder, CallbackInfo callbackInfo) {
		DrivingHud.setVehicle(vehicle);
	}

	@Inject(method = "render", at = @At("RETURN"), remap = false)
	private static void mewsmtr$renderExtraHud(GraphicsHolder graphicsHolder, CallbackInfo callbackInfo) {
		DrivingHud.render(graphicsHolder);
	}
}
