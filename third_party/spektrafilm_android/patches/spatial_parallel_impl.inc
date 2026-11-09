// GPU mutex is held. GPLv3. Original DF arithmetic, independent channel lanes.
int lutcam_spatial_check=0;
bool lutcam_spatial_checking=false;
double lutcam_spatial_error=0;
void qualify_spatial_parallel(Ctx&);
bool ensure_spatial_parallel(Ctx& c,Ctx::HalationKernel& k) {
    if(k.fastPipe)return true;
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    sm.codeSize=sizeof(kLutcamSpatialParallelSpv);sm.pCode=kLutcamSpatialParallelSpv;
    if(vkCreateShaderModule(c.device,&sm,nullptr,&k.fastShader)!=VK_SUCCESS)return false;
    VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cp.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;cp.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;
    cp.stage.module=k.fastShader;cp.stage.pName="main";cp.layout=k.pl;
    if(vkCreateComputePipelines(c.device,VK_NULL_HANDLE,1,&cp,nullptr,&k.fastPipe)!=VK_SUCCESS){
        vkDestroyShaderModule(c.device,k.fastShader,nullptr);k.fastShader=VK_NULL_HANDLE;return false;
    }
    return true;
}
