package org.mtr.signaldashboard;

import org.mtr.mapping.registry.BlockEntityTypeRegistryObject;
import org.mtr.signaldashboard.block.BlockEntityRailNode;
import org.mtr.signaldashboard.block.BlockEntityTpwsGrid;

/**
 * 方块实体类型注册。
 *
 * <p>两个：
 * <ul>
 *     <li>{@code mewsmtr:tpws_grid} → {@link BlockEntityTpwsGrid}（本模组自己的线圈方块）；</li>
 *     <li>{@code mewsmtr:rail_node_tpws} → {@link BlockEntityRailNode}（挂在 MTR 的
 *         {@code mtr:rail} 上，见 {@code BlockNodeMixin}）。</li>
 * </ul>
 *
 * <h2>为什么可以放心地直接引用 {@link Blocks#TPWS_GRID} / {@code org.mtr.mod.Blocks.RAIL_NODE}</h2>
 * {@code Registry.registerBlockEntityType(...)} 内部会<b>立即</b>调用
 * {@code FabricBlockEntityTypeBuilder.create(factory, blocks)}，也就是说
 * 传入的 {@code Supplier<Block>} 在这一刻就被求值了 —— 而 {@code Registry.init()}
 * 还没跑。之所以不会拿到 {@code null}，是因为 {@code BlockRegistryObject}
 * 构造时就持有已经 new 好的 {@code Block} 实例，延迟的只是「登记进
 * {@code Registries.BLOCK}」这一步，不是「创建方块对象」。
 *
 * <p>换句话说：方块对象早就存在，只是还没上户口；方块实体类型现在就要用它上户口，
 * 完全拿得到。MTR 自己的 {@code Blocks} 也是同一套延迟注册，提前触发它的类初始化
 * 只是把「new 方块对象」提前了，不影响 MTR 之后的登记。
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

	/**
	 * 轨道节点上的 TPWS 方块实体类型。
	 *
	 * <p>注意绑定的方块是 <b>MTR 的</b> {@code mtr:rail}，不是本模组注册的方块。
	 * 方块实体类型只要求「这些方块会产出这种方块实体」，不要求方块属于自己 ——
	 * 这正是我们能用 mixin 给别人的方块挂数据的方式。
	 */
	public static final BlockEntityTypeRegistryObject<BlockEntityRailNode> RAIL_NODE = MTRSignalDashboard.REGISTRY.registerBlockEntityType(
			MTRSignalDashboard.identifier("rail_node_tpws"),
			BlockEntityRailNode::new,
			() -> org.mtr.mod.Blocks.RAIL_NODE.get()
	);

	private BlockEntityTypes() {
	}

	public static void init() {
		// 触发类加载，完成静态字段中的注册（实际落库由 REGISTRY.init() 完成）
		MTRSignalDashboard.LOGGER.info("Registered block entity types: tpws_grid, rail_node_tpws");
	}
}
