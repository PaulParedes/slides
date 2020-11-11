use std::sync::Arc;

use fxhash::FxHashMap;
use serde::{Serialize, Deserialize};
use webrender::api::*;
use webrender::api::units::*;

use crate::harfbuzz;
pub use crate::harfbuzz::Wrap;


pub type FontId = i64;
pub type FontSize = i32;

pub struct FontData {
    pub font_kit_font: font_kit::font::Font,
    pub hb_face: harfbuzz::HbFace,
    pub hb_face2: harfbuzz2::HbFace,
}

#[derive(Debug, PartialEq, Eq, Hash, Clone)]
pub struct FontInstance {
    pub id: FontId,
    pub size: FontSize,
    //TODO why single variation?
    pub variation: Option<FontVariation>,
}

// TODO check for data races
unsafe impl Sync for FontsContainer {}

unsafe impl Send for FontsContainer {}

#[derive(Debug, PartialEq, Clone, Serialize, Deserialize)]
pub struct LayoutedText {
    pub glyphs: Vec<GlyphInstance>,
    pub size: LayoutSize,
    pub clusters: Vec<u32>,
}

#[derive(Default)]
pub struct FontsContainer {
    pub fonts: FxHashMap<FontId, Arc<FontData>>
}

impl FontsContainer {
    pub fn load_font(&mut self, font_id: FontId, buffer: Vec<u8>) {
        let font = font_kit::handle::Handle::from_memory(Arc::new(buffer), 0).load().expect("failed to load font");
        let hb_font = harfbuzz::HbFace::new(&font);
        let hb_font2 = harfbuzz2::HbFace::create(&font);
        let font_data = FontData {
            font_kit_font: font,
            hb_face: hb_font,
            hb_face2: hb_font2,
        };
        self.fonts.insert(font_id, Arc::new(font_data));
    }

    pub fn get(&self, font_id: FontId) -> Option<Arc<FontData>> {
        self.fonts.get(&font_id).map(|arc| arc.clone())
    }

    pub fn layout_text(
        &self,
        font_instance: FontInstance,
        origin: LayoutPoint,
        text: &str,
        width: f32,
        wrap: harfbuzz::Wrap) -> LayoutedText
    {
        let font = self.get(font_instance.id).expect("no such font");
        let hb_font = &font.hb_face;
        let font = &font.font_kit_font;
        let mut layouted_text = harfbuzz::layout_text(hb_font, font, text, font_instance, width, wrap);
        let origin_offset = origin.to_vector();
        for glyph in &mut layouted_text.glyphs {
            glyph.point += origin_offset;
        }
        return layouted_text;
    }
}

pub mod harfbuzz2 {
    use std::os::raw::{c_char, c_uint, c_int, c_void};
    use std::sync::Arc;
    use harfbuzz::sys::*;
    use webrender::webrender_api::FontVariation;
    use font_kit::font::Font;
    use serde::Serialize;

    pub struct HbFace {
        hb_ptr: *mut hb_face_t,
    }

    impl HbFace {
        pub fn create(font: &Font) -> HbFace {
            let font_data = font.copy_font_data().expect("font data unavailable");
            let len = font_data.len();
            let hb_face = unsafe {
                let data_ptr = font_data.as_ptr();
                let arc_ptr = Arc::into_raw(font_data);
                let hb_blob = hb_blob_create(data_ptr as *const c_char,
                                             len as c_uint,
                                             HB_MEMORY_MODE_WRITABLE,
                                             arc_ptr as *mut c_void,
                                             Some(arc_vec_blob_destroy));
                // font file can contain entire collection of fonts
                let font_index = 0;
                hb_face_create(hb_blob, font_index)
            };
            HbFace { hb_ptr: hb_face }
        }
    }

    impl Drop for HbFace {
        fn drop(&mut self) {
            unsafe {
                hb_face_destroy(self.hb_ptr);
            }
        }
    }

