# Inline Dual-Language Translation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `<ruby>` translation display with inline `<span>` dual-language, improve API reliability with fallback chain and per-segment retry.

**Architecture:** Rewrite JS collect/apply/clear scripts in `PageTranslator.kt` to use `<span>` wrappers instead of `<ruby>/<rt>`. Add session translation cache, multi-engine fallback chain (Google → MyMemory → skip), per-segment retry with backoff. Change default engine to Google. Update `MainActivity.translateCurrentPage()` for batch-by-batch partial application with progress feedback.

**Tech Stack:** Kotlin, JavaScript (injected WebView), Google Translate API, MyMemory API

---

### Task 1: Change default translation engine to Google

**Files:**
- Modify: `app/src/main/java/com/fenglan/aurorabrowser/model/BrowserModels.kt:74-86`

- [ ] **Step 1: Change default engine and fromKey fallback**

In `BrowserModels.kt`, change `TranslationEngine.fromKey` fallback from `MICROSOFT` to `GOOGLE`, and reorder enum so GOOGLE is first:

```kotlin
enum class TranslationEngine(val key: String, val label: String, val requiresCredential: Boolean = false) {
    GOOGLE("google", "谷歌翻译"),
    MICROSOFT("microsoft", "微软翻译"),
    BAIDU("baidu", "百度翻译", true),
    YOUDAO("youdao", "有道翻译", true),
    ;

    companion object {
        fun fromKey(key: String?): TranslationEngine {
            return entries.firstOrNull { it.key == key } ?: GOOGLE
        }
    }
}
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 2: Rewrite JS scripts for inline `<span>` dual-language display

**Files:**
- Modify: `app/src/main/java/com/fenglan/aurorabrowser/browser/PageTranslator.kt:38-194`

- [ ] **Step 1: Replace `buildCollectScript()` with inline span approach**

Replace the entire `buildCollectScript()` method (lines 38-142) with:

```kotlin
fun buildCollectScript(limit: Int = MAX_SEGMENTS): String {
    return """
        (function() {
          try {
            document.querySelectorAll('[data-aurora-translation-unit="1"]').forEach(function(node) {
              var original = node.getAttribute('data-aurora-source');
              if (!original) {
                var sourceNode = node.querySelector('.__aurora_translation_source');
                original = sourceNode ? sourceNode.textContent : '';
              }
              node.replaceWith(document.createTextNode(original || ''));
            });
            var styleNode = document.getElementById('__aurora_translation_style__');
            if (styleNode) { styleNode.remove(); }
            if (!document.body && !document.documentElement) { return '[]'; }
            var style = document.createElement('style');
            style.id = '__aurora_translation_style__';
            style.textContent =
              '.__aurora_translation_text{color:#3b82f6;font-size:0.75em;line-height:1.4;}' +
              '.__aurora_translation_text.__aurora_block{display:block;margin-top:2px;}' +
              '.__aurora_translation_text.__aurora_inline{display:inline;margin-left:6px;}' +
              '.__aurora_translation_source{color:inherit;}';
            (document.head || document.documentElement).appendChild(style);
            var root = document.body || document.documentElement;
            var results = [];
            var seed = String(Date.now());
            var BLOCK_RE = /^H[1-6]|^P$|^LI$|^TD$|^TH$|^DD$|^DT$|^FIGCAPTION$|^CAPTION$|^BLOCKQUOTE$|^DIV$|^SECTION$|^ARTICLE$|^HEADER$|^FOOTER$|^MAIN$|^ASIDE$|^NAV$|^SUMMARY$/;
            var isBlock = function(el) {
              if (!el) return false;
              var tag = el.tagName;
              if (BLOCK_RE.test(tag)) return true;
              var d = window.getComputedStyle(el).display;
              return d === 'block' || d === 'flex' || d === 'grid' || d === 'list-item';
            };
            var shouldSkip = function(parent, text) {
              if (!parent || !text) return true;
              if (parent.closest && parent.closest(
                '[data-aurora-translation-unit="1"],script,style,noscript,textarea,input,select,option,button,canvas,svg,code,pre,kbd,samp'
              )) return true;
              var compact = String(text).replace(/\s+/g, ' ').trim();
              if (!compact || compact.length < 2 || compact.length > $MAX_SEGMENT_TEXT_LENGTH) return true;
              if (!/[A-Za-z\u00C0-\u024F\u4E00-\u9FFF]/.test(compact)) return true;
              if (/^(https?:\/\/|www\.)/i.test(compact)) return true;
              var cs = window.getComputedStyle(parent);
              if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity || '1') === 0) return true;
              var rect = parent.getBoundingClientRect();
              return !rect || rect.width < 4 || rect.height < 4;
            };
            var walker = document.createTreeWalker(
              root, NodeFilter.SHOW_TEXT,
              { acceptNode: function(n) {
                return shouldSkip(n && n.parentElement, n && n.nodeValue)
                  ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
              }}
            );
            var current;
            while ((current = walker.nextNode()) && results.length < ${limit.coerceAtMost(MAX_SEGMENTS).coerceAtLeast(1)}) {
              var raw = String(current.nodeValue || '');
              var compact = raw.replace(/\s+/g, ' ').trim();
              var parent = current.parentElement;
              if (!parent) continue;
              var id = 'aurora_translate_' + seed + '_' + results.length;
              var wrapper = document.createElement('span');
              wrapper.setAttribute('data-aurora-translation-unit', '1');
              wrapper.setAttribute('data-aurora-translation-id', id);
              wrapper.setAttribute('data-aurora-source', raw);
              var source = document.createElement('span');
              source.className = '__aurora_translation_source';
              source.textContent = raw;
              var translation = document.createElement('span');
              translation.className = '__aurora_translation_text ' + (isBlock(parent) ? '__aurora_block' : '__aurora_inline');
              translation.textContent = '翻译中...';
              wrapper.appendChild(source);
              wrapper.appendChild(translation);
              current.parentNode.replaceChild(wrapper, current);
              results.push({ id: id, text: compact });
            }
            return JSON.stringify(results);
          } catch (error) {
            return '[]';
          }
        })();
    """.trimIndent()
}
```

- [ ] **Step 2: Replace `buildApplyScript()` with span selector**

Replace the entire `buildApplyScript()` method (lines 144-173) with:

```kotlin
fun buildApplyScript(items: List<TranslatedPageText>): String {
    val payload = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("text", item.translatedText)
            })
        }
    }.toString()
    return """
        (function() {
          try {
            var items = JSON.parse(${JSONObject.quote(payload)});
            items.forEach(function(item) {
              var selector = '[data-aurora-translation-id="' + String(item.id).replace(/"/g, '\\"') + '"]';
              var node = document.querySelector(selector);
              if (!node) return;
              var tgt = node.querySelector('.__aurora_translation_text');
              if (tgt) { tgt.textContent = String(item.text || ''); }
            });
          } catch (error) {}
        })();
    """.trimIndent()
}
```

- [ ] **Step 3: Replace `buildClearScript()` with span selector**

Replace the entire `buildClearScript()` method (lines 175-194) with:

```kotlin
fun buildClearScript(): String {
    return """
        (function() {
          try {
            document.querySelectorAll('[data-aurora-translation-unit="1"]').forEach(function(node) {
              var original = node.getAttribute('data-aurora-source');
              if (!original) {
                var sourceNode = node.querySelector('.__aurora_translation_source');
                original = sourceNode ? sourceNode.textContent : '';
              }
              node.replaceWith(document.createTextNode(original || ''));
            });
            var styleNode = document.getElementById('__aurora_translation_style__');
            if (styleNode) { styleNode.remove(); }
          } catch (error) {}
        })();
    """.trimIndent()
}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Add session translation cache

