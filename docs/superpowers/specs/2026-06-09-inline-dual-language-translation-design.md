# Inline Dual-Language Translation Optimization

**Date:** 2026-06-09
**Status:** Approved

## Problem

1. Translation API frequently fails — Microsoft gets CAPTCHA'd, Google rate-limited, no robust fallback chain
2. Current `<ruby>/<rt>` display can break page layouts in headings, buttons, tables, and other HTML structures
3. Any batch failure causes the entire page translation to fail with no partial results

## Solution

### 1. Display: Inline dual-language (replacing `<ruby>` approach)

Replace `<ruby>` text-node wrapping with `<span>` wrapper per text node.

**DOM structure:**
```html
<!-- Before -->
<p>Hello world</p>

<!-- After -->
<p><span data-aurora-translation-unit="1" data-aurora-translation-id="...">
  <span class="__aurora_translation_source">Hello world</span>
  <span class="__aurora_translation_text">你好世界</span>
</span></p>
```

**CSS rules:**
- Block parents (h1-h6, p, li, td, th, dd, dt, figcaption, caption):
  `.aurora_translation_text` gets `display: block; margin-top: 2px;`
- Inline parents (a, span, em, strong, etc.):
  `.aurora_translation_text` gets `display: inline; margin-left: 6px;`
- Style: `color: #3b82f6; font-size: 0.75em; line-height: 1.4;`

**Clear script:** Same logic — unwrap `<span>` back to text node using `data-aurora-source` attribute.

### 2. API reliability: Multi-engine fallback with per-segment retry

**Default engine change:**
- Default `TranslationEngine` changes from `MICROSOFT` to `GOOGLE` in `BrowserModels.kt`
- Users who previously set Microsoft keep their preference

**Fallback chain per batch:**
1. Selected engine (e.g., Google)
2. If selected is not Google and fails → try Google
3. If Google fails → try MyMemory
4. If MyMemory fails → return empty for that batch (graceful degradation)

**Per-segment retry:**
- If a batch translation returns empty or throws, retry individual segments
- For each segment: try Google → try MyMemory → skip (leave original text)
- 1 retry with 1s delay for timeout/HTTP 429 errors

**Session translation cache:**
- `HashMap<String, String>` mapping source text → translated text
- Checked before API calls; avoids re-translating identical text across segments
- Cleared when translation is cleared or activity destroyed

**Partial result application:**
- `translateCurrentPage()` applies translations batch-by-batch via `buildApplyScript()`
- Progress feedback: "已翻译 N/M 段" during translation
- Final message: "X/Y 段双语翻译已应用" (with count of actually translated segments)
- If some segments fail, show partial results instead of failing entirely

### 3. Files to modify

| File | Changes |
|------|---------|
| `browser/PageTranslator.kt` | Rewrite JS scripts for `<span>` approach; add session cache; implement fallback chain; add per-segment retry; add `translateWithDeepL` if API key present |
| `model/BrowserModels.kt` | Change default `TranslationEngine` from `MICROSOFT` to `GOOGLE` |
| `MainActivity.kt` | Partial batch application; progress feedback; update error messages with counts |

### 4. Error handling

- Transient errors (timeout, HTTP 429): retry once with 1s backoff
- Permanent errors (invalid key, CAPTCHA): skip engine, try next in chain
- All engines fail for a segment: leave original text, don't show error
- Page navigation during translation: cancel job silently
- Empty translation result: treat as failure for that segment, continue with others

### 5. Constraints

- No new dependencies required
- Keep existing `TranslationEngine` enum and settings UI
- Keep batch marker encoding for existing engines
- Google API endpoint unchanged: `translate.googleapis.com/translate_a/single`
- MyMemory endpoint unchanged: `api.mymemory.translated.net`
- Session cache only — no persistent disk cache for translations
