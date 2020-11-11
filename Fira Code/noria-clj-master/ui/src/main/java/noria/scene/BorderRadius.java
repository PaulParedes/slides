package noria.scene;

import java.util.Objects;

public class BorderRadius {
    public final Size topLeft;
    public final Size topRight;
    public final Size bottomLeft;
    public final Size bottomRight;

    public BorderRadius(Size topLeft, Size topRight, Size bottomLeft, Size bottomRight) {
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BorderRadius that = (BorderRadius) o;
        return Objects.equals(topLeft, that.topLeft) &&
                Objects.equals(topRight, that.topRight) &&
                Objects.equals(bottomLeft, that.bottomLeft) &&
                Objects.equals(bottomRight, that.bottomRight);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topLeft, topRight, bottomLeft, bottomRight);
    }

    public static BorderRadius uniform(float r) {
        return new BorderRadius(new Size(r, r), new Size(r, r), new Size(r, r), new Size(r, r));
    }
}
