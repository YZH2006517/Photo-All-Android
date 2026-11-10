# HDR+ burst align and merge

These compute shaders are a Vulkan port of the align and merge stages of
[hdr-plus-swift](https://github.com/martin-marek/hdr-plus-swift) ("Burst Photo",
Martin Marek, Christian Bauer and contributors), which is licensed under the
GNU General Public License v3.0. The port is distributed under the same licence
(see `LICENSE` at the root of this repository).

The algorithms follow:

- S. W. Hasinoff et al., "Burst photography for high dynamic range and low-light
  imaging on mobile cameras", SIGGRAPH Asia 2016
- O. Liba et al., "Handheld Mobile Photography in Very Low Light", SIGGRAPH Asia 2019
- A. Monod, J. Delon, T. Veit, "An Analysis and Implementation of the HDR+ Burst
  Denoising Method", IPOL 2021

| Shader | From hdr-plus-swift |
| --- | --- |
| `hp_quad.comp` | `prepare_texture_bayer`, `convert_to_rgba` (texture.metal) |
| `hp_pyramid.comp` | `avg_pool_normalization`, `blur_mosaic_texture`, `build_pyramid` |
| `hp_align.comp` | `correct_upsampling_error`, `compute_tile_differences`, `find_best_tile_alignment` (align.metal) |
| `hp_warp.comp` | `warp_texture_bayer` (align.metal) |
| `hp_merge.comp` | `merge_frequency_domain`, `forward_fft`, `backward_fft`, `calculate_highlights_norm_rgba` (frequency.metal) |
| `hp_bayer.comp` | `convert_to_bayer` (texture.metal) |

## Changes

- Metal to GLSL compute for Vulkan; one workgroup of 64 threads per 8x8 merge tile,
  the Fourier transforms done in shared memory, and the whole burst merged per tile in
  one dispatch (no frequency-domain textures in memory).
- Frames are black-subtracted and normalised to white before alignment and merging;
  exposure differences come from the measured exposure ratio of each frame.
- The merge's noise term is the DNG noise model of each frame at the tile's
  brightness, times a strength, instead of the tile RMS and the app's
  noise-reduction setting; the window's energy is accounted for.
- The motion measure is the residual after the best sub-pixel shift (Parseval)
  against what noise alone leaves, instead of a separate spatial mismatch pass with
  a frame-wide normalisation; the extra denoising of agreeing tiles depends on the
  noise at mid grey.
- The Fourier shift theorem uses signed frequencies.
- Warping weights run the full 0..1 between tile centres.
- Not ported: hot pixel suppression, the deconvolution and tile-border passes, the
  spatial-domain merge, X-Trans support, exposure correction (the develop does its
  own tone mapping).
