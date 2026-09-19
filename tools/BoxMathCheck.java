package io.giasinton.countday;

/**
 * BoxMath 的纯逻辑校验：不依赖 Android，可以直接在电脑上用 JDK 跑。
 *
 * <pre>bash tools/run-tests.sh</pre>
 */
public class BoxMathCheck {

    private static int failed;

    private static String box(int[] size) {
        return size[0] + "x" + size[1];
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected.equals(actual);
        if (!ok) {
            failed++;
        }
        System.out.printf("%-46s %s  actual=%s expected=%s%n",
                name, ok ? "PASS" : "FAIL", actual, expected);
    }

    public static void main(String[] args) {
        // 正方形格子
        check("格子 179x200 + 1:1", box(BoxMath.imageBox(179, 200, 1f)), "179x179");
        check("格子 179x200 + 4:3", box(BoxMath.imageBox(179, 200, 4f / 3f)), "179x134");
        check("格子 179x200 + 3:4", box(BoxMath.imageBox(179, 200, 3f / 4f)), "150x200");
        check("格子 179x200 + 2:1", box(BoxMath.imageBox(179, 200, 2f)), "179x90");
        check("格子 179x200 + 1:2", box(BoxMath.imageBox(179, 200, 0.5f)), "100x200");

        // 宽格子（4x2）
        check("格子 388x179 + 1:1", box(BoxMath.imageBox(388, 179, 1f)), "179x179");
        check("格子 388x179 + 16:9", box(BoxMath.imageBox(388, 179, 16f / 9f)), "318x179");
        check("格子 388x179 + 9:16", box(BoxMath.imageBox(388, 179, 9f / 16f)), "101x179");

        // 结果永远在格子内，且长边顶格
        float[] aspects = { 0.2f, 0.5f, 0.8f, 1f, 1.5f, 2f, 3f, 4.5f };
        boolean inside = true;
        boolean touchesEdge = true;
        for (float aspect : aspects) {
            int[] size = BoxMath.imageBox(179, 200, aspect);
            if (size[0] > 179 || size[1] > 200) {
                inside = false;
            }
            if (size[0] != 179 && size[1] != 200) {
                touchesEdge = false;
            }
        }
        check("各种比例都不超出格子", inside, true);
        check("各种比例都至少顶满一条边", touchesEdge, true);

        // 异常比例 -> 退化成填满格子
        check("比例 0 -> 填满格子", box(BoxMath.imageBox(179, 200, 0f)), "179x200");
        check("比例 -1 -> 填满格子", box(BoxMath.imageBox(179, 200, -1f)), "179x200");
        check("比例 NaN -> 填满格子", box(BoxMath.imageBox(179, 200, Float.NaN)), "179x200");
        check("格子为 0 -> 至少 1px", box(BoxMath.imageBox(0, 0, 1f)), "1x1");

        // 裁剪：图片必须始终完全盖住框
        check("1000x1000 的图盖 500x500 的框", BoxMath.coverScale(1000, 1000, 500f, 500f), 0.5f);
        check("1000x500 的图盖 500x500 的框", BoxMath.coverScale(1000, 500, 500f, 500f), 1f);
        check("1000x500 的图盖 500x800 的框", BoxMath.coverScale(1000, 500, 500f, 800f), 1.6f);
        check("参数非法时给安全值 1", BoxMath.coverScale(0, 100, 500f, 500f), 1f);

        // 不变量：缩放不小于"铺满框"时，偏移被夹住后图片四边都在框外（或正好贴边）
        float frameL = 200f;
        float frameT = 150f;
        float frameR = 700f;
        float frameB = 950f;
        float minScale = BoxMath.coverScale(1000, 500, frameR - frameL, frameB - frameT);
        check("1000x500 盖 500x800 的最小缩放", minScale, 1.6f);

        float[] scales = { minScale, 2f, 3f, 6f };
        float[] offsets = { -9999f, -300f, 0f, 137f, 9999f };
        boolean covered = true;
        boolean edgeFlush = true;
        for (float scale : scales) {
            float drawW = 1000 * scale;
            float drawH = 500 * scale;
            for (float raw : offsets) {
                float ox = BoxMath.coverOffset(raw, frameL, frameR, drawW);
                float oy = BoxMath.coverOffset(raw, frameT, frameB, drawH);
                if (ox > frameL || ox + drawW < frameR || oy > frameT || oy + drawH < frameB) {
                    covered = false;
                }
                // 往左上拖到底 -> 图片右下角贴住框；往右下拖到底 -> 图片左上角贴住框
                if (raw < -1000f && (ox != frameR - drawW || oy != frameB - drawH)) {
                    edgeFlush = false;
                }
                if (raw > 1000f && (ox != frameL || oy != frameT)) {
                    edgeFlush = false;
                }
            }
        }
        check("拖到任何位置都不会露出框", covered, true);
        check("拖到头是贴边而不是留缝", edgeFlush, true);

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
