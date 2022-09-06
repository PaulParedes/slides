use crossbeam::crossbeam_channel::{Receiver, Sender};
use fxhash::{FxHashMap, FxHashSet};
use gleam::gl;
use glutin::{ControlFlow, ElementState, Event, EventsLoop, GlContext, GlWindow, KeyboardInput, ModifiersState, MouseButton, MouseCursor, MouseScrollDelta, TouchPhase, VirtualKeyCode, Window, WindowAttributes, WindowBuilder, WindowEvent, WindowId, DeviceEvent};
use glutin::dpi::{LogicalPosition, LogicalSize};
use serde::Serialize;
use thread_profiler::{profile_scope, write_profile};
use webrender::api::*;
use webrender::api::units::*;
use webrender::Renderer;

use crate::scene::NodeId;
use crate::diagnostic::{DiagnosticReceiver, create_diagnostic_chanels, DiagnosticSender, FrameId, receive_frame_id};

struct PhotonWindow {
    window: GlWindow,
    // None only after Drop
    renderer: Option<Renderer>,
    sender: RenderApiSender,
    document_id: DocumentId,
    pipeline_id: PipelineId,
    size: glutin::dpi::LogicalSize,
    dpi_factor: f64,
    render_api: RenderApi,
    opts: PhotonWindowOptions,
    diagnostic_receiver: DiagnosticReceiver
}

impl Drop for PhotonWindow {
    fn drop(&mut self) {
        let renderer = self.renderer.take().unwrap();
        renderer.deinit();
    }
}

struct PhotonWindowOptions {
    macos_transparent_titlebar: bool,
    macos_buttons_x: Option<f64>,
    macos_buttons_y: Option<f64>,
}

impl Default for PhotonWindowOptions {
    fn default() -> Self {
        PhotonWindowOptions {
            macos_transparent_titlebar: false,
            macos_buttons_x: None,
            macos_buttons_y: None,
        }
    }
}


fn framebuffer_size(window: &Window) -> DeviceIntSize {
    let dpi_factor = window.get_hidpi_factor();
    let (width, height): (u32, u32) = window.get_inner_size().unwrap().to_physical(dpi_factor).into();
    DeviceIntSize::new(width as i32, height as i32)
}

fn adjust_window(window: &GlWindow, opts: &PhotonWindowOptions) {
    #[cfg(target_os = "macos")]
    {
        use cocoa::appkit::NSWindowButton::{NSWindowCloseButton, NSWindowMiniaturizeButton, NSWindowToolbarButton, NSWindowZoomButton};
        use cocoa::base::{id, nil};
        use cocoa::foundation::{NSPoint, NSRect, NSSize};

        if opts.macos_transparent_titlebar {
            unsafe {
                use objc::*;
                use glutin::os::macos::WindowExt;
                use cocoa::appkit::*;
                let nswindow = window.get_nswindow() as *mut objc::runtime::Object;
                NSWindow::setTitleVisibility_(nswindow, NSWindowTitleVisibility::NSWindowTitleHidden);
                NSWindow::setTitlebarAppearsTransparent_(nswindow, 1);
                let mask = NSWindow::styleMask(nswindow);
                NSWindow::setStyleMask_(nswindow, mask | NSWindowStyleMask::NSFullSizeContentViewWindowMask);

                if opts.macos_buttons_x.is_some() && opts.macos_buttons_y.is_some() {
                    let tl_offset_x = opts.macos_buttons_x.unwrap();
                    let tl_offset_y = opts.macos_buttons_y.unwrap();

                    // https://github.com/electron/electron/pull/21781/files
                    let close = NSWindow::standardWindowButton_(nswindow, NSWindowCloseButton);
                    let miniaturize = NSWindow::standardWindowButton_(nswindow, NSWindowMiniaturizeButton);
                    let zoom = NSWindow::standardWindowButton_(nswindow, NSWindowZoomButton);

                    let titlebar_container_view = NSView::superview(NSView::superview(close));

                    // todo hide buttons when exiting fullscreen so they don't jump
                    let _: () = msg_send![titlebar_container_view, setHidden:false];
                    let btn_height = NSView::frame(close).size.height;
                    let titlebar_frame_height = btn_height + tl_offset_y;
                    let titlebar_frame = NSView::frame(titlebar_container_view);

                    let frame = NSRect {
                        origin: NSPoint {
                            x: titlebar_frame.origin.x,
                            y: NSView::frame(nswindow).size.height - titlebar_frame_height,
                        },
                        size: NSSize {
                            width: titlebar_frame.size.width,
                            height: titlebar_frame_height,
                        }
                    };
                    let _: () = msg_send![titlebar_container_view, setFrame:frame];

                    let btns = vec![close, miniaturize, zoom];
                    let space_between = NSView::frame(miniaturize).origin.x - NSView::frame(close).origin.x;
                    for i in 0..btns.len() {
                        let btn = btns[i];
                        let btn_frame = NSView::frame(btn);

                        let origin = NSPoint {
                            x: tl_offset_x + (i as f64 * space_between),
                            y: btn_frame.origin.y,
                        };
                        let _: () = msg_send![btn, setFrameOrigin:origin];
                    }
                }
            }
        }
    }
}

