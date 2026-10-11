package org.mtr.signaldashboard.item;

import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.ItemExtension;
import org.mtr.signaldashboard.packet.PacketOpenSignalDashboard;

/**
 * 信号调度板物品。
 *
 * <p>右键打开信号调度板 GUI。所有实际的信号编辑操作都在 GUI 内完成
 * （选择两个节点 → 指定信号颜色），不依赖物品手持状态，
 * 这样玩家可以在面板里连续编辑多组信号而不用反复切换快捷栏。
 *
 * @author Mewzica
 */
public class ItemSignalDashboard extends ItemExtension {

	public ItemSignalDashboard(ItemSettings itemSettings) {
		super(itemSettings.maxCount(1));
	}

	@Override
	public void useWithoutResult(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient()) {
			// 在服务端请求打开面板。真正的数据填充在 PacketOpenSignalDashboard 的
			// runServer 中完成——它会收集玩家周围一定半径内的轨道数据一并下发。
			PacketOpenSignalDashboard.sendDirectlyToServer(world, user);
		}
	}
}
