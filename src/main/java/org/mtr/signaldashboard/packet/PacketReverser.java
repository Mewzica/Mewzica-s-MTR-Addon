package org.mtr.signaldashboard.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.signaldashboard.drive.ReverseDrive;

/**
 * 客户端 → 服务端：同步司机的<b>反相器档位</b>（F / N / R）。
 *
 * <p>只带一个 int（{@link ReverseDrive.Reverser#position}），客户端在驾驶期间
 * <b>每 tick 发一次</b>。之所以不做「只在换档时发」：那样一旦丢包，
 * 服务端就会永远停在错误的档位。每 tick 发一次虽然多几个字节，但天然自愈，
 * 而且服务端还有超时兜底（见 {@link ReverseDrive}）——玩家下车后不再发包，
 * 反相器会自动回到前进位。
 *
 * <p>车辆本身不需要出现在包里：服务端拿到玩家 UUID 后在自己的模拟数据里反查这辆车，
 * 这样客户端不需要知道任何服务端的车辆标识，也避免了伪造他人车辆的风险。
 *
 * @author Mewzica
 */
public class PacketReverser extends PacketHandler {

	private final int position;

	/** 客户端构造。{@code position}：-1 = R，0 = N，+1 = F。 */
	public PacketReverser(int position) {
		this.position = position;
	}

	/** 服务端反序列化。 */
	public PacketReverser(PacketBufferReceiver packetBufferReceiver) {
		this.position = packetBufferReceiver.readInt();
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(position);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		ReverseDrive.setReverser(serverPlayerEntity.getUuid(), position);
	}
}
