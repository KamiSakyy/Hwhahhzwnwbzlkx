cmake_minimum_required(VERSION 3.22.1)
project(payload CXX)
add_library(c++_shared SHARED key.cpp)
target_compile_options(c++_shared PRIVATE -fvisibility=hidden)
