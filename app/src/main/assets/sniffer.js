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
        domInitialized: false
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

    // ==================== 暴露 API ====================
    window.CatCatchSniffer = {
        init: init,                        // AUTO 模式
        initNetworkOnly: initNetworkOnly,  // NETWORK 模式
        initDomOnly: initDomOnly,          // DOM 模式
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
