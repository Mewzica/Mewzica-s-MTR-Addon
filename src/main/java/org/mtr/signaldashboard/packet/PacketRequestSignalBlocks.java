package org.mtr.signaldashboard.packet;

import org.mtr.core.data.ClientData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.DataRequest;
import org.mtr.core.operation.DataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.data.SignalRailData;
import org.mtr.signaldashboard.screen.SignalDashboardScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 轨道数据下发包。
 *
 * <p>方向：服务端 → 客户端。携带玩家周围轨道的轻量快照
 * （{@link SignalRailData}）。客户端收到后缓存数据，并打开或刷新
 * {@link SignalDashboardScreen}。
 *
 * @author Mewzica
 */
public class PacketRequestSignalBlocks extends PacketHandler {

	/** 客户端侧的共享数据缓存，GUI 从这里读取，避免每帧走网络。 */
	private static SignalRailData[] cachedRails = new SignalRailData[0];

	private final SignalRailData[] rails;

	/** 服务端发送用：携带数据。 */
	public PacketRequestSignalBlocks(SignalRailData[] rails) {
		this.rails = rails;
	}

	/** 客户端接收用：反序列化。 */
	public PacketRequestSignalBlocks(PacketBufferReceiver packetBufferReceiver) {
		final int count = packetBufferReceiver.readInt();
		this.rails = new SignalRailData[count];
		for (int i = 0; i < count; i++) {
			final long pos1 = packetBufferReceiver.readLong();
			final long pos2 = packetBufferReceiver.readLong();

			final int pathFloats = packetBufferReceiver.readInt();
			final float[] path = new float[pathFloats];
			for (int j = 0; j < pathFloats; j++) {
				path[j] = packetBufferReceiver.readFloat();
			}
			final double length = packetBufferReceiver.readDouble();

			final int colorCount = packetBufferReceiver.readInt();
			final int[] colors = new int[colorCount];
			for (int j = 0; j < colorCount; j++) {
				colors[j] = packetBufferReceiver.readInt();
			}

			final int blockedCount = packetBufferReceiver.readInt();
			final int[] blocked = new int[blockedCount];
			for (int j = 0; j < blockedCount; j++) {
				blocked[j] = packetBufferReceiver.readInt();
			}

			final boolean occupied = packetBufferReceiver.readBoolean();
			final boolean isPlatform = packetBufferReceiver.readBoolean();
			final boolean isSiding = packetBufferReceiver.readBoolean();
			final double speedLimit = packetBufferReceiver.readDouble();
			final int shapeOrdinal = packetBufferReceiver.readInt();

			this.rails[i] = new SignalRailData(
					pos1, pos2,
					path, length,
					colors, blocked, occupied,
					isPlatform, isSiding, speedLimit, shapeOrdinal
			);
		}
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(rails.length);
		for (final SignalRailData rail : rails) {
			packetBufferSender.writeLong(rail.pos1);
			packetBufferSender.writeLong(rail.pos2);

			packetBufferSender.writeInt(rail.path.length);
			for (final float value : rail.path) {
				packetBufferSender.writeFloat(value);
			}
			packetBufferSender.writeDouble(rail.length);

			packetBufferSender.writeInt(rail.signalColors.length);
			for (final int color : rail.signalColors) {
				packetBufferSender.writeInt(color);
			}

			packetBufferSender.writeInt(rail.blockedColors.length);
			for (final int color : rail.blockedColors) {
				packetBufferSender.writeInt(color);
			}

			packetBufferSender.writeBoolean(rail.occupied);
			packetBufferSender.writeBoolean(rail.isPlatform);
			packetBufferSender.writeBoolean(rail.isSiding);
			packetBufferSender.writeDouble(rail.speedLimit);
			packetBufferSender.writeInt(rail.shapeOrdinal);
		}
	}

	@Override
	public void runClient() {
		cachedRails = rails;

		// 切回客户端主线程再操作屏幕
		final MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			if (SignalDashboardScreen.getInstance() != null) {
				SignalDashboardScreen.refresh(rails);
			} else {
				client.openScreen(new Screen(new SignalDashboardScreen()));
			}
		});
	}

	public static SignalRailData[] getCachedRails() {
		return cachedRails;
	}

	/**
	 * 服务端：查询指定玩家周围的轨道并回发。
	 *
	 * @param serverPlayerEntity 目标玩家
	 * @param radius             搜索半径（方块）
	 */
	public static void sendRailsToPlayer(ServerPlayerEntity serverPlayerEntity, int radius) {
		final ServerWorld serverWorld = ServerWorld.cast(serverPlayerEntity.getEntityWorld());
		final World world = serverPlayerEntity.getEntityWorld();
		final Position playerPosition = Init.blockPosToPosition(serverPlayerEntity.getBlockPos());
		final UUID playerId = serverPlayerEntity.getUuid();
		MTRSignalDashboard.LOGGER.info("Querying rails within radius {} for {}", radius, serverPlayerEntity.getName().getString());

		// 走 MTR 官方的 GET_DATA 通道（与 ItemDashboard 同一条路）。
		// 注意不能用 RailsRequest：它只遍历 railIds 列表，不传 id 时返回空结果。
		// DataRequest 会遍历模拟器里的全部轨道，用 rail.closeTo(clientPosition, radius)
		// 在服务端就完成距离过滤，正好是我们想要的行为。
		Init.sendMessageC2S(
				OperationProcessor.GET_DATA,
				serverWorld.getServer(),
				world,
				new DataRequest(playerId, playerPosition, radius),
				dataResponse -> {
					// 把响应重新序列化成 JSON，再喂给 ClientData，
					// 这样 rails 字段就是反序列化好的完整 Rail 对象。
					final ClientData clientData = new ClientData();
					new DataResponse(new JsonReader(Utilities.getJsonObjectFromData(dataResponse)), clientData).write();

					final List<SignalRailData> result = new ArrayList<>();
					for (final Rail rail : clientData.rails) {
						if (!rail.isValid()) {
							continue;
						}
						result.add(SignalRailData.fromRail(rail));
					}

					final SignalRailData[] data = result.toArray(new SignalRailData[0]);
					MTRSignalDashboard.LOGGER.info("Found {} rails, sending to client", data.length);

					MTRSignalDashboard.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketRequestSignalBlocks(data));
				},
				DataResponse.class
		);
	}
}
