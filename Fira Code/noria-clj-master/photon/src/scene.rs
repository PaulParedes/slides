use fxhash::{FxHashMap, FxHashSet};
use serde::Deserialize;
use webrender::webrender_api::*;
use webrender::webrender_api::units::*;

use crate::event_loop::WindowInfo;
use crate::fonts::{FontId, FontInstance, FontSize};
use crate::images::{ImagesContainer, ImageId, Image};
use std::sync::RwLock;
use crate::diagnostic::{FrameId, DiagnosticSender, notify_on_render};

pub type NodeId = i64;
pub type CallbackId = i64;

#[derive(Debug, PartialEq, Clone)]
struct StackingNode {
    origin: LayoutPoint,
    stacked: Vec<NodeId>
}

#[derive(Debug, PartialEq, Clone)]
struct EventsTarget {
    callback_id: CallbackId,
    mask: u16,
}

impl EventsTarget {
    fn hit_tag(&self) -> ItemTag {
        (self.callback_id as u64, self.mask)
    }
}

#[derive(Debug, PartialEq, Clone)]
struct RectNode {
    frame: LayoutRect,
    color: ColorF,
    events_target: Option<EventsTarget>
}

#[derive(Debug, PartialEq, Clone)]
struct BorderNode {
    frame: LayoutRect,
    color: ColorF,
    line_width: f32,
    style: BorderStyle,
    border_radius: BorderRadius,
}

#[derive(Debug, PartialEq, Clone)]
struct BoxShadowNode {
    clip_rect: LayoutRect,
    basis_rect: LayoutRect,
    offset: LayoutVector2D,
    color: ColorF,
    blur_radius: f32,
    spread_radius: f32,
    border_radius: BorderRadius,
    clip_mode: BoxShadowClipMode,
}

#[derive(Debug, PartialEq, Clone)]
struct TextNode {
    frame: LayoutRect,
    color: ColorF,
    glyphs: Vec<GlyphInstance>,
    font_id: NodeId,
    font_size: FontSize,
    font_key: FontKey,
    font_instance_key: FontInstanceKey,
}

#[derive(Debug, PartialEq, Clone)]
struct ScrollNode {
    frame: LayoutRect,
    content_size: LayoutSize,
    content_id: NodeId,
    scroll_position: LayoutPoint
}

#[derive(Debug, PartialEq, Clone)]
struct ClipNode {
    clip_rect: LayoutRect,
    complex_clips: Vec<ComplexClipRegion>,
    content_id: NodeId,
}

#[derive(Debug, PartialEq, Clone)]
struct ImageNode {
    frame: LayoutRect,
    image_key: ImageKey,
}

#[derive(Debug, PartialEq, Clone)]
enum Node {
    Stack(StackingNode),
    Rect(RectNode),
    Border(BorderNode),
    BoxShadow(BoxShadowNode),
    Text(TextNode),
    Scroll(ScrollNode),
    Clip(ClipNode),
    Image(ImageNode)
}

struct SceneTree {
    size: LayoutSize,
    arena: FxHashMap<NodeId, Node>,
    root_id: Option<NodeId>
}

impl SceneTree {
    fn new(size: LayoutSize) -> Self {
        SceneTree {
            arena: FxHashMap::default(),
            size,
            root_id: None
        }
    }

    fn root_id(&self) -> NodeId {
        self.root_id.expect("Set root node first!")
    }

    fn mut_node_ref(&mut self, node_id: NodeId) -> &mut Node {
        self.arena.get_mut(&node_id).expect(format!("No node with id: {}", node_id).as_str())
    }

    fn node_ref(&self, node_id: NodeId) -> &Node {
        self.arena.get(&node_id).expect(format!("No node with id: {}", node_id).as_str())
    }

    fn add_node(&mut self, node_id: NodeId, node: Node) -> bool {
        let old_node = self.arena.get(&node_id);
        if old_node.is_some() && *old_node.unwrap() == node {
            false
        } else {
            self.arena.insert(node_id, node);
            true
        }
    }

