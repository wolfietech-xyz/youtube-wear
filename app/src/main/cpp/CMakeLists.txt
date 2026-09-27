cmake_minimum_required(VERSION 3.22.1)

project(youtubewear LANGUAGES C CXX)

add_library(youtubewear SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(youtubewear ${log-lib})

# QuickJS-ng's qjs CLI, which yt-dlp runs to solve YouTube's JavaScript challenges.
# Android only lets apps execute files from their native library directory, so it is
# built as an executable named like a library and installed alongside the real ones.
set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs-ng)
add_executable(qjs
    ${QJS_DIR}/qjs.c
    ${QJS_DIR}/gen/repl.c
    ${QJS_DIR}/gen/standalone.c
    ${QJS_DIR}/quickjs.c
    ${QJS_DIR}/quickjs-libc.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/dtoa.c
)
target_include_directories(qjs PRIVATE ${QJS_DIR})
target_compile_definitions(qjs PRIVATE _GNU_SOURCE)
target_compile_options(qjs PRIVATE -O2 -w)
target_link_libraries(qjs m dl)
set_target_properties(qjs PROPERTIES OUTPUT_NAME "libqjs.so")