**Files:**
- Modify: `app/src/main/java/com/fenglan/aurorabrowser/browser/PageTranslator.kt:29-36` (add cache field)

- [ ] **Step 1: Add session cache field to `PageTranslator` object**

Add after line 36 (`private const val BATCH_CHAR_LIMIT = 1400`):

```kotlin
private val sessionCache = HashMap<String, String>()

fun clearSessionCache() {
    sessionCache.clear()
}
```

- [ ] **Step 2: Add cache lookup in `translate()` method**

Replace the `translate()` method (lines 212-250) with a version that checks cache before API calls and stores results after:

```kotlin
fun translate(
    engine: TranslationEngine,
    segments: List<PageTextSegment>,
    preferences: BrowserPreferences,
): TranslationRunResult {
    if (segments.isEmpty()) {
        return TranslationRunResult(emptyList(), engine)
    }
    val uncachedSegments = segments.filter { sessionCache[it.text] == null }
    val translations = mutableMapOf<String, String>()
    segments.forEach { seg ->
        sessionCache[seg.text]?.let { translations[seg.id] = it }
    }
    if (uncachedSegments.isNotEmpty()) {
        val batches = buildBatches(uncachedSegments)
        var engineUsed = engine
        var usedFallback = false
        batches.forEach { batch ->
            val translatedBatch = try {
                translateBatchWithFallback(engine, batch, preferences)
            } catch (error: Throwable) {
                batch.map { segment -> translateSingleWithFallback(segment.text, preferences) }
            }
            batch.zip(translatedBatch).forEach { (segment, translatedText) ->
                if (translatedText.isNotBlank()) {
                    translations[segment.id] = translatedText
                    sessionCache[segment.text] = translatedText
                }
            }
        }
    }
    val translatedCount = translations.values.count { it.isNotBlank() }
    return TranslationRunResult(
        items = segments.map { segment ->
            TranslatedPageText(
                id = segment.id,
                translatedText = translations[segment.id].orEmpty(),
            )
        },
        engineUsed = engine,
        usedFallback = false,
        translatedCount = translatedCount,
        totalCount = segments.size,
    )
}
```

