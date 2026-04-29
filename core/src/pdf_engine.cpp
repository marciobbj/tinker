#include "pdf_engine.h"
#include <mupdf/pdf.h>
#include <mupdf/fitz/util.h>
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
    clearTextCache();
    
    fz_try(m_ctx) {
        m_doc = fz_open_document(m_ctx, path.c_str());
        m_pageCount = fz_count_pages(m_ctx, m_doc);
        m_path = path;
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
    clearTextCache();
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

bool PdfEngine::renderPageDirect(int pageNumber, int targetWidth, int targetHeight, float zoom,
                                  uint8_t* dstPixels, int dstStride, int dstWidth, int dstHeight,
                                  int* outWidth, int* outHeight) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return false;
    if (!dstPixels || dstWidth <= 0 || dstHeight <= 0) return false;

    fz_page* page = nullptr;
    fz_pixmap* pix = nullptr;
    bool success = false;

    fz_try(m_ctx) {
        page = fz_load_page(m_ctx, m_doc, pageNumber);
        fz_rect bounds = fz_bound_page(m_ctx, page);

        float pageWidth = bounds.x1 - bounds.x0;
        float pageHeight = bounds.y1 - bounds.y0;

        float scale = zoom;
        if (scale <= 0.0f) {
            float scaleX = targetWidth / pageWidth;
            float scaleY = targetHeight / pageHeight;
            scale = std::min(scaleX, scaleY);
        }
        if (!std::isfinite(scale) || scale <= 0.0f) {
            fz_throw(m_ctx, FZ_ERROR_ARGUMENT, "invalid page scale");
        }

        fz_matrix ctm = fz_scale(scale, scale);

        pix = fz_new_pixmap_from_page(m_ctx, page, ctm, fz_device_rgb(m_ctx), 0);

        if (pix) {
            int n = fz_pixmap_components(m_ctx, pix);
            int pw = fz_pixmap_width(m_ctx, pix);
            int ph = fz_pixmap_height(m_ctx, pix);
            unsigned char* samples = fz_pixmap_samples(m_ctx, pix);
            int srcStride = fz_pixmap_stride(m_ctx, pix);

            if (outWidth) *outWidth = pw;
            if (outHeight) *outHeight = ph;

            // Clear destination to white
            for (int y = 0; y < dstHeight; ++y) {
                memset(dstPixels + y * dstStride, 0xFF, dstWidth * 4);
            }

            // Center the rendered content in the destination
            int copyWidth = std::min(dstWidth, pw);
            int copyHeight = std::min(dstHeight, ph);
            int offsetX = (dstWidth - copyWidth) / 2;
            int offsetY = (dstHeight - copyHeight) / 2;

            // Write directly into the destination buffer — no intermediate allocation
            if (n == 3) {
                for (int y = 0; y < copyHeight; ++y) {
                    unsigned char* src = samples + y * srcStride;
                    unsigned char* dst = dstPixels + (y + offsetY) * dstStride + offsetX * 4;
                    for (int x = 0; x < copyWidth; ++x) {
                        dst[0] = src[0];
                        dst[1] = src[1];
                        dst[2] = src[2];
                        dst[3] = 0xFF;
                        src += 3;
                        dst += 4;
                    }
                }
            } else if (n == 4) {
                for (int y = 0; y < copyHeight; ++y) {
                    unsigned char* src = samples + y * srcStride;
                    unsigned char* dst = dstPixels + (y + offsetY) * dstStride + offsetX * 4;
                    memcpy(dst, src, copyWidth * 4);
                }
            } else {
                for (int y = 0; y < copyHeight; ++y) {
                    for (int x = 0; x < copyWidth; ++x) {
                        unsigned char* src = samples + y * srcStride + x * n;
                        unsigned char* dst = dstPixels + (y + offsetY) * dstStride + (x + offsetX) * 4;
                        dst[0] = src[0];
                        dst[1] = src[1];
                        dst[2] = src[2];
                        dst[3] = (n >= 4) ? src[3] : 0xFF;
                    }
                }
            }

            if (m_darkMode) {
                applyDarkMode(dstPixels, dstWidth, dstHeight);
            }

            success = true;
        }
    }
    fz_catch(m_ctx) {}

    if (pix) fz_drop_pixmap(m_ctx, pix);
    if (page) fz_drop_page(m_ctx, page);

    return success;
}

std::string PdfEngine::extractText(int pageNumber) const {
    // Stub: text extraction API changed in newer MuPDF versions.
    // Re-implement using fz_stext_page if needed.
    return "";
}

std::vector<float> PdfEngine::getSelectionQuads(int pageNumber, float ax, float ay, float bx, float by, int mode) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<float> result;
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return result;

    fz_stext_page* textPage = getOrCreateTextPage(pageNumber);
    if (!textPage) return result;

    fz_point a = fz_make_point(ax, ay);
    fz_point b = fz_make_point(bx, by);
    int selMode = (mode < FZ_SELECT_CHARS || mode > FZ_SELECT_LINES) ? FZ_SELECT_WORDS : mode;

    fz_try(m_ctx) {
        fz_snap_selection(m_ctx, textPage, &a, &b, selMode);

        int cap = 64;
        std::vector<fz_quad> quads;
        while (true) {
            quads.resize(cap);
            int count = fz_highlight_selection(m_ctx, textPage, a, b, quads.data(), cap);
            if (count < cap || cap >= 2048) {
                quads.resize(count);
                break;
            }
            cap *= 2;
        }

        result.resize(quads.size() * 8);
        for (size_t i = 0; i < quads.size(); ++i) {
            const fz_quad& q = quads[i];
            size_t o = i * 8;
            result[o + 0] = q.ul.x;
            result[o + 1] = q.ul.y;
            result[o + 2] = q.ur.x;
            result[o + 3] = q.ur.y;
            result[o + 4] = q.ll.x;
            result[o + 5] = q.ll.y;
            result[o + 6] = q.lr.x;
            result[o + 7] = q.lr.y;
        }
    }
    fz_catch(m_ctx) {
        result.clear();
    }

    return result;
}

