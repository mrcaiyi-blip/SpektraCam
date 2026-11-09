// Included inside spk::gpu after the original GPU implementation. GPL-3.0-only.
#include "grain_tail_spv.h"
namespace {
struct GrainTailPush {
    uint32_t width,height,npix,groupsX,mode;int32_t radius;
    float sub[3],weights[9];
};
static_assert(sizeof(GrainTailPush)==72,"Grain FIR push layout");
std::once_flag grain_chain_check_once;
int grain_chain_check=0;
double grain_chain_check_error=0;

bool grain_chain_cells(const float* layers,int points,double pixel,const GrainParams& g,
                       std::array<GrainCell,9>& cells) {
    double maxima[9],total[3]={0,0,0};
    for(int k=0;k<9;k++) {
        double mx=-1;bool any=false;
        for(int i=0;i<points;i++){float v=layers[i*9+k];if(std::isnan(v))continue;if(!any || v>mx){mx=v;any=true;}}
        maxima[k]=any?mx:2.2;total[k%3]+=maxima[k];
    }
    for(int sl=0;sl<3;sl++)for(int ch=0;ch<3;ch++) {
        int k=sl*3+ch;double frac=maxima[k]/total[ch];auto& c=cells[k];
        c.density_min=frac*g.density_min[ch];c.density_max=maxima[k]+c.density_min;
        c.n_particles_per_pixel=pixel*pixel*frac/(g.agx_particle_area_um2*g.agx_particle_scale[ch]*g.agx_particle_scale_layers[sl]);
        c.uniformity=g.uniformity[ch];c.channel=ch;c.seed_base=static_cast<uint32_t>(g.seed_base[ch]+sl*10);
        c.blur_sigma_px=g.blur_dye_clouds_um>0?g.blur_dye_clouds_um*std::sqrt(c.density_max/c.n_particles_per_pixel):0;
    }
    GrainSampleRequest r{};r.cells=cells.data();r.cell_count=9;
    return grain_cells_valid(r);
}

bool run_grain_chain(const float* density,int w,int h,const float* axis,const float* layers,
                     int points,bool positive,const GrainParams& grain,
                     const std::array<GrainCell,9>& cells,float* output,GrainChainDiagnostics& d) {
    const uint32_t count=static_cast<uint32_t>(w*h);
    const VkDeviceSize rgbBytes=VkDeviceSize(count)*3*sizeof(float),layerBytes=rgbBytes*3;
    std::array<float,kGrainMaxCells*kGrainCellStride> cellData{};
    uint32_t maxRadius=0;
    for(int k=0;k<9;k++) {
        const auto& g=cells[k];float taps[7]={};int r=grain_blur_kernel(g.blur_sigma_px,taps);
        if(r<0){d.reason="particle-blur-wide";return false;}maxRadius=std::max(maxRadius,uint32_t(r));
        float* e=cellData.data()+k*kGrainCellStride;
        e[0]=float(g.density_min);e[1]=float(g.density_max);e[2]=float(g.n_particles_per_pixel);
        e[3]=float(g.uniformity);e[4]=float(g.density_max/g.n_particles_per_pixel);e[5]=float(g.channel);
        std::memcpy(e+6,&g.seed_base,4);e[7]=float(r);for(int t=0;t<2*r+1;t++)e[8+t]=taps[t];
        for(int j=0;j<16;j++)if(j!=6 && !std::isfinite(e[j])){d.reason="cell-range";return false;}
    }
    if(uint32_t(w)<2*maxRadius+1 || uint32_t(h)<2*maxRadius+1){d.reason="particle-kernel-size";return false;}
    GrainTailPush tail{};tail.width=w;tail.height=h;tail.npix=count;
    // gaussian_kernel_1d takes float sigma, rounds taps before f64 normalization.
    float sg=float(grain.blur);tail.radius=sg>0?int(3.f*sg+.5f):0;
    double sum=0;
    for(int k=-tail.radius;k<=tail.radius;k++){double v=tail.radius?std::exp(-.5*(double(k)/sg)*(double(k)/sg)):1;sum+=v;tail.weights[k+tail.radius]=float(v);}
    for(int k=0;k<2*tail.radius+1;k++)tail.weights[k]=float(tail.weights[k]/sum);
    for(int ch=0;ch<3;ch++)tail.sub[ch]=float(grain.density_min[ch]);
    std::lock_guard<std::mutex> lock(gpu_mutex());Ctx& c=ctx();
    if(!c.ok){d.reason="unavailable";return false;}
    auto grid=plan_pointwise_dispatch(count,c.properties.limits.maxComputeWorkGroupCount[0],c.properties.limits.maxComputeWorkGroupCount[1]);
    if(!grid.valid || layerBytes>c.properties.limits.maxStorageBufferRange){d.reason="device-range";return false;}
    auto& l=c.grainChainLayers;auto& g=c.grainChainSample;auto& t=c.grainChainTail;
    auto fail=[&](const char* why){d.reason=why;vkDeviceWaitIdle(c.device);
        c.destroyKernel(l);c.destroyKernel(g);c.destroyKernel(t);
        c.destroyResidentBuf(c.grainChainScratch);c.destroyResidentBuf(c.grainChainAccum);return false;};
    if((!l.pipelineReady && !build_scan_pipeline(c,l,kFilmingStageSpv,sizeof(kFilmingStageSpv),sizeof(FilmingStagePush))) ||
       (!g.pipelineReady && !build_scan_pipeline(c,g,kGrainSpv,sizeof(kGrainSpv),sizeof(GrainPush))) ||
       (!t.pipelineReady && !build_scan_pipeline(c,t,kLutcamGrainTailSpv,sizeof(kLutcamGrainTailSpv),sizeof(GrainTailPush))))return fail("pipeline");
    PointwiseChainDiagnostics unused{};
    const VkDeviceSize tableBytes=VkDeviceSize(points)*12*sizeof(float),cellBytes=sizeof(cellData);
    if(!c.ensureBuf(l.in,rgbBytes) || !c.ensureBuf(l.cmfB,tableBytes) ||
       !c.ensureBuf(g.dyeB,cellBytes) || !c.ensureBuf(g.cmfB,16) ||
       !c.ensureResidentBuf(c.grainChainScratch,layerBytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false,unused,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) ||
       !c.ensureResidentBuf(c.grainChainAccum,rgbBytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false,unused,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))return fail("allocation");
    auto descriptors=[&](Ctx::Kernel& k,const std::array<VkDescriptorBufferInfo,4>& info) {
        VkWriteDescriptorSet writes[4]{};
        for(uint32_t b=0;b<4;b++){writes[b].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[b].dstSet=k.dset;
            writes[b].dstBinding=b;writes[b].descriptorCount=1;writes[b].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[b].pBufferInfo=&info[b];}
        vkUpdateDescriptorSets(c.device,4,writes,0,nullptr);
    };
    descriptors(l,{{{l.in.buf,0,rgbBytes},{c.grainChainScratch.buf,0,layerBytes},{g.cmfB.buf,0,16},{l.cmfB.buf,0,tableBytes}}});
    descriptors(g,{{{c.grainChainScratch.buf,0,layerBytes},{c.grainChainAccum.buf,0,rgbBytes},{g.dyeB.buf,0,cellBytes},{g.cmfB.buf,0,16}}});
    descriptors(t,{{{c.grainChainAccum.buf,0,rgbBytes},{c.grainChainScratch.buf,0,layerBytes},{l.in.buf,0,rgbBytes},{g.cmfB.buf,0,16}}});
    auto now=std::chrono::steady_clock::now();std::atomic<bool> finite{true};
    parallel_for(0,int(count*3),[&](int lo,int hi){for(int i=lo;i<hi;i++)if(!std::isfinite(density[i]))finite.store(false,std::memory_order_relaxed);});
    if(!finite){d.reason="nonfinite-input";return false;}
    parallel_for(0,int(count*3),[&](int lo,int hi){std::memcpy(static_cast<float*>(l.in.mapped)+lo,density+lo,size_t(hi-lo)*sizeof(float));});
    auto* table=static_cast<float*>(l.cmfB.mapped);
    for(int i=0;i<points*3;i++)table[i]=positive?-axis[i]:axis[i];
    std::memcpy(table+points*3,layers,size_t(points)*9*sizeof(float));
    std::memcpy(g.dyeB.mapped,cellData.data(),size_t(cellBytes));std::memset(g.cmfB.mapped,0,16);
    d.upload_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    FilmingStagePush lp{};lp.npix=count;lp.groups_x=grid.groups_x;lp.total_groups=grid.groups_x*grid.groups_y;
    lp.mode=kFilmModeLayers;lp.flags=positive?kFilmFlagPositive:0;lp.n=points;lp.L=2;
    GrainPush gp{};gp.npix=count;gp.ncells=9;gp.seed_offset=uint32_t(grain.seed_offset);gp.groups_x=grid.groups_x;gp.width=w;gp.height=h;
    tail.groupsX=grid.groups_x;
    now=std::chrono::steady_clock::now();
    if(vkResetCommandBuffer(g.cmd,0)!=VK_SUCCESS)return fail("command-reset");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if(vkBeginCommandBuffer(g.cmd,&begin)!=VK_SUCCESS)return fail("command-begin");
    auto barrier=[&](VkPipelineStageFlags src,VkAccessFlags access,VkPipelineStageFlags dst,VkAccessFlags target){
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};b.srcAccessMask=access;b.dstAccessMask=target;
        vkCmdPipelineBarrier(g.cmd,src,dst,0,1,&b,0,nullptr,0,nullptr);
    };
    barrier(VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
    auto dispatch=[&](Ctx::Kernel& k,const void* push,uint32_t size){
        vkCmdBindPipeline(g.cmd,VK_PIPELINE_BIND_POINT_COMPUTE,k.pipe);
        vkCmdBindDescriptorSets(g.cmd,VK_PIPELINE_BIND_POINT_COMPUTE,k.pl,0,1,&k.dset,0,nullptr);
        vkCmdPushConstants(g.cmd,k.pl,VK_SHADER_STAGE_COMPUTE_BIT,0,size,push);
        vkCmdDispatch(g.cmd,grid.groups_x,grid.groups_y,1);
        // Include read->write ordering: scratch is recycled after grain sampling.
        barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
    };
    dispatch(l,&lp,sizeof(lp));tail.mode=2;dispatch(t,&tail,sizeof(tail));dispatch(g,&gp,sizeof(gp));
    for(uint32_t mode=0;mode<2;mode++){tail.mode=mode;dispatch(t,&tail,sizeof(tail));}
    barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT);
    if(vkEndCommandBuffer(g.cmd)!=VK_SUCCESS)return fail("command-end");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&g.cmd;
    if(vkQueueSubmit(c.queue,1,&submit,g.fence)!=VK_SUCCESS || vkWaitForFences(c.device,1,&g.fence,VK_TRUE,UINT64_MAX)!=VK_SUCCESS ||
       vkResetFences(c.device,1,&g.fence)!=VK_SUCCESS)return fail("dispatch");
    stage_timing_note_gpu_submissions(1);
    d.compute_wait_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    now=std::chrono::steady_clock::now();const float* result=static_cast<const float*>(l.in.mapped);
    parallel_for(0,int(count*3),[&](int lo,int hi){for(int i=lo;i<hi;i++)if(!std::isfinite(result[i]))finite.store(false,std::memory_order_relaxed);});
    if(!finite)return fail("nonfinite-output");
    parallel_for(0,int(count*3),[&](int lo,int hi){std::memcpy(output+lo,result+lo,size_t(hi-lo)*sizeof(float));});
    std::memcpy(&d.out_of_branch,g.cmfB.mapped,4);
    d.readback_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    d.dispatches=5;d.submissions=1;d.bytes_up=rgbBytes+tableBytes+cellBytes;d.bytes_down=rgbBytes;
    d.avoided_layer_host_bytes=layerBytes*2;d.engaged=true;d.reason="none";return true;
}

