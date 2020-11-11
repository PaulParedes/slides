use std::fmt::Debug;
use std::panic::{catch_unwind, UnwindSafe, PanicInfo};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, RwLock};
use std::ffi::c_void;

use bincode::config;
use clipboard::{ClipboardContext, ClipboardProvider};
use fxhash::FxHashMap;
use glutin::dpi::LogicalPosition;
use jni::{JNIEnv, NativeMethod};
use jni::objects::{JClass, JMethodID, JObject, JString, JValue};
use jni::signature::{JavaType, Primitive, TypeSignature};
use jni::sys::{jboolean, jbyteArray, jfloat, jlongArray, jint, jlong, jstring};
use webrender::webrender_api::units::LayoutPoint;
use webrender::webrender_api::FontVariation;
use thread_profiler::{profile_scope, register_thread_with_profiler};

use crate::fonts::{FontsContainer, FontInstance, Wrap};
use lazy_static::lazy_static;

use crate::scene::{NodeId, Scene};
use crate::event_loop;
use crate::scene;
use crate::images::{ImagesContainer, Image};
use std::cell::RefCell;
use glutin::MouseCursor;
use tinyfiledialogs::open_file_dialog;
use serde::{Serializer, ser::SerializeStruct};
use env_logger::try_init;
use std::convert::TryInto;

#[derive(Default)]
struct GlobalState {
    event_loop_proxy: Option<event_loop::EventLoopProxy>
}

lazy_static! {
    static ref GLOBAL_STATE_CVAR: Condvar = Condvar::new();
    static ref GLOBAL_STATE: Mutex<GlobalState> = Mutex::new(GlobalState::default());

    static ref SCENES: RwLock<FxHashMap<NodeId, Arc<Mutex<Scene>>>> = Default::default();

    static ref FONTS_CONTAINER: RwLock<FontsContainer> = Default::default();
    static ref IMAGES_CONTAINER: RwLock<ImagesContainer> = Default::default();
    static ref CLIPBOARD: Mutex<ClipboardContext> = Mutex::new(ClipboardProvider::new().unwrap());
}

thread_local! {
    pub static PANIC_MESSAGE: RefCell<Option<String>> = RefCell::new(None);
}

fn get_global_state() -> MutexGuard<'static, GlobalState> {
    let mut global_state = GLOBAL_STATE.lock().unwrap();
    loop {
        if global_state.event_loop_proxy.is_some() {
            return global_state;
        } else {
            global_state = GLOBAL_STATE_CVAR.wait(global_state).unwrap();
        }
    }
}

fn panic_hook(info: &PanicInfo<'_>) {
    let payload = match info.payload().downcast_ref::<&'static str>() {
        Some(s) => *s,
        None => match info.payload().downcast_ref::<String>() {
            Some(s) => &s[..],
            None => "",
        }
    };

    let location = info.location().unwrap();

    let thread = std::thread::current();
    let name = thread.name().unwrap_or("<unnamed>");

    let backtrace = backtrace::Backtrace::new();

    let message = format!("thread '{}' panicked at '{}', {}\n {:?}", name, payload, location, backtrace);

    PANIC_MESSAGE.with(|panic_message| {
        panic_message.replace(Some(message));
    });
}

fn with_panic_logger<F: FnOnce(&JNIEnv) -> R + UnwindSafe, R: Debug>(env: &JNIEnv, f: F) -> Option<R> {
    let result = catch_unwind(|| f(env));
    if result.is_err() {
        let message = PANIC_MESSAGE.with(|panic_message| {
            panic_message.replace(None)
        });
        env.throw_new("noria/scene/PhotonApi$PhotonException", message.unwrap_or("".to_string())).unwrap();
    }
    result.ok()
}

struct EventsBuffer<'a> {
    env: &'a JNIEnv<'a>,
    instance: JObject<'a>,
    get_byte_buffer_method_id: JMethodID<'a>,
    on_ready_method_id: JMethodID<'a>,
}

impl<'a> EventsBuffer<'a> {
    fn new(env: &'a JNIEnv<'a>, instance: JObject<'a>) -> Self {
        let class = env.get_object_class(instance).unwrap();
        let get_byte_buffer_method_id = env.get_method_id(class,
                                                          "getByteBufferAddress",
                                                          TypeSignature {
                                                              args: vec![JavaType::Primitive(Primitive::Long)],
                                                              ret: JavaType::Primitive(Primitive::Long),
                                                          }.to_string()).unwrap();
        let on_ready_method_id = env.get_method_id(class,
                                                   "onReady",
                                                   TypeSignature {
                                                       args: vec![],
                                                       ret: JavaType::Primitive(Primitive::Void),
                                                   }.to_string()).unwrap();
        EventsBuffer {
            env,
            instance,
            get_byte_buffer_method_id,
            on_ready_method_id,
        }
    }

