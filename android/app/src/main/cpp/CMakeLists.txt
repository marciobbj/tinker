cmake_minimum_required(VERSION 3.22.1)
project("pdfreader-core")

# MuPDF setup - you need to provide MuPDF prebuilt or source
# For prebuilt, set MUPDF_ROOT to the directory containing include/ and libs/
set(MUPDF_ROOT "${CMAKE_SOURCE_DIR}/../../../../../../pdf-reader/mupdf" CACHE PATH "Path to MuPDF")

# Core library (shared, to be loaded by Android)
add_library(pdfcore SHARED
    ../../../../../core/src/pdf_engine.cpp
    native-lib.cpp
)

target_include_directories(pdfcore PUBLIC
    ${CMAKE_SOURCE_DIR}/../../../../../../pdf-reader/core/include
    ${MUPDF_ROOT}/include
)

# MuPDF prebuilt shared library (libmupdf_java.so)
set(MUPDF_LIB_PATH ${MUPDF_ROOT}/libs/${ANDROID_ABI}/libmupdf_java.so)

add_library(mupdf_java SHARED IMPORTED)
set_target_properties(mupdf_java PROPERTIES
    IMPORTED_LOCATION ${MUPDF_LIB_PATH}
)

target_link_libraries(pdfcore
    mupdf_java
    android
    jnigraphics
    log
)

target_compile_features(pdfcore PUBLIC cxx_std_17)

# If you want to build MuPDF from source within this project,
# uncomment and adapt:
# add_subdirectory(${MUPDF_ROOT} mupdf-build)
# target_link_libraries(pdfcore mupdf)
