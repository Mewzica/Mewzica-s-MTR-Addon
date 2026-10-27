package org.mtr.signaldashboard;

import org.mtr.mapping.registry.SoundEventRegistryObject;

/**
 * 音效注册。
 *
 * <p>三个音效都是列车司机室里真实能听到的那几种，对应 TPWS / AWS 的三种报警：
 *
 * <table border="1">
 *     <caption>音效与场景</caption>
 *     <tr><th>注册名</th><th>文件</th><th>什么时候响</th></tr>
 *     <tr>
 *         <td>{@code mewsmtr:tpws_start}</td>
 *         <td>{@code tpws_start.ogg}</td>
 *         <td><b>起动音</b> —— 列车压上线圈、系统开始防护时的一次性提示音
 *             （对应 AWS 的「警音 + 铃声」那一套）</td>
 *     </tr>
 *     <tr>
 *         <td>{@code mewsmtr:tpws_overspeed}</td>
 *         <td>{@code tpws_overspeed.ogg}</td>
 *         <td><b>超速报警</b> —— 超速模式的线圈被触发、列车被紧急制动时</td>
 *     </tr>
 *     <tr>
 *         <td>{@code mewsmtr:tpws_spad}</td>
 *         <td>{@code tpws_spad.ogg}</td>
 *         <td><b>闯红灯报警</b> —— 信号联锁模式的线圈被触发时（SPAD = Signal Passed At Danger）</td>
 *     </tr>
 * </table>
 *
 * <p>三个文件都是 44.1 kHz 单声道 OGG Vorbis，由原始 WAV 转码并把峰值抬到 -1 dBFS。
 * Minecraft 只认 OGG，WAV 放进去会被当成坏文件静默丢弃。
 *
 * <p>播放走的是服务端 {@code World.playSound(...)}，也就是「在列车位置广播」，
 * 所以附近的玩家都能听见，而且音量随距离衰减 —— 和列车本身的声音是一个行为。
 *
 * @author Mewzica
 */
public final class Sounds {

	/** 起动音：列车压上线圈、防护生效。 */
	public static final SoundEventRegistryObject TPWS_START = MTRSignalDashboard.REGISTRY.registerSoundEvent(
			MTRSignalDashboard.identifier("tpws_start")
	);

	/** 超速报警。 */
	public static final SoundEventRegistryObject TPWS_OVERSPEED = MTRSignalDashboard.REGISTRY.registerSoundEvent(
			MTRSignalDashboard.identifier("tpws_overspeed")
	);

	/** 闯红灯（SPAD）报警。 */
	public static final SoundEventRegistryObject TPWS_SPAD = MTRSignalDashboard.REGISTRY.registerSoundEvent(
			MTRSignalDashboard.identifier("tpws_spad")
	);

	private Sounds() {
	}

	public static void init() {
		// 触发类加载，完成静态字段中的注册（实际落库由 REGISTRY.init() 完成）
		MTRSignalDashboard.LOGGER.info("Registered sound events: tpws_start, tpws_overspeed, tpws_spad");
	}
}
