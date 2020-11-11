use std::os::raw::{c_char, c_uint, c_void};
use std::sync::Arc;

use euclid::Vector2D;
use font_kit::font::Font;
use harfbuzz::{Buffer, Direction, Language};
use harfbuzz::sys::{hb_feature_t, HB_FEATURE_GLOBAL_START, hb_blob_create, hb_blob_destroy, hb_blob_t, hb_buffer_get_glyph_infos, hb_buffer_get_glyph_positions,
                    hb_face_create, hb_face_destroy, hb_face_reference, hb_face_t, hb_font_create, hb_font_destroy, hb_font_t, hb_shape,
                    HB_MEMORY_MODE_READONLY, hb_variation_t, hb_font_set_variations, hb_ot_font_set_funcs};
use itertools::Itertools;
use webrender::api::GlyphInstance;
use webrender::webrender_api::units::{LayoutSize, LayoutVector2D};
use crate::fonts::{LayoutedText, FontInstance};
use harfbuzz::sys::HB_SCRIPT_LATIN;

/*
details on UNSAFE_TO_BREAK flag https://harfbuzz.github.io/harfbuzz-hb-buffer.html#hb-glyph-flags-t
it may be used to perform word wraps using single layout run

discussion about different approaches on text shape caches with raph and behdad https://github.com/linebender/skribo/issues/4

also this proposal seems relevant to my perception of ideal cache https://github.com/harfbuzz/harfbuzz/issues/1463,
this comment in particular https://github.com/harfbuzz/harfbuzz/issues/1463#issuecomment-476877426
*/

/// A HarfBuzz blob that's backed by an `Arc<Vec>`.
///
/// Note: this can probably be merged with `Blob` in the harfbuzz crate.
struct ArcVecBlob(*mut hb_blob_t);

impl ArcVecBlob {
    pub fn new(data: Arc<Vec<u8>>) -> ArcVecBlob {
        let len = data.len();
        assert!(len <= c_uint::max_value() as usize);
        unsafe {
            let data_ptr = data.as_ptr();
            let ptr = Arc::into_raw(data);
            let hb_blob = hb_blob_create(
                data_ptr as *const c_char,
                len as c_uint,
                HB_MEMORY_MODE_READONLY,
                ptr as *mut c_void,
                Some(arc_vec_blob_destroy),
            );
            ArcVecBlob(hb_blob)
        }
    }

    pub fn into_raw(self) -> *mut hb_blob_t {
        let ptr = self.0;
        std::mem::forget(self);
        ptr
    }
}

// Can implement Clone, Deref as needed; impls similar to harfbuzz crate

impl Drop for ArcVecBlob {
    fn drop(&mut self) {
        unsafe {
            hb_blob_destroy(self.0);
        }
    }
}

// This has type hb_destroy_func_t
unsafe extern "C" fn arc_vec_blob_destroy(user_data: *mut c_void) {
    std::mem::drop(Arc::from_raw(user_data as *const Vec<u8>))
}

pub struct HbFace {
    hb_face: *mut hb_face_t,
}

impl HbFace {
    pub fn new(font: &Font) -> HbFace {
        let data = font.copy_font_data().expect("font data unavailable");
        let blob = ArcVecBlob::new(data);
        unsafe {
            let hb_face = hb_face_create(blob.into_raw(), 0);
            HbFace { hb_face }
        }
    }
}

impl Clone for HbFace {
    fn clone(&self) -> HbFace {
        unsafe {
            HbFace {
                hb_face: hb_face_reference(self.hb_face),
            }
        }
    }
}

impl Drop for HbFace {
    fn drop(&mut self) {
        unsafe {
            hb_face_destroy(self.hb_face);
        }
    }
}

fn line_height(font: &Font, font_size: f32) -> f32 {
    let font_metrics = font.metrics();
    let scale = font_size / (font_metrics.units_per_em as f32);
    (font_metrics.ascent - font_metrics.descent) * scale
}


macro_rules! ot_tag {
        ($t1:expr, $t2:expr, $t3:expr, $t4:expr) => {
            (($t1 as u32) << 24) | (($t2 as u32) << 16) | (($t3 as u32) << 8) | ($t4 as u32)
        };
    }


pub const LIGA: u32 = ot_tag!('l', 'i', 'g', 'a');
pub const DLIG: u32 = ot_tag!('d', 'l', 'i', 'g');
pub const CALT: u32 = ot_tag!('c', 'a', 'l', 't');
pub const HB_FEATURE_GLOBAL_END: u32 = -1i32 as u32;



