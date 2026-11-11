"""Generate checked build overlays; never modify the pinned upstream files."""
import hashlib,json,sys,re,struct
from pathlib import Path
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
checks=json.loads((root/'UPSTREAM_SHA256.json').read_text())

shader=root/'patches'
sha=json.loads((shader/'shader_sha256.json').read_text())
words=[int(v,16) for v in re.findall(r'0x[0-9a-fA-F]{8}',(shader/'scan_tail_spv.h').read_text())]
if hashlib.sha256((shader/'scan_tail.comp').read_bytes()).hexdigest()!=sha['glsl'] or \
        hashlib.sha256(struct.pack('<'+'I'*len(words),*words)).hexdigest()!=sha['spirv']:
    raise RuntimeError('Scan-tail shader/header checksum mismatch; regenerate with build_scan_tail_shader.py')

sha=json.loads((shader/'grain_shader_sha256.json').read_text())
words=[int(v,16) for v in re.findall(r'0x[0-9a-fA-F]{8}',(shader/'grain_tail_spv.h').read_text())]
if hashlib.sha256((shader/'grain_tail.comp').read_bytes()).hexdigest()!=sha['glsl'] or hashlib.sha256(struct.pack('<'+'I'*len(words),*words)).hexdigest()!=sha['spirv']:
    raise RuntimeError('Grain-tail shader/header checksum mismatch')

sha=json.loads((shader/'spatial_shader_sha256.json').read_text())
words=[int(v,16) for v in re.findall(r'0x[0-9a-fA-F]{8}',(shader/'spatial_parallel_spv.h').read_text())]
if hashlib.sha256((shader/'spatial_parallel.comp').read_bytes()).hexdigest()!=sha['glsl'] or hashlib.sha256(struct.pack('<'+'I'*len(words),*words)).hexdigest()!=sha['spirv']:
    raise RuntimeError('Spatial shader checksum mismatch')

sha=json.loads((shader/'spatial_padded_sha256.json').read_text())
words=[int(v,16) for v in re.findall(r'0x[0-9a-fA-F]{8}',(shader/'spatial_padded_spv.h').read_text())]
if hashlib.sha256((shader/'spatial_padded.comp').read_bytes()).hexdigest()!=sha['glsl'] or hashlib.sha256(struct.pack('<'+'I'*len(words),*words)).hexdigest()!=sha['spirv']:
    raise RuntimeError('Padded transpose shader checksum mismatch')

def source(name):
    p=root/'engine'/name
    key='third_party/spektrafilm_android/engine/'+name
    if hashlib.sha256(p.read_bytes()).hexdigest()!=checks[key]:raise RuntimeError('Unexpected upstream source: '+name)
    return p.read_text()

def replace(text,anchor,replacement):
    if text.count(anchor)!=1:raise RuntimeError('Overlay anchor changed: '+anchor[:100])
    return text.replace(anchor,replacement)

def write(name,text):
    p=out/name
    p.parent.mkdir(parents=True,exist_ok=True)
    if not p.exists() or p.read_text()!=text:p.write_text(text)

