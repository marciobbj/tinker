<p align="center">
	<img src="assets/tinker-logo.svg" alt="Tinker" width="132" />
</p>

# Tinker

Tinker is a minimalist PDF reader for Android with a C++ core.

## Features

- **C++ core with MuPDF**: fast rendering via the NDK
- **Vertical mode**: continuous vertical scrolling between pages
- **Book mode**: horizontal paging with swipe and tap zones
- **Bookmarks**: automatically saves page, mode, and timestamp
- **Dark mode**: inverts PDF colors for night reading
- **Minimalist**: clean UI with no distractions

## Como compilar

### 1. Get MuPDF

Clone MuPDF and build it for Android, or use a prebuilt copy:

```bash
git clone --recursive https://github.com/ArtifexSoftware/mupdf.git
# Follow the MuPDF Android build instructions
# Place the headers in mupdf/include and the libs in mupdf/libs/<abi>
```

### 2. Build the app

```bash
cd android
./gradlew assembleDebug
```

## Porting the core

The core in `core/` is independent from Android. To port it to another platform:

1. Compile `core/src/pdf_engine.cpp` with MuPDF
2. Implement bindings for your target platform (JavaScript, Python, etc.)
3. Use the `PdfEngine` C++ API

## C++ structure

- `pdfcore::PdfEngine`: opens, renders, and extracts text from PDFs
- `pdfcore::RenderedPage`: in-memory bitmap (RGBA)
- `pdfcore::PageInfo`: page dimensions
- `setDarkMode(bool)`: applies color inversion to pixels

## JNI Bridge

`native-lib.cpp` exposes static methods for `PdfNative.kt`:
- Create/destroy engine
- Open/close document
- Render a page into `android.graphics.Bitmap`
- Return page count and title