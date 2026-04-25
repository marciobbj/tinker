#include "pdf_engine.h"
#include <mupdf/pdf.h>
#include <cstring>
#include <cmath>

namespace pdfcore {

PdfEngine::PdfEngine() {
    m_ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (m_ctx) {
        fz_register_document_handlers(m_ctx);
    }
}

PdfEngine::~PdfEngine() {
    closeDocument();
    if (m_ctx) {
        fz_drop_context(m_ctx);
    }
}

bool PdfEngine::openDocument(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_ctx) return false;
    
    fz_try(m_ctx) {
        m_doc = fz_open_document(m_ctx, path.c_str());
        m_pageCount = fz_count_pages(m_ctx, m_doc);
    }
    fz_catch(m_ctx) {
        m_doc = nullptr;
        m_pageCount = -1;
        return false;
    }
    return true;
}

void PdfEngine::closeDocument() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_doc && m_ctx) {
        fz_drop_document(m_ctx, m_doc);
        m_doc = nullptr;
    }
    m_pageCount = -1;
}

bool PdfEngine::isOpen() const {
    return m_doc != nullptr;
}

int PdfEngine::getPageCount() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen()) return 0;
    if (m_pageCount < 0) {
        fz_try(m_ctx) {
            m_pageCount = fz_count_pages(m_ctx, m_doc);
        }
        fz_catch(m_ctx) {
            m_pageCount = 0;
        }
    }
    return m_pageCount;
}

std::string PdfEngine::getTitle() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen()) return "";
    char buf[256] = {};
    fz_try(m_ctx) {
        fz_lookup_metadata(m_ctx, m_doc, FZ_META_INFO_TITLE, buf, sizeof(buf));
    }
    fz_catch(m_ctx) {}
    return std::string(buf);
}

PageInfo PdfEngine::getPageInfo(int pageNumber) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    PageInfo info;
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return info;

    fz_page* page = nullptr;
    fz_try(m_ctx) {
        page = fz_load_page(m_ctx, m_doc, pageNumber);
        fz_rect bounds = fz_bound_page(m_ctx, page);
        info.number = pageNumber;
        info.width = bounds.x1 - bounds.x0;
        info.height = bounds.y1 - bounds.y0;
    }
    fz_catch(m_ctx) {}

    if (page) fz_drop_page(m_ctx, page);
    return info;
}

RenderedPage PdfEngine::renderPage(int pageNumber, int targetWidth, int targetHeight, float zoom) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    RenderedPage result;
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return result;

    fz_page* page = nullptr;
    fz_pixmap* pix = nullptr;

    fz_try(m_ctx) {
        page = fz_load_page(m_ctx, m_doc, pageNumber);
        fz_rect bounds = fz_bound_page(m_ctx, page);
        
        float pageWidth = bounds.x1 - bounds.x0;
        float pageHeight = bounds.y1 - bounds.y0;

        // Zoom from the UI is already the final content scale.
        // Fallback to fit-to-target only when zoom is not provided.
        float scale = zoom;
        if (scale <= 0.0f) {
            float scaleX = targetWidth / pageWidth;
            float scaleY = targetHeight / pageHeight;
            scale = std::min(scaleX, scaleY);
        }
        if (!std::isfinite(scale) || scale <= 0.0f) {
            fz_throw(m_ctx, FZ_ERROR_ARGUMENT, "invalid page scale");
        }
        
        int w = static_cast<int>(pageWidth * scale);
        int h = static_cast<int>(pageHeight * scale);
        
        fz_matrix ctm = fz_scale(scale, scale);
        
        pix = fz_new_pixmap_from_page(m_ctx, page, ctm, fz_device_rgb(m_ctx), 0);
        if (!pix) {
            // Fallback: render to RGBA via draw device
            pix = fz_new_pixmap(m_ctx, fz_device_rgb(m_ctx), w, h, nullptr, 0);
            fz_clear_pixmap_with_value(m_ctx, pix, 0xFF);
            fz_device* dev = fz_new_draw_device(m_ctx, ctm, pix);
            fz_run_page(m_ctx, page, dev, fz_identity, nullptr);
            fz_close_device(m_ctx, dev);
            fz_drop_device(m_ctx, dev);
        }

        if (pix) {
            int n = fz_pixmap_components(m_ctx, pix);
            int pw = fz_pixmap_width(m_ctx, pix);
            int ph = fz_pixmap_height(m_ctx, pix);
            unsigned char* samples = fz_pixmap_samples(m_ctx, pix);
            int stride = fz_pixmap_stride(m_ctx, pix);

            result.width = pw;
            result.height = ph;
            result.pixels.resize(pw * ph * 4); // RGBA

            // Optimized: copy row-by-row when possible
            if (n == 3) {
                // RGB -> RGBA: need per-pixel conversion
                for (int y = 0; y < ph; ++y) {
                    unsigned char* src = samples + y * stride;
                    unsigned char* dst = &result.pixels[y * pw * 4];
                    for (int x = 0; x < pw; ++x) {
                        dst[0] = src[0]; // R
                        dst[1] = src[1]; // G
                        dst[2] = src[2]; // B
                        dst[3] = 0xFF;   // A
                        src += 3;
                        dst += 4;
                    }
                }
            } else if (n == 4 && stride == pw * 4) {
                // RGBA direct copy - fastest path
                memcpy(result.pixels.data(), samples, pw * ph * 4);
            } else {
                // Generic path
                for (int y = 0; y < ph; ++y) {
                    for (int x = 0; x < pw; ++x) {
                        unsigned char* src = samples + y * stride + x * n;
                        unsigned char* dst = &result.pixels[(y * pw + x) * 4];
                        dst[0] = src[0]; // R
                        dst[1] = src[1]; // G
                        dst[2] = src[2]; // B
                        dst[3] = (n >= 4) ? src[3] : 0xFF; // A
                    }
                }
            }

            if (m_darkMode) {
                applyDarkMode(result.pixels.data(), pw, ph);
            }
        }
    }
    fz_catch(m_ctx) {}

    if (pix) fz_drop_pixmap(m_ctx, pix);
    if (page) fz_drop_page(m_ctx, page);

    return result;
}

std::string PdfEngine::extractText(int pageNumber) const {
    // Stub: text extraction API changed in newer MuPDF versions.
    // Re-implement using fz_stext_page if needed.
    return "";
}

void PdfEngine::setDarkMode(bool enabled) {
    m_darkMode = enabled;
}

bool PdfEngine::isDarkMode() const {
    return m_darkMode;
}

void PdfEngine::applyDarkMode(uint8_t* pixels, int width, int height) const {
    for (int i = 0; i < width * height; ++i) {
        uint8_t r = pixels[i * 4 + 0];
        uint8_t g = pixels[i * 4 + 1];
        uint8_t b = pixels[i * 4 + 2];

        // Invert colors and reduce contrast for comfortable dark reading
        // Page background becomes dark, text becomes light
        float nr = 255.0f - r;
        float ng = 255.0f - g;
        float nb = 255.0f - b;

        // Darken background slightly (assumes original background is white -> now black)
        // and soften text
        pixels[i * 4 + 0] = static_cast<uint8_t>(nr * 0.85f + 20);
        pixels[i * 4 + 1] = static_cast<uint8_t>(ng * 0.85f + 20);
        pixels[i * 4 + 2] = static_cast<uint8_t>(nb * 0.85f + 20);
    }
}

} // namespace pdfcore
