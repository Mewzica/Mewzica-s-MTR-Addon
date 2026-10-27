package org.mtr.signaldashboard.packet;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.screen.TpwsConfigScreen;

/**
 * 打开 / 刷新 TPWS 线圈配置界面。
 *
 * <p>方向：服务端 → 客户端。携带方块坐标和一份<b>配置副本</b>。
 *
 * <p>之所以由服务端主动下发而不是客户端自己开界面：配置的权威数据在方块实体里，
 * 客户端那份只是投影。让服务端把「当前真实配置」推下来再开界面，
 * 就不会出现「界面上显示的是旧值、一按确定就把服务端改回旧值」这种坑。
 *
 * <p>同一个包也用于<b>刷新</b>：玩家在界面里点了「自动检测信号机」，
 * 服务端处理完会回发一个本包，客户端据此刷新界面上的信号机信息。
 *
 * @author Mewzica
 */
public class PacketOpenTpwsConfig extends PacketHandler {

	private final BlockPos blockPos;
	private final TpwsCoilConfig config;

	/** 服务端发送用。 */
	public PacketOpenTpwsConfig(BlockPos blockPos, TpwsCoilConfig config) {
		this.blockPos = blockPos;
		this.config = config;
	}

	/** 客户端接收用。 */
	public PacketOpenTpwsConfig(PacketBufferReceiver packetBufferReceiver) {
		this.blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		this.config = TpwsCoilConfig.read(packetBufferReceiver);
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
		config.write(packetBufferSender);
	}

	@Override
	public void runClient() {
		final MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			if (TpwsConfigScreen.getInstance() != null) {
				TpwsConfigScreen.refresh(blockPos, config);
			} else {
				client.openScreen(new Screen(new TpwsConfigScreen(blockPos, config)));
			}
		});
	}

	/**
	 * 服务端调用：把配置推给指定玩家。
	 *
	 * <p>传进去的配置会先 {@link TpwsCoilConfig#copy()} 一份，
	 * 避免客户端后续的编辑改到方块实体正在用的那个实例。
	 *
	 * <p>注意 {@code ServerPlayerEntity} 在 MTR 的映射层里<b>不是</b>
	 * {@code PlayerEntity} 的子类（两者都是独立的 {@code HolderBase} 包装），
	 * 所以下面拆成两个入口，别指望能直接传。
	 */
	public static void sendToPlayer(World world, PlayerEntity playerEntity, BlockPos blockPos, TpwsCoilConfig config) {
		if (ServerWorld.isInstance(world) && ServerPlayerEntity.isInstance(playerEntity)) {
			sendToServerPlayer(ServerPlayerEntity.cast(playerEntity), blockPos, config);
		}
	}

	/** 服务端调用：直接对服务端玩家对象发包。 */
	public static void sendToServerPlayer(ServerPlayerEntity serverPlayerEntity, BlockPos blockPos, TpwsCoilConfig config) {
		MTRSignalDashboard.REGISTRY.sendPacketToClient(
				serverPlayerEntity,
				new PacketOpenTpwsConfig(blockPos, config.copy())
		);
	}
}