fn create_window(event_loop: &EventsLoop,
                 proxy: EventLoopProxy,
                 width: f32,
                 height: f32,
                 title: String,
                 transparent_titlebar: bool) -> (PhotonWindow, WindowInfo) {

    let window_builder = WindowBuilder::new()
        .with_title(title)
        .with_resizable(true)
        .with_dimensions(LogicalSize::new(width as f64, height as f64));

    let context_builder = glutin::ContextBuilder::new()
        .with_vsync(true)
        .with_gl(glutin::GlRequest::GlThenGles {
            opengl_version: (3, 2),
            opengles_version: (3, 0),
        });

    let window = GlWindow::new(window_builder, context_builder, &event_loop)
        .expect("Failed to create window.");

    let dpi_factor = window.get_hidpi_factor();
    let opts = webrender::RendererOptions {
        clear_color: Some(ColorF::new(1.0, 1.0, 1.0, 1.0)),
        device_pixel_ratio: dpi_factor as f32,
        ..webrender::RendererOptions::default()
    };

    unsafe {
        window.context().make_current().unwrap();
    }

    let gl = match window.get_api() {
        glutin::Api::OpenGl => unsafe {
            gl::GlFns::load_with(|symbol| window.get_proc_address(symbol) as *const _)
        },
        glutin::Api::OpenGlEs => unsafe {
            gl::GlesFns::load_with(|symbol| window.get_proc_address(symbol) as *const _)
        },
        glutin::Api::WebGl => unimplemented!(),
    };
    let framebuffer_size = framebuffer_size(&window);
    let notifier = WindowNotifier {
        proxy,
        window_id: window.id()
    };
    let (renderer, sender) = webrender::Renderer::new(gl, Box::new(notifier), opts, None, framebuffer_size).unwrap();
    let render_api = sender.create_api();
    let size = window.get_inner_size().unwrap();
    let document_id = render_api.add_document(framebuffer_size, 0);
    let pipeline_id = webrender::api::PipelineId(0, 0);
    let mut txn = Transaction::new();
    txn.set_root_pipeline(pipeline_id);
    txn.generate_frame();
    render_api.send_transaction(document_id, txn);

    let opts;
    #[cfg(target_os = "macos")]
    {
        opts = if transparent_titlebar {
            PhotonWindowOptions {
                macos_transparent_titlebar: true,
                macos_buttons_x: Some(11f64),
                macos_buttons_y: Some(11f64),
            }
        } else {
            PhotonWindowOptions::default()
        }
    }

    #[cfg(not(target_os = "macos"))]
    {
        opts = PhotonWindowOptions::default();
    }

    let (diagnostic_sender, diagnostic_receiver) = create_diagnostic_chanels();
    let window = PhotonWindow {
        window,
        renderer: Some(renderer),
        render_api,
        sender,
        document_id,
        pipeline_id,
        size,
        dpi_factor,
        opts,
        diagnostic_receiver
    };
    let window_info = WindowInfo { render_api: window.sender.create_api(),
        size: window.size,
        document_id: window.document_id,
        pipeline_id: window.pipeline_id,
        diagnostic_sender
    };
    (window, window_info)
}

pub struct WindowInfo {
    pub render_api: RenderApi,
    pub size: glutin::dpi::LogicalSize,
    pub document_id: DocumentId,
    pub pipeline_id: PipelineId,
    pub diagnostic_sender: DiagnosticSender
}

#[derive(Debug)]
pub enum Msg {
    Repaint(WindowId),
    CreateWindow { sender: Sender<WindowInfo>, node_id: NodeId, width: f32, height: f32, title: String, transparent_titlebar: bool },
    DestroyWindow(NodeId),
    SetAnimationRunning(bool),
    SetIMEPosition(LogicalPosition, NodeId),
    SetCursor(MouseCursor, NodeId),
    Stop,
    SetDebugProfilerShowing(NodeId, bool),
    DropCpuProfile(String),
}

