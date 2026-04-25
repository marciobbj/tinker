# Agent Instructions

## Build — Primeira vez

### 1. Clonar e compilar o MuPDF

O MuPDF precisa ser compilado separadamente via `ndk-build` (não via CMake do app):

```bash
git clone --recursive https://github.com/ArtifexSoftware/mupdf.git
cd mupdf
export ANDROID_NDK=/opt/android-sdk/ndk/28.2.13676358
$ANDROID_NDK/ndk-build -j$(nproc) \
    APP_BUILD_SCRIPT=platform/java/Android.mk \
    APP_PROJECT_PATH=build/android \
    APP_PLATFORM=android-16 \
    APP_OPTIM=release
```

Isso gera `libmupdf_java.so` em `build/android/libs/<abi>/`.

### 2. Copiar libs para o projeto

```bash
mkdir -p mupdf/libs/{arm64-v8a,armeabi-v7a,x86_64}
cp mupdf/build/android/libs/arm64-v8a/libmupdf_java.so mupdf/libs/arm64-v8a/
cp mupdf/build/android/libs/armeabi-v7a/libmupdf_java.so mupdf/libs/armeabi-v7a/
cp mupdf/build/android/libs/x86_64/libmupdf_java.so mupdf/libs/x86_64/
```

**Importante:** O `CMakeLists.txt` do app espera `mupdf/libs/${ANDROID_ABI}/libmupdf_java.so` e já linka ela como `mupdf_java`.

### 3. Gerar Gradle Wrapper

```bash
cd android
# Use Gradle standalone (8.2 recomendado)
/tmp/gradle-8.2/bin/gradle wrapper --gradle-version 8.2
```

### 4. Build do App

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_NDK=/opt/android-sdk/ndk/28.2.13676358
export ANDROID_NDK_HOME=$ANDROID_NDK
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

### Variáveis de ambiente necessárias

| Variável | Exemplo |
|----------|---------|
| `ANDROID_HOME` | `/opt/android-sdk` |
| `ANDROID_NDK` | `/opt/android-sdk/ndk/28.2.13676358` |
| `ANDROID_NDK_HOME` | `$ANDROID_NDK` |

## Build — Após mudanças no código

```bash
cd android
./gradlew assembleDebug
```

Se mudar C++ ou CMake, limpe o cache primeiro:
```bash
rm -rf app/.cxx app/build .gradle
./gradlew assembleDebug
```

## Convenções

- C++: `snake_case`, namespaces `pdfcore`
- Kotlin: `camelCase`, packages `com.pdfreader.*`
- JNI naming: `Java_com_pdfreader_core_PdfNative_native*`

## Dependências

- MuPDF (C library) — compilado manualmente via ndk-build
- Android Gradle Plugin 8.2.0
- Kotlin 1.9.20
- Material3
- minSdk 24, targetSdk 34

## Testar

- Android: `./gradlew assembleDebug`
- Core desktop: `cmake -B build core && cmake --build build`

## Notas

- O app copia PDFs via ContentResolver para cache local antes de abrir
- Bookmarks são salvos em SharedPreferences como JSON
- Dark mode inverte cores no C++ (pixels ARGB) e aplica tema Android
- `PdfNative.kt` carrega `libmupdf_java.so` antes de `libpdfcore.so`
