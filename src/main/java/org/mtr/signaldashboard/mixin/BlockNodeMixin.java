package org.mtr.signaldashboard.mixin;

import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockHitResult;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.Item;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mod.block.BlockNode;
import org.mtr.signaldashboard.block.BlockEntityRailNode;
import org.mtr.signaldashboard.data.TpwsCoilHolder;
import org.mtr.signaldashboard.packet.PacketOpenTpwsConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给 MTR 的轨道节点（{@code mtr:rail}）挂上 TPWS。
 *
 * <h2>为什么是 mixin</h2>
 * 轨道节点不是本模组的方块，没法改它的类。要让它「有 TPWS 线圈的功能」，
 * 只能往它的类里注入两样东西：
 * <ol>
 *     <li><b>实现 {@link BlockWithEntity}</b> —— 这样它才有方块实体可以存配置。
 *         接口是靠 mixin 的「接口注入」加到目标类上的，方法体由本类提供；</li>
 *     <li><b>拦下 {@code onUse2}</b> —— MTR 原来的实现是「手持刷子右键 → 打开线路
 *         形状编辑界面」，我们用 <b>Shift + 刷子</b> 抢在前面打开 TPWS 配置，
 *         普通刷子仍然原样落到 MTR 那边，一点都没动它的行为。</li>
 * </ol>
 *
 * <h2>为什么只有 Shift 才抢</h2>
 * 轨道形状编辑是铺轨时最常用的操作，抢掉它会让人没法干活。
 * Shift 在 MTR 里本来就是「另一个功能」的修饰键（比如 Shift + 刷子改站台方向），
 * 用在这里不突兀，也不会和原版行为撞车。
 *
 * <h2>为什么要在客户端也返回 SUCCESS</h2>
 * {@code onUse2} 在<b>两边</b>都会被调用。MTR 的原实现只在客户端开界面，
 * 所以我们的注入也必须两边都拦：客户端那边如果不拦，MTR 的线路形状界面会跟着弹出来，
 * 盖在 TPWS 配置界面上。真正发包只由服务端做。
 *
 * @author Mewzica
 */
@Mixin(BlockNode.class)
public abstract class BlockNodeMixin implements BlockWithEntity {

	/**
	 * 轨道节点 → 带 TPWS 配置的方块实体。
	 *
	 * <p>非铁路的节点（飞机 / 船 / 缆车）返回 {@code null}。这很重要：
	 * <ul>
	 *     <li>这些方块跟 TPWS 没关系，白挂一个方块实体纯属浪费内存和存档体积；</li>
	 *     <li>而且我们的方块实体类型只绑定了 {@code mtr:rail}，
	 *         别的方块上挂的方块实体会在存盘时被原版按
	 *         {@code BlockEntityType.supports(blockState)} 判为不匹配而丢掉 ——
	 *         也就是说存了也是白存，还多一条告警。</li>
	 * </ul>
	 * 返回 {@code null} 是合法的：{@code BlockEntityProvider.createBlockEntity}
	 * 本来就是可空的，原版拿到 {@code null} 就当这个方块没有方块实体。
	 */
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return mewsmtr$isRailNode(blockState) ? new BlockEntityRailNode(blockPos, blockState) : null;
	}

	/**
	 * Shift + 刷子右键 → 打开 TPWS 配置界面。
	 *
	 * <p>{@code remap = false}：目标是 MTR 的类，不是 Minecraft 的类，
	 * 不需要走 yarn → intermediary 的重映射。
	 */
	@Inject(method = "onUse2", at = @At("HEAD"), cancellable = true, remap = false)
	private void mewsmtr$openTpwsConfig(BlockState blockState, World world, BlockPos blockPos, PlayerEntity playerEntity, Hand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<ActionResult> callbackInfo) {
		if (!playerEntity.isSneaking() || !mewsmtr$isRailNode(blockState) || !mewsmtr$isBrush(playerEntity.getStackInHand(hand))) {
			return;
		}

		if (!world.isClient()) {
			final BlockEntity blockEntity = world.getBlockEntity(blockPos);
			if (blockEntity != null && blockEntity.data instanceof TpwsCoilHolder coil) {
				PacketOpenTpwsConfig.sendToPlayer(world, playerEntity, blockPos, coil.getConfig());
			}
		}

		// 两边都要返回 SUCCESS：客户端那边拦的是 MTR 的线路形状界面
		callbackInfo.setReturnValue(ActionResult.SUCCESS);
	}

	/**
	 * 只在真正的轨道节点上生效。
	 *
	 * <p>{@link BlockNode} 是 MTR 所有「节点类方块」共用的类（飞机节点、船节点、
	 * 缆车节点都是它），TPWS 只对铁路有意义，所以这里按方块实例过滤。
	 */
	private static boolean mewsmtr$isRailNode(BlockState blockState) {
		final Block railNode = org.mtr.mod.Blocks.RAIL_NODE.get();
		return railNode != null && blockState.getBlock().data == railNode.data;
	}

	/** 判断手上拿的是不是 MTR 的刷子（比底层 {@code Item} 实例最可靠）。 */
	private static boolean mewsmtr$isBrush(ItemStack itemStack) {
		if (itemStack == null || itemStack.isEmpty()) {
			return false;
		}
		final Item brush = org.mtr.mod.Items.BRUSH.get();
		return brush != null && itemStack.getItem().data == brush.data;
	}
}
