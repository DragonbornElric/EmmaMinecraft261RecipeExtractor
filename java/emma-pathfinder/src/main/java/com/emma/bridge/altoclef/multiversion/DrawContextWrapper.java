package adris.altoclef.multiversion;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

public class DrawContextWrapper {


    public static DrawContextWrapper of(DrawContext context) {
        if (context == null) return null;
        return new DrawContextWrapper(context);
    }
    private final DrawContext context;

    private DrawContextWrapper(DrawContext context) {
        this.context = context;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y2, color);
    }

    public void drawHorizontalLine(int x1, int x2, int y, int color) {
        context.drawHorizontalLine(x1, x2, y, color);
    }

    public void drawVerticalLine(int x, int y1, int y2, int color) {
        context.drawVerticalLine(x, y1, y2, color);
    }

    public void drawText(TextRenderer textRenderer, @Nullable String text, int x, int y, int color, boolean shadow) {
        context.drawText(textRenderer,text,x,y,color,shadow);
    }


    public Matrix3x2fStack getMatrices() {
        return context.getMatrices();
    }

    public int getScaledWindowWidth() {
        return context.getScaledWindowWidth();
    }

    public int getScaledWindowHeight() {
        return context.getScaledWindowHeight();
    }


}
