package org.mtr.signaldashboard.item;

import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.ItemExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.block.BlockEntityTpwsGrid;
import org.mtr.signaldashboard.data.TpwsCoilConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TPWS 连接器 —— 把一枚 TPWS 线圈绑定到附近的信号机上。
 *
 * <h2>为什么单独做成一个物品</h2>
 * MTR 自己的信号连接器（{@code ItemSignalModifier}）语义是「连接两个轨道节点」，
 * 复用它会和原版行为打架。所以这里给 TPWS 一个独立的物品，操作是两段式：
 *
 * <ol>
 *     <li><b>右键信号机</b> —— 记下这个位置（屏幕上会提示已选中）；</li>
 *     <li><b>右键 TPWS 线圈</b> —— 把记下的信号机绑上去。</li>
 * </ol>
 *
 * <p>和 MTR 的「先点 A 再点 B」完全一致，玩家不用学新东西。
 *
 * <p>不直接用 GUI 里的「自动检测」也可以走这条路 —— 两种方式都写同一份配置，
 * 界面上会立刻反映出连接结果。
 *
 * @author Mewzica
 */
public class ItemTpwsLinker extends ItemExtension {

	/** 每个玩家手上「已选中但还没落地」的信号机坐标（玩家 UUID → 方块坐标编码）。 */
	private static final Map<UUID, Long> PENDING_SIGNAL = new ConcurrentHashMap<>();

	public ItemTpwsLinker(ItemSettings itemSettings) {
		super(itemSettings.maxCount(1));
	}

	@Override
	public ActionResult useOnBlock2(ItemUsageContext context) {
		final World world = context.getWorld();
		final PlayerEntity playerEntity = context.getPlayer();
		if (playerEntity == null) {
			return ActionResult.PASS;
		}

		if (world.isClient()) {
			// 客户端只要回一个非 PASS，避免同一次右键再触发方块的 use 逻辑
			return ActionResult.SUCCESS;
		}

		final BlockPos clickedPos = context.getBlockPos();
		final BlockEntity blockEntity = world.getBlockEntity(clickedPos);

		if (blockEntity != null && blockEntity.data instanceof BlockEntityTpwsGrid coil) {
			return bindSignal(playerEntity, coil, clickedPos);
		}

		// 不是线圈 —— 当成「选择信号机」
		if (blockEntity != null && blockEntity.data instanceof BlockSignalBase.BlockEntityBase) {
			PENDING_SIGNAL.put(playerEntity.getUuid(), clickedPos.asLong());
			playerEntity.sendMessage(
					Text.cast(TextHelper.translatable(
							"message.mewsmtr.tpws.link.selected",
							clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()
					)),
					true
			);
			return ActionResult.SUCCESS;
		}

		playerEntity.sendMessage(
				Text.cast(TextHelper.translatable("message.mewsmtr.tpws.link.not_signal")),
				true
		);
		return ActionResult.PASS;
	}

	/** 把已选中的信号机绑到这枚线圈上。 */
	private static ActionResult bindSignal(PlayerEntity playerEntity, BlockEntityTpwsGrid coil, BlockPos coilPos) {
		final Long pending = PENDING_SIGNAL.remove(playerEntity.getUuid());
		if (pending == null) {
			playerEntity.sendMessage(
					Text.cast(TextHelper.translatable("message.mewsmtr.tpws.link.no_selection")),
					true
			);
			return ActionResult.SUCCESS;
		}

		final TpwsCoilConfig config = coil.getConfig();
		config.setMode(TpwsCoilConfig.MODE_SIGNAL);
		config.setSignal(pending, readSignalColor(playerEntity.getEntityWorld(), pending));
		coil.markConfigChanged();

		final BlockPos signalPos = BlockPos.fromLong(pending);
		MTRSignalDashboard.LOGGER.info(
				"Player {} linked TPWS coil {} to signal {}",
				playerEntity.getName().getString(), coilPos, signalPos
		);

		playerEntity.sendMessage(
				Text.cast(TextHelper.translatable(
						"message.mewsmtr.tpws.link.done",
						signalPos.getX(), signalPos.getY(), signalPos.getZ()
				)),
				true
		);
		return ActionResult.SUCCESS;
	}

	/**
	 * 读出信号机的颜色。
	 *
	 * <p>颜色决定「红灯」判定认哪一种闭塞 —— 一条轨道上可能同时挂多组颜色，
	 * 只认自己这一个才不会误伤。读不到时返回 {@link TpwsCoilConfig#NO_COLOR}，
	 * 此时刹车逻辑会退化成「该轨道任一闭塞被占用即视为红」。
	 */
	private static int readSignalColor(World world, long signalPos) {
		final BlockEntity blockEntity = world.getBlockEntity(BlockPos.fromLong(signalPos));
		if (blockEntity != null && blockEntity.data instanceof BlockSignalBase.BlockEntityBase signalEntity) {
			final org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet colors = signalEntity.getSignalColors(false);
			if (colors != null && !colors.isEmpty()) {
				return colors.firstInt();
			}
		}
		return TpwsCoilConfig.NO_COLOR;
	}
}
