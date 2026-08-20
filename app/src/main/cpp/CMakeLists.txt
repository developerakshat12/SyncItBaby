cmake_minimum_required(VERSION 3.22.1)
project("oboe_renderer" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(oboe_renderer SHARED
    NativeAudioBridge.cpp
    OboeRenderer.cpp
)

target_include_directories(oboe_renderer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(oboe_renderer
    oboe::oboe
    log
)
