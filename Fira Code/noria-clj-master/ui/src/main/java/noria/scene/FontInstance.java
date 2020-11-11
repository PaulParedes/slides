package noria.scene;

import java.util.Arrays;
import java.util.Objects;

public class FontInstance {
    public final long id;
    public final int size;
    public final FontVariation[] variations;

    private final int _hashCode;
    
    public FontInstance(long id, int size) {
        this(id, size, new FontVariation[0]);
    }

    public FontInstance(long id, int size, FontVariation[] variations) {
        this.id = id;
        this.size = size;
        this.variations = variations;
        this._hashCode = Objects.hash(id, size) * 31 + Arrays.hashCode(variations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FontInstance that = (FontInstance) o;
        return _hashCode == that._hashCode &&
                id == that.id &&
                size == that.size &&
                Arrays.equals(variations, that.variations);
    }

    @Override
    public int hashCode() { return _hashCode; }
}