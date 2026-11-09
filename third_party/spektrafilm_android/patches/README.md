# LutCam checked GPU build overlays

GPL-3.0-only. Film modeling powered by Spektrafilm; the local optimization is part
of LutCam's integration. Base Android engine commit:
`15fb120996f03fc1e58a9a9d819e335f65d7a092`.

The files under `../engine/` are the original pinned copies. CMake runs
`apply_overlay.py` with Python 3 (stdlib only), verifies their original SHA-256,
checks each exact insertion anchor and writes six generated translation units in
the build directory. The engine executable **does contain local modifications**:

- The common sRGB linear scanner tail may run additive viewing glare, reflected
  separable FIR, unsharp mixing, the existing CAT02 round-trip matrix and sRGB
  encoding/clipping in two dispatches and one fenced submission.
- This avoids the full-size f32-to-f64 scan plane, f64 unsharp copy and separate
  blur/mix host round trips for this tail. The spectral scan currently still
  returns its f32 plane to the host; the whole film graph is not GPU resident.
- The new scratch is device-local preferred and participates in the original
  process memory coordinator. Mapped source/output/glare and scratch buffers grow
  lazily and reuse the existing Vulkan context, queue, lock and pipeline builder.
- A small on-device independent double-precision reference checks HDR/negative
  linear values, glare, transfer/matrix, odd sizes and one-pixel axes. A failure
  disables the optimization for the process. Unsupported settings, range or
  allocation failures leave caller output unchanged and retain the old tail.
- Eligible settings: encoded sRGB, legacy gamut mode, no optional custom tone
  curve or scanner lens blur, unsharp sigma below 1.5 pixels and radius <=4.
  Defaults fit; any other settings continue through the original implementation.
- The buffer destroy helper releases VkBuffer before its bound memory, so resource
  growth and failure recovery follow Vulkan lifetime requirements.
- A per-thread policy enables controlled old/new comparisons without changing
  model settings. Per-render counters expose engagement, self-check error, host
  bytes, upload/compute-wait/readback time, dispatches and submissions.

All model profiles, effects and output resolution remain enabled/unchanged. This
f32 spatial/encoding implementation is tolerance bounded, not byte-identical to
all float64 operations. Qualify real exports against the previous GPU-export
path, including stochastic effects, and do not change the old CPU oracle route.
The on-device failure checks are not a substitute for device-wide validation.

Rebuild the embedded shader with Python 3 and Android NDK glslc:

```sh
python3 tools/film_lut/build_scan_tail_shader.py /path/to/ndk/shader-tools/HOST/glslc
```

`shader_sha256.json` records GLSL and compiled SPIR-V checksums. The generator and
embedded shader are included in the source distribution. `overlay_sha256.json`
records local sources; original source hashes remain in `../UPSTREAM_SHA256.json`.

## Resident grain and scan transfers (2026-10-22 second pass)

The filming overlay offers default sublayer grain to a resident chain before
allocating the CPU nine-channel plane. The original pinned filming-stage and
particle-sampler SPIR-V are reused byte-for-byte; cell derivation, counter RNG,
seeds, dye-cloud blur taps and normal-branch policy stay the same. Missing layer
values are sanitized to zero on the GPU, matching the old sampler host upload.
A local shader then subtracts density_min and applies the reflected final FIR.
Five dispatches share one command buffer, one submission and one fence. Every
intermediate stays on the device. The nine-channel scratch is reused for the
vertical FIR after the sampler has finished reading it, with an explicit
read-to-write execution dependency. One mapped RGB buffer carries input and,
after all reads have finished, the final output. At 4096x3072 this removes the
432 MiB host layer allocation and its 864 MiB readback-plus-upload; it does not
remove the 432 MiB device scratch. All buffers use the existing memory budget.

Eligibility requires fast GPU sampling, active sublayer grain, no active
micro-structure, supported particle radii and final blur sigma below 1.5 (radius
<=4), valid geometry/storage ranges and a successful first-use check. Explicit
SPK_GPU_BLUR=0 is respected. Other settings use the unchanged old path, including
its micro-structure and wider blur. Refusals publish no output. A process-once
small real-profile comparison against the original GPU sequence checks every
output and the out-of-branch counter before enabling the chain. Full-model
20-profile and same-RAW Bitmap comparisons are separate qualifications, not a
claim of strict CPU/GPU stochastic identity. The old sampler was already the
GPU-export normal approximation; this change does not introduce that model.