    fn get_byte_buffer_address(&self, size: i64) -> i64 {
        if let JValue::Long(result) = self.env.call_method_unchecked(self.instance,
                                                                     self.get_byte_buffer_method_id,
                                                                     JavaType::Primitive(Primitive::Long),
                                                                     &vec![JValue::Long(size)]).unwrap() {
            result
        } else {
            unreachable!("Wrong return value type")
        }
    }

    fn on_ready(&self) {
        self.env.call_method_unchecked(self.instance,
                                       self.on_ready_method_id,
                                       JavaType::Primitive(Primitive::Void),
                                       &vec![]).unwrap();
    }
}

impl<'a> event_loop::EventsHandler for EventsBuffer<'a> {
    fn handle(&mut self, events: Vec<event_loop::UserEvent>) {
        let mut config = config();
        config.big_endian();
        let size = config.serialized_size(&events).unwrap();
        let addr = self.get_byte_buffer_address(size as i64);
        let buffer = unsafe {
            std::slice::from_raw_parts_mut(addr as *mut u8, size as usize)
        };
        config.serialize_into(buffer, &events).unwrap();
        self.on_ready();
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_runEventLoopImpl(env: JNIEnv,
                                                                   _class: JClass,
                                                                   events_buffer: JObject) {
    try_init();
    with_panic_logger(&env, |env| {
        std::panic::set_hook(Box::new(panic_hook));
        let event_loop = event_loop::PhotonEventLoop::new();
        let proxy = event_loop.create_proxy();
        {
            let state: &mut GlobalState = &mut GLOBAL_STATE.lock().unwrap();
            state.event_loop_proxy = Some(proxy);
            GLOBAL_STATE_CVAR.notify_all();
        }
        event_loop.run(EventsBuffer::new(env, events_buffer));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_createWindowImpl(env: JNIEnv,
                                                                   _class: JClass,
                                                                   node_id: jlong,
                                                                   width: jfloat,
                                                                   height: jfloat,
                                                                   title: JString,
                                                                   transparet_titlebar: jboolean) {
    with_panic_logger(&env, |_env| {
        // TODO fix double initialization when multiple windows has been driven by single noria thread
        register_thread_with_profiler(format!("Noria thread window#{:?}", node_id));
        let t: String = env.get_string(title).unwrap().into();
        let window_info = get_global_state().event_loop_proxy.as_mut().unwrap().create_window(node_id, width, height, t, transparet_titlebar != 0);
        let fonts_container = FONTS_CONTAINER.read().expect("FONTS_CONTAINER mutex is poisoned");
        let mut new_scene = Scene::new(window_info, &IMAGES_CONTAINER);
        for (font_id, font) in &fonts_container.fonts {
            new_scene.load_font(*font_id, font.font_kit_font.copy_font_data().expect("no data for font").to_vec());
        }
        SCENES.write().expect("SCENES mutex is poisoned").insert(node_id, Arc::new(Mutex::new(new_scene)));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_destroyWindow(env: JNIEnv,
                                                                _class: JClass,
                                                                node_id: jlong) {
    with_panic_logger(&env, |_env| {
        SCENES.write().expect("SCENES mutex is poisoned").remove(&node_id);
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::DestroyWindow(node_id));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_stopApplication(env: JNIEnv,
                                                                  _class: JClass) {
    with_panic_logger(&env, |_env| {
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::Stop);
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_setAnimationRunning(env: JNIEnv,
                                                                      _class: JClass,
                                                                      value: jboolean) {
    with_panic_logger(&env, |_env| {
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::SetAnimationRunning(value != 0));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_setIMEPosition(env: JNIEnv,
                                                                 _class: JClass,
                                                                 node_id: jlong,
                                                                 x: jfloat,
                                                                 y: jfloat) {
    with_panic_logger(&env, |_env| {
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::SetIMEPosition(LogicalPosition::new(x as f64, y as f64), node_id));
    });
}

fn with_scene<F: FnOnce(&mut Scene) -> R, R>(node_id: NodeId, f: F) -> R {
    let scene_mutex = SCENES.read().unwrap().get(&node_id).unwrap().clone();
    let mut scene = match scene_mutex.lock() {
        Ok(scene_state) => scene_state,
        Err(poisoned) => poisoned.into_inner()
    };
    f(&mut *scene)
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_commit(env: JNIEnv,
                                                         _class: JClass,
                                                         window_id: jlong,
                                                         frame_id: jlong,
                                                         addr: jlong,
                                                         size: jlong) -> jboolean {
    profile_scope!(Box::leak(format!("Commit of: {:?}", frame_id).into_boxed_str()));
    with_panic_logger(&env, |_env| {
        let buffer = unsafe {
            std::slice::from_raw_parts(addr as *const u8, size as usize)
        };
        let updates = config().big_endian().deserialize::<Vec<scene::Update>>(buffer).unwrap();
        log::debug!("Commit: {:?}", updates);
        with_scene(window_id, |scene_ref| {
            scene_ref.commit(frame_id.try_into().unwrap(), updates)
        })
    }).unwrap_or(false).into()
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_loadFont(env: JNIEnv,
                                                           _class: JClass,
                                                           font_id: jlong,
                                                           bytes: jbyteArray) {
    with_panic_logger(&env, |env| {
        let bytes = env.convert_byte_array(bytes).expect("failed to convert jbyteArray with font to bytes");
        FONTS_CONTAINER.write().expect("FONTS_CONTAINER mutex is poisoned").load_font(font_id, bytes.clone());
        for (_scene_id, scene_mutex) in &*SCENES.read().expect("SCENES mutex is poisoned") {
            let mut scene = match scene_mutex.lock() {
                Ok(scene_state) => scene_state,
                Err(poisoned) => poisoned.into_inner()
            };
            scene.load_font(font_id, bytes.clone());
        }
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_loadImage(env: JNIEnv,
                                                            _class: JClass,
                                                            image_id: jlong,
                                                            bytes: jbyteArray) {
    with_panic_logger(&env, |env| {
        let bytes = env.convert_byte_array(bytes).expect("failed to convert jbyteArray with font to bytes");
        let image = Image::from_bytes(&bytes).unwrap();
        IMAGES_CONTAINER.write().expect("IMAGES_CONTAINER is poisoned").images.insert(image_id, image);
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_destroyImage(env: JNIEnv,
                                                               _class: JClass,
                                                               image_id: jlong) {
    with_panic_logger(&env, |_env| {
        IMAGES_CONTAINER.write().expect("IMAGES_CONTAINER is poisoned").images.remove(&image_id);
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_layoutTextImpl(env: JNIEnv,
                                                                 _class: JClass,
                                                                 font_id: jlong,
                                                                 font_size: jint,
                                                                 variation_tag: jlong,
                                                                 variation_value: jfloat,
                                                                 width: jfloat,
                                                                 text: JString,
                                                                 wrap: jint,
                                                                 result: jlongArray) {
    with_panic_logger(&env, |env| {
        let text: String = env.get_string(text).unwrap().into();
        let wrap = match wrap {
            0 => Wrap::Words,
            1 => Wrap::Lines,
            _ => unreachable!()
        };
        let variation = if variation_tag > 0 {
            Some(FontVariation { tag: variation_tag as u32, value: variation_value })
        } else {
            None
        };
        let layouted_text = FONTS_CONTAINER.read().unwrap().layout_text(FontInstance { id: font_id, size: font_size, variation }, LayoutPoint::zero(), text.as_str(), width, wrap);
        let serialized_layouted_text = config().serialize(&layouted_text).unwrap().into_boxed_slice();
        let buffer_len = serialized_layouted_text.len();
        let buffer_addr = Box::into_raw(serialized_layouted_text) as *const u8;
        env.set_long_array_region(result, 0, &[buffer_addr as i64, buffer_len as i64]).unwrap();
    });
}

struct MetricsWrapper {
    metrics: font_kit::metrics::Metrics
}

impl serde::Serialize for MetricsWrapper {
    fn serialize<S>(&self, serializer: S) -> Result<<S as Serializer>::Ok, <S as Serializer>::Error> where S: Serializer {
        let font_kit::metrics::Metrics { units_per_em, ascent, descent, line_gap, underline_position, underline_thickness, cap_height, x_height } = self.metrics;
        let mut s = serializer.serialize_struct("Metrics", 8)?;
        s.serialize_field("units_per_em", &units_per_em)?;
        s.serialize_field("ascent", &ascent)?;
        s.serialize_field("descent", &descent)?;
        s.serialize_field("line_gap", &line_gap)?;
        s.serialize_field("underline_position", &underline_position)?;
        s.serialize_field("underline_thickness", &underline_thickness)?;
        s.serialize_field("cap_heigth", &cap_height)?;
        s.serialize_field("x_height", &x_height)?;
        s.end()
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_fontMetricsImpl(env: JNIEnv,
                                                                  _class: JClass,
                                                                  font_id: jlong,
                                                                  result: jlongArray) {
    with_panic_logger(&env, |env| {
        let font_data = FONTS_CONTAINER.read().expect("FONTS_CONTAINER mutex is poisoned").get(font_id).expect("no such font");
        let metrics = font_data.font_kit_font.metrics();
        let serialized_metrics = config().serialize(&MetricsWrapper { metrics }).expect("failed to serialize font metrics").into_boxed_slice();
        let buffer_len = serialized_metrics.len();
        let buffer_addr = Box::into_raw(serialized_metrics) as *const u8;
        env.set_long_array_region(result, 0, &[buffer_addr as i64, buffer_len as i64]).unwrap();
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_shapeTextImpl(env: JNIEnv,
                                                                _class: JClass,
                                                                font_id: jlong,
                                                                variation_tag: jlong,
                                                                variation_value: jfloat,
                                                                text: JString,
                                                                result: jlongArray) {
    with_panic_logger(&env, |env| {
        let text: String = env.get_string(text).expect("failed jstring->String conversion").into();
        let variation = if variation_tag > 0 {
            Some(FontVariation { tag: variation_tag as u32, value: variation_value })
        } else {
            None
        };
        let font_data = FONTS_CONTAINER.read().expect("FONTS_CONTAINER mutex is poisoned").get(font_id).expect("no such font");
        let hb_font = crate::fonts::harfbuzz2::hb_font(&font_data.hb_face2, variation);
        let text_shape = crate::fonts::harfbuzz2::shape_run(&hb_font, &text);
        let serialized_shape = config().serialize(&text_shape).expect("failed to serialize text shape").into_boxed_slice();
        let buffer_len = serialized_shape.len();
        let buffer_addr = Box::into_raw(serialized_shape) as *const u8;
        env.set_long_array_region(result, 0, &[buffer_addr as i64, buffer_len as i64]).expect("set_long_array_region");
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_releaseBuffer(env: JNIEnv,
                                                                _class: JClass,
                                                                buffer_addr: jlong,
                                                                buffer_len: jlong) {
    with_panic_logger(&env, |_env| {
        unsafe {
            let ptr: *mut [u8] = std::mem::transmute((buffer_addr, buffer_len));
            Box::from_raw(ptr);
        };
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_releaseTextLayoutImpl(env: JNIEnv,
                                                                        _class: JClass,
                                                                        buffer_addr: jlong,
                                                                        buffer_len: jlong) {
    with_panic_logger(&env, |_env| {
        unsafe {
            let ptr: *mut [u8] = std::mem::transmute((buffer_addr, buffer_len));
            Box::from_raw(ptr);
        };
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_getClipboardContent(env: JNIEnv,
                                                                      _class: JClass) -> jstring {
    with_panic_logger(&env, |env| {
        let mut clipboard = CLIPBOARD.lock().unwrap();
        env.new_string(clipboard.get_contents().unwrap()).unwrap().into_inner()
    }).unwrap_or(JObject::null().into_inner())
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_setClipboardContent(env: JNIEnv,
                                                                      _class: JClass, content: JString) {
    with_panic_logger(&env, |env| {
        let mut clipboard = CLIPBOARD.lock().unwrap();
        clipboard.set_contents(env.get_string(content).unwrap().into()).unwrap();
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_setCursorImpl(env: JNIEnv,
                                                                _class: JClass,
                                                                node_id: jlong,
                                                                mouse_cursor: jlong) {
    with_panic_logger(&env, |_env| {
        let mouse_cursor = match mouse_cursor {
            0 => MouseCursor::Default,
            1 => MouseCursor::Crosshair,
            2 => MouseCursor::Hand,
            3 => MouseCursor::Arrow,
            4 => MouseCursor::Move,
            5 => MouseCursor::Text,
            6 => MouseCursor::Wait,
            7 => MouseCursor::Help,
            8 => MouseCursor::Progress,
            9 => MouseCursor::NotAllowed,
            10 => MouseCursor::ContextMenu,
            11 => MouseCursor::Cell,
            12 => MouseCursor::VerticalText,
            13 => MouseCursor::Alias,
            14 => MouseCursor::Copy,
            15 => MouseCursor::NoDrop,
            16 => MouseCursor::Grab,
            17 => MouseCursor::Grabbing,
            18 => MouseCursor::AllScroll,
            19 => MouseCursor::ZoomIn,
            20 => MouseCursor::ZoomOut,
            _ => MouseCursor::Default
        };
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::SetCursor(mouse_cursor, node_id));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_setDebugProfilerShowing(env: JNIEnv,
                                                                          _class: JClass,
                                                                          node_id: jlong,
                                                                          state: bool) {
    with_panic_logger(&env, |_env| {
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::SetDebugProfilerShowing(node_id, state));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_dropCpuProfile(env: JNIEnv,
                                                                 _class: JClass,
                                                                 filename: JString) {
    with_panic_logger(&env, |env| {
        get_global_state().event_loop_proxy.as_mut().unwrap().send(event_loop::Msg::DropCpuProfile(env.get_string(filename).unwrap().into()));
    });
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_noria_scene_PhotonApi_openFileDialogImpl(env: JNIEnv,
                                                                     _class: JClass,
                                                                     name: JString,
                                                                     default_path: JString) -> jstring {
    with_panic_logger(&env, |env| {
        if let Some(path) = open_file_dialog(env.get_string(name).unwrap().to_str().unwrap(),
                                             env.get_string(default_path).unwrap().to_str().unwrap(),
                                             None)
        {
            env.new_string(path).unwrap().into_inner()
        } else {
            env.new_string("").unwrap().into_inner()
        }
    }).unwrap_or(JObject::null().into_inner())
}


pub fn bind_photon_methods(env: &JNIEnv) {
    env.register_native_methods("noria/scene/PhotonApi",
                                vec![
                                    NativeMethod {
                                        name: "runEventLoopImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Object("noria/scene/PhotonApi$EventsBuffer".to_string())],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_runEventLoopImpl as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "createWindowImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Float),
                                                       JavaType::Primitive(Primitive::Float),
                                                       JavaType::Object("java/lang/String".to_string()),
                                                       JavaType::Primitive(Primitive::Boolean)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_createWindowImpl as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "destroyWindow".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_destroyWindow as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "stopApplication".into(),
                                        sig: TypeSignature {
                                            args: vec![],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_stopApplication as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "setAnimationRunning".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Boolean)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_setAnimationRunning as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "setIMEPosition".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Float),
                                                       JavaType::Primitive(Primitive::Float)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_setIMEPosition as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "commit".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Long)],
                                            ret: JavaType::Primitive(Primitive::Boolean),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_commit as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "loadFont".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Array(Box::new(JavaType::Primitive(Primitive::Byte)))],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_loadFont as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "loadImage".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Array(Box::new(JavaType::Primitive(Primitive::Byte)))],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_loadImage as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "layoutTextImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Int),
                                                       JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Float),
                                                       JavaType::Primitive(Primitive::Float),
                                                       JavaType::Object("java/lang/String".to_string()),
                                                       JavaType::Primitive(Primitive::Int),
                                                       JavaType::Array(Box::new(JavaType::Primitive(Primitive::Long)))],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_layoutTextImpl as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "releaseTextLayoutImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Long)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_releaseTextLayoutImpl as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "getClipboardContent".into(),
                                        sig: TypeSignature {
                                            args: vec![],
                                            ret: JavaType::Object("java/lang/String".to_string()),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_getClipboardContent as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "setClipboardContent".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Object("java/lang/String".to_string())],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_setClipboardContent as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "setCursorImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Long)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_setCursorImpl as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "setDebugProfilerShowing".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Primitive(Primitive::Long),
                                                       JavaType::Primitive(Primitive::Boolean)],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_setDebugProfilerShowing as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "dropCpuProfile".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Object("java/lang/String".to_string())],
                                            ret: JavaType::Primitive(Primitive::Void),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_dropCpuProfile as *mut c_void,
                                    },
                                    NativeMethod {
                                        name: "openFileDialogImpl".into(),
                                        sig: TypeSignature {
                                            args: vec![JavaType::Object("java/lang/String".to_string()),
                                                       JavaType::Object("java/lang/String".to_string())],
                                            ret: JavaType::Object("java/lang/String".to_string()),
                                        }.to_string().into(),
                                        fn_ptr: Java_noria_scene_PhotonApi_openFileDialogImpl as *mut c_void,
                                    }
                                ].as_slice()).unwrap();
}