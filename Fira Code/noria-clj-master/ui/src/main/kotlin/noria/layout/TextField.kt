package noria.layout

import noria.*
import noria.scene.*
import noria.scene.UserEvent.VirtualKeyCode
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.timerTask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TextFieldStyle(val bg: Color,
                          val text: Color,
                          val placeholder: Color,
                          val border: Color) {
  constructor(bg: String, text: String, placeholder: String, border: String) : this(Color.css(bg), Color.css(text), Color.css(placeholder),
                                                                                    Color.css(border))
}

data class TextFieldOptions(val defaultStyle: TextFieldStyle? = null,
                            val hoverStyle: TextFieldStyle? = null,
                            val focusedStyle: TextFieldStyle? = null,
                            val disabledStyle: TextFieldStyle? = null,
                            val padding: Rect? = null,
                            val cursorColor: Color? = null,
                            val selectionColor: Color? = null,
                            val font: FontSpec? = null,
                            val borderRadius: Float? = null,
                            val placeholderText: String? = null,
                            val doubleClickDelay: Long? = null) {
  companion object {
    val constructorParameterNames = listOf("defaultStyle", "hoverStyle", "focusedStyle", "disabledStyle", "padding", "cursorColor",
                                           "selectionColor", "font", "borderRadius", "placeholderText", "doubleClickDelay")
  }

  fun merge(opts: TextFieldOptions?) =
    if (opts == null)
      this
    else
      TextFieldOptions(
        defaultStyle = opts.defaultStyle ?: defaultStyle,
        hoverStyle = opts.hoverStyle ?: hoverStyle,
        focusedStyle = opts.focusedStyle ?: focusedStyle,
        disabledStyle = opts.disabledStyle ?: disabledStyle,
        padding = opts.padding ?: padding,
        cursorColor = opts.cursorColor ?: cursorColor,
        selectionColor = opts.selectionColor ?: selectionColor,
        font = opts.font ?: font,
        borderRadius = opts.borderRadius ?: borderRadius,
        placeholderText = opts.placeholderText ?: placeholderText,
        doubleClickDelay = opts.doubleClickDelay ?: doubleClickDelay)
}

private val defaultOptions = TextFieldOptions(
  padding = Rect(8f, 8f, 8f, 8f),
  defaultStyle = TextFieldStyle("00000000", "000000", "605E5C", "8A8886"),
  hoverStyle = TextFieldStyle("00000000", "000000", "605E5C", "323130"),
  focusedStyle = TextFieldStyle("00000000", "000000", "605E5C", "0878D4"),
  disabledStyle = TextFieldStyle("F3F2F1", "A19F9D", "A19F9D", "00000000"),
  cursorColor = Color.black,
  selectionColor = Color.css("B3D7FF"),
  font = fontSystemRegular,
  borderRadius = 2f,
  placeholderText = "",
  doubleClickDelay = 500)


fun Frame.textField(initialText: String,
                    opts: TextFieldOptions? = null): TextField {
  val tState = state { TextFieldState(text = initialText) }
  return TextField(tState, expr { tState::update }, defaultOptions.merge(opts))
}

fun Frame.textField(initialState: TextFieldState = TextFieldState(),
                    opts: TextFieldOptions? = null): TextField {
  val tState = state { initialState }
  return TextField(tState, expr { tState::update }, defaultOptions.merge(opts))
}

data class TextFieldState(val text: String = "",
                          val selectionStart: Int = 0,
                          val selectionEnd: Int = 0,
                          val scrollOffset: Float = 0f,
                          val focused: Boolean = false,
                          val hovered: Boolean = false,
                          val disabled: Boolean = false,
                          val bbox: Rect? = null,
                          val lastClicks: List<Long> = listOf(0, 0)) {
  val selectionMin get() = min(selectionStart, selectionEnd)
  val selectionMax get() = max(selectionStart, selectionEnd)
  val collapsed get() = selectionStart == selectionEnd
}

