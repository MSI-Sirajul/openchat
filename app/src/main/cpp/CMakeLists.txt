cmake_minimum_required(VERSION 3.22.1)

project("credentials_manager")

add_library(
    credentials_manager
    SHARED
    credentials_manager.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    credentials_manager
    ${log-lib}
)
