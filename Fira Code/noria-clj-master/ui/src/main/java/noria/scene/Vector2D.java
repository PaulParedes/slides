package noria.scene;

import java.util.Objects;

public class Vector2D {
    public final float dx;
    public final float dy;

    public Vector2D(float dx, float dy) {
        this.dx = dx;
        this.dy = dy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vector2D point = (Vector2D) o;
        return Float.compare(point.dx, dx) == 0 &&
                Float.compare(point.dy, dy) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dx, dy);
    }

    @Override
    public String toString() {
        return "Vector{" + dx + "," + dy + '}';
    }
}
