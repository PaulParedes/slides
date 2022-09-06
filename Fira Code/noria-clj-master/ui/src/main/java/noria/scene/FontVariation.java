package noria.scene;

import java.util.Objects;

public class FontVariation {
    private static long tagFromString(String code) {
        return code.charAt(0) << 24L | code.charAt(1) << 16L | code.charAt(2) << 8L | code.charAt(3);
    }

    public static final long TAG_WEIGHT = tagFromString("wght");

    public final long tag;
    public final float value;

    public FontVariation(long tag, float value) {
        this.tag = tag;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FontVariation that = (FontVariation) o;
        return tag == that.tag &&
                Float.compare(that.value, value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, value);
    }
}