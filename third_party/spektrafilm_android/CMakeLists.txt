# LutCam integration of the pinned GPLv3 Spektrafilm Android engine.
# Pinned sources stay unchanged; checked local scan-tail build overlays are applied.
cmake_minimum_required(VERSION 3.22.1)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(LUTCAM_OVERLAY "${CMAKE_CURRENT_BINARY_DIR}/lutcam_overlay")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/apply_overlay.py"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/scan_tail.comp"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/scan_tail_spv.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/shader_sha256.json"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/grain_tail.comp"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/grain_tail_spv.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/grain_shader_sha256.json"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_parallel.comp"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_parallel_spv.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_shader_sha256.json"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_padded.comp"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_padded_spv.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/patches/spatial_padded_sha256.json")
execute_process(COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/patches/apply_overlay.py"
    "${CMAKE_CURRENT_SOURCE_DIR}" "${LUTCAM_OVERLAY}" RESULT_VARIABLE overlay_status)
if(NOT overlay_status EQUAL 0)
    message(FATAL_ERROR "Checked Spektrafilm overlay failed")
endif()
add_library(spektrafilm_engine STATIC
    engine/spektra.cpp
    engine/kernels/interp.cpp
    engine/kernels/gaussian.cpp
    engine/kernels/gaussian_hwy.cpp
    engine/kernels/exponential_filter.cpp
    engine/kernels/spectral_upsampling.cpp
    engine/kernels/stats.cpp
    engine/kernels/lut3d.cpp
    engine/kernels/lut3d_cache.cpp
    engine/kernels/parallel.cpp
    engine/kernels/fft.cpp
    engine/kernels/fft_convolve.cpp
    engine/kernels/tonecurve.cpp
    engine/kernels/half.cpp
    engine/io/npy_lut.cpp
    engine/model/spectral.cpp
    engine/model/conversions.cpp
    engine/model/density_curves.cpp
    engine/model/emulsion.cpp
    engine/model/glare.cpp
    engine/model/diffusion.cpp
    engine/model/grain.cpp
    engine/model/morph_curves.cpp
    engine/model/gamut_compression.cpp
    engine/model/color_output.cpp
    engine/profiles/profile.cpp
    "${LUTCAM_OVERLAY}/scanning.cpp"
    "${LUTCAM_OVERLAY}/couplers.cpp"
    engine/runtime/params.cpp
    engine/runtime/tc_lut_cache.cpp
    "${LUTCAM_OVERLAY}/filming.cpp"
    engine/model/color_filters.cpp
    "${LUTCAM_OVERLAY}/printing.cpp"
    engine/runtime/print_digest.cpp
    engine/runtime/color_reference.cpp
    engine/runtime/stages/crop_resize.cpp
    "${LUTCAM_OVERLAY}/autoexposure.cpp"
    "${LUTCAM_OVERLAY}/vulkan_compute.cpp"
)
set_target_properties(spektrafilm_engine PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(spektrafilm_engine PUBLIC cxx_std_17)
target_include_directories(spektrafilm_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/engine")
target_include_directories(spektrafilm_engine BEFORE PUBLIC "${LUTCAM_OVERLAY}")
target_include_directories(spektrafilm_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/patches")
target_compile_options(spektrafilm_engine PRIVATE -O2 -Wno-error)
find_package(Threads REQUIRED)
target_link_libraries(spektrafilm_engine PUBLIC Threads::Threads)
if(ANDROID)
    target_compile_definitions(spektrafilm_engine PRIVATE SPK_ENABLE_VULKAN=1)
    target_link_libraries(spektrafilm_engine PUBLIC android log vulkan)
elseif(APPLE AND DEFINED RAW_ISP_VULKAN_LIBRARY AND NOT RAW_ISP_VULKAN_LIBRARY STREQUAL "")
    target_compile_definitions(spektrafilm_engine PRIVATE SPK_ENABLE_VULKAN=1)
    target_include_directories(spektrafilm_engine PRIVATE "${RAW_ISP_VULKAN_INCLUDE_DIR}")
    target_link_libraries(spektrafilm_engine PUBLIC "${RAW_ISP_VULKAN_LIBRARY}")
endif()
