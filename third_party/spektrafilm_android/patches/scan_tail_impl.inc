// LutCam local implementation, included inside spk::gpu after the pinned host.
// GPL-3.0-only; reuses its device, mutex, pipeline builder and memory coordinator.
#include "scan_tail_spv.h"
namespace {
struct ScanTailPush {
    uint32_t width,height,npix,mode;
    float amount;int32_t radius;uint32_t glare,groupsX;
    float matrix[9],glareColor[3],weights[9];
};
static_assert(sizeof(ScanTailPush)==116,"Scan tail push layout");
int lutcam_tail_check=0; // guarded by the existing GPU mutex
double lutcam_tail_error=0;

bool run_scan_tail(Ctx& c,const float* linear,const float* glare,int w,int h,
                  const double* color,const double* matrix,double sigma,double amount,
                  float* output,ScanTailDiagnostics& d) {
    auto& s=c.scanTail;
    auto fail=[&](const char* reason) {
        d.reason=reason;
        vkDeviceWaitIdle(c.device);
        c.destroyKernel(s);c.destroyResidentBuf(c.scanTailScratch);
        return false;
    };
    const uint64_t count=static_cast<uint64_t>(w)*h,bytes=count*3*sizeof(float);
    const uint64_t glareBytes=glare?count*sizeof(float):sizeof(float);
    if(bytes>c.properties.limits.maxStorageBufferRange || glareBytes>c.properties.limits.maxStorageBufferRange) {
        d.reason="storage-range";return false;
    }
    auto grid=plan_pointwise_dispatch(static_cast<uint32_t>(count),
            c.properties.limits.maxComputeWorkGroupCount[0],c.properties.limits.maxComputeWorkGroupCount[1]);
    if(!grid.valid){d.reason="dispatch-range";return false;}
    if(!s.pipelineReady && !build_scan_pipeline(c,s,kLutcamScanTailSpv,sizeof(kLutcamScanTailSpv),sizeof(ScanTailPush)))
        return fail("pipeline");
    const auto allocate=std::chrono::steady_clock::now();
    // The grain chain has finished. Its mapped RGB buffer can stage this
    // input, then receive final output. Preserve input separately on device:
    // mode 1 needs the original values while writing its encoded result.
    const bool shared=shared_workspace_enabled() && lutcam_grain_diagnostics.engaged &&
        c.grainChainLayers.in.buf && c.grainChainLayers.in.cap>=bytes &&
        c.grainChainScratch.buf && c.grainChainScratch.cap>=bytes &&
        c.grainChainAccum.buf && c.grainChainAccum.cap>=bytes;
    PointwiseChainDiagnostics allocation{};
    if(!c.ensureBuf(s.cmfB,glareBytes))return fail("glare-allocation");
    if(!shared && (!c.ensureBuf(s.in,bytes) || !c.ensureBuf(s.out,bytes) ||
       !c.ensureResidentBuf(c.scanTailScratch,bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,false,allocation,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)))return fail("allocation");
    const VkBuffer inputBuffer=shared?c.grainChainAccum.buf:s.in.buf;
    const VkBuffer scratchBuffer=shared?c.grainChainScratch.buf:c.scanTailScratch.buf;
    const VkBuffer outputBuffer=shared?c.grainChainLayers.in.buf:s.out.buf;
    const VkBuffer stagingBuffer=shared?c.grainChainLayers.in.buf:s.in.buf;
    void* inputMapped=shared?c.grainChainLayers.in.mapped:s.in.mapped;
    void* outputMapped=shared?c.grainChainLayers.in.mapped:s.out.mapped;
    d.shared_workspace=shared;d.avoided_workspace_bytes=shared?bytes*3:0;
    d.allocation_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-allocate).count();
    const auto upload=std::chrono::steady_clock::now();
    // Copy into private GPU staging while validating. Refusal still cannot
    // touch caller output. Parallel chunks avoid a serial 144 MiB guard pass.
    if(scan_parallel_transfer_enabled()) {
        std::atomic<bool> finite{true};
        auto upload_checked=[&](const float* src,float* dst,int size){
            parallel_for(0,size,[&](int lo,int hi){for(int i=lo;i<hi;i++){
                float v=src[i];if(!std::isfinite(v))finite.store(false,std::memory_order_relaxed);dst[i]=v;
            }});
        };
        upload_checked(linear,static_cast<float*>(inputMapped),int(count*3));
        if(glare)upload_checked(glare,static_cast<float*>(s.cmfB.mapped),int(count));
        else *static_cast<float*>(s.cmfB.mapped)=0;
        if(!finite){d.reason="nonfinite-input";return false;}
    } else {
        for(size_t i=0;i<count*3;i++)if(!std::isfinite(linear[i])){d.reason="nonfinite-input";return false;}
        if(glare)for(size_t i=0;i<count;i++)if(!std::isfinite(glare[i])){d.reason="nonfinite-glare";return false;}
        std::memcpy(inputMapped,linear,static_cast<size_t>(bytes));
        if(glare)std::memcpy(s.cmfB.mapped,glare,static_cast<size_t>(glareBytes));
        else *static_cast<float*>(s.cmfB.mapped)=0;
    }
    d.upload_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-upload).count();
    ScanTailPush p{};p.width=w;p.height=h;p.npix=static_cast<uint32_t>(count);p.groupsX=grid.groups_x;
    p.amount=static_cast<float>(amount);p.radius=sigma>0?static_cast<int>(3*sigma+.5):0;p.glare=glare?1:0;
    for(int i=0;i<9;i++)p.matrix[i]=static_cast<float>(matrix[i]);
    for(int i=0;i<3;i++)p.glareColor[i]=static_cast<float>(color[i]);
    double sum=0;
    for(int k=-p.radius;k<=p.radius;k++){double v=p.radius?std::exp(-.5*(k/sigma)*(k/sigma)):1;sum+=v;p.weights[k+p.radius]=static_cast<float>(v);}
    for(int k=0;k<2*p.radius+1;k++)p.weights[k]=static_cast<float>(p.weights[k]/sum);
    VkDescriptorBufferInfo info[4]={{inputBuffer,0,bytes},{scratchBuffer,0,bytes},{outputBuffer,0,bytes},{s.cmfB.buf,0,glareBytes}};
    VkWriteDescriptorSet writes[4]{};
    for(uint32_t i=0;i<4;i++){writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=s.dset;
        writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&info[i];}
    vkUpdateDescriptorSets(c.device,4,writes,0,nullptr);
    const auto compute=std::chrono::steady_clock::now();
    if(vkResetCommandBuffer(s.cmd,0)!=VK_SUCCESS)return fail("command-reset");
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if(vkBeginCommandBuffer(s.cmd,&begin)!=VK_SUCCESS)return fail("command-begin");
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    if(shared) {
        barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(s.cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
        VkBufferCopy copy{0,0,bytes};vkCmdCopyBuffer(s.cmd,stagingBuffer,inputBuffer,1,&copy);
        barrier.srcAccessMask=VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT;
        barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(s.cmd,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    } else {
        barrier.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(s.cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }
    vkCmdBindPipeline(s.cmd,VK_PIPELINE_BIND_POINT_COMPUTE,s.pipe);
    vkCmdBindDescriptorSets(s.cmd,VK_PIPELINE_BIND_POINT_COMPUTE,s.pl,0,1,&s.dset,0,nullptr);
    for(uint32_t mode=0;mode<2;mode++) {
        p.mode=mode;vkCmdPushConstants(s.cmd,s.pl,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);
        vkCmdDispatch(s.cmd,grid.groups_x,grid.groups_y,1);
        barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask=mode==0?VK_ACCESS_SHADER_READ_BIT:VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(s.cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            mode==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr);
    }
    if(vkEndCommandBuffer(s.cmd)!=VK_SUCCESS)return fail("command-end");
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&s.cmd;
    if(vkQueueSubmit(c.queue,1,&submit,s.fence)!=VK_SUCCESS ||
       vkWaitForFences(c.device,1,&s.fence,VK_TRUE,UINT64_MAX)!=VK_SUCCESS ||
       vkResetFences(c.device,1,&s.fence)!=VK_SUCCESS)return fail("dispatch");
    stage_timing_note_gpu_submissions(1);
    d.compute_wait_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-compute).count();
    const auto readback=std::chrono::steady_clock::now();
    const float* result=static_cast<const float*>(outputMapped);
    if(scan_parallel_transfer_enabled()) {
        std::atomic<bool> finite{true};
        parallel_for(0,int(count*3),[&](int lo,int hi){for(int i=lo;i<hi;i++)
            if(!std::isfinite(result[i]))finite.store(false,std::memory_order_relaxed);});
        if(!finite)return fail("nonfinite-output");
        parallel_for(0,int(count*3),[&](int lo,int hi){std::memcpy(output+lo,result+lo,size_t(hi-lo)*sizeof(float));});
    } else {
        for(size_t i=0;i<count*3;i++)if(!std::isfinite(result[i]))return fail("nonfinite-output");
        std::memcpy(output,result,static_cast<size_t>(bytes));
    }
    d.readback_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-readback).count();
    d.engaged=true;d.reason="none";d.dispatches=2;d.submissions=1;d.bytes_up=bytes+(glare?glareBytes:0);d.bytes_down=bytes;
    return true;
}
int tail_reflect(int q,int n){if(n==1)return 0;int t=q%(2*n);if(t<0)t+=2*n;return t<n?t:2*n-1-t;}
// Independent f64 CPU oracle including tiny/non-square reflect boundaries,
// out-of-gamut/HDR values, glare, matrix and transfer threshold.
bool qualify_scan_tail(Ctx& c) {
    for(const auto shape: {std::pair<int,int>{17,13},{1,19},{23,1}}) {
        int w=shape.first,h=shape.second,n=w*h;
        std::vector<float> input(n*3),glare(n),actual(n*3);
        for(int i=0;i<n;i++){glare[i]=static_cast<float>((i%7)*.0003);for(int k=0;k<3;k++)input[i*3+k]=static_cast<float>(((i*17+k*31)%127)/55.0-.1);}
        const double color[]={.91,1.02,1.1},matrix[]={1.0001,-.0002,.0001,-.0001,1.0002,-.0001,.0002,-.0001,.9999};
        for(double sigma: {0.0,.7,1.2}) {
            const int radius=sigma>0?int(3*sigma+.5):0;const double amount=sigma>0?.7:0;
            std::vector<double> composed(n*3),vertical(n*3);double weights[9],sum=0;
            for(int k=-radius;k<=radius;k++){weights[k+radius]=radius?std::exp(-.5*(k/sigma)*(k/sigma)):1;sum+=weights[k+radius];}
            for(int k=0;k<2*radius+1;k++)weights[k]/=sum;
            for(int i=0;i<n;i++)for(int k=0;k<3;k++)composed[i*3+k]=input[i*3+k]+glare[i]*color[k];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int ch=0;ch<3;ch++){
                double v=0;for(int k=-radius;k<=radius;k++)v+=weights[k+radius]*composed[(tail_reflect(y+k,h)*w+x)*3+ch];vertical[(y*w+x)*3+ch]=v;}
            ScanTailDiagnostics d;
            if(!run_scan_tail(c,input.data(),glare.data(),w,h,color,matrix,sigma,amount,actual.data(),d))return false;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                double mixed[3];for(int ch=0;ch<3;ch++){
                    double b=0;for(int k=-radius;k<=radius;k++)b+=weights[k+radius]*vertical[(y*w+tail_reflect(x+k,w))*3+ch];
                    double a=composed[(y*w+x)*3+ch];mixed[ch]=a+amount*(a-b);}
                for(int ch=0;ch<3;ch++){
                    double v=0;for(int k=0;k<3;k++)v+=matrix[ch*3+k]*mixed[k];
                    v=v<=.0031308?12.92*v:1.055*std::pow(v,1/2.4)-.055;v=std::clamp(v,0.0,1.0);
                    double error=std::fabs(actual[(y*w+x)*3+ch]-v);lutcam_tail_error=std::max(lutcam_tail_error,error);
                    if(!std::isfinite(error) || error>1e-4)return false;
                }
            }
        }
    }
    return true;
}
}
bool scan_tail(const float* linear,const float* glare,int width,int height,const double color[3],
               const double matrix[9],double sigma,double amount,float* encoded) {
    auto& d=lutcam_tail_diagnostics;d={};
    if(!scan_tail_enabled()){d.reason="disabled_for_reference";return false;}
    d.attempted=true;
    if(!linear || !encoded || !color || !matrix || width<=0 || height<=0 ||
       static_cast<uint64_t>(width)*height>32ull*1024*1024 || !std::isfinite(sigma) ||
       !std::isfinite(amount) || !std::isfinite(static_cast<float>(amount)) || sigma<0 || sigma>=1.5 || int(3*sigma+.5)>4){d.reason="unsupported";return false;}
    for(int i=0;i<9;i++)if(!std::isfinite(matrix[i]) || !std::isfinite(static_cast<float>(matrix[i]))){d.reason="matrix";return false;}
    for(int i=0;i<3;i++)if(!std::isfinite(color[i]) || !std::isfinite(static_cast<float>(color[i]))){d.reason="glare-color";return false;}
    std::lock_guard<std::mutex> lock(gpu_mutex());Ctx& c=ctx();
    if(!c.ok){d.reason="unavailable";return false;}
    if(lutcam_tail_check==0)lutcam_tail_check=qualify_scan_tail(c)?1:-1;
    d.self_check=lutcam_tail_check;d.self_check_max_error=lutcam_tail_error;
    if(lutcam_tail_check!=1){d.reason="self-check";return false;}
    return run_scan_tail(c,linear,glare,width,height,color,matrix,sigma,amount,encoded,d);
}
