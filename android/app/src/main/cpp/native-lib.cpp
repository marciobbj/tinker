#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <vector>

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

    // Render directly into the bitmap pixels — no intermediate buffer
    int outW = 0, outH = 0;
    bool ok = engine->renderPageDirect(
        pageNumber, targetWidth, targetHeight, zoom,
        static_cast<uint8_t*>(pixels), info.stride, info.width, info.height,
        &outW, &outH
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jfloatArray JNICALL
Java_com_pdfreader_core_PdfNative_nativeGetSelectionQuads(JNIEnv* env, jclass clazz, jlong handle, jint pageNumber,
                                                          jfloat ax, jfloat ay, jfloat bx, jfloat by, jint mode) {
    auto engine = getEngine(handle);
    if (!engine) return nullptr;

    std::vector<float> quads = engine->getSelectionQuads(pageNumber, ax, ay, bx, by, mode);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(quads.size()));
    if (!result) return nullptr;
    if (!quads.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(quads.size()), quads.data());
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_pdfreader_core_PdfNative_nativeCopySelection(JNIEnv* env, jclass clazz, jlong handle, jint pageNumber,
                                                      jfloat ax, jfloat ay, jfloat bx, jfloat by, jint mode) {
    auto engine = getEngine(handle);
    if (!engine) return env->NewStringUTF("");

    std::string text = engine->copySelectionText(pageNumber, ax, ay, bx, by, mode);
    return env->NewStringUTF(text.c_str());
}
JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeAddMarkupAnnotation(JNIEnv* env, jclass clazz, jlong handle,
                                                            jint pageNumber,
                                                            jint type, jfloatArray quadArray,
                                                            jfloat r, jfloat g, jfloat b, jfloat opacity) {
    auto engine = getEngine(handle);
    if (!engine || !quadArray) return JNI_FALSE;

    jsize len = env->GetArrayLength(quadArray);
    if (len <= 0 || (len % 8) != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(quadArray, nullptr);
    if (!data) return JNI_FALSE;

    int quadCount = static_cast<int>(len / 8);
    bool ok = engine->addMarkupAnnotation(pageNumber, type, data, quadCount, r, g, b, opacity);
    env->ReleaseFloatArrayElements(quadArray, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Delete all markup annotations of a given type on a page
JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeDeleteMarkupAnnotation(JNIEnv* env, jclass clazz, jlong handle,
                                                               jint pageNumber, jint type, jfloatArray quadArray) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;

    if (!quadArray) return JNI_FALSE;
    jsize len = env->GetArrayLength(quadArray);
    if (len <= 0 || (len % 8) != 0) return JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(quadArray, nullptr);
    if (!data) return JNI_FALSE;

    bool ok = engine->deleteMarkupAnnotation(pageNumber, type, data, static_cast<int>(len / 8));
    env->ReleaseFloatArrayElements(quadArray, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeSaveDocument(JNIEnv* env, jclass clazz, jlong handle) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;
    return engine->saveDocument() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeAddInkAnnotation(JNIEnv* env, jclass clazz, jlong handle,
                                                         jint pageNumber, jfloatArray pointsArray,
                                                         jintArray strokeLengthsArray, jint strokeCount,
                                                         jfloat r, jfloat g, jfloat b, jfloat opacity,
                                                         jfloat lineWidth) {
    auto engine = getEngine(handle);
    if (!engine || !pointsArray || !strokeLengthsArray) return JNI_FALSE;

    jsize pointsLen = env->GetArrayLength(pointsArray);
    jsize lengthsLen = env->GetArrayLength(strokeLengthsArray);
    if (pointsLen <= 0 || lengthsLen != strokeCount) return JNI_FALSE;

    jfloat* points = env->GetFloatArrayElements(pointsArray, nullptr);
    jint* lengths = env->GetIntArrayElements(strokeLengthsArray, nullptr);
    if (!points || !lengths) {
        if (points) env->ReleaseFloatArrayElements(pointsArray, points, JNI_ABORT);
        if (lengths) env->ReleaseIntArrayElements(strokeLengthsArray, lengths, JNI_ABORT);
        return JNI_FALSE;
    }

    bool ok = engine->addInkAnnotation(pageNumber, points, lengths, strokeCount, r, g, b, opacity, lineWidth);

    env->ReleaseFloatArrayElements(pointsArray, points, JNI_ABORT);
    env->ReleaseIntArrayElements(strokeLengthsArray, lengths, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeDeleteInkAnnotation(JNIEnv* env, jclass clazz, jlong handle,
                                                            jint pageNumber, jfloatArray matchPointsArray) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;

    if (!matchPointsArray) return JNI_FALSE;
    jsize len = env->GetArrayLength(matchPointsArray);
    if (len <= 0 || (len % 2) != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(matchPointsArray, nullptr);
    if (!data) return JNI_FALSE;

    bool ok = engine->deleteInkAnnotation(pageNumber, data, static_cast<int>(len / 2));
    env->ReleaseFloatArrayElements(matchPointsArray, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_pdfreader_core_PdfNative_nativeGetInkAnnotations(JNIEnv* env, jclass clazz, jlong handle,
                                                          jint pageNumber) {
    auto engine = getEngine(handle);
    if (!engine) return nullptr;

    std::vector<float> data = engine->getInkAnnotations(pageNumber);
    if (data.empty()) return nullptr;

    jfloatArray result = env->NewFloatArray(data.size());
    if (!result) return nullptr;

    env->SetFloatArrayRegion(result, 0, data.size(), data.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_pdfreader_core_PdfNative_nativeClearInkAnnotations(JNIEnv* env, jclass clazz, jlong handle,
                                                            jint pageNumber) {
    auto engine = getEngine(handle);
    if (!engine) return JNI_FALSE;

    bool ok = engine->clearInkAnnotations(pageNumber);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