s=source('gpu/vulkan_compute.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"', '''#include "gpu/vulkan_compute.h"
#ifdef __APPLE__
#include <TargetConditionals.h>
#if TARGET_OS_IOS
#include <charconv>
#endif
#endif''')
s=replace(s,'        ici.pApplicationInfo = &app;', '''        ici.pApplicationInfo = &app;
#ifdef __APPLE__
        // A Vulkan loader exposes portability_enumeration, while statically
        // linked iOS MoltenVK may expose devices directly without that extension.
        uint32_t instanceExtensionCount = 0;
        if (vkEnumerateInstanceExtensionProperties(nullptr,&instanceExtensionCount,nullptr) != VK_SUCCESS) return false;
        std::vector<VkExtensionProperties> instanceProperties(instanceExtensionCount);
        if (vkEnumerateInstanceExtensionProperties(nullptr,&instanceExtensionCount,instanceProperties.data()) != VK_SUCCESS) return false;
        std::vector<const char*> instanceExtensions;
        for (const auto& extension : instanceProperties) {
            if (!std::strcmp(extension.extensionName,"VK_KHR_portability_enumeration")) {
                instanceExtensions.push_back("VK_KHR_portability_enumeration");
                ici.flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
            }
            if (!std::strcmp(extension.extensionName,"VK_KHR_get_physical_device_properties2"))
                instanceExtensions.push_back("VK_KHR_get_physical_device_properties2");
        }
        ici.enabledExtensionCount = uint32_t(instanceExtensions.size());
        ici.ppEnabledExtensionNames = instanceExtensions.data();
#endif''')
s=replace(s,'        dci.pQueueCreateInfos = &qci;', '''        dci.pQueueCreateInfos = &qci;
#ifdef __APPLE__
        uint32_t deviceExtensionCount = 0;
        if (vkEnumerateDeviceExtensionProperties(phys,nullptr,&deviceExtensionCount,nullptr) != VK_SUCCESS) return false;
        std::vector<VkExtensionProperties> deviceProperties(deviceExtensionCount);
        if (vkEnumerateDeviceExtensionProperties(phys,nullptr,&deviceExtensionCount,deviceProperties.data()) != VK_SUCCESS) return false;
        const char* portability = "VK_KHR_portability_subset";
        for (const auto& extension : deviceProperties) {
            if (!std::strcmp(extension.extensionName,portability)) {
                dci.enabledExtensionCount = 1;
                dci.ppEnabledExtensionNames = &portability;
                break;
            }
        }
#endif''')
s=replace(s,'#include "runtime/stage_timer.h"','#include "runtime/stage_timer.h"\n#include "scan_tail.h"\n#include "grain_chain.h"\n#include "third_pass.h"\n#include "fifth_pass.h"\n#include "sixth_pass.h"\n#include "seventh_pass.h"')
anchor='PointwiseDispatchGrid plan_pointwise_dispatch('
s=replace(s,anchor,'''namespace { thread_local bool lutcam_third_enabled=true; thread_local ThirdPassDiagnostics lutcam_third_diagnostics; thread_local bool lutcam_seventh_enabled=true; thread_local SeventhPassDiagnostics lutcam_seventh_diagnostics; thread_local bool lutcam_sixth_enabled=true; thread_local SixthPassDiagnostics lutcam_sixth_diagnostics; thread_local bool lutcam_fifth_enabled=true; thread_local FifthPassDiagnostics lutcam_fifth_diagnostics; thread_local bool lutcam_shared_enabled=true; thread_local bool lutcam_tail_enabled=true; thread_local bool lutcam_scan_parallel_transfer=true; thread_local ScanTailDiagnostics lutcam_tail_diagnostics; thread_local bool lutcam_grain_enabled=true; thread_local GrainChainDiagnostics lutcam_grain_diagnostics; }
void set_third_pass_enabled(bool enabled){lutcam_third_enabled=enabled;}
bool third_pass_enabled(){return lutcam_third_enabled;}
void reset_third_pass_diagnostics(){lutcam_third_diagnostics={};}
ThirdPassDiagnostics third_pass_diagnostics(){return lutcam_third_diagnostics;}
void note_print_tail(){lutcam_third_diagnostics.print_tail=true;}
void note_dir_f32(){lutcam_third_diagnostics.dir_f32=true;}
void set_scan_parallel_transfer_enabled(bool enabled){lutcam_scan_parallel_transfer=enabled;}
bool scan_parallel_transfer_enabled(){return lutcam_scan_parallel_transfer;}
void set_grain_chain_enabled(bool enabled){lutcam_grain_enabled=enabled;}
void reset_grain_chain_diagnostics(){lutcam_grain_diagnostics={};}
GrainChainDiagnostics grain_chain_diagnostics(){return lutcam_grain_diagnostics;}
void set_seventh_pass_enabled(bool enabled){lutcam_seventh_enabled=enabled;}
bool seventh_pass_enabled(){return lutcam_seventh_enabled;}
void reset_seventh_pass_diagnostics(){lutcam_seventh_diagnostics={};}
SeventhPassDiagnostics seventh_pass_diagnostics(){return lutcam_seventh_diagnostics;}
void set_sixth_pass_enabled(bool enabled){lutcam_sixth_enabled=enabled;}
bool sixth_pass_enabled(){return lutcam_sixth_enabled;}
void reset_sixth_pass_diagnostics(){lutcam_sixth_diagnostics={};}
SixthPassDiagnostics sixth_pass_diagnostics(){return lutcam_sixth_diagnostics;}
void set_fifth_pass_enabled(bool enabled){lutcam_fifth_enabled=enabled;}
bool fifth_pass_enabled(){return lutcam_fifth_enabled;}
void reset_fifth_pass_diagnostics(){lutcam_fifth_diagnostics={};}
FifthPassDiagnostics fifth_pass_diagnostics(){return lutcam_fifth_diagnostics;}
void set_fourth_pass_enabled(bool enabled){lutcam_shared_enabled=enabled;}
bool fourth_pass_enabled(){return lutcam_shared_enabled;}
void set_shared_workspace_enabled(bool enabled){lutcam_shared_enabled=enabled;}
bool shared_workspace_enabled(){return lutcam_shared_enabled;}
void set_scan_tail_enabled(bool enabled){lutcam_tail_enabled=enabled;}
bool scan_tail_enabled(){return lutcam_tail_enabled;}
void reset_scan_tail_diagnostics(){lutcam_tail_diagnostics={};}
ScanTailDiagnostics scan_tail_diagnostics(){return lutcam_tail_diagnostics;}

'''+anchor)
s=replace(s,'bool available() { return false; }','bool available() { return false; }\nbool scan_tail(const float*,const float*,int,int,const double*,const double*,double,double,float*){return false;}\nbool grain_chain(const float*,int,int,const float*,const float*,int,bool,double,const GrainParams&,float*){return false;}\nbool halation_scatter_f32(const HalationScatterRequest&,float*,HalationScatterDiagnostics*){return false;}\nbool expose_halation_chain(const double*,const float*,double,int,int,const double*,int,double,const HalationScatterRequest&,float*){return false;}\nbool mapped_dir_diffusion(const HalationScatterRequest&,MappedPrepare,MappedFinish,void*){return false;}')
s=replace(s,'bool available() { return false; }','bool available() { return false; }\nbool prefer_spatial_gpu() { return false; }')
anchor='''bool available() {
    std::lock_guard<std::mutex> lk(gpu_mutex());
    return ctx().ok;
}'''
s=replace(s,anchor,anchor+'''

bool prefer_spatial_gpu() {
#if defined(__APPLE__) && TARGET_OS_IOS
    std::lock_guard<std::mutex> lock(gpu_mutex());
    const Ctx& c = ctx();
    // Use the CPU for halation and DIR diffusion until the A18 generation.
    // MoltenVK reports names such as "Apple A18 Pro GPU".
    constexpr char prefix[] = "Apple A";
    constexpr size_t prefixLength = sizeof(prefix) - 1;
    const char* name = c.properties.deviceName;
    if (!c.ok || std::strncmp(name, prefix, prefixLength) != 0) return false;

    unsigned generation = 0;
    const char* end = name + std::strlen(name);
    const auto parsed = std::from_chars(name + prefixLength, end, generation);
    return parsed.ec == std::errc{} && generation >= 18 &&
           (parsed.ptr == end || *parsed.ptr == ' ');
#else
    return true;
#endif
}''')
# A resident diffusion result was written by transfer, not by compute.
s=replace(s,'pre.srcAccessMask = toDevice ? VK_ACCESS_HOST_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;',
    'pre.srcAccessMask = toDevice ? VK_ACCESS_HOST_WRITE_BIT : VK_ACCESS_MEMORY_WRITE_BIT;')
