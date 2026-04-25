cmake_minimum_required(VERSION 3.14)
project(pdf-core LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MuPDF root path - adjust as needed
set(MUPDF_ROOT "${CMAKE_SOURCE_DIR}/../mupdf" CACHE PATH "Path to MuPDF")

# Core static library (for testing / standalone use)
add_library(pdfcore-static STATIC
    src/pdf_engine.cpp
)

target_include_directories(pdfcore-static PUBLIC
    ${CMAKE_SOURCE_DIR}/include
    ${MUPDF_ROOT}/include
)

# Find MuPDF libraries
find_library(MUPDF_LIB mupdf PATHS ${MUPDF_ROOT}/build/release ${MUPDF_ROOT}/build/debug)
find_library(MUPDF_THIRD_LIB mupdfthird PATHS ${MUPDF_ROOT}/build/release ${MUPDF_ROOT}/build/debug)

if(MUPDF_LIB AND MUPDF_THIRD_LIB)
    target_link_libraries(pdfcore-static PUBLIC
        ${MUPDF_LIB}
        ${MUPDF_THIRD_LIB}
    )
endif()

# Simple CLI test app (optional)
option(BUILD_TEST_APP "Build test CLI app" OFF)
if(BUILD_TEST_APP)
    add_executable(pdfreader-test test/main.cpp)
    target_link_libraries(pdfreader-test PRIVATE pdfcore-static)
endif()
