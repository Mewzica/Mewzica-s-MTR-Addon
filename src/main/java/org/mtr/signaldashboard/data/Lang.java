package org.mtr.signaldashboard.data;

import org.mtr.mapping.mapper.TextHelper;

/**
 * 翻译键的集中入口。
 *
 * <p>之前界面上的文字是硬编码中文的，把游戏语言切成英文也没用。
 * 现在所有可见文本都走 {@link TextHelper#translatable}，由
 * {@code assets/mewsmtr/lang/*.json} 提供各语言的译文。
 *
 * <p>这里用 {@link #tr} 直接把译文解析成 {@code String} 再拼接，
 * 而不是构造 {@code MutableText} 树——因为界面里大量用到
 * {@code GraphicsHolder.getTextWidth(String)} 做宽度截断，
 * 而它只接受已经解析好的字符串。代价是跨语言的语序不够灵活，
 * 对本模组这种"字段拼接"式的文本来说可以接受。
 *
 * @author Mewzica
 */
public final class Lang {

	/** 所有 GUI 键的公共前缀。 */
	public static final String PREFIX = "gui.mewsmtr.";

	/**
	 * 16 种信号颜色的键名后缀，顺序与
	 * {@code ItemSignalModifier.COLORS} / {@code SignalDashboardScreen.SIGNAL_COLORS} 一致。
	 */
	private static final String[] COLOR_KEYS = {
			"white", "orange", "magenta", "light_blue",
			"yellow", "lime", "pink", "gray",
			"light_gray", "cyan", "purple", "blue",
			"brown", "green", "red", "black",
	};

	private Lang() {
	}

	/**
	 * 取译文。
	 *
	 * <p>注意：每次调用都会按当前游戏语言实时解析，所以玩家在
	 * 游戏里切换语言后不需要重开界面。
	 */
	public static String tr(String key, Object... args) {
		return TextHelper.translatable(key, args).getString();
	}

	/** 取某个信号颜色的译名。 */
	public static String color(int index) {
		if (index < 0 || index >= COLOR_KEYS.length) {
			return "";
		}
		return tr(PREFIX + "color." + COLOR_KEYS[index]);
	}

	/** 取某个 GUI 键的译文（自动补前缀）。 */
	public static String gui(String path, Object... args) {
		return tr(PREFIX + path, args);
	}
}
