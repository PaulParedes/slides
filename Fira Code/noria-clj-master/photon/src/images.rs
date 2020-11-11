use fxhash::FxHashMap;
use std::sync::Arc;
use image::{load_from_memory, ImageResult, DynamicImage, GenericImageView};

pub type ImageId = i64;

pub struct Image {
    // rgba8 bytes
    pub raw_bytes: Arc<Vec<u8>>,
    pub width: u32,
    pub height: u32,
    pub is_opaque: bool
}

impl Image {
    pub fn from_bytes(data: &[u8]) -> ImageResult<Self> {
        let image = load_from_memory(data)?;
        let mut rgba = match image {
            DynamicImage::ImageRgba8(rgba) => rgba,
            image => image.to_rgba()
        };
        let is_opaque = rgba8_premultiply_inplace(&mut rgba);
        Ok(Image {
            raw_bytes: Arc::new((&rgba).to_vec()),
            width: rgba.width(),
            height: rgba.height(),
            is_opaque
        })
    }
}

#[derive(Default)]
pub struct ImagesContainer {
    pub images: FxHashMap<ImageId, Image>
}

pub fn multiply_u8_color(a: u8, b: u8) -> u8 {
    return (a as u32 * b as u32 / 255) as u8;
}

/// Returns true if the pixels were found to be completely opaque.
pub fn rgba8_premultiply_inplace(pixels: &mut [u8]) -> bool {
    assert!(pixels.len() % 4 == 0);
    let mut is_opaque = true;
    for rgba in pixels.chunks_mut(4) {
        rgba[0] = multiply_u8_color(rgba[0], rgba[3]);
        rgba[1] = multiply_u8_color(rgba[1], rgba[3]);
        rgba[2] = multiply_u8_color(rgba[2], rgba[3]);
        is_opaque = is_opaque && rgba[3] == 255;
    }
    is_opaque
}