s=replace(s,': VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,\n                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &pre,',
    ': VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,\n                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &pre,')
# Resource growth/fallback must destroy a bound VkBuffer before freeing its memory.
s=replace(s,'''        if (b.mem) { vkFreeMemory(device, b.mem, nullptr); b.mem = VK_NULL_HANDLE; }
        if (b.buf) { vkDestroyBuffer(device, b.buf, nullptr); b.buf = VK_NULL_HANDLE; }''','''        if (b.buf) { vkDestroyBuffer(device, b.buf, nullptr); b.buf = VK_NULL_HANDLE; }
        if (b.mem) { vkFreeMemory(device, b.mem, nullptr); b.mem = VK_NULL_HANDLE; }''')
s=replace(s,'    Kernel scanLin;    // scan_spectral_lin.comp (density -> unclipped linear RGB)','    Kernel scanLin;    // scan_spectral_lin.comp (density -> unclipped linear RGB)\n    Kernel scanTail;\n    ResidentBuf scanTailScratch;\n    Kernel grainChainLayers,grainChainSample,grainChainTail;\n    ResidentBuf grainChainScratch,grainChainAccum;')
s=replace(s,'        destroyKernel(scanLin);','        destroyKernel(scanLin);\n        destroyKernel(scanTail);\n        destroyResidentBuf(scanTailScratch);\n        destroyKernel(grainChainLayers);destroyKernel(grainChainSample);destroyKernel(grainChainTail);\n        destroyResidentBuf(grainChainScratch);destroyResidentBuf(grainChainAccum);')
s=replace(s,"        if (!dispatch_ok) break;\n        d.slices = 1;","        if (!dispatch_ok) break;\n        stage_timing_note_gpu_submissions(1);\n        d.slices = 1;")
s=replace(s,'    struct HalationKernel {','    struct HalationKernel {\n        VkShaderModule fastShader=VK_NULL_HANDLE;\n        VkPipeline fastPipe=VK_NULL_HANDLE;\n        VkShaderModule paddedShader=VK_NULL_HANDLE;\n        VkPipeline paddedPipe=VK_NULL_HANDLE;')
s=replace(s,'        HalationKernel& k = halation;','        HalationKernel& k = halation;\n        if(k.paddedPipe){vkDestroyPipeline(device,k.paddedPipe,nullptr);k.paddedPipe=VK_NULL_HANDLE;}\n        if(k.paddedShader){vkDestroyShaderModule(device,k.paddedShader,nullptr);k.paddedShader=VK_NULL_HANDLE;}\n        if(k.fastPipe){vkDestroyPipeline(device,k.fastPipe,nullptr);k.fastPipe=VK_NULL_HANDLE;}\n        if(k.fastShader){vkDestroyShaderModule(device,k.fastShader,nullptr);k.fastShader=VK_NULL_HANDLE;}')
# Split the old helper into a locked implementation and unchanged public wrapper.
a=s.index('bool run_filming_stage(const FilmingStageRun& r,')
b=s.index('\n}  // namespace',a)
chunk=s[a:b]
chunk=replace(chunk,'bool run_filming_stage(const FilmingStageRun& r,','bool run_filming_stage_locked(Ctx& c,const FilmingStageRun& r,')
chunk=replace(chunk,'    std::lock_guard<std::mutex> lk(gpu_mutex());\n    Ctx& c = ctx();\n','')
# Resident front changes each stage; descriptors must follow that exact buffer.
chunk=replace(chunk,'if (!hadIn || !hadOut || !hadA || !hadB || resident != s.boundResident)',
                    'if (resident || !hadIn || !hadOut || !hadA || !hadB || resident != s.boundResident)')
