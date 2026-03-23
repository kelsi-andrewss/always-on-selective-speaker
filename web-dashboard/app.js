(function () {
    "use strict";

    // --------------- Firebase Config ---------------
    // Replace these values with your actual Firebase project config.
    var firebaseConfig = {
        apiKey: "YOUR_API_KEY",
        authDomain: "YOUR_PROJECT.firebaseapp.com",
        projectId: "YOUR_PROJECT_ID",
        storageBucket: "YOUR_PROJECT.appspot.com",
        messagingSenderId: "000000000000",
        appId: "1:000000000000:web:0000000000000000"
    };

    // --------------- Firebase SDK (compat CDN) ---------------
    // Loaded via script tags injected at runtime so index.html stays clean.
    var FIREBASE_VERSION = "10.12.0";
    var sdkScripts = [
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-app-compat.js",
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-auth-compat.js",
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-firestore-compat.js"
    ];

    var scriptsLoaded = 0;

    function loadScript(src, cb) {
        var s = document.createElement("script");
        s.src = src;
        s.onload = cb;
        s.onerror = function () {
            console.error("Failed to load " + src);
        };
        document.head.appendChild(s);
    }

    function loadScriptsSequentially(list, done) {
        if (list.length === 0) { done(); return; }
        loadScript(list[0], function () {
            scriptsLoaded++;
            loadScriptsSequentially(list.slice(1), done);
        });
    }

    loadScriptsSequentially(sdkScripts, boot);

    // --------------- DOM refs ---------------
    var loginScreen = document.getElementById("login-screen");
    var dashboardScreen = document.getElementById("dashboard-screen");
    var loginForm = document.getElementById("login-form");
    var emailInput = document.getElementById("email");
    var passwordInput = document.getElementById("password");
    var loginError = document.getElementById("login-error");
    var userEmailSpan = document.getElementById("user-email");
    var logoutBtn = document.getElementById("logout-btn");
    var transcriptList = document.getElementById("transcript-list");
    var filterStart = document.getElementById("filter-start");
    var filterEnd = document.getElementById("filter-end");
    var filterBtn = document.getElementById("filter-btn");
    var clearFilterBtn = document.getElementById("clear-filter-btn");

    var db = null;
    var auth = null;
    var refreshTimer = null;
    var REFRESH_INTERVAL_MS = 30000;

    // --------------- Boot ---------------
    function boot() {
        firebase.initializeApp(firebaseConfig);
        auth = firebase.auth();
        db = firebase.firestore();

        auth.onAuthStateChanged(function (user) {
            if (user) {
                showDashboard(user);
            } else {
                showLogin();
            }
        });

        loginForm.addEventListener("submit", handleLogin);
        logoutBtn.addEventListener("click", handleLogout);
        filterBtn.addEventListener("click", function () { loadTranscripts(); });
        clearFilterBtn.addEventListener("click", function () {
            filterStart.value = "";
            filterEnd.value = "";
            loadTranscripts();
        });
    }

    // --------------- Auth ---------------
    function handleLogin(e) {
        e.preventDefault();
        loginError.hidden = true;
        var email = emailInput.value.trim();
        var pw = passwordInput.value;
        auth.signInWithEmailAndPassword(email, pw).catch(function (err) {
            loginError.textContent = friendlyAuthError(err.code);
            loginError.hidden = false;
        });
    }

    function handleLogout() {
        auth.signOut();
    }

    function friendlyAuthError(code) {
        switch (code) {
            case "auth/user-not-found":
            case "auth/wrong-password":
            case "auth/invalid-credential":
                return "Invalid email or password.";
            case "auth/too-many-requests":
                return "Too many attempts. Try again later.";
            default:
                return "Sign-in failed. Please try again.";
        }
    }

    // --------------- Screen switching ---------------
    function showLogin() {
        loginScreen.hidden = false;
        dashboardScreen.hidden = true;
        stopAutoRefresh();
    }

    function showDashboard(user) {
        loginScreen.hidden = true;
        dashboardScreen.hidden = false;
        userEmailSpan.textContent = user.email;
        loadTranscripts();
        startAutoRefresh();
    }

    // --------------- Auto-refresh ---------------
    function startAutoRefresh() {
        stopAutoRefresh();
        refreshTimer = setInterval(function () {
            loadTranscripts();
        }, REFRESH_INTERVAL_MS);
    }

    function stopAutoRefresh() {
        if (refreshTimer) {
            clearInterval(refreshTimer);
            refreshTimer = null;
        }
    }

    // --------------- Data loading ---------------
    function loadTranscripts() {
        var query = db.collection("transcripts").orderBy("timestamp", "desc").limit(500);

        var startDate = filterStart.value;
        var endDate = filterEnd.value;

        if (startDate) {
            var startMs = new Date(startDate).getTime();
            query = query.where("timestamp", ">=", startMs);
        }
        if (endDate) {
            var endMs = new Date(endDate).getTime() + 86400000; // include full day
            query = query.where("timestamp", "<=", endMs);
        }

        query.get().then(function (snapshot) {
            renderTranscripts(snapshot.docs.map(function (doc) {
                return Object.assign({ id: doc.id }, doc.data());
            }));
        }).catch(function (err) {
            console.error("Failed to load transcripts", err);
            transcriptList.innerHTML = '<p class="placeholder">Failed to load transcripts.</p>';
        });
    }

    // --------------- Rendering ---------------
    function renderTranscripts(items) {
        if (items.length === 0) {
            transcriptList.innerHTML = '<p class="placeholder">No transcripts found.</p>';
            return;
        }

        // Group by sessionId
        var groups = {};
        var order = [];
        items.forEach(function (item) {
            var sid = item.sessionId || "unknown";
            if (!groups[sid]) {
                groups[sid] = [];
                order.push(sid);
            }
            groups[sid].push(item);
        });

        var html = "";
        order.forEach(function (sid) {
            html += '<div class="session-group">';
            html += '<div class="session-header">Session: ' + escapeHtml(sid.substring(0, 12)) + "...</div>";
            groups[sid].forEach(function (t) {
                html += renderCard(t);
            });
            html += "</div>";
        });

        transcriptList.innerHTML = html;
    }

    function renderCard(t) {
        var displayText = t.correctedText || t.text || "(empty)";
        var time = t.timestamp ? formatTimestamp(t.timestamp) : "—";
        var gps = "";
        if (t.latitude != null && t.longitude != null) {
            var lat = Number(t.latitude).toFixed(6);
            var lng = Number(t.longitude).toFixed(6);
            var mapsUrl = "https://www.google.com/maps?q=" + lat + "," + lng;
            gps = '<a class="gps-link" href="' + mapsUrl + '" target="_blank" rel="noopener">' + lat + ", " + lng + "</a>";
            if (t.accuracy != null) {
                gps += " <span>(+/- " + Math.round(t.accuracy) + "m)</span>";
            }
        }

        return (
            '<div class="transcript-card">' +
                '<div class="transcript-meta">' +
                    "<span>" + escapeHtml(time) + "</span>" +
                    (gps ? gps : "") +
                "</div>" +
                '<div class="transcript-text">' + escapeHtml(displayText) + "</div>" +
            "</div>"
        );
    }

    // --------------- Helpers ---------------
    function formatTimestamp(ms) {
        var d = new Date(ms);
        return d.toLocaleDateString(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric"
        }) + " " + d.toLocaleTimeString(undefined, {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit"
        });
    }

    function escapeHtml(str) {
        var div = document.createElement("div");
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

})();