The scan tail also offers parallel finite checks and staging copies. Upload may
write private staging while validating, but no caller output is written until
a completed readback has passed finite checks. This changes no pixel arithmetic.
Independent per-thread switches support old/new comparisons. The second-pass
A/B toggles grain residency and parallel scan transfers together, while retaining
the first-pass scan shader for both routes. The original grain sampler's missing
submission counter is corrected in the generated build, for accurate accounting.

Rebuild the additional checked-in shader:

```sh
python3 tools/film_lut/build_grain_tail_shader.py /path/to/ndk/shader-tools/HOST/glslc
```

`grain_shader_sha256.json` checks GLSL and embedded SPIR-V during configuration.
The source distribution includes both shader generators and both implementations.

## Print tail, DIR transport and parallel spatial channels (third pass)

The print overlay, after the existing qualified GPU spectral integral and only
when enlarger optical diffusion is off, directly evaluates the two existing
clamp/floor seams and the final log10 in f64. It avoids the full f64 intermediate
and the redundant log10/pow10 round trip, retaining midgray, preflash,
print-exposure and black/white correction factors. Optical diffusion uses the
old implementation. This is algebraically equivalent, not a universal claim of
bit identity across all floating-point inputs; paired real exports qualify it.

The DIR overlay retains the existing small f64 curve construction, matrix and
CPU f64 interpolation. Its correction is rounded to f32 at exactly the old GPU
upload seam, passed through the same GPU diffusion algorithm, and expanded to
f64 scalars at the same interpolation seam. Host correction and diffused planes
are f32 rather than f64, removing 288 MiB of host scratch at 4096x3072. Failure
keeps the old complete route. The CPU oracle is unchanged.

The local spatial shader derives from the pinned halation shader with original
attribution/license. It changes only the IIR lane mapping: (column,channel) has
its own thread, rather than a thread iterating three independent channels. Host
IIR dispatch groups are tripled. Coefficients, f64 host derivation, high/low
state, Dekker products, FIR, transpose, boundaries and blends are preserved.
The original pipeline remains available in the same context. A mutex-protected
first-use comparison exercises odd 127x93 geometry, mixed FIR/IIR and sigmas
3,12,63,256 before enabling the new pipeline. Failed checks or unsupported
extra dispatch ranges keep the original. The checker runs before outer-request
buffer/table setup, so recursive checks cannot corrupt that request's data.

An experimental native FMA-residual variant failed this phone's primitive
precision check and was removed. The shipped shader does not use that variant
and does not replace double-float states with f32 recursion.

Rebuild the local spatial shader from the checked original:

```sh
python3 tools/film_lut/build_spatial_parallel_shader.py /path/to/ndk/shader-tools/HOST/glslc
```

`spatial_shader_sha256.json` is checked during configuration. The third-pass
A/B toggles these three changes together with both previous optimizations kept
on. The whole RAW-to-film pipeline is still CPU/Vulkan hybrid. See VALIDATION.json
in the source distribution for measurements and single-device limitations.

## Shared grain/scanner workspace (fourth pass)

The scan tail can borrow completed grain-chain buffers while holding the same
GPU mutex. It uploads linear RGB into the grain chain's mapped RGB buffer, copies
those bits into its device-local accumulated RGB buffer, then uses the grain
nine-channel scratch for the vertical scan FIR and the mapped buffer for final
encoded output. The source remains separate from output throughout both passes;
there is no simultaneous read/write aliasing of input pixels. One extra device
copy uses explicit TRANSFER_DST buffer usage and host/transfer/compute barriers,
inside the same fenced submission. All scan/grain shader bytes and arithmetic
are unchanged. No whole model is computed during preview or filter selection.