anchor='            if (vkBeginCommandBuffer(s.cmd, &bi) != VK_SUCCESS) { dispatch_ok = false; break; }'
chunk=replace(chunk,anchor,anchor+'''
            if(resident) {
                VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
                barrier.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT;
                barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
                vkCmdPipelineBarrier(s.cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
            }''')
chunk+='\nbool run_filming_stage(const FilmingStageRun& r,FilmingStageDiagnostics* diagnostics) {\n    std::lock_guard<std::mutex> lock(gpu_mutex());return run_filming_stage_locked(ctx(),r,diagnostics);\n}\n'
s=s[:a]+chunk+s[b:]
s=replace(s,'bool halation_scatter_locked(Ctx& c, const HalationScatterRequest& r, double* out,','#include "spatial_parallel_spv.h"\n#include "spatial_parallel_impl.inc"\n#include "spatial_padded_spv.h"\n#include "transpose_padded_impl.inc"\n\nbool halation_scatter_locked(Ctx& c, const HalationScatterRequest& r, double* out,')
s=replace(s,'    d.attempted = true;\n    if (!c.ok) { d.reason = "no-device"; return false; }','    d.attempted = true;\n    if (!c.ok) { d.reason = "no-device"; return false; }\n    if(third_pass_enabled() && !lutcam_spatial_checking && lutcam_spatial_check==0)qualify_spatial_parallel(c);\n    if(seventh_pass_enabled() && !lutcam_spatial_checking && !lutcam_padded_checking && lutcam_padded_check==0 && !c.frame.open)qualify_padded_transpose(c);')
s=replace(s,'    write_halation_sets(c, k);',"""    write_halation_sets(c, k);
    bool use_parallel=false;
    if(third_pass_enabled() && (lutcam_spatial_check==1 || lutcam_spatial_checking) &&
       (uint64_t(maxDim)*3+kHalLineThreads-1)/kHalLineThreads<=lim.maxComputeWorkGroupCount[0])
        use_parallel=ensure_spatial_parallel(c,k);
    lutcam_third_diagnostics.spatial_check=lutcam_spatial_check;
    lutcam_third_diagnostics.spatial_max_error=lutcam_spatial_error;
    if(use_parallel)lutcam_third_diagnostics.spatial_parallel=true;
    const VkPipeline activePipeline=use_parallel?k.fastPipe:k.pipe;
    const bool use_padded=seventh_pass_enabled() && (lutcam_padded_check==1 || lutcam_padded_checking) && ensure_padded_transpose(c,k);
    lutcam_seventh_diagnostics.self_check=lutcam_padded_check;
    lutcam_seventh_diagnostics.self_error=lutcam_padded_error;""")
