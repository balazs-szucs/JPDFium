// jpdfium_advanced.cpp - Pattern matching, font analysis, metadata and text utilities.

#include <fpdf_save.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"

// Diagnostics: monotonically counts whole-document QPDF snapshot operations
// (one increment per load_qpdf). Relaxed ordering: this is a counter, not a
// synchronisation primitive. The reset is the only state-changing entry point.
static std::atomic<int64_t> g_native_op_count{0};

// Work counters for the font-reference graph and program cache. Diagnostics
// only, like the snapshot counter: they measure the plan/apply paths so their
// scaling can be benchmarked, and never gate behaviour. `reference_nodes` is the
// number of resource dictionaries visited while building the graph,
// `reference_lookups` the number of font-object lookups against the reverse
// index, `programs_analyzed` the number of distinct font programs actually
// decoded, and `program_bytes_hashed` the total program bytes hashed.
static std::atomic<int64_t> g_reference_nodes{0};
static std::atomic<int64_t> g_reference_lookups{0};
static std::atomic<int64_t> g_programs_analyzed{0};
static std::atomic<int64_t> g_program_bytes_hashed{0};

int64_t jpdfium_native_op_count(void) noexcept {
    return g_native_op_count.load(std::memory_order_relaxed);
}

void jpdfium_native_op_reset(void) noexcept {
    g_native_op_count.store(0, std::memory_order_relaxed);
}

