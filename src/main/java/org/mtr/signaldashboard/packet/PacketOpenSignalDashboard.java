package org.mtr.signaldashboard.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.signaldashboard.MTRSignalDashboard;

/**
 * 请求打开 / 刷新信号调度板。
 *
 * <p>方向：客户端 → 服务端。包体为空，服务端收到后会查询该玩家周围
 * 一定半径内的轨道，通过 {@link PacketRequestSignalBlocks} 回发数据；
 * 客户端收到数据后才会打开或刷新 GUI（避免首帧空白）。
 *
 * @author Mewzica
 */
public class PacketOpenSignalDashboard extends PacketHandler {

	/** 以玩家为中心，扫描轨道的半径（方块）。 */
	public static final int SEARCH_RADIUS = 96;

	/** 反序列化用（服务端接收时调用）。 */
	public PacketOpenSignalDashboard(PacketBufferReceiver packetBufferReceiver) {
	}

	/** 客户端发送用。 */
	public PacketOpenSignalDashboard() {
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		// 无载荷
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		MTRSignalDashboard.LOGGER.info("Player {} requested to open/refresh the signal dashboard", serverPlayerEntity.getName().getString());
		PacketRequestSignalBlocks.sendRailsToPlayer(serverPlayerEntity, SEARCH_RADIUS);
	}

	/**
	 * 由物品在服务端直接调用（不经过网络，省一次往返）。
	 */
	public static void sendDirectlyToServer(World world, PlayerEntity playerEntity) {
		if (ServerWorld.isInstance(world) && ServerPlayerEntity.isInstance(playerEntity)) {
			new PacketOpenSignalDashboard().runServer(
					ServerWorld.cast(world).getServer(),
					ServerPlayerEntity.cast(playerEntity)
			);
		}
	}
}
