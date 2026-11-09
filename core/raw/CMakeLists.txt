find_package(Python3 REQUIRED COMPONENTS Interpreter)
find_program(SPEKTRACAM_GLSLC glslc HINTS
    "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64" REQUIRED)

add_library(spektracam_raw STATIC raw_frame.cpp vulkan_raw.cpp render.cpp ../pipeline/dng_stage3_vignette.cpp)
target_include_directories(spektracam_raw PUBLIC .. PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(spektracam_raw PUBLIC spektracam_film)
set_target_properties(spektracam_raw PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    target_link_libraries(spektracam_raw PRIVATE vulkan)
else()
    target_include_directories(spektracam_raw PRIVATE "${RAW_ISP_VULKAN_INCLUDE_DIR}")
    target_link_libraries(spektracam_raw PRIVATE "${RAW_ISP_VULKAN_LIBRARY}")
endif()

foreach(shader IN ITEMS joint_green film_linear)
    set(source "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${shader}.comp")
    set(binary "${CMAKE_CURRENT_BINARY_DIR}/${shader}.spv")
    set(header "${CMAKE_CURRENT_BINARY_DIR}/${shader}_spv.h")
    set(embed "${PROJECT_SOURCE_DIR}/tools/embed_spirv.py")
    add_custom_command(OUTPUT "${header}"
        COMMAND "${SPEKTRACAM_GLSLC}" --target-env=vulkan1.0 -O "${source}" -o "${binary}"
        COMMAND "${Python3_EXECUTABLE}" "${embed}" "${binary}" "${header}"
        DEPENDS "${source}" "${embed}"
        VERBATIM)
    target_sources(spektracam_raw PRIVATE "${header}")
endforeach()