s=replace(s,'vkCmdDispatch(k.cmd, (width + kHalLineThreads - 1u) / kHalLineThreads, 1, 1);','vkCmdDispatch(k.cmd, (width * (use_parallel ? 3u : 1u) + kHalLineThreads - 1u) / kHalLineThreads, 1, 1);')
s=replace(s,'vkCmdBindPipeline(k.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, k.pipe);','const bool cached=use_padded && op==kHalOpTranspose;\n        if(cached){lutcam_seventh_diagnostics.attempted=true;lutcam_seventh_diagnostics.engaged=true;lutcam_seventh_diagnostics.reason="none";}\n        vkCmdBindPipeline(k.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cached?k.paddedPipe:activePipeline);')
anchor='''    auto dispatch = [&](uint32_t set, uint32_t op, uint32_t sigmaMode, uint32_t component,
                        uint32_t width, uint32_t height) {'''
s=replace(s,anchor,'''    bool batchOk = true;
    // MoltenVK maps a Vulkan submission to one Metal command buffer. The
    // whole spatial graph can exceed the GPU watchdog on older iPhones.
    // Keep the same full-image kernels and barriers, but yield between passes.
#if defined(__APPLE__) && TARGET_OS_IOS
    constexpr uint64_t boundedFramePixels = 4ull * 1024 * 1024;
    constexpr uint32_t maxDispatchesPerBatch = 2;
#endif
'''+anchor+'''
        if (!batchOk) return;
#if defined(__APPLE__) && TARGET_OS_IOS
        if (uint64_t(W) * H > boundedFramePixels && dispatches != 0 &&
            dispatches % maxDispatchesPerBatch == 0) {
            batchOk = submit_wait() && begin();
            if (!batchOk) return;
        }
#endif''')
anchor='''    const VkBuffer result = (scatterActive && bounceActive) ? k.work[kHalSrc].buf : k.work[kHalX].buf;
    if (!submit_wait()) { d.reason = "dispatch-failed"; return false; }'''
