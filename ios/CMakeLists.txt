cmake_minimum_required(VERSION 3.22.1)
project(SpektraCamIOS LANGUAGES CXX Swift)

set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0" CACHE STRING "Minimum iOS version")
set(BUILD_TESTING OFF CACHE BOOL "Disable host tools for the iOS app")
option(SPEKTRACAM_APP_STORE "Exclude local support links from the App Store build" OFF)
if(NOT RAW_ISP_VULKAN_LIBRARY OR NOT RAW_ISP_VULKAN_INCLUDE_DIR)
    message(FATAL_ERROR "Set RAW_ISP_VULKAN_LIBRARY and RAW_ISP_VULKAN_INCLUDE_DIR to the iOS MoltenVK package")
endif()
add_subdirectory(.. core)

add_executable(SpektraCam MACOSX_BUNDLE
    CaptureGallery.swift Localization.swift
    SpektraCamApp.swift FilmSettingsView.swift CameraCapture.swift FilmProcessor.swift FilmBorder.swift
    RawIspCameraScreen.swift RawIspCameraSelection.swift RawIspFilmLook.swift
    RawIspDngStage3Vignette.swift RawIspRawRegion.swift RawIspLutPreview.swift RawIspLutCube.swift)
set_target_properties(SpektraCam PROPERTIES
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/Info.plist"
    XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER "org.spektracam.app"
    XCODE_ATTRIBUTE_TARGETED_DEVICE_FAMILY "1"
    XCODE_ATTRIBUTE_INSTALL_PATH "$(LOCAL_APPS_DIR)"
    XCODE_ATTRIBUTE_SKIP_INSTALL "NO"
    XCODE_ATTRIBUTE_ASSETCATALOG_COMPILER_APPICON_NAME "AppIcon"
    XCODE_ATTRIBUTE_SWIFT_VERSION "5.0"
    XCODE_ATTRIBUTE_SWIFT_OBJC_BRIDGING_HEADER "${CMAKE_CURRENT_SOURCE_DIR}/../core/spektracam.h"
    XCODE_GENERATE_SCHEME YES)
target_link_libraries(SpektraCam PRIVATE spektracam_raw
    "-framework AVFoundation" "-framework Photos" "-framework MetalKit"
    "-framework CoreImage" "-framework CoreGraphics" "-framework QuartzCore"
    "-framework Metal" "-framework IOSurface" "-framework UIKit" "-framework Foundation"
    "-framework IOKit")
set(resources "${CMAKE_CURRENT_SOURCE_DIR}/../assets/spektra" "${CMAKE_CURRENT_SOURCE_DIR}/../licenses/spektrafilm"
    "${CMAKE_CURRENT_SOURCE_DIR}/resources/en.lproj" "${CMAKE_CURRENT_SOURCE_DIR}/resources/zh.lproj"
    "${CMAKE_CURRENT_SOURCE_DIR}/resources/Assets.xcassets"
    "${CMAKE_CURRENT_SOURCE_DIR}/PrivacyInfo.xcprivacy")
set_source_files_properties(${resources} PROPERTIES MACOSX_PACKAGE_LOCATION Resources)
set(moltenvk_license "${CMAKE_CURRENT_SOURCE_DIR}/../licenses/MoltenVK.txt")
set_source_files_properties("${moltenvk_license}" PROPERTIES MACOSX_PACKAGE_LOCATION Resources)
target_sources(SpektraCam PRIVATE ${resources} "${moltenvk_license}")

# Keep installation-specific account details out of the repository.
set(local_support "${CMAKE_CURRENT_SOURCE_DIR}/../assets/support-links.local.json")
set(bundled_support "${CMAKE_CURRENT_BINARY_DIR}/support-links.local.json")
if(NOT SPEKTRACAM_APP_STORE AND EXISTS "${local_support}")
    configure_file("${local_support}" "${bundled_support}" COPYONLY)
else()
    file(CONFIGURE OUTPUT "${bundled_support}" CONTENT "{}\n")
endif()
set_source_files_properties("${bundled_support}" PROPERTIES MACOSX_PACKAGE_LOCATION Resources)
target_sources(SpektraCam PRIVATE "${bundled_support}")
