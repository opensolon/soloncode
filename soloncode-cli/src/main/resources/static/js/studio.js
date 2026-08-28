(function () {
    if (window.__studioBridgeInstalled || window.__studioNavigationGuardInstalled) {
        return;
    }
    window.__studioBridgeInstalled = true;
    window.__studioNavigationGuardInstalled = true;

    var nativeOpen = window.open;
    var allowedSchemes = ["http:", "https:", "mailto:", "tel:"];
    var studioFlag = "studio";
    var isStudioPageEnabled = false;
    var pendingStudioTheme = "";
    var pendingStudioLocale = "";
    var cliMessageSource = "soloncode-cli";
    var studioMessageSource = "soloncode-studio";
    var studioParentOrigin = "";
    var supportedStudioLocales = [
        "system",
        "zh-CN",
        "zh-TW",
        "en",
        "ja",
        "ko",
        "de",
        "fr",
        "es",
        "it",
        "ru",
        "ar",
        "br",
        "th",
        "vi",
        "pl",
        "bn",
        "bs",
        "da",
        "gr",
        "no",
        "tr",
        "uk"
    ];

    try {
        studioParentOrigin = window.location.ancestorOrigins && window.location.ancestorOrigins[0];
        if (!studioParentOrigin && document.referrer) {
            studioParentOrigin = new URL(document.referrer).origin;
        }
    } catch (e) {
        studioParentOrigin = "";
    }

    function isStudioParentMessage(event, data) {
        return (
            window.parent !== window &&
            event.source === window.parent &&
            (!studioParentOrigin || event.origin === studioParentOrigin) &&
            data &&
            data.source === studioMessageSource &&
            typeof data.type === "string" &&
            data.type.indexOf("studio-") === 0
        );
    }

    try {
        isStudioPageEnabled = new URL(window.location.href).searchParams.get(studioFlag) === "true";
    } catch (e) {
        isStudioPageEnabled = false;
    }

    function studioLog(message, detail) {
        try {
            if (detail !== undefined) {
                console.log("[studio] " + message, detail);
            } else {
                console.log("[studio] " + message);
            }
        } catch (e) {
            // ignore console failures
        }
    }

    function dispatchStudioMessage(eventName, payload) {
        try {
            var customEvent = new CustomEvent(eventName, { detail: payload });
            window.dispatchEvent(customEvent);
        } catch (e) {
            // ignore custom event failures
        }

        try {
            if (window.top && window.top !== window) {
                var topEvent = new CustomEvent(eventName, { detail: payload });
                window.top.dispatchEvent(topEvent);
            }
        } catch (e) {
            // ignore top window dispatch failures
        }

        if (window.parent && window.parent !== window) {
            try {
                window.parent.postMessage(
                    {
                        type: eventName,
                        source: cliMessageSource,
                        payload: payload
                    },
                    studioParentOrigin || "*"
                );
            } catch (e) {
                // ignore cross-window failures
            }
        }

        studioLog("message dispatched", {
            type: eventName,
            payload: payload
        });
    }

    function dispatchStudioNavigationBlocked(payload) {
        dispatchStudioMessage("studio-blocked-navigation", payload);
    }

    function bindStudioNavigationBlockedListener() {
        try {
            window.addEventListener("message", function (event) {
                var data = event && event.data ? event.data : null;
                if (!isStudioParentMessage(event, data)) {
                    return;
                }

                studioLog("message received", {
                    origin: event.origin,
                    data: data
                });
            });
        } catch (e) {
            // ignore listener setup failures
        }

        try {
            window.addEventListener("studio-blocked-navigation", function (event) {
                var payload = event && event.detail ? event.detail : null;
                if (!payload || typeof payload.url !== "string") {
                    return;
                }

                studioLog("navigation event received", payload);
            });
        } catch (e) {
            // ignore listener setup failures
        }
    }

    function normalizeStudioTheme(theme) {
        return theme === "dark" ? "dark" : theme === "light" ? "light" : "";
    }

    function applyStudioTheme(theme) {
        var normalizedTheme = normalizeStudioTheme(theme);
        if (!normalizedTheme) {
            return;
        }

        var currentTheme = normalizeStudioTheme(document.body.getAttribute("data-theme") || window.currentTheme);
        if (currentTheme === normalizedTheme) {
            pendingStudioTheme = "";
            return;
        }

        pendingStudioTheme = normalizedTheme;
        var themeButton = document.getElementById("themeBtn");
        if (themeButton && typeof themeButton.click === "function") {
            themeButton.click();
            return;
        }

        document.body.setAttribute("data-theme", normalizedTheme);
        window.currentTheme = normalizedTheme;
        try {
            window.localStorage.setItem("chat-theme", normalizedTheme);
        } catch (e) {
            // ignore theme persistence failures
        }
        if (typeof window.applyHljsTheme === "function") {
            window.applyHljsTheme(normalizedTheme);
        }
        if (typeof window.updateThemeIcon === "function") {
            window.updateThemeIcon();
        }
        if (typeof window.mermaid !== "undefined") {
            window.mermaid.initialize({ theme: normalizedTheme === "dark" ? "dark" : "default" });
        }
    }

    function reportWorkspaceTheme(theme) {
        var normalizedTheme = normalizeStudioTheme(theme);
        if (!normalizedTheme) {
            return;
        }
        if (pendingStudioTheme === normalizedTheme) {
            pendingStudioTheme = "";
            return;
        }
        pendingStudioTheme = "";
        dispatchStudioMessage("soloncode-theme-change", {
            theme: normalizedTheme
        });
    }

    function bindStudioThemeBridge() {
        try {
            window.addEventListener("message", function (event) {
                var data = event && event.data ? event.data : null;
                if (!isStudioParentMessage(event, data)) {
                    return;
                }

                if (data.type === "studio-theme-sync") {
                    var theme = data.payload && data.payload.theme ? data.payload.theme : data.theme;
                    studioLog("theme synchronized from studio", theme);
                    applyStudioTheme(theme);
                }
            });

            if (window.MutationObserver && document.body) {
                var themeObserver = new MutationObserver(function (mutations) {
                    for (var i = 0; i < mutations.length; i += 1) {
                        if (mutations[i].attributeName === "data-theme") {
                            reportWorkspaceTheme(document.body.getAttribute("data-theme"));
                            return;
                        }
                    }
                });
                themeObserver.observe(document.body, { attributes: true, attributeFilter: ["data-theme"] });
            }
        } catch (e) {
            // ignore theme listener setup failures
        }
    }

    function normalizeStudioLocale(locale) {
        return supportedStudioLocales.indexOf(locale) >= 0 ? locale : "zh-CN";
    }

    function applyStudioLocale(locale) {
        var normalizedLocale = normalizeStudioLocale(locale);
        if (!normalizedLocale || !window.I18n || typeof window.I18n.switch !== "function") {
            return;
        }

        // 显式语言与当前实际生效语言相同且非跟随系统模式时，视为已同步
        if (normalizedLocale !== "system"
            && !(window.I18n.isSystemMode && window.I18n.isSystemMode())
            && window.I18n.getLocale() === normalizedLocale) {
            pendingStudioLocale = "";
            return;
        }

        pendingStudioLocale = normalizedLocale;
        window.I18n.switch(normalizedLocale);
    }

    function reportWorkspaceLocale(locale, mode) {
        // 跟随系统模式下向桌面端上报 system 哨兵（附实际生效语言便于桌面端展示）
        var reported = (mode === "system") ? "system" : locale;
        var normalizedLocale = normalizeStudioLocale(reported);
        if (!normalizedLocale) {
            return;
        }
        if (pendingStudioLocale === normalizedLocale) {
            pendingStudioLocale = "";
            return;
        }
        pendingStudioLocale = "";
        dispatchStudioMessage("soloncode-locale-change", {
            locale: normalizedLocale,
            mode: mode || null,
            resolvedLocale: locale
        });
    }

    function bindStudioLocaleBridge() {
        try {
            window.addEventListener("message", function (event) {
                var data = event && event.data ? event.data : null;
                if (!isStudioParentMessage(event, data)) {
                    return;
                }

                if (data.type === "studio-locale-sync") {
                    var locale = data.payload && data.payload.locale ? data.payload.locale : data.locale;
                    studioLog("locale synchronized from studio", locale);
                    applyStudioLocale(locale);
                }
            });

            document.addEventListener("i18n:switched", function (event) {
                var detail = event && event.detail ? event.detail : {};
                reportWorkspaceLocale(detail.locale || "", detail.mode || null);
            });
        } catch (e) {
            // ignore locale listener setup failures
        }
    }

    function getStudioTaskName(sess) {
        var sessionId = sess && sess.sessionId ? sess.sessionId : window.SESSION_ID;

        try {
            if (window.chatHistory && sessionId) {
                for (var i = 0; i < window.chatHistory.length; i += 1) {
                    if (window.chatHistory[i] && window.chatHistory[i].sessionId === sessionId) {
                        return window.chatHistory[i].label || "";
                    }
                }
            }
        } catch (e) {
            // ignore task name lookup failures
        }

        try {
            var activeLabel = document.querySelector("#historyList .sidebar-item.active .sidebar-item-label");
            if (activeLabel) {
                return activeLabel.textContent || "";
            }
        } catch (e) {
            // ignore active label lookup failures
        }

        return "";
    }

    function dispatchStudioTaskLifecycle(action, trigger, sess) {
        var payload = {
            action: action,
            trigger: trigger,
            taskName: getStudioTaskName(sess),
            sessionId: sess && sess.sessionId ? sess.sessionId : window.SESSION_ID,
            timestamp: Date.now()
        };

        dispatchStudioMessage("studio-task-lifecycle", payload);
    }

    function bindStudioTaskLifecycle() {
        if (typeof window.sendWithFormDataGrouped === "function") {
            var nativeSendWithFormDataGrouped = window.sendWithFormDataGrouped;
            window.sendWithFormDataGrouped = function (sess, text, filesToSend) {
                dispatchStudioTaskLifecycle("start", "send", sess);
                return nativeSendWithFormDataGrouped.apply(this, arguments);
            };
        }

        if (typeof window.handleHitlResponse === "function") {
            var nativeHandleHitlResponse = window.handleHitlResponse;
            window.handleHitlResponse = function (sess, action) {
                dispatchStudioTaskLifecycle("start", "hitl-" + action, sess);
                return nativeHandleHitlResponse.apply(this, arguments);
            };
        }

        if (typeof window.finishStream === "function") {
            var nativeFinishStream = window.finishStream;
            window.finishStream = function (sess) {
                var result = nativeFinishStream.apply(this, arguments);
                dispatchStudioTaskLifecycle("end", "finish-stream", sess);
                return result;
            };
        }

        window._notifyTaskComplete = function () {};
        studioLog("task lifecycle bridge installed");
    }

    bindStudioNavigationBlockedListener();
    bindStudioThemeBridge();
    bindStudioLocaleBridge();

    if (!isStudioPageEnabled) {
        studioLog("inactive, missing studio=true");
        return;
    }

    function applyStudioAppearance() {
        var hiddenControlIds = ["newChatVoiceBtn", "chatVoiceBtn"];
        for (var i = 0; i < hiddenControlIds.length; i += 1) {
            var control = document.getElementById(hiddenControlIds[i]);
            if (control) {
                control.style.display = "none";
            }
        }

        var wsOpenBtn = document.getElementById("wsOpenBtn");
        if (wsOpenBtn) {
            wsOpenBtn.style.display = "none";
        }

        var themeButton = document.getElementById("themeBtn");
        if (themeButton) {
            themeButton.hidden = false;
            themeButton.classList.remove("layui-hide");
            themeButton.style.setProperty("display", "flex", "important");
        }

        var currentTheme = document.body.getAttribute("data-theme") || window.currentTheme || "light";
        if (currentTheme !== "light" && currentTheme !== "dark") {
            currentTheme = "light";
        }
        applyStudioTheme(currentTheme);

        studioLog("studio appearance applied with theme:", currentTheme);
        dispatchStudioMessage("soloncode-theme-ready", {});
        var activeLocale = window.I18n && typeof window.I18n.getLocale === "function" ? window.I18n.getLocale() : "";
        var systemMode = !!(window.I18n && window.I18n.isSystemMode && window.I18n.isSystemMode());
        dispatchStudioMessage("soloncode-locale-ready", {
            locale: systemMode ? "system" : activeLocale,
            mode: systemMode ? "system" : null,
            resolvedLocale: activeLocale
        });
    }

    studioLog("active");
    applyStudioAppearance();

    function isAllowedUrl(url) {
        if (!url) {
            return false;
        }

        var value = String(url).trim();
        if (!value) {
            return false;
        }

        if (value.charAt(0) === "#") {
            return true;
        }

        if (value.indexOf("javascript:") === 0 || value.indexOf("data:") === 0) {
            return false;
        }

        try {
            var parsed = new URL(value, window.location.href);
            return allowedSchemes.indexOf(parsed.protocol) >= 0 || parsed.origin === window.location.origin;
        } catch (e) {
            return false;
        }
    }

    function shouldBlockAnchor(anchor) {
        if (!anchor || !anchor.getAttribute) {
            return false;
        }

        var href = anchor.getAttribute("href");
        if (!href) {
            return false;
        }

        if (isAllowedUrl(href) && (anchor.getAttribute("target") || "").toLowerCase() === "_blank") {
            return true;
        }

        return false;
    }

    function updateAnchorInterception(anchor) {
        if (!anchor || !anchor.getAttribute) {
            return;
        }

        var href = anchor.getAttribute("href");
        if (!href) {
            return;
        }

        if (isAllowedUrl(href) && (anchor.getAttribute("target") || "").toLowerCase() === "_blank") {
            anchor.setAttribute("data-studio-block-navigation", "true");
        } else {
            anchor.removeAttribute("data-studio-block-navigation");
        }
    }

    function scanAnchors(root) {
        if (!root || !root.querySelectorAll) {
            return;
        }

        var anchors = root.querySelectorAll("a[href]");
        for (var i = 0; i < anchors.length; i += 1) {
            updateAnchorInterception(anchors[i]);
        }
    }

    function bindAnchorObserver() {
        try {
            scanAnchors(document);

            if (!window.MutationObserver) {
                return;
            }

            var observer = new MutationObserver(function (mutations) {
                for (var i = 0; i < mutations.length; i += 1) {
                    var mutation = mutations[i];
                    for (var j = 0; j < mutation.addedNodes.length; j += 1) {
                        var node = mutation.addedNodes[j];
                        if (!node || node.nodeType !== 1) {
                            continue;
                        }

                        if (node.tagName === "A") {
                            updateAnchorInterception(node);
                        }

                        scanAnchors(node);
                    }
                }
            });

            observer.observe(document.documentElement || document.body, {
                childList: true,
                subtree: true
            });
        } catch (e) {
            // ignore observer setup failures
        }
    }

    function handleBlockedNavigation(url, trigger) {
        var payload = {
            trigger: trigger,
            url: url,
            timestamp: Date.now()
        };

        dispatchStudioNavigationBlocked(payload);

        if (typeof window.onStudioNavigationBlocked === "function") {
            window.onStudioNavigationBlocked(url, trigger);
        }
    }

    window.open = function (url, target, features) {
        if (!isAllowedUrl(url)) {
            return nativeOpen.apply(window, arguments);
        }

        if (typeof target === "string" && target.toLowerCase() === "_blank") {
            handleBlockedNavigation(url, "window.open");
            return null;
        }

        return nativeOpen.apply(window, arguments);
    };

    document.addEventListener(
        "click",
        function (event) {
            var node = event.target;
            while (node && node !== document) {
                if (node.tagName === "A") {
                    if (shouldBlockAnchor(node)) {
                        event.preventDefault();
                        event.stopPropagation();
                        handleBlockedNavigation(node.href || node.getAttribute("href"), "anchor-click");
                    }
                    return;
                }
                node = node.parentNode;
            }
        },
        true
    );

    document.addEventListener(
        "submit",
        function (event) {
            var form = event.target;
            if (!form || form.tagName !== "FORM") {
                return;
            }

            var action = form.getAttribute("action") || window.location.href;
            if (!isAllowedUrl(action)) {
                return;
            }

            if ((action || "").indexOf("#") === 0) {
                return;
            }

            if (new URL(action, window.location.href).origin !== window.location.origin) {
                return;
            }

            if ((window.location.search || "").indexOf(studioFlag + "=true") >= 0) {
                event.preventDefault();
                event.stopPropagation();
                handleBlockedNavigation(action, "form-submit");
            }
        },
        true
    );

    bindStudioTaskLifecycle();
    bindAnchorObserver();
})();
