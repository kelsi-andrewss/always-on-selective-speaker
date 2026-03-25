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
        initTestPanel();
        initRecordPanel();
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

    function initTestPanel() {
        document.getElementById("toggle-test-btn").addEventListener("click", function () {
            testPanel.hidden = !testPanel.hidden;
            if (!testPanel.hidden && window.lucide) lucide.createIcons();
        });

        document.getElementById("close-test-panel").addEventListener("click", function () {
            testPanel.hidden = true;
        });

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

    } // end initTestPanel

    // --------------- Record Panel ---------------
    function initRecordPanel() {
        var recordPanel = document.getElementById("record-panel");
        var startBtn = document.getElementById("record-start-btn");
        var stopBtn = document.getElementById("record-stop-btn");
        var statusEl = document.getElementById("record-status");
        var gpsDisplay = document.getElementById("record-gps-display");
        var previewEl = document.getElementById("transcript-preview");
        var previewText = previewEl.querySelector(".transcript-preview-text");
        var saveBtn = document.getElementById("save-transcript-btn");
        var modeBrowserBtn = document.getElementById("record-mode-browser");
        var modeCloudBtn = document.getElementById("record-mode-cloud");
        var apiKeyInput = document.getElementById("api-key-input");
        var settingsPanel = document.getElementById("record-settings");
        var settingsBtn = document.getElementById("record-settings-btn");
        var meterFill = document.querySelector("#audio-level-meter .audio-level-fill");

        var currentMode = "browser"; // "browser" | "cloud"
        var audioContext = null;
        var analyserNode = null;
        var micStream = null;
        var meterRaf = null;
        var mediaRecorder = null;
        var recordedChunks = [];
        var speechRecognition = null;
        var currentTranscript = "";
        var interimTranscript = "";
        var currentGps = null;
        var recordSessionId = null;
        var wordsData = null;

        // Restore API key from localStorage
        var storedKey = localStorage.getItem("assemblyai_api_key");
        if (storedKey) apiKeyInput.value = storedKey;

        apiKeyInput.addEventListener("change", function () {
            localStorage.setItem("assemblyai_api_key", apiKeyInput.value.trim());
        });

        // Toggle panel
        document.getElementById("toggle-record-btn").addEventListener("click", function () {
            recordPanel.hidden = !recordPanel.hidden;
            if (!recordPanel.hidden && window.lucide) lucide.createIcons();
        });
        document.getElementById("close-record-panel").addEventListener("click", function () {
            recordPanel.hidden = true;
        });

        // Settings toggle
        settingsBtn.addEventListener("click", function () {
            settingsPanel.hidden = !settingsPanel.hidden;
        });

        // Mode toggle
        modeBrowserBtn.addEventListener("click", function () {
            currentMode = "browser";
            modeBrowserBtn.classList.add("active");
            modeCloudBtn.classList.remove("active");
            settingsPanel.hidden = true;
        });
        modeCloudBtn.addEventListener("click", function () {
            currentMode = "cloud";
            modeCloudBtn.classList.add("active");
            modeBrowserBtn.classList.remove("active");
            // Show settings so user can enter key
            settingsPanel.hidden = false;
        });

        // ----------- Audio Level Meter -----------
        function startMeter(stream) {
            audioContext = new (window.AudioContext || window.webkitAudioContext)();
            var source = audioContext.createMediaStreamSource(stream);
            analyserNode = audioContext.createAnalyser();
            analyserNode.fftSize = 256;
            source.connect(analyserNode);

            var dataArray = new Uint8Array(analyserNode.frequencyBinCount);
            function tick() {
                analyserNode.getByteFrequencyData(dataArray);
                var sum = 0;
                for (var i = 0; i < dataArray.length; i++) sum += dataArray[i];
                var avg = sum / dataArray.length;
                var pct = Math.min(100, (avg / 128) * 100);
                meterFill.style.width = pct + "%";
                meterRaf = requestAnimationFrame(tick);
            }
            tick();
        }

        function stopMeter() {
            if (meterRaf) { cancelAnimationFrame(meterRaf); meterRaf = null; }
            meterFill.style.width = "0%";
            if (audioContext) { audioContext.close(); audioContext = null; }
        }

        // ----------- GPS -----------
        function fetchGps() {
            gpsDisplay.textContent = "Fetching GPS...";
            if (!navigator.geolocation) {
                gpsDisplay.textContent = "Geolocation not available";
                return;
            }
            navigator.geolocation.getCurrentPosition(
                function (pos) {
                    currentGps = {
                        latitude: pos.coords.latitude,
                        longitude: pos.coords.longitude,
                        accuracy: pos.coords.accuracy
                    };
                    gpsDisplay.textContent = pos.coords.latitude.toFixed(6) + ", " + pos.coords.longitude.toFixed(6) + " (±" + Math.round(pos.coords.accuracy) + "m)";
                },
                function (err) {
                    gpsDisplay.textContent = "GPS error: " + err.message;
                    currentGps = null;
                }
            );
        }

        // ----------- WAV Encoding -----------
        function encodeWAV(samples, sampleRate) {
            var numChannels = 1;
            var bytesPerSample = 2;
            var blockAlign = numChannels * bytesPerSample;
            var byteRate = sampleRate * blockAlign;
            var dataLength = samples.length * bytesPerSample;
            var buffer = new ArrayBuffer(44 + dataLength);
            var view = new DataView(buffer);

            function writeString(offset, str) {
                for (var i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
            }

            writeString(0, "RIFF");
            view.setUint32(4, 36 + dataLength, true);
            writeString(8, "WAVE");
            writeString(12, "fmt ");
            view.setUint32(16, 16, true);
            view.setUint16(20, 1, true); // PCM
            view.setUint16(22, numChannels, true);
            view.setUint32(24, sampleRate, true);
            view.setUint32(28, byteRate, true);
            view.setUint16(32, blockAlign, true);
            view.setUint16(34, bytesPerSample * 8, true);
            writeString(36, "data");
            view.setUint32(40, dataLength, true);

            var offset = 44;
            for (var i = 0; i < samples.length; i++) {
                var s = Math.max(-1, Math.min(1, samples[i]));
                view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
                offset += 2;
            }

            return new Blob([view], { type: "audio/wav" });
        }

        // ----------- Browser STT (Web Speech API) -----------
        function startBrowserSTT() {
            var SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (!SpeechRecognition) {
                statusEl.textContent = "Web Speech API not supported in this browser. Try Chrome.";
                return false;
            }

            speechRecognition = new SpeechRecognition();
            speechRecognition.continuous = true;
            speechRecognition.interimResults = true;
            speechRecognition.lang = "en-US";

            currentTranscript = "";
            interimTranscript = "";

            speechRecognition.onresult = function (event) {
                var final = "";
                var interim = "";
                for (var i = 0; i < event.results.length; i++) {
                    if (event.results[i].isFinal) {
                        final += event.results[i][0].transcript;
                    } else {
                        interim += event.results[i][0].transcript;
                    }
                }
                currentTranscript = final;
                interimTranscript = interim;

                previewEl.hidden = false;
                previewText.textContent = currentTranscript + (interimTranscript ? " " + interimTranscript : "");
            };

            speechRecognition.onerror = function (event) {
                if (event.error !== "no-speech") {
                    statusEl.textContent = "Speech error: " + event.error;
                }
            };

            speechRecognition.onend = function () {
                // Auto-restart if still recording (browser may stop it)
                if (!stopBtn.hidden) {
                    try { speechRecognition.start(); } catch (e) { /* already started */ }
                }
            };

            speechRecognition.start();
            return true;
        }

        function stopBrowserSTT() {
            if (speechRecognition) {
                speechRecognition.onend = null; // prevent auto-restart
                speechRecognition.stop();
                speechRecognition = null;
            }
        }

        // ----------- Cloud STT (AssemblyAI) -----------
        function startCloudRecording(stream) {
            recordedChunks = [];
            // Use a ScriptProcessor to capture raw PCM for WAV encoding
            var scriptNode = audioContext.createScriptProcessor(4096, 1, 1);
            var source = audioContext.createMediaStreamSource(stream);
            source.connect(scriptNode);
            scriptNode.connect(audioContext.destination);

            var allSamples = [];
            scriptNode.onaudioprocess = function (e) {
                var channelData = e.inputBuffer.getChannelData(0);
                allSamples.push(new Float32Array(channelData));
            };

            // Store refs for cleanup
            mediaRecorder = { scriptNode: scriptNode, source: source, allSamples: allSamples };
        }

        function stopCloudRecording() {
            return new Promise(function (resolve, reject) {
                if (!mediaRecorder) { reject(new Error("No recording")); return; }

                var allSamples = mediaRecorder.allSamples;
                mediaRecorder.scriptNode.disconnect();
                mediaRecorder.source.disconnect();

                // Concatenate all sample buffers
                var totalLength = 0;
                for (var i = 0; i < allSamples.length; i++) totalLength += allSamples[i].length;
                var merged = new Float32Array(totalLength);
                var offset = 0;
                for (var i = 0; i < allSamples.length; i++) {
                    merged.set(allSamples[i], offset);
                    offset += allSamples[i].length;
                }

                var sampleRate = audioContext ? audioContext.sampleRate : 16000;
                var wavBlob = encodeWAV(merged, sampleRate);
                mediaRecorder = null;
                resolve(wavBlob);
            });
        }

        function uploadToAssemblyAI(wavBlob) {
            var apiKey = apiKeyInput.value.trim();
            if (!apiKey) return Promise.reject(new Error("No API key. Open settings and paste your AssemblyAI key."));

            statusEl.textContent = "Uploading audio...";

            return fetch("https://api.assemblyai.com/v2/upload", {
                method: "POST",
                headers: {
                    "Authorization": apiKey,
                    "Content-Type": "application/octet-stream"
                },
                body: wavBlob
            })
            .then(function (resp) {
                if (!resp.ok) throw new Error("Upload failed: " + resp.status);
                return resp.json();
            })
            .then(function (data) {
                statusEl.textContent = "Transcribing...";
                return fetch("https://api.assemblyai.com/v2/transcript", {
                    method: "POST",
                    headers: {
                        "Authorization": apiKey,
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify({ audio_url: data.upload_url, language_code: "en" })
                });
            })
            .then(function (resp) {
                if (!resp.ok) throw new Error("Transcript request failed: " + resp.status);
                return resp.json();
            })
            .then(function (data) {
                return pollTranscript(data.id, apiKey);
            });
        }

        function pollTranscript(transcriptId, apiKey) {
            return new Promise(function (resolve, reject) {
                function check() {
                    fetch("https://api.assemblyai.com/v2/transcript/" + transcriptId, {
                        headers: { "Authorization": apiKey }
                    })
                    .then(function (resp) { return resp.json(); })
                    .then(function (data) {
                        if (data.status === "completed") {
                            resolve(data);
                        } else if (data.status === "error") {
                            reject(new Error("Transcription error: " + (data.error || "unknown")));
                        } else {
                            statusEl.textContent = "Transcribing... (" + data.status + ")";
                            setTimeout(check, 3000);
                        }
                    })
                    .catch(reject);
                }
                check();
            });
        }

        // ----------- Session Management -----------
        function getRecordSessionId() {
            if (!recordSessionId) {
                recordSessionId = "rec-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
            }
            return recordSessionId;
        }

        // ----------- Start / Stop -----------
        startBtn.addEventListener("click", function () {
            currentTranscript = "";
            interimTranscript = "";
            wordsData = null;
            previewEl.hidden = true;
            previewText.textContent = "";
            saveBtn.hidden = true;
            statusEl.textContent = "Requesting microphone...";

            var constraints = { audio: { channelCount: 1, sampleRate: { ideal: 16000 } } };

            navigator.mediaDevices.getUserMedia(constraints)
                .then(function (stream) {
                    micStream = stream;
                    startMeter(stream);
                    fetchGps();
                    statusEl.textContent = "Recording...";
                    startBtn.hidden = true;
                    stopBtn.hidden = false;
                    recordPanel.classList.add("is-recording");

                    if (currentMode === "browser") {
                        startBrowserSTT();
                    } else {
                        startCloudRecording(stream);
                    }
                })
                .catch(function (err) {
                    statusEl.textContent = "Microphone error: " + err.message;
                });
        });

        stopBtn.addEventListener("click", function () {
            stopBtn.hidden = true;
            startBtn.hidden = false;
            recordPanel.classList.remove("is-recording");

            if (currentMode === "browser") {
                stopBrowserSTT();
                // Stop mic and meter after a small delay to let final results come in
                setTimeout(function () {
                    stopMicAndMeter();
                    if (currentTranscript.trim()) {
                        statusEl.textContent = "Done! Review transcript and save.";
                        saveBtn.hidden = false;
                    } else {
                        statusEl.textContent = "No speech detected.";
                    }
                }, 500);
            } else {
                statusEl.textContent = "Processing audio...";
                stopCloudRecording()
                    .then(function (wavBlob) {
                        stopMicAndMeter();
                        return uploadToAssemblyAI(wavBlob);
                    })
                    .then(function (result) {
                        currentTranscript = result.text || "";
                        wordsData = result.words || null;
                        previewEl.hidden = false;
                        previewText.textContent = currentTranscript;
                        statusEl.textContent = "Done! Review transcript and save.";
                        saveBtn.hidden = false;
                    })
                    .catch(function (err) {
                        stopMicAndMeter();
                        statusEl.textContent = "Error: " + err.message;
                        // If it looks like CORS, show a helpful note
                        if (err.message && (err.message.indexOf("Failed to fetch") >= 0 || err.message.indexOf("NetworkError") >= 0)) {
                            statusEl.textContent += " (Likely CORS blocked. Install a CORS browser extension or use Browser STT mode.)";
                        }
                    });
            }
        });

        function stopMicAndMeter() {
            stopMeter();
            if (micStream) {
                micStream.getTracks().forEach(function (t) { t.stop(); });
                micStream = null;
            }
        }

        // ----------- Save to Firestore -----------
        saveBtn.addEventListener("click", function () {
            if (!currentTranscript.trim()) { statusEl.textContent = "Nothing to save."; return; }
            if (!db) { statusEl.textContent = "Firestore not initialized."; return; }

            var doc = {
                text: currentTranscript.trim(),
                timestamp: Date.now(),
                sessionId: getRecordSessionId(),
                userId: auth.currentUser ? auth.currentUser.uid : "web-recorder"
            };

            if (wordsData) doc.words = wordsData;

            if (currentGps) {
                doc.latitude = currentGps.latitude;
                doc.longitude = currentGps.longitude;
                doc.accuracy = currentGps.accuracy;
            }

            saveBtn.disabled = true;
            statusEl.textContent = "Saving...";
            db.collection("transcripts").add(doc).then(function () {
                statusEl.textContent = "Saved! Refreshing list...";
                saveBtn.hidden = true;
                saveBtn.disabled = false;
                loadTranscripts();
            }).catch(function (err) {
                statusEl.textContent = "Save failed: " + err.message;
                saveBtn.disabled = false;
            });
        });

    } // end initRecordPanel

})();
