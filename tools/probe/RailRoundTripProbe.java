import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * 验证「服务端 Rail → JSON → 客户端 Rail」这条往返链路，
 * 重点是反序列化之后 railMath 是否仍然可用、getPosition 是否给出同样的路径。
 *
 * <p>如果这一步不成立，那么在服务端采样路径的方案就会失效，
 * 必须改成"服务端采样好再传折线"。
 */
public class RailRoundTripProbe {

	public static void main(String[] args) {
		check("直轨", new Position(100, 64, 200), Angle.E, new Position(110, 64, 200), Angle.W, Rail.Shape.QUADRATIC, 0);
		check("弯轨", new Position(100, 64, 200), Angle.E, new Position(110, 64, 210), Angle.S, Rail.Shape.QUADRATIC, 0);
		check("TWO_RADII", new Position(0, 64, 0), Angle.N, new Position(20, 64, 20), Angle.E, Rail.Shape.TWO_RADII, 0);
	}

	private static void check(String name, Position p1, Angle a1, Position p2, Angle a2, Rail.Shape shape, double radius) {
		System.out.println("=== " + name + " ===");
		try {
			final Rail rail = Rail.newPlatformRail(p1, a1, p2, a2, shape, radius, new ObjectArrayList<>(), TransportMode.TRAIN);
			final double len1 = rail.railMath.getLength();
			System.out.println("  原始 rail: length=" + len1 + " shape=" + rail.railMath.getShape());

			// 走 MTR 自己的序列化通道
			final JsonObject json = Utilities.getJsonObjectFromData(rail);
			System.out.println("  JSON 长度=" + json.toString().length());

			// 反序列化，模拟客户端拿到 DataResponse 后的结果
			final Rail copy = new Rail(new JsonReader(json));
			final double len2 = copy.railMath.getLength();
			System.out.println("  反序列化后: length=" + len2 + " shape=" + copy.railMath.getShape());

			final boolean ok = Math.abs(len1 - len2) < 1e-6;
			System.out.println("  length 一致: " + ok);

			// 逐点比较路径
			double maxDiff = 0;
			for (int i = 0; i <= 8; i++) {
				final double s = len1 * i / 8.0;
				final Vector v1 = rail.railMath.getPosition(s, false);
				final Vector v2 = copy.railMath.getPosition(s, false);
				maxDiff = Math.max(maxDiff, Math.max(Math.abs(v1.x - v2.x), Math.abs(v1.z - v2.z)));
			}
			System.out.printf("  路径最大偏差: %.6f%n", maxDiff);

			// 端点还原
			final Vector s0 = copy.railMath.getPosition(0, false);
			final Vector s1 = copy.railMath.getPosition(len2, false);
			System.out.printf("  起点还原: (%.2f, %.2f, %.2f) 期望 (%d, %d, %d)%n",
					s0.x, s0.y, s0.z, p1.getX(), p1.getY(), p1.getZ());
			System.out.printf("  终点还原: (%.2f, %.2f, %.2f) 期望 (%d, %d, %d)%n",
					s1.x, s1.y, s1.z, p2.getX(), p2.getY(), p2.getZ());
		} catch (Throwable t) {
			System.out.println("  ERROR: " + t);
			t.printStackTrace(System.out);
		}
		System.out.println();
	}
}
