package org.mtr.signaldashboard.screen;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.signaldashboard.MTRSignalDashboardClient;
import org.mtr.signaldashboard.data.IGui;
import org.mtr.signaldashboard.data.Lang;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.packet.PacketTpwsConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * TPWS 线圈配置界面 —— 用 MTR 的刷子右键线圈打开。
 *
 * <h2>界面结构</h2>
 * 一块居中面板，从上到下四段：
 * <ol>
 *     <li><b>模式</b> —— 超速防护 / 信号联锁，二选一；</li>
 *     <li><b>参数</b> —— 随模式切换：
 *         <ul>
 *             <li>超速防护：阈值来源（固定值 / 线路限速+余量）+ 数值微调；</li>
 *             <li>信号联锁：绑定状态 + 自动检测 / 断开按钮。</li>
 *         </ul>
 *     </li>
 *     <li><b>说明</b> —— 一行灰色提示，讲清楚当前模式会怎么动作；</li>
 *     <li><b>完成 / 取消</b>。</li>
 * </ol>
 *
 * <h2>为什么不暂停游戏</h2>
 * {@link #isPauseScreen2()} 返回 {@code false}：这个界面是要<b>边看列车边调</b>的，
 * 暂停了列车就不动了，调完也验证不了。
 *
 * <h2>状态同步</h2>
 * 界面里的 {@link #config} 是服务端推下来的一份副本。点「完成」才写回；
 * 点「自动检测 / 断开」时服务端处理完会回发一份新配置，通过 {@link #refresh} 刷进来。
 * 也就是说<b>界面永远不会自己编造状态</b>，看到的都是服务端的真实值。
 *
 * @author Mewzica
 */
public class TpwsConfigScreen extends ScreenExtension implements IGui {

	private static final int PANEL_WIDTH = 252;
	private static final int PANEL_HEIGHT = 196;

	/** 按钮通用高度。 */
	private static final int BUTTON_HEIGHT = 18;

	/** 数值微调小按钮的边长。 */
	private static final int STEPPER_SIZE = 16;

	// ---- 按钮 ID ----
	private static final int ID_MODE_OVERSPEED = 1;
	private static final int ID_MODE_SIGNAL = 2;
	private static final int ID_THRESHOLD_FIXED = 3;
	private static final int ID_THRESHOLD_LINE = 4;
	private static final int ID_SPEED_MINUS_10 = 5;
	private static final int ID_SPEED_MINUS_1 = 6;
	private static final int ID_SPEED_PLUS_1 = 7;
	private static final int ID_SPEED_PLUS_10 = 8;
	private static final int ID_MARGIN_MINUS = 9;
	private static final int ID_MARGIN_PLUS = 10;
	private static final int ID_AUTO_LINK = 11;
	private static final int ID_UNLINK = 12;
	private static final int ID_DONE = 13;
	private static final int ID_CANCEL = 14;

	// ---- 配色 ----
	private static final int ARGB_PANEL = 0xF0101010;
	private static final int ARGB_BORDER = 0xFF555555;
	private static final int ARGB_ROW_LABEL = 0xFFAAAAAA;
	private static final int ARGB_BUTTON = 0xFF37474F;
	private static final int ARGB_BUTTON_ACTIVE = 0xFF2E7D32;
	private static final int ARGB_BUTTON_WARN = 0xFF8B2500;
	private static final int ARGB_BUTTON_DISABLED = 0xFF2A2A2A;
	private static final int ARGB_TEXT_DISABLED = 0xFF666666;
	private static final int ARGB_VALUE = 0xFFFFD54F;
	private static final int ARGB_HINT = 0xFF888888;

	private static TpwsConfigScreen instance;

	private final BlockPos blockPos;

	/** 界面正在编辑的配置副本，点「完成」才写回服务端。 */
	private TpwsCoilConfig config;

	public TpwsConfigScreen(BlockPos blockPos, TpwsCoilConfig config) {
		super();
		this.blockPos = blockPos;
		this.config = config;
	}

	public static TpwsConfigScreen getInstance() {
		return instance;
	}

	/** 服务端回发新配置时刷新界面（只认同一枚线圈）。 */
	public static void refresh(BlockPos blockPos, TpwsCoilConfig config) {
		final TpwsConfigScreen screen = instance;
		if (screen != null && screen.blockPos.asLong() == blockPos.asLong()) {
			screen.config = config;
		}
	}

	@Override
	protected void init2() {
		super.init2();
		instance = this;
	}

	@Override
	public void removed2() {
		super.removed2();
		if (instance == this) {
			instance = null;
		}
	}

	/** 不暂停 —— 调参数时要能看见列车在跑。 */
	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	// ------------------------------------------------------------------
	// 绘制
	// ------------------------------------------------------------------

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		final int panelX = (getWidthMapped() - PANEL_WIDTH) / 2;
		final int panelY = (getHeightMapped() - PANEL_HEIGHT) / 2;

		// 面板底 + 边框
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, ARGB_PANEL);
		guiDrawing.drawRectangle(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, ARGB_BORDER);
		guiDrawing.drawRectangle(panelX, panelY + PANEL_HEIGHT - 1, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, ARGB_BORDER);
		guiDrawing.drawRectangle(panelX, panelY, panelX + 1, panelY + PANEL_HEIGHT, ARGB_BORDER);
		guiDrawing.drawRectangle(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, ARGB_BORDER);
		guiDrawing.finishDrawingRectangle();

		graphicsHolder.drawCenteredText(
				Lang.gui("tpws.title"),
				panelX + PANEL_WIDTH / 2, panelY + 8,
				ARGB_WHITE
		);

		graphicsHolder.drawText(
				Lang.gui("tpws.coord", blockPos.getX(), blockPos.getY(), blockPos.getZ()),
				panelX + 12, panelY + 22,
				ARGB_HINT, false, GraphicsHolder.getDefaultLight()
		);

		// 行标签
		graphicsHolder.drawText(Lang.gui("tpws.mode"), panelX + 12, panelY + 40, ARGB_ROW_LABEL, false, GraphicsHolder.getDefaultLight());

		if (config.isSignalMode()) {
			drawSignalSection(graphicsHolder, panelX, panelY);
		} else {
			drawOverspeedSection(graphicsHolder, panelX, panelY);
		}

		// 说明文字
		graphicsHolder.drawText(
				Lang.gui(config.isSignalMode() ? "tpws.hint.signal" : "tpws.hint.overspeed"),
				panelX + 12, panelY + PANEL_HEIGHT - 46,
				ARGB_HINT, false, GraphicsHolder.getDefaultLight()
		);

		// 按钮
		for (final Button button : buildButtons(panelX, panelY)) {
			final boolean hovered = mouseX >= button.x() && mouseX < button.x() + button.w()
					&& mouseY >= button.y() && mouseY < button.y() + button.h();
			final int color = button.enabled()
					? (hovered ? brighten(button.color()) : button.color())
					: ARGB_BUTTON_DISABLED;
			guiDrawing.beginDrawingRectangle();
			guiDrawing.drawRectangle(button.x(), button.y(), button.x() + button.w(), button.y() + button.h(), color);
			guiDrawing.finishDrawingRectangle();
			graphicsHolder.drawCenteredText(
					button.label(),
					button.x() + button.w() / 2,
					button.y() + (button.h() - TEXT_HEIGHT) / 2 + 1,
					button.enabled() ? ARGB_WHITE : ARGB_TEXT_DISABLED
			);
		}

		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	/** 超速防护模式的参数区：阈值来源 + 两个数值微调。 */
	private void drawOverspeedSection(GraphicsHolder graphicsHolder, int panelX, int panelY) {
		graphicsHolder.drawText(Lang.gui("tpws.threshold"), panelX + 12, panelY + 68, ARGB_ROW_LABEL, false, GraphicsHolder.getDefaultLight());
		graphicsHolder.drawText(Lang.gui("tpws.speed_limit"), panelX + 12, panelY + 96, ARGB_ROW_LABEL, false, GraphicsHolder.getDefaultLight());

		// 固定阈值
		graphicsHolder.drawText(
				config.getSpeedLimitKmh() + " " + Lang.gui("tpws.unit.kmh"),
				panelX + 108, panelY + 96,
				config.isLineSpeedMode() ? ARGB_TEXT_DISABLED : ARGB_VALUE,
				false, GraphicsHolder.getDefaultLight()
		);

		if (config.isLineSpeedMode()) {
			graphicsHolder.drawText(Lang.gui("tpws.margin"), panelX + 12, panelY + 120, ARGB_ROW_LABEL, false, GraphicsHolder.getDefaultLight());
			graphicsHolder.drawText(
					"+" + config.getMarginKmh() + " " + Lang.gui("tpws.unit.kmh"),
					panelX + 108, panelY + 120,
					ARGB_VALUE, false, GraphicsHolder.getDefaultLight()
			);
			graphicsHolder.drawText(
					Lang.gui("tpws.line_hint"),
					panelX + 12, panelY + 138,
					ARGB_HINT, false, GraphicsHolder.getDefaultLight()
			);
		}
	}

	/** 信号联锁模式的参数区：绑定状态 + 操作按钮。 */
	private void drawSignalSection(GraphicsHolder graphicsHolder, int panelX, int panelY) {
		graphicsHolder.drawText(Lang.gui("tpws.signal"), panelX + 12, panelY + 68, ARGB_ROW_LABEL, false, GraphicsHolder.getDefaultLight());

		final String status;
		if (config.hasSignal()) {
			final BlockPos signalPos = BlockPos.fromLong(config.getSignalPos());
			status = Lang.gui("tpws.signal.linked", signalPos.getX(), signalPos.getY(), signalPos.getZ());
		} else {
			status = Lang.gui("tpws.signal.none");
		}
		graphicsHolder.drawText(
				status,
				panelX + 12, panelY + 84,
				config.hasSignal() ? ARGB_VALUE : ARGB_HINT,
				false, GraphicsHolder.getDefaultLight()
		);

		if (config.hasSignal()) {
			graphicsHolder.drawText(
					Lang.gui("tpws.signal.color", Integer.toHexString(config.getSignalColor()).toUpperCase()),
					panelX + 12, panelY + 100,
					ARGB_HINT, false, GraphicsHolder.getDefaultLight()
			);
		}
	}

	// ------------------------------------------------------------------
	// 按钮
	// ------------------------------------------------------------------

	private record Button(int id, int x, int y, int w, int h, String label, int color, boolean enabled) {
	}

	/**
	 * 按当前状态生成按钮列表。
	 *
	 * <p>绘制和点击共用同一份布局 —— 这样永远不会出现「画在这里、点在那里」的错位。
	 */
	private List<Button> buildButtons(int panelX, int panelY) {
		final List<Button> buttons = new ArrayList<>();

		// 模式
		buttons.add(new Button(
				ID_MODE_OVERSPEED, panelX + 92, panelY + 36, 72, BUTTON_HEIGHT,
				Lang.gui("tpws.mode.overspeed"),
				config.isSignalMode() ? ARGB_BUTTON : ARGB_BUTTON_ACTIVE, true
		));
		buttons.add(new Button(
				ID_MODE_SIGNAL, panelX + 168, panelY + 36, 72, BUTTON_HEIGHT,
				Lang.gui("tpws.mode.signal"),
				config.isSignalMode() ? ARGB_BUTTON_ACTIVE : ARGB_BUTTON, true
		));

		if (config.isSignalMode()) {
			buttons.add(new Button(
					ID_AUTO_LINK, panelX + 12, panelY + 120, 92, BUTTON_HEIGHT,
					Lang.gui("tpws.auto_link"), ARGB_BUTTON, true
			));
			buttons.add(new Button(
					ID_UNLINK, panelX + 110, panelY + 120, 60, BUTTON_HEIGHT,
					Lang.gui("tpws.unlink"), ARGB_BUTTON_WARN, config.hasSignal()
			));
		} else {
			buttons.add(new Button(
					ID_THRESHOLD_FIXED, panelX + 92, panelY + 64, 68, BUTTON_HEIGHT,
					Lang.gui("tpws.threshold.fixed"),
					config.isLineSpeedMode() ? ARGB_BUTTON : ARGB_BUTTON_ACTIVE, true
			));
			buttons.add(new Button(
					ID_THRESHOLD_LINE, panelX + 164, panelY + 64, 76, BUTTON_HEIGHT,
					Lang.gui("tpws.threshold.line"),
					config.isLineSpeedMode() ? ARGB_BUTTON_ACTIVE : ARGB_BUTTON, true
			));

			// 固定阈值：-10 / -1 / +1 / +10
			final int stepperY = panelY + 94;
			buttons.add(new Button(ID_SPEED_MINUS_10, panelX + 168, stepperY, STEPPER_SIZE + 8, STEPPER_SIZE, "-10", ARGB_BUTTON, true));
			buttons.add(new Button(ID_SPEED_MINUS_1, panelX + 194, stepperY, STEPPER_SIZE, STEPPER_SIZE, "-", ARGB_BUTTON, true));
			buttons.add(new Button(ID_SPEED_PLUS_1, panelX + 212, stepperY, STEPPER_SIZE, STEPPER_SIZE, "+", ARGB_BUTTON, true));
			buttons.add(new Button(ID_SPEED_PLUS_10, panelX + 230, stepperY, STEPPER_SIZE + 8, STEPPER_SIZE, "+10", ARGB_BUTTON, true));

			// 余量（只有线路限速模式才有效）
			final int marginY = panelY + 118;
			final boolean marginEnabled = config.isLineSpeedMode();
			buttons.add(new Button(ID_MARGIN_MINUS, panelX + 168, marginY, STEPPER_SIZE, STEPPER_SIZE, "-", ARGB_BUTTON, marginEnabled));
			buttons.add(new Button(ID_MARGIN_PLUS, panelX + 230, marginY, STEPPER_SIZE + 8, STEPPER_SIZE, "+", ARGB_BUTTON, marginEnabled));
		}

		// 底部
		buttons.add(new Button(
				ID_DONE, panelX + PANEL_WIDTH - 152, panelY + PANEL_HEIGHT - 30, 68, BUTTON_HEIGHT,
				Lang.gui("tpws.done"), ARGB_BUTTON_ACTIVE, true
		));
		buttons.add(new Button(
				ID_CANCEL, panelX + PANEL_WIDTH - 80, panelY + PANEL_HEIGHT - 30, 68, BUTTON_HEIGHT,
				Lang.gui("tpws.cancel"), ARGB_BUTTON, true
		));

		return buttons;
	}

	@Override
	public boolean mouseClicked2(double mouseX, double mouseY, int button) {
		if (button == 0) {
			final int panelX = (getWidthMapped() - PANEL_WIDTH) / 2;
			final int panelY = (getHeightMapped() - PANEL_HEIGHT) / 2;

			for (final Button candidate : buildButtons(panelX, panelY)) {
				if (!candidate.enabled()) {
					continue;
				}
				if (mouseX >= candidate.x() && mouseX < candidate.x() + candidate.w()
						&& mouseY >= candidate.y() && mouseY < candidate.y() + candidate.h()) {
					handleClick(candidate.id());
					return true;
				}
			}
		}
		return super.mouseClicked2(mouseX, mouseY, button);
	}

	private void handleClick(int id) {
		switch (id) {
			case ID_MODE_OVERSPEED -> config.setMode(TpwsCoilConfig.MODE_OVERSPEED);
			case ID_MODE_SIGNAL -> config.setMode(TpwsCoilConfig.MODE_SIGNAL);
			case ID_THRESHOLD_FIXED -> config.setSpeedMode(TpwsCoilConfig.SPEED_FIXED);
			case ID_THRESHOLD_LINE -> config.setSpeedMode(TpwsCoilConfig.SPEED_LINE);
			case ID_SPEED_MINUS_10 -> config.nudgeSpeedLimitKmh(-2);
			case ID_SPEED_MINUS_1 -> config.nudgeSpeedLimitKmh(-1);
			case ID_SPEED_PLUS_1 -> config.nudgeSpeedLimitKmh(1);
			case ID_SPEED_PLUS_10 -> config.nudgeSpeedLimitKmh(2);
			case ID_MARGIN_MINUS -> config.nudgeMarginKmh(-1);
			case ID_MARGIN_PLUS -> config.nudgeMarginKmh(1);
			case ID_AUTO_LINK -> sendToServer(PacketTpwsConfig.ACTION_AUTO_LINK);
			case ID_UNLINK -> sendToServer(PacketTpwsConfig.ACTION_UNLINK);
			case ID_DONE -> {
				sendToServer(PacketTpwsConfig.ACTION_SAVE);
				onClose2();
			}
			case ID_CANCEL -> onClose2();
			default -> {
			}
		}
	}

	/**
	 * 把当前配置发给服务端。
	 *
	 * <p>「完成」和「自动检测 / 断开」都走这里，区别只在 {@code action}。
	 * 服务端处理完一律回发新配置，所以界面不会出现「本地改了但服务端没改」的分歧。
	 */
	private void sendToServer(int action) {
		MTRSignalDashboardClient.sendTpwsConfig(blockPos, config, action);
	}

	private static int brighten(int color) {
		final int r = Math.min(255, ((color >> 16) & 0xFF) + 24);
		final int g = Math.min(255, ((color >> 8) & 0xFF) + 24);
		final int b = Math.min(255, (color & 0xFF) + 24);
		return (r << 16) | (g << 8) | b;
	}
}