#[derive(Copy, Clone, Serialize, Debug)]
pub struct NodeHit {
    callback_id: NodeId,
    intersection_point: LayoutPoint
}

#[derive(Serialize, Debug)]
pub enum UserEvent {
    MouseWheel {
        window_id: NodeId,
        hits: Vec<NodeHit>,
        delta: LayoutVector2D,
        touch_phase: TouchPhase,
        cursor_position: WorldPoint,
    },
    CursorMoved {
        window_id: NodeId,
        hits: Vec<NodeHit>,
        cursor_position: WorldPoint,
    },
    MouseInput {
        window_id: NodeId,
        hits: Vec<NodeHit>,
        state: ElementState,
        button: MouseButton,
        cursor_position: WorldPoint,
    },
    KeyboardInput {
        window_id: NodeId,
        state: ElementState,
        key_code: VirtualKeyCode,
        modifiers: ModifiersState,
    },
    CharacterTyped {
        window_id: NodeId,
        char: String,
    },
    WindowResize {
        window_id: NodeId,
        size: LayoutSize,
    },
    NewFrame {
        window_id: NodeId,
        frame_id: Option<FrameId>
    },
    CloseRequest {
        window_id: NodeId
    },
    MouseMotion {
        delta: WorldVector2D
    },
}

#[derive(Clone)]
pub struct EventLoopProxy {
    events_proxy: glutin::EventsLoopProxy,
    sender: Sender<Msg>,
}

impl EventLoopProxy {
    pub fn send(&self, event: Msg) {
        self.sender.send(event).unwrap();
        self.events_proxy.wakeup().unwrap();
    }

    pub fn create_window(&self, node_id: NodeId, width: f32, height: f32, title: String, transparent_titlebar: bool) -> WindowInfo {
        let (sender, receiver) = crossbeam::crossbeam_channel::bounded(1);
        self.send(Msg::CreateWindow { sender, node_id, width, height, title, transparent_titlebar });
        receiver.recv().unwrap()
    }
}

type EventLoopReceiver = Receiver<Msg>;

fn create_event_loop_proxy(event_loop: &EventsLoop) -> (EventLoopProxy, EventLoopReceiver) {
    let (msg_sender, msg_receiver) = crossbeam::crossbeam_channel::unbounded();
    let proxy = EventLoopProxy { events_proxy: event_loop.create_proxy(), sender: msg_sender };
    (proxy, msg_receiver)
}

pub struct PhotonEventLoop {
    events_loop: EventsLoop,
    proxy: EventLoopProxy,
    receiver: EventLoopReceiver,
}

fn set_debug_profiler(photon_window: &PhotonWindow, state: bool) {
    let api = &photon_window.render_api;
    let renderer = &photon_window.renderer.as_ref().unwrap();
    let mut debug_flags = renderer.get_debug_flags();
    debug_flags.set(DebugFlags::PROFILER_DBG, state);
    debug_flags.set(DebugFlags::GPU_TIME_QUERIES, state);
//    debug_flags.set(DebugFlags::RENDER_TARGET_DBG, state);
    api.send_debug_cmd(DebugCommand::SetFlags(debug_flags));
}

pub trait EventsHandler {
    fn handle(&mut self, events: Vec<UserEvent>);
}

fn unpack_delta(delta: &MouseScrollDelta) -> LayoutVector2D {
    const X_SCROLL_STEP: f32 = 38.0;
    const Y_SCROLL_STEP: f32 = 38.0;
    match delta {
        MouseScrollDelta::LineDelta(dx, dy) => {
            LayoutVector2D::new(dx * X_SCROLL_STEP, dy * Y_SCROLL_STEP)
        }

        MouseScrollDelta::PixelDelta(glutin::dpi::LogicalPosition { x: dx, y: dy }) => {
            LayoutVector2D::new(*dx as f32, *dy as f32)
        }
    }
}