At 4096x3072 this avoids growing three additional 144MiB scan buffers (432MiB).
Small private self-check buffers may remain. This is an allocation/capacity saving,
not a measured operating-system RSS reduction. The glare buffer remains separate.
Eligibility requires successful current-render resident grain and adequate live
buffer capacities; otherwise the existing private scan workspace is used. A
per-thread policy supports controlled old/new runs. Refusals still preserve the
caller's CPU output; borrowed storage belongs to the GPU context and is never
freed by the scanner. The queue/fence and mutex bound its lifetime.

Diagnostics now expose scan allocation time, actual sharing and avoided capacity.
Fresh-process A/B runs include the first full-size render and first-use checks,
then a warm repeat; image hashes are calculated after timing. This separates
allocation/cold-path effects from warmed throughput. No RAW/JPEG/gallery speedup
is inferred from this model-only test. See the new validation report.


The fourth-pass switch also enables exact auto-exposure preview sampling in
`meter_parallel.inc`, inserted into generated `autoexposure.cpp`. It caches only
integer reflected tap indices and distributes independent output pixels among
workers. The original f64 per-pixel horizontal/vertical summation order, Gaussian
coefficients, nearest/round-half-even coordinates, preview dimensions and AE
policy remain unchanged. The baseline branch remains available. Host tests
compare f32-input AE EV bits at 1/4 workers on narrow, odd and 12MP inputs, plus
f64 preview sample bits. This is not reduced sampling or a different exposure.

On HONOR BKQ_AN10, eight fresh instrumented processes in ABBA/BAAB order, one
first full-size render plus one warm repeat each, compared the combined fourth
pass against the preceding three passes. Same 4096x3072 RAW-linear Portra 800:
first-render median 3157 -> 2894ms (8.3%); warm median 2566 -> 2325ms (9.4%).
Preprocess median was 208 -> 56ms cold, 302 -> 77ms warm. Cold scan allocation
median was 55 -> 6ms. All 16 RGB8 hashes matched; all 20 small full-model fixtures
had zero float/RGB8 difference. These are observed-input agreements, not universal
float identity. Model/Bitmap timing excludes engine creation, fixture loading,
RAW development, rotation, JPEG and gallery. Temperature/background activity were
not controlled, and one optimized late run was slower; see per-run evidence.
The earlier workspace-only experiment saved capacity but only ~1.3% cold model
wall time in that session. Do not attribute the combined gain solely to storage.


## Partial resident expose/halation chain and fused output (fifth pass)

`expose_chain_hook.inc` uses the original exposed-irradiance, complete halation/
scatter and exposure-log shader operations, retaining their f32 rounding seams,
while the intermediate image stays in a private resident ping-pong pair. A
single GPU mutex covers upload, all three stages, readback, validation and close.
The locked filming helper always rebinds descriptors to the current front;
explicit host/previous-write -> shader-read/write barriers protect each stage.
Original pinned shader bytes, model coefficients, RNG and spatial operations are
unchanged. The existing halation kernel still has its device-local work/copies;
this is partial residency, not a whole-model GPU graph or one submission.

The complete caller output is published only after successful finite readback.
A scope guard closes residency on every return/exception, so unsupported settings,
nonfinite input and allocation/dispatch failures retain the original full path
and cannot leave a stale frame for subsequent stages. A first-use 127x93 check
compares the old three-stage GPU sequence against the chain before allowing it.
The hook requires GPU expose/halation, active halation, no highlight boost, no
creative camera diffusion/lens blur and unit BW correction. Other settings keep
the full existing route. At 12MP, two 288MiB host f64 transient planes are avoided;
the new resident pair and mapped staging have their own capacities. This is not
a net process-RSS claim; existing later-stage allocations may remain.

`film_output_transform.h` combines original RGB8 quantization with exact integer
crop/0-90-180-270-degree rotation in parallel disjoint 32x32 tiles. The complete
film model still processes the full captured image BEFORE crop/rotation, exactly
as before. Output dimensions match the original crop; no downsample, interpolation
or change to JPEG quality. This avoids a separate full-frame Java Bitmap rotation
and its extra Bitmap. The classic output route is unchanged. Host tests check
independently enumerated cropped pixel labels at all four angles, row padding,
alpha, invalid geometry, and parallel output against original quantization.

