// Included under existing GPU lock before halation_scatter_locked. GPLv3.
int lutcam_padded_check=0;bool lutcam_padded_checking=false;double lutcam_padded_error=0;
void qualify_padded_transpose(Ctx&);
bool ensure_padded_transpose(Ctx& c,Ctx::HalationKernel& k) {
    if(k.paddedPipe)return true;
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=sizeof(kLutcamSpatialPaddedSpv);sm.pCode=kLutcamSpatialPaddedSpv;
    if(vkCreateShaderModule(c.device,&sm,nullptr,&k.paddedShader)!=VK_SUCCESS)return false;
    VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cp.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;cp.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;
    cp.stage.module=k.paddedShader;cp.stage.pName="main";cp.layout=k.pl;
    if(vkCreateComputePipelines(c.device,VK_NULL_HANDLE,1,&cp,nullptr,&k.paddedPipe)!=VK_SUCCESS){vkDestroyShaderModule(c.device,k.paddedShader,nullptr);k.paddedShader=VK_NULL_HANDLE;return false;}
    return true;
}
