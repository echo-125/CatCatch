/**
 * CatCatch M3U8 嗅探脚本
 * 支持多种嗅探模式：AUTO / NETWORK / DOM / DEEP_SCAN
 */
(function() {
    'use strict';

    // ==================== 配置 ====================
    const CONFIG = {
        AD_KEYWORDS: ['ad', 'ads', 'adv', 'advertisement', 'silent-basis'],
        LOG_PREFIX: '[CatCatch]',
        MAX_LOG_LINES: 100,
        MAX_VALUE_LENGTH: 10000,
        MAX_OBJECT_DEPTH: 2,
        MAX_PROPERTIES: 100,
        SCAN_DELAY_1: 2000,
        SCAN_DELAY_2: 5000
    };

    // ==================== 状态 ====================
    const state = {
        capturedUrls: new Set(),
        pendingUrls: new Set(),
        requestHeaders: new Map(),
        logs: [],
        initialized: false,
        networkInitialized: false,
        domInitialized: false,
        disguiseInitialized: false
    };

    // ==================== 工具函数 ====================
    function log(message) {
        const fullMessage = `${CONFIG.LOG_PREFIX} ${message}`;
        console.log(fullMessage);
        state.logs.push(`[${new Date().toLocaleTimeString()}] ${message}`);
        if (state.logs.length > CONFIG.MAX_LOG_LINES) {
            state.logs.shift();
        }
        try {
            window.Android.onLog(message);
        } catch (e) {}
    }

    function isAdUrl(url) {
        const lowerUrl = url.toLowerCase();
        return CONFIG.AD_KEYWORDS.some(kw => lowerUrl.includes(kw));
    }

    function extractHeaders(inputHeaders) {
        if (!inputHeaders) return {};
        if (inputHeaders instanceof Headers) {
            return Object.fromEntries(inputHeaders.entries());
        }
        if (Array.isArray(inputHeaders)) {
            return Object.fromEntries(inputHeaders);
        }
        if (typeof inputHeaders === 'object') {
            return { ...inputHeaders };
        }
        return {};
    }

    function buildHeaders(url, headers = {}) {
        const normalized = {};
        for (const [key, value] of Object.entries(headers || {})) {
            if (key && value) {
                normalized[String(key).toLowerCase()] = String(value);
            }
        }
        if (!normalized.origin) {
            normalized.origin = window.location.origin;
        }
        if (!normalized.referer) {
            normalized.referer = window.location.href;
        }
        state.requestHeaders.set(url, normalized);
        return normalized;
    }

    function notifyM3u8Found(url, headers) {
        if (!url || state.capturedUrls.has(url) || state.pendingUrls.has(url)) {
            return;
        }
        state.capturedUrls.add(url);
        state.pendingUrls.add(url);

        const headersJson = JSON.stringify(headers || {});
        log(`发现 M3U8: ${url.substring(0, 80)}...`);

        try {
            window.Android.onM3u8Found(url, headersJson);
        } catch (e) {
            log(`通知失败: ${e.message}`);
        } finally {
            state.pendingUrls.delete(url);
        }
    }

    function extractM3u8Urls(text, foundUrls) {
        if (!text || text.length > CONFIG.MAX_VALUE_LENGTH) return;
        const patterns = [
            /https?:\/\/[^\s"'`<>]+\.m3u8[^\s"'`<>]*/gi,
            /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/gi
        ];
        patterns.forEach(pattern => {
            const matches = text.match(pattern);
            if (matches) {
                matches.forEach(url => {
                    url = url.replace(/^["']|["']$/g, '').replace(/[;,\)\]}\>'"`]+$/, '');
                    if (url.includes('.m3u8')) foundUrls.add(url);
                });
            }
        });
    }

    // ==================== 网络请求拦截 ====================
    function interceptNetworkRequests() {
        if (state.networkInitialized) return;
        state.networkInitialized = true;

        // 拦截 XMLHttpRequest
        const OriginalXHR = window.XMLHttpRequest;
        const originalXHROpen = OriginalXHR.prototype.open;
        const originalXHRSetHeader = OriginalXHR.prototype.setRequestHeader;

        OriginalXHR.prototype.setRequestHeader = function(key, value) {
            if (!this._catcatchHeaders) this._catcatchHeaders = {};
            this._catcatchHeaders[key] = value;
            return originalXHRSetHeader.apply(this, arguments);
        };

        OriginalXHR.prototype.open = function(method, url) {
            this._catcatchUrl = url;
            this.addEventListener('load', function() {
                try {
                    const urlStr = String(url);
                    if (urlStr.includes('.m3u8')) {
                        const headers = buildHeaders(urlStr, this._catcatchHeaders || {});
                        if (typeof this.responseText === 'string' && this.responseText.includes('#EXTM3U')) {
                            notifyM3u8Found(urlStr, headers);
                        }
                    }
                } catch (e) {}
            });
            return originalXHROpen.apply(this, arguments);
        };

        // 拦截 fetch
        const originalFetch = window.fetch;
        window.fetch = function(input, init) {
            const url = (input && input.url) || String(input);
            const requestHeaders = extractHeaders(init && init.headers);

            return originalFetch.apply(this, arguments).then(response => {
                try {
                    const headers = buildHeaders(url, requestHeaders);

                    // 快速路径：URL 包含 .m3u8
                    if (url.includes('.m3u8')) {
                        response.clone().text().then(text => {
                            if (text.includes('#EXTM3U')) {
                                notifyM3u8Found(url, headers);
                            }
                        }).catch(() => {});
                    }
                    // 慢速路径：检查所有响应内容（捕获动态 URL）
                    else {
                        const contentType = response.headers.get('content-type') || '';
                        if (contentType.includes('mpegurl') ||
                            contentType.includes('mp2t') ||
                            contentType.includes('x-mpegURL')) {
                            notifyM3u8Found(url, headers);
                        }
                    }
                } catch (e) {}
                return response;
            });
        };

        // 监听 script src 变化
        const originalCreateElement = document.createElement;
        document.createElement = function(tagName) {
            const element = originalCreateElement.call(document, tagName);
            if (tagName.toLowerCase() === 'script') {
                const originalSetAttribute = element.setAttribute;
                element.setAttribute = function(name, value) {
                    if (name === 'src' && String(value).includes('.m3u8')) {
                        notifyM3u8Found(String(value), buildHeaders(String(value)));
                    }
                    return originalSetAttribute.call(this, name, value);
                };
            }
            return element;
        };

        log('网络请求拦截已启用');
    }

    // ==================== DOM 监听 ====================
    function observeDOM() {
        if (state.domInitialized) return;
        state.domInitialized = true;

        const observer = new MutationObserver(mutations => {
            mutations.forEach(mutation => {
                mutation.addedNodes.forEach(node => {
                    if (node.nodeType !== Node.ELEMENT_NODE) return;

                    // 监听 <video> 元素
                    if (node.tagName === 'VIDEO') {
                        const src = node.src || node.currentSrc;
                        if (src && src.includes('.m3u8')) {
                            notifyM3u8Found(src, buildHeaders(src));
                        }
                    }

                    // 监听子元素中的 <video>
                    if (node.querySelectorAll) {
                        node.querySelectorAll('video').forEach(video => {
                            const src = video.src || video.currentSrc;
                            if (src && src.includes('.m3u8')) {
                                notifyM3u8Found(src, buildHeaders(src));
                            }
                        });
                    }
                });
            });
        });

        observer.observe(document.documentElement, { childList: true, subtree: true });
        log('DOM 监听已启用');
    }

    // ==================== 深度扫描（原有） ====================
    function deepScanBasic() {
        log('开始基础深度扫描...');

        const foundUrls = new Set();
        const patterns = [
            /https?:\/\/[^\s"'`<>]+\.m3u8[^\s"'`<>]*/gi,
            /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/gi,
            /url\s*[:=]\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/gi
        ];

        // 1. 扫描 HTML
        try {
            const html = document.documentElement.outerHTML;
            patterns.forEach(pattern => {
                const matches = html.match(pattern);
                if (matches) {
                    matches.forEach(url => {
                        url = url.replace(/^["']|["']$/g, '').replace(/[;,\)\]}\>'"`]+$/, '');
                        if (url.includes('.m3u8')) foundUrls.add(url);
                    });
                }
            });
        } catch (e) {
            log(`HTML 扫描失败: ${e.message}`);
        }

        // 2. 扫描 <script> 标签
        try {
            document.querySelectorAll('script').forEach(script => {
                const content = script.textContent || script.innerHTML;
                if (content) {
                    patterns.forEach(pattern => {
                        const matches = content.match(pattern);
                        if (matches) {
                            matches.forEach(url => {
                                url = url.replace(/^["']|["']$/g, '').replace(/[;,\)\]}\>'"`]+$/, '');
                                if (url.includes('.m3u8')) foundUrls.add(url);
                            });
                        }
                    });
                }
            });
        } catch (e) {
            log(`Script 扫描失败: ${e.message}`);
        }

        // 3. 扫描 <video> 元素
        try {
            document.querySelectorAll('video').forEach(video => {
                const src = video.src || video.currentSrc;
                if (src) foundUrls.add(src);
            });
        } catch (e) {
            log(`Video 扫描失败: ${e.message}`);
        }

        log(`基础扫描完成，发现 ${foundUrls.size} 个链接`);

        // 处理发现的 URL
        foundUrls.forEach(url => {
            notifyM3u8Found(url, buildHeaders(url));
        });
    }

    // ==================== 深度扫描（增强） ====================
    function scanJavaScriptVariables() {
        log('扫描 JavaScript 变量...');
        const foundUrls = new Set();

        const SKIP_KEYS = new Set([
            'document', 'navigator', 'location', 'performance', 'screen',
            'history', 'localStorage', 'sessionStorage', 'caches',
            'indexedDB', 'webkitIndexedDB', 'crypto', 'speechSynthesis',
            'chrome', 'webkitStorageInfo', 'webkitIndexedDB'
        ]);

        try {
            const keys = Object.getOwnPropertyNames(window);
            let scannedCount = 0;

            for (const key of keys) {
                if (SKIP_KEYS.has(key)) continue;
                if (scannedCount > 200) break; // 限制扫描数量
                scannedCount++;

                try {
                    const value = window[key];
                    if (value === null || value === undefined) continue;
                    if (typeof value === 'function') continue;

                    if (typeof value === 'string') {
                        extractM3u8Urls(value, foundUrls);
                    } else if (typeof value === 'object') {
                        scanObject(value, foundUrls, 1);
                    }
                } catch (e) {}
            }
        } catch (e) {
            log(`JS 变量扫描失败: ${e.message}`);
        }

        log(`JS 变量扫描完成，发现 ${foundUrls.size} 个链接`);
        return foundUrls;
    }

    function scanObject(obj, foundUrls, maxDepth) {
        if (maxDepth <= 0 || !obj) return;

        try {
            const keys = Object.getOwnPropertyNames(obj).slice(0, CONFIG.MAX_PROPERTIES);
            for (const key of keys) {
                try {
                    const value = obj[key];
                    if (value === null || value === undefined) continue;
                    if (typeof value === 'function') continue;

                    if (typeof value === 'string') {
                        extractM3u8Urls(value, foundUrls);
                    } else if (typeof value === 'object') {
                        scanObject(value, foundUrls, maxDepth - 1);
                    }
                } catch (e) {}
            }
        } catch (e) {}
    }

    function scanJsonConfigs() {
        log('扫描 JSON 配置...');
        const foundUrls = new Set();

        // 1. 扫描 <script type="application/json"> 和 <script type="text/template">
        try {
            document.querySelectorAll('script[type="application/json"], script[type="text/template"]')
                .forEach(script => {
                    try {
                        const json = JSON.parse(script.textContent);
                        scanObjectForUrls(json, foundUrls, 3);
                    } catch (e) {}
                });
        } catch (e) {}

        // 2. 扫描常见的全局配置变量
        const configVarNames = [
            'playerConfig', 'videoConfig', 'playInfo', 'videoInfo',
            'pageData', '__INITIAL_DATA__', '__NEXT_DATA__',
            '__data__', 'player', '__playinfo__',
            '__playerConfig__', '__flashvars', '__CONFIG__'
        ];

        for (const varName of configVarNames) {
            try {
                const obj = window[varName];
                if (obj && typeof obj === 'object') {
                    scanObjectForUrls(obj, foundUrls, 5);
                }
            } catch (e) {}
        }

        log(`JSON 配置扫描完成，发现 ${foundUrls.size} 个链接`);
        return foundUrls;
    }

    function scanObjectForUrls(obj, foundUrls, maxDepth) {
        if (maxDepth <= 0 || !obj) return;

        try {
            if (typeof obj === 'string') {
                extractM3u8Urls(obj, foundUrls);
                return;
            }

            if (Array.isArray(obj)) {
                obj.forEach(item => scanObjectForUrls(item, foundUrls, maxDepth - 1));
                return;
            }

            if (typeof obj === 'object') {
                const keys = Object.keys(obj).slice(0, CONFIG.MAX_PROPERTIES);
                for (const key of keys) {
                    try {
                        const value = obj[key];
                        if (typeof value === 'string') {
                            extractM3u8Urls(value, foundUrls);
                        } else if (typeof value === 'object' && value !== null) {
                            scanObjectForUrls(value, foundUrls, maxDepth - 1);
                        }
                    } catch (e) {}
                }
            }
        } catch (e) {}
    }

    // ==================== 播放器识别 ====================
    function scanPlayerInstances() {
        log('扫描播放器实例...');
        const foundUrls = new Set();

        // 1. 检测 hls.js
        try {
            if (window.Hls) {
                document.querySelectorAll('video').forEach(video => {
                    if (video.hls) {
                        // hls.js 实例挂载在 video 元素上
                        const hls = video.hls;
                        if (hls.url) foundUrls.add(hls.url);
                        if (hls.levels) {
                            hls.levels.forEach(level => {
                                if (level.url) foundUrls.add(level.url);
                            });
                        }
                    }
                });

                // 检查全局 Hls 实例
                if (window.Hls.instance) {
                    const hls = window.Hls.instance;
                    if (hls.url) foundUrls.add(hls.url);
                }
            }
        } catch (e) {}

        // 2. 检测 DPlayer
        try {
            if (window.dp) {
                const dp = window.dp;
                if (dp.options && dp.options.video) {
                    const video = dp.options.video;
                    if (video.url) foundUrls.add(video.url);
                    if (video.urls) {
                        Object.values(video.urls).forEach(url => foundUrls.add(url));
                    }
                }
            }
        } catch (e) {}

        // 3. 检测 ArtPlayer
        try {
            if (window.art) {
                const art = window.art;
                if (art.option && art.option.url) {
                    foundUrls.add(art.option.url);
                }
            }
        } catch (e) {}

        // 4. 检测 video.js
        try {
            if (window.videojs) {
                const players = videojs.getPlayers();
                Object.values(players).forEach(player => {
                    if (player.currentSrc) foundUrls.add(player.currentSrc());
                    if (player.options_ && player.options_.sources) {
                        player.options_.sources.forEach(source => {
                            if (source.src) foundUrls.add(source.src);
                        });
                    }
                });
            }
        } catch (e) {}

        // 5. 检测 JW Player
        try {
            if (window.jwplayer) {
                const player = jwplayer();
                if (player.getPlaylistItem) {
                    const item = player.getPlaylistItem();
                    if (item && item.file) foundUrls.add(item.file);
                    if (item && item.sources) {
                        item.sources.forEach(source => {
                            if (source.file) foundUrls.add(source.file);
                        });
                    }
                }
            }
        } catch (e) {}

        // 6. 检测 ckplayer
        try {
            if (window.ckplayer) {
                // ckplayer 通常通过 ckplayer() 获取实例
                // 尝试从全局变量中查找配置
                const ckVars = ['ckplayer_config', 'ckplayer_video', 'videoObject'];
                for (const varName of ckVars) {
                    const obj = window[varName];
                    if (obj && typeof obj === 'object') {
                        if (obj.video) foundUrls.add(obj.video);
                        if (obj.videoUrl) foundUrls.add(obj.videoUrl);
                    }
                }
            }
        } catch (e) {}

        // 7. 通用扫描：查找常见的播放器实例变量
        try {
            const playerKeys = ['player', 'dp', 'art', 'ap', 'video', 'hlsPlayer', 'flvPlayer', 'mpegtsPlayer'];
            for (const key of playerKeys) {
                try {
                    const instance = window[key];
                    if (!instance || typeof instance !== 'object') continue;

                    // 尝试常见属性
                    const possibleUrlFields = ['url', 'src', 'source', 'videoUrl', 'currentSrc'];
                    for (const field of possibleUrlFields) {
                        const val = instance[field];
                        if (typeof val === 'string' && val.includes('.m3u8')) {
                            foundUrls.add(val);
                        }
                    }

                    // 尝试获取 options/config
                    const config = instance.options || instance.config || instance._opt || {};
                    scanObjectForUrls(config, foundUrls, 2);
                } catch (e) {}
            }
        } catch (e) {}

        log(`播放器扫描完成，发现 ${foundUrls.size} 个链接`);
        return foundUrls;
    }

    // ==================== 增强版深度扫描 ====================
    function deepScanEnhanced() {
        log('开始增强深度扫描...');

        const allFoundUrls = new Set();

        // 1. 基础扫描（HTML/script/video）
        deepScanBasic();

        // 2. JavaScript 变量扫描
        const jsUrls = scanJavaScriptVariables();
        jsUrls.forEach(url => allFoundUrls.add(url));

        // 3. JSON 配置扫描
        const jsonUrls = scanJsonConfigs();
        jsonUrls.forEach(url => allFoundUrls.add(url));

        // 4. 播放器实例扫描
        const playerUrls = scanPlayerInstances();
        playerUrls.forEach(url => allFoundUrls.add(url));

        // 5. 处理所有发现的 URL
        allFoundUrls.forEach(url => {
            notifyM3u8Found(url, buildHeaders(url));
        });

        log(`增强扫描完成，共发现 ${allFoundUrls.size} 个链接`);
    }

    // ==================== 获取页面标题 ====================
    function getPageTitle() {
        const selectors = ['h1', 'title', '[class*="title"]', '[class*="video-title"]'];
        for (const selector of selectors) {
            const el = document.querySelector(selector);
            if (el && el.textContent && el.textContent.trim()) {
                const title = el.textContent.trim().replace(/[<>:"/\\|?*]/g, '_').substring(0, 50);
                try {
                    window.Android.onTitle(title);
                } catch (e) {}
                return title;
            }
        }
        const title = document.title.replace(/[<>:"/\\|?*]/g, '_').substring(0, 50);
        try {
            window.Android.onTitle(title);
        } catch (e) {}
        return title;
    }

    // ==================== 清空状态 ====================
    function clearState() {
        state.capturedUrls.clear();
        state.pendingUrls.clear();
        state.requestHeaders.clear();
        state.logs = [];
        // 重置所有初始化标志，允许重新初始化
        state.initialized = false;
        state.networkInitialized = false;
        state.domInitialized = false;
        state.disguiseInitialized = false;
        log('已清空状态');
    }

    // ==================== 模式化初始化 ====================

    // AUTO 模式：同时启用网络拦截 + DOM 监听
    function init() {
        if (state.initialized) return;
        state.initialized = true;

        log('嗅探脚本已加载 (AUTO 模式)');
        log(`当前页面: ${window.location.href}`);

        // 启用网络拦截
        interceptNetworkRequests();

        // 启用 DOM 监听
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', observeDOM);
        } else {
            observeDOM();
        }

        // 获取页面标题
        setTimeout(getPageTitle, 500);

        // 延迟深度扫描
        setTimeout(deepScanEnhanced, CONFIG.SCAN_DELAY_1);
        setTimeout(deepScanEnhanced, CONFIG.SCAN_DELAY_2);
    }

    // NETWORK 模式：仅启用网络拦截
    function initNetworkOnly() {
        if (state.networkInitialized) return;

        log('嗅探脚本已加载 (NETWORK 模式)');
        log(`当前页面: ${window.location.href}`);

        // 仅启用网络拦截
        interceptNetworkRequests();

        // 获取页面标题
        setTimeout(getPageTitle, 500);
    }

    // DOM 模式：仅启用 DOM 监听
    function initDomOnly() {
        if (state.domInitialized) return;

        log('嗅探脚本已加载 (DOM 模式)');
        log(`当前页面: ${window.location.href}`);

        // 仅启用 DOM 监听
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', observeDOM);
        } else {
            observeDOM();
        }

        // 获取页面标题
        setTimeout(getPageTitle, 500);
    }

    // ==================== 伪装模式 ====================

    /** 判断 URL 是否可能是伪装的 M3U8（如 index.jpg 实际是 M3U8） */
    function isDisguisedM3u8Url(url) {
        if (!url) return false;
        const lower = url.toLowerCase();
        const result = lower.includes('.m3u8') ||
            lower.includes('index.jpg') ||
            lower.includes('index.ts');
        log('[伪装] URL检查: ' + (result ? '✓匹配' : '✗跳过') + ' ' + url.substring(0, 100));
        return result;
    }

    /** 验证响应内容是否是 M3U8 格式 */
    function verifyM3U8Content(text) {
        if (!text) { log('[伪装] 内容验证: ✗空内容'); return false; }
        const has = text.includes('#EXTM3U');
        log('[伪装] 内容验证: ' + (has ? '✓是M3U8' : '✗非M3U8') + ' (长度:' + text.length + ', 前80字:' + text.substring(0, 80).replace(/\n/g, '\\n') + ')');
        return has;
    }

    /** 点击播放按钮 */
    function clickPlayButton(callback) {
        log('[伪装] ===== 查找播放按钮 =====');
        log('[伪装] 页面: ' + window.location.href.substring(0, 80));
        log('[伪装] readyState: ' + document.readyState);

        // 查找 SVG 播放图标
        const playPaths = document.querySelectorAll('svg path[d*="M8 5v14l11-7z"]');
        log('[伪装] SVG播放路径: ' + playPaths.length + '个');
        for (const path of playPaths) {
            const svg = path.closest('svg');
            if (!svg) continue;
            for (const parent of [svg.parentElement, svg.parentElement?.parentElement]) {
                if (!parent) continue;
                const anchor = parent.closest('a');
                if (anchor) { log('[伪装] 跳过SVG: 在<a>内'); continue; }
                const style = window.getComputedStyle(parent);
                if (style.borderRadius === '50%' || style.cursor === 'pointer' || parent.tagName === 'BUTTON') {
                    log('[伪装] ✓ 点击播放按钮 (SVG): ' + parent.tagName);
                    parent.click();
                    callback && callback(true);
                    return true;
                }
            }
        }

        // 查找圆形播放按钮
        const divs = document.querySelectorAll('div');
        for (const div of divs) {
            const style = window.getComputedStyle(div);
            if (style.borderRadius === '50%' && style.display === 'flex' &&
                div.querySelector('svg path[d*="M8 5v14l11-7z"]')) {
                const anchor = div.closest('a');
                if (anchor) continue;
                log('[伪装] ✓ 点击播放按钮 (圆形div)');
                div.click();
                callback && callback(true);
                return true;
            }
        }

        // 通用按钮
        const btns = document.querySelectorAll('[role="button"], button, .play-btn, .vjs-big-play-button');
        log('[伪装] 候选按钮: ' + btns.length + '个');
        for (const btn of btns) {
            const text = btn.textContent || '';
            if (text.includes('播放') || text.includes('Play') || text.includes('play')) {
                const anchor = btn.closest('a');
                if (anchor) continue;
                log('[伪装] ✓ 点击播放按钮 (文本): "' + text.trim().substring(0, 20) + '"');
                btn.click();
                callback && callback(true);
                return true;
            }
        }

        // 尝试直接播放 video
        const videos = document.querySelectorAll('video');
        log('[伪装] video元素: ' + videos.length + '个');
        for (const video of videos) {
            log('[伪装] video: src=' + (video.src || '空').substring(0, 60) + ' paused=' + video.paused);
            if (video.paused) {
                try { video.play(); log('[伪装] ✓ 调用video.play()'); callback && callback(true); return true; }
                catch (e) { log('[伪装] video.play()失败: ' + e.message); }
            }
        }

        log('[伪装] ✗ 未找到播放按钮');
        callback && callback(false);
        return false;
    }

    /** 等待广告并尝试跳过 */
    function clickSkipAdButton(callback) {
        log('[伪装] 等待广告加载 (4秒)...');
        setTimeout(function() {
            log('[伪装] ===== 查找跳过广告按钮 =====');

            // 列出 iframe
            const iframes = document.querySelectorAll('iframe');
            log('[伪装] iframe: ' + iframes.length + '个');
            iframes.forEach(function(f, i) { log('[伪装]   iframe[' + i + ']: ' + (f.src || '空').substring(0, 80)); });

            // 广告容器
            const adContainers = document.querySelectorAll('.rmp-ad-container, [class*="ad-container"], [class*="ad-banner"]');
            log('[伪装] 广告容器: ' + adContainers.length + '个');

            // 查找跳过按钮
            const skipSelectors = [
                '.rmp-ad-container-skip',
                '[class*="skip"]',
                '[class*="Skip"]',
                '[class*="close-ad"]',
                '[class*="ad-skip"]'
            ];

            for (const selector of skipSelectors) {
                const btns = document.querySelectorAll(selector);
                if (btns.length > 0) log('[伪装] 候选跳过按钮 (' + selector + '): ' + btns.length + '个');
                for (const btn of btns) {
                    const visible = btn.offsetParent !== null || btn.offsetWidth > 0;
                    log('[伪装]   ' + btn.tagName + ' "' + (btn.textContent || '').trim().substring(0, 20) + '" visible=' + visible);
                    if (visible) {
                        log('[伪装] ✓ 点击跳过按钮: ' + selector);
                        btn.click();
                        setTimeout(removeAdContainers, 500);
                        callback && callback(true);
                        return;
                    }
                }
            }

            // 文本匹配
            const patterns = ['跳过', 'Skip', 'skip', '关闭广告'];
            for (const pattern of patterns) {
                try {
                    const result = document.evaluate(
                        '//*[contains(text(), "' + pattern + '")]',
                        document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null
                    );
                    if (result.snapshotLength > 0) log('[伪装] 文本"' + pattern + '": ' + result.snapshotLength + '个');
                    for (let i = 0; i < Math.min(result.snapshotLength, 5); i++) {
                        const node = result.snapshotItem(i);
                        const visible = node.offsetParent !== null || node.offsetWidth > 0;
                        log('[伪装]   [' + i + '] ' + node.tagName + ' "' + (node.textContent || '').trim().substring(0, 30) + '" visible=' + visible);
                        if (visible) {
                            log('[伪装] ✓ 点击跳过按钮 (文本)');
                            node.click();
                            setTimeout(removeAdContainers, 500);
                            callback && callback(true);
                            return;
                        }
                    }
                } catch (e) { log('[伪装] XPath错误: ' + e.message); }
            }

            log('[伪装] ✗ 未找到跳过广告按钮');
            callback && callback(false);
        }, 4000);
    }

    /** 移除广告容器 */
    function removeAdContainers() {
        const selectors = [
            '.rmp-ad-container',
            '[class*="ad-container"]',
            '[class*="ad-banner"]',
            '[class*="ad-overlay"]',
            '.rmp-ad-vast-video-player',
            'iframe[src*="silent-basis"]'
        ];
        let removed = 0;
        selectors.forEach(function(sel) {
            document.querySelectorAll(sel).forEach(function(el) {
                el.remove();
                removed++;
            });
        });
        // 移除广告视频
        document.querySelectorAll('video').forEach(function(video) {
            const cls = video.className || '';
            if (cls.includes('rmp-ad') || cls.includes('rmp-video')) {
                const parent = video.closest('.rmp-ad-container, [class*="ad-container"]');
                if (parent) parent.remove();
                else video.remove();
                removed++;
            }
        });
        if (removed > 0) log('已移除 ' + removed + ' 个广告元素');
    }

    /** 监听 DOM 变化自动移除广告 */
    function setupAdObserver() {
        function startObserver() {
            if (!document.documentElement) {
                log('[伪装] document.documentElement 未就绪，延迟监听');
                setTimeout(startObserver, 500);
                return;
            }
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType !== Node.ELEMENT_NODE) return;
                        var cls = node.className || '';
                        if (typeof cls === 'string' && (cls.includes('rmp-ad') || cls.includes('ad-container'))) {
                            log('[伪装] 检测到新增广告容器，移除');
                            node.remove();
                        }
                        if (node.tagName === 'VIDEO') {
                            var vCls = node.className || '';
                            if (typeof vCls === 'string' && vCls.includes('rmp-ad')) {
                                node.remove();
                            }
                        }
                    });
                });
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
            log('[伪装] 广告DOM监听已启用');
        }
        startObserver();
    }

    /** 伪装模式专用网络拦截（扩展 index.jpg 等伪装 URL 检查） */
    function disguiseInterceptNetworkRequests() {
        // 拦截 XMLHttpRequest
        var OriginalXHR = window.XMLHttpRequest;
        var originalXHROpen = OriginalXHR.prototype.open;
        var originalXHRSetHeader = OriginalXHR.prototype.setRequestHeader;

        OriginalXHR.prototype.setRequestHeader = function(key, value) {
            if (!this._catcatchHeaders) this._catcatchHeaders = {};
            this._catcatchHeaders[key] = value;
            return originalXHRSetHeader.apply(this, arguments);
        };

        OriginalXHR.prototype.open = function(method, url) {
            var urlStr = String(url);
            this.addEventListener('load', function() {
                try {
                    log('[伪装] XHR完成: ' + urlStr.substring(0, 100));
                    if (isDisguisedM3u8Url(urlStr)) {
                        var headers = buildHeaders(urlStr, this._catcatchHeaders || {});
                        if (typeof this.responseText === 'string' && verifyM3U8Content(this.responseText)) {
                            log('[伪装] ✓ XHR发现伪装M3U8!');
                            notifyM3u8Found(urlStr, headers);
                        } else if (urlStr.includes('.m3u8')) {
                            log('[伪装] ✓ XHR发现.m3u8 URL');
                            notifyM3u8Found(urlStr, headers);
                        }
                    }
                } catch (e) { log('[伪装] XHR处理错误: ' + e.message); }
            });
            return originalXHROpen.apply(this, arguments);
        };

        // 拦截 fetch - 伪装模式检查所有响应
        var originalFetch = window.fetch;
        var fetchCount = 0;
        window.fetch = function(input, init) {
            var url = (input && input.url) || String(input);
            var requestHeaders = extractHeaders(init && init.headers);
            fetchCount++;

            return originalFetch.apply(this, arguments).then(function(response) {
                try {
                    log('[伪装] fetch完成[#' + fetchCount + ']: ' + url.substring(0, 100) + ' status=' + response.status);
                    var headers = buildHeaders(url, requestHeaders);

                    if (url.includes('.m3u8')) {
                        log('[伪装] fetch URL含.m3u8，验证内容...');
                        response.clone().text().then(function(text) {
                            if (verifyM3U8Content(text)) {
                                log('[伪装] ✓ fetch发现.m3u8 M3U8!');
                                notifyM3u8Found(url, headers);
                            }
                        }).catch(function(e) { log('[伪装] fetch .m3u8读取失败: ' + e); });
                    } else if (isDisguisedM3u8Url(url)) {
                        log('[伪装] fetch URL匹配伪装规则，验证内容...');
                        response.clone().text().then(function(text) {
                            if (verifyM3U8Content(text)) {
                                log('[伪装] ✓ fetch发现伪装M3U8!');
                                notifyM3u8Found(url, headers);
                            }
                        }).catch(function(e) { log('[伪装] fetch 伪装URL读取失败: ' + e); });
                    }

                    // 检查 Content-Type
                    var contentType = response.headers.get('content-type') || '';
                    if (contentType.includes('mpegurl') || contentType.includes('x-mpegURL')) {
                        log('[伪装] ✓ Content-Type 匹配 M3U8: ' + contentType);
                        notifyM3u8Found(url, headers);
                    }
                } catch (e) { log('[伪装] fetch处理错误: ' + e.message); }
                return response;
            });
        };

        // 监听 script src
        var origCreate = document.createElement;
        document.createElement = function(tagName) {
            var element = origCreate.call(document, tagName);
            if (tagName.toLowerCase() === 'script') {
                var origSetAttr = element.setAttribute;
                element.setAttribute = function(name, value) {
                    if (name === 'src' && isDisguisedM3u8Url(String(value))) {
                        log('[伪装] ✓ script src匹配伪装规则');
                        notifyM3u8Found(String(value), buildHeaders(String(value)));
                    }
                    return origSetAttr.call(this, name, value);
                };
            }
            return element;
        };

        log('[伪装] 网络拦截已启用 (XHR + fetch + script)');
    }

    /** 伪装模式深度扫描（扫描 .m3u8 和 index.jpg） */
    function deepScanDisguise() {
        log('[伪装] ===== 深度扫描开始 =====');
        var foundUrls = new Set();
        var patterns = [
            /https?:\/\/[^\s"'`<>]+\.m3u8[^\s"'`<>]*/gi,
            /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/gi,
            /https?:\/\/[^\s"'`<>]+index\.jpg[^\s"'`<>]*/gi,
            /["'](https?:\/\/[^"']+index\.jpg[^"']*)["']/gi,
            /url\s*[:=]\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/gi
        ];

        try {
            var html = document.documentElement.outerHTML;
            log('[伪装] HTML长度: ' + html.length);
            patterns.forEach(function(pattern) {
                var matches = html.match(pattern);
                if (matches) {
                    matches.forEach(function(url) {
                        url = url.replace(/^["']|["']$/g, '').replace(/[;,\)\]}\>'"`]+$/, '');
                        if (url.includes('.m3u8') || url.includes('index.jpg')) foundUrls.add(url);
                    });
                }
            });
        } catch (e) { log('[伪装] HTML扫描失败: ' + e.message); }

        try {
            var scripts = document.querySelectorAll('script');
            log('[伪装] script标签: ' + scripts.length + '个');
            scripts.forEach(function(script) {
                var content = script.textContent || script.innerHTML;
                if (content) {
                    patterns.forEach(function(pattern) {
                        var matches = content.match(pattern);
                        if (matches) {
                            matches.forEach(function(url) {
                                url = url.replace(/^["']|["']$/g, '').replace(/[;,\)\]}\>'"`]+$/, '');
                                if (url.includes('.m3u8') || url.includes('index.jpg')) foundUrls.add(url);
                            });
                        }
                    });
                }
            });
        } catch (e) { log('[伪装] script扫描失败: ' + e.message); }

        try {
            document.querySelectorAll('video').forEach(function(video) {
                var src = video.src || video.currentSrc;
                if (src) { foundUrls.add(src); log('[伪装] video src: ' + src.substring(0, 80)); }
            });
        } catch (e) {}

        log('[伪装] 深度扫描完成，发现 ' + foundUrls.size + ' 个链接');
        foundUrls.forEach(function(url) {
            log('[伪装] 深度扫描结果: ' + url.substring(0, 100));
            notifyM3u8Found(url, buildHeaders(url));
        });
    }

    // DISGUISE 模式：伪装模式（index.jpg 拦截 + 自动播放 + 广告跳过）
    function initDisguise() {
        if (state.disguiseInitialized) {
            log('[伪装] 已初始化，跳过');
            return;
        }
        state.disguiseInitialized = true;

        log('[伪装] ===== initDisguise 开始 =====');
        log('[伪装] URL: ' + window.location.href);
        log('[伪装] readyState: ' + document.readyState);

        // 启用伪装模式网络拦截
        disguiseInterceptNetworkRequests();

        // 启用广告监控
        setupAdObserver();
        removeAdContainers();

        // 获取页面标题
        setTimeout(getPageTitle, 500);

        // 延迟后自动点击播放按钮
        setTimeout(function() {
            log('[伪装] 尝试自动播放...');
            clickPlayButton(function(clicked) {
                if (clicked) {
                    log('[伪装] 播放按钮已点击，等待广告...');
                    clickSkipAdButton();
                } else {
                    log('[伪装] 未找到播放按钮，等待5秒后重试...');
                    setTimeout(function() {
                        clickPlayButton(function(retryClicked) {
                            if (retryClicked) clickSkipAdButton();
                        });
                    }, 5000);
                }
            });
        }, 2000);

        // 定时深度扫描
        setTimeout(deepScanDisguise, 3000);
        setTimeout(deepScanDisguise, 6000);
        setTimeout(deepScanDisguise, 10000);

        // 定时移除广告容器
        setInterval(removeAdContainers, 3000);

        log('[伪装] initDisguise 完成');
    }

    // ==================== 暴露 API ====================
    window.CatCatchSniffer = {
        init: init,                        // AUTO 模式
        initNetworkOnly: initNetworkOnly,  // NETWORK 模式
        initDomOnly: initDomOnly,          // DOM 模式
        initDisguise: initDisguise,        // 伪装模式
        deepScan: deepScanEnhanced,        // DEEP_SCAN 模式（增强版）
        deepScanBasic: deepScanBasic,      // 基础深度扫描
        deepScanEnhanced: deepScanEnhanced,// 增强深度扫描
        scanJavaScriptVariables: scanJavaScriptVariables,
        scanJsonConfigs: scanJsonConfigs,
        scanPlayerInstances: scanPlayerInstances,
        getPageTitle: getPageTitle,
        clearState: clearState,
        getLogs: function() { return state.logs; }
    };

    // 不再自动初始化，由 Kotlin 端根据当前模式选择性调用
    // init();
})();
