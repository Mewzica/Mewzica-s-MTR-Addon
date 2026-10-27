package org.mtr.signaldashboard;

import org.mtr.mapping.registry.BlockEntityTypeRegistryObject;
import org.mtr.signaldashboard.block.BlockEntityTpwsGrid;

/**
 * 方块实体类型注册。
 *
 * <p>只有一个：{@code mewsmtr:tpws_grid} 对应的 {@link BlockEntityTpwsGrid}。
 *
 * <h2>为什么可以放心地直接引用 {@link Blocks#TPWS_GRID}</h2>
 * {@code Registry.registerBlockEntityType(...)} 内部会<b>立即</b>调用
 * {@code FabricBlockEntityTypeBuilder.create(factory, blocks)}，也就是说
 * 传入的 {@code Supplier<Block>} 在这一刻就被求值了 —— 而 {@code Registry.init()}
 * 还没跑。之所以不会拿到 {@code null}，是因为 {@code BlockRegistryObject}
 * 构造时就持有已经 new 好的 {@code Block} 实例，延迟的只是「登记进
 * {@code Registries.BLOCK}」这一步，不是「创建方块对象」。
 *
 * <p>换句话说：方块对象早就存在，只是还没上户口；方块实体类型现在就要用它上户口，
 * 完全拿得到。
 *
 * @author Mewzica
 */
public final class BlockEntityTypes {

	/** TPWS 线圈的方块实体类型。 */
	public static final BlockEntityTypeRegistryObject<BlockEntityTpwsGrid> TPWS_GRID = MTRSignalDashboard.REGISTRY.registerBlockEntityType(
			MTRSignalDashboard.identifier("tpws_grid"),
			BlockEntityTpwsGrid::new,
			() -> Blocks.TPWS_GRID.get()
	);

	private BlockEntityTypes() {
	}

	public static void init() {
		// 触发类加载，完成静态字段中的注册（实际落库由 REGISTRY.init() 完成）
		MTRSignalDashboard.LOGGER.info("Registered block entity type: tpws_grid");
	}
}
