package noria.scene;

import noria.scene.Serializer.Update;
import noria.scene.UserEvent.EventType;
import sun.nio.ch.DirectBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class SceneImpl implements Scene {
    final long windowId;
    private boolean isDestroyed = false;
    private boolean trace;

    static class BAOS extends ByteArrayOutputStream {
        public byte[] byteArray(){
            return buf;
        }
    }

    BAOS baos;
    Serializer serializer;
    ByteBuffer buffer;
    private int updatesCount;
    private String LOG = System.getProperty("noria.log", "INFO");

    SceneImpl(long windowId) {
        this.windowId = windowId;
        baos = new BAOS();
        serializer = new Serializer(baos);
        buffer = ByteBuffer.allocateDirect(0);
        trace = LOG.equals("TRACE");
        updatesCount = 0;
    }

    private void log(String s) {
        System.out.println("[ scene ] " + windowId + " " + s);
    }

    @Override
    public void setRoot(long nodeId) {
        assert !isDestroyed;

        if (trace) log("setRoot nodeId:" + nodeId);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SET_ROOT);
            serializer.writeLong(nodeId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void rect(long nodeId, Point origin, Size size, Color color) {
        assert !isDestroyed;

        if (trace) log("rect nodeId:" + nodeId + " origin:" + origin + " size:" + size + " color:" + color);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.RECT);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
            serializer.writeSize(size);
            serializer.writeColor(color);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void border(long nodeId, Point origin, Size size, Color color, float lineWidth, BorderStyle style, BorderRadius borderRadius) {
        assert !isDestroyed;

        if (trace) log("border nodeId:" + nodeId + " origin:" + origin + " size:" + size + " color:" + color + " lineWidth:" + lineWidth + " style:" + style + " radius:" + borderRadius);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.BORDER);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
            serializer.writeSize(size);
            serializer.writeColor(color);
            serializer.writeFloat(lineWidth);
            serializer.writeEnum(style);
            serializer.writeBorderRadius(borderRadius);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void boxShadow(long nodeId, Rect clipRect, Rect basisRect, Vector2D offset, Color color, float blurRadius, float spreadRadius, BorderRadius borderRadius, BoxShadowClipMode clipMode) {
      assert !isDestroyed;
      try {
        updatesCount++;
        serializer.writeUpdate(Update.BOX_SHADOW);
        serializer.writeLong(nodeId);
        serializer.writeRect(clipRect);
        serializer.writeRect(basisRect);
        serializer.writeVector(offset);
        serializer.writeColor(color);
        serializer.writeFloat(blurRadius);
        serializer.writeFloat(spreadRadius);
        serializer.writeBorderRadius(borderRadius);
        serializer.writeInt(clipMode.ordinal());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void stack(long nodeId, long[] children) {
        assert !isDestroyed;

        if (trace) log("stack nodeId:" + nodeId + " children:" + Arrays.toString(children));
        try {
            updatesCount++;
            serializer.writeUpdate(Update.STACK);
            serializer.writeLong(nodeId);
            serializer.writeLongArray(children);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setPosition(long nodeId, Point origin) {
        assert !isDestroyed;

        if (trace) log("setPosition nodeId:" + nodeId + " origin:" + origin);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SET_POSITION);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setOpacity(long nodeId, float opacity) {
        assert !isDestroyed;

        if (trace) log("setOpacity nodeId:" + nodeId + " opacity:" + opacity);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SET_OPACITY);
            serializer.writeLong(nodeId);
            serializer.writeFloat(opacity);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy(long nodeId) {
        assert !isDestroyed;

        if (trace) log("destroy nodeId:" + nodeId);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.DESTROY);
            serializer.writeLong(nodeId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEvent(long nodeId, long callbackId, Iterable<EventType> eventTypes) {
        assert !isDestroyed;
        try {
            assert EventType.values().length <= 16;
            short mask = 0;
            for (EventType t: eventTypes) {
                mask |= 1 << t.ordinal();
            }
            updatesCount++;
            serializer.writeUpdate(Update.ON_EVENT);
            serializer.writeLong(nodeId);
            serializer.writeLong(callbackId);
            serializer.writeShort(mask);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void text(long nodeId, Point origin, Size size, Color color, PhotonApi.TextLayout textLayout) {
        assert !isDestroyed;
        try {
            updatesCount++;
            serializer.writeUpdate(Update.TEXT);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
            serializer.writeSize(size);
            serializer.writeColor(color);
            serializer.writeTextLayout(textLayout);
            serializer.writeLong(textLayout.fontInstance.id);
            serializer.writeInt(textLayout.fontInstance.size);
            if (textLayout.fontInstance.variations.length > 0) {
                serializer.writeLong(textLayout.fontInstance.variations[0].tag);
                serializer.writeFloat(textLayout.fontInstance.variations[0].value);
            } else {
                serializer.writeLong(0);
                serializer.writeFloat(0f);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void scroll(long nodeId, Point origin, Size size, long contentId, Size contentSize) {
        assert !isDestroyed;

        if (trace){
            log("scroll nodeId:" + nodeId + " origin:" + origin + " size:" + size + " contentId:" + contentId + " contentSize:" + contentSize);
        }
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SCROLL);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
            serializer.writeSize(size);
            serializer.writeLong(contentId);
            serializer.writeSize(contentSize);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void scrollPosition(long nodeId, Point position) {
        assert !isDestroyed;

        if (trace) log("scrollPosition nodeId:" + nodeId + " position:" + position);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SCROLL_POSITION);
            serializer.writeLong(nodeId);
            serializer.writePoint(position);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setSceneSize(Size size) {
        assert !isDestroyed;

        if (trace) log("Set scene size to: " + size);
        try {
            updatesCount++;
            serializer.writeUpdate(Update.SET_SCENE_SIZE);
            serializer.writeSize(size);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clip(long nodeId, Rect clipRect, List<ComplexClipRegion> complexClips, long contentId) {
        assert !isDestroyed;
        try {
            updatesCount++;
            serializer.writeUpdate(Update.CLIP);
            serializer.writeLong(nodeId);
            serializer.writeRect(clipRect);
            serializer.writeLong(complexClips.size());
            for (ComplexClipRegion c: complexClips) {
                serializer.writeRect(c.rect);
                serializer.writeSize(c.borderRadius.topLeft);
                serializer.writeSize(c.borderRadius.topRight);
                serializer.writeSize(c.borderRadius.bottomLeft);
                serializer.writeSize(c.borderRadius.bottomRight);
                serializer.writeInt(c.clipMode.ordinal());
            }
            serializer.writeLong(contentId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void image(long nodeId, Point origin, Size size, long imageId) {
        assert !isDestroyed;
        try {
            updatesCount++;
            serializer.writeUpdate(Update.IMAGE);
            serializer.writeLong(nodeId);
            serializer.writePoint(origin);
            serializer.writeSize(size);
            serializer.writeLong(imageId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean commit(long frameId) {
        assert !isDestroyed;

        if (trace) log("commit");
        int updatesSize = Long.BYTES + baos.size();
        if (updatesSize > buffer.capacity()) {
            buffer = ByteBuffer.allocateDirect(updatesSize);
        }
        buffer.clear();
        buffer.putLong(updatesCount);
        buffer.put(baos.byteArray(), 0, baos.size());
        baos.reset();
        try {
            return PhotonApi.commit(windowId, frameId, ((DirectBuffer)buffer).address(), buffer.capacity());
        } finally {
            updatesCount = 0;
        }
    }

    @Override
    public void reset() {
        assert !isDestroyed;
        buffer.clear();
        updatesCount = 0;
        baos.reset();
    }

    @Override
    public void closeWindow() {
        isDestroyed = true;
        PhotonApi.destroyWindow(windowId);
    }

    @Override
    public long windowId() {
        return this.windowId;
    }
}
