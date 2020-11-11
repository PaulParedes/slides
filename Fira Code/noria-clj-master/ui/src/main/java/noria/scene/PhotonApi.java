package noria.scene;

import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

public class PhotonApi {
  static final Unsafe unsafe;

  static boolean useSharedLibrary() {
    final Object mode = System.getProperty("noria.photon.mode");
    return mode != null && mode.equals("SHARED_LIBRARY");
  }

  static {
    if (useSharedLibrary()) {
      if (System.getProperty("noria.photon.log", "INFO").equals("DEBUG")) {
        System.load(new File("../photon/target/debug/", System.mapLibraryName("photon")).getAbsolutePath());
      }
      else {
        File dylib = new File("../photon/target/release", System.mapLibraryName("photon"));
        if (dylib.exists()) {
          System.load(dylib.getAbsolutePath());
        }
        else {
          try {
            LibLoader.loadLibraryFromJar("/" + System.mapLibraryName("photon"));
          }
          catch (IOException e) {
            throw new RuntimeException("Can't load load photon", e);
          }
        }
      }
    }

    try {
      final Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      unsafe = (Unsafe)field.get(null);
    }
    catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException("Can't init Unsafe instance");
    }
  }

  static class PhotonException extends RuntimeException {
    public PhotonException(String message) {
      super(message);
    }
  }

  public static void runEventLoop(Consumer<ArrayList<UserEvent>> f) {
    runEventLoopImpl(new EventsBuffer() {
      @Override
      public void handleEvents(ArrayList<UserEvent> events) {
        f.accept(events);
      }
    });
  }

  abstract static class EventsBuffer {
    ByteBuffer currentBuffer = ByteBuffer.allocateDirect(0);

    long getByteBufferAddress(long size) {
      if (currentBuffer.capacity() < size) {
        currentBuffer = ByteBuffer.allocateDirect((int)size);
      }
      currentBuffer.clear();
      return ((DirectBuffer)currentBuffer).address();
    }

    void onReady() {
      try {
        handleEvents(Deserializer.deserialize(currentBuffer));
      }
      catch (Throwable t) {
        t.printStackTrace();
      }
    }

    public abstract void handleEvents(ArrayList<UserEvent> events);
  }

  public static Scene createWindow(long nodeId, Size size, String title, boolean transparent_titlebar) {
    createWindowImpl(nodeId, size.width, size.height, title, transparent_titlebar);
    return new SceneImpl(nodeId);
  }

  public static native void destroyWindow(long nodeId);

  public enum Wrap {
    WORDS,
    LINES
  }

  private static native void runEventLoopImpl(EventsBuffer eventsBuffer);

  private static native void createWindowImpl(long nodeId, float width, float height, String title, boolean transparent_titlebar);

  static native boolean commit(long windowId, long frameId, long addr, long size);

  public static native void setAnimationRunning(boolean value);

  public static native void setIMEPosition(long windowId, float x, float y);

  public static native void stopApplication();

  public static native void loadFont(long fontId, byte[] bytes);

  public static native void loadImage(long imageId, byte[] bytes);

  public static void loadImageResource(long imageId, String path) {
    try (InputStream is = PhotonApi.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new RuntimeException("Can’t find resource " + path);
      }
      loadImage(imageId, is.readAllBytes());
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static native void layoutTextImpl(long fontId,
                                            int fontSize,
                                            long variation_tag,
                                            float variation_value,
                                            float width,
                                            String text,
                                            int wrap,
                                            long[] result);

  private static native void releaseTextLayoutImpl(long addr, long bufferLength);

  public static class TextShape {
    final static long lengthSize = 8;
    final static long glyphIndexSize = 4;
    final static long clusterSize = 4;
    final static long advanceSize = 4;
    final static long offsetSize = 4;
    final static long safeToBreakSize = 1;

    public final int[] glyphIndexes;
    public final int[] xAdvances;
    public final int[] yAdvances;
    public final int[] xOffsets;
    public final int[] yOffsets;
    public final int[] clusters;
    public final boolean[] safeToBreak;
    public final int length;

    @Override
    public String toString() {
      return "TextShape{" +
             "glyphIndexes=" + Arrays.toString(glyphIndexes) +
             ", xAdvances=" + Arrays.toString(xAdvances) +
             ", yAdvances=" + Arrays.toString(yAdvances) +
             ", xOffsets=" + Arrays.toString(xOffsets) +
             ", yOffsets=" + Arrays.toString(yOffsets) +
             ", clusters=" + Arrays.toString(clusters) +
             ", safeToBreak=" + Arrays.toString(safeToBreak) +
             ", length=" + length +
             '}';
    }

    TextShape(int[] glyphIndexes, int[] glyphClusters, int[] xAdvances, int[] yAdvances, int[] xOffsets, int[] yOffsets, boolean[] safeToBreak, int length) {
      this.glyphIndexes = glyphIndexes;
      this.clusters = glyphClusters;
      this.xAdvances = xAdvances;
      this.yAdvances = yAdvances;
      this.xOffsets = xOffsets;
      this.yOffsets = yOffsets;
      this.safeToBreak = safeToBreak;
      this.length = length;
    }

    public static TextShape fromBuffer(long bufferAddr) {
      int glyphsCount = (int)unsafe.getLong(bufferAddr);
      int[] glyphIndexes = new int[glyphsCount];
      int[] clusters = new int[glyphsCount];
      int[] xAdvances = new int[glyphsCount];
      int[] yAdvances = new int[glyphsCount];
      int[] xOffsets = new int[glyphsCount];
      int[] yOffsets = new int[glyphsCount];
      boolean[] safeToBreak = new boolean[glyphsCount];
      for (int i = 0; i < glyphsCount; ++i) {
        final long glyphAddr = bufferAddr + lengthSize + (glyphIndexSize + clusterSize + advanceSize * 2 + offsetSize * 2 + safeToBreakSize) * i;
        glyphIndexes[i] = unsafe.getInt(glyphAddr);
        clusters[i] = unsafe.getInt(glyphAddr + glyphIndexSize);
        xAdvances[i] = unsafe.getInt(glyphAddr + glyphIndexSize + clusterSize);
        yAdvances[i] = unsafe.getInt(glyphAddr + glyphIndexSize + clusterSize + advanceSize);
        xOffsets[i] = unsafe.getInt(glyphAddr + glyphIndexSize + clusterSize + advanceSize * 2);
        yOffsets[i] = unsafe.getInt(glyphAddr + glyphIndexSize + clusterSize + advanceSize * 2 + offsetSize);
        safeToBreak[i] = unsafe.getByte(glyphAddr + glyphIndexSize + clusterSize + advanceSize * 2 + offsetSize * 2) != 0;
      }
      return new TextShape(glyphIndexes, clusters, xAdvances, yAdvances, xOffsets, yOffsets, safeToBreak, (int)glyphsCount);
    }
  }

  public static class TextLayout {
    final static long lengthSize = 8;
    final static long glyphIndexSize = 4;
    final static long pointCoordSize = 4;
    final static long sizeCompSize = 4;
    final static long clusterSize = 4;

    public final FontInstance fontInstance;
    public final int glyphsCount;
    public final int[] glyphIndexes;
    public final int[] glyphClusters;
    // pairs of [x1,y1,x2,y2, ...]
    public final float[] glyphPositions;
    public final Size size;

    public TextLayout(int glyphsCount,
                      int[] glyphIndexes,
                      int[] glyphClusters,
                      float[] glyphPositions,
                      FontInstance fontInstance,
                      Size size) {
      this.glyphsCount = glyphsCount;
      this.glyphIndexes = glyphIndexes;
      this.glyphClusters = glyphClusters;
      this.glyphPositions = glyphPositions;
      this.fontInstance = fontInstance;
      this.size = size;
    }

    public static TextLayout fromBuffer(FontInstance fontInstance, long bufferAddr, long bufferLength) {
      int glyphsCount = (int)unsafe.getLong(bufferAddr);
      int[] glyphIndexes = new int[glyphsCount];
      float[] glyphPositions = new float[glyphsCount * 2];
      for (int i = 0; i < glyphsCount; i++) {
        final long glyphIndexAddr = bufferAddr + lengthSize + (glyphIndexSize + pointCoordSize * 2) * i;
        final long pointAddr = bufferAddr + lengthSize + (glyphIndexSize + pointCoordSize * 2) * i + glyphIndexSize;
        glyphIndexes[i] = unsafe.getInt(glyphIndexAddr);
        glyphPositions[2 * i] = unsafe.getFloat(pointAddr);
        glyphPositions[2 * i + 1] = unsafe.getFloat(pointAddr + pointCoordSize);
      }
      final long sizeAddr = bufferAddr
                            + lengthSize
                            + (glyphIndexSize + pointCoordSize * 2) * glyphsCount;
      Size size = new Size(unsafe.getFloat(sizeAddr), unsafe.getFloat(sizeAddr + sizeCompSize));
      final long clustersAddr = bufferAddr
                                + lengthSize
                                + (glyphIndexSize + pointCoordSize * 2) * glyphsCount
                                + sizeCompSize * 2;
      final int clustersCount = (int)unsafe.getLong(clustersAddr);
      assert clustersCount == glyphsCount;
      int[] glyphClusters = new int[clustersCount];
      for (int i = 0; i < clustersCount; i++) {
        glyphClusters[i] = unsafe.getInt(clustersAddr + lengthSize + clusterSize * i);
      }
      PhotonApi.releaseTextLayoutImpl(bufferAddr, bufferLength);
      return new TextLayout((int)glyphsCount, glyphIndexes, glyphClusters, glyphPositions, fontInstance, size);
    }

    public Size size() {
      return size;
    }

    public long glyphsCount() {
      return glyphsCount;
    }

    public long glyphIndex(long offset) {
      return glyphIndexes[(int)offset];
    }

    public long glyphCluster(long offset) {
      return glyphClusters[(int)offset];
    }

    public Point glyphPosition(long offset) {
      return offset == 0 ? new Point(0f, 0f) : new Point(glyphPositions[(int)(2 * offset)], glyphPositions[(int)(2 * offset) + 1]);
    }
  }

  public static TextLayout layoutText(FontInstance fontInstance, float width, String text, Wrap wrap) {
    final long[] result = new long[2];
    if (fontInstance.variations.length > 0) {
      layoutTextImpl(fontInstance.id, fontInstance.size, fontInstance.variations[0].tag, fontInstance.variations[0].value, width, text,
                     wrap.ordinal(), result);
    }
    else {
      layoutTextImpl(fontInstance.id, fontInstance.size, 0, 0f, width, text, wrap.ordinal(), result);
    }
    return TextLayout.fromBuffer(fontInstance, result[0], result[1]);
  }

  public static TextShape shapeText(FontInstance fontInstance, String text) {
    final long[] result = new long[2];
    if (fontInstance.variations.length > 0) {
      shapeTextImpl(fontInstance.id, fontInstance.variations[0].tag, fontInstance.variations[0].value, text, result);
    }
    else {
      shapeTextImpl(fontInstance.id, 0, 0f, text, result);
    }
    TextShape shape = TextShape.fromBuffer(result[0]);
    releaseBuffer(result[0], result[1]);
    return shape;
  }

  public static class FontMetrics {
    public static final int fieldSize = 4;

    public final int unitsPerEm;
    public final float ascent;
    public final float descent;
    public final float lineGap;
    public final float underlinePosition;
    public final float underlineThickness;
    public final float capHeight;
    public final float xHeight;

    public FontMetrics(int unitsPerEm,
                       float ascent,
                       float descent,
                       float lineGap,
                       float underlinePosition,
                       float underlineThickness,
                       float capHeight,
                       float xHeight) {
      this.unitsPerEm = unitsPerEm;
      this.ascent = ascent;
      this.descent = descent;
      this.lineGap = lineGap;
      this.underlinePosition = underlinePosition;
      this.underlineThickness = underlineThickness;
      this.capHeight = capHeight;
      this.xHeight = xHeight;
    }

    static FontMetrics fromBuffer(long bufferAddr) {
      int unitsPerEm = unsafe.getInt(bufferAddr);
      float ascent = unsafe.getFloat(bufferAddr + fieldSize);
      float descent = unsafe.getFloat(bufferAddr + fieldSize * 2);
      float lineGap = unsafe.getFloat(bufferAddr + fieldSize * 3);
      float underlinePosition = unsafe.getFloat(bufferAddr + fieldSize * 4);
      float underlineThickness = unsafe.getFloat(bufferAddr + fieldSize * 5);
      float capHeight = unsafe.getFloat(bufferAddr + fieldSize * 6);
      float xHeight = unsafe.getFloat(bufferAddr + fieldSize * 7);
      return new FontMetrics(unitsPerEm, ascent, descent, lineGap, underlinePosition, underlineThickness, capHeight, xHeight);
    }
  }

  private static native void fontMetricsImpl(long fontId, long[] result);

  public static FontMetrics fontMetrics(FontInstance fontInstance) {
    final long[] result = new long[2];
    fontMetricsImpl(fontInstance.id, result);
    FontMetrics metrics = FontMetrics.fromBuffer(result[0]);
    releaseBuffer(result[0], result[1]);
    return metrics;
  }

  private static native void shapeTextImpl(long fontId,
                                           long variation_tag,
                                           float variation_value,
                                           String text,
                                           long[] result);

  private static native void releaseBuffer(long addr, long bufferLength);


  public static native void setDebugProfilerShowing(long windowId, boolean state);

  public static native void dropCpuProfile(String filename);

  public static native String getClipboardContent();

  public static native void setClipboardContent(String content);

  public enum MouseCursor {
    Default,
    Crosshair,
    Hand,
    Arrow,
    Move,
    Text,
    Wait,
    Help,
    Progress,
    NotAllowed,
    ContextMenu,
    Cell,
    VerticalText,
    Alias,
    Copy,
    NoDrop,
    Grab,
    Grabbing,
    AllScroll,
    ZoomIn,
    ZoomOut
  }

  public static void setCursor(long windowId, MouseCursor cursor) { setCursorImpl(windowId, cursor.ordinal()); }

  private static native void setCursorImpl(long windowId, long cursor);

  public interface OnFileChosen {
    void invoke(String path);
  }

  public static void openFileDialog(String dialogName, String defaultPath, OnFileChosen onFileChosen) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        onFileChosen.invoke(openFileDialogImpl(dialogName, defaultPath));
      }
    }).start();
  }

  private static native String openFileDialogImpl(String dialogName, String defaultPath);
}
