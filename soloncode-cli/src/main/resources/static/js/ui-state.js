/**
 * ui-state.js — 界面状态门面（后端记忆）
 *
 * 背景（Gitee #IKJOCR）：localStorage / sessionStorage 按 origin（scheme + host + port）隔离。
 * SolonCode Web 支持随机端口启动（--port 0），端口一变 origin 就变，浏览器给出一个空白的
 * localStorage：表现就是「每次重启 Web 都不会自动加载之前的对话」，主题/字号/皮肤/侧栏布局
 * 也一并回到默认。固定端口只是碰巧躲过了这件事。
 *
 * 因此把这类「刷新后要恢复的轻量状态」的真相源搬到服务端（GET/POST /web/ui/state，
 * 落 ~/.soloncode/ui-state.json），本文件是统一的读写门面：
 *
 *   UiState.get(key, def)   同步读取：以本地镜像为准（服务端水合后会覆盖镜像）
 *   UiState.set(key, value) 写本地镜像 + 异步批量写回服务端
 *   UiState.remove(key)     删除（本地 + 服务端）
 *   UiState.ready(cb)       服务端水合完成后回调（用于「服务端值优先」的二次校准）
 *
 * 设计要点：
 * 1) 保留 localStorage 只作为「同源首屏缓存」：主题/字体这类状态若等异步请求回来再应用，
 *    会出现可见闪烁（现有代码也是「先本地立即应用 → 再由服务端校准」的思路）。真相源是服务端，
 *    本地缓存丢了（换端口/换浏览器/清缓存）只是首帧回到默认值，随后被服务端值纠正。
 * 2) 读取一律同步（内存镜像就是 localStorage），写入合并后延迟批量提交，避免连续交互打出请求风暴。
 * 3) 页面卸载时用 sendBeacon 兜底提交，避免「刚切换主题就关页面」丢状态。
 * 4) 首次升级时，本地镜像里已有、服务端还没有的键会补推一次（老用户的设置不丢）。
 */
