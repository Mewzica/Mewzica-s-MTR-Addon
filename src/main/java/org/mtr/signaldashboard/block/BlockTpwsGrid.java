package org.mtr.signaldashboard.block;

import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockSettings;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.ItemPlacementContext;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;

import java.util.List;

/**
 * TPWS 感应网格方块：带水平朝向（{@code facing}）的薄片。
 *
 * <h2>朝向怎么做</h2>
 * MTR 的映射层把原版 {@code StateManager.Builder} 封掉了（{@code appendProperties} 是
 * {@code final}），加方块属性的唯一入口是 {@link DirectionHelper} 那套：
 * <ol>
 *     <li>{@link #addBlockProperties(List)} 里把 {@link DirectionHelper#FACING} 加进属性表 ——
 *         这一步决定方块状态里<b>真的</b>有 {@code facing}；</li>
 *     <li>构造函数里用 {@link #withFacing} 设默认值，否则默认状态没有该属性，
 *         第一次放置就会抛 {@code IllegalArgumentException}；</li>
 *     <li>{@link #getPlacementState2} 里按玩家朝向取反，让正面朝着玩家。</li>
 * </ol>
 *
 * <h2>为什么要那个 unchecked 的 {@code withFacing}</h2>
 * {@link DirectionHelper#FACING} 是 {@code DirectionProperty}，而 {@code BlockState.with}
 * 只接受 {@code Property<T>}，两者在映射层里是两个类。MTR 自己的方块也是靠
 * {@code new Property<>(FACING.data)} 转的。另外泛型 {@code T} 是<b>原版</b>的
 * {@code net.minecraft.util.math.Direction}，不是映射层的 {@code holder.Direction}，
 * 所以值要取 {@code direction.data}——直接传 holder 枚举会在运行时校验失败。
 *
 * @author Mewzica
 */
public class BlockTpwsGrid extends BlockExtension implements DirectionHelper {

	/**
	 * 碰撞箱 / 选取框：和模型完全一致的一块薄板。
	 *
	 * <p>模型（{@code models/block/tpws_grid.json}）占满整格 x/z，高度只从 0.75 到 1.75 像素，
	 * 所以这里也用同样的六个坐标（单位是 1/16 格）。模型改了这里要跟着改。
	 */
	private static final VoxelShape SHAPE = Block.createCuboidShape(0.0D, 0.75D, 0.0D, 16.0D, 1.75D, 16.0D);

	public BlockTpwsGrid(BlockSettings blockSettings) {
		super(blockSettings);
		setDefaultState2(withFacing(getDefaultState2(), Direction.NORTH));
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(DirectionHelper.FACING);
	}

	@Override
	public BlockState getPlacementState2(ItemPlacementContext itemPlacementContext) {
		// 和原版箱子/熔炉一致：正面朝向玩家
		return withFacing(getDefaultState2(), itemPlacementContext.getPlayerFacing().getOpposite());
	}

	/** 鼠标指向时的黑框：贴合薄板，而不是整格立方体。 */
	@Override
	public VoxelShape getOutlineShape2(BlockState blockState, BlockView blockView, BlockPos blockPos, ShapeContext shapeContext) {
		return SHAPE;
	}

	/** 实际碰撞：同样是这块薄板，所以可以站在上面、但不会有一格高的空气墙。 */
	@Override
	public VoxelShape getCollisionShape2(BlockState blockState, BlockView blockView, BlockPos blockPos, ShapeContext shapeContext) {
		return SHAPE;
	}

	/**
	 * 把 {@code facing} 写进方块状态。
	 *
	 * <p>泛型擦除后 {@code with} 的第二个参数退化成 {@code Comparable}，所以这里用原始类型
	 * 绕开 {@code DirectionProperty} 与 {@code Property<T>} 之间的类型鸿沟 —— MTR 的
	 * 映射层没有为这种情况提供公开的转换方法。
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState withFacing(BlockState blockState, Direction facing) {
		return blockState.with((Property) new Property<>(DirectionHelper.FACING.data), facing.data);
	}
}
