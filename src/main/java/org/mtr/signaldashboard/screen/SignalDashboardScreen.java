package org.mtr.signaldashboard.screen;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.screen.WorldMap;
import org.mtr.signaldashboard.MTRSignalDashboardClient;
import org.mtr.signaldashboard.data.IGui;
import org.mtr.signaldashboard.data.Lang;
import org.mtr.signaldashboard.data.SignalRailData;
import org.mtr.signaldashboard.packet.PacketRequestSignalBlocks;
import org.mtr.signaldashboard.packet.PacketUpdateSignalLink;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信号调度板主界面。
 *
 * <p>界面分为三个区域：
 * <ul>
 *     <li><b>左侧列表</b>：所有扫描到的轨道，标注信号颜色与占用状态</li>
 *     <li><b>中央画布</b>：轨道的俯视示意图，按真实走向绘制，点击节点进行选择</li>
 *     <li><b>右侧面板</b>：16 色信号选择器 + 操作按钮</li>
 * </ul>
 *
 * <p>典型操作流程：在画布上点选节点 A → 点选节点 B → 在右侧选颜色 → 点"连接"。
 *
 * <p>画布绘制的是 {@link SignalRailData#path} 里的<b>真实路径折线</b>
 * （由服务端沿弧长采样得到），而不是端点之间的直线，所以弯道会如实呈现，
 * 共享端点的两条轨道也会在画面上真正接上。
 *
 * @author Mewzica
 */
public class SignalDashboardScreen extends ScreenExtension implements IGui {

	/** MTR 信号连接器使用的 16 种颜色，顺序与 ItemSignalModifier.COLORS 一致。 */
	public static final int[] SIGNAL_COLORS = {
			0xFFFFFF, 0xFF8C00, 0xFF00FF, 0x87CEEB,
			0xFFFF00, 0x7FFF00, 0xFF69B4, 0x808080,
			0xC0C0C0, 0x00FFFF, 0x800080, 0x0000FF,
			0x8B4513, 0x00FF00, 0xFF0000, 0x000000,
	};

	/** 右侧面板按钮的配色（顺序：连接 / 断开 / 清空 / 复位视图 / 刷新 / 地形）。 */
	private static final int[] BUTTON_COLORS = {0x2E7D32, 0x8B2500, 0x444444, 0x37474F, 0x1565C0, 0x5D4037};

	/** 右侧面板按钮数量。 */
	private static final int BUTTON_COUNT = 6;

	/** 「地形」按钮的下标。 */
	private static final int BUTTON_TERRAIN = 5;

	private static SignalDashboardScreen instance;

	private SignalRailData[] rails = new SignalRailData[0];

	/**
	 * 地形底图。
	 *
	 * <p>直接复用 MTR 自己的 {@link WorldMap}：它会把玩家周围已加载区块
	 * 逐块取顶面方块的颜色，烘焙成 16×16 的贴图。我们只借用它的贴图，
	 * 绘制时套用自己的视图变换，这样地形和轨道能严格对齐。
	 *
	 * <p>注意 {@code WorldMap} 本身<b>不</b>依赖
	 * {@code MinecraftClientData.getDashboardInstance()}（只有 {@code WidgetMap} 依赖），
	 * 所以可以安全地脱离 MTR 的仪表板单独使用。
	 */
	private final WorldMap worldMap = new WorldMap();

	/** 是否绘制地形底图（初始值由 {@link IGui#SHOW_TERRAIN} 决定）。 */
	private boolean showTerrain = SHOW_TERRAIN;

	/** 去重后的轨道端点（节点）。key 是 BlockPos.asLong 编码。 */
	private final Map<Long, Node> nodes = new LinkedHashMap<>();

	/** 画布上两个已选中的节点。 */
	private long selectedNode1 = Long.MIN_VALUE;
	private long selectedNode2 = Long.MIN_VALUE;

	/** 当前在右侧选中的颜色索引。 */
	private int selectedColorIndex = 14; // 默认红色

	/** 鼠标悬停的节点。 */
	private long hoveredNode = Long.MIN_VALUE;

	/** 用户手动缩放 / 平移（在自动适配的基础上叠加）。 */
	private float userScale = 1F;
	private double panX = 0;
	private double panY = 0;

	/** 每帧根据轨道包围盒算出的自动适配参数。 */
	private double viewCenterX;
	private double viewCenterZ;
	private float viewFitScale = 1F;

	/** 鼠标拖拽状态。 */
	private boolean dragging = false;
	private double dragStartX = 0;
	private double dragStartY = 0;
	private double dragOriginX = 0;
	private double dragOriginY = 0;

	/** 画布区域。 */
	private int canvasLeft;
	private int canvasTop;
	private int canvasRight;
	private int canvasBottom;

	/** 左侧列表宽度、右侧颜色面板宽度。 */
	private int listWidth;
	private int colorWidth;

	/** 当前在左侧列表里选中的轨道下标，-1 表示未选。 */
	private int selectedRailIndex = -1;

	/** 鼠标悬停的列表行下标，-1 表示无。 */
	private int hoveredRailRow = -1;

	/** 左侧列表的滚动偏移（以行为单位）。 */
	private int listScroll = 0;

	/**
	 * 状态栏提示文本。
	 *
	 * <p>为 {@code null} 时显示默认操作提示（每次绘制时才解析译文，
	 * 这样玩家切换语言后无需重开界面）。
	 */
	private String statusMessage = null;

	public SignalDashboardScreen() {
		super();
		setRails(PacketRequestSignalBlocks.getCachedRails());
		instance = this;
	}

	/**
	 * 打开本屏幕（须在客户端主线程调用）。
	 */
	public static void open() {
		final MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> client.openScreen(new Screen(new SignalDashboardScreen())));
	}

	public static SignalDashboardScreen getInstance() {
		return instance;
	}

	/** 网络数据刷新时由包调用。 */
	public static void refresh(SignalRailData[] newRails) {
		final SignalDashboardScreen screen = instance;
		if (screen != null) {
			screen.setRails(newRails);
			screen.statusMessage = Lang.gui("status.refreshed", newRails.length);
		}
	}

	private void setRails(SignalRailData[] newRails) {
		rails = newRails == null ? new SignalRailData[0] : newRails;
		rebuildNodes();
		if (selectedRailIndex >= rails.length) {
			selectedRailIndex = -1;
		}
	}

	/**
	 * 重建去重后的节点表，并统计每个节点被几条轨道共用。
	 *
	 * <p>共用数 ≥ 2 的节点是"岔口"，信号连接通常就发生在这些点上，
	 * 画面上会用更大的亮点标出来。
	 */
	private void rebuildNodes() {
		nodes.clear();
		for (final SignalRailData rail : rails) {
			if (rail.getPointCount() < 2) {
				continue;
			}
			// 起点 = path 的第一个点，终点 = path 的最后一个点
			addNode(rail.pos1, rail.getPathX(0), rail.getPathY(0), rail.getPathZ(0));
			final int last = rail.getPointCount() - 1;
			addNode(rail.pos2, rail.getPathX(last), rail.getPathY(last), rail.getPathZ(last));
		}
	}

	private void addNode(long id, double x, double y, double z) {
		final Node existing = nodes.get(id);
		if (existing == null) {
			nodes.put(id, new Node(id, x, y, z));
		} else {
			existing.count++;
		}
	}

	private void resetView() {
		userScale = 1F;
		panX = 0;
		panY = 0;
	}

	@Override
	protected void init2() {
		super.init2();
		final int width = getWidthMapped();
		final int height = getHeightMapped();

		// 面板宽度随窗口自适应：窗口越窄，两侧面板越窄，把空间尽量留给画布。
		listWidth = clamp(width / 6, 64, 150);
		colorWidth = clamp(width / 9, 58, 100);

		canvasLeft = listWidth;
		canvasRight = Math.max(canvasLeft + 60, width - colorWidth);
		canvasTop = TEXT_HEIGHT * 2 + 4;
		canvasBottom = height - TEXT_HEIGHT * 2 - 2;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	// ------------------------------------------------------------------
	// 渲染
	// ------------------------------------------------------------------

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		updateView();

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);

		graphicsHolder.drawText(
				Lang.gui("title"),
				TEXT_PADDING, TEXT_PADDING,
				ARGB_WHITE, false, GraphicsHolder.getDefaultLight()
		);

		drawRailList(graphicsHolder, guiDrawing, mouseX, mouseY);
		drawCanvas(graphicsHolder, guiDrawing, mouseX, mouseY, delta);
		drawColorPanel(graphicsHolder, guiDrawing);

		// 悬停列表行时，底部改为显示这条轨道的完整信息
		final String bottomText;
		if (hoveredRailRow >= 0 && hoveredRailRow < rails.length) {
			bottomText = Lang.gui("hover_row", hoveredRailRow + 1, describeRail(rails[hoveredRailRow]));
		} else {
			bottomText = statusMessage != null ? statusMessage : Lang.gui("status.hint");
		}
		graphicsHolder.drawText(
				bottomText,
				TEXT_PADDING, getHeightMapped() - TEXT_HEIGHT - 2,
				ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight()
		);

		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	/**
	 * 绘制左侧轨道列表。
	 *
	 * <p>列表是可交互的：单击选中一条轨道（画布上会高亮并自动定位），
	 * 再点一次取消；鼠标滚轮在列表上滚动翻页。
	 */
	private void drawRailList(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing, int mouseX, int mouseY) {
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(0, canvasTop, listWidth, canvasBottom, ARGB_BACKGROUND);
		guiDrawing.finishDrawingRectangle();

		graphicsHolder.drawText(
				Lang.gui("rail_list_header", rails.length),
				TEXT_PADDING, canvasTop + 2,
				ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight()
		);

		final int rowTop = getListRowTop();
		final int maxRows = getListMaxRows();
		clampListScroll();

		// 悬停行
		hoveredRailRow = -1;
		if (mouseX >= 0 && mouseX < listWidth && mouseY >= rowTop && mouseY < canvasBottom) {
			final int row = (mouseY - rowTop) / LINE_HEIGHT;
			final int index = listScroll + row;
			if (row >= 0 && row < maxRows && index < rails.length) {
				hoveredRailRow = index;
			}
		}

		final int count = Math.min(rails.length - listScroll, maxRows);
		for (int i = 0; i < count; i++) {
			final int index = listScroll + i;
			final SignalRailData rail = rails[index];
			final int y = rowTop + i * LINE_HEIGHT;

			guiDrawing.beginDrawingRectangle();
			// 选中 / 悬停背景
			if (index == selectedRailIndex) {
				guiDrawing.drawRectangle(0, y, listWidth, y + LINE_HEIGHT - 1, ARGB_ROW_SELECTED);
			} else if (index == hoveredRailRow) {
				guiDrawing.drawRectangle(0, y, listWidth, y + LINE_HEIGHT - 1, ARGB_ROW_HOVER);
			}
			// 占用状态指示条
			guiDrawing.drawRectangle(0, y, 2, y + LINE_HEIGHT - 1, rail.occupied ? ARGB_RAIL_OCCUPIED : ARGB_OCCUPANCY_FREE);
			// 信号颜色块（从右往左排，最多 MAX_SWATCHES 个，垂直居中）
			final int swatchTop = y + (LINE_HEIGHT - SWATCH_H) / 2;
			for (int c = 0; c < rail.signalColors.length && c < MAX_SWATCHES; c++) {
				final int colorRight = listWidth - SWATCH_W - c * SWATCH_GAP;
				guiDrawing.drawRectangle(
						colorRight - SWATCH_W, swatchTop,
						colorRight, swatchTop + SWATCH_H,
						rail.signalColors[c] | ARGB_BLACK
				);
			}
			guiDrawing.finishDrawingRectangle();

			graphicsHolder.drawText(
					trimToWidth(getRowLabel(index), listWidth - TEXT_PADDING - swatchWidth(rail)),
					TEXT_PADDING, y,
					index == selectedRailIndex ? ARGB_ROW_TEXT_SELECTED : ARGB_WHITE,
					false, GraphicsHolder.getDefaultLight()
			);
		}

		// 滚动条
		if (maxRows > 0 && rails.length > maxRows) {
			final int trackHeight = maxRows * LINE_HEIGHT;
			final int thumbHeight = Math.max(6, trackHeight * maxRows / rails.length);
			final int maxScroll = rails.length - maxRows;
			final int thumbY = rowTop + (trackHeight - thumbHeight) * listScroll / maxScroll;

			guiDrawing.beginDrawingRectangle();
			guiDrawing.drawRectangle(listWidth - 2, rowTop, listWidth - 1, rowTop + trackHeight, ARGB_SCROLL_TRACK);
			guiDrawing.drawRectangle(listWidth - 2, thumbY, listWidth - 1, thumbY + thumbHeight, ARGB_SCROLL_THUMB);
			guiDrawing.finishDrawingRectangle();
		}

		if (rails.length == 0) {
			graphicsHolder.drawText(
					Lang.gui("no_rails"),
					TEXT_PADDING, rowTop + 4,
					ARGB_GRAY, false, GraphicsHolder.getDefaultLight()
			);
			graphicsHolder.drawText(
					Lang.gui("no_rails_hint"),
					TEXT_PADDING, rowTop + 4 + LINE_HEIGHT,
					ARGB_GRAY, false, GraphicsHolder.getDefaultLight()
			);
		}
	}

	/** 列表第一行的 y 坐标。 */
	private int getListRowTop() {
		return canvasTop + LINE_HEIGHT + 2;
	}

	/** 列表最多能显示多少行。 */
	private int getListMaxRows() {
		return Math.max(0, (canvasBottom - getListRowTop()) / LINE_HEIGHT);
	}

	private void clampListScroll() {
		final int maxScroll = Math.max(0, rails.length - getListMaxRows());
		listScroll = Math.max(0, Math.min(maxScroll, listScroll));
	}

	/**
	 * 列表行的简短文本。
	 *
	 * <p>列表很窄（窄窗口下只有几十像素），所以这里只放"序号 + 站台/存车 + 限速"，
	 * 完整的长度/颜色数/占用信息在鼠标悬停时显示在底部状态栏。
	 *
	 * <p>不做缓存：每次绘制都按当前游戏语言实时解析，这样玩家切换语言后
	 * 界面立刻跟着变，不用重开。
	 */
	private String getRowLabel(int index) {
		final SignalRailData rail = rails[index];
		final StringBuilder builder = new StringBuilder();
		builder.append('#').append(index + 1).append(' ');
		if (rail.isPlatform) {
			builder.append(Lang.gui("rail.platform")).append(' ');
		} else if (rail.isSiding) {
			builder.append(Lang.gui("rail.siding")).append(' ');
		}
		builder.append((int) rail.speedLimit);
		return builder.toString();
	}

	/** 列表行右侧信号色块占用的像素宽度。 */
	private static int swatchWidth(SignalRailData rail) {
		final int shown = Math.min(rail.signalColors.length, MAX_SWATCHES);
		return shown == 0 ? SWATCH_W + 2 : shown * SWATCH_GAP + SWATCH_W + 2;
	}

	private static String trimToWidth(String text, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (GraphicsHolder.getTextWidth(text) <= maxWidth) {
			return text;
		}
		final String ellipsis = "…";
		final int ellipsisWidth = GraphicsHolder.getTextWidth(ellipsis);
		int low = 0;
		int high = text.length();
		while (low < high) {
			final int mid = (low + high + 1) / 2;
			if (GraphicsHolder.getTextWidth(text.substring(0, mid)) + ellipsisWidth <= maxWidth) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return text.substring(0, low) + ellipsis;
	}

	/**
	 * 绘制中央俯视画布：地形底图 + 真实路径折线。
	 */
	private void drawCanvas(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing, int mouseX, int mouseY, float delta) {
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(canvasLeft, canvasTop, canvasRight, canvasBottom, ARGB_CANVAS);
		guiDrawing.finishDrawingRectangle();

		// ---- 地形底图 ----
		// 贴图用 beginDrawingTexture/finishDrawingTexture 单独成批，
		// 必须和矩形绘制分开，不能交叉。
		// 即使没有轨道也画，方便用户确认自己在地图上的位置。
		if (showTerrain) {
			tickWorldMap(delta);
			drawMapTiles(guiDrawing);
			fadeTerrain(guiDrawing);
		}

		if (rails.length == 0) {
			return;
		}

		// 更新悬停节点（要在绘制前算，才能当帧高亮）
		hoveredNode = findNodeAt(mouseX, mouseY);

		guiDrawing.beginDrawingRectangle();

		// ---- 网格（叠在地形之上、轨道之下）----
		if (SHOW_GRID) {
			drawGrid(guiDrawing);
		}

		// ---- 轨道折线 ----
		for (final SignalRailData rail : rails) {
			drawRailPath(guiDrawing, rail, rail.occupied ? ARGB_RAIL_OCCUPIED : ARGB_RAIL_FREE, RAIL_WIDTH);
		}

		// 选中的轨道再叠一遍（更亮），保证压在其它轨道上层
		if (selectedRailIndex >= 0 && selectedRailIndex < rails.length) {
			drawRailPath(guiDrawing, rails[selectedRailIndex], ARGB_RAIL_SELECTED, RAIL_WIDTH_SELECTED);
		}

		// ---- 节点 ----
		for (final Node node : nodes.values()) {
			final double x = toScreenX(node.x);
			final double y = toScreenY(node.z);

			final int nodeColor;
			double radius;
			if (node.id == selectedNode1) {
				nodeColor = ARGB_NODE_A;
				radius = NODE_RADIUS_SELECTED;
			} else if (node.id == selectedNode2) {
				nodeColor = ARGB_NODE_B;
				radius = NODE_RADIUS_SELECTED;
			} else if (node.id == hoveredNode) {
				nodeColor = ARGB_NODE_HOVER;
				radius = NODE_RADIUS_HOVER;
			} else if (node.count >= 2) {
				nodeColor = ARGB_NODE_JUNCTION;
				radius = NODE_RADIUS_JUNCTION;
			} else {
				nodeColor = ARGB_NODE_NORMAL;
				radius = NODE_RADIUS;
			}

			guiDrawing.drawRectangle(x - radius, y - radius, x + radius, y + radius, nodeColor | ARGB_BLACK);
		}

		guiDrawing.finishDrawingRectangle();

		// 左上角信息
		final String selectionText;
		if (selectedNode1 == Long.MIN_VALUE) {
			selectionText = Lang.gui("selection_none");
		} else if (selectedNode2 == Long.MIN_VALUE) {
			selectionText = Lang.gui("selection_one");
		} else {
			selectionText = Lang.gui("selection_both");
		}
		graphicsHolder.drawText(
				selectionText,
				canvasLeft + TEXT_PADDING, canvasTop + 3,
				ARGB_WHITE, false, GraphicsHolder.getDefaultLight()
		);
		graphicsHolder.drawText(
				Lang.gui("canvas_info", nodes.size(), Math.round(viewFitScale * userScale * 10) / 10.0),
				canvasLeft + TEXT_PADDING, canvasTop + 3 + LINE_HEIGHT,
				ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight()
		);
	}

	/**
	 * 把地形按 {@link IGui#TERRAIN_ALPHA} 淡出。
	 *
	 * <p>{@code GuiDrawing.drawTexture} 没有 alpha 参数，所以改为在地形之上
	 * 叠一层「画布底色」的半透明矩形，alpha 取 {@code 1 - TERRAIN_ALPHA}。
	 * 该值为 1.0 时透明度为 0，也就是完全不画，没有额外开销。
	 */
	private void fadeTerrain(GuiDrawing guiDrawing) {
		final int overlayAlpha = (int) Math.round((1 - TERRAIN_ALPHA) * 255);
		if (overlayAlpha <= 0) {
			return;
		}
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(canvasLeft, canvasTop, canvasRight, canvasBottom,
				(overlayAlpha << 24) | (ARGB_CANVAS & 0xFFFFFF));
		guiDrawing.finishDrawingRectangle();
	}

	/**
	 * 在地形之上叠一层网格，便于估算距离。
	 *
	 * <p>网格覆盖轨道折线的包围盒，间距 {@link IGui#GRID_STEP} 方块。
	 * {@code GuiDrawing} 只能画矩形，所以每条线用一个 1 像素宽的矩形表示。
	 *
	 * <p>用半透明白色：{@code GuiDrawing} 走的是带
	 * {@code TRANSLUCENT_TRANSPARENCY} 的 GUI 渲染层，alpha 会被正常混合。
	 */
	private void drawGrid(GuiDrawing guiDrawing) {
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (final SignalRailData rail : rails) {
			for (int i = 0; i < rail.getPointCount(); i++) {
				minX = Math.min(minX, rail.getPathX(i));
				maxX = Math.max(maxX, rail.getPathX(i));
				minZ = Math.min(minZ, rail.getPathZ(i));
				maxZ = Math.max(maxZ, rail.getPathZ(i));
			}
		}
		if (minX > maxX || minZ > maxZ) {
			return;
		}

		final int gridColor = 0x1AFFFFFF;

		for (double worldX = Math.floor(minX / GRID_STEP) * GRID_STEP; worldX <= maxX + GRID_STEP; worldX += GRID_STEP) {
			final double screenX = Math.round(toScreenX(worldX));
			if (screenX >= canvasLeft && screenX < canvasRight) {
				guiDrawing.drawRectangle(screenX, canvasTop, screenX + 1, canvasBottom, gridColor);
			}
		}
		for (double worldZ = Math.floor(minZ / GRID_STEP) * GRID_STEP; worldZ <= maxZ + GRID_STEP; worldZ += GRID_STEP) {
			final double screenY = Math.round(toScreenY(worldZ));
			if (screenY >= canvasTop && screenY < canvasBottom) {
				guiDrawing.drawRectangle(canvasLeft, screenY, canvasRight, screenY + 1, gridColor);
			}
		}
	}

	/**
	 * 驱动地形底图更新。
	 *
	 * <p>{@link WorldMap#tick} 内部有自己的节流（每 40 个 tick 单位才重烘一次），
	 * 所以这里每帧调用即可，不必自己再控频。
	 */
	private void tickWorldMap(float delta) {
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientWorld world = client.getWorldMapped();
		final ClientPlayerEntity player = client.getPlayerMapped();
		if (world == null || player == null) {
			return;
		}
		worldMap.tick(World.cast(world), player, delta);
	}

	/**
	 * 把地形贴图按当前视图变换画到画布上。
	 *
	 * <p>{@link WorldMap} 为每个区块烘焙一张 16×16 的贴图，贴图的
	 * {@code u} 对应世界 X、{@code v} 对应世界 Z。所以只要把区块的世界坐标
	 * 经 {@code toScreenX/Z} 投影，再把贴图铺上去，地形就和轨道共用同一套坐标系，
	 * 天然对齐。
	 *
	 * <p>画布外的部分用 UV 裁剪掉，避免贴图溢出到左右面板和标题栏。
	 */
	private void drawMapTiles(GuiDrawing guiDrawing) {
		worldMap.forEachTile(mapImage -> {
			final double worldX1 = mapImage.chunkX * (double) WorldMap.CHUNK_SIZE;
			final double worldZ1 = mapImage.chunkZ * (double) WorldMap.CHUNK_SIZE;
			final double worldX2 = worldX1 + WorldMap.CHUNK_SIZE;
			final double worldZ2 = worldZ1 + WorldMap.CHUNK_SIZE;

			final double x1 = toScreenX(worldX1);
			final double y1 = toScreenY(worldZ1);
			final double x2 = toScreenX(worldX2);
			final double y2 = toScreenY(worldZ2);

			// 整块都在画布外 → 跳过
			if (x2 < canvasLeft || x1 > canvasRight || y2 < canvasTop || y1 > canvasBottom) {
				return;
			}

			final double tileWidth = x2 - x1;
			final double tileHeight = y2 - y1;
			if (tileWidth <= 0 || tileHeight <= 0) {
				return;
			}

			// 裁剪到画布范围
			final double clippedX1 = Math.max(x1, canvasLeft);
			final double clippedY1 = Math.max(y1, canvasTop);
			final double clippedX2 = Math.min(x2, canvasRight);
			final double clippedY2 = Math.min(y2, canvasBottom);

			// 用 UV 保持贴图与世界的对应关系（而不是把贴图拉伸到裁剪框）
			final float u1 = (float) ((clippedX1 - x1) / tileWidth);
			final float u2 = (float) ((clippedX2 - x1) / tileWidth);
			final float v1 = (float) ((clippedY1 - y1) / tileHeight);
			final float v2 = (float) ((clippedY2 - y1) / tileHeight);

			guiDrawing.beginDrawingTexture(mapImage.textureId);
			guiDrawing.drawTexture(clippedX1, clippedY1, clippedX2, clippedY2, u1, v1, u2, v2);
			guiDrawing.finishDrawingTexture();
		});
	}

	private void drawColorPanel(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing) {
		final int panelLeft = getWidthMapped() - colorWidth;

		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(panelLeft, canvasTop, getWidthMapped(), canvasBottom, ARGB_BACKGROUND);
		guiDrawing.finishDrawingRectangle();

		graphicsHolder.drawText(
				Lang.gui("signal_colors"),
				panelLeft + TEXT_PADDING, canvasTop + 2,
				ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight()
		);

		// 4×4 颜色网格
		final int gridTop = canvasTop + LINE_HEIGHT + 2;
		final int cell = Math.max(8, Math.min(SQUARE_SIZE, (colorWidth - TEXT_PADDING * 2) / 4));
		for (int i = 0; i < SIGNAL_COLORS.length; i++) {
			final int col = i % 4;
			final int row = i / 4;
			final int x = panelLeft + TEXT_PADDING + col * cell;
			final int y = gridTop + row * cell;

			guiDrawing.beginDrawingRectangle();
			guiDrawing.drawRectangle(x, y, x + cell - 2, y + cell - 2, SIGNAL_COLORS[i] | ARGB_BLACK);
			if (i == selectedColorIndex) {
				// 选中：四边画白框
				guiDrawing.drawRectangle(x - 1, y - 1, x + cell - 1, y, ARGB_WHITE);
				guiDrawing.drawRectangle(x - 1, y + cell - 2, x + cell - 1, y + cell - 1, ARGB_WHITE);
				guiDrawing.drawRectangle(x - 1, y - 1, x, y + cell - 1, ARGB_WHITE);
				guiDrawing.drawRectangle(x + cell - 2, y - 1, x + cell - 1, y + cell - 1, ARGB_WHITE);
			}
			guiDrawing.finishDrawingRectangle();
		}

		final int gridBottom = gridTop + 4 * cell;
		graphicsHolder.drawText(
				Lang.color(selectedColorIndex),
				panelLeft + TEXT_PADDING, gridBottom + 3,
				ARGB_WHITE, false, GraphicsHolder.getDefaultLight()
		);

		// 按钮：高度按剩余空间自适应
		final int buttonTop = gridBottom + LINE_HEIGHT + 4;
		final int available = canvasBottom - buttonTop - 2;
		final int buttonHeight = clamp(available / BUTTON_COUNT - BUTTON_GAP, BUTTON_MIN_H, BUTTON_MAX_H);
		final int step = buttonHeight + BUTTON_GAP;
		for (int i = 0; i < BUTTON_COUNT; i++) {
			final int y = buttonTop + i * step;
			if (y + buttonHeight > canvasBottom) {
				break;
			}
			drawButton(graphicsHolder, guiDrawing, panelLeft + TEXT_PADDING, y,
					colorWidth - TEXT_PADDING * 2, buttonHeight, getButtonLabel(i), BUTTON_COLORS[i]);
		}
	}

	private String getButtonLabel(int index) {
		switch (index) {
			case 0:
				return Lang.gui("button.connect");
			case 1:
				return Lang.gui("button.disconnect");
			case 2:
				return Lang.gui("button.clear");
			case 3:
				return Lang.gui("button.reset_view");
			case 4:
				return Lang.gui("button.refresh");
			case BUTTON_TERRAIN:
				return Lang.gui(showTerrain ? "button.terrain_on" : "button.terrain_off");
			default:
				return "";
		}
	}

	private void drawButton(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing, int x, int y, int w, int h, String label, int color) {
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(x, y, x + w, y + h, color | ARGB_BLACK);
		guiDrawing.finishDrawingRectangle();
		graphicsHolder.drawCenteredText(label, x + w / 2, y + (h - TEXT_HEIGHT) / 2 + 1, ARGB_WHITE);
	}

	/**
	 * 画一条轨道的真实路径折线，并在中点标出它的信号颜色。
	 */
	private void drawRailPath(GuiDrawing guiDrawing, SignalRailData rail, int lineColor, double thickness) {
		final int points = rail.getPointCount();
		if (points < 2) {
			return;
		}
		for (int i = 0; i + 1 < points; i++) {
			drawThickLine(
					guiDrawing,
					toScreenX(rail.getPathX(i)), toScreenY(rail.getPathZ(i)),
					toScreenX(rail.getPathX(i + 1)), toScreenY(rail.getPathZ(i + 1)),
					thickness, lineColor
			);
		}
		// 信号颜色标记：画在轨道中点（由 SHOW_RAIL_MARKERS 控制）
		if (SHOW_RAIL_MARKERS && rail.signalColors.length > 0) {
			final int mid = points / 2;
			final double mx = toScreenX(rail.getPathX(mid));
			final double my = toScreenY(rail.getPathZ(mid));
			final double radius = thickness + 1;
			guiDrawing.drawRectangle(mx - radius, my - radius, mx + radius, my + radius, rail.signalColors[0] | ARGB_BLACK);
		}
	}

	/**
	 * 用多个小矩形拼出一条粗线（{@link GuiDrawing} 只有矩形可用）。
	 */
	private void drawThickLine(GuiDrawing guiDrawing, double x1, double y1, double x2, double y2, double thickness, int color) {
		final double dx = x2 - x1;
		final double dy = y2 - y1;
		final double length = Math.hypot(dx, dy);
		if (length < 0.01) {
			return;
		}
		final double half = thickness / 2;
		// 每 1.5 像素一个矩形，既平滑又不至于产生太多四边形
		final int steps = clamp((int) Math.ceil(length / 1.5), 1, 96);
		for (int i = 0; i <= steps; i++) {
			final double t = (double) i / steps;
			final double x = x1 + dx * t;
			final double y = y1 + dy * t;
			guiDrawing.drawRectangle(x - half, y - half, x + half, y + half, color);
		}
	}

	// ------------------------------------------------------------------
	// 视图变换
	// ------------------------------------------------------------------

	/**
	 * 根据所有轨道折线的包围盒，算出"刚好铺满画布"的缩放与中心点。
	 *
	 * <p>用户滚轮缩放 / 中键平移是叠加在这个自动适配之上的。
	 */
	private void updateView() {
		if (rails.length == 0) {
			// 没有轨道时以玩家为中心，这样地形底图仍然有意义
			final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
			viewCenterX = player == null ? 0 : player.getX();
			viewCenterZ = player == null ? 0 : player.getZ();
			viewFitScale = 1F;
			return;
		}

		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (final SignalRailData rail : rails) {
			for (int i = 0; i < rail.getPointCount(); i++) {
				final double x = rail.getPathX(i);
				final double z = rail.getPathZ(i);
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}
		if (minX > maxX || minZ > maxZ) {
			viewCenterX = 0;
			viewCenterZ = 0;
			viewFitScale = 1F;
			return;
		}

		viewCenterX = (minX + maxX) / 2;
		viewCenterZ = (minZ + maxZ) / 2;

		// 画布内留 20px 边距
		final double canvasW = Math.max(1, canvasRight - canvasLeft - 20);
		final double canvasH = Math.max(1, canvasBottom - canvasTop - 20);
		final double worldW = Math.max(maxX - minX, 0.5);
		final double worldH = Math.max(maxZ - minZ, 0.5);
		final double fit = Math.min(canvasW / worldW, canvasH / worldH);
		// 限制上限，避免只有一小段轨道时被放得过大
		viewFitScale = (float) Math.max(0.05, Math.min(fit, 12));
	}

	private double currentScale() {
		return viewFitScale * userScale;
	}

	private double toScreenX(double worldX) {
		return (canvasLeft + canvasRight) / 2.0 + (worldX - viewCenterX) * currentScale() + panX;
	}

	private double toScreenY(double worldZ) {
		return (canvasTop + canvasBottom) / 2.0 + (worldZ - viewCenterZ) * currentScale() + panY;
	}

	// ------------------------------------------------------------------
	// 交互
	// ------------------------------------------------------------------

	@Override
	public boolean mouseScrolled2(double mouseX, double mouseY, double amount) {
		// 左侧列表上滚轮 → 翻页
		if (mouseX >= 0 && mouseX < listWidth && mouseY >= getListRowTop() && mouseY < canvasBottom) {
			listScroll -= (int) Math.signum(amount) * 3;
			clampListScroll();
			return true;
		}
		if (isInsideCanvas(mouseX, mouseY)) {
			userScale *= (float) Math.pow(1.15, amount);
			userScale = Math.max(0.1F, Math.min(20F, userScale));
			return true;
		}
		return super.mouseScrolled2(mouseX, mouseY, amount);
	}

	@Override
	public boolean mouseDragged2(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (dragging) {
			panX = dragOriginX + (mouseX - dragStartX);
			panY = dragOriginY + (mouseY - dragStartY);
			return true;
		}
		return super.mouseDragged2(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased2(double mouseX, double mouseY, int button) {
		if (dragging) {
			dragging = false;
			return true;
		}
		return super.mouseReleased2(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseClicked2(double mouseX, double mouseY, int button) {
		super.mouseClicked2(mouseX, mouseY, button);
		updateView();

		// 右侧面板
		final int panelLeft = getWidthMapped() - colorWidth;
		if (mouseX >= panelLeft && mouseX <= getWidthMapped() && mouseY >= canvasTop && mouseY <= canvasBottom) {
			handlePanelClick(mouseX, mouseY, panelLeft);
			return true;
		}

		// 左侧轨道列表（注意 canvasLeft == listWidth，两者不会重叠）
		if (mouseX >= 0 && mouseX < listWidth && mouseY >= getListRowTop() && mouseY < canvasBottom) {
			if (button == 0) {
				handleListClick(mouseY);
			}
			return true;
		}

		if (isInsideCanvas(mouseX, mouseY)) {
			// 中键：开始拖动平移
			if (button == 2) {
				dragging = true;
				dragStartX = mouseX;
				dragStartY = mouseY;
				dragOriginX = panX;
				dragOriginY = panY;
				return true;
			}
			// 左键：选节点
			if (button == 0) {
				hoveredNode = findNodeAt(mouseX, mouseY);
				if (hoveredNode != Long.MIN_VALUE) {
					selectNode(hoveredNode);
				}
			}
			// 右键：取消选择
			if (button == 1) {
				clearSelection();
			}
		}
		return true;
	}

	private boolean isInsideCanvas(double mouseX, double mouseY) {
		return mouseX >= canvasLeft && mouseX <= canvasRight && mouseY >= canvasTop && mouseY <= canvasBottom;
	}

	/**
	 * 点击左侧列表的一行：选中该轨道，再点一次取消。
	 *
	 * <p>选中后会调用 {@link #focusOnRail} 把视图中心移到这条轨道上 ——
	 * 否则列表里十几条轨道根本对不上画布里的哪一根。
	 */
	private void handleListClick(double mouseY) {
		final int row = (int) ((mouseY - getListRowTop()) / LINE_HEIGHT);
		final int index = listScroll + row;
		if (row < 0 || index < 0 || index >= rails.length) {
			return;
		}

		if (index == selectedRailIndex) {
			selectedRailIndex = -1;
			statusMessage = Lang.gui("status.deselected_rail");
		} else {
			selectedRailIndex = index;
			focusOnRail(index);
			statusMessage = Lang.gui("status.selected_rail", index + 1, describeRail(rails[index]));
		}
	}

	/**
	 * 把视图中心移到指定轨道的中点（只平移，不改缩放）。
	 *
	 * <p>由 {@code toScreenX(w) = 画布中心 + (w - viewCenterX) * scale + panX}
	 * 反解：要让轨道中点正好落在画布中心，取
	 * {@code panX = -(中点 - viewCenterX) * scale} 即可。
	 */
	private void focusOnRail(int index) {
		final SignalRailData rail = rails[index];
		final int points = rail.getPointCount();
		if (points == 0) {
			return;
		}
		final int mid = points / 2;
		final double scale = currentScale();
		panX = -(rail.getPathX(mid) - viewCenterX) * scale;
		panY = -(rail.getPathZ(mid) - viewCenterZ) * scale;
	}

	private void handlePanelClick(double mouseX, double mouseY, int panelLeft) {
		final int gridTop = canvasTop + LINE_HEIGHT + 2;
		final int cell = Math.max(8, Math.min(SQUARE_SIZE, (colorWidth - TEXT_PADDING * 2) / 4));

		// 颜色格
		for (int i = 0; i < SIGNAL_COLORS.length; i++) {
			final int col = i % 4;
			final int row = i / 4;
			final int x = panelLeft + TEXT_PADDING + col * cell;
			final int y = gridTop + row * cell;
			if (mouseX >= x && mouseX <= x + cell && mouseY >= y && mouseY <= y + cell) {
				selectedColorIndex = i;
				statusMessage = Lang.gui("status.selected_color", Lang.color(i));
				return;
			}
		}

		// 按钮
		final int buttonTop = gridTop + 4 * cell + LINE_HEIGHT + 4;
		final int available = canvasBottom - buttonTop - 2;
		final int buttonHeight = clamp(available / BUTTON_COUNT - BUTTON_GAP, BUTTON_MIN_H, BUTTON_MAX_H);
		final int step = buttonHeight + BUTTON_GAP;
		for (int i = 0; i < BUTTON_COUNT; i++) {
			final int y = buttonTop + i * step;
			if (y + buttonHeight > canvasBottom) {
				break;
			}
			if (mouseY >= y && mouseY <= y + buttonHeight
					&& mouseX >= panelLeft + TEXT_PADDING && mouseX <= panelLeft + colorWidth - TEXT_PADDING) {
				handleButton(i);
				return;
			}
		}
	}

	private void handleButton(int index) {
		switch (index) {
			case 0: // 连接
				if (selectedNode1 != Long.MIN_VALUE && selectedNode2 != Long.MIN_VALUE) {
					submitSignal(PacketUpdateSignalLink.ACTION_ADD);
				} else {
					statusMessage = Lang.gui("status.need_two_nodes");
				}
				break;
			case 1: // 断开
				if (selectedNode1 != Long.MIN_VALUE && selectedNode2 != Long.MIN_VALUE) {
					submitSignal(PacketUpdateSignalLink.ACTION_REMOVE);
				} else {
					statusMessage = Lang.gui("status.need_two_nodes");
				}
				break;
			case 2: // 清空选择
				clearSelection();
				break;
			case 3: // 复位视图
				resetView();
				statusMessage = Lang.gui("status.view_reset");
				break;
			case 4: // 刷新
				MTRSignalDashboardClient.requestRefresh();
				statusMessage = Lang.gui("status.refreshing");
				break;
			case BUTTON_TERRAIN: // 地形开关
				showTerrain = !showTerrain;
				if (!showTerrain) {
					// 关掉时立刻释放贴图，避免占着显存
					worldMap.disposeImages();
				}
				statusMessage = Lang.gui(showTerrain ? "status.terrain_on" : "status.terrain_off");
				break;
			default:
				break;
		}
	}

	private void selectNode(long nodeId) {
		if (selectedNode1 == nodeId) {
			selectedNode1 = selectedNode2;
			selectedNode2 = Long.MIN_VALUE;
		} else if (selectedNode2 == nodeId) {
			selectedNode2 = Long.MIN_VALUE;
		} else if (selectedNode1 == Long.MIN_VALUE) {
			selectedNode1 = nodeId;
		} else if (selectedNode2 == Long.MIN_VALUE) {
			selectedNode2 = nodeId;
		} else {
			selectedNode1 = selectedNode2;
			selectedNode2 = nodeId;
		}

		if (selectedNode1 != Long.MIN_VALUE && selectedNode2 != Long.MIN_VALUE) {
			statusMessage = Lang.gui("status.ready");
		}
	}

	private void clearSelection() {
		selectedNode1 = Long.MIN_VALUE;
		selectedNode2 = Long.MIN_VALUE;
		selectedRailIndex = -1;
		statusMessage = Lang.gui("status.cleared");
	}

	private void submitSignal(int action) {
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientWorld world = client.getWorldMapped();
		if (world == null) {
			return;
		}

		// 直接用节点的 BlockPos 编码，服务端会还原成 Position 提交给 MTR。
		final BlockPos pos1 = BlockPos.fromLong(selectedNode1);
		final BlockPos pos2 = BlockPos.fromLong(selectedNode2);
		final int color = SIGNAL_COLORS[selectedColorIndex];

		MTRSignalDashboardClient.sendSignalUpdate(pos1, pos2, color, action);

		statusMessage = Lang.gui(
				action == PacketUpdateSignalLink.ACTION_ADD ? "status.sent_connect" : "status.sent_disconnect",
				Lang.color(selectedColorIndex)
		);
	}

	/**
	 * 找到鼠标位置最近的节点。
	 */
	private long findNodeAt(double mouseX, double mouseY) {
		long best = Long.MIN_VALUE;
		double bestDistance = 10;

		for (final Node node : nodes.values()) {
			final double x = toScreenX(node.x);
			final double y = toScreenY(node.z);
			final double distance = Math.hypot(mouseX - x, mouseY - y);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = node.id;
			}
		}

		return best;
	}

	/** 轨道的完整描述，例如「[站台] 80 km/h 60 blocks 2 colours Occupied」。 */
	private String describeRail(SignalRailData rail) {
		final StringBuilder builder = new StringBuilder();
		if (rail.isPlatform) {
			builder.append('[').append(Lang.gui("rail.platform")).append("] ");
		} else if (rail.isSiding) {
			builder.append('[').append(Lang.gui("rail.siding")).append("] ");
		}
		builder.append(Lang.gui("rail.speed", (int) rail.speedLimit));
		builder.append(' ').append(Lang.gui("rail.length", Math.round(rail.length)));
		if (rail.signalColors.length > 0) {
			builder.append(' ').append(Lang.gui("rail.color_count", rail.signalColors.length));
		}
		if (rail.occupied) {
			builder.append(' ').append(Lang.gui("rail.occupied"));
		}
		return builder.toString();
	}

	@Override
	public void onClose2() {
		super.onClose2();
		// 释放区块贴图，否则关掉界面后显存不会回收
		worldMap.disposeImages();
		instance = null;
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	/** 画布上的一个轨道端点。 */
	private static final class Node {
		final long id;
		final double x;
		final double y;
		final double z;
		/** 有几条轨道共用这个端点（≥2 表示岔口）。 */
		int count = 1;

		Node(long id, double x, double y, double z) {
			this.id = id;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
}