std::string PdfEngine::copySelectionText(int pageNumber, float ax, float ay, float bx, float by, int mode) const {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return "";

    fz_stext_page* textPage = getOrCreateTextPage(pageNumber);
    if (!textPage) return "";

    fz_point a = fz_make_point(ax, ay);
    fz_point b = fz_make_point(bx, by);
    int selMode = (mode < FZ_SELECT_CHARS || mode > FZ_SELECT_LINES) ? FZ_SELECT_WORDS : mode;

    std::string result;
    fz_try(m_ctx) {
        fz_snap_selection(m_ctx, textPage, &a, &b, selMode);
        char* text = fz_copy_selection(m_ctx, textPage, a, b, 0);
        if (text) {
            result.assign(text);
            fz_free(m_ctx, text);
        }
    }
    fz_catch(m_ctx) {
        return "";
    }

    return result;
}

bool PdfEngine::addMarkupAnnotation(int pageNumber, int type, const float* quadData, int quadCount,
                                    float r, float g, float b, float opacity) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen() || pageNumber < 0 || pageNumber >= m_pageCount) return false;
    if (!quadData || quadCount <= 0) return false;

    bool ok = false;
    fz_page* page = nullptr;
    pdf_annot* annot = nullptr;

    fz_try(m_ctx) {
        if (!pdf_specifics(m_ctx, m_doc)) {
            fz_throw(m_ctx, FZ_ERROR_GENERIC, "not a PDF document");
        }

        page = fz_load_page(m_ctx, m_doc, pageNumber);
        pdf_page* pdfPage = pdf_page_from_fz_page(m_ctx, page);
        if (!pdfPage) fz_throw(m_ctx, FZ_ERROR_GENERIC, "invalid PDF page");

        annot = pdf_create_annot(m_ctx, pdfPage, static_cast<decltype(PDF_ANNOT_HIGHLIGHT)>(type));
        if (!annot) fz_throw(m_ctx, FZ_ERROR_GENERIC, "failed to create annotation");

        std::vector<fz_quad> quads;
        quads.reserve(static_cast<size_t>(quadCount));
        for (int i = 0; i < quadCount; ++i) {
            size_t o = static_cast<size_t>(i) * 8;
            fz_quad q;
            q.ul = fz_make_point(quadData[o + 0], quadData[o + 1]);
            q.ur = fz_make_point(quadData[o + 2], quadData[o + 3]);
            q.ll = fz_make_point(quadData[o + 4], quadData[o + 5]);
            q.lr = fz_make_point(quadData[o + 6], quadData[o + 7]);
            quads.push_back(q);
        }

        const float color[3] = { r, g, b };
        pdf_set_annot_color(m_ctx, annot, 3, color);
        pdf_set_annot_opacity(m_ctx, annot, opacity);
        pdf_set_annot_quad_points(m_ctx, annot, quadCount, quads.data());
        pdf_update_page(m_ctx, pdfPage);
        ok = true;
    }
    fz_catch(m_ctx) {
        ok = false;
    }

    if (annot) pdf_drop_annot(m_ctx, annot);
    if (page) fz_drop_page(m_ctx, page);

    return ok;
}

bool PdfEngine::saveDocument() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!isOpen() || m_path.empty()) return false;

    bool ok = false;
    fz_try(m_ctx) {
        pdf_document* pdfdoc = pdf_specifics(m_ctx, m_doc);
        if (!pdfdoc) {
            fz_throw(m_ctx, FZ_ERROR_GENERIC, "not a PDF document");
        }
        pdf_write_options opts = pdf_default_write_options;
        opts.do_incremental = 1;
        pdf_save_document(m_ctx, pdfdoc, m_path.c_str(), &opts);
        ok = true;
    }
    fz_catch(m_ctx) {
        ok = false;
    }
    return ok;
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

fz_stext_page* PdfEngine::getOrCreateTextPage(int pageNumber) const {
    if (m_textPage && m_textPageNumber == pageNumber) return m_textPage;

    clearTextCache();
    if (!m_ctx || !m_doc) return nullptr;

    fz_page* page = nullptr;
    fz_stext_page* textPage = nullptr;
    fz_stext_options opts;

    fz_try(m_ctx) {
        page = fz_load_page(m_ctx, m_doc, pageNumber);
        fz_init_stext_options(m_ctx, &opts);
        textPage = fz_new_stext_page_from_page(m_ctx, page, &opts);
    }
    fz_catch(m_ctx) {
        if (page) fz_drop_page(m_ctx, page);
        return nullptr;
    }

    if (page) fz_drop_page(m_ctx, page);
    m_textPage = textPage;
    m_textPageNumber = pageNumber;
    return m_textPage;
}

void PdfEngine::clearTextCache() const {
    if (m_textPage && m_ctx) {
        fz_drop_stext_page(m_ctx, m_textPage);
    }
    m_textPage = nullptr;
    m_textPageNumber = -1;
}

} // namespace pdfcore
