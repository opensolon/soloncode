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
                        payload: payload
                    },
                    "*"
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
                if (!data || typeof data.type !== "string" || data.type.indexOf("studio-") !== 0) {
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

    function dispatchStudioTaskLifecycle(action, source, sess) {
        var payload = {
            action: action,
            source: source,
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

    if (!isStudioPageEnabled) {
        studioLog("inactive, missing studio=true");
        return;
    }

    function applyStudioAppearance() {
        var hiddenControlIds = ["themeBtn", "welcomeVoiceBtn", "chatVoiceBtn"];
        for (var i = 0; i < hiddenControlIds.length; i += 1) {
            var control = document.getElementById(hiddenControlIds[i]);
            if (control) {
                control.style.display = "none";
            }
        }

        document.body.setAttribute("data-theme", "light");

        if (typeof window.currentTheme !== "undefined") {
            window.currentTheme = "light";
        }
        if (typeof window.applyHljsTheme === "function") {
            window.applyHljsTheme("light");
        }
        if (typeof window.updateThemeIcon === "function") {
            window.updateThemeIcon();
        }
        if (typeof window.mermaid !== "undefined") {
            window.mermaid.initialize({ theme: "default" });
        }
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

    function handleBlockedNavigation(url, source) {
        var payload = {
            source: source,
            url: url,
            timestamp: Date.now()
        };

        dispatchStudioNavigationBlocked(payload);

        if (typeof window.onStudioNavigationBlocked === "function") {
            window.onStudioNavigationBlocked(url, source);
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