// Same original GPU layer/sampling shaders on both sides. Exercise current
// profile's curves/cells and compare all pixels after subtraction and FIR.
bool qualify_grain_chain(const float* axis,const float* layers,int points,bool positive,
                         const GrainParams& grain,const std::array<GrainCell,9>& cells) {
    constexpr int w=23,h=19,count=w*h;
    std::vector<float> input(count*3),layer(size_t(count)*9),expected(count*3),actual(count*3);
    for(int i=0;i<count;i++)for(int ch=0;ch<3;ch++){
        float t=float((i*17+ch*31)%101)/100.f;input[i*3+ch]=axis[ch]+t*(axis[(points-1)*3+ch]-axis[ch]);
    }
    FilmingStageDiagnostics fd{};GrainSampleDiagnostics gd{};
    if(!grain_layers(input.data(),count,w,h,axis,layers,points,positive,layer.data(),&fd))return false;
    GrainSampleRequest request{};request.density_cells=layer.data();request.cells=cells.data();request.cell_count=9;
    request.npix=count;request.width=w;request.height=h;request.seed_offset=uint32_t(grain.seed_offset);
    if(!grain_sample(request,expected.data(),&gd) || !subtract_per_channel(expected.data(),expected.data(),w,h,grain.density_min,&fd))return false;
    double sigma[3]={grain.blur,grain.blur,grain.blur};
    if(grain.blur>0 && !gaussian_blur_rgb_f32(expected.data(),w,h,sigma))return false;
    GrainChainDiagnostics d;
    if(!run_grain_chain(input.data(),w,h,axis,layers,points,positive,grain,cells,actual.data(),d) || d.out_of_branch!=gd.out_of_branch)return false;
    for(size_t i=0;i<actual.size();i++){
        double error=std::fabs(double(expected[i])-actual[i]);grain_chain_check_error=std::max(grain_chain_check_error,error);
        if(!std::isfinite(error) || error>5e-6)return false;
    }
    return true;
}
}
bool grain_chain(const float* density,int width,int height,const float* axis,const float* layers,
                 int points,bool positive,double pixel,const GrainParams& grain,float* output) {
    auto& d=lutcam_grain_diagnostics;d={};
    if(!lutcam_grain_enabled){d.reason="disabled_for_reference";return false;}d.attempted=true;
    const char* blurEnv=std::getenv("SPK_GPU_BLUR");
    if(!density || !output || !axis || !layers || width<=0 || height<=0 ||
       uint64_t(width)*height>32ull*1024*1024 || points<2 || points>4096 ||
       !grain.active || !grain.sublayers_active || !grain.allow_gpu_sampler || !grain.fast_sampler ||
       !std::isfinite(pixel) || pixel<=0 || !std::isfinite(grain.micro_structure[1]) ||
       grain.micro_structure[1]*.001/pixel>.05 || !std::isfinite(grain.blur) || grain.blur<0 || grain.blur>=1.5 || float(grain.blur)>=1.5f || int(3.f*float(grain.blur)+.5f)>4 ||
       (blurEnv && blurEnv[0]=='0')){d.reason="unsupported";return false;}
    for(int ch=0;ch<3;ch++){
        if(!std::isfinite(float(grain.density_min[ch]))){d.reason="density-min";return false;}
    }
    std::array<GrainCell,9> cells{};
    if(!grain_chain_cells(layers,points,pixel,grain,cells)){d.reason="cell";return false;}
    std::call_once(grain_chain_check_once,[&]{grain_chain_check=qualify_grain_chain(axis,layers,points,positive,grain,cells)?1:-1;});
    d.self_check=grain_chain_check;d.self_check_max_error=grain_chain_check_error;
    if(grain_chain_check!=1){d.reason="self-check";return false;}
    const bool ok=run_grain_chain(density,width,height,axis,layers,points,positive,grain,cells,output,d);
    if(ok)stage_timing_note_gpu_grain(true,true,"resident-chain",d.compute_wait_ms);
    return ok;
}
