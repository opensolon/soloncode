(function () {
    if (window.__studioNavigationGuardInstalled) {
        return;
    }
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

    function dispatchStudioNavigationBlocked(payload) {
        var eventName = "studio-blocked-navigation";

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
    }

    function bindStudioNavigationBlockedListener() {
        try {
            window.addEventListener("message", function (event) {
                console.log("[studio] raw message:", event.origin, event.data);
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

                console.log("[studio] blocked navigation:", payload.source, payload.url);
            });
        } catch (e) {
            // ignore listener setup failures
        }
    }

    bindStudioNavigationBlockedListener();

    if (!isStudioPageEnabled) {
        return;
    }

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
        console.log("[studio] blocked navigation:", source, url);

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

    bindStudioNavigationBlockedListener();
    bindAnchorObserver();
})();
