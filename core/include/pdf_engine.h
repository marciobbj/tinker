#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <memory>
#include <mutex>

extern "C" {
#include <mupdf/fitz.h>
}

namespace pdfcore {

struct RenderedPage {
    std::vector<uint8_t> pixels;
    int width = 0;
    int height = 0;
};

struct PageInfo {
    int number = 0;
    float width = 0.0f;
    float height = 0.0f;
};

struct Bookmark {
    int pageNumber = 0;
    float scrollY = 0.0f;
    int64_t timestamp = 0;
};

class PdfEngine {
public:
    PdfEngine();
    ~PdfEngine();

    // Lifecycle
    bool openDocument(const std::string& path);
    void closeDocument();
    bool isOpen() const;

    // Document info
    int getPageCount() const;
    std::string getTitle() const;

    // Page info
    PageInfo getPageInfo(int pageNumber) const;

    // Rendering
    RenderedPage renderPage(int pageNumber, int width, int height, float zoom = 1.0f) const;

    // Direct rendering into a provided RGBA buffer (e.g. a locked Android Bitmap).
    // Avoids the intermediate RenderedPage copy for better performance.
    // Returns true on success. outWidth/outHeight receive the actual rendered dimensions.
    bool renderPageDirect(int pageNumber, int targetWidth, int targetHeight, float zoom,
                          uint8_t* dstPixels, int dstStride, int dstWidth, int dstHeight,
                          int* outWidth, int* outHeight) const;

    // Text extraction (optional, for future search)
    std::string extractText(int pageNumber) const;

    // Text selection
    std::vector<float> getSelectionQuads(int pageNumber, float ax, float ay, float bx, float by, int mode) const;
    std::string copySelectionText(int pageNumber, float ax, float ay, float bx, float by, int mode) const;

    // Markup annotations (highlight/underline/strike-out)
    bool addMarkupAnnotation(int pageNumber, int type, const float* quadData, int quadCount,
                             float r, float g, float b, float opacity);

    // Save document (persist annotations)
    bool saveDocument();

    // Dark mode
    void setDarkMode(bool enabled);
    bool isDarkMode() const;

private:
    fz_context* m_ctx = nullptr;
    fz_document* m_doc = nullptr;
    mutable int m_pageCount = -1;
    bool m_darkMode = false;
    std::string m_path;
    mutable std::mutex m_mutex;
    mutable fz_stext_page* m_textPage = nullptr;
    mutable int m_textPageNumber = -1;

    void applyDarkMode(uint8_t* pixels, int width, int height) const;
    fz_stext_page* getOrCreateTextPage(int pageNumber) const;
    void clearTextCache() const;
};

} // namespace pdfcore