    pub struct HbFont<'a> {
        face: &'a HbFace,
        hb_ptr: *mut hb_font_t,
    }

    pub fn hb_font(face: &HbFace, variation: Option<FontVariation>) -> HbFont {
        let hb_font = unsafe {
            let hb_font = hb_font_create(face.hb_ptr);
            //documentation says it's not necessary to call it explicitly, but for some reason linux has troubles without it
            hb_ot_font_set_funcs(hb_font);
            if let Some(var) = variation {
                let variations = [hb_variation_t { tag: var.tag, value: var.value }];
                hb_font_set_variations(hb_font, variations.as_ptr(), variations.len() as u32);
            }
            hb_font
        };
        HbFont { face, hb_ptr: hb_font }
    }

    impl<'a> Drop for HbFont<'a> {
        fn drop(&mut self) {
            unsafe { hb_font_destroy(self.hb_ptr); }
        }
    }

    unsafe extern "C" fn arc_vec_blob_destroy(user_data: *mut c_void) {
        std::mem::drop(Arc::from_raw(user_data as *const Vec<u8>))
    }

    #[repr(C)]
    #[derive(Debug, Serialize)]
    pub struct Glyph {
        pub glyph_index: u32,
        pub glyph_cluster: u32,
        pub x_advance: i32,
        pub y_advance: i32,
        pub x_offset: i32,
        pub y_offset: i32,
        pub safe_to_break: bool,
    }

    #[derive(Debug, Serialize)]
    pub struct TextShape {
        glyphs: Vec<Glyph>,
    }

    macro_rules! ot_tag {
        ($t1:expr, $t2:expr, $t3:expr, $t4:expr) => {
            (($t1 as u32) << 24) | (($t2 as u32) << 16) | (($t3 as u32) << 8) | ($t4 as u32)
        };
    }

    #[allow(unused)]
    pub const LIGA: u32 = ot_tag!('l', 'i', 'g', 'a');
    #[allow(unused)]
    pub const DLIG: u32 = ot_tag!('d', 'l', 'i', 'g');
    #[allow(unused)]
    pub const CALT: u32 = ot_tag!('c', 'a', 'l', 't');
    #[allow(unused)]
    pub const HB_FEATURE_GLOBAL_END: u32 = -1i32 as u32;

    #[allow(dead_code)]
    unsafe extern "C" fn buffer_message(buffer: *mut hb_buffer_t,
                                        _font: *mut hb_font_t,
                                        message: *const ::std::os::raw::c_char,
                                        _user_data: *mut ::std::os::raw::c_void) -> i32 {
        println!("{:?}", std::ffi::CStr::from_ptr(message).to_str().expect("message"));
        0
    }

    pub fn shape_run(hb_font: &HbFont, text: &str) -> TextShape {
        unsafe {
            let hb_buffer: *mut hb_buffer_t = hb_buffer_create();
//            uncomment this line to enable harfbuzz internal tracing
//            hb_buffer_set_message_func(hb_buffer, Some(buffer_message), std::ptr::null_mut(), None);
            hb_buffer_guess_segment_properties(hb_buffer);
            hb_buffer_add_utf8(hb_buffer, text.as_ptr() as *const c_char, text.len() as c_int, 0, text.len() as c_int);
            let mut features = Vec::new();
//            features.push(hb_feature_t {
//                tag: CALT,
//                value: 0,
//                start: HB_FEATURE_GLOBAL_START,
//                end: HB_FEATURE_GLOBAL_END,
//            });

            hb_shape(hb_font.hb_ptr, hb_buffer, features.as_mut_ptr(), features.len() as u32);
            let mut n_glyph = 0;
            let glyph_infos = hb_buffer_get_glyph_infos(hb_buffer, &mut n_glyph);
            let glyph_infos = std::slice::from_raw_parts(glyph_infos, n_glyph as usize);
            let mut n_glyph_pos = 0;
            let glyph_positions = hb_buffer_get_glyph_positions(hb_buffer, &mut n_glyph_pos);
            let glyph_positions = std::slice::from_raw_parts(glyph_positions, n_glyph_pos as usize);

            let mut glyphs = Vec::with_capacity(n_glyph as usize);
            for (info, pos) in glyph_infos.into_iter().zip(glyph_positions.into_iter()) {
                let flags = hb_glyph_info_get_glyph_flags(info);
                let safe_to_break = (flags & HB_GLYPH_FLAG_UNSAFE_TO_BREAK) != HB_GLYPH_FLAG_UNSAFE_TO_BREAK;
                let hb_glyph_position_t { x_advance, y_advance, x_offset, y_offset, .. } = pos;
                glyphs.push(Glyph {
                    glyph_index: info.codepoint,
                    glyph_cluster: info.cluster,
                    x_advance: *x_advance,
                    y_advance: *y_advance,
                    x_offset: *x_offset,
                    y_offset: *y_offset,
                    safe_to_break,
                });
            }
            hb_buffer_destroy(hb_buffer);
            TextShape { glyphs }
        }
    }

    pub fn shape(hb_font: &HbFont, text: &str) -> TextShape {
        unimplemented!()
    }
}