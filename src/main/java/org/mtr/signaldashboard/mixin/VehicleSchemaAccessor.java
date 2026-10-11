package org.mtr.signaldashboard.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code VehicleSchema} 里几个 protected 字段的读写口子。
 *
 * <p>这些字段 MTR 一个 public getter 都没给（{@code getDepartureIndex()} /
 * {@code PathData.getDwellTime()} 是 public 的，唯独下面这几个不是）。
 * 用 Mixin 的 {@code @Accessor} 生成访问器，比反射稳——编译期就校验字段存在。
 *
 * <p>三个字段的用途：
 * <ul>
 *     <li>{@code elapsedDwellTime} —— 发车倒计时：{@code 剩余 = 发车阈值 - elapsedDwellTime}；</li>
 *     <li>{@code railProgress} —— 车辆沿整条路径已行驶的<b>弧长（方块）</b>，是车辆位置的唯一来源。
 *         倒车就是把它改小，见 {@code ReverseDrive}；</li>
 *     <li>{@code speed} —— 当前速度，单位是<b>方块/毫秒</b>（不是 km/h！见
 *         {@code PathData.getSpeedLimitMetersPerMillisecond}）。</li>
 * </ul>
 *
 * <p>注意 {@code VehicleSchema} 是 MTR 自己生成的 schema 类，字段名不做混淆，
 * 所以字段名字符串就是运行期真实的字段名。
 * 也正因为不混淆，这里必须写 {@code remap = false}——否则注解处理器会拿
 * Minecraft 的映射表去查这个名字，查不到就报
 * {@code Unable to locate obfuscation mapping for @Accessor target}。
 *
 * @author Mewzica
 */
@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleSchemaAccessor {

	/** @return 车辆在当前停靠点已经停留的毫秒数。 */
	@Accessor(value = "elapsedDwellTime", remap = false)
	long mewsmtr$getElapsedDwellTime();

	/** @return 车辆沿整条路径已行驶的弧长（方块）。 */
	@Accessor(value = "railProgress", remap = false)
	double mewsmtr$getRailProgress();

	/** 直接改写车辆在路径上的位置。倒车靠它。 */
	@Accessor(value = "railProgress", remap = false)
	void mewsmtr$setRailProgress(double railProgress);

	/** @return 当前速度（方块/毫秒）。 */
	@Accessor(value = "speed", remap = false)
	double mewsmtr$getSpeed();

	/** 直接改写速度。倒车时把它压成 0，让 MTR 走「停站」分支不再往前推。 */
	@Accessor(value = "speed", remap = false)
	void mewsmtr$setSpeed(double speed);
}
