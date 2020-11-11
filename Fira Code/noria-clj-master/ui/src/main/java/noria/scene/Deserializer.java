package noria.scene;

import noria.scene.UserEvent.ButtonState;
import noria.scene.UserEvent.MouseButton;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class Deserializer {

    final ByteBuffer buffer;

    Deserializer(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    Point readPoint() {
        return new Point(buffer.getFloat(), buffer.getFloat());
    }

    Size readSize() {
        return new Size(buffer.getFloat(), buffer.getFloat());
    }

    UserEvent.NodeHit readNodeHit() {
        return new UserEvent.NodeHit(buffer.getLong(), readPoint());
    }

    ArrayList<UserEvent.NodeHit> readHitsArray() {
        final long count = buffer.getLong();
        final ArrayList<UserEvent.NodeHit> result = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            result.add(readNodeHit());
        }
        return result;
    }

    UserEvent.ModifiersState readModifiersState() {
        return new UserEvent.ModifiersState(
                buffer.get() != 0,
                buffer.get() != 0,
                buffer.get() != 0,
                buffer.get() != 0);
    }

    Vector2D readVector2D() {
        return new Vector2D(buffer.getFloat(), buffer.getFloat());
    }

    ButtonState readButtonState() {
        return ButtonState.values()[buffer.getInt()];
    }

    MouseButton readMouseButton() {
        MouseButton result = MouseButton.values()[buffer.getInt()];
        if (result == MouseButton.OTHER) {
            buffer.getChar(); // just skip other code
        }
        return result;
    }

    String readUTF8String() {
        long count = buffer.getLong();
        final byte[] bytes = new byte[(int)count];
        for (int i = 0; i < count; i++) {
            bytes[i] = buffer.get();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    UserEvent readUserEvent() {
        final UserEvent.EventType eventType = UserEvent.EventType.values()[buffer.getInt()];
        switch (eventType) {
            case MOUSE_WHEEL: {
                final long windowId = buffer.getLong();
                final ArrayList<UserEvent.NodeHit> hits = readHitsArray();
                return new UserEvent.MouseWheel(hits, windowId, readVector2D(), UserEvent.TouchPhase.values()[buffer.getInt()], readPoint());
            }
            case CURSOR_MOVED: {
                final long windowId = buffer.getLong();
                final ArrayList<UserEvent.NodeHit> hits = readHitsArray();
                return new UserEvent.CursorMoved(hits, windowId, readPoint());
            }
            case MOUSE_INPUT: {
                final long windowId = buffer.getLong();
                final ArrayList<UserEvent.NodeHit> hits = readHitsArray();
                return new UserEvent.MouseInput(hits, windowId, readButtonState(), readMouseButton(), readPoint());
            }
            case KEYBOARD_INPUT: {
                final long windowId = buffer.getLong();
                final ButtonState state = readButtonState();
                final UserEvent.VirtualKeyCode keyCode = UserEvent.VirtualKeyCode.values()[buffer.getInt()];
                return new UserEvent.KeyboardInput(windowId, state, keyCode, readModifiersState());
            }
            case CHARACTER_TYPED: {
                final long windowId = buffer.getLong();
                return new UserEvent.CharacterTyped(windowId, readUTF8String());
            }
            case WINDOW_RESIZE: {
                final long windowId = buffer.getLong();
                return new UserEvent.WindowResize(windowId, readSize());
            }
            case NEW_FRAME: {
                final long windowId = buffer.getLong();
                Long frameId = null;
                if (buffer.get() != 0) {
                    frameId = buffer.getLong();
                }
                return new UserEvent.NewFrame(windowId, frameId);
            }
            case CLOSE_REQUEST: {
                final long windowId = buffer.getLong();
                return new UserEvent.CloseRequest(windowId);
            }
            case MOUSE_MOTION: {
                return new UserEvent.MouseMotion(readVector2D());
            }
            default: {
                throw new RuntimeException("Not exhaustive variants matching");
            }
        }
    }

    ArrayList<UserEvent> readUserEventsArray() {
        final long eventsCount = buffer.getLong();
        final ArrayList<UserEvent> result = new ArrayList<>((int) eventsCount);
        for (int i = 0; i < eventsCount; i++) {
            result.add(readUserEvent());
        }
        return result;
    }

    static ArrayList<UserEvent> deserialize(ByteBuffer buffer) {
        return new Deserializer(buffer).readUserEventsArray();
    }
}