int32_t jpdfium_native_metrics(char** json) noexcept {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    std::string out = "{\"snapshot_ops\":" + std::to_string(g_native_op_count.load(std::memory_order_relaxed))
                    + ",\"reference_nodes\":" + std::to_string(g_reference_nodes.load(std::memory_order_relaxed))
                    + ",\"reference_lookups\":" + std::to_string(g_reference_lookups.load(std::memory_order_relaxed))
                    + ",\"programs_analyzed\":" + std::to_string(g_programs_analyzed.load(std::memory_order_relaxed))
                    + ",\"program_bytes_hashed\":" + std::to_string(g_program_bytes_hashed.load(std::memory_order_relaxed))
                    + "}";
    *json = strdup(out.c_str());
    return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

void jpdfium_native_metrics_reset(void) noexcept {
    g_native_op_count.store(0, std::memory_order_relaxed);
    g_reference_nodes.store(0, std::memory_order_relaxed);
    g_reference_lookups.store(0, std::memory_order_relaxed);
    g_programs_analyzed.store(0, std::memory_order_relaxed);
    g_program_bytes_hashed.store(0, std::memory_order_relaxed);
}

// PCRE2 JIT Pattern Engine

#ifdef JPDFIUM_HAS_PCRE2
#define PCRE2_CODE_UNIT_WIDTH 8
#include <pcre2.h>

struct Pcre2Pattern {
    pcre2_code* code;
    pcre2_match_data* match_data;
};

int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t flags, int64_t* handle) {
    try {
        if (!pattern || !handle) return JPDFIUM_ERR_INVALID;

        uint32_t pcre2_opts = 0;
        if (flags & JPDFIUM_PCRE2_CASELESS) pcre2_opts |= PCRE2_CASELESS;
        if (flags & JPDFIUM_PCRE2_MULTILINE) pcre2_opts |= PCRE2_MULTILINE;
        if (flags & JPDFIUM_PCRE2_DOTALL) pcre2_opts |= PCRE2_DOTALL;
        if (flags & JPDFIUM_PCRE2_UTF) pcre2_opts |= PCRE2_UTF;
        if (flags & JPDFIUM_PCRE2_UCP) pcre2_opts |= PCRE2_UCP;

        int errcode;
        PCRE2_SIZE erroffset;
        pcre2_code* code = pcre2_compile((PCRE2_SPTR)pattern, PCRE2_ZERO_TERMINATED, pcre2_opts,
                                         &errcode, &erroffset, nullptr);

        if (!code) return JPDFIUM_ERR_INVALID;

        // JIT compile for speed - failure is non-fatal (falls back to interpreter)
        pcre2_jit_compile(code, PCRE2_JIT_COMPLETE);

        auto* pw = new Pcre2Pattern();
        pw->code = code;
        pw->match_data = pcre2_match_data_create_from_pattern(code, nullptr);

        Pcre2HandleRegistry::instance().add(pw);
        *handle = reinterpret_cast<int64_t>(pw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_pcre2_match_all(int64_t pattern_handle, const char* text, char** json_result) {
    try {
        if (!text || !json_result) return JPDFIUM_ERR_INVALID;
        auto* pw = reinterpret_cast<Pcre2Pattern*>(pattern_handle);
        if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return JPDFIUM_ERR_INVALID;
        if (!pw->code) return JPDFIUM_ERR_INVALID;

        PCRE2_SIZE subject_len = strlen(text);
        std::string json = "[";
        bool first = true;
        PCRE2_SIZE offset = 0;

        while (offset < subject_len) {
            int rc = pcre2_match(pw->code, (PCRE2_SPTR)text, subject_len, offset, 0, pw->match_data,
                                 nullptr);
            if (rc < 0) break;

            PCRE2_SIZE* ovector = pcre2_get_ovector_pointer(pw->match_data);
            PCRE2_SIZE start = ovector[0];
            PCRE2_SIZE end = ovector[1];

            if (!first) json += ",";
            first = false;

            std::string match_text(text + start, end - start);
            // Escape JSON special characters in the match text
            std::string escaped;
            for (char c : match_text) {
                switch (c) {
                    case '"':
                        escaped += "\\\"";
                        break;
                    case '\\':
                        escaped += "\\\\";
                        break;
                    case '\n':
                        escaped += "\\n";
                        break;
                    case '\r':
                        escaped += "\\r";
                        break;
                    case '\t':
                        escaped += "\\t";
                        break;
                    default:
                        escaped += c;
                }
            }

            char buf[256];
            snprintf(buf, sizeof(buf), "{\"start\":%zu,\"end\":%zu,\"match\":\"%s\"}",
                     (size_t)start, (size_t)end, escaped.c_str());
            json += buf;

            offset = (end > start) ? end : end + 1;  // advance past zero-length matches
        }

        json += "]";
        *json_result = strdup(json.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_pcre2_free(int64_t pattern_handle) noexcept {
    // Membership is checked before any dereference, so a fabricated handle is
    // rejected rather than read. See HandleRegistry for the lifetime contract.
    auto* pw = reinterpret_cast<Pcre2Pattern*>(pattern_handle);
    if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return;
    Pcre2HandleRegistry::instance().remove(pw);
    if (pw->match_data) pcre2_match_data_free(pw->match_data);
    if (pw->code) pcre2_code_free(pw->code);
    delete pw;
}

#else  // !JPDFIUM_HAS_PCRE2

int32_t jpdfium_pcre2_compile(const char*, uint32_t, int64_t* handle) {
    if (handle) *handle = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_pcre2_match_all(int64_t, const char*, char** json_result) {
    if (json_result) *json_result = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}
void jpdfium_pcre2_free(int64_t) noexcept {}

#endif  // JPDFIUM_HAS_PCRE2

// Luhn Algorithm - pure C, no dependencies

int32_t jpdfium_luhn_validate(const char* number) {
    try {
        if (!number) return 0;

        int digits[32];
        int n = 0;
        for (const char* p = number; *p && n < 32; ++p) {
            if (*p >= '0' && *p <= '9')
                digits[n++] = *p - '0';
            else if (*p != ' ' && *p != '-')
                return 0;  // invalid character
        }

        // Credit cards: 13-19 digits (Visa 13/16/19, MC/Amex/Discover 15-16)
        if (n < 13 || n > 19) return 0;

        int sum = 0;
        for (int i = n - 1, alt = 0; i >= 0; --i, alt ^= 1) {
            int d = digits[i];
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (sum % 10 == 0) ? 1 : 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// FlashText Trie-based Keyword NER - O(n) matching

namespace {

struct TrieNode {
    std::unordered_map<char, std::unique_ptr<TrieNode>> children;
    std::string keyword;  // non-empty at terminal nodes
    std::string label;    // entity label at terminal nodes
};

struct FlashTextProcessor {
    std::unique_ptr<TrieNode> root;
    bool case_sensitive;

    FlashTextProcessor() : root(std::make_unique<TrieNode>()), case_sensitive(false) {}

    void addKeyword(const char* keyword, const char* label) {
        if (!keyword) return;
        TrieNode* node = root.get();
        for (const char* p = keyword; *p; ++p) {
            char c = case_sensitive
                         ? *p
                         : static_cast<char>(std::tolower(static_cast<unsigned char>(*p)));
            auto it = node->children.find(c);
            if (it == node->children.end()) {
                node->children[c] = std::make_unique<TrieNode>();
            }
            node = node->children[c].get();
        }
        node->keyword = keyword;
        node->label = label ? label : "";
    }

    std::string findAll(const char* text) {
        if (!text) return "[]";
        std::string result = "[";
        bool first = true;
        size_t textLen = strlen(text);

        for (size_t i = 0; i < textLen;) {
            TrieNode* node = root.get();
            size_t matchEnd = 0;
            std::string matchKeyword, matchLabel;
            bool found = false;

            for (size_t j = i; j < textLen; ++j) {
                char c = case_sensitive
                             ? text[j]
                             : static_cast<char>(std::tolower(static_cast<unsigned char>(text[j])));
                auto it = node->children.find(c);
                if (it == node->children.end()) break;
                node = it->second.get();
                if (!node->keyword.empty()) {
                    // Check word boundary: must be at start/end or adjacent to non-alphanumeric
                    bool leftBound = (j - (j - i) == 0) || !isalnum(text[j - (j - i) - 1]);
                    bool rightBound = (j + 1 >= textLen) || !isalnum(text[j + 1]);
                    if (leftBound && rightBound) {
                        matchEnd = j + 1;
                        matchKeyword = node->keyword;
                        matchLabel = node->label;
                        found = true;
                    }
                }
            }

            if (found) {
                if (!first) result += ",";
                first = false;
                char buf[512];
                snprintf(buf, sizeof(buf),
                         "{\"start\":%zu,\"end\":%zu,\"keyword\":\"%s\",\"label\":\"%s\"}", i,
                         matchEnd, matchKeyword.c_str(), matchLabel.c_str());
                result += buf;
                i = matchEnd;
            } else {
                ++i;
            }
        }

        result += "]";
        return result;
    }
};

}  // anonymous namespace

int32_t jpdfium_flashtext_create(int64_t* handle) {
    try {
        if (!handle) return JPDFIUM_ERR_INVALID;
        auto* ft = new FlashTextProcessor();
        FlashTextHandleRegistry::instance().add(ft);
        *handle = reinterpret_cast<int64_t>(ft);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!keyword) return JPDFIUM_ERR_INVALID;
        ft->addKeyword(keyword, label);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keywords_json(int64_t handle, const char* json) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json) return JPDFIUM_ERR_INVALID;

        // Simple JSON parser for: [{"keyword":"...","label":"..."}, ...]
        const char* p = json;
        while (*p) {
            const char* kw_start = strstr(p, "\"keyword\":\"");
            if (!kw_start) break;
            kw_start += 11;  // skip "keyword":"
            const char* kw_end = strchr(kw_start, '"');
            if (!kw_end) break;
            std::string keyword(kw_start, kw_end - kw_start);

            std::string label;
            const char* lb_start = strstr(kw_end, "\"label\":\"");
            if (lb_start) {
                lb_start += 9;
                const char* lb_end = strchr(lb_start, '"');
                if (lb_end) label.assign(lb_start, lb_end - lb_start);
            }

            ft->addKeyword(keyword.c_str(), label.c_str());
            p = kw_end + 1;
        }
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json_result) return JPDFIUM_ERR_INVALID;
        std::string result = ft->findAll(text);
        *json_result = strdup(result.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_flashtext_free(int64_t handle) noexcept {
    // Identity checked before the delete; a fabricated handle is a no-op.
    auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
    if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return;
    FlashTextHandleRegistry::instance().remove(ft);
    delete ft;
}

// Font Normalization Pipeline - FreeType + HarfBuzz hb-subset

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H
#include FT_TRUETYPE_TABLES_H

#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <hb-subset.h>
#include <hb.h>

#include <set>

#include "jpdfium_internal.h"

static FT_Library ft_lib = nullptr;

static void ensure_freetype_init() {
    if (!ft_lib) FT_Init_FreeType(&ft_lib);
}

int32_t jpdfium_font_get_data(int64_t page, int32_t font_index, uint8_t** data, int64_t* len) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page || !data || !len) return JPDFIUM_ERR_INVALID;
        *data = nullptr;
        *len = 0;

        // Enumerate unique fonts on the page by walking text objects.
        int objCount = FPDFPage_CountObjects(pw->page);
        std::vector<FPDF_FONT> fonts;  // ordered list of unique fonts
        std::set<FPDF_FONT> seen;

        for (int i = 0; i < objCount; i++) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;
            FPDF_FONT font = FPDFTextObj_GetFont(obj);
            if (font && seen.insert(font).second) {
                fonts.push_back(font);
            }
        }

        if (font_index < 0 || font_index >= static_cast<int32_t>(fonts.size()))
            return JPDFIUM_ERR_NOT_FOUND;

        FPDF_FONT font = fonts[font_index];

        // Query required buffer size
        size_t buflen = 0;
        if (!FPDFFont_GetFontData(font, nullptr, 0, &buflen) || buflen == 0)
            return JPDFIUM_ERR_NOT_FOUND;

        // Allocate and fill
        uint8_t* buf = static_cast<uint8_t*>(malloc(buflen));
        if (!buf) return JPDFIUM_ERR_NATIVE;

        size_t actual = 0;
        if (!FPDFFont_GetFontData(font, buf, buflen, &actual)) {
            free(buf);
            return JPDFIUM_ERR_NATIVE;
        }

        *data = buf;
        *len = static_cast<int64_t>(actual);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_classify(const uint8_t* data, int64_t len, char** json) {
    try {
        if (!data || len <= 0 || !json) return JPDFIUM_ERR_INVALID;
        ensure_freetype_init();

        FT_Face face;
        FT_Error err = FT_New_Memory_Face(ft_lib, data, (FT_Long)len, 0, &face);
        if (err) return JPDFIUM_ERR_INVALID;

        const char* type_str;
        bool sfnt = FT_IS_SFNT(face);
        bool has_cmap = (face->num_charmaps > 0);
        bool has_kerning = FT_HAS_KERNING(face);

        // Detect font type
        if (face->face_flags & FT_FACE_FLAG_SFNT) {
            // Check for CFF: presence of CFF table in sfnt container
            FT_ULong cff_len = 0;
            if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &cff_len) ==
                0)
                type_str = "CFF";
            else if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr,
                                        &cff_len) == 0)
                type_str = "CFF2";
            else
                type_str = "TrueType";
        } else {
            type_str = "Type1";
        }

        // Detect subset prefix (e.g. "ABCDEF+Arial")
        bool is_subset = false;
        if (face->family_name) {
            const char* plus = strchr(face->family_name, '+');
            if (plus && (plus - face->family_name) == 6) {
                is_subset = true;
                for (const char* c = face->family_name; c < plus; ++c) {
                    if (*c < 'A' || *c > 'Z') {
                        is_subset = false;
                        break;
                    }
                }
            }
        }

        char buf[512];
        snprintf(buf, sizeof(buf),
                 "{\"type\":\"%s\",\"sfnt\":%s,\"has_cmap\":%s,"
                 "\"num_glyphs\":%ld,\"units_per_em\":%d,\"has_kerning\":%s,"
                 "\"is_subset\":%s,\"family\":\"%s\"}",
                 type_str, sfnt ? "true" : "false", has_cmap ? "true" : "false", face->num_glyphs,
                 (int)face->units_per_EM, has_kerning ? "true" : "false",
                 is_subset ? "true" : "false", face->family_name ? face->family_name : "");

        FT_Done_Face(face);
        *json = strdup(buf);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_covers_text(const uint8_t* data, int64_t len, const int32_t* codepoints,
                                 int32_t count, uint8_t* out_covered) {
    try {
        if (!data || len <= 0 || !codepoints || count <= 0 || !out_covered)
            return JPDFIUM_ERR_INVALID;
        ensure_freetype_init();

        FT_Face face;
        if (FT_New_Memory_Face(ft_lib, data, (FT_Long)len, 0, &face)) return JPDFIUM_ERR_INVALID;
        // Map codepoints, not glyph ids: without this a symbol charmap misreports.
        if (face->num_charmaps > 0) FT_Select_Charmap(face, FT_ENCODING_UNICODE);

        for (int32_t i = 0; i < count; ++i) {
            uint32_t cp = (uint32_t)codepoints[i];
            // Index 0 is the .notdef placeholder, never a real glyph.
            out_covered[i] = (cp <= 0x10FFFF && FT_Get_Char_Index(face, cp) != 0) ? 1 : 0;
        }
        FT_Done_Face(face);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_text_shape(const uint8_t* font_data, int64_t font_len, const char* utf8_text,
                           float font_size, char** json) {
    try {
        // 26.6 scale: reject sizes whose scaled value overflows the int cast.
        if (!font_data || font_len <= 0 || !utf8_text || !json || !std::isfinite(font_size) ||
            font_size <= 0 || font_size * 64.0 > (double)INT32_MAX)
            return JPDFIUM_ERR_INVALID;
        hb_blob_t* blob = hb_blob_create((const char*)font_data, (unsigned int)font_len,
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);
        // hb_face_create never fails: corrupt bytes yield an empty face whose
        // every codepoint maps to glyph 0. Reject those, as font_covers_text does.
        if (hb_face_get_glyph_count(face) == 0) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }
        hb_font_t* font = hb_font_create(face);
        hb_face_destroy(face);
        // Advances come out in 26.6 fractional points at this scale.
        hb_font_set_scale(font, (int)(font_size * 64), (int)(font_size * 64));

        hb_buffer_t* buf = hb_buffer_create();
        hb_buffer_add_utf8(buf, utf8_text, -1, 0, -1);
        // Script/direction/language default from content; same as plain hb-shape.
        // One buffer is one run: mixed direction/script must be split by the caller.
        hb_buffer_guess_segment_properties(buf);
        hb_shape(font, buf, nullptr, 0);

        unsigned int count = 0;
        hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &count);
        hb_glyph_position_t* pos = hb_buffer_get_glyph_positions(buf, &count);

        std::string out = "[";
        char entry[128];
        for (unsigned int i = 0; i < count; ++i) {
            if (i) out += ",";
            snprintf(entry, sizeof(entry),
                     "{\"g\":%u,\"ax\":%d,\"ay\":%d,\"dx\":%d,\"dy\":%d,\"cluster\":%u}",
                     infos[i].codepoint, pos[i].x_advance, pos[i].y_advance, pos[i].x_offset,
                     pos[i].y_offset, infos[i].cluster);
            out += entry;
        }
        out += "]";
        hb_buffer_destroy(buf);
        hb_font_destroy(font);
        *json = strdup(out.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_repair_widths(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    try {
        // Real implementation would:
        // 1. For each font, load into FreeType
        // 2. For each glyph: FT_Load_Glyph + linearHoriAdvance
        // 3. Convert to PDF glyph space: (advance * 1000) / units_per_EM
        // 4. Build corrected /W array
        // 5. Replace via qpdf
        (void)doc;
        (void)page_index;
        if (fonts_fixed) *fonts_fixed = 0;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_normalize_page(int64_t doc, int32_t page_index, char** json) {
    try {
        if (!json) return JPDFIUM_ERR_INVALID;

        int32_t tuc_fixed = 0, w_fixed = 0;
        jpdfium_font_fix_tounicode(doc, page_index, &tuc_fixed);
        jpdfium_font_repair_widths(doc, page_index, &w_fixed);

        char buf[256];
        snprintf(buf, sizeof(buf),
                 "{\"fonts_processed\":0,\"tounicode_fixed\":%d,"
                 "\"widths_repaired\":%d,\"type1_converted\":0,\"resubset\":0}",
                 tuc_fixed, w_fixed);
        *json = strdup(buf);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_subset(const uint8_t* font_data, int64_t font_len, const uint32_t* codepoints,
                            int32_t num_codepoints, int32_t retain_gids, uint8_t** out_data,
                            int64_t* out_len) {
    try {
        if (!font_data || font_len <= 0 || !codepoints || num_codepoints <= 0 || !out_data ||
            !out_len) {
            return JPDFIUM_ERR_INVALID;
        }

        // Create HarfBuzz blob from font data
        hb_blob_t* blob = hb_blob_create((const char*)font_data, (unsigned int)font_len,
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);

        if (!face) return JPDFIUM_ERR_INVALID;

        // Create subset input
        hb_subset_input_t* input = hb_subset_input_create_or_fail();
        if (!input) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_NATIVE;
        }

        // Add requested codepoints
        hb_set_t* unicode_set = hb_subset_input_unicode_set(input);
        for (int32_t i = 0; i < num_codepoints; i++) {
            hb_set_add(unicode_set, codepoints[i]);
        }

        // Set flags
        if (retain_gids) {
            hb_subset_input_set_flags(input, HB_SUBSET_FLAGS_RETAIN_GIDS);
        }

        // Perform subsetting
        hb_face_t* subset_face = hb_subset_or_fail(face, input);
        hb_subset_input_destroy(input);
        hb_face_destroy(face);

        if (!subset_face) return JPDFIUM_ERR_NATIVE;

        // Extract result
        hb_blob_t* result_blob = hb_face_reference_blob(subset_face);
        unsigned int result_len;
        const char* result_data = hb_blob_get_data(result_blob, &result_len);

        *out_len = result_len;
        if (result_len == 0) {
            hb_blob_destroy(result_blob);
            hb_face_destroy(subset_face);
            return JPDFIUM_ERR_NATIVE;
        }
        *out_data = (uint8_t*)malloc(result_len);
        if (!*out_data) {
            hb_blob_destroy(result_blob);
            hb_face_destroy(subset_face);
            return JPDFIUM_ERR_NATIVE;
        }
        memcpy(*out_data, result_data, result_len);

        hb_blob_destroy(result_blob);
        hb_face_destroy(subset_face);

        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else  // !FREETYPE || !HARFBUZZ

int32_t jpdfium_font_get_data(int64_t, int32_t, uint8_t** data, int64_t* len) {
    if (data) *data = nullptr;
    if (len) *len = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_classify(const uint8_t*, int64_t, char** json) {
    if (json) *json = strdup("{\"type\":\"unknown\",\"error\":\"FreeType not available\"}");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_covers_text(const uint8_t*, int64_t, const int32_t*, int32_t, uint8_t*) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_text_shape(const uint8_t*, int64_t, const char*, float, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_repair_widths(int64_t, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_normalize_page(int64_t, int32_t, char** json) {
    if (json) *json = strdup("{\"error\":\"FreeType/HarfBuzz not available\"}");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_subset(const uint8_t*, int64_t, const uint32_t*, int32_t, int32_t,
                            uint8_t** out, int64_t* len) {
    if (out) *out = nullptr;
    if (len) *len = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif  // JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ

// Glyph-Level Redaction

#if defined(JPDFIUM_HAS_HARFBUZZ) && defined(JPDFIUM_HAS_ICU)

#include <hb.h>
#include <unicode/ubidi.h>
#include <unicode/unistr.h>

int32_t jpdfium_redact_glyph_aware(int64_t page, const char** words, int32_t word_count,
                                   uint32_t argb, float padding, uint32_t flags,
                                   int32_t* match_count, char** result_json) {
    try {
        if (!words || word_count <= 0 || !match_count || !result_json) return JPDFIUM_ERR_INVALID;

        // Real implementation pipeline:
        // 1. Extract text with FPDFText_GetCharBox() for per-character bounding boxes
        // 2. If JPDFIUM_GLYPH_LIGATURE_AWARE: use HarfBuzz hb_shape() for cluster mapping
        // 3. If JPDFIUM_GLYPH_BIDI_AWARE: ICU BiDi for visual order resolution
        // 4. If JPDFIUM_GLYPH_GRAPHEME_SAFE: grapheme cluster boundaries
        // 5. Match words against extracted text
        // 6. Compute precise redact rectangles from glyph advances
        // 7. If JPDFIUM_GLYPH_REMOVE_STREAM: qpdf structural content removal
        // 8. Paint filled rectangles

        (void)page;
        (void)argb;
        (void)padding;
        (void)flags;
        *match_count = 0;
        *result_json = strdup("[]");
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_redact_glyph_aware(int64_t, const char**, int32_t, uint32_t, float, uint32_t,
                                   int32_t* match_count, char** result_json) {
    if (match_count) *match_count = 0;
    if (result_json) *result_json = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// XMP Metadata Redaction + Font Strip - qpdf

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Buffer.hh>
#include <qpdf/BufferInputSource.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFTokenizer.hh>
#include <qpdf/QPDFWriter.hh>

// Save an FPDF_DOCUMENT to a byte vector.
static bool qpdf_save_fpdf(FPDF_DOCUMENT doc, std::vector<uint8_t>& out) {
    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t>* buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                const auto* src = static_cast<const uint8_t*>(data);
                bw->buf->insert(bw->buf->end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    } bw;
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;
    bw.buf = &out;
    return FPDF_SaveAsCopy(doc, &bw, FPDF_NO_INCREMENTAL) != 0;
}

// Write a QPDF document to a byte vector.
static std::vector<uint8_t> qpdf_write(QPDF& pdf) {
    QPDFWriter writer(pdf);
    writer.setOutputMemory();
    writer.setLinearization(false);
    writer.setCompressStreams(true);
    writer.write();
    std::shared_ptr<Buffer> buf = writer.getBufferSharedPointer();
    std::vector<uint8_t> result(buf->getSize());
    memcpy(result.data(), buf->getBuffer(), buf->getSize());
    return result;
}

// Replace the FPDF_DOCUMENT inside a DocWrapper with a document loaded from new
// bytes. The DocWrapper pointer itself is unchanged, so the Java-side handle
// remains valid.
//
// Strong failure contract: the candidate is fully prepared, loaded, and
// validated before anything is retired. If any step fails, the original
// document is left untouched and usable. Only after a valid candidate exists
// are the old resources retired and the replacement committed.
static int32_t docwrapper_reload(DocWrapper* w, const std::vector<uint8_t>& newBytes) {
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    if (newBytes.empty() || newBytes.size() > static_cast<size_t>(INT32_MAX))
        return JPDFIUM_ERR_INVALID;

    size_t sz = newBytes.size();
    auto* copy = static_cast<uint8_t*>(malloc(sz));
    if (!copy) return JPDFIUM_ERR_NATIVE;
    memcpy(copy, newBytes.data(), sz);

    FPDF_DOCUMENT candidate = FPDF_LoadMemDocument(copy, static_cast<int>(sz), nullptr);
    if (!candidate) {
        free(copy);
        return JPDFIUM_ERR_NATIVE;
    }

    // Retire native state tied to the old document before committing the
    // replacement: loaded FPDF_FONT handles belong to the old FPDF_DOCUMENT
    // and would otherwise dangle. The redaction audit state is deliberately
    // preserved: reload (metadata/font stripping) round-trips the same pages
    // through QPDF, so committed redactions, unapplied-mark counts, and the
    // literal/zone audit trail stay valid for the replacement bytes. Dropping
    // them here would silently disable the save-time sanitize pass (which
    // requires contentRedacted + sanitizeOnSave and scrubs XMP against
    // redactedLiterals) and the uncommitted-marks save refusal on the very
    // documents that need them. Only per-document resources are retired:
    // font handles, the pattern cache, the stale sanitize report, and the
    // source path (the replacement is memory-backed, so alias protection
    // against the old backing file no longer applies).
    for (FPDF_FONT f : w->core->loadedFonts) {
        FPDFFont_Close(f);
    }
    w->core->loadedFonts.clear();
    w->core->clearRedactPatternCache();
    w->core->touchedFontsSet.clear();
    w->core->touchedFontNames.clear();
    w->core->sanitizeReport.clear();
    w->core->sourcePath.clear();

    FPDF_CloseDocument(w->core->doc);
    free(w->core->buf);  // free() ignores NULL
    w->core->doc = candidate;
    w->core->buf = copy;
    w->core->blen = static_cast<int64_t>(sz);
    // Replacement committed: previously opened pages now point into the freed
    // document. Their wrappers detect the mismatch and refuse use and release.
    ++w->core->generation;
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip(int64_t doc, const char** keys, int32_t key_count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !keys || key_count <= 0) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        QPDFObjectHandle trailer = pdf.getTrailer();
        if (trailer.hasKey("/Info")) {
            QPDFObjectHandle info = trailer.getKey("/Info");
            if (info.isDictionary()) {
                for (int i = 0; i < key_count; i++) {
                    std::string qkey = std::string("/") + keys[i];
                    if (info.hasKey(qkey)) info.removeKey(qkey);
                }
            }
        }

        return docwrapper_reload(w, qpdf_write(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_metadata_strip_all(int64_t doc) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip_all", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        QPDFObjectHandle trailer = pdf.getTrailer();
        if (trailer.hasKey("/Info")) trailer.removeKey("/Info");

        QPDFObjectHandle root = pdf.getRoot();
        if (root.hasKey("/Metadata")) root.removeKey("/Metadata");
        if (root.hasKey("/MarkInfo")) root.removeKey("/MarkInfo");

        return docwrapper_reload(w, qpdf_write(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_strip_fonts(int64_t doc, int32_t* fonts_removed) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !fonts_removed) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip_fonts", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        int count = 0;
        std::set<int> processed;  // object IDs of shared resource dicts already stripped

        // NOLINTNEXTLINE(performance-for-range-copy) - copy is intentional; hasKey/getKey are
        // non-const in libqpdf on Ubuntu 24.04 (qpdf < 11.9 in apt)
        for (auto page : pdf.getAllPages()) {
            if (!page.hasKey("/Resources")) continue;
            QPDFObjectHandle resources = page.getKey("/Resources");
            if (!resources.isDictionary()) continue;

            // Shared indirect resource dicts appear on multiple pages; strip only once.
            if (resources.isIndirect()) {
                int objid = resources.getObjectID();
                if (processed.count(objid)) continue;
                processed.insert(objid);
            }

            if (!resources.hasKey("/Font")) continue;
            QPDFObjectHandle fonts = resources.getKey("/Font");
            if (!fonts.isDictionary()) continue;

            count += static_cast<int>(fonts.getKeys().size());
            resources.removeKey("/Font");
        }

        *fonts_removed = count;
        return docwrapper_reload(w, qpdf_write(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Font intelligence: evidence extraction + /ToUnicode reconstruction. Needs
// both qpdf and FreeType; the mutation must run inside the same docwrapper_reload
// transaction as the other qpdf writers.

#if defined(JPDFIUM_HAS_FREETYPE)

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_TRUETYPE_TABLES_H

#include <algorithm>
#include <utility>

namespace {

// Decode a stream object's bytes into a std::string (empty when not a stream).
std::string stream_bytes(QPDFObjectHandle s) {
    if (!s.isStream()) return {};
    std::shared_ptr<Buffer> buf = s.getStreamData();
    if (!buf || !buf->getBuffer() || buf->getSize() == 0) return {};
    return std::string(reinterpret_cast<const char*>(buf->getBuffer()),
                       static_cast<size_t>(buf->getSize()));
}

// One code -> Unicode derivation with its provenance.
struct Mapping {
    uint32_t code;
    uint32_t uni;  // 0 == unresolved
    const char* provenance;
    std::string note;
};

// Classification + reverse maps extracted from an embedded font program.
struct GlyphMaps {
    bool ok = false;
    bool sfnt = false;
    bool cff = false;
    bool has_cmap = false;
    bool subset = false;
    uint32_t num_glyphs = 0;
    int units_per_em = 0;
    int32_t fs_type = -1;
    std::string family;
    std::unordered_map<uint32_t, uint32_t> gid_to_uni;  // first codepoint wins
    std::unordered_set<uint32_t> gid_ambiguous;         // >1 codepoint maps to one glyph
    std::unordered_map<std::string, uint32_t> name_to_uni;
    std::unordered_set<std::string> name_ambiguous;     // one glyph name, >1 codepoint
    bool cmap_truncated = false;                        // a bounded scan stopped early
};

FT_Library font_intel_ft() {
    static FT_Library lib = nullptr;
    if (!lib) FT_Init_FreeType(&lib);
    return lib;
}

GlyphMaps build_glyph_maps(const uint8_t* data, size_t len) {
    GlyphMaps m;
    if (!data || len == 0) return m;
    FT_Library lib = font_intel_ft();
    if (!lib) return m;
    FT_Face face = nullptr;
    if (FT_New_Memory_Face(lib, data, static_cast<FT_Long>(len), 0, &face)) return m;

    m.sfnt = FT_IS_SFNT(face) != 0;
    m.has_cmap = face->num_charmaps > 0;
    m.num_glyphs = static_cast<uint32_t>(face->num_glyphs);
    m.units_per_em = static_cast<int>(face->units_per_EM);
    if (face->family_name) m.family = face->family_name;
    if (m.sfnt) {
        FT_ULong clen = 0;
        if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &clen) == 0)
            m.cff = true;
        else if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr, &clen) == 0)
            m.cff = true;
    }
    auto* os2 = static_cast<TT_OS2*>(FT_Get_Sfnt_Table(face, FT_SFNT_OS2));
    if (os2) m.fs_type = static_cast<int32_t>(os2->fsType);
    if (face->family_name) {
        const char* plus = strchr(face->family_name, '+');
        if (plus && (plus - face->family_name) == 6) {
            m.subset = true;
            for (const char* c = face->family_name; c < plus; ++c) {
                if (*c < 'A' || *c > 'Z') {
                    m.subset = false;
                    break;
                }
            }
        }
    }

    if (m.has_cmap) FT_Select_Charmap(face, FT_ENCODING_UNICODE);
    FT_UInt gid = 0;
    FT_ULong cp = FT_Get_First_Char(face, &gid);
    size_t guard = 0;
    while (gid != 0 && guard++ < 200000) {
        if (cp <= 0x10FFFF) {
            auto g = static_cast<uint32_t>(gid);
            auto it = m.gid_to_uni.find(g);
            if (it == m.gid_to_uni.end())
                m.gid_to_uni.emplace(g, static_cast<uint32_t>(cp));
            else if (it->second != static_cast<uint32_t>(cp))
                m.gid_ambiguous.insert(g);  // keep the first, record that >1 codepoint reaches it
        }
        cp = FT_Get_Next_Char(face, cp, &gid);
    }
    // A stop at the guard means the scan covered only part of the charmap.
    if (guard > 200000) m.cmap_truncated = true;
    if (FT_HAS_GLYPH_NAMES(face)) {
        char nm[256];
        uint32_t cap = std::min<uint32_t>(m.num_glyphs, 65535);
        if (m.num_glyphs > 65535) m.cmap_truncated = true;
        for (uint32_t g = 1; g <= cap; ++g) {
            if (FT_Get_Glyph_Name(face, g, nm, sizeof(nm)) == 0 && nm[0]) {
                auto it = m.gid_to_uni.find(g);
                if (it == m.gid_to_uni.end()) continue;
                std::string name(nm);
                auto n = m.name_to_uni.find(name);
                if (n == m.name_to_uni.end())
                    m.name_to_uni.emplace(name, it->second);
                else if (n->second != it->second)
                    m.name_ambiguous.insert(name);  // same name, different codepoint
            }
        }
    }
    m.ok = true;
    FT_Done_Face(face);
    return m;
}

// Verified program identity: a content hash of the embedded font program
// bytes. Used only to cache analysis. Two resources that declare the same base
// name and the same byte length but carry different bytes hash differently and
// are never merged; the bytes are the identity, not the name or the size.
std::string program_content_hash(const std::string& bytes) {
    uint64_t h = 1469598103934665603ull;
    for (unsigned char c : bytes) {
        h ^= c;
        h *= 1099511628211ull;
    }
    char buf[48];
    std::snprintf(buf, sizeof(buf), "%016llx%zx", static_cast<unsigned long long>(h), bytes.size());
    return buf;
}

// Per-operation memo of glyph analysis, keyed by verified program identity, so
// one snapshot analyses each distinct font program exactly once even when it is
// referenced from many pages or by many resources. The 64-bit FNV hash is only
// a bucket selector: a hit is accepted only after the incoming bytes are
// compared byte-for-byte against the stored program, so two different programs
// that happen to collide are analysed separately and never merged. The common
// (no-collision) case stays one hash lookup plus one comparison.
using ProgramCache =
    std::unordered_map<std::string, std::vector<std::pair<std::string, GlyphMaps>>>;

GlyphMaps const& glyph_maps_cached(const std::string& bytes, ProgramCache& cache) {
    std::string key = program_content_hash(bytes);
    g_program_bytes_hashed.fetch_add(static_cast<int64_t>(bytes.size()), std::memory_order_relaxed);
    auto it = cache.find(key);
    if (it == cache.end()) {
        it = cache.emplace(key, std::vector<std::pair<std::string, GlyphMaps>>{}).first;
    }
    for (const auto& entry : it->second) {
        if (entry.first == bytes) return entry.second;
    }
    // Distinct bytes under an identical hash: keep them separate, never merged.
    GlyphMaps gm =
        build_glyph_maps(reinterpret_cast<const uint8_t*>(bytes.data()), bytes.size());
    g_programs_analyzed.fetch_add(1, std::memory_order_relaxed);
    it->second.emplace_back(bytes, std::move(gm));
    return it->second.back().second;
}

using CodeToUni = std::unordered_map<uint32_t, uint32_t>;

void fill_ascii(CodeToUni& m) {
    for (uint32_t c = 32; c <= 126; ++c) m[c] = c;
}

void fill_winansi(CodeToUni& m) {
    fill_ascii(m);
    for (uint32_t c = 0xA0; c <= 0xFF; ++c) m[c] = c;
    static const uint32_t hi[32] = {
        0x20AC, 0,      0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0,      0x017D, 0,
        0,      0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0,      0x017E, 0x0178};
    for (uint32_t i = 0; i < 32; ++i)
        if (hi[i]) m[0x80 + i] = hi[i];
}

void fill_macroman(CodeToUni& m) {
    fill_ascii(m);
    static const uint32_t hi[128] = {
        0x00C4, 0x00C5, 0x00C7, 0x00C9, 0x00D1, 0x00D6, 0x00DC, 0x00E1, 0x00E0, 0x00E2,
        0x00E4, 0x00E3, 0x00E5, 0x00E7, 0x00E9, 0x00E8, 0x00EA, 0x00EB, 0x00ED, 0x00EC,
        0x00EE, 0x00EF, 0x00F1, 0x00F3, 0x00F2, 0x00F4, 0x00F6, 0x00F5, 0x00FA, 0x00F9,
        0x00FB, 0x00FC, 0x2020, 0x00B0, 0x00A2, 0x00A3, 0x00A7, 0x2022, 0x00B6, 0x00DF,
        0x00AE, 0x00A9, 0x2122, 0x00B4, 0x00A8, 0x2260, 0x00C6, 0x00D8, 0x221E, 0x00B1,
        0x2264, 0x2265, 0x00A5, 0x00B5, 0x2202, 0x2211, 0x220F, 0x03C0, 0x222B, 0x00AA,
        0x00BA, 0x03A9, 0x00E6, 0x00F8, 0x00BF, 0x00A1, 0x00AC, 0x221A, 0x0192, 0x2248,
        0x2206, 0x00AB, 0x00BB, 0x2026, 0x00A0, 0x00C0, 0x00C3, 0x00D5, 0x0152, 0x0153,
        0x2013, 0x2014, 0x201C, 0x201D, 0x2018, 0x2019, 0x00F7, 0x25CA, 0x00FF, 0x0178,
        0x2044, 0x20AC, 0x2039, 0x203A, 0xFB01, 0xFB02, 0x2021, 0x00B7, 0x201A, 0x201E,
        0x2030, 0x00C2, 0x00CA, 0x00C1, 0x00CB, 0x00C8, 0x00CD, 0x00CE, 0x00CF, 0x00CC,
        0x00D3, 0x00D4, 0xF8FF, 0x00D2, 0x00DA, 0x00DB, 0x00D9, 0x0131, 0x02C6, 0x02DC,
        0x00AF, 0x02D8, 0x02D9, 0x02DA, 0x00B8, 0x02DD, 0x02DB, 0x02C7};
    for (uint32_t i = 0; i < 128; ++i)
        if (hi[i]) m[0x80 + i] = hi[i];
}

void fill_standard(CodeToUni& m) {
    fill_ascii(m);
    m[0x27] = 0x2019;  // quoteright (StandardEncoding differs from ASCII)
    m[0x60] = 0x2018;  // quoteleft
    static const struct {
        uint32_t code, uni;
    } hi[] = {
        {0xA1, 0x00A1}, {0xA2, 0x00A2}, {0xA3, 0x00A3}, {0xA4, 0x2044}, {0xA5, 0x00A5},
        {0xA6, 0x0192}, {0xA7, 0x00A7}, {0xA8, 0x00A4}, {0xA9, 0x0027}, {0xAA, 0x201C},
        {0xAB, 0x00AB}, {0xAC, 0x2039}, {0xAD, 0x203A}, {0xAE, 0xFB01}, {0xAF, 0xFB02},
        {0xB1, 0x2013}, {0xB2, 0x2020}, {0xB3, 0x2021}, {0xB4, 0x00B7}, {0xB6, 0x00B6},
        {0xB7, 0x2022}, {0xB8, 0x201A}, {0xB9, 0x201E}, {0xBA, 0x201D}, {0xBB, 0x00BB},
        {0xBC, 0x2026}, {0xBD, 0x2030}, {0xBF, 0x00BF}, {0xC1, 0x0060}, {0xC2, 0x00B4},
        {0xC3, 0x02C6}, {0xC4, 0x02DC}, {0xC5, 0x00AF}, {0xC6, 0x02D8}, {0xC7, 0x02D9},
        {0xC8, 0x00A8}, {0xCA, 0x02DA}, {0xCB, 0x00B8}, {0xCD, 0x02DD}, {0xCE, 0x02DB},
        {0xCF, 0x02C7}, {0xD0, 0x2014}, {0xE1, 0x00C6}, {0xE3, 0x00AA}, {0xE8, 0x0141},
        {0xE9, 0x00D8}, {0xEA, 0x0152}, {0xEB, 0x00BA}, {0xF1, 0x00E6}, {0xF5, 0x0131},
        {0xF8, 0x0142}, {0xF9, 0x00F8}, {0xFA, 0x0153}, {0xFB, 0x00DF}};
    for (const auto& e : hi) m[e.code] = e.uni;
}

// Compact Adobe Glyph List subset: /Differences may name a glyph on a font with
// no embedded program, where the name is the only semantic authority for the code.
const std::unordered_map<std::string, uint32_t>& agl_subset() {
    static const std::unordered_map<std::string, uint32_t> m = {
        {"space", 0x0020}, {"exclam", 0x0021}, {"quotedbl", 0x0022},
        {"numbersign", 0x0023}, {"dollar", 0x0024}, {"percent", 0x0025},
        {"ampersand", 0x0026}, {"quotesingle", 0x0027}, {"parenleft", 0x0028},
        {"parenright", 0x0029}, {"asterisk", 0x002A}, {"plus", 0x002B},
        {"comma", 0x002C}, {"hyphen", 0x002D}, {"period", 0x002E},
        {"slash", 0x002F}, {"zero", 0x0030}, {"one", 0x0031}, {"two", 0x0032},
        {"three", 0x0033}, {"four", 0x0034}, {"five", 0x0035}, {"six", 0x0036},
        {"seven", 0x0037}, {"eight", 0x0038}, {"nine", 0x0039}, {"colon", 0x003A},
        {"semicolon", 0x003B}, {"less", 0x003C}, {"equal", 0x003D},
        {"greater", 0x003E}, {"question", 0x003F}, {"at", 0x0040},
        {"bracketleft", 0x005B}, {"backslash", 0x005C}, {"bracketright", 0x005D},
        {"asciicircum", 0x005E}, {"underscore", 0x005F}, {"grave", 0x0060},
        {"braceleft", 0x007B}, {"bar", 0x007C}, {"braceright", 0x007D},
        {"asciitilde", 0x007E},
        {"quoteright", 0x2019}, {"quoteleft", 0x2018}, {"quotedblleft", 0x201C},
        {"quotedblright", 0x201D}, {"quotedblbase", 0x201E},
        {"quotesinglbase", 0x201A}, {"guillemotleft", 0x00AB},
        {"guillemotright", 0x00BB}, {"guilsinglleft", 0x2039},
        {"guilsinglright", 0x203A}, {"endash", 0x2013}, {"emdash", 0x2014},
        {"bullet", 0x2022}, {"dagger", 0x2020}, {"daggerdbl", 0x2021},
        {"ellipsis", 0x2026}, {"perthousand", 0x2030}, {"periodcentered", 0x00B7},
        {"section", 0x00A7}, {"paragraph", 0x00B6}, {"germandbls", 0x00DF},
        {"fraction", 0x2044}, {"florin", 0x0192}, {"fi", 0xFB01}, {"fl", 0xFB02},
        {"nbspace", 0x00A0}, {"exclamdown", 0x00A1}, {"cent", 0x00A2},
        {"sterling", 0x00A3}, {"currency", 0x00A4}, {"yen", 0x00A5},
        {"brokenbar", 0x00A6}, {"dieresis", 0x00A8}, {"copyright", 0x00A9},
        {"ordfeminine", 0x00AA}, {"logicalnot", 0x00AC}, {"registered", 0x00AE},
        {"macron", 0x00AF}, {"degree", 0x00B0}, {"plusminus", 0x00B1},
        {"twosuperior", 0x00B2}, {"threesuperior", 0x00B3}, {"acute", 0x00B4},
        {"mu", 0x00B5}, {"onesuperior", 0x00B9}, {"ordmasculine", 0x00BA},
        {"onequarter", 0x00BC}, {"onehalf", 0x00BD}, {"threequarters", 0x00BE},
        {"questiondown", 0x00BF}, {"Agrave", 0x00C0}, {"Aacute", 0x00C1},
        {"Acircumflex", 0x00C2}, {"Atilde", 0x00C3}, {"Adieresis", 0x00C4},
        {"Aring", 0x00C5}, {"AE", 0x00C6}, {"Ccedilla", 0x00C7}, {"Egrave", 0x00C8},
        {"Eacute", 0x00C9}, {"Ecircumflex", 0x00CA}, {"Edieresis", 0x00CB},
        {"Igrave", 0x00CC}, {"Iacute", 0x00CD}, {"Icircumflex", 0x00CE},
        {"Idieresis", 0x00CF}, {"Eth", 0x00D0}, {"Ntilde", 0x00D1},
        {"Ograve", 0x00D2}, {"Oacute", 0x00D3}, {"Ocircumflex", 0x00D4},
        {"Otilde", 0x00D5}, {"Odieresis", 0x00D6}, {"multiply", 0x00D7},
        {"Oslash", 0x00D8}, {"Ugrave", 0x00D9}, {"Uacute", 0x00DA},
        {"Ucircumflex", 0x00DB}, {"Udieresis", 0x00DC}, {"Yacute", 0x00DD},
        {"Thorn", 0x00DE}, {"agrave", 0x00E0}, {"aacute", 0x00E1},
        {"acircumflex", 0x00E2}, {"atilde", 0x00E3}, {"adieresis", 0x00E4},
        {"aring", 0x00E5}, {"ae", 0x00E6}, {"ccedilla", 0x00E7}, {"egrave", 0x00E8},
        {"eacute", 0x00E9}, {"ecircumflex", 0x00EA}, {"edieresis", 0x00EB},
        {"igrave", 0x00EC}, {"iacute", 0x00ED}, {"icircumflex", 0x00EE},
        {"idieresis", 0x00EF}, {"eth", 0x00F0}, {"ntilde", 0x00F1},
        {"ograve", 0x00F2}, {"oacute", 0x00F3}, {"ocircumflex", 0x00F4},
        {"otilde", 0x00F5}, {"odieresis", 0x00F6}, {"divide", 0x00F7},
        {"oslash", 0x00F8}, {"ugrave", 0x00F9}, {"uacute", 0x00FA},
        {"ucircumflex", 0x00FB}, {"udieresis", 0x00FC}, {"yacute", 0x00FD},
        {"thorn", 0x00FE}, {"ydieresis", 0x00FF}, {"dotlessi", 0x0131},
        {"Lslash", 0x0141}, {"lslash", 0x0142}, {"OE", 0x0152}, {"oe", 0x0153},
        {"Scaron", 0x0160}, {"scaron", 0x0161}, {"Zcaron", 0x017D},
        {"zcaron", 0x017E}, {"Ydieresis", 0x0178}, {"Euro", 0x20AC},
        {"trademark", 0x2122}, {"minus", 0x2212}, {"notequal", 0x2260},
        {"lessequal", 0x2264}, {"greaterequal", 0x2265}, {"infinity", 0x221E},
        {"integral", 0x222B}, {"product", 0x220F}, {"summation", 0x2211},
        {"radical", 0x221A}, {"partialdiff", 0x2202}, {"Delta", 0x2206},
        {"Omega", 0x03A9}, {"pi", 0x03C0}, {"lozenge", 0x25CA},
        {"circumflex", 0x02C6}, {"tilde", 0x02DC}, {"breve", 0x02D8},
        {"dotaccent", 0x02D9}, {"ring", 0x02DA}, {"cedilla", 0x00B8},
        {"hungarumlaut", 0x02DD}, {"ogonek", 0x02DB}, {"caron", 0x02C7},
    };
    return m;
}

// Resolve an Adobe glyph name to Unicode: AGL "uniXXXX"/"uXXXX"/single-letter
// forms, then the embedded program's glyph-name -> cmap reverse map and AGL subset.
uint32_t resolve_glyph_name(const std::string& name, const GlyphMaps& gm,
                            bool* ambiguous = nullptr) {
    if (name.empty()) return 0;
    if (name.rfind("uni", 0) == 0 && name.size() >= 7) {
        bool ok = true;
        char grp[5] = {0, 0, 0, 0, 0};
        for (size_t i = 3; i + 4 <= name.size(); i += 4) {
            grp[0] = name[i];
            grp[1] = name[i + 1];
            grp[2] = name[i + 2];
            grp[3] = name[i + 3];
            char* end = nullptr;
            long v = strtol(grp, &end, 16);
            if (end != grp + 4) {
                ok = false;
                break;
            }
            // First 4-hex group wins; surrogate pairs in names are rare.
            if (i == 3 && v > 0 && v <= 0x10FFFF) return static_cast<uint32_t>(v);
        }
        if (!ok) return 0;
    }
    if (name.size() >= 5 && name[0] == 'u') {
        char* end = nullptr;
        long v = strtol(name.c_str() + 1, &end, 16);
        if (end == name.c_str() + name.size() && v > 0 && v <= 0x10FFFF)
            return static_cast<uint32_t>(v);
    }
    if (gm.name_ambiguous.find(name) != gm.name_ambiguous.end()) {
        if (ambiguous) *ambiguous = true;
        return 0;  // two glyphs share this name with different codepoints
    }
    auto it = gm.name_to_uni.find(name);
    if (it != gm.name_to_uni.end()) return it->second;
    if (name.size() == 1 && std::isalpha(static_cast<unsigned char>(name[0])))
        return static_cast<uint32_t>(name[0]);  // AGL: a bare ASCII letter name
    auto agl = agl_subset().find(name);
    return agl != agl_subset().end() ? agl->second : 0;
}

// Defined below, after descriptor_of(); declared here so the map builders can
// refuse a symbolic font that declares no explicit /Encoding.
bool is_symbolic_font(QPDFObjectHandle fd);

// Simple (non-CID) font: code -> Unicode from the /Encoding. Base encodings are
// the standard tables; /Differences glyph names resolve against the font program.
bool build_simple_map(QPDFObjectHandle fontDict, const GlyphMaps& gm, std::vector<Mapping>& out,
                      std::string* code = nullptr) {
    std::string baseName = "/StandardEncoding";
    QPDFObjectHandle diffs;
    QPDFObjectHandle enc = fontDict.getKey("/Encoding");
    bool explicitEncoding = enc.isName() || enc.isDictionary();
    if (!explicitEncoding && is_symbolic_font(fontDict)) {
        // No /Encoding and a symbolic descriptor: the glyph codes are in the
        // program's own built-in encoding. Do not fall back to StandardEncoding.
        if (code) *code = "SYMBOLIC_FONT_UNSUPPORTED";
        return false;
    }
    if (enc.isName()) {
        baseName = enc.getName();
    } else if (enc.isDictionary()) {
        QPDFObjectHandle be = enc.getKey("/BaseEncoding");
        if (be.isName()) baseName = be.getName();
        if (enc.hasKey("/Differences")) diffs = enc.getKey("/Differences");
    }

    CodeToUni base;
    if (baseName == "/WinAnsiEncoding")
        fill_winansi(base);
    else if (baseName == "/MacRomanEncoding")
        fill_macroman(base);
    else if (baseName == "/StandardEncoding")
        fill_standard(base);
    else {
        if (code) *code = "BUILTIN_ENCODING_UNSUPPORTED";
        return false;  // unknown / font built-in encoding: deferred, not guessed
    }

    std::vector<Mapping> maps;
    maps.reserve(base.size());
    for (const auto& [code, uni] : base)
        maps.push_back({code, uni, "RECOVERED", "encoding:" + baseName.substr(1)});

    if (diffs.isArray()) {
        int cur = -1;
        for (const auto& item : diffs.getArrayAsVector()) {
            if (item.isInteger()) {
                cur = static_cast<int>(item.getIntValue());
                continue;
            }
            if (!item.isName() || cur < 0 || cur > 255) continue;
            std::string nm = item.getName();
            if (!nm.empty() && nm[0] == '/') nm.erase(0, 1);
            bool ambiguous = false;
            uint32_t uni = resolve_glyph_name(nm, gm, &ambiguous);
            // Differences override the base entry for this code.
            maps.erase(std::remove_if(maps.begin(), maps.end(),
                                      [cur](const Mapping& r) { return r.code == static_cast<uint32_t>(cur); }),
                       maps.end());
            const char* prov = uni ? "RECOVERED" : (ambiguous ? "AMBIGUOUS" : "UNRESOLVED");
            maps.push_back({static_cast<uint32_t>(cur), uni, prov, "differences:" + nm});
            ++cur;
        }
    }

    std::sort(maps.begin(), maps.end(),
              [](const Mapping& a, const Mapping& b) { return a.code < b.code; });
    out = std::move(maps);
    return !out.empty();
}

// Composite font with an Identity encoding and Identity /CIDToGIDMap:
// code == CID == GID -> Unicode via the embedded program's cmap.
bool build_cid_map(QPDFObjectHandle fontDict, const GlyphMaps& gm, std::vector<Mapping>& out,
                   bool* truncated = nullptr, std::string* code = nullptr) {
    QPDFObjectHandle enc = fontDict.getKey("/Encoding");
    std::string encName = enc.isName() ? enc.getName() : "";
    if (encName != "/Identity-H" && encName != "/Identity-V") {
        if (code) *code = "CFF_CID_MAPPING_UNSUPPORTED";
        return false;  // embedded CMap streams: deferred
    }
    if (!gm.ok) {
        if (code) *code = "CFF_CID_MAPPING_UNSUPPORTED";
        return false;
    }

    QPDFObjectHandle desc;
    if (fontDict.hasKey("/DescendantFonts")) {
        QPDFObjectHandle arr = fontDict.getKey("/DescendantFonts");
        if (arr.isArray() && arr.getArrayNItems() > 0) desc = arr.getArrayItem(0);
    }
    if (!desc.isDictionary()) {
        if (code) *code = "CFF_CID_MAPPING_UNSUPPORTED";
        return false;
    }

    std::vector<uint16_t> cid2gid;
    bool identity = true;
    if (desc.hasKey("/CIDToGIDMap")) {
        QPDFObjectHandle c = desc.getKey("/CIDToGIDMap");
        if (c.isName()) {
            identity = (c.getName() == "/Identity");
        } else if (c.isStream()) {
            identity = false;
            std::string bytes = stream_bytes(c);
            cid2gid.reserve(bytes.size() / 2);
            for (size_t i = 0; i + 1 < bytes.size(); i += 2)
                cid2gid.push_back(static_cast<uint16_t>((static_cast<uint8_t>(bytes[i]) << 8) |
                                                        static_cast<uint8_t>(bytes[i + 1])));
        }
    }

    const size_t kCap = 8192;
    std::vector<Mapping> maps;
    if (identity) {
        for (const auto& [gid, uni] : gm.gid_to_uni) {
            if (gid > 0xFFFF) continue;  // out-of-range for a 2-byte code space
            if (maps.size() >= kCap) {
                if (truncated) *truncated = true;
                break;
            }
            bool amb = gm.gid_ambiguous.find(gid) != gm.gid_ambiguous.end();
            maps.push_back({gid, amb ? 0u : uni, amb ? "AMBIGUOUS" : "RECOVERED",
                            amb ? "cmap:ambiguous" : "cidToGid:identity"});
        }
    } else {
        // Walk the CIDs forward, not the glyphs: several CIDs may select one
        // glyph, and every such code must appear in the map. A reverse
        // glyph -> first-CID index silently drops the extra CIDs.
        for (uint32_t cid = 0; cid < cid2gid.size(); ++cid) {
            uint32_t g = cid2gid[cid];
            if (g == 0) continue;
            auto it = gm.gid_to_uni.find(g);
            if (it == gm.gid_to_uni.end()) continue;
            if (maps.size() >= kCap) {
                if (truncated) *truncated = true;
                break;
            }
            bool amb = gm.gid_ambiguous.find(g) != gm.gid_ambiguous.end();
            maps.push_back({cid, amb ? 0u : it->second, amb ? "AMBIGUOUS" : "RECOVERED",
                            amb ? "cmap:ambiguous" : "cidToGid:stream"});
        }
    }
    std::sort(maps.begin(), maps.end(),
              [](const Mapping& a, const Mapping& b) { return a.code < b.code; });
    out = std::move(maps);
    return !out.empty();
}

std::string build_tounicode_cmap(const std::vector<Mapping>& maps, bool twoByte) {
    std::string s;
    s.reserve(maps.size() * 20 + 320);
    s += "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n";
    s += "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n";
    s += "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n";
    s += twoByte ? "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
                 : "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n";
    char buf[96];
    const size_t chunk = 100;
    for (size_t i = 0; i < maps.size(); i += chunk) {
        size_t n = std::min(chunk, maps.size() - i);
        snprintf(buf, sizeof(buf), "%zu beginbfchar\n", n);
        s += buf;
        for (size_t j = 0; j < n; ++j) {
            uint32_t code = maps[i + j].code;
            uint32_t uni = maps[i + j].uni;
            if (uni <= 0xFFFF) {
                if (twoByte)
                    snprintf(buf, sizeof(buf), "<%04X> <%04X>\n", code & 0xFFFF, uni);
                else
                    snprintf(buf, sizeof(buf), "<%02X> <%04X>\n", code & 0xFF, uni);
            } else {
                uint32_t v = uni - 0x10000;
                uint32_t hi = 0xD800 + (v >> 10);
                uint32_t lo = 0xDC00 + (v & 0x3FF);
                if (twoByte)
                    snprintf(buf, sizeof(buf), "<%04X> <%04X%04X>\n", code & 0xFFFF, hi, lo);
                else
                    snprintf(buf, sizeof(buf), "<%02X> <%04X%04X>\n", code & 0xFF, hi, lo);
            }
            s += buf;
        }
        s += "endbfchar\n";
    }
    s += "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return s;
}

std::string escape_json(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 2);
    o.push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof(b), "\\u%04X", c);
                    o += b;
                } else {
                    o.push_back(static_cast<char>(c));
                }
        }
    }
    o.push_back('"');
    return o;
}

bool is_cid_font(QPDFObjectHandle fd) {
    QPDFObjectHandle st = fd.getKey("/Subtype");
    return st.isName() && st.getName() == "/Type0";
}

// Font subtypes this layer supports; others (e.g. /Type3) are reported as
// unsupported and never repaired, so capability reporting matches behaviour.
bool is_simple_subtype(QPDFObjectHandle fd) {
    if (!fd.hasKey("/Subtype")) return false;
    QPDFObjectHandle st = fd.getKey("/Subtype");
    if (!st.isName()) return false;
    std::string n = st.getName();
    return n == "/TrueType" || n == "/Type1" || n == "/MMType1";
}

QPDFObjectHandle descriptor_of(QPDFObjectHandle fd, bool cid) {
    QPDFObjectHandle target = fd;
    if (cid && fd.hasKey("/DescendantFonts")) {
        QPDFObjectHandle arr = fd.getKey("/DescendantFonts");
        if (arr.isArray() && arr.getArrayNItems() > 0) target = arr.getArrayItem(0);
    }
    if (target.isDictionary() && target.hasKey("/FontDescriptor")) {
        QPDFObjectHandle d = target.getKey("/FontDescriptor");
        if (d.isDictionary()) return d;
    }
    return QPDFObjectHandle();
}

// A simple font is symbolic when its descriptor sets bit 3 (value 4) of /Flags.
// Such a font with no explicit /Encoding names glyphs in its own built-in
// encoding, which this layer does not decode, so it must not be guessed at.
bool is_symbolic_font(QPDFObjectHandle fd) {
    QPDFObjectHandle d = descriptor_of(fd, false);
    if (!d.isDictionary() || !d.hasKey("/Flags")) return false;
    QPDFObjectHandle f = d.getKey("/Flags");
    return f.isInteger() && (f.getIntValue() & 4) != 0;
}

std::string program_bytes(QPDFObjectHandle descriptor) {
    if (!descriptor.isDictionary()) return {};
    for (const char* k : {"/FontFile2", "/FontFile3", "/FontFile"}) {
        if (descriptor.hasKey(k)) {
            QPDFObjectHandle s = descriptor.getKey(k);
            if (s.isStream()) return stream_bytes(s);
        }
    }
    return {};
}

// States of a declared /ToUnicode stream. Only the SUPPORTED_* states may be
// reconstructed; UNSUPPORTED (a construct the writer cannot reproduce, such as
// an inherited /UseCMap) and MALFORMED must be left untouched.
enum class TounicodeState {
    ABSENT,
    SUPPORTED_COMPLETE,
    SUPPORTED_EMPTY,
    UNSUPPORTED,
    MALFORMED
};

const char* tounicode_state_name(TounicodeState s) {
    switch (s) {
        case TounicodeState::ABSENT: return "absent";
        case TounicodeState::SUPPORTED_COMPLETE: return "supported_complete";
        case TounicodeState::SUPPORTED_EMPTY: return "supported_empty";
        case TounicodeState::UNSUPPORTED: return "unsupported";
        case TounicodeState::MALFORMED: return "malformed";
    }
    return "malformed";
}

// Defined below in this anonymous namespace; declared here so callers can
// decide from parsed state rather than token presence.
TounicodeState parse_tounicode(QPDFObjectHandle fd,
                               std::unordered_map<uint32_t, uint32_t>* out);

std::string font_base_name(QPDFObjectHandle fd) {
    if (!fd.hasKey("/BaseFont")) return {};
    QPDFObjectHandle b = fd.getKey("/BaseFont");
    if (!b.isName()) return {};
    std::string n = b.getName();
    if (!n.empty() && n[0] == '/') n.erase(0, 1);
    return n;
}

std::string font_encoding_name(QPDFObjectHandle fd) {
    if (!fd.hasKey("/Encoding")) return {};
    QPDFObjectHandle e = fd.getKey("/Encoding");
    if (e.isName()) {
        std::string n = e.getName();
        if (!n.empty() && n[0] == '/') n.erase(0, 1);
        return n;
    }
    if (e.isDictionary()) return "custom";
    return {};
}

struct FontEntry {
    std::string key;
    QPDFObjectHandle dict;
};

bool page_font_dicts(const std::vector<QPDFObjectHandle>& pages, int32_t page_index,
                     std::vector<FontEntry>& out) {
    if (page_index < 0 || static_cast<size_t>(page_index) >= pages.size()) return false;
    QPDFObjectHandle page = pages[static_cast<size_t>(page_index)];
    // /Resources is inheritable: a page with none of its own uses the nearest
    // ancestor /Pages node's. QPDFPageObjectHelper resolves that for us.
    QPDFObjectHandle res = QPDFPageObjectHelper(page).getAttribute("/Resources", false);
    if (!res.isDictionary() || !res.hasKey("/Font")) return true;
    QPDFObjectHandle fonts = res.getKey("/Font");
    if (!fonts.isDictionary()) return true;
    for (const auto& k : fonts.getKeys()) {
        QPDFObjectHandle d = fonts.getKey(k);
        if (d.isDictionary()) out.push_back({k, d});
    }
    return true;
}

std::string font_evidence_json(int32_t page_index, const std::string& key, QPDFObjectHandle fd,
                               std::string& mappingsOut, ProgramCache& cache) {
    bool cid = is_cid_font(fd);
    std::string subtype = fd.hasKey("/Subtype") && fd.getKey("/Subtype").isName()
                              ? fd.getKey("/Subtype").getName()
                              : "";
    if (!subtype.empty() && subtype[0] == '/') subtype.erase(0, 1);

    QPDFObjectHandle descriptor = descriptor_of(fd, cid);
    std::string bytes = program_bytes(descriptor);
    GlyphMaps const& gm = glyph_maps_cached(bytes, cache);

    TounicodeState tuc = parse_tounicode(fd, nullptr);
    bool tucPresent = tuc != TounicodeState::ABSENT;
    bool tucValid = tuc == TounicodeState::SUPPORTED_COMPLETE;

    bool simple = !cid && is_simple_subtype(fd);
    std::string kind = cid ? "composite" : (simple ? "simple" : "unknown");

    std::vector<Mapping> maps;
    bool mapTruncated = false;
    if (cid)
        build_cid_map(fd, gm, maps, &mapTruncated);
    else if (simple)
        build_simple_map(fd, gm, maps);
    // A bounded charmap scan that stopped early makes the map incomplete.
    if (gm.cmap_truncated) mapTruncated = true;

    std::string out = "{";
    out += "\"page_index\":" + std::to_string(page_index);
    out += ",\"resource\":" + escape_json(key);
    out += ",\"base_font\":" + escape_json(font_base_name(fd));
    out += ",\"family\":" + escape_json(gm.family);
    out += ",\"subtype\":" + escape_json(subtype);
    out += ",\"kind\":" + escape_json(kind);
    out += ",\"embedded\":";
    out += bytes.empty() ? "false" : "true";
    out += ",\"sfnt\":";
    out += gm.sfnt ? "true" : "false";
    out += ",\"cff\":";
    out += gm.cff ? "true" : "false";
    out += ",\"cid\":";
    out += cid ? "true" : "false";
    out += ",\"subset\":";
    out += gm.subset ? "true" : "false";
    out += ",\"has_cmap\":";
    out += gm.has_cmap ? "true" : "false";
    char num[128];
    snprintf(num, sizeof(num), ",\"num_glyphs\":%u,\"units_per_em\":%d,\"glyph_bytes\":%zu",
             gm.num_glyphs, gm.units_per_em, bytes.size());
    out += num;
    out += ",\"has_tounicode\":";
    out += tucPresent ? "true" : "false";
    out += ",\"tounicode_valid\":";
    out += tucValid ? "true" : "false";
    out += ",\"tounicode_state\":";
    out += "\"";
    out += tounicode_state_name(tuc);
    out += "\"";
    out += ",\"encoding\":" + escape_json(font_encoding_name(fd));
    snprintf(num, sizeof(num), ",\"embedding_permission\":%d", gm.fs_type);
    out += num;
    out += ",\"mapping_truncated\":";
    out += mapTruncated ? "true" : "false";

    // Mapping rows go in a separate flat array: NativeJsonParser only handles
    // brace-free object literals and cannot descend into nested arrays.
    int mappingCount = 0, unresolvedCount = 0, ambiguousCount = 0;
    bool firstRow = true;
    for (const auto& mp : maps) {
        bool resolved = mp.uni != 0;
        if (resolved)
            ++mappingCount;
        else if (std::strcmp(mp.provenance, "AMBIGUOUS") == 0)
            ++ambiguousCount;  // ambiguous: preserved but not a usable mapping
        else
            ++unresolvedCount;
        if (!firstRow) mappingsOut += ",";
        firstRow = false;
        std::string row = "{\"page_index\":" + std::to_string(page_index)
                          + ",\"resource\":" + escape_json(key);
        snprintf(num, sizeof(num), ",\"code\":%u,\"unicode\":%d,\"provenance\":\"%s\",\"note\":",
                 mp.code, resolved ? static_cast<int>(mp.uni) : -1, mp.provenance);
        row += num;
        row += escape_json(mp.note);
        row += "}";
        mappingsOut += row;
    }
    snprintf(num, sizeof(num),
             ",\"mapping_count\":%d,\"unresolved_count\":%d,\"ambiguous_count\":%d}", mappingCount,
             unresolvedCount, ambiguousCount);
    out += num;
    return out;
}

// Load a QPDF view of the current document bytes. QPDF::processMemoryFile does
// not copy its input: `backing` must outlive `pdf`.
bool load_qpdf(FPDF_DOCUMENT doc, QPDF& pdf, const char* tag, std::vector<uint8_t>& backing) {
    if (!qpdf_save_fpdf(doc, backing)) return false;
    g_native_op_count.fetch_add(1, std::memory_order_relaxed);
    pdf.setSuppressWarnings(true);
    pdf.setAttemptRecovery(true);
    pdf.processMemoryFile(tag, reinterpret_cast<const char*>(backing.data()), backing.size());
    return true;
}

// Used-code-aware repair helpers: partial-map repair adds only codes the page
// actually shows and preserves entries the font already declares.

// Turn a shown string into source codes: 2-byte big-endian for a CID font, one
// byte per code for a simple font.
void decode_shown_codes(const std::string& s, bool twoByte, std::vector<uint32_t>& out) {
    if (twoByte) {
        for (size_t i = 0; i + 1 < s.size(); i += 2)
            out.push_back((static_cast<uint32_t>(static_cast<uint8_t>(s[i])) << 8) |
                          static_cast<uint8_t>(s[i + 1]));
    } else {
        for (unsigned char c : s) out.push_back(c);
    }
}

// Structured used-code scanning (Commit C).
//
// QPDF's tokenizer decodes literal escapes, line continuations and hex
// strings, so this pass cannot mis-read them the way a hand-rolled byte
// scanner can. Graphics state is threaded through q/Q, form XObjects are
// followed with their own (inherited) resources, and inline images are skipped
// via the tokenizer's ID..EI support so a binary payload can never be mistaken
// for shown text. A scope that cannot be established exactly sets
// `complete = false`; callers then treat used-code knowledge as unknown rather
// than as a smaller set, and never extend a partial map from partial evidence.
struct UsageScan {
    std::unordered_map<std::string, std::vector<std::string>> shown;
    bool complete = true;
    int depth = 0;
    // Content-stream state. A page's /Contents may be an ARRAY of streams that
    // is interpreted as one concatenated instruction sequence, so the selected
    // font and the operand state must survive from one stream to the next
    // (e.g. stream 1 `BT /F1 12 Tf`, stream 2 `<..> Tj ET`).
    std::string curFont;
    std::vector<std::string> fontStack;
    std::string lastName;
    std::vector<std::string> pending;
    bool inImageHeader = false;
};

std::unordered_map<std::string, QPDFObjectHandle> fonts_from_resources(
    QPDFObjectHandle const& res) {
    std::unordered_map<std::string, QPDFObjectHandle> fonts;
    if (!res.isDictionary() || !res.hasKey("/Font")) return fonts;
    QPDFObjectHandle fdict = res.getKey("/Font");
    if (!fdict.isDictionary()) return fonts;
    for (const auto& k : fdict.getKeys()) {
        QPDFObjectHandle d = fdict.getKey(k);
        if (d.isDictionary()) fonts[k] = d;
    }
    return fonts;
}

// True when two font resource entries denote the same font object.
bool same_font(QPDFObjectHandle const& a, QPDFObjectHandle const& b) {
    if (!a.isInitialized() || !b.isInitialized()) return false;
    if (a.isIndirect() && b.isIndirect()) return a.isSameObjectAs(b);
    return a.unparse() == b.unparse();
}

// A reference to a font object from one resource scope. `scope_path` is empty
// for a page's own /Resources and otherwise names the containing form XObject
// chain (e.g. "/Form1" or "/Form1/Inner"), so a font reached through a form is
// distinguishable from the same resource name on the page itself.
struct FontRef {
    int32_t page_index;
    std::string scope;       // "page" | "xobject" | "annotation"
    std::string scope_path;  // "" for page scope
    std::string resource;    // font resource name, with leading slash
};

// A stable identity for a font object, so references can be indexed by object
// once per operation instead of rescanning every scope per candidate font.
// An indirect object is identified by its object/generation number and is
// genuinely shared across the scopes that reach it. A direct dictionary has no
// document identity, and identical content named from two scopes is two
// distinct objects: it is identified by the single scope that names it so it is
// never falsely reported as a shared font.
std::string font_identity(QPDFObjectHandle const& d, int32_t page_index,
                          const std::string& scope, const std::string& scope_path,
                          const std::string& resource) {
    if (d.isIndirect()) {
        QPDFObjGen og = d.getObjGen();
        return "i" + std::to_string(og.getObj()) + ":" + std::to_string(og.getGen());
    }
    return "d" + std::to_string(page_index) + ":" + scope + ":" + scope_path + ":" + resource;
}

// Bounded font-reference graph for one document, built in a single pass:
//   font-object identity -> every (page, scope, resource) that reaches it.
// Each page's own /Resources /Font, every form XObject reachable through
// /Resources /XObject (recursively), and annotation appearance streams are
// traversed. `pageComplete[page]` is false when a scope could not be read
// exactly, so a plan for that page can refuse object-wide mutation rather than
// under-disclose a shared font.
struct ReferenceIndex {
    std::unordered_map<std::string, std::vector<FontRef>> byIdentity;
    std::unordered_map<int32_t, bool> pageComplete;

    const std::vector<FontRef>* lookup(QPDFObjectHandle const& fd, int32_t page_index,
                                       const std::string& scope, const std::string& scope_path,
                                       const std::string& resource) const {
        g_reference_lookups.fetch_add(1, std::memory_order_relaxed);
        auto it = byIdentity.find(font_identity(fd, page_index, scope, scope_path, resource));
        return it == byIdentity.end() ? nullptr : &it->second;
    }

    bool complete(int32_t page_index) const {
        auto it = pageComplete.find(page_index);
        return it == pageComplete.end() || it->second;
    }
};

constexpr int kMaxRefDepth = 32;  // bounded nesting; deeper scopes are "unknown"

void index_resources(QPDFObjectHandle const& res, int32_t page_index,
                     const std::string& scope, const std::string& scope_path,
                     ReferenceIndex& index, std::set<QPDFObjGen>& seen, int depth);

// A form XObject executes with its own /Resources or, when absent, the caller's.
// Recurse into the form's resources so a font it names is disclosed; a form
// without /Resources adds no reference of its own (it uses the caller's).
void index_form(QPDFObjectHandle const& xobj, int32_t page_index, const std::string& scope,
                const std::string& scope_path, ReferenceIndex& index,
                std::set<QPDFObjGen>& seen, int depth) {
    if (depth >= kMaxRefDepth) {
        index.pageComplete[page_index] = false;
        return;
    }
    if (xobj.isIndirect()) {
        QPDFObjGen og = xobj.getObjGen();
        if (og.getObj() != 0 && !seen.insert(og).second) return;  // cycle / repeat
    }
    QPDFObjectHandle formDict = xobj.getDict();
    if (!formDict.hasKey("/Resources")) return;  // inherits the caller's scope
    index_resources(formDict.getKey("/Resources"), page_index, scope, scope_path, index, seen,
                    depth + 1);
}

void index_resources(QPDFObjectHandle const& res, int32_t page_index,
                     const std::string& scope, const std::string& scope_path,
                     ReferenceIndex& index, std::set<QPDFObjGen>& seen, int depth) {
    if (!res.isDictionary()) return;
    g_reference_nodes.fetch_add(1, std::memory_order_relaxed);
    if (res.hasKey("/Font")) {
        QPDFObjectHandle fdict = res.getKey("/Font");
        if (fdict.isDictionary()) {
            for (const auto& k : fdict.getKeys()) {
                QPDFObjectHandle d = fdict.getKey(k);
                if (d.isDictionary())
                    index.byIdentity[font_identity(d, page_index, scope, scope_path, k)]
                        .push_back({page_index, scope, scope_path, k});
            }
        } else {
            index.pageComplete[page_index] = false;
        }
    }
    if (!res.hasKey("/XObject")) return;
    QPDFObjectHandle xos = res.getKey("/XObject");
    if (!xos.isDictionary()) {
        index.pageComplete[page_index] = false;
        return;
    }
    for (const auto& k : xos.getKeys()) {
        QPDFObjectHandle x = xos.getKey(k);
        if (!x.isFormXObject()) continue;
        index_form(x, page_index, "xobject", scope_path + k, index, seen, depth);
    }
}

// Font resources reachable from a page's annotation appearance streams (/AP /N).
void index_annotations(QPDFObjectHandle const& page, int32_t page_index, ReferenceIndex& index,
                       std::set<QPDFObjGen>& seen) {
    if (!page.isDictionary() || !page.hasKey("/Annots")) return;
    QPDFObjectHandle annots = page.getKey("/Annots");
    if (!annots.isArray()) {
        index.pageComplete[page_index] = false;
        return;
    }
    for (int i = 0; i < annots.getArrayNItems(); ++i) {
        QPDFObjectHandle a = annots.getArrayItem(i);
        if (!a.isDictionary() || !a.hasKey("/AP")) continue;
        QPDFObjectHandle ap = a.getKey("/AP");
        if (!ap.isDictionary() || !ap.hasKey("/N")) continue;
        QPDFObjectHandle n = ap.getKey("/N");
        std::string base = "/Annots[" + std::to_string(i) + "]";
        if (n.isFormXObject()) {
            index_form(n, page_index, "annotation", base, index, seen, 0);
        } else if (n.isDictionary()) {
            for (const auto& k : n.getKeys()) {
                QPDFObjectHandle s = n.getKey(k);
                if (s.isFormXObject())
                    index_form(s, page_index, "annotation", base + k, index, seen, 0);
            }
        }
    }
}

ReferenceIndex build_reference_index(const std::vector<QPDFObjectHandle>& pages) {
    ReferenceIndex index;
    for (size_t p = 0; p < pages.size(); ++p) {
        int32_t pi = static_cast<int32_t>(p);
        std::set<QPDFObjGen> seen;  // per page: a shared form discloses per page
        QPDFObjectHandle res = QPDFPageObjectHelper(pages[p]).getAttribute("/Resources", false);
        index_resources(res, pi, "page", "", index, seen, 0);
        index_annotations(pages[p], pi, index, seen);
    }
    return index;
}

// Every reference in the prebuilt index that denotes the same font object as
// `fd`. A mutation of `fd` reaches all of them, so a plan that authorises one
// reference must see the others before it writes.
std::vector<FontRef> font_references(const ReferenceIndex& index, QPDFObjectHandle const& fd,
                                     int32_t page_index, const std::string& resource) {
    const std::vector<FontRef>* refs = index.lookup(fd, page_index, "page", "", resource);
    return refs ? *refs : std::vector<FontRef>{};
}

// An eligible page font entry: the page whose table named it, the resource name,
// and the (possibly shared, indirect) font object it resolves to.
struct EligibleFont {
    int32_t page_index;
    std::string key;
    QPDFObjectHandle dict;
};

void scan_content(const std::string& data, QPDFObjectHandle const& resources,
                  const std::unordered_map<std::string, QPDFObjectHandle>& pageFonts,
                  UsageScan& out);

// Follow a form XObject invoked by `Do`. A form that redefines a font name the
// page scope already uses, or introduces a name that cannot be tied to a page
// font, makes used-code attribution ambiguous, so the whole scan is marked
// incomplete rather than guessed at.
void scan_form(QPDFObjectHandle const& xobj, QPDFObjectHandle const& callerResources,
               const std::unordered_map<std::string, QPDFObjectHandle>& pageFonts,
               UsageScan& out) {
    if (out.depth >= 8) {
        out.complete = false;
        return;
    }
    QPDFObjectHandle formDict = xobj.getDict();
    QPDFObjectHandle formRes = formDict.hasKey("/Resources") ? formDict.getKey("/Resources")
                                                            : callerResources;
    for (const auto& kv : fonts_from_resources(formRes)) {
        auto it = pageFonts.find(kv.first);
        if (it == pageFonts.end() || !same_font(it->second, kv.second)) out.complete = false;
    }
    // A form executes with a copy of the caller's graphics state and its own
    // changes do not persist after it returns, so scan it with the current font
    // but restore the operand and font-selection state afterwards.
    std::string savedFont = out.curFont;  // a form inherits the caller's font
    std::vector<std::string> savedStack = std::move(out.fontStack);
    std::string savedLast = std::move(out.lastName);
    std::vector<std::string> savedPending = std::move(out.pending);
    bool savedImage = out.inImageHeader;
    out.fontStack.clear();
    out.lastName.clear();
    out.pending.clear();
    out.inImageHeader = false;
    ++out.depth;
    scan_content(stream_bytes(xobj), formRes, pageFonts, out);
    --out.depth;
    out.curFont = std::move(savedFont);
    out.fontStack = std::move(savedStack);
    out.lastName = std::move(savedLast);
    out.pending = std::move(savedPending);
    out.inImageHeader = savedImage;
}

void scan_content(const std::string& data, QPDFObjectHandle const& resources,
                  const std::unordered_map<std::string, QPDFObjectHandle>& pageFonts,
                  UsageScan& out) {
    QPDFTokenizer tok;
    tok.allowEOF();  // EOF is a clean end of input, not a bad token
    auto is = std::make_shared<BufferInputSource>("content", data);

    auto flush = [&]() {
        if (!out.curFont.empty()) {
            auto& v = out.shown[out.curFont];
            for (const auto& s : out.pending) v.push_back(s);
        }
        out.pending.clear();
    };

    while (true) {
        QPDFTokenizer::Token t = tok.readToken(is, "content", true);
        QPDFTokenizer::token_type_e tt = t.getType();
        if (tt == QPDFTokenizer::tt_eof) break;
        if (tt == QPDFTokenizer::tt_bad) {
            out.complete = false;
            break;
        }
        if (tt == QPDFTokenizer::tt_space || tt == QPDFTokenizer::tt_comment) continue;
        if (tt == QPDFTokenizer::tt_inline_image) {
            out.inImageHeader = false;  // opaque bytes; nothing to attribute
            continue;
        }
        if (tt == QPDFTokenizer::tt_string) {
            if (!out.inImageHeader) out.pending.push_back(t.getValue());
            continue;
        }
        if (tt == QPDFTokenizer::tt_name) {
            out.lastName = t.getValue();
            continue;
        }
        if (tt == QPDFTokenizer::tt_array_open || tt == QPDFTokenizer::tt_array_close
            || tt == QPDFTokenizer::tt_integer || tt == QPDFTokenizer::tt_real) {
            // TJ arrays interleave strings with position numbers; neither opens
            // nor closes the operand list that a show operator consumes.
            continue;
        }
        if (tt == QPDFTokenizer::tt_word) {
            std::string w = t.getValue();
            if (w == "Tj" || w == "TJ" || w == "'" || w == "\"") {
                flush();
            } else {
                out.pending.clear();
            }
            if (w == "q") {
                out.fontStack.push_back(out.curFont);
            } else if (w == "Q") {
                if (out.fontStack.empty()) {
                    out.complete = false;  // unbalanced graphics state
                } else {
                    out.curFont = out.fontStack.back();
                    out.fontStack.pop_back();
                }
            } else if (w == "Tf") {
                out.curFont = out.lastName;
            } else if (w == "gs") {
                // An ExtGState may carry a /Font [ref size] entry that selects a
                // different font. Resolve it to the page resource name that
                // denotes the same font object, or treat usage as unknown.
                QPDFObjectHandle egs = resources.isDictionary()
                                               && resources.hasKey("/ExtGState")
                                           ? resources.getKey("/ExtGState")
                                           : QPDFObjectHandle();
                QPDFObjectHandle g = egs.isDictionary() && egs.hasKey(out.lastName)
                                         ? egs.getKey(out.lastName)
                                         : QPDFObjectHandle();
                if (!g.isDictionary()) {
                    out.complete = false;
                } else if (g.hasKey("/Font")) {
                    QPDFObjectHandle fa = g.getKey("/Font");
                    if (!fa.isArray() || fa.getArrayNItems() < 1) {
                        out.complete = false;
                    } else {
                        QPDFObjectHandle fref = fa.getArrayItem(0);
                        std::string matched;
                        for (const auto& kv : pageFonts) {
                            if (same_font(kv.second, fref)) {
                                matched = kv.first;
                                break;
                            }
                        }
                        if (matched.empty()) {
                            out.complete = false;  // unknown font selection
                        } else {
                            out.curFont = matched;
                        }
                    }
                }
            } else if (w == "BI") {
                out.inImageHeader = true;
            } else if (w == "ID") {
                // Discard the single whitespace that terminates ID, then let
                // the tokenizer consume the image data up to EI.
                char ch = 0;
                is->read(&ch, 1);
                tok.expectInlineImage(is);
                QPDFTokenizer::Token img = tok.readToken(is, "content", true);
                out.inImageHeader = false;
                if (img.getType() == QPDFTokenizer::tt_bad) {
                    out.complete = false;
                    break;
                }
            } else if (w == "Do") {
                if (out.lastName.empty()) {
                    out.complete = false;
                } else if (!resources.isDictionary() || !resources.hasKey("/XObject")) {
                    out.complete = false;  // an XObject was invoked but none is named
                } else {
                    QPDFObjectHandle xobjects = resources.getKey("/XObject");
                    QPDFObjectHandle xobj = xobjects.isDictionary() && xobjects.hasKey(out.lastName)
                                                ? xobjects.getKey(out.lastName)
                                                : QPDFObjectHandle();
                    if (!xobj.isInitialized()) {
                        out.complete = false;
                    } else if (xobj.isFormXObject()) {
                        scan_form(xobj, resources, pageFonts, out);
                    }
                    // Image XObjects show no text: nothing to attribute.
                }
            }
            out.lastName.clear();
            continue;
        }
        // Dictionaries, braces, null and booleans are not show operands.
        out.pending.clear();
    }
}

// Collect, per font resource name, the raw bytes of strings actually shown by
// the page's content streams and by the form XObjects they invoke. `*complete`
// is set false when any part of the page could not be attributed exactly, so
// callers treat used codes as unknown instead of as a smaller set.
bool collect_used_strings(QPDFObjectHandle page,
                          std::unordered_map<std::string, std::vector<std::string>>& out,
                          bool* complete) {
    if (complete) *complete = true;
    if (!page.isDictionary()) return true;
    QPDFPageObjectHelper helper(page);
    QPDFObjectHandle res = helper.getAttribute("/Resources", false);
    UsageScan scan;
    std::unordered_map<std::string, QPDFObjectHandle> pageFonts = fonts_from_resources(res);
    for (const auto& s : helper.getPageContents()) {
        if (s.isStream()) scan_content(stream_bytes(s), res, pageFonts, scan);
    }
    out = std::move(scan.shown);
    if (complete) *complete = scan.complete;
    return true;
}

// Parse an existing /ToUnicode into declared code -> Unicode entries. Handles
// beginbfchar pairs and scalar beginbfrange runs. *exact is set false for any
// construct this parser cannot reproduce byte-for-byte (multi-code destinations,
// array bfrange, >2-byte source codes), so callers refuse to rewrite a map they
// cannot preserve.
// Decode a PDF hex-string operand into its bytes. Whitespace between digits is
// insignificant; an odd number of digits is padded with a trailing zero nibble
// (PDF 32000-1 7.3.4.3). Any other non-hex character makes the operand
// undecodable; the caller must then refuse the rewrite rather than guess.
bool hex_to_bytes(const std::string& h, std::string& out) {
    std::vector<int> nib;
    nib.reserve(h.size());
    for (char ch : h) {
        int d;
        if (ch >= '0' && ch <= '9')
            d = ch - '0';
        else if (ch >= 'a' && ch <= 'f')
            d = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F')
            d = ch - 'A' + 10;
        else if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\f'
                 || ch == '\0')
            continue;  // insignificant whitespace
        else
            return false;  // invalid hex character
        nib.push_back(d);
    }
    if (nib.size() % 2 != 0) nib.push_back(0);  // trailing zero nibble
    out.clear();
    out.reserve(nib.size() / 2);
    for (size_t i = 0; i + 1 < nib.size(); i += 2)
        out.push_back(static_cast<char>((nib[i] << 4) | nib[i + 1]));
    return true;
}

// Decode a source code operand: 1 or 2 bytes, big-endian, with the byte count
// retained. Anything wider is outside the supported subset.
bool decode_code(const std::string& h, uint32_t& code, uint32_t& width) {
    std::string b;
    if (!hex_to_bytes(h, b)) return false;
    if (b.empty() || b.size() > 2) return false;
    width = static_cast<uint32_t>(b.size());
    code = 0;
    for (unsigned char c : b) code = (code << 8) | c;
    return true;
}

// Decode UTF-16BE destination bytes to exactly one Unicode scalar. Only the
// 2-byte (BMP) and 4-byte (well-formed surrogate pair) forms are reproduced;
// any other length, or a lone surrogate, is outside the supported subset.
bool utf16be_scalar(const std::string& b, uint32_t& uni) {
    auto be16 = [&b](size_t off) {
        return (static_cast<uint32_t>(static_cast<uint8_t>(b[off])) << 8)
               | static_cast<uint32_t>(static_cast<uint8_t>(b[off + 1]));
    };
    if (b.size() == 2) {
        uint32_t u = be16(0);
        if (u >= 0xD800u && u <= 0xDFFFu) return false;  // lone surrogate
        uni = u;
        return true;
    }
    if (b.size() == 4) {
        uint32_t hi = be16(0);
        uint32_t lo = be16(2);
        if (hi >= 0xD800u && hi <= 0xDBFFu && lo >= 0xDC00u && lo <= 0xDFFFu) {
            uni = 0x10000u + ((hi - 0xD800u) << 10) + (lo - 0xDC00u);
            return true;
        }
        return false;  // lone or malformed surrogate pair
    }
    return false;
}

TounicodeState parse_tounicode(QPDFObjectHandle fd,
                               std::unordered_map<uint32_t, uint32_t>* out) {
    if (out) out->clear();
    if (!fd.hasKey("/ToUnicode")) return TounicodeState::ABSENT;
    QPDFObjectHandle tu = fd.getKey("/ToUnicode");
    if (!tu.isStream()) return TounicodeState::MALFORMED;
    // Inherited mappings (a stream-dictionary /UseCMap or a body `usecmap`) are
    // not reproduced by rewriting the local block, so they are unsupported.
    // Read the dict explicitly: QPDFObjectHandle::hasKey() warns and returns
    // false for a non-dictionary object, which on a stream would silently hide
    // an inherited map.
    QPDFObjectHandle tuDict = tu.getDict();
    if (tuDict.hasKey("/UseCMap")) return TounicodeState::UNSUPPORTED;
    std::string s = stream_bytes(tu);
    enum { NONE, BFCHAR, BFRANGE } mode = NONE;
    std::vector<std::string> hexes;
    bool unsupported = false;
    bool malformed = false;
    // A mapping entry was parsed, whether or not the caller asked for the map.
    bool any = false;
    // The integer token immediately before a block keyword is its declared entry
    // count; a count that disagrees with the entries actually present is
    // malformed, not merely odd. -1 means no count was supplied.
    long long pendingNumber = -1;
    long long expectedCount = -1;
    long long entryCount = 0;
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        char c = s[i];
        if (c == '%') {
            while (i < n && s[i] != '\n') ++i;
            continue;
        }
        if (c == '<' && i + 1 < n && s[i + 1] != '<') {
            ++i;
            std::string h;
            while (i < n && s[i] != '>') h += s[i++];
            if (i < n) ++i;
            // A hex string outside a bfchar/bfrange block is a codespace
            // descriptor or other non-mapping value; it carries no mapping and
            // is deliberately not accumulated.
            if (mode == NONE) continue;
            pendingNumber = -1;
            hexes.push_back(h);
            if (mode == BFCHAR && hexes.size() == 2) {
                uint32_t src = 0, srcW = 0;
                uint32_t dst = 0;
                std::string db;
                if (!decode_code(hexes[0], src, srcW)) unsupported = true;
                if (!hex_to_bytes(hexes[1], db) || !utf16be_scalar(db, dst)) unsupported = true;
                (void)srcW;  // width is bounded by decode_code
                if (out) (*out)[src] = dst;
                any = true;
                ++entryCount;
                hexes.clear();
            } else if (mode == BFRANGE && hexes.size() == 3) {
                uint32_t lo = 0, hi = 0, loW = 0, hiW = 0;
                uint32_t dst = 0;
                std::string db;
                if (!decode_code(hexes[0], lo, loW) || !decode_code(hexes[1], hi, hiW)
                        || loW != hiW) {
                    unsupported = true;
                }
                if (!hex_to_bytes(hexes[2], db) || !utf16be_scalar(db, dst)) unsupported = true;
                if (hi < lo) malformed = true;
                uint32_t span = (hi >= lo) ? (hi - lo) : 0;
                if (span >= 65536) unsupported = true;  // beyond the bounded expansion
                bool rangeOk = true;
                if (dst > 0x10FFFFu - span) rangeOk = false;  // runs past scalar space
                if (rangeOk && dst <= 0xDFFFu && dst + span >= 0xD800u) {
                    rangeOk = false;  // crosses the surrogate block
                }
                if (!rangeOk) unsupported = true;
                for (uint32_t k = 0; k + lo <= hi && k < 65536; ++k)
                    if (out) (*out)[lo + k] = dst + k;
                any = true;
                ++entryCount;
                hexes.clear();
            }
            continue;
        }
        if (c == '<' && i + 1 < n && s[i + 1] == '<') { i += 2; continue; }
        if (c == '>' && i + 1 < n && s[i + 1] == '>') { i += 2; continue; }
        if (c == '[') {
            if (mode == BFRANGE) unsupported = true;  // array destination
            i++;
            hexes.clear();
            pendingNumber = -1;
            continue;
        }
        if (c == ']') { i++; hexes.clear(); pendingNumber = -1; continue; }
        if (std::isdigit(static_cast<unsigned char>(c))) {
            size_t st = i;
            while (i < n && std::isdigit(static_cast<unsigned char>(s[i]))) ++i;
            pendingNumber = std::strtoll(s.substr(st, i - st).c_str(), nullptr, 10);
            continue;
        }
        if (std::isalpha(static_cast<unsigned char>(c))) {
            size_t st = i;
            while (i < n && (std::isalpha(static_cast<unsigned char>(s[i])) ||
                             std::isdigit(static_cast<unsigned char>(s[i]))))
                ++i;
            std::string w = s.substr(st, i - st);
            if (w == "beginbfchar") {
                if (mode != NONE) malformed = true;  // nested block
                mode = BFCHAR;
                expectedCount = pendingNumber;
                entryCount = 0;
                hexes.clear();
            } else if (w == "endbfchar") {
                if (mode != BFCHAR) malformed = true;
                if (!hexes.empty()) malformed = true;  // dangling source operand
                if (expectedCount >= 0 && entryCount != expectedCount) malformed = true;
                mode = NONE;
                expectedCount = -1;
                hexes.clear();
            } else if (w == "beginbfrange") {
                if (mode != NONE) malformed = true;  // nested block
                mode = BFRANGE;
                expectedCount = pendingNumber;
                entryCount = 0;
                hexes.clear();
            } else if (w == "endbfrange") {
                if (mode != BFRANGE) malformed = true;
                if (!hexes.empty()) malformed = true;  // dangling operand
                if (expectedCount >= 0 && entryCount != expectedCount) malformed = true;
                mode = NONE;
                expectedCount = -1;
                hexes.clear();
            } else if (w == "usecmap") {
                unsupported = true;
            }
            pendingNumber = -1;
            continue;
        }
        ++i;
    }
    if (mode != NONE) malformed = true;  // unterminated block
    if (malformed) return TounicodeState::MALFORMED;
    if (unsupported) return TounicodeState::UNSUPPORTED;
    if (any) return TounicodeState::SUPPORTED_COMPLETE;
    return TounicodeState::SUPPORTED_EMPTY;
}

// Opaque identity of the input state a repair decision was derived from. Any
// change to the declared map, its validity, the code→Unicode evidence set, or
// the base encoding produces a different value, so a prepared plan can detect
// that the document moved underneath it.
std::string font_plan_identity(QPDFObjectHandle const& fd, TounicodeState st,
                               const std::unordered_map<uint32_t, uint32_t>& declared,
                               const std::vector<Mapping>& resolved, bool cid,
                               const std::string& programHash,
                               const std::vector<uint32_t>& usedCodes) {
    std::vector<uint32_t> codes;
    codes.reserve(declared.size());
    for (const auto& kv : declared) codes.push_back(kv.first);
    std::sort(codes.begin(), codes.end());

    uint64_t h = 1469598103934665603ull;
    auto mix = [&h](uint64_t v) {
        h ^= v;
        h *= 1099511628211ull;
    };
    mix(static_cast<uint64_t>(st));
    mix(cid ? 1u : 2u);
    // Every accepted declaration, by code and destination value, so a changed
    // value with an unchanged count still changes the identity.
    for (uint32_t c : codes) {
        mix(c);
        mix(declared.at(c));
    }
    // Every recovered mapping's contents, not merely how many there are.
    std::vector<Mapping> sorted = resolved;
    std::sort(sorted.begin(), sorted.end(),
              [](const Mapping& a, const Mapping& b) { return a.code < b.code; });
    for (const auto& mp : sorted) {
        mix(mp.code);
        mix(mp.uni);
    }
    // The embedded program bytes: a different program is a different plan.
    for (unsigned char ch : programHash) mix(ch);
    // The codes the page actually shows: changed content changes the plan.
    std::vector<uint32_t> usedSorted = usedCodes;
    std::sort(usedSorted.begin(), usedSorted.end());
    usedSorted.erase(std::unique(usedSorted.begin(), usedSorted.end()), usedSorted.end());
    for (uint32_t u : usedSorted) mix(u);
    std::string enc = fd.hasKey("/Encoding") && fd.getKey("/Encoding").isName()
                          ? fd.getKey("/Encoding").getName()
                          : "";
    for (unsigned char ch : enc) mix(ch);

    char buf[32];
    std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(h));
    return buf;
}

// One font's repair decision, derived without mutating anything. This is the
// single definition of "eligible for /ToUnicode repair": both the planner the
// caller authorises against and the applier consume it, so a resource the plan
// does not list can never be modified.
struct FontRepairDecision {
    bool eligible = false;
    bool cid = false;
    std::string mode;      // "extend" (valid map, add missing used codes) | "replace"
    std::string reason;    // why not eligible, when !eligible (human detail)
    std::string code;      // stable diagnostic reason code, when one applies
    std::string cmap;      // the /ToUnicode stream to install, when eligible
    std::string identity;  // opaque snapshot identity
    size_t used = 0;       // distinct codes this page shows under the resource
    size_t additions = 0;  // mappings the decision would add
};

FontRepairDecision decide_font_repair(QPDFObjectHandle const& fd,
                                      const std::vector<std::string>* usedStrings,
                                      bool usageComplete, ProgramCache& cache) {
    FontRepairDecision d;
    bool cid = is_cid_font(fd);
    d.cid = cid;
    if (!cid && !is_simple_subtype(fd)) {
        d.reason = "unsupported_subtype";
        return d;
    }

    // Parse the existing map up front. A stream that inherits mappings, is
    // malformed, or uses constructs the writer cannot reproduce is left
    // untouched: rewriting it would drop or corrupt entries.
    std::unordered_map<uint32_t, uint32_t> declared;
    TounicodeState st = parse_tounicode(fd, &declared);
    if (st == TounicodeState::UNSUPPORTED) {
        d.reason = "declared_map_unsupported";
        d.code = "DECLARED_CMAP_UNSUPPORTED";
        return d;
    }
    if (st == TounicodeState::MALFORMED) {
        d.reason = "declared_map_malformed";
        d.code = "DECLARED_CMAP_UNSUPPORTED";
        return d;
    }
    bool present = st != TounicodeState::ABSENT;
    bool valid = st == TounicodeState::SUPPORTED_COMPLETE;

    QPDFObjectHandle descriptor = descriptor_of(fd, cid);
    std::string bytes = program_bytes(descriptor);
    GlyphMaps const& gm = glyph_maps_cached(bytes, cache);
    std::string programHash = program_content_hash(bytes);

    std::vector<Mapping> maps;
    std::string mapCode;
    bool mapTruncated = gm.cmap_truncated;
    if (cid)
        build_cid_map(fd, gm, maps, &mapTruncated, &mapCode);
    else
        build_simple_map(fd, gm, maps, &mapCode);
    // An incomplete analysis cannot justify a mapping decision: refuse to
    // mutate rather than install a map built from partial evidence.
    if (mapTruncated) {
        d.reason = "analysis_truncated";
        d.code = "ANALYSIS_LIMIT_REACHED";
        return d;
    }

    std::vector<Mapping> resolved;
    bool ambiguousOnly = false;
    resolved.reserve(maps.size());
    for (const auto& mp : maps) {
        if (mp.uni != 0)
            resolved.push_back(mp);
        else if (std::strcmp(mp.provenance, "AMBIGUOUS") == 0)
            ambiguousOnly = true;
    }
    if (resolved.empty()) {
        // Distinguish an unexplained gap from a cause we can name: a mapping
        // that could not be built, or one built entirely from ambiguous codes.
        if (!mapCode.empty()) {
            d.reason = "no_evidence";
            d.code = mapCode;
        } else if (ambiguousOnly) {
            d.reason = "no_evidence";
            d.code = "AMBIGUOUS_GLYPH_SEMANTICS";
        } else {
            d.reason = "no_evidence";
        }
        return d;
    }

    std::vector<uint32_t> used;
    if (usedStrings)
        for (const auto& s : *usedStrings) decode_shown_codes(s, cid, used);
    std::unordered_set<uint32_t> usedSet(used.begin(), used.end());
    d.used = usedSet.size();

    d.identity = font_plan_identity(fd, st, declared, resolved, cid, programHash, used);

    if (present && valid) {
        // Valid map: preserve declared entries, add only missing codes the page
        // uses. If the page could not be scanned exactly, usage is unknown and
        // the map is left alone rather than extended from partial evidence.
        if (!usageComplete) {
            d.reason = "usage_unknown";
            return d;
        }
        if (usedSet.empty()) {
            d.reason = "no_used_codes";
            return d;
        }
        std::vector<Mapping> additions;
        for (const auto& mp : resolved) {
            if (declared.find(mp.code) != declared.end()) continue;
            if (usedSet.find(mp.code) == usedSet.end()) continue;
            additions.push_back(mp);
        }
        if (additions.empty()) {
            d.reason = "already_complete";
            return d;
        }
        std::vector<Mapping> merged;
        merged.reserve(declared.size() + additions.size());
        for (const auto& kv : declared)
            merged.push_back({kv.first, kv.second, "DECLARED", "existing"});
        for (const auto& mp : additions) merged.push_back(mp);
        d.cmap = build_tounicode_cmap(merged, cid);
        d.mode = "extend";
        d.additions = additions.size();
        d.eligible = true;
        return d;
    }

    // Absent or structurally empty: recover the evidence-backed set.
    d.cmap = build_tounicode_cmap(resolved, cid);
    d.mode = "replace";
    d.additions = resolved.size();
    d.eligible = true;
    return d;
}

// Build the plan JSON object for one page from an already-loaded page vector,
// reusing the shared program cache and prebuilt reference index. Returns false
// when page_index doesn't exist. This is the single definition of the per-page
// plan shape, shared by jpdfium_font_plan (one page) and jpdfium_font_plan_pages
// (every page), so both report exactly what jpdfium_font_fix_tounicode* would
// change. `references_complete` is false when the page's resource graph could
// not be read exactly, so a caller can refuse object-wide mutation. Eligible
// font objects are appended to `eligibleOut` so the document-level reference
// table can be emitted once instead of repeated under every owner page.
bool font_plan_page_json(const std::vector<QPDFObjectHandle>& pages, const ReferenceIndex& index,
                         int32_t page_index, ProgramCache& cache, std::string& out,
                         std::vector<EligibleFont>* eligibleOut) {
    std::vector<FontEntry> fonts;
    if (!page_font_dicts(pages, page_index, fonts)) return false;

    std::unordered_map<std::string, std::vector<std::string>> usedRaw;
    bool usageComplete = true;
    if (page_index >= 0 && static_cast<size_t>(page_index) < pages.size())
        collect_used_strings(pages[static_cast<size_t>(page_index)], usedRaw, &usageComplete);

    std::string repairs;
    std::string skipped;
    bool firstRepair = true;
    bool firstSkip = true;
    for (auto& fe : fonts) {
        const std::vector<std::string>* used = nullptr;
        auto itU = usedRaw.find(fe.key);
        if (itU != usedRaw.end()) used = &itU->second;
        FontRepairDecision d = decide_font_repair(fe.dict, used, usageComplete, cache);
        if (!d.eligible) {
            if (!firstSkip) skipped += ",";
            firstSkip = false;
            skipped += "{\"resource\":" + escape_json(fe.key)
                       + ",\"reason\":" + escape_json(d.reason);
            if (!d.code.empty()) skipped += ",\"code\":" + escape_json(d.code);
            skipped += "}";
            continue;
        }
        if (!firstRepair) repairs += ",";
        firstRepair = false;
        repairs += "{\"resource\":" + escape_json(fe.key)
                   + ",\"object\":"
                   + escape_json(font_identity(fe.dict, page_index, "page", "", fe.key))
                   + ",\"mode\":\"" + d.mode
                   + "\",\"used\":" + std::to_string(d.used)
                   + ",\"additions\":" + std::to_string(d.additions)
                   + ",\"identity\":\"" + d.identity + "\"}";
        if (eligibleOut) eligibleOut->push_back({page_index, fe.key, fe.dict});
    }

    out = "{\"page_index\":" + std::to_string(page_index) + ",\"references_complete\":"
          + (index.complete(page_index) ? "true" : "false") + ",\"repairs\":[" + repairs
          + "],\"skipped\":[" + skipped + "]}";
    return true;
}

// The complete reference set of every font object listed in `eligible`, emitted
// once per object as a flat array of flat rows (object, page, scope, resource).
// Mutating a shared font dictionary reaches all of its references, so the plan
// discloses them and the caller must authorise the whole set.
void emit_object_references(const ReferenceIndex& index,
                            const std::vector<EligibleFont>& eligible, std::string& out) {
    std::set<std::string> emitted;
    bool first = true;
    for (const auto& obj : eligible) {
        std::string id = font_identity(obj.dict, obj.page_index, "page", "", obj.key);
        if (!emitted.insert(id).second) continue;
        for (const auto& ref : font_references(index, obj.dict, obj.page_index, obj.key)) {
            if (!first) out += ",";
            first = false;
            out += "{\"object\":" + escape_json(id)
                   + ",\"page_index\":" + std::to_string(ref.page_index)
                   + ",\"resource\":" + escape_json(ref.resource)
                   + ",\"scope\":" + escape_json(ref.scope)
                   + ",\"scope_path\":" + escape_json(ref.scope_path) + "}";
        }
    }
}

}  // namespace

int32_t jpdfium_font_evidence(int64_t doc, int32_t page_index, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_evidence", backing)) return JPDFIUM_ERR_IO;
        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        std::vector<FontEntry> fonts;
        if (!page_font_dicts(pages, page_index, fonts)) return JPDFIUM_ERR_NOT_FOUND;

        std::string out = "{\"page_index\":";
        out += std::to_string(page_index);
        out += ",\"fonts\":[";
        std::string mappings;
        bool first = true;
        ProgramCache cache;
        for (const auto& fe : fonts) {
            if (!first) out += ",";
            first = false;
            out += font_evidence_json(page_index, fe.key, fe.dict, mappings, cache);
        }
        out += "],\"mappings\":[";
        out += mappings;
        out += "]}";
        *json = strdup(out.c_str());
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Evidence for many pages from a single QPDF view. Font programs are analysed
// once per distinct program across every requested page. The output keeps the
// single-page shape but flattens the pages: each font and mapping row carries
// its own "page_index", so a caller can group rows back into per-page evidence
// without a nested-array parser. Caller frees *json with jpdfium_free_string.
int32_t jpdfium_font_evidence_pages(int64_t doc, const int32_t* pages, int32_t count, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    if (count < 0 || (count > 0 && !pages)) return JPDFIUM_ERR_INVALID;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_evidence_pages", backing)) return JPDFIUM_ERR_IO;

        std::string out = "{\"pages\":" + std::to_string(count) + ",\"fonts\":[";
        std::string mappings;
        bool firstFont = true;
        ProgramCache cache;
        std::vector<QPDFObjectHandle> allPages = pdf.getAllPages();
        for (int32_t i = 0; i < count; ++i) {
            std::vector<FontEntry> fonts;
            if (!page_font_dicts(allPages, pages[i], fonts)) continue;
            for (const auto& fe : fonts) {
                if (!firstFont) out += ",";
                firstFont = false;
                out += font_evidence_json(pages[i], fe.key, fe.dict, mappings, cache);
            }
        }
        out += "],\"mappings\":[";
        out += mappings;
        out += "]}";
        *json = strdup(out.c_str());
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Plan the /ToUnicode repairs for a page without mutating the document.
//
// Returns the exact set of resources `jpdfium_font_fix_tounicode` would change,
// with an opaque per-resource identity and the identity of the font object it
// mutates:
//   {"pages":[{"page_index":N,"references_complete":true,
//              "repairs":[{"resource":"F1","object":"i5:0","mode":"replace|extend",
//                          "used":U,"additions":A,"identity":"..."}],
//              "skipped":[{"resource":"F2","reason":"already_complete"}]}],
//    "references":[{"object":"i5:0","page_index":P,"resource":"R",
//                   "scope":"page|xobject|annotation","scope_path":""}]}
// The document-level `references` table lists, once per font object, every
// (page, scope, resource) that reaches it — including form XObjects and
// annotation appearance streams, so a shared font dictionary is disclosed for
// EVERY reference it will be mutated through.
// Callers authorise by resource and apply with jpdfium_font_fix_tounicode; because
// both use decide_font_repair(), the authorised set is exactly the mutation set.
// Caller frees *json with jpdfium_free_string.
int32_t jpdfium_font_plan(int64_t doc, int32_t page_index, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_plan", backing)) return JPDFIUM_ERR_IO;

        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        ProgramCache cache;
        ReferenceIndex index = build_reference_index(pages);
        std::string page;
        std::vector<EligibleFont> eligible;
        if (!font_plan_page_json(pages, index, page_index, cache, page, &eligible))
            return JPDFIUM_ERR_NOT_FOUND;
        std::string references;
        emit_object_references(index, eligible, references);
        std::string out = "{\"pages\":[" + page + "],\"references\":[" + references + "]}";
        *json = strdup(out.c_str());
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Plan every page's /ToUnicode repairs from ONE document load, analysing each
// distinct font program once across pages and indexing every font reference
// once. The output keeps the single-page shape but wraps the pages:
//   {"pages":[{"page_index":0,...}, ...],
//    "references":[{"object":"i5:0","page_index":P,"resource":"R",
//                   "scope":"...","scope_path":"..."}, ...]}
// Caller frees *json with jpdfium_free_string.
int32_t jpdfium_font_plan_pages(int64_t doc, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_plan_pages", backing)) return JPDFIUM_ERR_IO;

        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        ProgramCache cache;
        ReferenceIndex index = build_reference_index(pages);
        std::string rows = "[";
        std::vector<EligibleFont> eligible;
        bool first = true;
        for (size_t i = 0; i < pages.size(); ++i) {
            std::string row;
            if (!font_plan_page_json(pages, index, static_cast<int32_t>(i), cache, row, &eligible))
                continue;
            if (!first) rows += ",";
            first = false;
            rows += row;
        }
        rows += "]";
        std::string references;
        emit_object_references(index, eligible, references);
        std::string out = "{\"pages\":" + rows + ",\"references\":[" + references + "]}";
        *json = strdup(out.c_str());
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Apply every eligible /ToUnicode repair for one page against an
// already-loaded QPDF view. Returns the number of resources changed, or -1 when
// the page index does not exist.
int fix_page_tounicode(QPDF& pdf, const std::vector<QPDFObjectHandle>& pages, int32_t page_index,
                       ProgramCache& cache) {
    std::vector<FontEntry> fonts;
    if (!page_font_dicts(pages, page_index, fonts)) return -1;

    // Codes shown on this page per font resource; an unreadable content
    // stream simply yields no used codes.
    std::unordered_map<std::string, std::vector<std::string>> usedRaw;
    bool usageComplete = true;
    if (page_index >= 0 && static_cast<size_t>(page_index) < pages.size())
        collect_used_strings(pages[static_cast<size_t>(page_index)], usedRaw, &usageComplete);

    int fixed = 0;
    for (auto& fe : fonts) {
        QPDFObjectHandle fd = fe.dict;
        const std::vector<std::string>* used = nullptr;
        auto itU = usedRaw.find(fe.key);
        if (itU != usedRaw.end()) used = &itU->second;
        FontRepairDecision d = decide_font_repair(fd, used, usageComplete, cache);
        if (!d.eligible) continue;
        fd.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&pdf, d.cmap));
        ++fixed;
    }
    return fixed;
}

int32_t jpdfium_font_fix_tounicode(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_tounicode", backing)) return JPDFIUM_ERR_IO;

        ProgramCache cache;
        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        int fixed = fix_page_tounicode(pdf, pages, page_index, cache);
        if (fixed < 0) return JPDFIUM_ERR_NOT_FOUND;

        if (fixed == 0) return JPDFIUM_OK;  // no mutation: leave the document untouched
        int32_t rc = docwrapper_reload(w, qpdf_write(pdf));
        if (rc != JPDFIUM_OK) return rc;
        if (fonts_fixed) *fonts_fixed = fixed;
        return JPDFIUM_OK;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Repair many pages from a single QPDF view and one reload. Each distinct font
// program is analysed once across every page. Caller frees nothing.
int32_t jpdfium_font_fix_tounicode_pages(int64_t doc, const int32_t* pages, int32_t count,
                                         int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    if (count < 0 || (count > 0 && !pages)) return JPDFIUM_ERR_INVALID;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    try {
        std::vector<uint8_t> backing;  // must outlive `pdf`
        QPDF pdf;
        if (!load_qpdf(w->core->doc, pdf, "font_tounicode_pages", backing)) return JPDFIUM_ERR_IO;

        ProgramCache cache;
        std::vector<QPDFObjectHandle> allPages = pdf.getAllPages();
        int fixed = 0;
        for (int32_t i = 0; i < count; ++i) {
            int r = fix_page_tounicode(pdf, allPages, pages[i], cache);
            if (r > 0) fixed += r;
        }

        if (fixed == 0) return JPDFIUM_OK;  // no mutation: leave the document untouched
        int32_t rc = docwrapper_reload(w, qpdf_write(pdf));
        if (rc != JPDFIUM_OK) return rc;
        if (fonts_fixed) *fonts_fixed = fixed;
        return JPDFIUM_OK;
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else  // !JPDFIUM_HAS_FREETYPE

int32_t jpdfium_font_evidence(int64_t, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_plan(int64_t, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_plan_pages(int64_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_evidence_pages(int64_t, const int32_t*, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_fix_tounicode(int64_t, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_fix_tounicode_pages(int64_t, const int32_t*, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif  // JPDFIUM_HAS_FREETYPE

#else  // !JPDFIUM_HAS_QPDF

int32_t jpdfium_metadata_strip(int64_t, const char**, int32_t) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_metadata_strip_all(int64_t) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_strip_fonts(int64_t, int32_t* fonts_removed) {
    if (fonts_removed) *fonts_removed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_evidence(int64_t, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_plan(int64_t, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_plan_pages(int64_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_evidence_pages(int64_t, const int32_t*, int32_t, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_fix_tounicode(int64_t, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_fix_tounicode_pages(int64_t, const int32_t*, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// XMP pattern redaction requires pugixml in addition to qpdf

#if defined(JPDFIUM_HAS_PUGIXML) && defined(JPDFIUM_HAS_QPDF)

#include <pugixml.hpp>

int32_t jpdfium_xmp_redact_patterns(int64_t doc, const char** patterns, int32_t pattern_count,
                                    int32_t* fields_redacted) {
    try {
        (void)doc;
        (void)patterns;
        (void)pattern_count;
        if (fields_redacted) *fields_redacted = 0;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_xmp_redact_patterns(int64_t, const char**, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// ICU4C Text Processing

#ifdef JPDFIUM_HAS_ICU

#include <unicode/brkiter.h>
#include <unicode/normlzr.h>
#include <unicode/ubidi.h>
#include <unicode/unistr.h>

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    try {
        if (!text || !result) return JPDFIUM_ERR_INVALID;

        UErrorCode status = U_ZERO_ERROR;
        icu::UnicodeString src = icu::UnicodeString::fromUTF8(text);
        icu::UnicodeString dst;
        icu::Normalizer::normalize(src, UNORM_NFC, 0, dst, status);

        if (U_FAILURE(status)) {
            *result = strdup(text);  // fallback to original
            return JPDFIUM_OK;
        }

        std::string utf8;
        dst.toUTF8String(utf8);
        *result = strdup(utf8.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_icu_break_sentences(const char* text, char** json_result) {
    try {
        if (!text || !json_result) return JPDFIUM_ERR_INVALID;

        UErrorCode status = U_ZERO_ERROR;
        std::unique_ptr<icu::BreakIterator> bi(
            icu::BreakIterator::createSentenceInstance(icu::Locale::getDefault(), status));

        if (U_FAILURE(status)) {
            *json_result = strdup("[]");
            return JPDFIUM_ERR_NATIVE;
        }

        icu::UnicodeString utext = icu::UnicodeString::fromUTF8(text);
        bi->setText(utext);

        std::string json = "[";
        bool first = true;
        int32_t start = bi->first();
        int32_t end = bi->next();

        while (end != icu::BreakIterator::DONE) {
            if (!first) json += ",";
            first = false;

            icu::UnicodeString segment;
            utext.extractBetween(start, end, segment);
            std::string segUtf8;
            segment.toUTF8String(segUtf8);

            // Escape for JSON
            std::string escaped;
            for (char c : segUtf8) {
                switch (c) {
                    case '"':
                        escaped += "\\\"";
                        break;
                    case '\\':
                        escaped += "\\\\";
                        break;
                    case '\n':
                        escaped += "\\n";
                        break;
                    case '\r':
                        escaped += "\\r";
                        break;
                    case '\t':
                        escaped += "\\t";
                        break;
                    default:
                        escaped += c;
                }
            }

            char buf[4096];
            snprintf(buf, sizeof(buf), "{\"start\":%d,\"end\":%d,\"text\":\"%s\"}", start, end,
                     escaped.c_str());
            json += buf;

            start = end;
            end = bi->next();
        }

        json += "]";
        *json_result = strdup(json.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    try {
        if (!text || !result) return JPDFIUM_ERR_INVALID;

        icu::UnicodeString utext = icu::UnicodeString::fromUTF8(text);
        int32_t length = utext.length();
        if (length == 0) {
            *result = strdup("");
            return JPDFIUM_OK;
        }

        UErrorCode status = U_ZERO_ERROR;
        UBiDi* bidi = ubidi_open();
        ubidi_setPara(bidi, utext.getBuffer(), length, UBIDI_DEFAULT_LTR, nullptr, &status);

        if (U_FAILURE(status)) {
            ubidi_close(bidi);
            *result = strdup(text);
            return JPDFIUM_OK;
        }

        // Get visual ordering
        icu::UnicodeString visual(length, 0, 0);
        int32_t runCount = ubidi_countRuns(bidi, &status);
        int32_t visPos = 0;

        for (int32_t i = 0; i < runCount; i++) {
            int32_t logStart, runLen;
            UBiDiDirection dir = ubidi_getVisualRun(bidi, i, &logStart, &runLen);
            if (dir == UBIDI_RTL) {
                for (int32_t j = logStart + runLen - 1; j >= logStart; --j) {
                    visual.setCharAt(visPos++, utext.charAt(j));
                }
            } else {
                for (int32_t j = logStart; j < logStart + runLen; ++j) {
                    visual.setCharAt(visPos++, utext.charAt(j));
                }
            }
        }

        ubidi_close(bidi);

        std::string utf8;
        visual.toUTF8String(utf8);
        *result = strdup(utf8.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else  // !JPDFIUM_HAS_ICU

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    if (result) *result = strdup(text ? text : "");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_icu_break_sentences(const char*, char** json_result) {
    if (json_result) *json_result = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    if (result) *result = strdup(text ? text : "");
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif  // JPDFIUM_HAS_ICU
