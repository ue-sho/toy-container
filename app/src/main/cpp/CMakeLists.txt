cmake_minimum_required(VERSION 3.10)
project(toy_container)

add_library(container_native SHARED
    container_native.cpp)

target_link_libraries(container_native)