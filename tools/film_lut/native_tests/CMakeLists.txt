add_executable(film_integration_test film_integration_test.cpp)
target_link_libraries(film_integration_test PRIVATE spektracam_film)
add_test(NAME film_integration COMMAND film_integration_test "${PROJECT_SOURCE_DIR}/assets/spektra")

add_executable(histogram_metering_test histogram_metering_test.cpp)
target_link_libraries(histogram_metering_test PRIVATE spektrafilm_engine)
add_test(NAME histogram_metering COMMAND histogram_metering_test "${PROJECT_SOURCE_DIR}/assets/spektra")

add_executable(meter_parallel_test meter_parallel_test.cpp)
target_link_libraries(meter_parallel_test PRIVATE spektrafilm_engine)
add_test(NAME meter_parallel COMMAND meter_parallel_test)

add_executable(bake_preview_luts bake_preview_luts.cpp)
target_link_libraries(bake_preview_luts PRIVATE spektrafilm_engine)

if(TARGET spektracam_raw AND NOT CMAKE_CROSSCOMPILING)
    add_executable(raw_film_test raw_film_test.cpp)
    target_link_libraries(raw_film_test PRIVATE spektracam_raw)
    add_test(NAME raw_film COMMAND raw_film_test "${PROJECT_SOURCE_DIR}/assets/spektra")
endif()