The fifth A/B switch covers this resident chain and fused/parallel output. Both
sides retain all four previous passes. New headless `fifthFixture` trials end at
the equivalent portrait Bitmap: the baseline includes its original Java rotation;
the optimized side directly packs portrait pixels. Engine initialization, RAW
loading/development, JPEG and gallery are outside timing; hashes are outside
measurement. All 20 model fixtures and a fixed full RAW are separately checked.
A smaller IIR workgroup experiment showed no stable extra gain and is not shipped.
See VALIDATION.json for measured results and limitations.


## Mapped DIR transport (sixth pass)

`dir_mapped.inc` keeps the existing f64 silver/high-exposure correction, matrix
sum, f32 conversion seam, f64 curve setup and final interpolation verbatim. It
writes correction directly into the prior resident frame's mapped staging, runs
the original complete diffusion shaders with resident device copies, then reads
back into that same mapping and interpolates directly into the caller's density
plane. The frame pair/staging must already have sufficient capacity; the new
route does not grow additional full-size buffers. Otherwise it retains the old
`dir_f32.inc` route. At 4096x3072 it avoids the two private 144MiB correction and
readback vectors and their copies/value-initialization (288MiB transient host
capacity). This is not an operating-system RSS measurement or new approximation.

The shared queue mutex and fence cover fill/compute/readback/finish. Output is
not touched until finite readback succeeds. A scope guard closes residency on
every exit, including callback exceptions. CPU callbacks run synchronously and
must do no GPU work. Callback exceptions propagate to the rendering boundary;
they are never swallowed as a false return followed by a CPU retry on partially
written aliased output. Invalid/inactive inputs and GPU refusals never call the
finish callback. A resident readback now orders prior transfer OR shader writes
before its transfer read (the former result can come from a diffusion copy).

A first-use old/new transport check covers odd geometry, mixed FIR/IIR channels
and sigma .65/3/12/63/256 with exact float comparison. Separate 20-stock complete
model fixtures check stochastic exports; explicit tests cover nonfinite fill,
GPU sigma rejection after upload, prepare/finish cancellation, residency closure
and successful recovery against the original diffusion. No shader/RNG/model
parameter changes or photo resolution/effect reduction are involved.

The sixth A/B retains all five previous passes, including fused portrait output,
on both routes. Eight fresh processes in ABBA/BAAB order each render the same
full RAW-linear fixture twice (first full-size and warm repeat). Both end at the
same portrait Bitmap; hashes are outside timing. Engine setup, fixture loading,
RAW development, JPEG/gallery are excluded. See VALIDATION.json for measurements,
per-phase CPU/GPU details and single-device limitations.


## Padded spatial transpose (seventh pass)

Only the transpose dispatch (op 17) uses a new pipeline with the original shared
32x32 tile padded to a row stride of 33 floats. Pixel indices, operations and
barriers are unchanged. IIR/FIR dispatches still use the previous pipeline; all
coefficients, double-float state, reflected edges, RNG and model parameters stay
unchanged. Padding is intended to reduce shared-memory bank conflicts; the
measured device timings do not independently establish that hardware cause.

A process-once exact old/new diffusion comparison gates the pipeline, under the
existing GPU mutex and before any resident frame opens. It covers 1-pixel axes,
narrow/odd and partial-edge tiles (up to 271x257), mixed FIR/IIR channels and
sigmas .65, 2.999, 12 and 256. A refusal or failed check retains the existing
complete pipeline. Pipeline and shader objects follow the existing context
lifetime. Separate full-model/RAW comparisons and live checks qualify exports.

Rebuild the additional checked shader:

```sh
python3 tools/film_lut/build_spatial_padded_shader.py /path/to/ndk/shader-tools/HOST/glslc
```

`spatial_padded_sha256.json` verifies GLSL and embedded SPIR-V on configuration.
Four shader generators are included in the corresponding source snapshot.
An alternate shared FIR-coefficient experiment produced less than 0.3% overall
gain in its first interleaved block and is not shipped. Only transpose padding
is added in this pass. The full pipeline remains CPU/Vulkan hybrid.
