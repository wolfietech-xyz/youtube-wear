cmake_minimum_required(VERSION 3.22.1)

project(youtubewear LANGUAGES CXX)

add_library(youtubewear SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(youtubewear ${log-lib})
