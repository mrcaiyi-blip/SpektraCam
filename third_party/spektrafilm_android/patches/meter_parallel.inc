// Inside small_preview_impl<T>. GPLv3. Cache integer mirror indices once and
// split independent output pixels. Every pixel retains the original f64 sum order.
if(gpu::fourth_pass_enabled()) {
    const int taps_x=2*rad_x+1,taps_y=2*rad_y+1;
    std::vector<int> sample_x(static_cast<size_t>(ow)*taps_x),sample_y(static_cast<size_t>(oh)*taps_y);
    for(int ox=0;ox<ow;ox++) {
        double xx=(static_cast<double>(ox)+.5)/zoom_x-.5;
        int sx=mirror_index(static_cast<long long>(std::nearbyint(xx)),w);
        for(int k=-rad_x;k<=rad_x;k++)sample_x[static_cast<size_t>(ox)*taps_x+k+rad_x]=mirror_index(static_cast<long long>(sx)+k,w);
    }
    for(int oy=0;oy<oh;oy++) {
        double yy=(static_cast<double>(oy)+.5)/zoom_y-.5;
        int sy=mirror_index(static_cast<long long>(std::nearbyint(yy)),h);
        for(int k=-rad_y;k<=rad_y;k++)sample_y[static_cast<size_t>(oy)*taps_y+k+rad_y]=mirror_index(static_cast<long long>(sy)+k,h);
    }
    parallel_for(0,ow*oh,[&](int lo,int hi){for(int o=lo;o<hi;o++){
        int oy=o/ow,ox=o%ow;double acc0=0,acc1=0,acc2=0;
        for(int ky=-rad_y;ky<=rad_y;ky++) {
            int syy=sample_y[static_cast<size_t>(oy)*taps_y+ky+rad_y];
            double in0=0,in1=0,in2=0;
            for(int kx=-rad_x;kx<=rad_x;kx++) {
                int sxx=sample_x[static_cast<size_t>(ox)*taps_x+kx+rad_x];
                const double wx=ker_x[static_cast<size_t>(kx+rad_x)];
                const size_t si=(static_cast<size_t>(syy)*w+sxx)*3;
                in0+=wx*static_cast<double>(in[si]);in1+=wx*static_cast<double>(in[si+1]);in2+=wx*static_cast<double>(in[si+2]);
            }
            const double wy=ker_y[static_cast<size_t>(ky+rad_y)];
            acc0+=wy*in0;acc1+=wy*in1;acc2+=wy*in2;
        }
        (*out)[static_cast<size_t>(o)*3]=acc0;(*out)[static_cast<size_t>(o)*3+1]=acc1;(*out)[static_cast<size_t>(o)*3+2]=acc2;
    }});
    *out_w=ow;*out_h=oh;return;
}
