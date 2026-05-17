/**
 * CatCatch M3U8 嗅探脚本
 * 从油猴脚本提取的核心嗅探逻辑
 */
(function() {
    'use strict';

    // ==================== 配置 ====================
    const CONFIG = {
        AD_KEYWORDS: ['ad', 'ads', 'adv', 'advertisement', 'silent-basis'],
        LOG_PREFIX: '[CatCatch]',
        MAX_LOG_LINES: 100
    };

    // ==================== 状态 ====================
    const state = {
        capturedUrls: new Set(),
        pendingUrls: new Set(),
        requestHeaders: new Map(),
        logs: [],
        initialized: false
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

    // ==================== 网络请求拦截 ====================
    function interceptNetworkRequests() {
        if (state.initialized) return;
        state.initialized = true;

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
                        // 尝试获取响应内容
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
                    if (url.includes('.m3u8')) {
                        const headers = buildHeaders(url, requestHeaders);
                        response.clone().text().then(text => {
                            if (text.includes('#EXTM3U')) {
                                notifyM3u8Found(url, headers);
                            }
                        }).catch(() => {});
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

    // ==================== 深度扫描 ====================
    function deepScan() {
        log('开始深度扫描...');

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

        // 4. 扫描 window 对象
        try {
            for (let key in window) {
                try {
                    const value = String(window[key]);
                    if (value.includes('.m3u8')) {
                        const matches = value.match(/https?:\/\/[^\s"'`<>]+\.m3u8[^\s"'`<>]*/gi);
                        if (matches) matches.forEach(url => foundUrls.add(url));
                    }
                } catch (e) {}
            }
        } catch (e) {}

        log(`扫描完成，发现 ${foundUrls.size} 个链接`);

        // 处理发现的 URL
        foundUrls.forEach(url => {
            notifyM3u8Found(url, buildHeaders(url));
        });
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
        log('已清空状态');
    }

    // ==================== 初始化 ====================
    function init() {
        if (state.initialized) return;

        log('嗅探脚本已加载');
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
        setTimeout(deepScan, 2000);
        setTimeout(deepScan, 5000);
    }

    // ==================== 暴露 API ====================
    window.CatCatchSniffer = {
        init: init,
        deepScan: deepScan,
        getPageTitle: getPageTitle,
        clearState: clearState,
        getLogs: function() { return state.logs; }
    };

    // 自动初始化
    init();
})();