pub fn layout_run(hb_font: *mut hb_font_t, font: &Font, text: &str, font_size: f32) -> LayoutedText {
    let mut b = Buffer::new();
    b.add_str(text);
    b.set_direction(Direction::LTR);
    b.set_script(HB_SCRIPT_LATIN);
    b.set_language(Language::from_string("en"));
//    b.guess_segment_properties();
    unsafe {
        let mut features = Vec::new();
//            features.push(hb_feature_t {
//                tag: CALT,
//                value: 0,
//                start: HB_FEATURE_GLOBAL_START,
//                end: HB_FEATURE_GLOBAL_END,
//            });

        hb_shape(hb_font, b.as_ptr(),  features.as_mut_ptr(), features.len() as u32);
        let mut n_glyph = 0;
        let glyph_infos = hb_buffer_get_glyph_infos(b.as_ptr(), &mut n_glyph);
        let glyph_infos = std::slice::from_raw_parts(glyph_infos, n_glyph as usize);
        let mut n_glyph_pos = 0;
        let glyph_positions = hb_buffer_get_glyph_positions(b.as_ptr(), &mut n_glyph_pos);
        let glyph_positions = std::slice::from_raw_parts(glyph_positions, n_glyph_pos as usize);
        let mut total_adv = LayoutVector2D::new(0.0, 0.0);
        let mut glyphs = Vec::new();
        let mut clusters = Vec::new();
        let font_metrics = font.metrics();
        let scale = font_size / (font_metrics.units_per_em as f32);
        let line_height = line_height(font, font_size);
        for (glyph, pos) in glyph_infos.iter().zip(glyph_positions.iter()) {
            let adv = Vector2D::new(pos.x_advance, pos.y_advance);
            let adv_f = adv.to_f32() * scale;
            let offset = Vector2D::new(pos.x_offset, pos.y_offset).to_f32() * scale;
            let g = GlyphInstance {
                index: glyph.codepoint,
                point: (total_adv + offset + Vector2D::new(0.0, font_metrics.ascent * scale)).cast_unit().to_point(),
            };
            total_adv += adv_f;
            clusters.push(glyph.cluster);
            glyphs.push(g);
        }
        LayoutedText {
            glyphs,
            clusters,
            size: LayoutSize::new(total_adv.x, line_height),
        }
    }
}

pub enum Wrap {
    Lines,
    Words,
}

pub fn layout_text(hb_face: &HbFace, font: &Font, text: &str, font_instance: FontInstance, block_width: f32, wrap: Wrap) -> LayoutedText {
    let font_size = font_instance.size as f32;
    let line_height = line_height(font, font_size);
    let hb_font = unsafe {
        hb_font_create(hb_face.hb_face)
    };
    unsafe {
        hb_ot_font_set_funcs(hb_font);
    }

    if let Some(var) = font_instance.variation {
        unsafe {
            let variations = [hb_variation_t { tag: var.tag, value: var.value }];
            hb_font_set_variations(hb_font, variations.as_ptr(), variations.len() as u32);
        }
    }

    let result = match wrap {
        Wrap::Lines => {
            let mut result = LayoutedText { glyphs: vec![], clusters: vec![], size: LayoutSize::zero() };
            let mut cursor = LayoutVector2D::zero();
            let mut line_start_offset = 0;
            for line in text.lines() {
                let line_layout = layout_run(hb_font, font, line, font_size);
                for g in line_layout.glyphs {
                    let mut g = g.clone();
                    g.point += cursor;
                    result.glyphs.push(g);
                }
                for cluster in line_layout.clusters {
                    result.clusters.push(line_start_offset + cluster);
                }
                cursor.y += line_height;
                result.size.width = result.size.width.max(line_layout.size.width);
                result.size.height = result.size.height.max(cursor.y);
                line_start_offset += (line.len() + 1) as u32; // TODO 1 for /n; 2 for /n/r in WINDOWS?
            }
            result
        }

        Wrap::Words => {
            let mut result = LayoutedText { glyphs: vec![], clusters: vec![], size: LayoutSize::zero() };
            let mut cursor = LayoutVector2D::zero();
            let mut first_word = true;
            let mut word_start_offset = 0;
            for line in text.lines() {
                for word in line.split(" ").intersperse(" ") {
                    let word_layout = layout_run(hb_font, font, word, font_size);
                    if !first_word && cursor.x + word_layout.size.width > block_width.max(result.size.width) {
                        cursor.y += line_height;
                        result.size.height = result.size.height.max(cursor.y);
                        cursor.x = 0.0;
                    }
                    for g in word_layout.glyphs {
                        let mut g = g.clone();
                        g.point += cursor;
                        result.glyphs.push(g);
                    }
                    for cluster in word_layout.clusters {
                        result.clusters.push(word_start_offset + cluster);
                    }
                    cursor.x += word_layout.size.width;
                    result.size.width = result.size.width.max(cursor.x);
                    first_word = false;
                    word_start_offset += word.len() as u32;
                }
                word_start_offset += 1; // TODO 1 for /n; 2 for /n/r in WINDOWS?
                cursor.y += line_height;
                result.size.height = result.size.height.max(cursor.y);
                cursor.x = 0.0;
                first_word = true;
            }
            result
        }
    };

    unsafe { hb_font_destroy(hb_font); }
    result
}

