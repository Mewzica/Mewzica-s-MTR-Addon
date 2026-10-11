package org.mtr.signaldashboard.packet;

import org.mtr.core.data.SignalModification;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.signaldashboard.MTRSignalDashboard;

/**
 * 提交一条信号连接。
 *
 * <p>这是信号调度板的核心操作：在 GUI 中依次点选两个轨道节点，
 * 再选择一个信号颜色，点击确认后由本包发往服务端。
 *
 * <p>实现上直接复用 MTR 的 {@link SignalModification}：
 * 它描述"在 pos1 与 pos2 之间的轨道上增/删指定颜色"，
 * 与手持信号连接器右键两个节点产生的效果完全一致，
 * 因此不会污染存档，也与原版 MTR 的行为保持兼容。
 *
 * @author Mewzica
 */
public class PacketUpdateSignalLink extends PacketHandler {

	/** 操作类型：新增连接。 */
	public static final int ACTION_ADD = 0;
	/** 操作类型：移除连接。 */
	public static final int ACTION_REMOVE = 1;

	private final BlockPos pos1;
	private final BlockPos pos2;
	private final int color;
	private final int action;

	/** 客户端 → 服务端：提交一次编辑。 */
	public PacketUpdateSignalLink(BlockPos pos1, BlockPos pos2, int color, int action) {
		this.pos1 = pos1;
		this.pos2 = pos2;
		this.color = color;
		this.action = action;
	}

	/** 服务端反序列化。 */
	public PacketUpdateSignalLink(PacketBufferReceiver packetBufferReceiver) {
		this.pos1 = BlockPos.fromLong(packetBufferReceiver.readLong());
		this.pos2 = BlockPos.fromLong(packetBufferReceiver.readLong());
		this.color = packetBufferReceiver.readInt();
		this.action = packetBufferReceiver.readInt();
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(pos1.asLong());
		packetBufferSender.writeLong(pos2.asLong());
		packetBufferSender.writeInt(color);
		packetBufferSender.writeInt(action);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final ServerWorld serverWorld = ServerWorld.cast(serverPlayerEntity.getEntityWorld());

		MTRSignalDashboard.LOGGER.info(
				"Player {} submitted signal link: {} <-> {}, colour {}, action {}",
				serverPlayerEntity.getName().getString(),
				pos1, pos2, Integer.toHexString(color), action == ACTION_ADD ? "add" : "remove"
		);

		final SignalModification modification = new SignalModification(
				Init.blockPosToPosition(pos1),
				Init.blockPosToPosition(pos2),
				false
		);

		if (action == ACTION_ADD) {
			modification.putColorToAdd(color);
		} else {
			modification.putColorToRemove(color);
		}

		// 复用 MTR 的服务端更新通道，保证信号状态被正确同步到所有客户端与模拟器
		PacketUpdateData.sendDirectlyToServerSignalModification(serverWorld, modification);

		// 回发最新轨道数据，让面板立即反映改动
		PacketRequestSignalBlocks.sendRailsToPlayer(
				serverPlayerEntity,
				PacketOpenSignalDashboard.SEARCH_RADIUS
		);
	}

	/**
	 * 客户端调用入口。
	 */
	public static void sendDirectlyToServer(World world, BlockPos pos1, BlockPos pos2, int color, int action) {
		if (ServerWorld.isInstance(world)) {
			final ServerWorld serverWorld = ServerWorld.cast(world);
			new PacketUpdateSignalLink(pos1, pos2, color, action).runServer(
					serverWorld.getServer(),
					null
			);
		}
	}
}
