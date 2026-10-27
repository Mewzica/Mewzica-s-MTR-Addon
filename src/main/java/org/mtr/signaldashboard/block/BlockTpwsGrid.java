package org.mtr.signaldashboard.block;

import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockHitResult;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockSettings;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.Item;
import org.mtr.mapping.holder.ItemPlacementContext;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.Init;
import org.mtr.signaldashboard.data.TpwsCoilRegistry;
import org.mtr.signaldashboard.packet.PacketOpenTpwsConfig;

import java.util.List;

/**
 * TPWS 线圈（{@code mewsmtr:tpws_grid}）。
 *
 * <p>现实中它是铺在两根钢轨之间地面上的两道感应回路（Arming / Trigger）。
 * 这里用一块 1 像素厚、带水平朝向的薄片表示，模型来自 Blockbench。
 *
 * <h2>交互</h2>
 * <ul>
 *     <li><b>手持 MTR 的刷子右键</b> → 打开配置界面（超速阈值 / 信号联锁）。
 *         用刷子而不是空手，是为了不和「直接放方块 / 破坏方块」冲突，
 *         而且刷子在 MTR 里本来就是「编辑已放置对象」的通用工具。</li>
 *     <li><b>手持 TPWS 连接器右键</b> → 把之前选中的信号机绑到这枚线圈上，
 *         见 {@code ItemTpwsLinker}。</li>
 * </ul>
 *
 * <h2>为什么必须实现 {@link BlockWithEntity}</h2>
 * 这个接口同时解决两件事：
 * <ol>
 *     <li>提供 {@code createBlockEntity} 的桥接（MTR 的 {@code BlockAbstractMapping}
 *         没有暴露 {@code Block#createBlockEntity}）；</li>
 *     <li>提供一个默认的 {@code BlockEntityTicker}，让
 *         {@link BlockEntityTpwsGrid#blockEntityTick()} 真的被调用 ——
 *         我们靠它做「首次登记进全局索引」。少了它，方块实体永远收不到 tick。</li>
 * </ol>
 *
 * <h2>碰撞箱</h2>
 * 和模型同尺寸（y 1.25~2.25）。注意 {@code Blocks} 里<b>不能</b>叠
 * {@code noCollision()} —— 那会把 {@code collidable} 置 false，
 * 下面重写的 {@code getCollisionShape2} 就失效了。
 *
 * @author Mewzica
 */
public class BlockTpwsGrid extends BlockExtension implements DirectionHelper, BlockWithEntity {

	/** 和模型完全一致的薄板（模型 y 从 1.25 到 2.25）。 */
	private static final VoxelShape SHAPE = Block.createCuboidShape(0.0D, 1.25D, 0.0D, 16.0D, 2.25D, 16.0D);

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
		return withFacing(getDefaultState2(), itemPlacementContext.getPlayerFacing().getOpposite());
	}

	@Override
	public VoxelShape getOutlineShape2(BlockState blockState, BlockView blockView, BlockPos blockPos, ShapeContext shapeContext) {
		return SHAPE;
	}

	@Override
	public VoxelShape getCollisionShape2(BlockState blockState, BlockView blockView, BlockPos blockPos, ShapeContext shapeContext) {
		return SHAPE;
	}

	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new BlockEntityTpwsGrid(blockPos, blockState);
	}

	// ------------------------------------------------------------------
	// 交互
	// ------------------------------------------------------------------

	@Override
	public ActionResult onUse2(BlockState blockState, World world, BlockPos blockPos, PlayerEntity playerEntity, Hand hand, BlockHitResult blockHitResult) {
		if (!isBrush(playerEntity.getStackInHand(hand))) {
			// 不是刷子：交给别的物品自己处理（比如连接器要在服务端绑定信号机）
			return ActionResult.PASS;
		}

		if (!world.isClient()) {
			final BlockEntityTpwsGrid coil = getCoil(world, blockPos);
			if (coil != null) {
				PacketOpenTpwsConfig.sendToPlayer(world, playerEntity, blockPos, coil.getConfig());
			}
		}
		return ActionResult.SUCCESS;
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	/**
	 * 方块被替换 / 破坏时把索引记录摘掉。
	 *
	 * <p>这里<b>不</b>判断新旧状态是否同一个方块 —— 朝向变化也会走到这里，
	 * 但那只会让索引少一条记录，下一个 tick 方块实体的
	 * {@code blockEntityTick} 会立刻补回来。相比之下，「方块真被挖掉但记录还在」
	 * 才是需要避免的情况（会对着空气刹车）。
	 */
	@Override
	public void onStateReplaced2(BlockState blockState, World world, BlockPos blockPos, BlockState newState, boolean moved) {
		if (!world.isClient()) {
			final BlockEntityTpwsGrid coil = getCoil(world, blockPos);
			if (coil != null) {
				coil.unregisterFromIndex();
			} else {
				// 方块实体已经没了（比如被爆炸直接抹掉），只能按坐标兜底摘除
				TpwsCoilRegistry.remove(Init.getWorldId(world), blockPos.asLong());
			}
		}
		super.onStateReplaced2(blockState, world, blockPos, newState, moved);
	}

	// ------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------

	/** 取出方块实体；不是本模组的就返回 {@code null}。 */
	private static BlockEntityTpwsGrid getCoil(World world, BlockPos blockPos) {
		final BlockEntity blockEntity = world.getBlockEntity(blockPos);
		return blockEntity != null && blockEntity.data instanceof BlockEntityTpwsGrid coil ? coil : null;
	}

	/**
	 * 判断手上拿的是不是 MTR 的刷子。
	 *
	 * <p>用 {@code .data ==} 而不是 {@code equals}：{@code Item} 是映射层的包装对象，
	 * 底层 {@code net.minecraft.item.Item} 才是注册表里的唯一实例，直接比底层对象最可靠。
	 */
	private static boolean isBrush(ItemStack itemStack) {
		if (itemStack == null || itemStack.isEmpty()) {
			return false;
		}
		final Item brush = org.mtr.mod.Items.BRUSH.get();
		return brush != null && itemStack.getItem().data == brush.data;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState withFacing(BlockState blockState, Direction facing) {
		return blockState.with((Property) new Property<>(DirectionHelper.FACING.data), facing.data);
	}
}