#[cfg(test)]
mod tests {
    use std::fs::File;
    use std::io::Read;

    use super::*;

    fn init_font() -> (HbFace, Font) {
        let font_index = 0; // 0 for single font file
        let mut file = File::open("resources/Fira Code/ttf/FiraCode-Retina.ttf").unwrap();
        let mut buffer = Vec::new();
        file.read_to_end(&mut buffer).unwrap();
        let font = font_kit::handle::Handle::from_memory(Arc::new(buffer), font_index).load().unwrap();
        let hb_face = HbFace::new(&font);
        (hb_face, font)
    }

    #[test]
    fn test_layout() {
        let (hb_face, font) = init_font();
        let instance = FontInstance { id: 0, size: 14, variation: None };
        let layouted_text = layout_text(&hb_face, &font, "hello cruel\n world Foo Bar Baz", instance, 30.0, Wrap::Words);
        println!("{:?}", layouted_text)
    }

    #[test]
    fn test_perf() {
        let (hb_face, font) = init_font();

        let mut file = File::open("../../dev/res/EditorImpl.java").unwrap();
        let mut contents = String::new();
        file.read_to_string(&mut contents).unwrap();
        let hb_font = unsafe {
            hb_font_create(hb_face.hb_face)
        };

        let result = layout_run(hb_font, &font, "abc", 14.0);

        let t0 = std::time::Instant::now();
        let mut c = 0;
        c = layout_run(hb_font, &font, &contents, 14.0).glyphs.len();

        unsafe { hb_font_destroy(hb_font); }

        println!("{:?} {:?}", t0.elapsed(), c);
    }

    #[test]
    fn test_perf_layout_words() {
        let (hb_face, font) = init_font();

        let mut file = File::open("../../dev/res/EditorImpl.java").unwrap();
        let mut contents = String::new();
        file.read_to_string(&mut contents).unwrap();

        let t0 = std::time::Instant::now();
        let mut c = 0;
        let instance = FontInstance { id: 0, size: 14, variation: None };
        for l in contents.lines() {
            let result = layout_text(&hb_face, &font, l, instance.clone(), 10000.0, Wrap::Words);
            c += result.glyphs.len();
        }

        println!("{:?} {:?}", t0.elapsed(), c);
    }

    #[test]
    fn test_perf_layout_lines() {
        let (hb_face, font) = init_font();

        let mut file = File::open("../../dev/res/EditorImpl.java").unwrap();
        let mut contents = String::new();
        file.read_to_string(&mut contents).unwrap();

        let t0 = std::time::Instant::now();
        let mut c = 0;
        let instance = FontInstance { id: 0, size: 14, variation: None };
        for l in contents.lines() {
            let result = layout_text(&hb_face, &font, l, instance.clone(), 10000.0, Wrap::Lines);
            c += result.glyphs.len();
        }

        println!("{:?} {:?}", t0.elapsed(), c);
    }

    #[test]
    fn test_alloc_perf() {
        let size = 1000;
        let iterations = 1000;
        let mut sum: u64 = 0;
        let t0 = std::time::Instant::now();
        for i in 1..iterations {
            let mut buffer = Vec::<u8>::with_capacity(size + 1);
            for j in 0..(size + 1) {
                buffer.push(((i * j) % 255) as u8);
            }

            for v in buffer {
                sum += (v % 2) as u64;
            }
        }
        println!("Result: {:?} Took: {:?}ms", sum, (t0.elapsed().as_millis() as f32) / (iterations as f32))
    }
}