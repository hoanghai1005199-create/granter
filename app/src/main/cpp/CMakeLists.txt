cmake_minimum_required(VERSION 3.22.1)
project("license")

add_library(license SHARED license.cpp)

target_link_libraries(license android log)