fn handle_event_synchronously<E: EventsHandler>(e: &Event,
                                                handler: &mut E,
                                                windows: &mut FxHashMap<WindowId, PhotonWindow>,
                                                window_id_to_node_id: &FxHashMap<WindowId, NodeId>) -> bool {
    if let Event::WindowEvent { event: WindowEvent::Resized(new_size), window_id } = e {
        if let Some(PhotonWindow { render_api, window, dpi_factor, renderer, document_id, opts, diagnostic_receiver, .. }) = windows.get_mut(&window_id) {
            let renderer = renderer.as_mut().unwrap();
            window.resize(new_size.to_physical(*dpi_factor));
            adjust_window(window, opts);


            let framebuffer_size = framebuffer_size(window);
            let device_rect = DeviceIntRect { origin: DeviceIntPoint::zero(), size: framebuffer_size };
            render_api.set_document_view(*document_id, device_rect, *dpi_factor as f32);

            handler.handle(vec![
                UserEvent::WindowResize {
                    size: LayoutSize::new(new_size.width as f32, new_size.height as f32),
                    window_id: window_id_to_node_id[&window_id]
                }
            ]);

            // TODO wait before next frame synchronously
            unsafe {
                window.context().make_current().unwrap();
            }
            renderer.update();
            renderer.render(framebuffer_size).unwrap();
            let frame_id = receive_frame_id(&diagnostic_receiver);
            renderer.flush_pipeline_info();
            window.swap_buffers().unwrap();

            handler.handle(vec![
                UserEvent::NewFrame { window_id: window_id_to_node_id[&window_id], frame_id  }
            ]);
        } else {
            log::warn!("Drop event with unknown window id {:?}", e);
        }
        return true;
    } else {
        return false
    }
}

impl PhotonEventLoop {
    pub fn new() -> Self {
        let events_loop = EventsLoop::new();
        let (proxy, receiver) = create_event_loop_proxy(&events_loop);
        PhotonEventLoop {
            events_loop,
            proxy,
            receiver,
        }
    }

    pub fn create_proxy(&self) -> EventLoopProxy {
        Clone::clone(&self.proxy)
    }

