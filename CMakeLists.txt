cmake_minimum_required(VERSION 3.22.1)
project(SpektraCam LANGUAGES CXX)

add_subdirectory(third_party/spektrafilm_android)
add_library(spektracam_film STATIC core/film/spektrafilm_adapter.cpp)
target_include_directories(spektracam_film PUBLIC core core/film)
target_link_libraries(spektracam_film PUBLIC spektrafilm_engine)
set_target_properties(spektracam_film PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID OR RAW_ISP_VULKAN_LIBRARY)
    add_subdirectory(core/raw)
endif()

include(CTest)
if(BUILD_TESTING)
    add_subdirectory(tools/film_lut/native_tests)
endif()
