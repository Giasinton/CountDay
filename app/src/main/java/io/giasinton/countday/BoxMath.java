package io.giasinton.countday;

/**
 * 卡片尺寸的纯几何计算。
 *
 * <p>刻意不依赖任何 Android 类，这样可以在电脑上直接用 JDK 跑校验
 * （见 {@code tools/BoxMathCheck.java}），不用连真机。
 */
final class BoxMath {

    private BoxMath() {
    }

    /**
     * 在格子里取最大的"图片同比例"矩形：等比缩放塞进格子，不裁剪、不留白。
     *
     * <p>先假定宽度顶满格子，算出高度；高度超了就以高度顶满重算宽度。
     * 比例无效（{@code <= 0} / NaN / 无穷）时退化成整个格子。
     */
    static int[] imageBox(int cellWidthDp, int cellHeightDp, float aspect) {
        int cellW = Math.max(1, cellWidthDp);
        int cellH = Math.max(1, cellHeightDp);
        if (!(aspect > 0f) || Float.isInfinite(aspect)) {
            return new int[] { cellW, cellH };
        }
        int width = cellW;
        int height = Math.max(1, Math.round(cellW / aspect));
        if (height > cellH) {
            height = cellH;
            width = Math.max(1, Math.round(cellH * aspect));
        }
        return new int[] { width, height };
    }

    /**
     * 图片要完全盖住裁剪框所需的最小缩放：宽、高两个方向各算一次，取较大的那个。
     *
     * <p>裁剪框是固定不动的（比例 = 卡片比例），图片只能在这个缩放之上拖动 / 放大，
     * 这样框内永远不会露出空白 —— 成品图是按"填满卡片"渲染的，露出空白就等于最终显示有空白。
     */
    static float coverScale(int imageWidth, int imageHeight, float frameWidth, float frameHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) {
            return 1f;
        }
        return Math.max(frameWidth / imageWidth, frameHeight / imageHeight);
    }

    /**
     * 把图片的偏移夹进"始终盖住框"的范围：图片左边缘不越过框左边，右边缘不缩进框右边。
     *
     * <p>只要 {@code drawSize >= frameEnd - frameStart}（即缩放不小于 {@link #coverScale}），
     * 范围就一定是合法的，拖到头是"贴边"而不是"露出空白"。
     */
    static float coverOffset(float offset, float frameStart, float frameEnd, float drawSize) {
        float lower = frameEnd - drawSize;
        float upper = frameStart;
        if (lower > upper) {
            return (lower + upper) / 2f;
        }
        if (offset < lower) {
            return lower;
        }
        return offset > upper ? upper : offset;
    }
}