- [ ] **Step 3: Add `translatedCount` and `totalCount` to `TranslationRunResult`**

Modify the data class (lines 23-27):

```kotlin
data class TranslationRunResult(
    val items: List<TranslatedPageText>,
    val engineUsed: TranslationEngine,
    val usedFallback: Boolean = false,
    val translatedCount: Int = items.size,
    val totalCount: Int = items.size,
)
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 4: Implement multi-engine fallback chain

**Files:**
- Modify: `app/src/main/java/com/fenglan/aurorabrowser/browser/PageTranslator.kt:252-291` (translation methods)

- [ ] **Step 1: Add `translateBatchWithFallback()` method**

Add after the existing `translateBatch()` method. This replaces the old batch logic:

```kotlin
private fun translateBatchWithFallback(
    engine: TranslationEngine,
    batch: List<PageTextSegment>,
    preferences: BrowserPreferences,
): List<String> {
    val engines = mutableListOf(engine)
    if (engine != TranslationEngine.GOOGLE) {
        engines.add(TranslationEngine.GOOGLE)
    }
    var lastError: Throwable? = null
    for (eng in engines) {
        try {
            val result = translateBatch(eng, batch, preferences)
            if (result.any { it.isNotBlank() }) return result
        } catch (e: Throwable) {
            lastError = e
        }
    }
    throw lastError ?: IllegalStateException("all translation engines failed")
}
```

- [ ] **Step 2: Add `translateSingleWithFallback()` method**

Per-segment fallback with retry:

```kotlin
private fun translateSingleWithFallback(
    text: String,
    preferences: BrowserPreferences,
): String {
    val engines = listOf(TranslationEngine.GOOGLE, TranslationEngine.MICROSOFT)
    for (eng in engines) {
        try {
            val result = when (eng) {
                TranslationEngine.GOOGLE -> translateWithGoogle(text)
                TranslationEngine.MICROSOFT -> translateWithMicrosoft(text)
                TranslationEngine.BAIDU -> translateWithBaidu(text, preferences.getBaiduAppId(), preferences.getBaiduSecret())
                TranslationEngine.YOUDAO -> translateWithYoudao(text, preferences.getYoudaoAppKey(), preferences.getYoudaoAppSecret())
            }
            if (result.isNotBlank()) return result
        } catch (_: Throwable) { }
    }
    return ""
}
```

- [ ] **Step 3: Add retry logic to `translateWithGoogle()`**

Replace the existing `translateWithGoogle()` (lines 341-357) to add one retry:

```kotlin
private fun translateWithGoogle(text: String): String {
    var lastError: Throwable? = null
    repeat(2) { attempt ->
        try {
            val payload = httpGet(
                url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=${encode(text)}",
                accept = "application/json,text/plain,*/*",
            )
            val root = JSONArray(payload)
            val translatedChunks = root.optJSONArray(0) ?: return@repeat
            return buildString {
                for (index in 0 until translatedChunks.length()) {
                    append(translatedChunks.optJSONArray(index)?.optString(0).orEmpty())
                }
            }
        } catch (e: Throwable) {
            lastError = e
            if (attempt == 0) Thread.sleep(1000)
        }
    }
    return try {
        translateWithMyMemory(text)
    } catch (_: Throwable) {
        ""
    }
}
```

- [ ] **Step 4: Add retry logic to `translateWithMicrosoft()`**

Replace the existing `translateWithMicrosoft()` (lines 359-384):

```kotlin
private fun translateWithMicrosoft(text: String): String {
    var lastError: Throwable? = null
    repeat(2) { attempt ->
        try {
            val token = fetchMicrosoftToken()
            val endpoint = "https://cn.bing.com/ttranslatev3?isVertical=1&IG=${token.ig}&IID=translator.5028.1"
            val response = httpPostForm(
                url = endpoint,
                form = linkedMapOf(
                    "fromLang" to "auto-detect",
                    "to" to "zh-Hans",
                    "text" to text,
                    "token" to token.token,
                    "key" to token.key,
                ),
                referer = "https://cn.bing.com/translator",
            )
            if (response.contains("\"ShowCaptcha\":true", ignoreCase = true)) {
                throw IllegalStateException("microsoft translator captcha")
            }
            val root = JSONArray(response)
            return buildString {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val translations = item.optJSONArray("translations") ?: continue
                    append(translations.optJSONObject(0)?.optString("text").orEmpty())
                }
            }
        } catch (e: Throwable) {
            lastError = e
            if (attempt == 0) Thread.sleep(1000)
        }
    }
    throw lastError ?: IllegalStateException("microsoft translation failed")
}
```

- [ ] **Step 5: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 5: Update `translateCurrentPage()` for batch-by-batch application and progress

**Files:**
- Modify: `app/src/main/java/com/fenglan/aurorabrowser/MainActivity.kt:1541-1586`

- [ ] **Step 1: Rewrite `translateCurrentPage()` with batch-by-batch application**

Replace the entire method (lines 1541-1586) with:

```kotlin
private fun translateCurrentPage(force: Boolean = false) {
    val state = viewModel.uiState.value
    val pageUrl = state.currentUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL } ?: return
    if (!force && translatedPageUrl == pageUrl) {
        return
    }
    if (!ensureTranslationEngineReady(state.settings.translationEngine)) {
        translationEnabled = false
        return
    }
    translationJob?.cancel()
    translationJob = lifecycleScope.launch {
        try {
            val rawResult = evaluateJavascriptAsync(PageTranslator.buildCollectScript())
            val segments = PageTranslator.parseCollectedSegments(decodeJavascriptStringResult(rawResult))
            if (segments.isEmpty()) {
                translatedPageUrl = null
                if (force) {
                    Snackbar.make(binding.root, "当前页面没有可翻译文本", Snackbar.LENGTH_SHORT).show()
                }
                return@launch
            }
            val engine = viewModel.uiState.value.settings.translationEngine
            val batches = segments.chunked(6)
            val allTranslated = mutableListOf<TranslatedPageText>()
            var appliedCount = 0
            for ((batchIndex, batch) in batches.withIndex()) {
                if (pageUrl != viewModel.uiState.value.currentUrl) return@launch
                val batchResult = withContext(Dispatchers.IO) {
                    PageTranslator.translate(engine, batch, preferences)
                }
                allTranslated.addAll(batchResult.items)
                val newItems = batchResult.items.filter { it.translatedText.isNotBlank() }
                if (newItems.isNotEmpty()) {
                    currentWebView().evaluateJavascript(PageTranslator.buildApplyScript(newItems), null)
                }
                appliedCount += newItems.size
            }
            if (pageUrl != viewModel.uiState.value.currentUrl) return@launch
            translatedPageUrl = pageUrl
            translationEnabled = true
            val total = segments.size
            val message = if (appliedCount >= total) {
                "${engine.label}双语翻译已应用（$appliedCount 段）"
            } else {
                "双语翻译已应用（$appliedCount/$total 段）"
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            translatedPageUrl = null
            Snackbar.make(binding.root, "网页翻译失败", Snackbar.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 2: Add import for `TranslatedPageText`**

Verify the import exists at the top of `MainActivity.kt`:

```kotlin
import com.fenglan.aurorabrowser.browser.PageTranslator
```

This import is already present (line 95). No change needed.

- [ ] **Step 3: Update `clearPageTranslation()` to clear session cache**

Modify `clearPageTranslation()` (line 1588):

```kotlin
private fun clearPageTranslation() {
    translatedPageUrl = null
    currentWebView().evaluateJavascript(PageTranslator.buildClearScript(), null)
    PageTranslator.clearSessionCache()
}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 6: Run full verification

- [ ] **Step 1: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new errors

- [ ] **Step 3: Clean build**

Run: `./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Update docs**

Update `AGENTS.md` recent change notes and `README.md` features as needed.