    pub fn run<E: EventsHandler>(mut self, mut handler: E) {
        let mut cursor_positions: FxHashMap<WindowId, WorldPoint> = Default::default();
        let receiver = &self.receiver;
        let proxy = self.proxy;
        let events_loop = &mut self.events_loop;

        let mut animation_running = false;
        let mut windows: FxHashMap<WindowId, PhotonWindow> = Default::default();
        let mut node_id_to_window_id: FxHashMap<NodeId, WindowId> = Default::default();
        let mut window_id_to_node_id: FxHashMap<WindowId, NodeId> = Default::default();

        'main_loop: loop {
            profile_scope!("Event loop iteration");
            let mut should_repaint: FxHashSet<WindowId> = Default::default();
            let mut events = Vec::<Event>::with_capacity(1);

            {
                profile_scope!("Waiting for events");
                if !animation_running {
                    events_loop.run_forever(|e| {
                        if handle_event_synchronously(&e, &mut handler, &mut windows, &window_id_to_node_id) {
                            return ControlFlow::Continue;
                        } else {
                            events.push(e);
                            return ControlFlow::Break;
                        }
                    });
                }
            }

            while let Ok(msg) = receiver.try_recv() {
                profile_scope!("Rcv msg");
                match msg {
                    Msg::Repaint(window_id) => {
                        profile_scope!("Rcv repaint");
                        should_repaint.insert(window_id);
                    },
                    Msg::CreateWindow { sender, node_id, width, height, title, transparent_titlebar } => {
                        let (window, window_info) = create_window(&events_loop, proxy.clone(), width, height, title.to_string(), transparent_titlebar);
                        sender.send(window_info).unwrap();
                        node_id_to_window_id.insert(node_id, window.window.id());
                        window_id_to_node_id.insert(window.window.id(), node_id);
                        windows.insert(window.window.id(), window);
                    },
                    Msg::DestroyWindow(node_id) => {
                        let window_id = node_id_to_window_id.remove(&node_id).expect(format!("No such node_id {:?}", node_id).as_str());
                        windows.remove(&window_id).expect(format!("No such window_id {:?}", window_id).as_str());
                        window_id_to_node_id.remove(&window_id).expect(format!("No such window_id {:?}", window_id).as_str());
                    },
                    Msg::SetAnimationRunning(value) => {
                        animation_running = value;
                    },
                    Msg::SetIMEPosition(position, node_id) => {
                        let photon_window = windows.get(&node_id_to_window_id[&node_id]).unwrap();
                        photon_window.window.set_ime_spot(position);
//                        #[cfg(target_os = "macos")]
//                            {
//                                unsafe {
//                                    use objc::*;
//                                    use glutin::os::macos::WindowExt;
//                                    let app: *mut objc::runtime::Object = msg_send![class!(NSApplication), sharedApplication];
//                                    let view = windows.get(window_id).unwrap().window.get_nsview();
//                                    msg_send![app, orderFrontCharacterPalette:view]
//                                }
//                            }
                    },
                    Msg::SetCursor(cursor, node_id) => {
                        let photon_window = windows.get(&node_id_to_window_id[&node_id]).unwrap();
                        photon_window.window.set_cursor(cursor);
                    },
                    Msg::Stop => {
                        break 'main_loop;
                    },
                    Msg::SetDebugProfilerShowing(node_id, state) => {
                        set_debug_profiler(windows.get(&node_id_to_window_id[&node_id]).unwrap(), state)
                    }
                    Msg::DropCpuProfile(filename) => {
                        write_profile(filename.as_str());
                    }
                }
            }

            let mut user_events: Vec<UserEvent> = Vec::new();

            if !animation_running {
                for window_id in should_repaint {
                    if let Some(w) = windows.get_mut(&window_id) {
                        unsafe {
                            w.window.context().make_current().unwrap();
                        }
                        let renderer = &mut w.renderer.as_mut().unwrap();
                        {
                            profile_scope!("Render");
                            renderer.update();
                            renderer.render(framebuffer_size(&w.window)).unwrap();
                            renderer.flush_pipeline_info();
                        }
                        let frame_id = receive_frame_id(&w.diagnostic_receiver);
                        {
                            profile_scope!(Box::leak(format!("Swap buffers after {:?}", frame_id).into_boxed_str()));
                            w.window.swap_buffers().unwrap();
                        }

                        user_events.push(UserEvent::NewFrame { window_id: window_id_to_node_id[&window_id], frame_id })
                    }
                }
            } else {
                for (window_id, w) in &mut windows {
                    unsafe {
                        w.window.context().make_current().unwrap();
                    }
                    let renderer = &mut w.renderer.as_mut().unwrap();
                    renderer.update();
                    renderer.render(framebuffer_size(&w.window)).unwrap();
                    let frame_id = receive_frame_id(&w.diagnostic_receiver);
                    renderer.flush_pipeline_info();
                    w.window.swap_buffers().unwrap();

                    user_events.push(UserEvent::NewFrame { window_id: window_id_to_node_id[&window_id], frame_id })
                }
            }

            // pool events that we get during vsync
            events_loop.poll_events(|e| {
                if !handle_event_synchronously(&e, &mut handler, &mut windows, &window_id_to_node_id) {
                    events.push(e);
                }
            });


            let mut hit_items_per_window: FxHashMap<NodeId, Vec<HitTestItem>> = Default::default();
            user_events.extend(
                events.drain(0..).filter_map(|e| {
                    match e {
                        Event::WindowEvent { event, window_id } => {
                            if let Some(window_node_id) = window_id_to_node_id.get(&window_id) {
                                if !hit_items_per_window.contains_key(&window_node_id) {
                                    let photon_window = &windows[&window_id];
                                    let pos = match event {
                                        WindowEvent::CursorMoved { position: p, .. } => {
                                            WorldPoint::new(p.x as f32, p.y as f32)
                                        },
                                        _ => *cursor_positions.entry(window_id).or_default()
                                    };
                                    let hit_items = photon_window.render_api.hit_test(photon_window.document_id, None, pos, HitTestFlags::FIND_ALL).items;
                                    hit_items_per_window.insert(*window_node_id, hit_items);
                                };

                                // todo revisit this
                                let id = match event {
                                        WindowEvent::MouseWheel { .. }  => 0,
                                        WindowEvent::CursorMoved { .. } => 1,
                                        WindowEvent::MouseInput { .. }  => 2,
                                        _ => -1
                                };

                                // todo log hit tests
                                let mut hits = Vec::new();
                                for hit_item in hit_items_per_window.get(&window_node_id).unwrap() {
                                    let mask = hit_item.tag.1;
                                    if mask & (1 << id) != 0 {
                                        hits.push(NodeHit {
                                            callback_id: hit_item.tag.0 as i64,
                                            intersection_point: hit_item.point_relative_to_item
                                        });
                                    }
                                };

                                match event {
                                    WindowEvent::MouseWheel { delta, phase, .. } => {
                                        Some(UserEvent::MouseWheel {
                                            hits,
                                            delta: unpack_delta(&delta),
                                            touch_phase: phase,
                                            window_id: *window_node_id,
                                            cursor_position: *cursor_positions.entry(window_id).or_default(),
                                        })
                                    }
                                    WindowEvent::MouseInput { state, button, .. } => {
                                        Some(UserEvent::MouseInput {
                                            state,
                                            button,
                                            hits,
                                            window_id: *window_node_id,
                                            cursor_position: *cursor_positions.entry(window_id).or_default(),
                                        })
                                    }
                                    WindowEvent::CursorMoved { position: glutin::dpi::LogicalPosition { x, y }, .. } => {
                                        // todo: we need to watch for cursor movement even when window is now focused
                                        // if we don't update cursor_position, scroll events on unfocused window are
                                        // targeted to wrong hit-items
                                        let cursor_position = cursor_positions.entry(window_id).or_default();
                                        cursor_position.x = x as f32;
                                        cursor_position.y = y as f32;
                                        Some(UserEvent::CursorMoved {
                                            cursor_position: *cursor_position,
                                            hits,
                                            window_id: *window_node_id,
                                        })
                                    }
                                    WindowEvent::KeyboardInput { input: KeyboardInput { state, virtual_keycode, modifiers, .. }, .. } => {
                                        if let Some(key_code) = virtual_keycode {
                                            Some(UserEvent::KeyboardInput {
                                                state,
                                                key_code,
                                                modifiers,
                                                window_id: *window_node_id,
                                            })
                                        } else {
                                            None
                                        }
                                    }
                                    WindowEvent::ReceivedCharacter(code_point) => {
                                        let mut s = String::new();
                                        s.push(code_point);
                                        Some(UserEvent::CharacterTyped {
                                            char: s,
                                            window_id: *window_node_id,
                                        })
                                    }
                                    WindowEvent::CloseRequested => Some(UserEvent::CloseRequest { window_id: *window_node_id }),
                                    WindowEvent::Refresh => {
                                        log::info!("Refresh event for {:?}", window_id);
                                        let window = windows.get(&window_id).unwrap();
                                        adjust_window(&window.window, &window.opts);
                                        proxy.send(Msg::Repaint(window_id));
                                        None
                                    },
                                    WindowEvent::HiDpiFactorChanged(new_dpi_factor) => {
                                        log::info!("New hdpi factor {:?} set for {:?}", new_dpi_factor, window_id);
                                        let window = windows.get(&window_id).unwrap();
                                        let framebuffer_size = framebuffer_size(&window.window);
                                        let device_rect = DeviceIntRect { origin: DeviceIntPoint::zero(), size: framebuffer_size };
                                        window.render_api.set_document_view(window.document_id, device_rect, new_dpi_factor as f32);
                                        proxy.send(Msg::Repaint(window_id));
                                        None
                                    }
                                    _ => None
                                }
                            } else {
                                None
                            }
                        }
                        Event::DeviceEvent { event, .. } => {
                            match event {
                                DeviceEvent::MouseMotion { delta } => {
                                    // todo adjust cursor position here?
                                    let (dx, dy) = delta;
                                    Some(UserEvent::MouseMotion { delta: WorldVector2D::new(dx as f32, dy as f32)})
                                }
                                _ => None
                            }
                        }
                        _ => {
                            None
                        }
                    }
                })
            );

            if !user_events.is_empty() {
                profile_scope!("Run java callbacks");
                handler.handle(user_events);
            }
        };
    }
}

struct WindowNotifier {
    proxy: EventLoopProxy,
    window_id: WindowId,
}

impl webrender::api::RenderNotifier for WindowNotifier {
    fn clone(&self) -> Box<dyn webrender::api::RenderNotifier> {
        Box::new(WindowNotifier {
            proxy: self.proxy.clone(),
            window_id: self.window_id
        })
    }

    fn wake_up(&self) {
        log::debug!("Wake up");
        self.proxy.send(Msg::Repaint(self.window_id))
    }

    fn new_frame_ready(&self, document_id: DocumentId, scrolled: bool, composite_needed: bool, render_time_ns: Option<u64>) {
        profile_scope!("frame-ready");
        log::debug!("Frame ready \
                     document-id: {:?} \
                     scrolled: {:?} \
                     composite_needed: {:?} \
                     render_time: {:?}μs", document_id, scrolled, composite_needed, render_time_ns.map(|t_ns| std::time::Duration::from_nanos(t_ns).as_micros()));
        if composite_needed {
            self.proxy.send(Msg::Repaint(self.window_id));
        }
    }
}