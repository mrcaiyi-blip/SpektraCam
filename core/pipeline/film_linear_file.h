#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace raw_isp::film_io {
// LCRF v1: 16-byte LE header (magic, version, width, height), then interleaved
// float32 linear ProPhoto RGB D50, top-down sensor rows. No display transfer/LUT.
inline constexpr uint32_t kMagic=0x4652434c;
inline void write_u32(std::ostream& stream,uint32_t value) {
    char bytes[4];for(int i=0;i<4;i++)bytes[i]=static_cast<char>((value>>(8*i))&255u);
    stream.write(bytes,4);
}
inline uint32_t read_u32(std::istream& stream) {
    unsigned char b[4]{};stream.read(reinterpret_cast<char*>(b),4);
    if(!stream)throw std::runtime_error("Truncated linear RAW RGB header");
    return uint32_t(b[0])|(uint32_t(b[1])<<8)|(uint32_t(b[2])<<16)|(uint32_t(b[3])<<24);
}
inline size_t components(uint32_t w,uint32_t h) {
    if(w==0 || h==0 || uint64_t(w)*h>32ull*1024*1024)
        throw std::runtime_error("Invalid linear RAW RGB dimensions");
    return static_cast<size_t>(w)*h*3;
}
inline void require_little_endian() {
    const uint32_t value=1;
    if(*reinterpret_cast<const uint8_t*>(&value)!=1)
        throw std::runtime_error("LCRF float payload requires a little-endian host");
    static_assert(sizeof(float)==4 && std::numeric_limits<float>::is_iec559);
}
inline void write(const std::string& path,uint32_t w,uint32_t h,const std::vector<float>& rgb) {
    require_little_endian();
    if(rgb.size()!=components(w,h))throw std::runtime_error("Linear RAW RGB size mismatch");
    for(float value:rgb)if(!std::isfinite(value))throw std::runtime_error("Nonfinite RAW RGB");
    std::ofstream file(path,std::ios::binary|std::ios::trunc);
    write_u32(file,kMagic);write_u32(file,1);write_u32(file,w);write_u32(file,h);
    file.write(reinterpret_cast<const char*>(rgb.data()),static_cast<std::streamsize>(rgb.size()*sizeof(float)));
    file.close();if(!file)throw std::runtime_error("Cannot write linear RAW RGB");
}
inline std::vector<float> read(const std::string& path,int* w,int* h) {
    require_little_endian();
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file)throw std::runtime_error("Cannot open linear RAW RGB");
    auto bytes=file.tellg();file.seekg(0);
    if(read_u32(file)!=kMagic || read_u32(file)!=1)throw std::runtime_error("Invalid linear RAW RGB format");
    uint32_t width=read_u32(file),height=read_u32(file);
    size_t count=components(width,height);
    if(bytes!=static_cast<std::streamoff>(16+count*sizeof(float)))throw std::runtime_error("Truncated or oversized linear RAW RGB");
    std::vector<float> rgb(count);
    file.read(reinterpret_cast<char*>(rgb.data()),static_cast<std::streamsize>(count*sizeof(float)));
    if(!file)throw std::runtime_error("Cannot read linear RAW RGB");
    for(float value:rgb)if(!std::isfinite(value))throw std::runtime_error("Nonfinite linear RAW RGB");
    *w=static_cast<int>(width);*h=static_cast<int>(height);return rgb;
}
} // namespace raw_isp::film_io
