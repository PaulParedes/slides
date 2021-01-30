package noria.scene;

import java.util.Objects;

public class FontSpec {
    public final String family;
    public final String style;
    public final int size;
    public final int weight;

    public FontSpec(String family, int size) {
        this(family, "Regular", size, 400);
    }

    public FontSpec(String family, int size, int weight) {
        this(family, "Regular", size, weight);
    }
    
    public FontSpec(String family, String style, int size, int weight) {
        this.family = family;
        this.size = size;
        this.weight = weight;
        this.style = style;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FontSpec fontSpec = (FontSpec) o;
        return size == fontSpec.size &&
                weight == fontSpec.weight &&
                Objects.equals(family, fontSpec.family) &&
                Objects.equals(style, fontSpec.style);
    }

    @Override
    public int hashCode() {
        return Objects.hash(family, size, weight, style);
    }

    public FontSpec withFamily(String newFamily) {
        return new FontSpec(newFamily, style, size, weight);
    }
    
    public FontSpec withStyle(String newStyle) {
        return new FontSpec(family, newStyle, size, weight);
    }

    public FontSpec withSize(int newSize) {
        return new FontSpec(family, style, newSize, weight);
    }
    
    public FontSpec withWeight(int newWeight) {
        return new FontSpec(family, style, size, newWeight);
    }
}
