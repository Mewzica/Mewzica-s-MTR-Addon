package org.mtr.signaldashboard.screen;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.signaldashboard.MTRSignalDashboardClient;
import org.mtr.signaldashboard.data.IGui;
import org.mtr.signaldashboard.data.Lang;
import org.mtr.signaldashboard.data.TpwsCoilConfig;
import org.mtr.signaldashboard.packet.PacketTpwsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * TPWS 线圈配置界面 —— 用 MTR 的刷子右键线圈打开。
 *
 * <h2>视觉风格</h2>
 * 刻意做成<b>原版</b>的样子：{@code #C6C6C6} 的浅灰面板、白色高光边、深灰阴影边，
 * 按钮一律用 {@link ButtonWidgetExtension}（也就是原版的按钮纹理、悬停态和点击音效），
 * 文字用原版的 {@code #404040}。面板宽度取 176 —— 和原版箱子 / 熔炉界面同宽。
 *
 * <p>没有用 {@code generic_54.png} 做九宫格：那张图中间是 6×9 个物品槽，
 * 截一段出来会带上槽位纹理；而 MTR 的 {@code GuiDrawing.drawTexture} 只有
 * 8 参数版本（目标尺寸 = 取样尺寸），没法把槽位那一段拉伸掉。
 * 原版面板本来就是纯色 + 三根描边，直接画反而更准。
 *
 * <h2>按钮为什么不重建</h2>
 * 模式切换会改变「哪些按钮该出现」，最直觉的做法是清空重加，但 MTR 的
 * {@code ScreenExtension} 没暴露 {@code clearChildren}。所以这里<b>一次性</b>
 * 建好两种模式的全部按钮，切换时只改 {@code visible} —— 不可见的原版按钮
 * 既不会被绘制，也不会吃到点击。
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

	/** 面板宽度：和原版箱子界面一致。 */
	private static final int PANEL_WIDTH = 176;

	private static final int PANEL_HEIGHT = 226;

	/** 原版按钮的标准高度。 */
	private static final int BUTTON_HEIGHT = 20;

	/** 面板内部左缩进。 */
	private static final int LABEL_X = 8;

	// ---- 原版配色 ----
	/** 原版 GUI 面板底色。 */
	private static final int ARGB_PANEL = 0xFFC6C6C6;
	/** 面板的高光边（顶 / 左）。 */
	private static final int ARGB_HIGHLIGHT = 0xFFFFFFFF;
	/** 面板的阴影边（底 / 右）。 */
	private static final int ARGB_SHADOW = 0xFF555555;
	/** 面板最外圈的黑色描边。 */
	private static final int ARGB_OUTLINE = 0xFF000000;
	/** 原版标签文字色。 */
	private static final int ARGB_TEXT = 0xFF404040;
	/** 数值文字色（比标签更重，用来强调当前值）。 */
	private static final int ARGB_VALUE = 0xFF000000;
	/** 次要 / 禁用文字色。 */
	private static final int ARGB_DIM = 0xFF707070;
	/** 警告文字色。 */
	private static final int ARGB_WARN = 0xFFA00000;

	private static TpwsConfigScreen instance;

	private final BlockPos blockPos;

	/** 界面正在编辑的配置副本，点「完成」才写回服务端。 */
	private TpwsCoilConfig config;

	/** 全部按钮 + 各自的文字来源 / 可见性来源，状态一变就统一刷新。 */
	private final List<Slot> slots = new ArrayList<>();

	/**
	 * 一个原版按钮，外加两个"怎么显示"的来源。
	 *
	 * <p>把文字和可见性做成 supplier，是因为它们依赖 {@link #config} 的实时状态；
	 * 用 supplier 就不用为每种组合各写一遍更新代码。
	 */
	private record Slot(ButtonWidgetExtension widget, Supplier<String> label, BooleanSupplier visible) {
	}

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
			screen.refreshSlots();
		}
	}

	@Override
	protected void init2() {
		super.init2();
		instance = this;
		slots.clear();

		final int px = (getWidthMapped() - PANEL_WIDTH) / 2;
		final int py = (getHeightMapped() - PANEL_HEIGHT) / 2;

		// ---- 模式（两种模式都显示）----
		addButton(px + 56, py + 32, 54,
				() -> optionLabel("tpws.mode.overspeed", config.getMode(), TpwsCoilConfig.MODE_OVERSPEED),
				() -> true, () -> setMode(TpwsCoilConfig.MODE_OVERSPEED));
		addButton(px + 114, py + 32, 54,
				() -> optionLabel("tpws.mode.signal", config.getMode(), TpwsCoilConfig.MODE_SIGNAL),
				() -> true, () -> setMode(TpwsCoilConfig.MODE_SIGNAL));

		// ---- 超速模式 ----
		final BooleanSupplier overspeed = () -> !config.isSignalMode();
		addButton(px + 56, py + 58, 52,
				() -> optionLabel("tpws.threshold.fixed", config.getSpeedMode(), TpwsCoilConfig.SPEED_FIXED),
				overspeed, () -> setSpeedMode(TpwsCoilConfig.SPEED_FIXED));
		addButton(px + 112, py + 58, 56,
				() -> optionLabel("tpws.threshold.line", config.getSpeedMode(), TpwsCoilConfig.SPEED_LINE),
				overspeed, () -> setSpeedMode(TpwsCoilConfig.SPEED_LINE));

		// 步进按钮的数值是「步数」，一步 = TpwsCoilConfig.SPEED_STEP km/h
		addButton(px + 88, py + 84, 20, () -> "-10",
				overspeed, () -> nudgeSpeed(-2));
		addButton(px + 110, py + 84, 18, () -> "-5",
				overspeed, () -> nudgeSpeed(-1));
		addButton(px + 130, py + 84, 18, () -> "+5",
				overspeed, () -> nudgeSpeed(1));
		addButton(px + 150, py + 84, 18, () -> "+10",
				overspeed, () -> nudgeSpeed(2));

		final BooleanSupplier lineSpeed = () -> !config.isSignalMode() && config.isLineSpeedMode();
		addButton(px + 130, py + 108, 18, () -> "-", lineSpeed, () -> config.nudgeMarginKmh(-1));
		addButton(px + 150, py + 108, 18, () -> "+", lineSpeed, () -> config.nudgeMarginKmh(1));

		// ---- 信号模式 ----
		final BooleanSupplier signal = () -> config.isSignalMode();
		addButton(px + 8, py + 112, 78, () -> Lang.gui("tpws.auto_link"),
				signal, () -> sendToServer(PacketTpwsConfig.ACTION_AUTO_LINK));
		addButton(px + 90, py + 112, 56, () -> Lang.gui("tpws.unlink"),
				() -> config.isSignalMode() && config.hasSignal(),
				() -> sendToServer(PacketTpwsConfig.ACTION_UNLINK));

		// ---- 触发方向（两种模式共用）----
		addButton(px + 48, py + 134, 80,
				() -> Lang.gui(directionKey(config.getDirection())),
				() -> true, () -> {
					config.cycleDirection();
					refreshSlots();
				});

		// ---- 启用开关（两种载体共用：线圈方块放下即启用，轨道节点默认关闭）----
		addButton(px + 28, py + 176, 120,
				() -> Lang.gui("tpws.enabled") + ": " + Lang.gui(config.isEnabled() ? "tpws.state.on" : "tpws.state.off"),
				() -> true, () -> {
					config.toggleEnabled();
					refreshSlots();
				});

		// ---- 底部 ----
		addButton(px + 29, py + 200, 56, () -> Lang.gui("tpws.done"), () -> true, () -> {
			sendToServer(PacketTpwsConfig.ACTION_SAVE);
			onClose2();
		});
		addButton(px + 91, py + 200, 56, () -> Lang.gui("tpws.cancel"), () -> true, this::onClose2);

		refreshSlots();
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
	// 按钮
	// ------------------------------------------------------------------

	/**
	 * 建一个原版按钮并挂到界面上。
	 *
	 * <p>{@code new ClickableWidget(widget)} 是 MTR 自己的写法：{@code addChild}
	 * 收的是映射层的包装类，而 {@link ButtonWidgetExtension} 是 vanilla 按钮的子类，
	 * 直接包一层就行。
	 */
	private void addButton(int x, int y, int width, Supplier<String> label, BooleanSupplier visible, Runnable action) {
		final ButtonWidgetExtension widget = new ButtonWidgetExtension(
				x, y, width, BUTTON_HEIGHT,
				TextHelper.literal(label.get()),
				button -> action.run()
		);
		widget.setVisibleMapped(visible.getAsBoolean());
		addChild(new ClickableWidget(widget));
		slots.add(new Slot(widget, label, visible));
	}

	/**
	 * 按当前状态刷新所有按钮的文字与可见性。
	 *
	 * <p>文字里带 {@code "> "} 前缀的那个就是当前选项 —— 原版按钮没有"选中态"，
	 * 用前缀是最不容易出错的做法（不需要额外的纹理，也不会和禁用态混淆）。
	 */
	private void refreshSlots() {
		for (final Slot slot : slots) {
			// setMessage2 收的是 Text，TextHelper.literal 返回 MutableText —— 映射层里这两个是
			// 平行的包装类（MutableText 不是 Text 的子类），所以这里走 Text.of
			slot.widget().setMessage2(Text.of(slot.label().get()));
			slot.widget().setVisibleMapped(slot.visible().getAsBoolean());
		}
	}

	/**
	 * 生成一个"选项按钮"的文字。
	 *
	 * <p>当前选中的那个加 {@code "> "} 前缀 —— 原版按钮没有选中态，
	 * 用前缀是最不容易出错的做法（不需要额外纹理，也不会和禁用态混淆）。
	 */
	private static String optionLabel(String key, int current, int value) {
		return (current == value ? "> " : "") + Lang.gui(key);
	}

	/**
	 * 方向值 → 语言键。
	 *
	 * <p>用<b>绝对世界方向</b>而不是「正向 / 反向」：映射层的 {@code BlockState.get}
	 * 返回的是 vanilla 枚举、不做 holder 包装，从方块实体里读朝向要多绕一层；
	 * 而绝对方向对玩家也更直观 —— 线圈可以横跨轨道，触发方向本来就不必等于模型朝向。
	 */
	private static String directionKey(int direction) {
		return switch (direction) {
			case TpwsCoilConfig.DIRECTION_NORTH -> "tpws.direction.north";
			case TpwsCoilConfig.DIRECTION_EAST -> "tpws.direction.east";
			case TpwsCoilConfig.DIRECTION_SOUTH -> "tpws.direction.south";
			case TpwsCoilConfig.DIRECTION_WEST -> "tpws.direction.west";
			default -> "tpws.direction.both";
		};
	}

	private void setMode(int mode) {
		config.setMode(mode);
		refreshSlots();
	}

	private void setSpeedMode(int speedMode) {
		config.setSpeedMode(speedMode);
		refreshSlots();
	}

	private void nudgeSpeed(int delta) {
		config.nudgeSpeedLimitKmh(delta);
		refreshSlots();
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

	// ------------------------------------------------------------------
	// 绘制
	// ------------------------------------------------------------------

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		final int px = (getWidthMapped() - PANEL_WIDTH) / 2;
		final int py = (getHeightMapped() - PANEL_HEIGHT) / 2;

		drawPanel(guiDrawing, px, py);

		graphicsHolder.drawCenteredText(Lang.gui("tpws.title"), px + PANEL_WIDTH / 2, py + 6, ARGB_TEXT);
		graphicsHolder.drawCenteredText(
				Lang.gui("tpws.coord", blockPos.getX(), blockPos.getY(), blockPos.getZ()),
				px + PANEL_WIDTH / 2, py + 18, ARGB_DIM
		);

		label(graphicsHolder, Lang.gui("tpws.mode"), px + LABEL_X, py + 38, ARGB_TEXT);

		if (config.isSignalMode()) {
			drawSignalSection(graphicsHolder, px, py);
		} else {
			drawOverspeedSection(graphicsHolder, px, py);
		}

		label(graphicsHolder, Lang.gui("tpws.direction"), px + LABEL_X, py + 140, ARGB_TEXT);

		if (!config.isEnabled()) {
			// 停用时把提示换成警告色 —— 参数区照样能调，但不生效这件事必须显眼
			graphicsHolder.drawCenteredText(
					Lang.gui("tpws.hint.disabled"),
					px + PANEL_WIDTH / 2, py + 158, ARGB_WARN
			);
		} else {
			graphicsHolder.drawCenteredText(
					Lang.gui(config.isSignalMode() ? "tpws.hint.signal" : "tpws.hint.overspeed"),
					px + PANEL_WIDTH / 2, py + 158, ARGB_DIM
			);
		}

		// 原版按钮由 Screen 的 children 机制绘制，必须放在面板之后
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	/**
	 * 画原版面板：黑色描边 + {@code #C6C6C6} 底 + 白高光（顶 / 左）+ 深灰阴影（底 / 右）。
	 *
	 * <p>这就是原版容器界面的全部视觉构成，任意尺寸都成立。
	 */
	private static void drawPanel(GuiDrawing guiDrawing, int x, int y) {
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(x - 1, y - 1, x + PANEL_WIDTH + 1, y + PANEL_HEIGHT + 1, ARGB_OUTLINE);
		guiDrawing.drawRectangle(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, ARGB_PANEL);
		guiDrawing.drawRectangle(x, y, x + PANEL_WIDTH, y + 1, ARGB_HIGHLIGHT);
		guiDrawing.drawRectangle(x, y, x + 1, y + PANEL_HEIGHT, ARGB_HIGHLIGHT);
		guiDrawing.drawRectangle(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, ARGB_SHADOW);
		guiDrawing.drawRectangle(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, ARGB_SHADOW);
		guiDrawing.finishDrawingRectangle();
	}

	/** 超速模式的参数区。 */
	private void drawOverspeedSection(GraphicsHolder graphicsHolder, int px, int py) {
		label(graphicsHolder, Lang.gui("tpws.threshold"), px + LABEL_X, py + 64, ARGB_TEXT);

		label(graphicsHolder, Lang.gui("tpws.speed_limit"), px + LABEL_X, py + 90, ARGB_TEXT);
		label(
				graphicsHolder,
				config.getSpeedLimitKmh() + " " + Lang.gui("tpws.unit.kmh"),
				px + 34, py + 90,
				config.isLineSpeedMode() ? ARGB_DIM : ARGB_VALUE
		);

		label(graphicsHolder, Lang.gui("tpws.margin"), px + LABEL_X, py + 114, ARGB_TEXT);
		label(
				graphicsHolder,
				"+" + config.getMarginKmh() + " " + Lang.gui("tpws.unit.kmh"),
				px + 34, py + 114,
				config.isLineSpeedMode() ? ARGB_VALUE : ARGB_DIM
		);
	}

	/**
	 * 信号模式的参数区。
	 *
	 * <p>「颜色」那一行是关键：MTR 的信号机只有在<b>配置过颜色</b>之后才会亮，
	 * 而颜色是在信号机自己的配置界面里选的。读到 {@link TpwsCoilConfig#NO_COLOR}
	 * 就说明这个信号机还没接入轨道，这里直接把原因写出来，省得玩家对着不亮的灯猜。
	 */
	private void drawSignalSection(GraphicsHolder graphicsHolder, int px, int py) {
		label(graphicsHolder, Lang.gui("tpws.signal"), px + LABEL_X, py + 64, ARGB_TEXT);

		if (config.hasSignal()) {
			final BlockPos signalPos = BlockPos.fromLong(config.getSignalPos());
			label(
					graphicsHolder,
					Lang.gui("tpws.signal.linked", signalPos.getX(), signalPos.getY(), signalPos.getZ()),
					px + LABEL_X, py + 80, ARGB_VALUE
			);

			if (config.hasSignalColor()) {
				label(
						graphicsHolder,
						Lang.gui("tpws.signal.color", Integer.toHexString(config.getSignalColor()).toUpperCase()),
						px + LABEL_X, py + 94, ARGB_TEXT
				);
			} else {
				label(graphicsHolder, Lang.gui("tpws.signal.no_color"), px + LABEL_X, py + 94, ARGB_WARN);
			}
		} else {
			label(graphicsHolder, Lang.gui("tpws.signal.none"), px + LABEL_X, py + 80, ARGB_DIM);
		}
	}

	/** 画一行不带阴影的文字（原版面板上的标签就是这个画法）。 */
	private static void label(GraphicsHolder graphicsHolder, String text, int x, int y, int color) {
		graphicsHolder.drawText(text, x, y, color, false, GraphicsHolder.getDefaultLight());
	}
}
