package org.mtr.signaldashboard.data;

/**
 * GUI 布局与配色常量。
 *
 * <p>这里的数值由可视化原型 {@code design/dashboard-prototype.html} 调好后导出，
 * 集中放在一处，改界面风格时只需要动这个文件。
 *
 * <p>其中一部分（{@link #TEXT_HEIGHT} 等）刻意与 MTR 自身的
 * {@code org.mtr.mod.data.IGui} 保持接近，让信号调度板的视觉风格和原版界面衔接。
 *
 * @author Mewzica
 */
public interface IGui {

	// ---- 布局 ----
	int SQUARE_SIZE = 20;
	int TEXT_HEIGHT = 10;
	int TEXT_PADDING = 5;
	int LINE_HEIGHT = 14;

	// ---- 面板配色 ----
	int ARGB_WHITE = 0xFFFFFFFF;
	int ARGB_BLACK = 0xFF000000;
	int ARGB_BACKGROUND = 0xFF121212;
	int ARGB_CANVAS = 0xFF0A0A0A;
	int ARGB_LIGHT_GRAY = 0xFFAAAAAA;
	int ARGB_GRAY = 0xFF555555;

	// ---- 轨道 ----
	int ARGB_RAIL_FREE = 0xFF44AA44;
	int ARGB_RAIL_OCCUPIED = 0xFFFF0000;
	int ARGB_RAIL_SELECTED = 0xFFFFD54F;

	/** 列表左侧占用指示条：空闲时的颜色（占用时用 {@link #ARGB_RAIL_OCCUPIED}）。 */
	int ARGB_OCCUPANCY_FREE = 0xFF00AA00;

	double RAIL_WIDTH = 1.5;
	double RAIL_WIDTH_SELECTED = 1.5;

	/** 是否在轨道中点标出它的第一个信号颜色。 */
	boolean SHOW_RAIL_MARKERS = false;

	// ---- 节点 ----
	int ARGB_NODE_NORMAL = 0xFFAAAAAA;
	int ARGB_NODE_JUNCTION = 0xFFFFE082;
	int ARGB_NODE_HOVER = 0xFFFFFFFF;
	int ARGB_NODE_A = 0xFFFFAA00;
	int ARGB_NODE_B = 0xFF00AAFF;

	double NODE_RADIUS = 2.5;
	double NODE_RADIUS_JUNCTION = 2.5;
	double NODE_RADIUS_HOVER = 2.5;
	double NODE_RADIUS_SELECTED = 2.5;

	// ---- 左侧列表 ----
	int ARGB_ROW_HOVER = 0xFF2A2A2A;
	int ARGB_ROW_SELECTED = 0xFF3E5A3E;
	int ARGB_ROW_TEXT_SELECTED = 0xFFFFD54F;
	int ARGB_SCROLL_TRACK = 0xFF2A2A2A;
	int ARGB_SCROLL_THUMB = 0xFF888888;

	int SWATCH_W = 4;
	int SWATCH_H = 6;
	int SWATCH_GAP = 6;

	/** 每行最多显示几个信号颜色块。 */
	int MAX_SWATCHES = 8;

	// ---- 地形底图 ----
	/** 界面打开时是否绘制地形（之后由「地形」按钮切换）。 */
	boolean SHOW_TERRAIN = true;

	/**
	 * 地形不透明度（1.0 = 完全不透明）。
	 *
	 * <p>{@code GuiDrawing.drawTexture} 没有 alpha 参数，所以实现方式是
	 * 在地形之上叠一层「画布底色 + (1 - 该值) 透明度」的矩形来等效淡出。
	 *
	 * <p>注意：MTR 烘焙地形贴图时还会按方块光照再压暗一次
	 * （{@code WorldMap} 私有的 {@code LIGHT_DIM_FACTOR = 1.5}），
	 * 那个系数无法从外部覆盖。
	 */
	double TERRAIN_ALPHA = 1.0;

	/** 是否在地形上叠网格，便于估算距离。 */
	boolean SHOW_GRID = false;

	/** 网格间距（方块）。 */
	int GRID_STEP = 32;

	// ---- 右侧按钮 ----
	int BUTTON_MIN_H = 18;
	int BUTTON_MAX_H = 18;
	int BUTTON_GAP = 3;

	// ---- 驾驶 HUD（并入 MTR 驾驶仪表盘）----
	/**
	 * 距屏幕右 / 下边缘的距离。
	 *
	 * <p>对应 MTR {@code DrivingGuiRenderer} 的 {@code EDGE_PADDING}（私有常量，抄过来）。
	 * 我们的控件要贴住 MTR 的表盘，所以必须和它一致；MTR 若改了布局需要同步。
	 */
	int DRIVING_HUD_EDGE_PADDING = 16;

	/**
	 * MTR 表盘的边长（占用的正方形区域）。
	 *
	 * <p>对应 MTR {@code DrivingGuiRenderer} 的 {@code TOOL_SIZE}（私有常量，抄过来）。
	 */
	int DRIVING_HUD_SPEEDOMETER_SIZE = 96;

	/** 行内边距（与 MTR 的 {@code PADDING} 一致）。 */
	int DRIVING_HUD_PADDING = 4;

	// ---- 控件：发车时间 ----
	/** 「发车时间」控件<b>右缘</b>距屏幕右边缘的距离（由 {@code design/driving-hud-editor.html} 导出）。 */
	int DRIVING_HUD_DEPARTURE_RIGHT_INSET = 50;

	/** 「发车时间」控件<b>下缘</b>距屏幕下边缘的距离（同上）。 */
	int DRIVING_HUD_DEPARTURE_BOTTOM_INSET = 125;

	// ---- 控件：反相器 ----
	/**
	 * 「反相器」指示器相对<b>表盘中心</b>的水平偏移（表盘局部坐标，右为正）。
	 *
	 * <p>MTR 自己在表盘左上角画功率指示（{@code N} / {@code +60%} / {@code -30%} / {@code E}），
	 * 它的绘制原点是表盘局部 {@code (-14.4, -10.8)}，而 MTR 的 {@code drawCenteredText}
	 * 自带 {@code x - 4} 偏移，所以那个指示器的水平中心约在 {@code -18.4}。
	 * 下面这一组值就是把反相器摆在它正下方。
	 */
	int DRIVING_HUD_REVERSER_OFFSET_X = -18;

	/** 「反相器」指示器相对表盘中心的垂直偏移（表盘局部坐标，下为正）。 */
	int DRIVING_HUD_REVERSER_OFFSET_Y = 9;

	/** 倒计时进入「即将发车」的阈值（毫秒）。 */
	long DRIVING_HUD_WARN_MILLIS = 5000L;

	/**
	 * 左侧标签文字色。
	 *
	 * <p>另有两个常量 {@code ARGB_HUD_BACKGROUND} / {@code ARGB_HUD_BORDER}（MTR 表盘的
	 * {@code 0xFF111111} / {@code 0xFF666666}）已经删掉 —— 两个控件现在都是纯文字、
	 * <b>不画底框</b>，看起来才是仪表盘的一部分而不是外挂的表格。
	 */
	int ARGB_HUD_LABEL = 0xFFAAAAAA;

	/** 普通数值文字色。 */
	int ARGB_HUD_TEXT = 0xFFFFFFFF;

	/** 强调色（时间充裕）。 */
	int ARGB_HUD_ACCENT = 0xFF00E676;

	/** 警告色（即将发车）。 */
	int ARGB_HUD_WARN = 0xFFFFB300;
}
