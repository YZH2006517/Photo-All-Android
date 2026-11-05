// From vkdt denoise/noise.glsl (c) johannes hanika, BSD 2-Clause. Values here are
// normalised (black 0, white 1) and edges are 0, so this is sqrt(a + b x).
vec3 noise_sigma(float noise_a, float noise_b, float val)
{
  return clamp(vec3(sqrt(noise_a + max(0.0, val) * noise_b)), vec3(1e-3), vec3(1e3));
}
