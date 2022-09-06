package noria.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public interface UserEvent {
    class NodeHit {
        public final long callbackId;
        public final Point relativeCursorPosition;

        public NodeHit(long callbackId, Point relativeCursorPosition) {
            this.callbackId = callbackId;
            this.relativeCursorPosition = relativeCursorPosition;
        }

        @Override
        public String toString() {
            return "NodeHit{" +
                    "callbackId=" + callbackId +
                    ", relativeCursorPosition=" + relativeCursorPosition +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeHit nodeHit = (NodeHit) o;
            return callbackId == nodeHit.callbackId &&
                    Objects.equals(relativeCursorPosition, nodeHit.relativeCursorPosition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callbackId, relativeCursorPosition);
        }
    }

    class ModifiersState {
        public final boolean shift;
        public final boolean ctrl;
        public final boolean alt;
        public final boolean cmd;

        public ModifiersState(boolean shift, boolean ctrl, boolean alt, boolean cmd) {
            this.shift = shift;
            this.ctrl = ctrl;
            this.alt = alt;
            this.cmd = cmd;
        }

        @Override
        public String toString() {
            return "ModifiersState{" +
                    "shift=" + shift +
                    ", ctrl=" + ctrl +
                    ", alt=" + alt +
                    ", cmd=" + cmd +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModifiersState that = (ModifiersState) o;
            return shift == that.shift &&
                    ctrl == that.ctrl &&
                    alt == that.alt &&
                    cmd == that.cmd;
        }

        @Override
        public int hashCode() {
            return Objects.hash(shift, ctrl, alt, cmd);
        }
    }
    
    EventType type();

    // according to event_loop.rs/EventType
    enum EventType {
        MOUSE_WHEEL,
        CURSOR_MOVED,
        MOUSE_INPUT,
        KEYBOARD_INPUT,
        CHARACTER_TYPED,
        WINDOW_RESIZE,
        NEW_FRAME,
        CLOSE_REQUEST,
        MOUSE_MOTION
    }

    enum TouchPhase {
        STARTED,
        MOVED,
        ENDED,
        CANCELLED
    }

    interface WindowEvent extends UserEvent {
        long getWindowId();
    }

    interface HitEvent extends WindowEvent {
        List<NodeHit> getHits();
    }

    class MouseWheel implements HitEvent {
        public final Vector2D delta;
        public final TouchPhase touchPhase;
        public final Point cursorPosition;
        public final ArrayList<NodeHit> hits;
        public final long windowId;

        public MouseWheel(ArrayList<NodeHit> hits, long windowId, Vector2D delta, TouchPhase touchPhase, Point cursorPosition) {
            this.delta = delta;
            this.touchPhase = touchPhase;
            this.cursorPosition = cursorPosition;
            this.hits = hits;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "MouseWheel{" +
                    "hits=" + hits +
                    ", delta=" + delta +
                    ", touchPhase=" + touchPhase +
                    '}';
        }

        @Override
        public EventType type() { return EventType.MOUSE_WHEEL; }

        @Override
        public long getWindowId() {
            return windowId;
        }

        @Override
        public List<NodeHit> getHits() {
            return hits;
        }
    }

    class CursorMoved implements HitEvent {
        public final Point cursorPosition;
        public final ArrayList<NodeHit> hits;
        public final long windowId;

        public CursorMoved(ArrayList<NodeHit> hits,  long windowId, Point cursorPosition) {
            this.cursorPosition = cursorPosition;
            this.hits = hits;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "CursorMoved{" +
                    "hits=" + hits +
                    ", cursorPosition=" + cursorPosition +
                    '}';
        }

        @Override
        public EventType type() { return EventType.CURSOR_MOVED; }

        @Override
        public long getWindowId() {
            return windowId;
        }

        @Override
        public List<NodeHit> getHits() {
            return hits;
        }
    }

    enum MouseButton {
        LEFT,
        RIGHT,
        MIDDLE,
        OTHER
    }

    enum ButtonState {
        PRESSED,
        RELEASED
    }

    class MouseInput implements HitEvent {
        public final MouseButton button;
        public final ButtonState state;
        public final Point cursorPosition;
        public final ArrayList<NodeHit> hits;
        public final long windowId;

        public MouseInput(ArrayList<NodeHit> hits,  long windowId, ButtonState state, MouseButton button, Point cursorPosition) {
            this.button = button;
            this.state = state;
            this.cursorPosition = cursorPosition;
            this.hits = hits;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "MouseInput{" +
                    "hits=" + hits +
                    ", button=" + button +
                    ", state=" + state +
                    '}';
        }

        @Override
        public EventType type() { return EventType.MOUSE_INPUT; }

        @Override
        public long getWindowId() {
            return windowId;
        }

        @Override
        public List<NodeHit> getHits() {
            return hits;
        }
    }

    // according to events.rs/VirtualKeyCode
    enum VirtualKeyCode {
        /// The '1' key over the letters.
        Key1,
        /// The '2' key over the letters.
        Key2,
        /// The '3' key over the letters.
        Key3,
        /// The '4' key over the letters.
        Key4,
        /// The '5' key over the letters.
        Key5,
        /// The '6' key over the letters.
        Key6,
        /// The '7' key over the letters.
        Key7,
        /// The '8' key over the letters.
        Key8,
        /// The '9' key over the letters.
        Key9,
        /// The '0' key over the 'O' and 'P' keys.
        Key0,

        A,
        B,
        C,
        D,
        E,
        F,
        G,
        H,
        I,
        J,
        K,
        L,
        M,
        N,
        O,
        P,
        Q,
        R,
        S,
        T,
        U,
        V,
        W,
        X,
        Y,
        Z,

        /// The Escape key, next to F1.
        Escape,

        F1,
        F2,
        F3,
        F4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        F11,
        F12,
        F13,
        F14,
        F15,
        F16,
        F17,
        F18,
        F19,
        F20,
        F21,
        F22,
        F23,
        F24,

        /// Print Screen/SysRq.
        Snapshot,
        /// Scroll Lock.
        Scroll,
        /// Pause/Break key, next to Scroll lock.
        Pause,

        /// `Insert`, next to Backspace.
        Insert,
        Home,
        Delete,
        End,
        PageDown,
        PageUp,

        Left,
        Up,
        Right,
        Down,

        /// The Backspace key, right over Enter.
        // TODO: rename
        Back,
        /// The Enter key.
        Return,
        /// The space bar.
        Space,

        /// The "Compose" key on Linux.
        Compose,

        Caret,

        Numlock,
        Numpad0,
        Numpad1,
        Numpad2,
        Numpad3,
        Numpad4,
        Numpad5,
        Numpad6,
        Numpad7,
        Numpad8,
        Numpad9,

        AbntC1,
        AbntC2,
        Add,
        Apostrophe,
        Apps,
        At,
        Ax,
        Backslash,
        Calculator,
        Capital,
        Colon,
        Comma,
        Convert,
        Decimal,
        Divide,
        Equals,
        Grave,
        Kana,
        Kanji,
        LAlt,
        LBracket,
        LControl,
        LShift,
        LWin,
        Mail,
        MediaSelect,
        MediaStop,
        Minus,
        Multiply,
        Mute,
        MyComputer,
        NavigateForward, // also called "Prior"
        NavigateBackward, // also called "Next"
        NextTrack,
        NoConvert,
        NumpadComma,
        NumpadEnter,
        NumpadEquals,
        OEM102,
        Period,
        PlayPause,
        Power,
        PrevTrack,
        RAlt,
        RBracket,
        RControl,
        RShift,
        RWin,
        Semicolon,
        Slash,
        Sleep,
        Stop,
        Subtract,
        Sysrq,
        Tab,
        Underline,
        Unlabeled,
        VolumeDown,
        VolumeUp,
        Wake,
        WebBack,
        WebFavorites,
        WebForward,
        WebHome,
        WebRefresh,
        WebSearch,
        WebStop,
        Yen,
        Copy,
        Paste,
        Cut,
    }

    class KeyboardInput implements WindowEvent {
        public final ButtonState state;
        public final VirtualKeyCode keyCode;
        public final ModifiersState modifiers;
        public final long windowId;

        public KeyboardInput(long windowId, ButtonState state, VirtualKeyCode keyCode, ModifiersState modifiers) {
            this.state = state;
            this.keyCode = keyCode;
            this.modifiers = modifiers;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "KeyboardInput{" +
                    "state=" + state +
                    ", keyCode=" + keyCode +
                    ", modifiers=" + modifiers +
                    '}';
        }

        @Override
        public EventType type() {
            return EventType.KEYBOARD_INPUT;
        }

        @Override
        public long getWindowId() {
            return windowId;
        }
    }

    class CharacterTyped implements WindowEvent {
        public final long windowId;
        public final String chars;

        public CharacterTyped(long windowId, String s) {
            this.chars = s;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "CharacterTyped{ " + chars + " }";
        }

        @Override
        public EventType type() {
            return EventType.CHARACTER_TYPED;
        }

        @Override
        public long getWindowId() {
            return windowId;
        }
    }

    class WindowResize implements WindowEvent {
        public final Size size;
        public final long windowId;

        protected WindowResize(long windowId, Size size) {
            this.size = size;
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "WindowResize{" +
                    ", size=" + size +
                    '}';
        }

        @Override
        public EventType type() {
            return EventType.WINDOW_RESIZE;
        }

        @Override
        public long getWindowId() {
            return windowId;
        }
    }

    class NewFrame implements WindowEvent {
        // Nullable
        public final Long frameId;
        public final long windowId;

        public NewFrame(long windowId, Long frameId) {
            this.windowId = windowId;
            this.frameId = frameId;
        }

        @Override
        public String toString() {
            return "NewFrame{" +
                    "windowId=" + windowId +
                    " frameId=" + frameId +
                    '}';
        }

        @Override
        public EventType type() { return EventType.NEW_FRAME; }

        @Override
        public long getWindowId() {
            return windowId;
        }
    }
    
    class CloseRequest implements WindowEvent {
        public final long windowId;

        public CloseRequest(long windowId) {
            this.windowId = windowId;
        }

        @Override
        public String toString() {
            return "CloseRequest{" +
                    "windowId=" + windowId +
                    '}';
        }
        
        @Override
        public EventType type() { return EventType.CLOSE_REQUEST; }

        @Override
        public long getWindowId() {
            return windowId;
        }
    }

    class MouseMotion implements UserEvent {
        public final Vector2D delta;

        public MouseMotion(Vector2D delta) {
            this.delta = delta;
        }

        @Override
        public String toString() {
            return "MouseMotion{" +
                   "delta=" + delta +
                   '}';
        }

        @Override
        public EventType type() { return EventType.MOUSE_MOTION; }
    }



}
