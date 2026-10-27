package org.mtr.signaldashboard.packet;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.signaldashboard.MTRSignalDashboard;
import org.mtr.signaldashboard.block.BlockEntityTpwsGrid;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.tpws.TpwsSignalFinder;

/**
 * 提交 TPWS 线圈配置。
 *
 * <p>方向：客户端 → 服务端。一个包干三件事，靠 {@code action} 区分：
 *
 * <ul>
 *     <li>{@link #ACTION_SAVE} —— 玩家按了「完成」，把界面上的配置整份写回方块实体；</li>
 *     <li>{@link #ACTION_AUTO_LINK} —— 玩家按了「自动检测」，服务端在方块周围
 *         找最近的 MTR 信号机并绑定；</li>
 *     <li>{@link #ACTION_UNLINK} —— 玩家按了「断开」，清掉绑定。</li>
 * </ul>
 *
 * <p>后两个动作<b>不会丢掉界面上还没保存的改动</b>：服务端从客户端发来的配置出发，
 * 只改信号绑定那两项，再把整份配置写回。处理完统一回发
 * {@link PacketOpenTpwsConfig} 刷新界面，所以玩家看到的状态永远是服务端的真实状态。
 *
 * @author Mewzica
 */
public class PacketTpwsConfig extends PacketHandler {

	/** 保存配置。 */
	public static final int ACTION_SAVE = 0;
	/** 自动检测并绑定最近的信号机。 */
	public static final int ACTION_AUTO_LINK = 1;
	/** 断开信号机绑定。 */
	public static final int ACTION_UNLINK = 2;

	/** 允许编辑的最大距离（方块）。防止远程改别人场地里的线圈。 */
	private static final int MAX_EDIT_DISTANCE = 8;

	private final BlockPos blockPos;
	private final TpwsCoilConfig config;
	private final int action;

	/** 客户端发送用。 */
	public PacketTpwsConfig(BlockPos blockPos, TpwsCoilConfig config, int action) {
		this.blockPos = blockPos;
		this.config = config.copy();
		this.action = action;
	}

	/** 服务端接收用。 */
	public PacketTpwsConfig(PacketBufferReceiver packetBufferReceiver) {
		this.blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		this.config = TpwsCoilConfig.read(packetBufferReceiver);
		this.action = packetBufferReceiver.readInt();
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
		config.write(packetBufferSender);
		packetBufferSender.writeInt(action);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final World world = serverPlayerEntity.getEntityWorld();
		if (world.isClient()) {
			return;
		}

		// 距离校验：GUI 是服务端推过去的，但包本身可以被伪造
		final BlockPos playerPos = serverPlayerEntity.getBlockPos();
		final double dx = playerPos.getX() - blockPos.getX();
		final double dy = playerPos.getY() - blockPos.getY();
		final double dz = playerPos.getZ() - blockPos.getZ();
		if (dx * dx + dy * dy + dz * dz > MAX_EDIT_DISTANCE * MAX_EDIT_DISTANCE) {
			MTRSignalDashboard.LOGGER.warn(
					"Player {} tried to edit a TPWS coil {} blocks away, ignored",
					serverPlayerEntity.getName().getString(),
					Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))
			);
			return;
		}

		final BlockEntity blockEntity = world.getBlockEntity(blockPos);
		if (blockEntity == null || !(blockEntity.data instanceof BlockEntityTpwsGrid coil)) {
			return;
		}

		switch (action) {
			case ACTION_AUTO_LINK -> {
				final TpwsSignalFinder.Found found = TpwsSignalFinder.findNearest(world, blockPos);
				if (found == null) {
					config.clearSignal();
					MTRSignalDashboard.LOGGER.info("No signal found near TPWS coil at {}", blockPos);
				} else {
					config.setSignal(found.blockPos(), found.color());
					MTRSignalDashboard.LOGGER.info(
							"TPWS coil at {} linked to signal at {} (colour {})",
							blockPos, BlockPos.fromLong(found.blockPos()), Integer.toHexString(found.color())
					);
				}
			}
			case ACTION_UNLINK -> config.clearSignal();
			default -> {
				// ACTION_SAVE：原样写入
			}
		}

		coil.applyConfig(config);

		// 回发真实状态，界面据此刷新（尤其是自动检测的结果）
		PacketOpenTpwsConfig.sendToServerPlayer(serverPlayerEntity, blockPos, coil.getConfig());
	}
}
