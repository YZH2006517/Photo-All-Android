# Raw denoise (ported from vkdt)

Adapted from vkdt's `denoise` module (`src/pipe/modules/denoise`, revision in
`../vkdt/REVISION`), copyright 2019 johannes hanika, 2-clause BSD licence
(`../vkdt/LICENCE.bsd2`). `README.vkdt.md` is the module's own documentation.

Pipeline for a Bayer mosaic: `dn_half` (2x2 -> half-size rgb), `dn_downcov` and
three `dn_down` (four levels of edge-aware a-trous wavelets, each writing its result
sorted into four sub-images), `dn_assemble` (noise-normalised soft shrinkage of the
coefficients), `dn_doub` (the finest band on the full mosaic).

Changes against vkdt:
- input is the engine's integer mosaic (one raw or the burst merge), normalised on
  read with per-position black and white, for any 2x2 Bayer order (vkdt: rggb after
  crop, unorm16 texture);
- all values are normalised, so the noise model is the DNG NoiseProfile directly
  (variance = noise_a + noise_b x, x in [0, 1]);
- parameters are push constants instead of a uniform buffer; `edges` is fixed at 0;
- write-only images declare their format (rgba16f / r32ui);
- `dn_doub` writes the denoised mosaic as integers (value * scale) for the engine's raw
  pass, and leaves the lens-shading gain map to that pass;
- X-Trans paths removed.
`dn_cov.glsl` is vkdt's `cov.glsl` unchanged.
