package noria.scene;

import java.util.Objects;

public class Constraints {
    public final float minWidth, maxWidth, minHeight, maxHeight;

    public Constraints(float minWidth, float maxWidth, float minHeight, float maxHeight) {
        this.minWidth = minWidth;
        this.maxWidth = maxWidth;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }
    
    public static Constraints tight(Size s) {
        return new Constraints(s.width, s.width, s.height, s.height);
    }

    public static Constraints tight(float width, float height) {
        return new Constraints(width, width, height, height);
    }

    public static final Constraints INFINITE = new Constraints(0, Float.POSITIVE_INFINITY, 0, Float.POSITIVE_INFINITY);

    @Override
    public String toString() {
        return "Constraints{" + minWidth + ".." + maxWidth + "," + minHeight + ".." + maxHeight + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Constraints that = (Constraints) o;
        return Float.compare(that.minWidth, minWidth) == 0 &&
                Float.compare(that.maxWidth, maxWidth) == 0 &&
                Float.compare(that.minHeight, minHeight) == 0 &&
                Float.compare(that.maxHeight, maxHeight) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minWidth, maxWidth, minHeight, maxHeight);
    }
}
