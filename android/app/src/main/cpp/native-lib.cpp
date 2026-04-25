#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <memory>
#include <unordered_map>
#include <mutex>

#include "pdf_engine.h"

using namespace pdfcore;

static std::unordered_map<jlong, std::shared_ptr<PdfEngine>> g_engines;
static jlong g_nextId = 1;
static std::mutex g_map_mutex;  // Protects the map only

// Helper: safely get engine shared_ptr under lock, then release lock
static std::shared_ptr<PdfEngine> getEngine(jlong handle) {
    std::lock_guard<std::mutex> lock(g_map_mutex);
    auto it = g_engines.find(handle);
    if (it == g_engines.end()) return nullptr;
    return it->second;  // Returns a copy of shared_ptr (ref-counted)
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pdfreader_core_PdfNative_nativeCreateEngine(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_map_mutex);
    jlong id = g_nextId++;
    g_engines[id] = std::make_shared<PdfEngine>();
    return id;
}

JNIEXPORT void JNICALL
Java_com_pdfreader_core_PdfNative_nativeDestroyEngine(JNIEnv* env, jclass clazz, jlong handle) {
    std::lock_guard<std::mutex> lock(g_map_mutex);
    g_engines.erase(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeOpenDocument(JNIEnv* env, jclass clazz, jlong handle, jstring path) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;

    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = engine->openDocument(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pdfreader_core_PdfNative_nativeCloseDocument(JNIEnv* env, jclass clazz, jlong handle) {
    auto engine = getEngine(handle);
    if (engine) {
        engine->closeDocument();
    }
}

JNIEXPORT jint JNICALL
Java_com_pdfreader_core_PdfNative_nativeGetPageCount(JNIEnv* env, jclass clazz, jlong handle) {
    auto engine = getEngine(handle);
    if (!engine) return 0;
    return engine->getPageCount();
}

JNIEXPORT jstring JNICALL
Java_com_pdfreader_core_PdfNative_nativeGetTitle(JNIEnv* env, jclass clazz, jlong handle) {
    auto engine = getEngine(handle);
    if (!engine) return env->NewStringUTF("");
    return env->NewStringUTF(engine->getTitle().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeRenderPage(JNIEnv* env, jclass clazz, jlong handle,
                                                    jint pageNumber, jobject bitmap,
                                                    jint targetWidth, jint targetHeight, jfloat zoom) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;

    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    // Render outside any lock — this is the heavy operation
    RenderedPage page = engine->renderPage(pageNumber, targetWidth, targetHeight, zoom);

    uint8_t* dst = static_cast<uint8_t*>(pixels);
    int dstStride = info.stride;

    // Clear bitmap to white first
    for (int y = 0; y < (int)info.height; ++y) {
        memset(dst + y * dstStride, 0xFF, info.width * 4);
    }

    if (!page.pixels.empty() && page.width > 0 && page.height > 0) {
        // Center the rendered content in the target bitmap
        int copyWidth = std::min((int)info.width, page.width);
        int copyHeight = std::min((int)info.height, page.height);
        int offsetX = ((int)info.width - copyWidth) / 2;
        int offsetY = ((int)info.height - copyHeight) / 2;

        for (int y = 0; y < copyHeight; ++y) {
            uint8_t* srcRow = page.pixels.data() + y * page.width * 4;
            uint8_t* dstRow = dst + (y + offsetY) * dstStride + offsetX * 4;
            memcpy(dstRow, srcRow, copyWidth * 4);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return (!page.pixels.empty()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pdfreader_core_PdfNative_nativeSetDarkMode(JNIEnv* env, jclass clazz, jlong handle, jboolean enabled) {
    auto engine = getEngine(handle);
    if (engine) {
        engine->setDarkMode(enabled);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_pdfreader_core_PdfNative_nativeGetPageSize(JNIEnv* env, jclass clazz, jlong handle, jint pageNumber) {
    auto engine = getEngine(handle);
    if (!engine) return nullptr;

    PageInfo info = engine->getPageInfo(pageNumber);
    jfloatArray result = env->NewFloatArray(2);
    jfloat values[2] = { info.width, info.height };
    env->SetFloatArrayRegion(result, 0, 2, values);
    return result;
}

} // extern "C"
