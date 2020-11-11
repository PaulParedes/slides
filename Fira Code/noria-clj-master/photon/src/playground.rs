pub mod scene;
pub mod fonts;
pub mod images;
mod harfbuzz;
pub mod event_loop;
pub mod photon_api;

/*
   running rust->jvm->rust configuration requires "invocation" feature flag for jni crate
   right now it is written in photon's Cargo.toml which breaks this binary
   i don't want to shave this yak now so comment out that line before running benchmarks
   cargo run --bin playground --release
*/

#[allow(dead_code)]
#[allow(unused_variables)]
#[allow(unused_imports)]
mod playground {
    use std::fs::File;
    use std::os::raw::{c_char, c_uint, c_int, c_void};
    use std::sync::Arc;
    use std::io::{Read, BufRead};
    use std::path::PathBuf;

    use harfbuzz::{Buffer, Direction, Language};
    use harfbuzz::sys::*;

    use font_kit::{
        font::Font,
        loader::Loader,
        source::SystemSource,
        handle::Handle as FontHandle,
    };
    use itertools::Itertools;

    pub struct HbFace {
        hb_ptr: *mut hb_face_t,
    }

    impl Drop for HbFace {
        fn drop(&mut self) {
            unsafe {
                hb_face_destroy(self.hb_ptr);
            }
        }
    }

    pub struct HbFont {
        face: Arc<HbFace>,
        hb_ptr: *mut hb_font_t,
    }

    impl Drop for HbFont {
        fn drop(&mut self) {
            unsafe { hb_font_destroy(self.hb_ptr); }
        }
    }

    unsafe extern "C" fn arc_vec_blob_destroy(user_data: *mut c_void) {
        std::mem::drop(Arc::from_raw(user_data as *const Vec<u8>))
    }

    pub fn hb_font_face(font: &Font) -> Arc<HbFace> {
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
        Arc::new(HbFace { hb_ptr: hb_face })
    }

    pub fn hb_font(face: Arc<HbFace>) -> HbFont {
        let hb_font = unsafe {
            let hb_font = hb_font_create(face.hb_ptr);
            //documentation says it's not necessary to call it explicitly, but for some reason linux has troubles without it
            hb_ot_font_set_funcs(hb_font);
            hb_font
        };
        HbFont { face, hb_ptr: hb_font }
    }

    #[derive(Debug)]
    pub struct Glyph {
        pub glyph_index: u32,
        pub glyph_cluster: u32,
        pub advance: i32,
        pub safe_to_break: bool,
    }

    #[derive(Debug)]
    pub struct TextShape {
        glyphs: Vec<Glyph>,
    }

    macro_rules! ot_tag {
        ($t1:expr, $t2:expr, $t3:expr, $t4:expr) => {
            (($t1 as u32) << 24) | (($t2 as u32) << 16) | (($t3 as u32) << 8) | ($t4 as u32)
        };
    }

    fn log_used_shaper() {
//        let mut hb_segment_properties = hb_segment_properties_t{
//            direction: 0,
//            script: 0,
//            language: std::ptr::null_mut(),
//            reserved1: std::ptr::null_mut(),
//            reserved2: std::ptr::null_mut()
//        };
//        hb_buffer_get_segment_properties(hb_buffer, &mut hb_segment_properties);
//        let shape_plan = hb_shape_plan_create(hb_font.face.hb_ptr, &mut hb_segment_properties, features.as_ptr(), features.len() as u32, std::ptr::null());
//        let shaper = std::ffi::CStr::from_ptr(hb_shape_plan_get_shaper(shape_plan)).to_str().expect("shaper");
//        println!("using shaper {:?}", shaper);
//        hb_shape_plan_execute(shape_plan, hb_font.hb_ptr, hb_buffer, features.as_ptr(), features.len() as u32);
//        hb_shape_plan_destroy(shape_plan);
    }

    const LIGA: u32 = ot_tag!('l', 'i', 'g', 'a');
    const DLIG: u32 = ot_tag!('d', 'l', 'i', 'g');
    const CALT: u32 = ot_tag!('c', 'a', 'l', 't');
    const HB_FEATURE_GLOBAL_END: u32 = -1i32 as u32;

    unsafe extern "C" fn buffer_message(buffer: *mut hb_buffer_t,
                                        font: *mut hb_font_t,
                                        message: *const ::std::os::raw::c_char,
                                        user_data: *mut ::std::os::raw::c_void) -> i32 {
        println!("{:?}", std::ffi::CStr::from_ptr(message).to_str().expect("message"));
        0
    }

