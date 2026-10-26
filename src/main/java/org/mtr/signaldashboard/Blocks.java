package org.mtr.signaldashboard;

import org.mtr.mapping.holder.Block;
import org.mtr.mapping.mapper.BlockHelper;
import org.mtr.mapping.registry.BlockRegistryObject;
import org.mtr.signaldashboard.block.BlockTpwsGrid;

/**
 * 方块注册。
 *
 * <p>目前只注册一个方块：<b>TPWS 感应网格</b>（{@code tpws_grid}）。它是铺在轨道
 * 两轨之间地面上的装饰性薄片，模型来自 Blockbench（{@code models/block/tpws_grid.json}），
 * 厚度只有 1 像素，所以：
 * <ul>
 *     <li>{@link BlockHelper#createBlockSettings(boolean, boolean)} 的第二个参数传
 *         {@code true}（非不透明），否则薄片会被当成整格实体方块遮光；</li>
 *     <li>再叠一个 {@code noCollision()} —— 1 像素的薄片如果给整格碰撞，
 *         玩家走过去会被一格高的空气墙绊住。</li>
 * </ul>
 *
 * <p>和物品一样，这里用的也是<b>本模组自己的</b> {@link MTRSignalDashboard#REGISTRY}，
 * 原因见主类注释里的踩坑记录。
 *
 * <p>方块本体是 {@link BlockTpwsGrid}，带水平朝向（{@code facing}），
 * 放置时会自动转向玩家面朝的方向。
 *
 * @author Mewzica
 */
public final class Blocks {

	/** TPWS 感应网格方块（含对应的方块物品）。 */
	public static final BlockRegistryObject TPWS_GRID = MTRSignalDashboard.REGISTRY.registerBlockWithBlockItem(
			MTRSignalDashboard.identifier("tpws_grid"),
			() -> new Block(new BlockTpwsGrid(BlockHelper.createBlockSettings(true, true).strength(2.0F).noCollision())),
			org.mtr.mod.CreativeModeTabs.CORE
	);

	private Blocks() {
	}

	public static void init() {
		// 触发类加载，完成静态字段中的注册（实际落库由 REGISTRY.init() 完成）
		MTRSignalDashboard.LOGGER.info("Registered block: tpws_grid");
	}
}