data class TextField(val tState: Thunk<TextFieldState>,
                     val tUpdateState: Thunk<(Transformation<TextFieldState>) -> Unit>,
                     val opts: TextFieldOptions = defaultOptions) : RenderObject {

  companion object {
    val wordPattern: Pattern = Pattern.compile("""(\p{Alnum}|_)+""")
  }

  private val fontInstance = FontManager.resolve(opts.font)
  private val placeholderLayout = PhotonApi.layoutText(fontInstance, Float.POSITIVE_INFINITY, opts.placeholderText, PhotonApi.Wrap.LINES)

  private fun findWordLeft(text: String, position: Int): Int {
    val matcher = wordPattern.matcher(text)
    var wordLeft = 0
    while (matcher.find()) {
      if (matcher.start() >= position)
        return wordLeft
      wordLeft = matcher.start()
    }
    return wordLeft
  }

  private fun findWordRight(text: String, position: Int): Int {
    val matcher = wordPattern.matcher(text)
    if (matcher.find(position) && matcher.end() > position)
      return matcher.end()
    return text.length
  }

  private fun selectWord(text: String, position: Int): Pair<Int, Int> {
    val matcher = wordPattern.matcher(text)
    var start = 0
    var end = text.length
    while (matcher.find()) {
      if (matcher.start() <= position)
        start = matcher.start()
      if (matcher.end() <= position)
        start = matcher.end()
      if (matcher.start() > position && matcher.start() < end)
        end = matcher.start()
      if (matcher.end() > position && matcher.end() < end)
        end = matcher.end()
    }
    return Pair(start, end)
  }

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    val padding = opts.padding!!
    val ownHeight = 16f + padding.top + padding.bottom
    val height = if (cs.maxHeight.isFinite())
      min(cs.maxHeight, ownHeight)
    else
      ownHeight
    return Measure(cs.maxWidth, height)
  }

  private fun newScrollOffset(textLayout: PhotonApi.TextLayout, selectionEnd: Int, scrollOffset: Float, size: Size): Float {
    val textToCursor = textLayout.offsetPosition(selectionEnd.toLong())
    val textToEnd = textLayout.size().width
    val padding = opts.padding!!
    return when {
      textToCursor + padding.left - scrollOffset < padding.left -> max(0f, min(textToCursor,
                                                                               textToEnd + padding.left - size.width + padding.right))
      textToCursor + padding.left - scrollOffset > size.width - padding.right -> textToCursor + padding.left - size.width + padding.right
      else -> scrollOffset
    }
  }

  private fun offsetToPosition(textLayout: PhotonApi.TextLayout, offset: Float): Int {
    return (0..textLayout.glyphsCount() + 1).minBy { abs(textLayout.offsetPosition(it) - offset) }!!.toInt()
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
    with(frame) {

      // stack
      // - bg
      // - scroll
      //   - scrollContent
      //     - selection
      //     - text

      val stackId = generateId()
      val bgId = generateId()
      val bgClipId = generateId()
      val borderId = generateId()
      val scrollId = generateId()
      val scrollContentId = generateId()
      val selectionId = generateId()
      val selectionStackId = generateId()
      val textId = generateId()

      val state = read(tState)
      val text = state.text
      val length = text.length
      val hovered = state.hovered
      val focused = state.focused
      val disabled = state.disabled
      val collapsed = state.collapsed
      val bbox = state.bbox
      val dragged = bbox != null
      val updateState = read(tUpdateState)
      val style = when {
        disabled -> opts.disabledStyle
        focused -> opts.focusedStyle
        hovered -> opts.hoverStyle
        else -> opts.defaultStyle
      }!!
      val padding = opts.padding!!
      val borderRadius = opts.borderRadius!!
      val doubleClickDelay = opts.doubleClickDelay!!
      val size = measure.asSize()

      // bg
      val scene = scene()
      scene.rect(bgId, Point(0f, 0f), size, style.bg)
      scene.clip(bgClipId,
                 Rect(Point(0f, 0f), size),
                 listOf(Scene.ComplexClipRegion(Rect(Point(.0f, .0f), size),
                                                BorderRadius.uniform(borderRadius),
                                                Scene.ClipMode.CLIP)),
                 bgId)

      // border
      scene.border(borderId, Point(0f, 0f), size, style.border, 1f, Scene.BorderStyle.SOLID, BorderRadius.uniform(borderRadius))

      // text
      val textLayout = memo(tState, fontInstance) {
        PhotonApi.layoutText(fontInstance, Float.POSITIVE_INFINITY, read(tState).text, PhotonApi.Wrap.LINES)
      }
      if (text.isEmpty())
        scene.text(textId,
                   Point(padding.left, padding.top),
                   Size(Float.POSITIVE_INFINITY, size.height - padding.top - padding.bottom),
                   style.placeholder,
                   placeholderLayout)
      else
        scene.text(textId,
                   Point(padding.left, padding.top),
                   Size(Float.POSITIVE_INFINITY, size.height - padding.top - padding.bottom),
                   style.text,
                   textLayout)

      val tOnEventId = subscribeHit(scene, bgId, listOf(UserEvent.EventType.MOUSE_INPUT, UserEvent.EventType.CURSOR_MOVED))
      { e, hit ->
        when {
          // onClick
          e is UserEvent.MouseInput
          && e.button == UserEvent.MouseButton.LEFT
          && e.state == UserEvent.ButtonState.PRESSED
          && !disabled -> {
            val pos = offsetToPosition(textLayout, hit.relativeCursorPosition.x + state.scrollOffset - padding.left)
            val now = System.currentTimeMillis()

            val (newStart, newEnd, newLastClicks) = when {
              // tripleClick
              state.lastClicks[1] > now - doubleClickDelay * 2 ->
                Triple(0, length, listOf<Long>(0, 0))
              // doubleClick
              state.lastClicks[0] > now - doubleClickDelay -> {
                val (newStart, newEnd) = selectWord(text, pos)
                Triple(newStart, newEnd, listOf(now, state.lastClicks[0]))
              }
              else ->
                Triple(pos, pos, listOf(now, state.lastClicks[0]))
            }

            val newOffset = newScrollOffset(textLayout, newEnd, state.scrollOffset, size)
            val newBbox = Rect(e.cursorPosition.x - hit.relativeCursorPosition.x,
                               e.cursorPosition.y - hit.relativeCursorPosition.y,
                               e.cursorPosition.x - hit.relativeCursorPosition.x + size.width,
                               e.cursorPosition.y - hit.relativeCursorPosition.y + size.height)
            updateState {
              it.copy(focused = true,
                      selectionStart = newStart,
                      selectionEnd = newEnd,
                      scrollOffset = newOffset,
                      bbox = newBbox,
                      lastClicks = newLastClicks)
            }
            Propagate.STOP
          }

          // onHover
          e is UserEvent.CursorMoved && !hovered -> {
            if (!dragged)
              PhotonApi.setCursor(e.windowId, PhotonApi.MouseCursor.Text)
            updateState { it.copy(hovered = true) }
            Propagate.STOP
          }

          else ->
            Propagate.CONTINUE
        }
      }

      if (hovered) {
        // onBlur
        subscribeGlobal(UserEvent.EventType.CURSOR_MOVED) { e ->
          e as UserEvent.CursorMoved
          if (e.hits.all { it.callbackId != tOnEventId }) {
            if (!dragged)
              PhotonApi.setCursor(e.windowId, PhotonApi.MouseCursor.Default)
            updateState { it.copy(hovered = false) }
          }
        }
      }

      val scrollContentChildren = mutableListOf(textId)

      if (focused && !disabled) {
        // selection

        val start = textLayout.offsetPosition(state.selectionMin.toLong())
        val end = if (collapsed) start else textLayout.offsetPosition(state.selectionMax.toLong())

        scene.rect(selectionId,
                   Point(padding.left + start, padding.top),
                   Size(end - start + 1, size.height - padding.top - padding.bottom),
                   if (collapsed) opts.cursorColor else opts.selectionColor)

        scene.stack(selectionStackId, longArrayOf(selectionId))
        scene.setOpacity(selectionStackId, 1f)
        //            PhotonApi.setIMEPosition(end, padding.top) // TODO need screen-space coords

        scrollContentChildren.add(0, selectionStackId)

        val tCursorOpacity = state { 1f }
        if (collapsed) {
          resource("cursorAnimation",
                   {
                     val task = timerTask { tCursorOpacity.update { if (1f == it) 0f else 1f } }
                     timer.schedule(task, 500, 500)
                     task
                   },
                   { task: TimerTask -> task.cancel() })

          expr {
            scene().setOpacity(selectionStackId, read(tCursorOpacity))
          }
        }

        // onBlur && drag
        subscribeGlobal(UserEvent.EventType.MOUSE_INPUT) { e ->
          e as UserEvent.MouseInput
          when {
            e.button == UserEvent.MouseButton.LEFT
            && e.state == UserEvent.ButtonState.PRESSED
            && e.hits.all { it.callbackId != tOnEventId } ->
              updateState { it.copy(focused = false) }
            e.button == UserEvent.MouseButton.LEFT
            && e.state == UserEvent.ButtonState.RELEASED
            && dragged -> {
              if (!hovered)
                PhotonApi.setCursor(e.windowId, PhotonApi.MouseCursor.Default)
              updateState { it.copy(bbox = null) }
            }
          }
        }

        // drag
        if (bbox != null /* dragged */) {
          subscribeGlobal(UserEvent.EventType.CURSOR_MOVED) { e ->
            e as UserEvent.CursorMoved
            val outside = e.cursorPosition.y < bbox.top + padding.top || e.cursorPosition.y > bbox.bottom - padding.bottom
            val newSelectionEnd =
              if (outside) {
                val selectionStartOffset = bbox.left + padding.left + textLayout.offsetPosition(
                  state.selectionStart.toLong()) - state.scrollOffset
                if (e.cursorPosition.x < selectionStartOffset)
                  0
                else
                  textLayout.glyphsCount().toInt()
              }
              else
                offsetToPosition(textLayout, e.cursorPosition.x - bbox.left + state.scrollOffset - padding.left)

            val newOffset = newScrollOffset(textLayout, newSelectionEnd, state.scrollOffset, size)
            updateState {
              it.copy(selectionEnd = newSelectionEnd,
                      scrollOffset = newOffset,
                      lastClicks = listOf(0, 0))
            }
          }
        }

        // onType
        subscribeGlobal(UserEvent.EventType.CHARACTER_TYPED) { e ->
          e as UserEvent.CharacterTyped
          val newText = text.substring(0, state.selectionMin) + e.chars + text.substring(state.selectionMax)
          val newSelection = state.selectionMin + e.chars.length
          val newScrollOffset = newScrollOffset(textLayout, newSelection, state.scrollOffset, size)
          tCursorOpacity.update { 1f }
          updateState {
            it.copy(text = newText,
                    selectionStart = newSelection,
                    selectionEnd = newSelection,
                    scrollOffset = newScrollOffset)
          }
        }

        // onInput
        subscribeGlobal(UserEvent.EventType.KEYBOARD_INPUT) { e ->
          e as UserEvent.KeyboardInput
          if (e.state == UserEvent.ButtonState.PRESSED) {
            val kc = e.keyCode
            val ctrl = e.modifiers.ctrl
            val cmd = e.modifiers.cmd
            val alt = e.modifiers.alt
            val action = when {
              kc == VirtualKeyCode.Left && cmd -> "moveToBeginningOfLine"
              kc == VirtualKeyCode.Left && alt -> "moveWordLeft"
              kc == VirtualKeyCode.Left -> "moveLeft"
              kc == VirtualKeyCode.Right && cmd -> "moveToEndOfLine"
              kc == VirtualKeyCode.Right && alt -> "moveWordRight"
              kc == VirtualKeyCode.Right -> "moveRight"
              kc == VirtualKeyCode.Up -> "moveUp"
              kc == VirtualKeyCode.Down -> "moveDown"
              kc == VirtualKeyCode.Home -> "moveToBeginningOfLine"
              kc == VirtualKeyCode.End -> "moveToEndOfLine"
              kc == VirtualKeyCode.PageUp -> "pageUp"
              kc == VirtualKeyCode.PageDown -> "pageDown"
              kc == VirtualKeyCode.Back && alt -> "deleteWordBackward"
              kc == VirtualKeyCode.Back -> "deleteBackward"
              kc == VirtualKeyCode.Delete && alt -> "deleteWordForward"
              kc == VirtualKeyCode.Delete -> "deleteForward"

              kc == VirtualKeyCode.A && ctrl -> "moveToBeginningOfLine"
              kc == VirtualKeyCode.B && ctrl -> "moveLeft"
              kc == VirtualKeyCode.D && ctrl -> "deleteForward"
              kc == VirtualKeyCode.E && ctrl -> "moveToEndOfLine"
              kc == VirtualKeyCode.F && ctrl -> "moveRight"
              kc == VirtualKeyCode.H && ctrl -> "deleteBackward"
              kc == VirtualKeyCode.K && ctrl -> "deleteToEndOfParagraph"
              kc == VirtualKeyCode.N && ctrl -> "moveDown"
              kc == VirtualKeyCode.P && ctrl -> "moveUp"
              kc == VirtualKeyCode.V && ctrl -> "pageDown"

              kc == VirtualKeyCode.A && cmd -> "selectAll"
              kc == VirtualKeyCode.C && cmd -> "copy"
              kc == VirtualKeyCode.V && cmd -> "paste"
              kc == VirtualKeyCode.X && cmd -> "cut"

              else -> null
            }

            val (newText, newSelectionStart, newSelectionEnd) = when {
              action == "moveToBeginningOfLine" || action == "moveUp" || action == "pageUp" ->
                if (state.selectionStart < state.selectionEnd)
                  Triple(text, 0, if (e.modifiers.shift) state.selectionEnd else 0)
                else
                  Triple(text, if (e.modifiers.shift) state.selectionStart else 0, 0)

              action == "moveLeft" && e.modifiers.shift ->
                Triple(text, state.selectionStart, max(0, state.selectionEnd - 1))

              action == "moveLeft" && collapsed ->
                Triple(text, max(0, state.selectionEnd - 1), max(0, state.selectionEnd - 1))

              action == "moveLeft" ->
                Triple(text, state.selectionMin, state.selectionMin)

              action == "moveWordLeft" -> {
                val wordLeft = findWordLeft(text, state.selectionEnd)
                Triple(text, if (e.modifiers.shift) state.selectionStart else wordLeft, wordLeft)
              }

              action == "moveToEndOfLine" || action == "moveDown" || action == "pageDown" ->
                if (state.selectionStart <= state.selectionEnd)
                  Triple(text, if (e.modifiers.shift) state.selectionStart else length, length)
                else
                  Triple(text, length, if (e.modifiers.shift) state.selectionEnd else length)

              action == "moveRight" && e.modifiers.shift ->
                Triple(text, state.selectionStart, min(state.selectionEnd + 1, length))

              action == "moveRight" && collapsed ->
                Triple(text, min(state.selectionEnd + 1, length), min(state.selectionEnd + 1, length))

              action == "moveRight" ->
                Triple(text, state.selectionMax, state.selectionMax)

              action == "moveWordRight" -> {
                val wordRight = findWordRight(text, state.selectionEnd)
                Triple(text, if (e.modifiers.shift) state.selectionStart else wordRight, wordRight)
              }

              action == "deleteWordBackward" && collapsed -> {
                val wordLeft = findWordLeft(text, state.selectionEnd)
                Triple(text.substring(0, wordLeft) + text.substring(state.selectionEnd),
                       wordLeft, wordLeft)
              }

              action == "deleteBackward" && collapsed ->
                Triple(text.substring(0, max(0, state.selectionStart - 1)) + text.substring(state.selectionStart),
                       max(0, state.selectionStart - 1),
                       max(0, state.selectionStart - 1))

              action == "deleteWordForward" && collapsed -> {
                val wordRight = findWordRight(text, state.selectionEnd)
                Triple(text.substring(0, state.selectionEnd) + text.substring(wordRight),
                       state.selectionEnd, state.selectionEnd)
              }

              action == "deleteForward" && collapsed ->
                Triple(text.substring(0, state.selectionStart) + text.substring(min(length, state.selectionStart + 1)),
                       state.selectionStart,
                       state.selectionStart)

              action == "deleteBackward" || action == "deleteForward" ->
                Triple(text.substring(0, state.selectionMin) + text.substring(state.selectionMax),
                       state.selectionMin,
                       state.selectionMin)

              action == "deleteToEndOfParagraph" ->
                Triple(text.substring(state.selectionStart), state.selectionStart, state.selectionStart)

              action == "cut" && !collapsed -> {
                PhotonApi.setClipboardContent(text.substring(state.selectionMin, state.selectionMax))
                Triple(text.substring(0, state.selectionMin) + text.substring(state.selectionMax),
                       state.selectionMin,
                       state.selectionMin)
              }

              action == "copy" && !collapsed -> {
                PhotonApi.setClipboardContent(text.substring(state.selectionMin, state.selectionMax))
                Triple(text, state.selectionStart, state.selectionEnd)
              }

              action == "paste" -> {
                val paste = PhotonApi.getClipboardContent()
                Triple(text.substring(0, state.selectionMin) + paste + text.substring(state.selectionMax),
                       state.selectionMin + paste.length,
                       state.selectionMin + paste.length)
              }

              action == "selectAll" ->
                Triple(text, 0, length)

              else -> Triple(text, state.selectionStart, state.selectionEnd)
            }

            if (text != newText || state.selectionStart != newSelectionStart || state.selectionEnd != newSelectionEnd) {
              val newScrollOffset = newScrollOffset(textLayout, newSelectionEnd, state.scrollOffset, size)
              tCursorOpacity.update { 1f }
              updateState {
                it.copy(text = newText,
                        selectionStart = newSelectionStart,
                        selectionEnd = newSelectionEnd,
                        scrollOffset = newScrollOffset)
              }
            }
          }
        }
      }

      scene.stack(scrollContentId, scrollContentChildren.toLongArray())

      val scrollContentWidth = textLayout.size().width + padding.left + padding.right
      scene.scroll(scrollId, Point(1f, 0f),
                   Size(size.width - 1f, size.height), scrollContentId,
                   Size(scrollContentWidth, size.height))
      scene.scrollPosition(scrollId, Point(state.scrollOffset, 0f))
      scene.stack(stackId, longArrayOf(bgClipId, scrollId, borderId))
      return RenderResult(stackId)
    }
}

fun PhotonApi.TextLayout.offsetPosition(offset: Long): Float =
  when {
    0L == offset -> 0f
    offset < this.glyphsCount() -> this.glyphPosition(offset).x
    else -> this.size().width
  }
