package noria.scene;

import java.util.Objects;

public class Color {
    public static Color transparent = new Color(0f, 0f, 0f, 0f);
    public static Color black = new Color(0f, 0f, 0f, 1f);
    public static Color white = new Color(1f, 1f, 1f, 1f);
    
    public final float r;
    public final float g;
    public final float b;
    public final float a;

    public static Color css(String cssColor) {
        float r = Integer.valueOf(cssColor.substring(0, 2), 16) / 256f;
        float g = Integer.valueOf(cssColor.substring(2, 4), 16) / 256f;
        float b = Integer.valueOf(cssColor.substring(4, 6), 16) / 256f;
        float a = cssColor.length() == 8 ? Integer.valueOf(cssColor.substring(6, 8), 16) / 256f : 1f;
        return new Color(r, g, b, a);
    }
    
    public Color(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Color color = (Color) o;
        return Float.compare(color.r, r) == 0 &&
                Float.compare(color.g, g) == 0 &&
                Float.compare(color.b, b) == 0 &&
                Float.compare(color.a, a) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, g, b, a);
    }

    private String hex(float x) {
        return Long.toString(Float.valueOf(x * 255f).longValue(), 16);
    }
    
    @Override
    public String toString() {
        return "#" + hex(r) + hex(g) + hex(b) + hex(a);
    }

    public Color withAlpha(float newA) {
        return new Color(r, g, b, newA);
    }
}