    pub fn shape_run(hb_font: &HbFont, text: &str) -> TextShape {
        let timer = std::time::Instant::now();
        unsafe {
            let hb_buffer: *mut hb_buffer_t = hb_buffer_create();

            // uncomment this line to enable harfbuzz internal tracing
//            hb_buffer_set_message_func(hb_buffer, Some(buffer_message), std::ptr::null_mut(), None);

//            hb_buffer_set_direction(hb_buffer, HB_DIRECTION_LTR);
//            hb_buffer_set_script(hb_buffer, HB_SCRIPT_LATIN);
//            hb_buffer_set_language(hb_buffer, hb_language_get_default());
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
                let hb_glyph_position_t { x_advance, y_advance: _, .. } = pos;
                glyphs.push(Glyph { glyph_index: info.codepoint, glyph_cluster: info.cluster, advance: *x_advance, safe_to_break });
            }
            hb_buffer_destroy(hb_buffer);
            TextShape { glyphs }
        }
    }

    pub fn benchmark_layout(font: &Font, runs: u32) {
        let mut content = String::new();
        let mut f = File::open("../../dev/res/EditorImpl.java").expect("no editor impl");
        f.read_to_string(&mut content).unwrap();
        let hb_face = hb_font_face(&font);
        let hb_font = hb_font(hb_face);
        let timer = std::time::Instant::now();
        let i = runs;
        for i in 0..i {
            shape_run(&hb_font, &content);
        }
        let e = timer.elapsed();
        println!("{:?} iterations took {:?}, avg {:?}", i, e, e / i);
    }

    pub fn benchmark_line_by_line(font: &Font, runs: u32){
        let mut f = File::open("../../dev/res/EditorImpl.java").expect("no editor impl");
        let r = std::io::BufReader::new(f);
        let lines = r.lines().map(|l| l.unwrap()).collect::<Vec<_>>();
        let hb_face = hb_font_face(&font);
        let hb_font = hb_font(hb_face);
        let timer = std::time::Instant::now();
        let i = runs;
        for i in 0..i {
            for l in &lines {
                shape_run(&hb_font, l);
            }

        }
        let e = timer.elapsed();
        println!("{:?} iterations took {:?}, avg {:?}", i, e, e / i);
    }

    pub fn layout_correctness() {
        let content = String::from("abc->abc");
//        let path = PathBuf::from("resources/Fira Code/ttf/FiraCode-Retina.ttf");
//        let font = FontHandle::from_path(path, 0).load().expect("error loading font");
        let source = SystemSource::new();
        let font = source
            .select_by_postscript_name("FiraCode-Regular").expect("font not found")
            .load().expect("failed to load font");

        let hb_face = hb_font_face(&font);
        let hb_font = hb_font(hb_face);
        println!("done {:?}", shape_run(&hb_font, &content))
    }

    pub fn list_fonts() {
        let source = SystemSource::new();
        println!("{:?}", source
            .select_family_by_name("Menlo")
            .expect("no such font family installed")
            .fonts()
            .iter()
            .map(|f| f.load().unwrap().postscript_name())
            .collect_vec()
        );
    }

    pub fn system_font(postscript_name: &str) -> Font {
        let source = SystemSource::new();
        source
            .select_by_postscript_name(postscript_name).expect("font not found")
            .load().expect("failed to load font")
    }

    pub fn all_fonts_bench() {
        let fira_code = system_font("FiraCode-Regular");
        let menlo = system_font("Menlo-Regular");
        let verdana = system_font("Verdana");
        let jetbrains_mono = system_font("JetBrainsMono-Regular");
        let fira_code2_path = PathBuf::from("../../frontend/resources/Fira Code/ttf/FiraCode-Regular.ttf");
        let fira_code2 = FontHandle::from_path(fira_code2_path, 0).load().expect("error loading font");

        println!("shaping with {:?} whole, is_monospace: {:?}", verdana, verdana.is_monospace());
        benchmark_layout(&verdana, 100);
        println!("shaping with {:?} line by line, is_monospace: {:?}", verdana, verdana.is_monospace());
        benchmark_line_by_line(&verdana, 100);
        println!();

        println!("shaping with {:?} whole, is_monospace: {:?}", menlo, menlo.is_monospace());
        benchmark_layout(&menlo, 100);
        println!("shaping with {:?} line by line, is_monospace: {:?}", menlo, menlo.is_monospace());
        benchmark_line_by_line(&menlo, 100);
        println!();

        println!("shaping with Fira Code v2 whole, is_monospace: {:?}", fira_code2.is_monospace());
        benchmark_layout(&fira_code2, 100);
        println!("shaping with Fira Code v2 line by line, is_monospace: {:?}", fira_code2.is_monospace());
        benchmark_line_by_line(&fira_code2, 100);
        println!();

        println!("shaping with {:?} whole, is_monospace: {:?}", jetbrains_mono, jetbrains_mono.is_monospace());
        benchmark_layout(&jetbrains_mono, 100);
        println!("shaping with {:?} line by line, is_monospace: {:?}", jetbrains_mono, jetbrains_mono.is_monospace());
        benchmark_line_by_line(&jetbrains_mono, 100);
        println!();
    }

    pub fn font_fallback(){
        let font = system_font("FiraCode-Regular");
        let fallback_result = font.get_fallbacks("hello😁", "en-US");
        println!("fallback result {:?}", fallback_result);
        for font in &fallback_result.fonts {
            println!("font: {}", font.font.full_name());
        }
    }
}

pub fn main() {
//    let mut input = String::new();
//    std::io::stdin().read_line(&mut input).unwrap();

    playground::font_fallback();
}
