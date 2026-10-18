cmake_minimum_required(VERSION 3.22.1)
project(lscodec C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_library(lscodec SHARED
        lscodec.cpp
        codec.cpp
        aes.c
        lzma/LzmaEnc.c
        lzma/LzmaDec.c
        lzma/LzFind.c
        lzma/LzFindMt.c
        lzma/Alloc.c
        lzma/CpuArch.c
        lzma/Bra86.c
        lzma/Threads.c
)

target_include_directories(lscodec PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/lzma
)

target_compile_options(lscodec PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(lscodec PRIVATE -Wl,--gc-sections)

find_library(log-lib log)
target_link_libraries(lscodec ${log-lib})
