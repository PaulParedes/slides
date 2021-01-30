package noria.scene;

import noria.scene.UserEvent.EventType;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public interface Scene {
    void setRoot(long nodeId);
    void rect(long nodeId, Point origin, Size size, Color color);
    // according to wr BorderStyle
    enum BorderStyle {
        NONE,
        SOLID,
        DOUBLE,
        DOTTED,
        DASHED,
        HIDDEN,
        GROOVE,
        RIDGE,
        INSET,
        OUTEST
    }

    enum BoxShadowClipMode {
      OUTSET,
      INSET
    }

    void border(long nodeId, Point origin, Size size, Color color, float lineWidth, BorderStyle style, BorderRadius borderRadius);
    void boxShadow(long nodeId, Rect clipRect, Rect basisRect, Vector2D offset, Color color, float blurRadius, float spreadRadius, BorderRadius borderRadius, BoxShadowClipMode clipMode);
    void scroll(long nodeId, Point origin, Size size, long contentId, Size contentSize);
    void scrollPosition(long nodeId, Point position);
    void stack(long nodeId, long[] children);
    void setPosition(long nodeId, Point origin);
    void setOpacity(long nodeId, float opacity);
    void destroy(long nodeId);
    void onEvent(long nodeId, long callbackId, Iterable<EventType> eventTypes);
    void text(long nodeId, Point origin, Size size, Color color, PhotonApi.TextLayout layout);
    void setSceneSize(Size size);
    void image(long nodeId, Point origin, Size size, long imageId);

    enum ClipMode {
        CLIP,
        CLIP_OUT
    }

    class ComplexClipRegion {
        final Rect rect;
        final BorderRadius borderRadius;
        final ClipMode clipMode;

        public ComplexClipRegion(Rect rect, BorderRadius borderRadius, ClipMode clipMode) {
            this.rect = rect;
            this.borderRadius = borderRadius;
            this.clipMode = clipMode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ComplexClipRegion that = (ComplexClipRegion) o;
            return Objects.equals(rect, that.rect) &&
                    Objects.equals(borderRadius, that.borderRadius) &&
                    clipMode == that.clipMode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(rect, borderRadius, clipMode);
        }
    }

    void clip(long nodeId, Rect clipRect, List<ComplexClipRegion> complexClips, long contentId);
    boolean commit(long frameId);
    void reset();
    void closeWindow();
    long windowId();
}
