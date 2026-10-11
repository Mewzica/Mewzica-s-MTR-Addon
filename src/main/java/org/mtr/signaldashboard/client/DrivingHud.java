package org.mtr.signaldashboard.client;

import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.signaldashboard.data.IGui;
import org.mtr.signaldashboard.data.Lang;
import org.mtr.signaldashboard.drive.ReverseDrive;
import org.mtr.signaldashboard.mixin.VehicleSchemaAccessor;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 手动驾驶 HUD 的两个附加控件：<b>发车时间</b> 与 <b>反相器档位</b>。
 *
 * <p>两者都画在 MTR 驾驶 HUD 之上（由 {@code DrivingGuiRendererMixin} 驱动，
 * 见那里的说明），但位置来源不同：
 * <ul>
 *     <li><b>发车时间</b> —— 两行（上行标签「发车」、下行倒计时 {@code MM:ss}），
 *         右对齐到布局编辑器 {@code design/driving-hud-editor.html} 导出的
 *         {@code rightInset / bottomInset} 锚点；</li>
 *     <li><b>反相器</b> —— 锚在<b>表盘中心</b>，摆在 MTR 自己那个功率指示
 *         （{@code N} / {@code +60%} / {@code -30%} / {@code E}）的正下方。</li>
 * </ul>
 *
 * <p>两个控件都是纯文字（编辑器里 {@code box = false}），不画底框 —— 这样它们看起来是
 * 仪表盘的一部分，而不是外挂的一张表格。
 *
 * <h2>「发车时间」是怎么算出来的</h2>
 * <ol>
 *     <li>{@code Vehicle.getDepartureIndex()} 是当前所在路径段在
 *         {@code VehicleExtraData.immutablePath} 里的下标；</li>
 *     <li>该段的 {@code PathData.getDwellTime()} 就是这一站的<b>名义</b>停站时长（毫秒）；</li>
 *     <li>减去已经停留的 {@code elapsedDwellTime}（见 {@link VehicleSchemaAccessor}）
 *         得到剩余时间。</li>
 * </ol>
 *
 * <p>但 MTR 并<b>不会</b>停满 {@code dwellTime} 才走：4.0.5 的
 * {@code Vehicle.simulateStopped} 里实际发车条件是
 * {@code elapsedDwellTime >= max(dwellTime / 2, dwellTime - 3200 - 1000)}，
 * 也就是「提前 3.2 秒关门 + 1 秒余量」。所以倒计时要按这个阈值算，
 * 否则数字永远走不到 0 车就走了。这个公式见 {@link #departureThreshold(long)}。
 *
 * @author Mewzica
 */
public final class DrivingHud implements IGui {

	/** MTR 提前关门用的时长（毫秒），对应 {@code Vehicle.simulateStopped} 里的 3200。 */
	private static final long DOOR_CLOSE_MILLIS = 3200L;

	/** MTR 关门后留给发车的余量（毫秒），对应 {@code Vehicle.simulateStopped} 里的 1000。 */
	private static final long DOOR_MARGIN_MILLIS = 1000L;

	/**
	 * 本帧正在驾驶的车辆。
	 *
	 * <p>由 {@code DrivingGuiRendererMixin} 在 MTR 渲染驾驶 HUD 的 HEAD 塞进来，
	 * 在 {@link #render} 里取走并<b>立即清空</b>——这样玩家一放下司机钥匙，
	 * 下一帧没人再设置它，控件自然就消失了，不会残留上一辆车的信息。
	 */
	@Nullable
	private static VehicleExtension vehicle;

	private DrivingHud() {
	}

	/** 由 Mixin 每帧调用，传入 MTR 当前的驾驶车辆（可能为 {@code null}）。 */
	public static void setVehicle(@Nullable VehicleExtension vehicle) {
		DrivingHud.vehicle = vehicle;
	}

	/** 由 Mixin 在 MTR 驾驶 HUD 渲染完成后调用。 */
	public static void render(GraphicsHolder graphicsHolder) {
		final VehicleExtension vehicle = DrivingHud.vehicle;
		DrivingHud.vehicle = null;

		if (vehicle == null) {
			return;
		}

		// 先把车辆交给反相器逻辑。注意这一步刻意放在「有没有打开界面」的检查<b>之前</b>：
		// 只要玩家还坐在司机位上就算「在驾驶」，中途开一下背包不该让反相器归位。
		// 控件本身仍然只在没有界面时叠加。
		DriveInput.onDriving(vehicle);

		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getCurrentScreenMapped() != null) {
			// 打开了别的界面（背包、设置……）时不叠加，避免糊在界面上
			return;
		}

		final Window window = minecraftClient.getWindow();
		final int width = window.getScaledWidth();
		final int height = window.getScaledHeight();

		drawDeparture(graphicsHolder, vehicle, width, height);
		drawReverser(graphicsHolder, width, height);
	}

	/**
	 * 发车时间：<b>两行</b>，两行都右对齐到同一条竖线。
	 *
	 * <pre>
	 * 发车
	 * 00:45
	 * </pre>
	 *
	 * <p>上行是固定标签（{@code hud.departure}），下行是倒计时 {@code MM:ss}。
	 * 下行贴住 {@code bottomInset} 锚点、上行叠在它上面一行 —— 这样布局编辑时
	 * 锚点仍然只管「倒计时那一行」的位置，加标签不会把它顶下去。
	 */
	private static void drawDeparture(GraphicsHolder graphicsHolder, VehicleExtension vehicle, int width, int height) {
		final VehicleExtraData extraData = vehicle.vehicleExtraData;
		final long dwellTime = getDwellTime(extraData, vehicle.getDepartureIndex());
		final long elapsed = ((VehicleSchemaAccessor) vehicle).mewsmtr$getElapsedDwellTime();
		final long remaining = Math.max(0L, departureThreshold(dwellTime) - elapsed);
		final boolean atStop = dwellTime > 0L;

		final String value;
		final int valueColor;
		if (!atStop) {
			value = "-";
			valueColor = ARGB_HUD_LABEL;
		} else if (remaining <= 0L) {
			value = Lang.gui("hud.ready");
			valueColor = ARGB_HUD_ACCENT;
		} else {
			value = formatDuration(remaining);
			valueColor = remaining <= DRIVING_HUD_WARN_MILLIS ? ARGB_HUD_WARN : ARGB_HUD_ACCENT;
		}

		final int right = width - DRIVING_HUD_DEPARTURE_RIGHT_INSET;
		final int valueTop = height - DRIVING_HUD_DEPARTURE_BOTTOM_INSET - LINE_HEIGHT;
		drawRightAligned(graphicsHolder, Lang.gui("hud.departure"), ARGB_HUD_LABEL, right, valueTop - LINE_HEIGHT);
		drawRightAligned(graphicsHolder, value, valueColor, right, valueTop);
	}

	/**
	 * 反相器档位：单个字母 {@code F / N / R}，居中在「表盘中心 + 偏移」处，
	 * 即 MTR 功率指示的正下方。
	 *
	 * <p><b>只画字母、不画标签</b>：它紧贴在功率指示下面，上下文已经说明这是什么了，
	 * 多一个「反相器」反而把表盘挤满。
	 *
	 * <p>显示的是<b>客户端自己选的档位</b>。服务端在同一 tick 也会做一次相同的联锁判断
	 * （停稳才接受换档），两边逻辑一致。
	 */
	private static void drawReverser(GraphicsHolder graphicsHolder, int width, int height) {
		final int dialCenterX = width - DRIVING_HUD_EDGE_PADDING - DRIVING_HUD_SPEEDOMETER_SIZE / 2;
		final int dialCenterY = height - DRIVING_HUD_EDGE_PADDING - DRIVING_HUD_SPEEDOMETER_SIZE / 2;

		final ReverseDrive.Reverser reverser = DriveInput.getReverser();
		// 后退位用警示色：它决定油门往哪边推车就往哪边走，最需要一眼看出来
		final int valueColor = reverser == ReverseDrive.Reverser.REVERSE ? ARGB_HUD_WARN : ARGB_HUD_TEXT;

		drawCenteredText(
				graphicsHolder,
				Lang.gui(reverserLangKey(reverser)),
				valueColor,
				dialCenterX + DRIVING_HUD_REVERSER_OFFSET_X,
				dialCenterY + DRIVING_HUD_REVERSER_OFFSET_Y
		);
	}

	/** 档位 → 文案键。三档各自一个字母（F / N / R）。 */
	private static String reverserLangKey(ReverseDrive.Reverser reverser) {
		switch (reverser) {
			case REVERSE:
				return "hud.reverser_reverse";
			case NEUTRAL:
				return "hud.reverser_neutral";
			default:
				return "hud.reverser_forward";
		}
	}

	/**
	 * MTR 实际的发车时刻（自停稳起算的毫秒数）。
	 *
	 * <p>公式抄自 MTR 4.0.5 {@code Vehicle.simulateStopped} 的字节码：
	 * {@code Math.max(dwellTime / 2, dwellTime - 3200 - 1000)}。
	 * 即「最多等一半，或提前 4.2 秒」——两者取大。
	 */
	private static long departureThreshold(long dwellTime) {
		return Math.max(dwellTime / 2L, dwellTime - DOOR_CLOSE_MILLIS - DOOR_MARGIN_MILLIS);
	}

	/**
	 * 把剩余毫秒格式化成 {@code MM:ss}。
	 *
	 * <p>秒数<b>向上取整</b>：还剩 0.3 秒时显示 {@code 00:01} 而不是 {@code 00:00}，
	 * 免得和「已经可以发车」（那一秒会显示成文字）混淆。
	 */
	private static String formatDuration(long millis) {
		final long totalSeconds = (millis + 999L) / 1000L;
		return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
	}

	/** 取当前路径段的停站时长；下标越界或没有路径时返回 0（表示「过站不停」）。 */
	private static long getDwellTime(VehicleExtraData extraData, long departureIndex) {
		final int index = (int) departureIndex;
		if (index < 0 || index >= extraData.immutablePath.size()) {
			return 0L;
		}
		final PathData pathData = extraData.immutablePath.get(index);
		return pathData == null ? 0L : pathData.getDwellTime();
	}

	/**
	 * 把一段文字<b>右对齐</b>画在 {@code right} 处（右缘贴住它），文字顶端在 {@code top}。
	 *
	 * <p>对应编辑器里 {@code align = "left"} 且不画底框的情形：控件宽度自动贴合内容，
	 * 于是两行文字各自右对齐后右缘齐平，看起来就是一个右对齐的文字块。
	 */
	private static void drawRightAligned(GraphicsHolder graphicsHolder, String text, int color, int right, int top) {
		graphicsHolder.drawText(text, right - GraphicsHolder.getTextWidth(text), top, color, false, GraphicsHolder.getDefaultLight());
	}

	/** 把一段文字水平、竖直居中画在 {@code (centerX, centerY)}。 */
	private static void drawCenteredText(GraphicsHolder graphicsHolder, String text, int color, int centerX, int centerY) {
		graphicsHolder.drawText(
				text,
				centerX - GraphicsHolder.getTextWidth(text) / 2,
				centerY - TEXT_HEIGHT / 2,
				color, false, GraphicsHolder.getDefaultLight()
		);
	}
}
