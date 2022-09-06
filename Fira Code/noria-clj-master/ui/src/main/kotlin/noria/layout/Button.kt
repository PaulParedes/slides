package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.ceil
import kotlin.math.max

data class ButtonStyle(val bg: Color,
                       val text: Color,
                       val border: Color) {
  constructor(bg: String, text: String, border: String) : this(Color.css(bg), Color.css(text), Color.css(border))
}

data class ButtonOptions(
  val defaultStyle: ButtonStyle? = null,
  val hoverStyle: ButtonStyle? = null,
  val activeStyle: ButtonStyle? = null,
  val disabledStyle: ButtonStyle? = null,
  val focusBorderColor: Color? = null,
  val width: Float? = null,
  val height: Float? = null,
  val padding: Rect? = null,
  val font: FontSpec? = null,
  val borderRadius: Float? = null) {
  companion object {
    val constructorParameterNames = listOf("defaultStyle", "hoverStyle", "activeStyle", "disabledStyle", "focusBorderColor", "width",
                                           "height", "padding", "font", "borderRadius")
  }

  fun merge(opts: ButtonOptions?) =
    if (opts == null)
      this
    else
      ButtonOptions(defaultStyle = opts.defaultStyle ?: defaultStyle,
                    hoverStyle = opts.hoverStyle ?: hoverStyle,
                    activeStyle = opts.activeStyle ?: activeStyle,
                    disabledStyle = opts.disabledStyle ?: disabledStyle,
                    focusBorderColor = opts.focusBorderColor ?: focusBorderColor,
                    width = opts.width ?: width,
                    height = opts.height ?: height,
                    padding = opts.padding ?: padding,
                    font = opts.font ?: font,
                    borderRadius = opts.borderRadius ?: borderRadius)
}

private val defaultOptions = ButtonOptions(
  defaultStyle = ButtonStyle("FFFFFF", "323130", "8A8886"),
  hoverStyle = ButtonStyle("F3F2F1", "323130", "323130"),
  activeStyle = ButtonStyle("EDEBE9", "323130", "323130"),
  disabledStyle = ButtonStyle("F3F2F1", "A19F9D", "00000000"),
  focusBorderColor = Color.css("605E5C"),
  width = null,
  height = null,
  padding = Rect(16f, 8f, 16f, 8f),
  font = fontSystemSemibold,
  borderRadius = 2f)

fun Frame.buttonStandard(label: String,
                                onClick: Computation<() -> Unit>,
                                initialState: ButtonState = ButtonState(),
                                opts: ButtonOptions? = null): Button {
  val tState = state { initialState }
  return Button(label, onClick, tState, expr { tState::update }, defaultOptions.merge(opts))
}

val primaryOpts = defaultOptions.merge(ButtonOptions(
  defaultStyle = ButtonStyle("0878D4", "ffffff", "00000000"),
  hoverStyle = ButtonStyle("106EBE", "ffffff", "00000000"),
  activeStyle = ButtonStyle("035A9E", "ffffff", "00000000"),
  focusBorderColor = Color.white))

fun Frame.buttonPrimary(label: String,
                               onClick: Computation<() -> Unit>,
                               initialState: ButtonState = ButtonState(),
                               opts: ButtonOptions? = null): Button {
  val tState = state { initialState }
  return Button(label, onClick, tState, expr { tState::update }, primaryOpts.merge(opts))
}

val actionOpts = defaultOptions.merge(ButtonOptions(
  defaultStyle = ButtonStyle("00000000", "323130", "00000000"),
  hoverStyle = ButtonStyle("00000000", "0878D4", "00000000"),
  activeStyle = ButtonStyle("00000000", "000000", "00000000"),
  disabledStyle = ButtonStyle("00000000", "A19F9D", "00000000"),
  focusBorderColor = Color.css("605E5C"),
  font = fontSystemRegular))

fun Frame.buttonAction(label: String,
                              onClick: Computation<() -> Unit>,
                              initialState: ButtonState = ButtonState(),
                              opts: ButtonOptions? = null): Button {
  val tState = state { initialState }
  return Button(label, onClick, tState, expr { tState::update }, actionOpts.merge(opts))
}

data class ButtonState(val hovered: Boolean = false,
                       val pressed: Boolean = false,
                       val focused: Boolean = false,
                       val disabled: Boolean = false)

