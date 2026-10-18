// Terminal landing page — progressive enhancement only.
// The page is fully usable with JavaScript disabled: this file only adds the
// mobile menu, the sticky-header state and the copy-to-clipboard helper.

(function () {
    "use strict";

    // ---- Mobile navigation -------------------------------------------------

    var toggle = document.querySelector(".nav-toggle");
    var nav = document.getElementById("nav-principal");

    function closeNav() {
        if (!nav || !toggle) return;
        nav.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
    }

    if (toggle && nav) {
        toggle.addEventListener("click", function () {
            var open = nav.classList.toggle("is-open");
            toggle.setAttribute("aria-expanded", open ? "true" : "false");
        });

        // Following an in-page link should not leave the menu hanging open.
        nav.addEventListener("click", function (event) {
            if (event.target.closest("a")) closeNav();
        });

        document.addEventListener("keydown", function (event) {
            if (event.key !== "Escape") return;
            closeNav();
            toggle.focus();
        });

        document.addEventListener("click", function (event) {
            if (!nav.classList.contains("is-open")) return;
            if (nav.contains(event.target) || toggle.contains(event.target)) return;
            closeNav();
        });

        window.addEventListener("resize", function () {
            if (window.innerWidth > 900) closeNav();
        });
    }

    // ---- Sticky header state ----------------------------------------------

    var header = document.querySelector(".site-header");

    if (header) {
        var ticking = false;

        function updateHeader() {
            header.classList.toggle("is-stuck", window.scrollY > 8);
            ticking = false;
        }

        window.addEventListener("scroll", function () {
            if (ticking) return;
            ticking = true;
            window.requestAnimationFrame(updateHeader);
        }, { passive: true });

        updateHeader();
    }

    // ---- Copy the APK SHA-256 ---------------------------------------------

    function legacyCopy(text) {
        var area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();

        var ok = false;
        try {
            ok = document.execCommand("copy");
        } catch (error) {
            ok = false;
        }

        document.body.removeChild(area);
        return ok;
    }

    var copyButtons = document.querySelectorAll("[data-copy-target]");

    Array.prototype.forEach.call(copyButtons, function (button) {
        button.addEventListener("click", function () {
            var target = document.getElementById(button.getAttribute("data-copy-target"));
            var status = button.parentNode.querySelector("[data-copy-status]");
            if (!target) return;

            var text = (target.textContent || "").trim();
            var done = function (message) {
                if (!status) return;
                status.textContent = message;
                window.setTimeout(function () {
                    status.textContent = "";
                }, 2500);
            };

            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(function () {
                    done("Copiado al portapapeles.");
                }, function () {
                    done(legacyCopy(text) ? "Copiado al portapapeles." : "No se pudo copiar.");
                });
                return;
            }

            done(legacyCopy(text) ? "Copiado al portapapeles." : "No se pudo copiar.");
        });
    });
})();