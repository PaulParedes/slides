package noria.scene;

import java.util.Objects;

public class Point {
    public final float x;
    public final float y;
    
    public static final Point ZERO = new Point(0f, 0f);

    public Point(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    public Point offset(Point p) {
        return p == null ? null : new Point(x + p.x, y + p.y);
    }

    public Point offset(float dx, float dy) {
        return new Point(x + dx, y + dy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Point point = (Point) o;
        return Float.compare(point.x, x) == 0 &&
                Float.compare(point.y, y) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return String.format("Point{%.0f,%.0f}", x, y);
    }
}
