package noria.scene;

import java.util.Objects;

public class Rect {
  public final float left, top, right, bottom;

  public Rect(float left, float top, float right, float bottom) {
    this.top = top;
    this.left = left;
    this.bottom = bottom;
    this.right = right;
  }

  public Rect(Point origin, Size size) {
    this.top = origin.y;
    this.left = origin.x;
    this.bottom = this.top + size.height;
    this.right = this.left + size.width;
  }

  @SuppressWarnings("RedundantIfStatement")
  public boolean intersects(Rect r) {
    if (r == null) {
      return false;
    }
    if (r.left >= right || r.right <= left || r.top >= bottom || r.bottom <= top) {
      return false;
    }
    return true;
  }

  public Rect intersect(Rect r) {
      if (r == null) {
          return null;
      }
      if (r.left >= right || r.right <= left || r.top >= bottom || r.bottom <= top) {
          return null;
      }
    return new Rect(Math.max(left, r.left), Math.max(top, r.top), Math.min(right, r.right), Math.min(bottom, r.bottom));
  }

  public Rect offset(Point p) {
    return p == null ? null : new Rect(left + p.x, top + p.y, right + p.x, bottom + p.y);
  }

  public Rect offset(float dx, float dy) {
    return new Rect(left + dx, top + dy, right + dx, bottom + dy);
  }

  public boolean contains(Point p) {
    return p.x >= left && p.x <= right && p.y >= top && p.y <= bottom;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Rect rect = (Rect)o;
    return Float.compare(rect.top, top) == 0 &&
           Float.compare(rect.left, left) == 0 &&
           Float.compare(rect.bottom, bottom) == 0 &&
           Float.compare(rect.right, right) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(left, top, right, bottom);
  }

  @Override
  public String toString() {
    return String.format("Rect{%.0f,%.0f -> %.0f,%.0f}", left, top, right, bottom);
  }

  public float width() { return right - left; }

  public float height() { return bottom - top; }

  public Size size() {
    return new Size(width(), height());
  }

  public Point origin() {
    return new Point(left, top);
  }

  public Point center() {
    return new Point((left + right) / 2f, (top + bottom) / 2f);
  }
}
