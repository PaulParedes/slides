package noria.layout

enum class DimensionType { PX, PERCENT, STRETCH, HUG }
data class Dimension(val type: DimensionType, val value: Float) {
  fun convert(available: Float): Float =
    when (type) {
      DimensionType.PX -> value
      DimensionType.PERCENT -> value * available
      DimensionType.STRETCH -> value * available
      DimensionType.HUG -> throw NotImplementedError()
    }

  companion object {
    @JvmStatic
    fun px(value: Float): Dimension = Dimension(DimensionType.PX, value)

    @JvmStatic
    fun percent(value: Float): Dimension = Dimension(DimensionType.PERCENT, value)

    @JvmStatic
    fun stretch(value: Float): Dimension = Dimension(DimensionType.STRETCH, value)

    @JvmField
    val hug = Dimension(DimensionType.HUG, 0f)
    @JvmField
    val zero = Dimension(DimensionType.PX, 0f)
  }

  override fun toString(): String =
      when (type) {
        DimensionType.PX -> "${value}px"
        DimensionType.PERCENT -> "${decimalFormat.format(value * 100.0)}%"
        DimensionType.STRETCH -> "${decimalFormat.format(value)}stretch"
        DimensionType.HUG -> "hug"
      }
}