    fn truncate(&mut self, node_id: NodeId, depth: u32) -> bool {
        assert!(depth < 1000);
        match self.node_ref(node_id) {
            Node::Stack(StackingNode { stacked, .. }) => {
                let mut new_stacked = stacked.clone();
                new_stacked.retain(|id| {
                    let contains = self.arena.contains_key(&id);
                    if !contains {
                        log::error!("No node with id: {:?}", id)
                    }
                    contains && !self.truncate(*id, depth + 1)
                });

                if let Node::Stack(node) = self.mut_node_ref(node_id) {
                    node.stacked = new_stacked;
                }

                false
            }
            Node::Scroll(ScrollNode { content_id, .. }) => {
                let content_id = *content_id;
                let contains = self.arena.contains_key(&content_id);
                if !contains {
                    log::error!("No node with id: {:?}", content_id);
                }
                !contains || self.truncate(content_id, depth + 1)
            }
            Node::Clip(ClipNode { content_id, .. }) => {
                let content_id = *content_id;
                let contains = self.arena.contains_key(&content_id);
                if !contains {
                    log::error!("No node with id: {:?}", content_id);
                }
                !contains || self.truncate(content_id, depth + 1)
            }
            _ => false
        }
    }

    fn gc(&mut self) {
        if let Some(root_id) = self.root_id {
            let mut visited: FxHashSet<NodeId> = Default::default();
            let mut stack = vec![root_id];
            while !stack.is_empty() {
                let current_node_id = stack.pop().unwrap();
                match self.node_ref(current_node_id) {
                    Node::Stack(StackingNode { stacked, ..}) => {
                        for &s in stacked {
                            stack.push(s);
                        }
                    }
                    Node::Scroll(ScrollNode { content_id, ..}) => {
                        stack.push(*content_id);
                    }
                    Node::Clip(ClipNode { content_id, .. }) => {
                        stack.push(*content_id);
                    }
                    _ => {}
                }
                visited.insert(current_node_id);
            }
            self.arena.retain(|node_id, _| {
                visited.contains(node_id)
            });
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub enum Update {
    SetRoot(NodeId),
    Rect(NodeId, LayoutPoint, LayoutSize, ColorF),
    Border {
        node_id: NodeId,
        origin: LayoutPoint,
        size: LayoutSize,
        color: ColorF,
        line_width: f32,
        style: BorderStyle,
        border_radius: BorderRadius
    },
    BoxShadow {
        node_id: NodeId,
        clip_rect: LayoutRect,
        basis_rect: LayoutRect,
        offset: LayoutVector2D,
        color: ColorF,
        blur_radius: f32,
        spread_radius: f32,
        border_radius: BorderRadius,
        clip_mode: BoxShadowClipMode
    },
    Text {
        node_id: NodeId,
        origin: LayoutPoint,
        size: LayoutSize,
        color: ColorF,
        layouted_text: Vec<GlyphInstance>,
        background_size: LayoutSize,
        font_id: NodeId,
        font_size: FontSize,
        font_variation_tag: i64,
        font_variation_value: f32,
    },
    Scroll {
        node_id: NodeId,
        origin: LayoutPoint,
        size: LayoutSize,
        content_id: NodeId,
        content_size: LayoutSize
    },
    ScrollPosition(NodeId, LayoutPoint),
    Stack(NodeId, Vec<NodeId>),
    SetPosition(NodeId, LayoutPoint),
    SetOpacity(NodeId, f32),
    Destroy(NodeId),
    OnEvent {
        node_id: NodeId,
        callback_id: CallbackId,
        mask: u16,
    },
    SetSceneSize(LayoutSize),
    Clip {
        node_id: NodeId,
        clip_rect: LayoutRect,
        complex_clips: Vec<ComplexClipRegion>,
        content_id: NodeId,
    },
    Image {
        node_id: NodeId,
        origin: LayoutPoint,
        size: LayoutSize,
        image_id: ImageId
    }
}

#[derive(PartialEq, Eq)]
enum ApplyResult {
    Rebuild,
    Nothing,
}

pub struct Scene {
    render_api: RenderApi,
    document_id: DocumentId,
    pipeline_id: PipelineId,
    epoch: Epoch,
    scene_tree: SceneTree,
    font_keys: FxHashMap<FontId, FontKey>,
    font_instance_keys: FxHashMap<FontInstance, FontInstanceKey>,
    image_keys: FxHashMap<ImageId, ImageKey>,
    images_container: &'static RwLock<ImagesContainer>,
    diagnostic_sender: DiagnosticSender
}

struct ViewFrame {
    spatial_id: SpatialId,
    clip_id: ClipId
}

struct BuilderContext {
    depth: u32,
    builder: DisplayListBuilder,
    frame_stack: Vec<ViewFrame>,
    base_offset: LayoutPoint
}

impl BuilderContext {
    fn top_frame(&self) -> &ViewFrame {
        self.frame_stack.last().expect("Empty frame_stack")
    }
}

impl Scene {
    const BACKGROUND_COLOR: ColorF = ColorF {
        r: 0.1,
        g: 0.8,
        b: 0.5,
        a: 1.0,
    };

    pub fn new(window_info: WindowInfo, images_container: &'static RwLock<ImagesContainer>) -> Self {
        Scene {
            render_api: window_info.render_api,
            document_id: window_info.document_id,
            pipeline_id: window_info.pipeline_id,
            epoch: Epoch(0),
            scene_tree: SceneTree::new(LayoutSize::new(window_info.size.width as f32,
                                                       window_info.size.height as f32)),
            font_keys: Default::default(),
            font_instance_keys: Default::default(),
            image_keys: Default::default(),
            images_container,
            diagnostic_sender: window_info.diagnostic_sender
        }
    }

    pub fn load_font(&mut self, font_id: FontId, bytes: Vec<u8>) {
        let Scene { render_api, font_keys, document_id, .. } = self;
        font_keys.entry(font_id).or_insert_with(|| {
            let font_key = render_api.generate_font_key();
            let mut txn = Transaction::new();
            txn.add_raw_font(font_key, bytes, 0);
            render_api.send_transaction(*document_id, txn);
            font_key
        });
    }

    fn push_stack(&self, context: &mut BuilderContext, node_id: NodeId, stack: &StackingNode) {
        let StackingNode { origin, stacked } = stack;
        context.depth += 1;
        context.base_offset += origin.to_vector();
        for &node_id in stacked {
            self.build_display_list(context, node_id, self.scene_tree.node_ref(node_id));
        }
        context.base_offset -= origin.to_vector();
        context.depth -= 1;
    }

    fn push_rect(&self, context: &mut BuilderContext, rect: &RectNode) {
        let RectNode { frame, color, events_target } = rect;
        let item_properties = CommonItemProperties {
            clip_rect: frame.translate(context.base_offset.to_vector()),
            clip_id: context.top_frame().clip_id,
            spatial_id: context.top_frame().spatial_id,
            hit_info: events_target.as_ref().map(|et| et.hit_tag()),
            flags: Default::default(),
            item_key: None
        };
        context.builder.push_rect(&item_properties, *color);
    }

    fn push_border(&self, context: &mut BuilderContext, border: &BorderNode) {
        let BorderNode { frame, color, line_width, style, border_radius } = border;
        let item_properties = CommonItemProperties {
            clip_rect: frame.translate(context.base_offset.to_vector()),
            clip_id: context.top_frame().clip_id,
            spatial_id: context.top_frame().spatial_id,
            hit_info: None,
            flags: Default::default(),
            item_key: None
        };
        let layout_side_offsets = LayoutSideOffsets::new_all_same(*line_width);
        let border = BorderSide {
            color: *color,
            style: *style
        };
        let border_details = BorderDetails::Normal(NormalBorder {
            left: border,
            right: border,
            top: border,
            bottom: border,
            radius: *border_radius,
            do_aa: true
        });
        context.builder.push_border(&item_properties, frame.translate(context.base_offset.to_vector()), layout_side_offsets, border_details);
    }

    fn push_box_shadow(&self, context: &mut BuilderContext, shadow_box: &BoxShadowNode) {
        let BoxShadowNode { clip_rect, basis_rect, offset, color, blur_radius, spread_radius, border_radius, clip_mode } = shadow_box;
        let item_properties = CommonItemProperties {
            clip_rect: clip_rect.translate(context.base_offset.to_vector()),
            clip_id: context.top_frame().clip_id,
            spatial_id: context.top_frame().spatial_id,
            hit_info: None,
            flags: Default::default(),
            item_key: None
        };
        context.builder.push_box_shadow(&item_properties,
                                        *basis_rect,
                                        *offset,
                                        *color,
                                        *blur_radius,
                                        *spread_radius,
                                        *border_radius,
                                        *clip_mode);
    }

    fn push_scroll(&self, context: &mut BuilderContext, node_id: NodeId, scroll: &ScrollNode) {
        let ScrollNode { frame, content_size, content_id, scroll_position } = scroll;
        let parent_space_and_clip = SpaceAndClipInfo {
            spatial_id: context.top_frame().spatial_id,
            clip_id: context.top_frame().clip_id
        };
        let space_and_clip = context.builder.define_scroll_frame(&parent_space_and_clip,
                                                                 Some(ExternalScrollId { 0: node_id as u64, 1: self.pipeline_id }),
                                                                 LayoutRect::new(LayoutPoint::zero(), *content_size),
                                                                 frame.translate(context.base_offset.to_vector()),
                                                                 vec![],
                                                                 None,
                                                                 ScrollSensitivity::Script,
                                                                 LayoutVector2D::zero());

        let frame = ViewFrame {
            spatial_id: space_and_clip.spatial_id,
            clip_id: space_and_clip.clip_id
        };
        context.frame_stack.push(frame);
        context.depth += 1;
        self.build_display_list(context, *content_id, self.scene_tree.node_ref(*content_id));
        context.frame_stack.pop();
    }

    fn push_text(&self, context: &mut BuilderContext, text_node: &TextNode) {
        let TextNode { frame, color, glyphs, font_instance_key, .. } = text_node;
        let item_properties = CommonItemProperties {
            clip_rect: frame.translate(context.base_offset.to_vector()),
            clip_id: context.top_frame().clip_id,
            spatial_id: context.top_frame().spatial_id,
            hit_info: None,
            flags: Default::default(),
            item_key: None
        };
        let translated_glyphs: Vec<_> = glyphs.iter().map(|g| {
            let mut g = g.clone();
            g.point = g.point + context.base_offset.to_vector();
            g
        }).collect();
        context.builder.push_text(&item_properties,
                                  frame.translate(context.base_offset.to_vector()),
                                  translated_glyphs.as_slice(),
                                  *font_instance_key,
                                  *color,
                                  None);
    }
    
    fn push_clip(&self, context: &mut BuilderContext, clip: &ClipNode) {
        let ClipNode { clip_rect, complex_clips, content_id } = clip;
        {
            let frame = context.top_frame();
            let parent_space_and_clip = SpaceAndClipInfo { spatial_id: frame.spatial_id, clip_id: frame.clip_id };
            let BuilderContext { builder, frame_stack, base_offset, .. } = context;
            let complex_clips = complex_clips.iter().map(|cc| {
                let mut cc = cc.clone();
                cc.rect = cc.rect.translate(base_offset.to_vector());
                cc
            });
            let clip_id = builder.define_clip(&parent_space_and_clip, clip_rect.translate(base_offset.to_vector()), complex_clips, None);
            frame_stack.push(ViewFrame { clip_id, spatial_id: parent_space_and_clip.spatial_id });
        };
        self.build_display_list(context, *content_id, self.scene_tree.node_ref(*content_id));
        context.frame_stack.pop();
    }

    fn push_image(&self, context: &mut BuilderContext, image_node: &ImageNode) {
        let ImageNode { frame, image_key } = image_node;
        let item_properties = CommonItemProperties {
            clip_rect: frame.translate(context.base_offset.to_vector()),
            clip_id: context.top_frame().clip_id,
            spatial_id: context.top_frame().spatial_id,
            hit_info: None,
            flags: Default::default(),
            item_key: None
        };
        context.builder.push_image(&item_properties,
                                   // tile origin and same size in frame
                                   LayoutRect::new(context.base_offset, frame.size.clone()),
                                   ImageRendering::Auto,
                                   AlphaType::PremultipliedAlpha,
                                   *image_key,
                                   ColorF::WHITE);
    }
    
    const MAX_SCENE_DEPTH: u32 = 1_000;
    
    fn build_display_list(&self, context: &mut BuilderContext, node_id: NodeId, node: &Node) {
        assert!(context.depth < Scene::MAX_SCENE_DEPTH, "Scene depth limit");
        match node {
            Node::Stack(stack) => { self.push_stack(context, node_id, stack); }
            Node::Rect(rect) => { self.push_rect(context, rect); }
            Node::Border(border) => { self.push_border(context, border); }
            Node::BoxShadow(box_shadow) => { self.push_box_shadow(context, box_shadow); }
            Node::Text(text_node) => { self.push_text(context, text_node); }
            Node::Scroll(scroll_node) => { self.push_scroll(context, node_id, scroll_node); }
            Node::Clip(clip) => { self.push_clip(context, clip); }
            Node::Image(image_node) => { self.push_image(context, image_node); }
        }
    }

    fn apply_updates(&mut self, txn: &mut Transaction, updates: Vec<Update>) -> ApplyResult {
        let mut require_rebuild = false;
        let scene_tree = &mut self.scene_tree;
        for u in updates {
            let rebuild = match u.clone() {
                Update::SetRoot(node_id) => {
                    if scene_tree.root_id != Some(node_id) {
                        scene_tree.root_id = Some(node_id);
                        true
                    } else {
                        false
                    }
                },
                Update::Rect(node_id, origin, size, color) => {
                    let frame = LayoutRect::new(origin, size);
                    if let Some(Node::Rect(node)) = scene_tree.arena.get_mut(&node_id) {
                        if node.frame == frame && node.color == color {
                            false
                        } else {
                            node.frame = frame;
                            node.color = color;
                            true
                        }
                    } else {
                        scene_tree.add_node(node_id,
                                            Node::Rect(RectNode {
                                                frame: LayoutRect::new(origin, size),
                                                events_target: None,
                                                color,
                                            }))
                    }
                },
                Update::Border { node_id, origin, size, color, line_width, style, border_radius } => {
                    scene_tree.add_node(node_id, Node::Border(BorderNode {
                        frame: LayoutRect::new(origin, size),
                        color,
                        line_width,
                        style,
                        border_radius
                    }))
                },
                Update::BoxShadow { node_id, clip_rect, basis_rect, offset, color, blur_radius, spread_radius, border_radius, clip_mode } => {
                    scene_tree.add_node(node_id, Node::BoxShadow(BoxShadowNode {
                        clip_rect,
                        basis_rect,
                        offset,
                        color,
                        blur_radius,
                        spread_radius,
                        border_radius,
                        clip_mode
                    }))
                },
                Update::Text { node_id, origin, size, color, font_id, font_size, mut layouted_text, font_variation_tag, font_variation_value, .. } => {
                    let Scene { render_api, .. } = self;
                    let font_key = self.font_keys.get(&font_id).expect(format!("Font {:?} not found, existing font keys {:?}", font_id, self.font_keys.keys()).as_str());
                    let variation = if font_variation_tag > 0 {
                        Some(FontVariation { tag: font_variation_tag as u32, value: font_variation_value })
                    } else { None };
                    let instance = FontInstance { id: font_id, size: font_size, variation };
                    let font_instance_key = self.font_instance_keys.entry(instance).or_insert_with(|| {
                        let font_instance_key = render_api.generate_font_instance_key();
                        let variations = if let Some(v) = variation { vec![v] } else { vec![] };
                        txn.add_font_instance(font_instance_key, *font_key, app_units::Au::from_px(font_size), None, None, variations);
                        font_instance_key
                    });

                    let origin_offset = origin.to_vector();
                    for g in &mut layouted_text {
                        g.point += origin_offset;
                    }

                    scene_tree.add_node(node_id, Node::Text(TextNode {
                        frame: LayoutRect::new(origin, size),
                        color,
                        glyphs: layouted_text,
                        font_id,
                        font_size,
                        font_key: *font_key,
                        font_instance_key: *font_instance_key
                    }))
                },
                Update::Scroll { node_id, origin, size, content_id, content_size } => {
                    let frame = LayoutRect::new(origin, size);
                    if let Some(Node::Scroll(node)) = scene_tree.arena.get_mut(&node_id) {
                        if node.frame == frame &&
                            node.content_size == content_size &&
                            node.content_id == content_id {
                            false
                        } else {
                            node.frame = frame;
                            node.content_size = content_size;
                            node.content_id = content_id;
                            true
                        }
                    } else {
                        scene_tree.add_node(node_id, Node::Scroll(ScrollNode {
                            frame: LayoutRect::new(origin, size),
                            content_size,
                            content_id,
                            scroll_position: LayoutPoint::zero()
                        }))
                    }
                },
                Update::ScrollPosition(node_id, position) => {
                    if let Node::Scroll(node) = scene_tree.mut_node_ref(node_id) {
                        node.scroll_position = position
                    }
                    txn.scroll_node_with_id(position, ExternalScrollId { 0: node_id as u64, 1: self.pipeline_id }, ScrollClamping::NoClamping);
                    false
                }
                Update::Stack(node_id, ids) => {
                    if let Some(Node::Stack(old_node)) = scene_tree.arena.get_mut(&node_id) {
                        if old_node.stacked != ids {
                            old_node.stacked = ids;
                            true
                        } else {
                            false
                        }
                    } else {
                        scene_tree.arena.insert(node_id, Node::Stack(StackingNode { origin: LayoutPoint::zero(), stacked: ids }));
                        true
                    }
                },
                Update::SetPosition(node_id, origin) => {
                    match scene_tree.mut_node_ref(node_id) {
                        Node::Stack(stack_node) => {
                            if stack_node.origin != origin {
                                stack_node.origin = origin;
                                true
                            } else {
                                false
                            }
                        }
                        _ => {
                            unreachable!("Can't set position")
                        }
                    }
                },
                Update::SetOpacity(node_id, opacity) => {
                    txn.append_dynamic_properties(DynamicProperties {
                        transforms: vec![],
                        floats: vec![PropertyValue { key: PropertyBindingKey::new(node_id as u64), value: opacity }]
                    });
                    false
                },
                Update::Clip { node_id, clip_rect, complex_clips, content_id } => {
                    scene_tree.add_node(node_id, Node::Clip(ClipNode {
                        clip_rect,
                        complex_clips,
                        content_id
                    }))
                },
                Update::Destroy(node_id) => {
                    scene_tree.arena.remove(&node_id);
                    true
                },
                Update::OnEvent { node_id, callback_id, mask } => {
                    match scene_tree.mut_node_ref(node_id) {
                        Node::Rect(rect_node) => {
                            let new_target = if callback_id > 0 { Some(EventsTarget { callback_id, mask }) } else { None };
                            if rect_node.events_target == new_target {
                                false
                            } else {
                                rect_node.events_target = new_target;
                                true
                            }
                        }
                        _ => unreachable!("Only rect nodes support callbacks")
                    }
                }
                Update::SetSceneSize(size) => {
                    scene_tree.size = size.clone();
                    true
                }

                Update::Image { node_id, origin, size, image_id } => {
                    let Scene { image_keys, images_container, render_api, .. } = self;
                    let image_key_entry = image_keys.entry(image_id);
                    let image_key = image_key_entry.or_insert_with(|| {
                        let images_container = images_container.read().expect("IMAGE CONTAINER is poisoned");
                        // TODO ignore unknown image ids and log error
                        let image: &Image = images_container.images.get(&image_id).expect(format!("No image with id: {}", image_id).as_str());
                        let mut flags = ImageDescriptorFlags::ALLOW_MIPMAPS;
                        if image.is_opaque {
                            flags |= ImageDescriptorFlags::IS_OPAQUE;
                        }
                        let descriptor = ImageDescriptor {
                            size: DeviceIntSize::new(image.width as i32, image.height as i32),
                            stride: None,
                            format: ImageFormat::RGBA8,
                            offset: 0,
                            flags
                        };
                        let image_key = render_api.generate_image_key();
                        txn.add_image(image_key, descriptor, ImageData::new_shared(image.raw_bytes.clone()), None);
                        image_key
                    });
                    scene_tree.add_node(node_id, Node::Image(ImageNode {
                        frame: LayoutRect::new(origin, size),
                        image_key: *image_key
                    }))
                }
            };
//            if rebuild {
//                println!("rebuild after {:?}", u);
//            };
            require_rebuild |= rebuild;
        }
        // Truncate dead subtrees; can be removed after noria fix
        scene_tree.truncate(scene_tree.root_id(), 0);
        scene_tree.gc();
        if require_rebuild {
            ApplyResult::Rebuild
        } else {
            ApplyResult::Nothing
        }
    }

    pub fn commit(&mut self, frame_id: FrameId, updates: Vec<Update>) -> bool {
        let mut txn = Transaction::new();
        if self.apply_updates(&mut txn, updates) == ApplyResult::Rebuild {
            log::debug!("Rebuild DL with {:?}", frame_id);
            let content_size = self.scene_tree.size.clone();
            let root_scroll = SpaceAndClipInfo::root_scroll(self.pipeline_id);
            let root_view_frame = ViewFrame {
                spatial_id: root_scroll.spatial_id,
                clip_id: root_scroll.clip_id
            };
            let mut context = BuilderContext {
                depth: 0,
                builder: DisplayListBuilder::new(self.pipeline_id, content_size),
                frame_stack: vec![root_view_frame],
                base_offset: LayoutPoint::zero()
            };

            let root_id = self.scene_tree.root_id();
            self.build_display_list(&mut context, root_id, &self.scene_tree.node_ref(root_id));
            
            self.epoch.0 += 1;
            txn.update_epoch(self.pipeline_id, self.epoch);
            txn.set_display_list(self.epoch, Some(Scene::BACKGROUND_COLOR), content_size, context.builder.finalize(), true);
        }
        if !txn.is_empty() {
            txn.generate_frame();
            notify_on_render(&mut txn, frame_id, &self.diagnostic_sender);
            self.render_api.send_transaction(self.document_id, txn);
            true
        } else {
            false
        }
    }
}