s=replace(s,anchor,anchor.replace('if (!submit_wait())','if (!batchOk || !submit_wait())'))
anchor='    d.resident = resident;\n    return true;\n}\n\n}  // namespace'
s=replace(s,anchor,'    d.resident = resident;\n    return true;\n}\n\n#include "spatial_parallel_check.inc"\n#include "transpose_padded_check.inc"\n\n}  // namespace')
# Last public-namespace close is followed by the feature guard.
pos=s.rfind('}  // namespace spk::gpu')
if pos<0:raise RuntimeError('Missing GPU namespace close')
s=s[:pos]+'#include "scan_tail_impl.inc"\n#include "grain_chain_impl.inc"\n#include "third_pass_impl.inc"\n#include "fifth_pass_impl.inc"\n#include "sixth_pass_impl.inc"\n\n'+s[pos:]
write('vulkan_compute.cpp',s)
s=source('spektra.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"','#include "gpu/vulkan_compute.h"\n#include "third_pass.h"')
for before,after in (
    ('fparams.allow_gpu_halation = fast_gpu_route;',
     'const bool spatial_gpu_route = fast_gpu_route && spk::gpu::prefer_spatial_gpu();\n    fparams.allow_gpu_halation = spatial_gpu_route;'),
    ('fparams.dir_couplers.allow_gpu_diffusion = fast_gpu_route;',
     'fparams.dir_couplers.allow_gpu_diffusion = spatial_gpu_route;'),
):
    if s.count(before)!=2:raise RuntimeError('Expected both scan and print routing gates: '+before)
    s=s.replace(before,after)
write('spektra.cpp',s)
s=source('runtime/stages/scanning.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"','#include "gpu/vulkan_compute.h"\n#include "scan_tail.h"')
anchor='                lin_buf.reset(new double[static_cast<size_t>(npix) * 3]);'
hook='''                // LutCam common scan tail: keep FIR/unsharp/encode on the GPU.
                // Unsupported custom settings retain the entire original tail below.
                if(params.output_color_space==SPK_CS_SRGB && params.output_cctf_encoding &&
                   params.output_gamut_compress==OutputGamutCompress::kLegacyClip &&
                   params.lens_blur<=0 && !params.tone_curve.active) {
                    double tail_glare[3]={0,0,0};
                    if(do_glare)for(int r=0;r<3;r++)for(int k=0;k<3;k++)
                        tail_glare[r]+=output_matrix[r*3+k]*illuminant_xyz[k];
                    ScopedStage tail_timer(STG_SCAN_SPATIAL);
                    if(gpu::scan_tail(gpu_lin.data(),do_glare?glare_field.data():nullptr,width,height,
                                      tail_glare,kRGB_to_RGB_CCTF[SPK_CS_SRGB],
                                      do_unsharp?params.unsharp_sigma:0,do_unsharp?params.unsharp_amount:0,rgb_out)) {
                        g_gpu_scan_frames.fetch_add(1,std::memory_order_relaxed);
                        return;
                    }
                }
'''
s=replace(s,anchor,hook+anchor)
write('scanning.cpp',s)

