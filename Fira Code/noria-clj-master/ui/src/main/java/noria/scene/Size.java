package noria.scene;

import java.util.Objects;

public class Size {
    public final float width;
    public final float height;

    public Size(float width, float height) {
        this.width = width;
        this.height = height;
    }
    
    public Size add(float dw, float dh) {
        return new Size(width + dw, height + dh);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Size size = (Size) o;
        return Float.compare(size.width, width) == 0 &&
                Float.compare(size.height, height) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height);
    }

    @Override
    public String toString() {
        return "Size{" + width + "×" + height + '}';
    }
}