(function (window, document) {
    'use strict';

    var LOAD_URL = '/web/ui/state';
    var SAVE_URL = '/web/ui/state/save';
    /** 写回批量延迟（毫秒） */
    var SAVE_DELAY = 300;
    /**
     * 本门面接管的键（与后端键名白名单一致）：
     * 只纳入「目前没有其它服务端真相源」的状态。字体(chat-font-*)与皮肤(chat-skin)
     * 已由 settings.json 承载（/web/settings/general、/web/settings/skins/*），
     * 启动后会用服务端值校准，故仍留在 localStorage 做首屏缓存，不重复入库。
     *
     * 也用于首次升级时的镜像补推筛选：当前 origin 下的旧值只补推一次到服务端。
     */
    var MANAGED_KEY = /^(chat-theme|sidebar-collapsed|sidebar-width|files-collapsed|files-width|workspace-active-tab|filer-active-tab|sc-locale|soloncode\.backup\.checkedKeys|soloncode-active-session)/;

    /** 待提交的变更：值为 null 表示删除 */
    var pending = {};
    var timer = null;
    var saving = false;
    var hydrated = false;
    var readyCallbacks = [];

    function localStore() {
        try {
            return window.localStorage;
        } catch (e) {
            return null; // 隐私模式等场景下 localStorage 不可用
        }
    }

    function mirrorGet(key) {
        var s = localStore();
        if (!s || !key) return null;
        try {
            return s.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function mirrorSet(key, value) {
        var s = localStore();
        if (!s || !key) return;
        try {
            if (value === null || value === undefined) s.removeItem(key);
            else s.setItem(key, String(value));
        } catch (e) {
            /* 镜像写失败不影响主流程 */
        }
    }

    function hasPending() {
        for (var k in pending) {
            if (Object.prototype.hasOwnProperty.call(pending, k)) return true;
        }
        return false;
    }

    /**
     * 同步读取。未设置时返回 def（与原 localStorage 语义一致）。
     */
    function get(key, def) {
        var v = mirrorGet(key);
        if (v === null || v === undefined || v === '') return def;
        return v;
    }

    /**
     * 写入：立即更新本地镜像（首屏/后续读取都是同步的），服务端异步批量保存。
     * 传空串等价于删除（现有调用点都按 `get(key) || 默认值` 使用）。
     */
    function set(key, value) {
        if (!key) return;
        var v = (value === null || value === undefined) ? '' : String(value);
        if (v === '') {
            remove(key);
            return;
        }
        mirrorSet(key, v);
        pending[key] = v;
        scheduleSave();
    }

    /**
     * 删除键
     */
    function remove(key) {
        if (!key) return;
        mirrorSet(key, null);
        pending[key] = null;
        scheduleSave();
    }

    function scheduleSave() {
        if (timer !== null) return;
        timer = window.setTimeout(function () {
            timer = null;
            save();
        }, SAVE_DELAY);
    }

    /**
     * 提交待写变更。同一时刻只允许一个在途请求：期间的新变更留在 pending，
     * 本次响应返回后再发，保证同一键的写入顺序不会因网络乱序而反转。
     */
    function save() {
        if (saving || !hasPending()) return;

        var body = {};
        for (var k in pending) {
            if (Object.prototype.hasOwnProperty.call(pending, k)) body[k] = pending[k];
        }
        pending = {};

        saving = true;
        request('POST', SAVE_URL, JSON.stringify(body), function (ok) {
            saving = false;
            if (!ok) {
                //写失败：把变更回填待写（不覆盖期间更新的值），下次交互或卸载时重试（不在此处无限重试）
                for (var rk in body) {
                    if (Object.prototype.hasOwnProperty.call(body, rk)
                        && !Object.prototype.hasOwnProperty.call(pending, rk)) {
                        pending[rk] = body[rk];
                    }
                }
            }
            if (hasPending()) scheduleSave();
        });
    }

    /**
     * 页面卸载兜底提交：
     * 优先 sendBeacon（不阻塞卸载），不可用时退回同步 XHR。
     */
    function flush() {
        if (!hasPending()) return;

        var body = JSON.stringify(pending);
        var keys = pending;
        pending = {};

        if (window.navigator && window.navigator.sendBeacon) {
            try {
                var blob = new Blob([body], { type: 'application/json' });
                if (window.navigator.sendBeacon(SAVE_URL, blob)) return;
            } catch (e) {
                /* 退回 XHR */
            }
        }

        try {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', SAVE_URL, false); // 同步请求：卸载阶段唯一可靠的选择
            xhr.setRequestHeader('Content-Type', 'application/json');
            xhr.send(body);
        } catch (e) {
            //彻底失败：把变更留在内存里已无意义（页面即将销毁），记录便于排查
            console.warn('[UiState] flush failed', keys, e);
        }
    }

    function request(method, url, body, done) {
        var xhr;
        try {
            xhr = new XMLHttpRequest();
            xhr.open(method, url, true);
            xhr.withCredentials = true;
        } catch (e) {
            done(false, null);
            return;
        }

        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) return;

            var ok = (xhr.status >= 200 && xhr.status < 300);
            var data = null;
            if (ok) {
                try {
                    var resp = JSON.parse(xhr.responseText);
                    if (resp && typeof resp.code !== 'undefined') ok = (resp.code === 200);
                    data = resp ? resp.data : null;
                } catch (e) {
                    ok = false;
                }
            }
            done(ok, data);
        };

        try {
            if (body !== null && body !== undefined) {
                xhr.setRequestHeader('Content-Type', 'application/json');
            }
            xhr.send(body === null || body === undefined ? null : body);
        } catch (e) {
            done(false, null);
        }
    }

    /**
     * 服务端水合：服务端值覆盖本地镜像（服务端才是真相源）。
     * 未认证（如登录页）或服务不可达时静默降级，保留本地镜像。
     */
    function hydrate() {
        request('GET', LOAD_URL, null, function (ok, data) {
            if (ok && data) {
                var serverKeys = {};
                for (var k in data) {
                    if (Object.prototype.hasOwnProperty.call(data, k) === false) continue;
                    var v = data[k];
                    if (v === null || v === undefined || v === '') continue;
                    serverKeys[k] = true;
                    //水合前本地刚发生的修改优先级更高（它已经在界面上生效，且即将写回服务端）
                    if (Object.prototype.hasOwnProperty.call(pending, k)) continue;
                    mirrorSet(k, v);
                }
                pushLocalOnlyKeys(serverKeys);
            }

            hydrated = true;
            fireReady();
        });
    }

    /**
     * 首次升级补推：本地镜像已有、服务端还没有的键写回服务端。
     * （老用户切换到后端记忆的第一次运行，设置不能丢。）
     */
    function pushLocalOnlyKeys(serverKeys) {
        var s = localStore();
        if (!s) return;

        var changed = false;
        for (var i = 0; i < s.length; i++) {
            var key = s.key(i);
            if (!key || MANAGED_KEY.test(key) === false) continue;
            if (Object.prototype.hasOwnProperty.call(serverKeys, key)) continue;

            var value = mirrorGet(key);
            if (value === null || value === '' || value === undefined) continue;

            pending[key] = value;
            changed = true;
        }

        if (changed) scheduleSave();
    }

    function fireReady() {
        try {
            document.dispatchEvent(new CustomEvent('uistate:ready'));
        } catch (e) {
            /* 老浏览器不支持 CustomEvent 构造器：回调仍然会执行 */
        }

        var callbacks = readyCallbacks;
        readyCallbacks = [];
        for (var i = 0; i < callbacks.length; i++) {
            try {
                callbacks[i]();
            } catch (e) {
                console.error('[UiState] ready callback failed', e);
            }
        }
    }

    /**
     * 服务端水合完成回调（已水合则立即执行）。
     * 用于「服务端值优先」的二次校准，例如按服务端主题/语言/活动会话重新应用界面。
     */
    function ready(callback) {
        if (typeof callback !== 'function') return;
        if (hydrated) {
            callback();
            return;
        }
        readyCallbacks.push(callback);
    }

    window.UiState = {
        get: get,
        set: set,
        remove: remove,
        ready: ready,
        flush: flush,
        isHydrated: function () { return hydrated; },
        LOAD_URL: LOAD_URL,
        SAVE_URL: SAVE_URL
    };

    // 卸载兜底：pagehide 覆盖 bfcache/移动端切后台，beforeunload 覆盖桌面关标签页
    window.addEventListener('pagehide', flush, false);
    window.addEventListener('beforeunload', flush, false);

    hydrate();
})(window, document);