data class Button(val label: String,
                  val tOnClick: Computation<() -> Unit>,
                  val tState: Thunk<ButtonState>,
                  val tUpdateState: Thunk<(Transformation<ButtonState>) -> Unit>,
                  val opts: ButtonOptions = defaultOptions) : RenderObject {
  private val fontInstance = FontManager.resolve(opts.font)

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    with(frame) {
      val textlayout = PhotonApi.layoutText(fontInstance, cs.maxWidth - opts.padding!!.left - opts.padding.right, label,
                                            PhotonApi.Wrap.LINES);

      val w = when {
        opts.width != null -> opts.width
        else -> max(ceil(textlayout.size().width) + opts.padding.left + opts.padding.right, cs.minWidth)
      }

      val h = when {
        opts.height != null -> opts.height
        else -> max(ceil(textlayout.size().height) + opts.padding.top + opts.padding.bottom, cs.minHeight)
      }

      return Measure(w, h, textlayout)
    }
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val bgId = generateId()
      val bgClipId = generateId()
      val textId = generateId()
      val stackId = generateId()

      val scene = scene()
      val state = read(tState)
      // that way we can close over specific part of state, not whole state object in exprs
      val pressed = state.pressed
      val hovered = state.hovered
      val disabled = state.disabled
      val updateState = read(tUpdateState)
      val children = mutableListOf<NodeId>()
      val onClick = read(tOnClick)

      val style = when {
        state.disabled -> opts.disabledStyle
        state.pressed && state.hovered -> opts.activeStyle
        state.hovered -> opts.hoverStyle
        else -> opts.defaultStyle
      } as ButtonStyle
      val size = measure.asSize()

      // rect
      val borderRadius = opts.borderRadius!!
      scene.rect(bgId, Point(0f, 0f), size, style.bg)
      scene.clip(bgClipId,
                 Rect(Point(0f, 0f), size),
                 listOf(Scene.ComplexClipRegion(Rect(Point(.0f, .0f), size),
                                                BorderRadius.uniform(borderRadius),
                                                Scene.ClipMode.CLIP)),
                 bgId)
      children.add(bgClipId)

      // border
      if (style.border != Color.transparent) {
        val borderId = generateId()
        scene.border(borderId, Point(0f, 0f),
                     Size(size.width, size.height), style.border, 1f, Scene.BorderStyle.SOLID, BorderRadius.uniform(borderRadius))
        children.add(borderId)
      }

      // focus border
      if (state.focused) {
        val focusedBorderId = generateId()
        scene.border(focusedBorderId, Point(2f, 2f),
                     Size(size.width - 4f, size.height - 4f), opts.focusBorderColor, 1f, Scene.BorderStyle.SOLID, BorderRadius.uniform(.0f))
        children.add(focusedBorderId)
      }

      // label
      val padding = opts.padding!!
      val textLayout = measure.extra as PhotonApi.TextLayout
      scene.text(textId,
                 Point((size.width - textLayout.size().width) / 2f,
                       (size.height - textLayout.size().height) / 2),
                 Size(Float.POSITIVE_INFINITY, size.height - padding.top - padding.bottom),
                 style.text, textLayout)
      children.add(textId)

      // stack
      scene.stack(stackId, children.toLongArray())

      // events
      val tOnEventThunkId = subscribeHit(scene, bgId, listOf(UserEvent.EventType.MOUSE_INPUT,
                                                             UserEvent.EventType.CURSOR_MOVED))
      { e, _ ->
        when {
          e is UserEvent.CursorMoved && !hovered -> {
            updateState { it.copy(hovered = true) }
            Propagate.STOP
          }
          e is UserEvent.MouseInput
          && e.button == UserEvent.MouseButton.LEFT
          && e.state == UserEvent.ButtonState.PRESSED
          && !pressed
          && !disabled -> {
            updateState { it.copy(pressed = true) }
            Propagate.STOP
          }
          e is UserEvent.MouseInput
          && e.button == UserEvent.MouseButton.LEFT
          && e.state == UserEvent.ButtonState.RELEASED
          && pressed
          && !disabled -> {
            onClick()
            Propagate.STOP
          }

          else -> Propagate.CONTINUE
        }
      }
      // global events

      subscribeGlobal(UserEvent.EventType.MOUSE_INPUT) { e ->
        e as UserEvent.MouseInput
        if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
          updateState { it.copy(pressed = false) }
        }
      }

      if (hovered)
        subscribeGlobal(UserEvent.EventType.CURSOR_MOVED) { e ->
          e as UserEvent.CursorMoved
          if (e.hits.none { it.callbackId == tOnEventThunkId })
            updateState { it.copy(hovered = false) }
        }

      return RenderResult(stackId)
    }
  }
}