s=source('runtime/stages/filming.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"','#include "gpu/vulkan_compute.h"\n#include "grain_chain.h"\n#include "fifth_pass.h"\n#include "sixth_pass.h"\n#include "seventh_pass.h"')
anchor='        if (grain.sublayers_active && have_layers) {'
s=replace(s,anchor,"""        if (grain.sublayers_active && have_layers && params.allow_gpu_filming &&
            gpu::grain_chain(density_cmy_out,width,height,ndc.data(),
                film.density_curves_layers.data(),n,film.is_positive(),
                params.pixel_size_um,grain,density_cmy_out)) {
            // Complete grain applied; resident intermediates never visit the host.
        } else if (grain.sublayers_active && have_layers) {""")
anchor='    // Compute the float64 pre-log irradiance `raw` for every pixel.'
s=replace(s,anchor,'#include "expose_chain_hook.inc"\n'+anchor)
write('filming.cpp',s)

s=source('model/couplers.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"','#include "gpu/vulkan_compute.h"\n#include "third_pass.h"\n#include "fifth_pass.h"\n#include "sixth_pass.h"\n#include "seventh_pass.h"')
anchor='    // ---- compute_exposure_correction_dir_couplers (diffusion ON) ----'
s=replace(s,anchor,'#include "dir_mapped.inc"\n#include "dir_f32.inc"\n'+anchor)
write('couplers.cpp',s)
s=source('runtime/stages/printing.cpp')
s=replace(s,'#include "gpu/vulkan_compute.h"','#include "gpu/vulkan_compute.h"\n#include "third_pass.h"\n#include "fifth_pass.h"\n#include "sixth_pass.h"\n#include "seventh_pass.h"')
anchor='                lut_lr.resize(static_cast<size_t>(npix) * 3);'
s=replace(s,anchor,"""                if(gpu::third_pass_enabled() && !diffusion) {
                    // Algebraically the existing log10/pow10 round trip, evaluated
                    // directly in f64. Preserve both clamps/floors and preflash.
                    const double efm=params.exposure_factor_midgray;
                    const double mult=params.print_exposure*params.bw_exposure_correction;
                    parallel_for(0,npix,[&](int lo,int hi){for(int p=lo;p<hi;p++)for(int k=0;k<3;k++){
                        double raw=static_cast<double>(gpu_raw[static_cast<size_t>(p)*3+k])*efm+preflash_raw[k];
                        double y=std::fmax(raw,0.0)+1e-10;
                        log_raw_print_out[static_cast<size_t>(p)*3+k]=static_cast<float>(std::log10(std::fmax(y*mult,0.0)+1e-10));
                    }});
                    gpu::note_print_tail();g_gpu_print_frames.fetch_add(1,std::memory_order_relaxed);return;
                }
"""+anchor)
write('printing.cpp',s)

s=source('runtime/stages/autoexposure.h')
s=replace(s,'    kHighlightWeighted,  // "highlight_weighted"',
    '    kHighlightWeighted,  // "highlight_weighted"\n    kHistogramMiddleGray, // LutCam "histogram_middle_gray"')
write('runtime/stages/autoexposure.h',s)
s=source('runtime/stages/autoexposure.cpp')
s=replace(s,'#include "kernels/parallel.h"','#include "kernels/parallel.h"\n#include "scan_tail.h"\n#include "histogram_metering.h"')
s=replace(s,'        {"highlight_weighted", AeMethod::kHighlightWeighted},',
    '        {"highlight_weighted", AeMethod::kHighlightWeighted},\n        {"histogram_middle_gray", AeMethod::kHistogramMiddleGray},')
s=replace(s,'    double exposure;','    if (known_method && method == AeMethod::kHistogramMiddleGray)\n        return histogram_middle_gray_ev(std::move(Y));\n\n    double exposure;')
anchor='    for (int oy = 0; oy < oh; ++oy) {'
s=replace(s,anchor,'#include "meter_parallel.inc"\n'+anchor)
write('autoexposure.cpp',s)
