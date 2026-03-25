(function () {
    "use strict";

    // --------------- Firebase Config ---------------
    var firebaseConfig = {
        apiKey: "AIzaSyB8yR8BFV_aCEIavdVGXDBvEce61GzQWVM",
        authDomain: "selective-speaker-demo.firebaseapp.com",
        projectId: "selective-speaker-demo",
        storageBucket: "selective-speaker-demo.firebasestorage.app",
        messagingSenderId: "742529576115",
        appId: "1:742529576115:web:1e3d1a9fe8d4cae6d6d4c6"
    };

    // --------------- Firebase SDK (compat CDN) ---------------
    var FIREBASE_VERSION = "10.12.0";
    var sdkScripts = [
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-app-compat.js",
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-auth-compat.js",
        "https://www.gstatic.com/firebasejs/" + FIREBASE_VERSION + "/firebase-firestore-compat.js"
    ];

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
            loadScriptsSequentially(list.slice(1), done);
        });
    }

    loadScriptsSequentially(sdkScripts, boot);

    // --------------- DOM refs ---------------
    var loginScreen = document.getElementById("login-screen");
    var dashboardScreen = document.getElementById("dashboard-screen");
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

        document.getElementById("google-login-btn").addEventListener("click", handleGoogleLogin);
        logoutBtn.addEventListener("click", handleLogout);
        filterBtn.addEventListener("click", function () { loadTranscripts(); });
        clearFilterBtn.addEventListener("click", function () {
            filterStart.value = "";
            filterEnd.value = "";
            loadTranscripts();
        });
    }

    // --------------- Auth ---------------
    function handleGoogleLogin() {
        loginError.hidden = true;
        var provider = new firebase.auth.GoogleAuthProvider();
        auth.signInWithPopup(provider).catch(function (err) {
            loginError.innerHTML = '<i data-lucide="alert-circle"></i> ' + friendlyAuthError(err.code);
            loginError.hidden = false;
            lucide.createIcons();
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
            var endMs = new Date(endDate).getTime() + 86400000;
            query = query.where("timestamp", "<=", endMs);
        }

        query.get().then(function (snapshot) {
            renderTranscripts(snapshot.docs.map(function (doc) {
                return Object.assign({ id: doc.id }, doc.data());
            }));
        }).catch(function (err) {
            console.error("Failed to load transcripts", err);
            transcriptList.innerHTML = `
                <div class="empty-state">
                    <i data-lucide="alert-triangle"></i>
                    <p>Failed to load transcripts.</p>
                </div>
            `;
            lucide.createIcons();
        });
    }

    // --------------- Rendering ---------------
    function renderTranscripts(items) {
        if (items.length === 0) {
            transcriptList.innerHTML = `
                <div class="empty-state">
                    <i data-lucide="inbox"></i>
                    <p>No transcripts found for this period.</p>
                </div>
            `;
            lucide.createIcons();
            return;
        }

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
            html += '<div class="session-header">Session ID: ' + escapeHtml(sid.substring(0, 16)) + "...</div>";
            groups[sid].forEach(function (t) {
                html += renderCard(t);
            });
            html += "</div>";
        });

        transcriptList.innerHTML = html;
        // Re-initialize icons for newly added elements
        if (window.lucide) {
            lucide.createIcons();
        }
    }

    function renderCard(t) {
        var displayText = t.correctedText || t.text || "(empty)";
        var time = t.timestamp ? formatTimestamp(t.timestamp) : "—";
        var gps = "";
        if (t.latitude != null && t.longitude != null) {
            var lat = Number(t.latitude).toFixed(6);
            var lng = Number(t.longitude).toFixed(6);
            var mapsUrl = "https://www.google.com/maps?q=" + lat + "," + lng;
            gps = `
                <a class="gps-link" href="${mapsUrl}" target="_blank" rel="noopener">
                    <i data-lucide="map-pin"></i>
                    ${lat}, ${lng}
                </a>
            `;
        }

        return `
            <div class="transcript-card">
                <div class="transcript-meta">
                    <div class="time-stamp">
                        <i data-lucide="clock"></i>
                        <span>${escapeHtml(time)}</span>
                    </div>
                    ${gps}
                </div>
                <div class="transcript-text">${escapeHtml(displayText)}</div>
            </div>
        `;
    }

    // --------------- Helpers ---------------
    function formatTimestamp(ms) {
        var d = new Date(ms);
        return d.toLocaleDateString(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric"
        }) + " • " + d.toLocaleTimeString(undefined, {
            hour: "2-digit",
            minute: "2-digit"
        });
    }

    function escapeHtml(str) {
        var div = document.createElement("div");
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    // --------------- Test Panel ---------------
    var testPanel = document.getElementById("test-panel");
    var testSessionCounter = 0;
    var activeTestSession = null;

    // Toggle with Ctrl+Shift+T
    document.addEventListener("keydown", function (e) {
        if (e.ctrlKey && e.shiftKey && e.key === "T") {
            e.preventDefault();
            testPanel.hidden = !testPanel.hidden;
            if (!testPanel.hidden && window.lucide) lucide.createIcons();
        }
    });

    document.getElementById("close-test-panel").addEventListener("click", function () {
        testPanel.hidden = true;
    });

    var SAMPLE_TRANSCRIPTS = [
        "Officer approached the vehicle and made contact with the driver. License and registration were requested.",
        "Suspect was observed exiting the building at approximately fourteen thirty hours carrying a dark backpack.",
        "Dispatch confirmed a ten-fifty-one at the intersection of Main and Broadway. Units responding code three.",
        "Witness stated they heard two loud noises coming from the alley behind the convenience store at around nine PM.",
        "Traffic stop conducted on a silver sedan traveling eastbound. Driver was cooperative and no violations found.",
        "Perimeter established around the two hundred block. K-nine unit requested for a building search.",
        "Individual identified via state ID. No outstanding warrants. Released with a verbal warning.",
        "Responding to a noise complaint at the apartment complex on Elm Street. Resident agreed to lower the volume.",
        "Footage reviewed from the body camera shows the interaction lasted approximately four minutes.",
        "Backup unit arrived on scene. Situation de-escalated without incident. Report filed under case number seven-four-two."
    ];

    var SAMPLE_LOCATIONS = [
        { lat: 37.7749, lng: -122.4194 },  // San Francisco
        { lat: 34.0522, lng: -118.2437 },  // Los Angeles
        { lat: 40.7128, lng: -74.0060 },   // New York
        { lat: 41.8781, lng: -87.6298 },   // Chicago
        { lat: 29.7604, lng: -95.3698 },   // Houston
        { lat: 33.4484, lng: -112.0740 },  // Phoenix
    ];

    function generateSessionId() {
        if (!activeTestSession || testSessionCounter % 5 === 0) {
            activeTestSession = "test-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
        }
        testSessionCounter++;
        return activeTestSession;
    }

    function pushTranscript(data) {
        if (!db) return Promise.reject("Firestore not initialized");
        return db.collection("transcripts").add(data);
    }

    document.getElementById("test-push-btn").addEventListener("click", function () {
        var text = document.getElementById("test-text").value.trim();
        if (!text) { text = SAMPLE_TRANSCRIPTS[Math.floor(Math.random() * SAMPLE_TRANSCRIPTS.length)]; }
        var lat = parseFloat(document.getElementById("test-lat").value) || 37.7749;
        var lng = parseFloat(document.getElementById("test-lng").value) || -122.4194;
        var session = document.getElementById("test-session").value.trim() || generateSessionId();
        var corrected = document.getElementById("test-corrected").value.trim() || null;

        var doc = {
            text: text,
            correctedText: corrected,
            latitude: lat,
            longitude: lng,
            accuracy: Math.floor(Math.random() * 15) + 3,
            timestamp: Date.now(),
            sessionId: session,
            userId: auth.currentUser ? auth.currentUser.uid : "test-user"
        };

        var statusEl = document.getElementById("test-status");
        statusEl.textContent = "Pushing...";
        pushTranscript(doc).then(function () {
            statusEl.textContent = "Pushed! Refreshing...";
            loadTranscripts();
            setTimeout(function () { statusEl.textContent = ""; }, 2000);
        }).catch(function (err) {
            statusEl.textContent = "Error: " + err.message;
        });
    });

    document.getElementById("test-batch-btn").addEventListener("click", function () {
        var statusEl = document.getElementById("test-status");
        statusEl.textContent = "Pushing 5 transcripts...";
        var promises = [];
        var session = generateSessionId();

        for (var i = 0; i < 5; i++) {
            var loc = SAMPLE_LOCATIONS[Math.floor(Math.random() * SAMPLE_LOCATIONS.length)];
            var text = SAMPLE_TRANSCRIPTS[Math.floor(Math.random() * SAMPLE_TRANSCRIPTS.length)];
            // Stagger timestamps so they sort properly
            var doc = {
                text: text,
                correctedText: Math.random() > 0.5 ? text.replace(/approximately/g, "approx.") : null,
                latitude: loc.lat + (Math.random() - 0.5) * 0.01,
                longitude: loc.lng + (Math.random() - 0.5) * 0.01,
                accuracy: Math.floor(Math.random() * 20) + 3,
                timestamp: Date.now() - (i * 30000),
                sessionId: session,
                userId: auth.currentUser ? auth.currentUser.uid : "test-user"
            };
            promises.push(pushTranscript(doc));
        }

        Promise.all(promises).then(function () {
            statusEl.textContent = "5 transcripts pushed! Refreshing...";
            loadTranscripts();
            setTimeout(function () { statusEl.textContent = ""; }, 2000);
        }).catch(function (err) {
            statusEl.textContent = "Error: " + err.message;
        });
    });

    document.getElementById("test-clear-btn").addEventListener("click", function () {
        var statusEl = document.getElementById("test-status");
        if (!confirm("Delete ALL transcripts from Firestore? This cannot be undone.")) return;
        statusEl.textContent = "Deleting all transcripts...";
        db.collection("transcripts").get().then(function (snapshot) {
            var batch = db.batch();
            snapshot.docs.forEach(function (doc) { batch.delete(doc.ref); });
            return batch.commit();
        }).then(function () {
            statusEl.textContent = "All transcripts deleted.";
            loadTranscripts();
            setTimeout(function () { statusEl.textContent = ""; }, 2000);
        }).catch(function (err) {
            statusEl.textContent = "Error: " + err.message;
        });
    });

})();
