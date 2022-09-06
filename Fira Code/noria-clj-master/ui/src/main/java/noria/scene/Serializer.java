package noria.scene;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class Serializer extends DataOutputStream {

    // According to scene.rs/Update
    enum Update {
        SET_ROOT,
        RECT,
        BORDER,
        BOX_SHADOW,
        TEXT,
        SCROLL,
        SCROLL_POSITION,
        STACK,
        SET_POSITION,
        SET_OPACITY,
        DESTROY,
        ON_EVENT,
        SET_SCENE_SIZE,
        CLIP,
        IMAGE,
    }

    Serializer(OutputStream os) {
        super(os);
    }


    void writeUpdate(Update update) throws IOException {
        writeInt(update.ordinal());
    }

    void writeColor(Color color) throws IOException {
        writeFloat(color.r);
        writeFloat(color.g);
        writeFloat(color.b);
        writeFloat(color.a);
    }

    void writePoint(Point point) throws IOException {
        writeFloat(point.x);
        writeFloat(point.y);
    }

    void writeSize(Size size) throws IOException {
        writeFloat(size.width);
        writeFloat(size.height);
    }

    void writeRect(Rect rect) throws IOException {
        writeFloat(rect.left);
        writeFloat(rect.top);
        writeFloat(rect.width());
        writeFloat(rect.height());
    }

    void writeVector(Vector2D vec) throws IOException {
        writeFloat(vec.dx);
        writeFloat(vec.dy);
    }

    void writeBorderRadius(BorderRadius borderRadius) throws IOException {
      writeSize(borderRadius.topLeft);
      writeSize(borderRadius.topRight);
      writeSize(borderRadius.bottomLeft);
      writeSize(borderRadius.bottomRight);
    }

    void writeTextLayout(PhotonApi.TextLayout textLayout) throws IOException {
        writeLong(textLayout.glyphsCount);
        for (int i = 0; i < textLayout.glyphsCount; i++) {
            writeInt((int)textLayout.glyphIndex(i));
            writeFloat(textLayout.glyphPositions[2 * i]);
            writeFloat(textLayout.glyphPositions[2 * i + 1]);
        }
        writeSize(textLayout.size);
    }

    void writeLongArray(long[] array) throws IOException {
        writeLong(array.length);
        for (long x: array) {
            writeLong(x);
        }
    }

    void writeByteArray(byte[] array) throws IOException {
        writeLong(array.length);
        for (byte x: array) {
            writeByte(x);
        }
    }

    void writeEnum(Enum e) throws IOException {
        writeInt(e.ordinal());
    }

    void writeString(String s) throws IOException {
        writeByteArray(s.getBytes(StandardCharsets.UTF_8));
    }
}