package org.mtr.signaldashboard.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.generated.data.NameColorDataBaseSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code NameColorDataBaseSchema} 里那个 {@code protected final Data data}。
 *
 * <p>TPWS 的刹车判定跑在 MTR 的<b>模拟线程</b>里，手上只有一个
 * {@code org.mtr.core.data.Vehicle}。要判断「绑定的信号机是不是红的」、
 * 「这条线的限速是多少」，就必须拿到 MTR 的 {@link Data}
 * （{@code rails} / {@code sidings} 都在里面）。
 *
 * <p>MTR 没有给任何 public 的 {@code getData()}，但这个字段是<b>所有</b>
 * {@code NameColorDataBase} 子类共有的（{@code Vehicle}、{@code Rail}、
 * {@code Siding}、{@code Platform} 都继承它），所以挂一次 accessor 就全都能用。
 *
 * <p>{@code remap = false} 的原因和 {@link VehicleSchemaAccessor} 一样：
 * {@code org.mtr.core.*} 是 MTR 自己的类，不参与 Minecraft 混淆，
 * 字段名 {@code data} 就是运行期真实名字。
 *
 * @author Mewzica
 */
@Mixin(value = NameColorDataBaseSchema.class, remap = false)
public interface NameColorDataBaseSchemaAccessor {

	/** @return 该数据对象所属的 MTR 数据集（服务端是 {@code Simulator} 的实例）。 */
	@Accessor(value = "data", remap = false)
	Data mewsmtr$getData();
}
