cmake_minimum_required(VERSION 3.22)
project(shelf C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_VISIBILITY_PRESET hidden)
add_compile_options(-O2 -g0)

add_library(shelf SHARED jni.c dispatch.c)
target_link_libraries(shelf PRIVATE m)
target_link_options(shelf PRIVATE -s -Wl,--gc-sections)

function(engine_variant prefix)
    add_library(engine_${prefix} OBJECT speaker.c pocket.c)
    target_compile_definitions(engine_${prefix} PRIVATE VARIANT=${prefix}_)
    target_compile_options(engine_${prefix} PRIVATE ${ARGN})
    target_link_libraries(shelf PRIVATE engine_${prefix})
endfunction()

engine_variant(base)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    engine_variant(dot -march=armv8.2-a+dotprod)
endif()
