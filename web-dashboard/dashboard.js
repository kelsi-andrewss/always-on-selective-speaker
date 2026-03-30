(function () {
    "use strict";

    console.log("Dashboard script initializing...");

    // --------------- Firebase Config ---------------
    var firebaseConfig = {
        apiKey: "AIzaSyB8yR8BFV_aCEIavdVGXDBvEce61GzQWVM",
        authDomain: "selective-speaker-demo.firebaseapp.com",
        projectId: "selective-speaker-demo",
        storageBucket: "selective-speaker-demo.firebasestorage.app",
        messagingSenderId: "742529576115",
        appId: "1:742529576115:web:1e3d1a9fe8d4cae6d6d4c6"
    };

    // --------------- DOM refs ---------------
    var loginScreen, dashboardScreen, setupScreen, loginError, userEmailSpan, logoutBtn, transcriptList;
    var filterStart, filterEnd, filterBtn, clearFilterBtn;
    var setupUserEmail, createOrgName, createOrgBtn, joinInviteCode, joinOrgBtn, setupError, setupLoading, orgLabelText;

    var db = null;
    var auth = null;
    var refreshTimer = null;
    var REFRESH_INTERVAL_MS = 30000;

    // --------------- Access Level Constants ---------------
    var ACCESS_LEVELS = { OFFICER: 10, SUPERVISOR: 20, ADMIN: 30, OWNER: 40 };
    var LEVEL_NAMES = { 10: "Officer", 20: "Supervisor", 30: "Admin", 40: "Owner" };

    // --------------- Org State ---------------
    var currentOrgId = null;
    var currentUserLevel = null;
    var assignedOfficers = [];
    var currentOrgName = null;
    var memberCache = new Map();
    var selectedMemberId = null;

    function hasPermission(requiredLevel) {
        return currentUserLevel >= requiredLevel;
    }

    function getLevelName(level) {
        return LEVEL_NAMES[level] || "Unknown";
    }

    function getInitials(name) {
        if (!name) return "?";
        var parts = name.split(/\s+/);
        if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
        return name.substring(0, 2).toUpperCase();
    }

    function escapeHtml(str) {
        var div = document.createElement("div");
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    function getHashOrgId() {
        var hash = window.location.hash || "";
        if (hash.indexOf("#org/") === 0) return hash.substring(5);
        return null;
    }

    function setHashOrgId(orgId) {
        if (orgId) window.location.hash = "org/" + orgId;
        else window.location.hash = "";
    }

    // --------------- Boot ---------------
    function boot() {
        console.log("Booting dashboard...");
        
        // Initialize DOM refs
        loginScreen = document.getElementById("login-screen");
        dashboardScreen = document.getElementById("dashboard-screen");
        setupScreen = document.getElementById("setup-screen");
        loginError = document.getElementById("login-error");
        userEmailSpan = document.getElementById("user-email");
        logoutBtn = document.getElementById("logout-btn");
        transcriptList = document.getElementById("transcript-list");
        filterStart = document.getElementById("filter-start");
        filterEnd = document.getElementById("filter-end");
        filterBtn = document.getElementById("filter-btn");
        clearFilterBtn = document.getElementById("clear-filter-btn");

        setupUserEmail = document.getElementById("setup-user-email");
        createOrgName = document.getElementById("create-org-name");
        createOrgBtn = document.getElementById("create-org-btn");
        joinInviteCode = document.getElementById("join-invite-code");
        joinOrgBtn = document.getElementById("join-org-btn");
        setupError = document.getElementById("setup-error");
        setupLoading = document.getElementById("setup-loading");
        orgLabelText = document.getElementById("org-label-text");

        if (typeof firebase === 'undefined') {
            console.error("Firebase SDK not found!");
            return;
        }

        firebase.initializeApp(firebaseConfig);
        auth = firebase.auth();
        db = firebase.firestore();

        auth.onAuthStateChanged(function (user) {
            console.log("Auth state changed:", user ? user.email : "No user");
            if (user) {
                if (userEmailSpan) userEmailSpan.textContent = user.email;
                
                var targetOrgId = getHashOrgId();
                if (targetOrgId) {
                    enterOrg(user.uid, targetOrgId);
                } else {
                    resolveOrgMembership(user.uid).then(function (membership) {
                        if (membership) {
                            setOrgContext(membership.orgId, membership.accessLevel, membership.assignedOfficers, membership.orgName);
                            showDashboard(auth.currentUser);
                        } else {
                            showSetupScreen(user);
                        }
                    }).catch(function () {
                        showSetupScreen(user);
                    });
                }
            } else {
                showLogin();
            }
        });

        var googleBtn = document.getElementById("google-login-btn");
        if (googleBtn) {
            console.log("Attaching listener to google-login-btn");
            googleBtn.addEventListener("click", function() {
                console.log("Google Login clicked");
                handleGoogleLogin();
            });
        } else {
            console.error("Google Login button not found in DOM!");
        }

        if (logoutBtn) logoutBtn.addEventListener("click", handleLogout);
        
        var backToHome = document.getElementById("back-to-home-btn");
        if (backToHome) {
            backToHome.addEventListener("click", function () {
                showSetupScreen(auth.currentUser);
            });
        }

        if (filterBtn) filterBtn.addEventListener("click", function () { loadTranscripts(); });
        if (clearFilterBtn) {
            clearFilterBtn.addEventListener("click", function () {
                if (filterStart) filterStart.value = "";
                if (filterEnd) filterEnd.value = "";
                loadTranscripts();
            });
        }

        initSetupScreen();
        initTestPanel();
        initRecordPanel();

        // Settings modal
        var settingsBtn = document.getElementById("settings-btn");
        if (settingsBtn) {
            settingsBtn.addEventListener("click", function () {
                toggleSettingsModal(true);
            });
        }
        var settingsCloseBtn = document.getElementById("settings-close-btn");
        if (settingsCloseBtn) {
            settingsCloseBtn.addEventListener("click", function () {
                toggleSettingsModal(false);
            });
        }
        var settingsOverlay = document.getElementById("settings-overlay");
        if (settingsOverlay) {
            settingsOverlay.addEventListener("click", function (e) {
                if (e.target === e.currentTarget) toggleSettingsModal(false);
            });
        }
        var genInviteBtn = document.getElementById("generate-invite-btn");
        if (genInviteBtn) {
            genInviteBtn.addEventListener("click", function () {
                var roleSel = document.getElementById("invite-role-select");
                var level = roleSel ? parseInt(roleSel.value, 10) : 10;
                generateInvite(level);
            });
        }
    }

    // --------------- Auth ---------------
    function handleGoogleLogin() {
        console.log("handleGoogleLogin executing...");
        if (loginError) loginError.hidden = true;
        var provider = new firebase.auth.GoogleAuthProvider();
        auth.signInWithPopup(provider).catch(function (err) {
            console.error("Login error:", err);
            if (loginError) {
                loginError.innerHTML = '<i data-lucide="alert-circle"></i> ' + friendlyAuthError(err.code);
                loginError.hidden = false;
            }
            if (window.lucide) lucide.createIcons();
        });
    }

    function handleLogout() {
        currentOrgId = null;
        currentUserLevel = null;
        assignedOfficers = [];
        currentOrgName = null;
        selectedMemberId = null;
        setHashOrgId(null);
        memberCache.clear();
        if (orgLabelText) orgLabelText.textContent = "Frontier Audio";
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
        if (loginScreen) loginScreen.hidden = false;
        if (setupScreen) setupScreen.hidden = true;
        if (dashboardScreen) dashboardScreen.hidden = true;
        stopAutoRefresh();
    }

    function showSetupScreen(user) {
        setHashOrgId(null);
        if (loginScreen) loginScreen.hidden = true;
        if (setupScreen) setupScreen.hidden = false;
        if (dashboardScreen) dashboardScreen.hidden = true;
        
        var setupDisplayName = document.getElementById("setup-user-display-name");
        var setupInitials = document.getElementById("user-avatar-initials");
        if (setupDisplayName) setupDisplayName.textContent = user.displayName || user.email.split('@')[0];
        if (setupInitials) setupInitials.textContent = getInitials(user.displayName || user.email);
        
        var heroTitle = document.getElementById("home-hero-title");
        if (heroTitle) heroTitle.textContent = "Welcome back, " + (user.displayName || user.email.split('@')[0]) + ".";

        if (setupError) setupError.hidden = true;
        if (setupLoading) setupLoading.hidden = true;
        stopAutoRefresh();
        loadUserOrgs(user.uid);
    }

    function showDashboard(user) {
        if (loginScreen) loginScreen.hidden = true;
        if (setupScreen) setupScreen.hidden = true;
        if (dashboardScreen) dashboardScreen.hidden = false;

        var userEmail = document.getElementById("user-email");
        if (userEmail) userEmail.textContent = user.email;

        var recordBtn = document.getElementById("toggle-record-btn");
        if (recordBtn) recordBtn.hidden = (currentUserLevel !== ACCESS_LEVELS.OFFICER);

        var sidebar = document.getElementById("members-sidebar");
        if (sidebar) sidebar.hidden = !hasPermission(ACCESS_LEVELS.SUPERVISOR);

        loadMemberCache().then(function () {
            if (hasPermission(ACCESS_LEVELS.SUPERVISOR)) {
                renderMembersSidebar();
            }
            loadTranscripts();
        });
        startAutoRefresh();
    }

    // --------------- Org Membership ---------------
    function resolveOrgMembership(uid) {
        return db.collection("users").doc(uid).get()
            .then(function (userDoc) {
                var orgIds = (userDoc.exists && userDoc.data().orgs) ? userDoc.data().orgs : [];
                if (orgIds.length === 0) return null;
                var orgId = orgIds[0];
                return db.collection("orgs").doc(orgId).collection("members").doc(uid).get()
                    .then(function (memberDoc) {
                        if (!memberDoc.exists) return null;
                        return buildMembership(memberDoc, orgId);
                    });
            })
            .catch(function (err) {
                console.warn("Org membership resolution failed:", err.message);
                return null;
            });
    }

    function buildMembership(memberDoc, orgId) {
        var data = memberDoc.data();
        var level = data.accessLevel;
        if (level === undefined || level === null) {
            var roleMap = { "owner": 40, "admin": 40, "supervisor": 20, "officer": 10 };
            level = roleMap[(data.role || "").toLowerCase()] || 10;
        }
        return db.collection("orgs").doc(orgId).get().then(function (orgDoc) {
            return {
                orgId: orgId,
                accessLevel: level,
                assignedOfficers: data.assignedOfficers || [],
                orgName: orgDoc.exists ? orgDoc.data().name : orgId
            };
        });
    }

    function setOrgContext(orgId, accessLevel, officers, orgName) {
        currentOrgId = orgId;
        currentUserLevel = accessLevel;
        assignedOfficers = officers || [];
        currentOrgName = orgName || orgId;
        if (orgLabelText) orgLabelText.textContent = currentOrgName;
        setHashOrgId(orgId);
    }

    function enterOrg(uid, orgId) {
        if (setupLoading) setupLoading.hidden = false;
        db.collection("orgs").doc(orgId).collection("members").doc(uid).get()
            .then(function (memberDoc) {
                if (!memberDoc.exists) {
                    if (setupError) {
                        setupError.textContent = "You are no longer a member of this organization.";
                        setupError.hidden = false;
                    }
                    if (setupLoading) setupLoading.hidden = true;
                    return;
                }
                return buildMembership(memberDoc, orgId).then(function (membership) {
                    setOrgContext(membership.orgId, membership.accessLevel, membership.assignedOfficers, membership.orgName);
                    showDashboard(auth.currentUser);
                });
            })
            .catch(function (err) {
                if (setupError) {
                    setupError.textContent = "Error entering organization: " + err.message;
                    setupError.hidden = false;
                }
                if (setupLoading) setupLoading.hidden = true;
            });
    }

    // --------------- Organizations (Setup) ---------------
    function initSetupScreen() {
        var yoursTab = document.querySelector('[data-tab="yours"]');
        var exploreTab = document.querySelector('[data-tab="explore"]');
        var createTab = document.querySelector('[data-tab="create"]');
        var joinTab = document.querySelector('[data-tab="join"]');

        function switchTab(tabId) {
            document.querySelectorAll(".nav-item").forEach(function (n) { n.classList.remove("active"); });
            document.querySelectorAll(".setup-tab-content").forEach(function (c) { c.classList.remove("active"); });
            var activeNav = document.querySelector('[data-tab="' + tabId + '"]');
            var activeContent = document.getElementById(tabId + "-org-form") || document.getElementById(tabId + "-tab-form");
            if (activeNav) activeNav.classList.add("active");
            if (activeContent) activeContent.classList.add("active");
            
            var label = document.getElementById("current-tab-label");
            if (label && activeNav) label.textContent = activeNav.textContent.trim();
        }

        if (yoursTab) yoursTab.onclick = function () { switchTab("yours"); loadUserOrgs(auth.currentUser.uid); };
        if (exploreTab) exploreTab.onclick = function () { switchTab("explore"); };
        if (createTab) createTab.onclick = function () { switchTab("create"); };
        if (joinTab) joinTab.onclick = function () { switchTab("join"); };

        if (createOrgBtn) {
            createOrgBtn.onclick = function () {
                var name = createOrgName.value.trim();
                var desc = document.getElementById("create-org-desc").value.trim();
                var vis = document.getElementById("create-org-visibility").value;
                if (!name) return alert("Organization name required");
                handleCreateOrg(name, desc, vis);
            };
        }

        if (joinOrgBtn) {
            joinOrgBtn.onclick = function () {
                var code = joinInviteCode.value.trim();
                if (!code) return alert("Invite code required");
                handleJoinOrg(code);
            };
        }

        var yoursList = document.getElementById("your-orgs-list");
        if (yoursList) {
            yoursList.addEventListener("click", function (e) {
                var row = e.target.closest(".org-item-row");
                if (!row) return;
                var orgId = row.dataset.orgId;
                if (orgId) {
                    row.style.opacity = "0.5";
                    var iconWrapper = row.querySelector(".icon-wrapper");
                    if (iconWrapper) iconWrapper.innerHTML = '<i data-lucide="loader-2" class="spinner"></i>';
                    if (window.lucide) lucide.createIcons();
                    enterOrg(auth.currentUser.uid, orgId);
                }
            });
        }
    }

    function loadUserOrgs(uid) {
        var list = document.getElementById("your-orgs-list");
        if (!list) return;
        list.innerHTML = '<div class="loading-placeholder"><i data-lucide="loader-2" class="spinner"></i><span>Fetching your memberships...</span></div>';
        if (window.lucide) lucide.createIcons();

        db.collectionGroup("members").where("uid", "==", uid).get()
            .then(function (snapshot) {
                if (snapshot.empty) {
                    list.innerHTML = '<div class="empty-list-state"><p>You are not a member of any organizations.</p></div>';
                    return;
                }
                var orgPromises = snapshot.docs.map(function (doc) {
                    var orgId = doc.ref.parent.parent.id;
                    return db.collection("orgs").doc(orgId).get().then(function (orgDoc) {
                        return {
                            id: orgId,
                            name: orgDoc.exists ? orgDoc.data().name : orgId,
                            visibility: orgDoc.exists ? orgDoc.data().visibility : "private",
                            description: orgDoc.exists ? orgDoc.data().description : ""
                        };
                    });
                });
                return Promise.all(orgPromises).then(function (orgs) {
                    renderOrgList(uid, orgs, null, list);
                });
            })
            .catch(function (err) {
                list.innerHTML = '<div class="global-error-banner">Failed to load organizations: ' + err.message + '</div>';
            });
    }

    function renderOrgList(uid, orgs, container, list) {
        if (!list) return;
        if (orgs.length === 0) {
            list.innerHTML = '<div class="empty-list-state"><p>No organizations found.</p></div>';
            return;
        }
        var fragment = document.createDocumentFragment();
        orgs.forEach(function (org) {
            var row = document.createElement("button");
            row.className = "org-item-row";
            row.dataset.orgId = org.id;
            var initials = getInitials(org.name);
            var isPublic = org.visibility === "public";
            var html = '<div class="org-item-left">' +
                '<div class="org-avatar-box">' + escapeHtml(initials) + '</div>' +
                '<div class="org-item-info">' +
                '<div class="org-item-title">' + escapeHtml(org.name) + ' <span class="visibility-badge ' + (isPublic ? 'public' : '') + '">' + (isPublic ? 'Public' : 'Private') + '</span></div>' +
                (org.description ? '<div class="org-item-desc">' + escapeHtml(org.description) + '</div>' : '') +
                '</div></div>' +
                '<span class="icon-wrapper"><i data-lucide="chevron-right"></i></span>';
            row.innerHTML = html;
            fragment.appendChild(row);
        });
        list.innerHTML = "";
        list.appendChild(fragment);
        if (window.lucide) lucide.createIcons();
    }

    function handleCreateOrg(orgName, description, visibility) {
        var user = auth.currentUser;
        var orgRef = db.collection("orgs").doc();
        var orgId = orgRef.id;
        if (setupLoading) setupLoading.hidden = false;
        
        return orgRef.set({
            name: orgName,
            description: description || "",
            visibility: visibility || "private",
            createdBy: user.uid,
            createdAt: firebase.firestore.FieldValue.serverTimestamp()
        }).then(function () {
            return db.collection("orgs").doc(orgId).collection("members").doc(user.uid).set({
                uid: user.uid,
                email: user.email,
                displayName: user.displayName || user.email.split('@')[0],
                role: "owner",
                accessLevel: 40,
                joinedAt: firebase.firestore.FieldValue.serverTimestamp()
            });
        }).then(function () {
            return db.collection("users").doc(user.uid).set({
                orgs: firebase.firestore.FieldValue.arrayUnion(orgId)
            }, { merge: true });
        }).then(function () {
            return enterOrg(user.uid, orgId);
        }).catch(function (err) {
            if (setupError) {
                setupError.textContent = "Error creating organization: " + err.message;
                setupError.hidden = false;
            }
            if (setupLoading) setupLoading.hidden = true;
        });
    }

    function handleJoinOrg(inviteCode) {
        var user = auth.currentUser;
        if (setupLoading) setupLoading.hidden = false;

        db.collectionGroup("invites").where("code", "==", inviteCode).where("used", "==", false).get()
            .then(function (snapshot) {
                if (snapshot.empty) throw new Error("Invalid or expired invite code.");
                var inviteDoc = snapshot.docs[0];
                var orgId = inviteDoc.ref.parent.parent.id;
                var inviteData = inviteDoc.data();

                return db.collection("orgs").doc(orgId).collection("members").doc(user.uid).set({
                    uid: user.uid,
                    email: user.email,
                    displayName: user.displayName || user.email.split('@')[0],
                    role: inviteData.roleName || "officer",
                    accessLevel: inviteData.accessLevel || 10,
                    joinedAt: firebase.firestore.FieldValue.serverTimestamp()
                }).then(function () {
                    return inviteDoc.ref.update({ used: true, usedBy: user.uid, usedAt: firebase.firestore.FieldValue.serverTimestamp() });
                }).then(function () {
                    return db.collection("users").doc(user.uid).set({
                        orgs: firebase.firestore.FieldValue.arrayUnion(orgId)
                    }, { merge: true });
                }).then(function () {
                    return enterOrg(user.uid, orgId);
                });
            })
            .catch(function (err) {
                if (setupError) {
                    setupError.textContent = err.message;
                    setupError.hidden = false;
                }
                if (setupLoading) setupLoading.hidden = true;
            });
    }

    // --------------- Transcripts (Dashboard) ---------------
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

    function transcriptsPath() {
        if (currentOrgId) return "orgs/" + currentOrgId + "/transcripts";
        return "transcripts";
    }

    function loadTranscripts() {
        var list = document.getElementById("transcript-list");
        if (!list) return;

        var query = db.collection(transcriptsPath()).orderBy("timestamp", "desc");

        if (selectedMemberId) {
            query = query.where("userId", "==", selectedMemberId);
        } else if (currentUserLevel === ACCESS_LEVELS.OFFICER) {
            query = query.where("userId", "==", auth.currentUser.uid);
        }

        var startDate = filterStart ? filterStart.value : null;
        var endDate = filterEnd ? filterEnd.value : null;

        if (startDate) {
            var startMs = new Date(startDate).getTime();
            query = query.where("timestamp", ">=", startMs);
        }
        if (endDate) {
            var endMs = new Date(endDate).getTime() + 86400000;
            query = query.where("timestamp", "<=", endMs);
        }

        query.limit(100).get().then(function (snapshot) {
            renderTranscripts(snapshot.docs.map(function (doc) {
                return Object.assign({ id: doc.id }, doc.data());
            }));
        }).catch(function (err) {
            console.error("Transcript load failed", err);
            list.innerHTML = '<div class="empty-state"><p>Error loading transcripts: ' + err.message + '</p></div>';
        });
    }

    function renderTranscripts(items) {
        var list = document.getElementById("transcript-list");
        if (!list) return;
        if (items.length === 0) {
            list.innerHTML = '<div class="empty-state"><i data-lucide="message-square-dashed"></i><p>No transcripts found matching filters.</p></div>';
            if (window.lucide) lucide.createIcons();
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
            var sessionTranscripts = groups[sid];
            var first = sessionTranscripts[0];
            var time = first.timestamp ? formatTimestamp(first.timestamp) : "Unknown Date";
            var officerName = memberCache.has(first.userId) ? memberCache.get(first.userId).displayName : "Unknown Officer";

            html += '<details class="conversation-group" open>';
            html += '<summary class="conversation-header">' +
                '<div class="conversation-title-row">' +
                '<i data-lucide="message-square" class="convo-icon"></i>' +
                '<span class="conversation-title">' + escapeHtml(officerName) + '</span>' +
                '</div>' +
                '<div class="conversation-meta">' +
                '<span class="convo-time"><i data-lucide="clock"></i>' + time + '</span>' +
                '<span class="convo-count"><i data-lucide="layers"></i>' + sessionTranscripts.length + ' segments</span>' +
                '</div></summary>';
            
            sessionTranscripts.forEach(function (t) {
                var displayText = t.correctedText || t.text || "(empty)";
                var cardTime = t.timestamp ? new Date(t.timestamp).toLocaleTimeString() : "";
                var confidence = "";
                if (t.speakerConfidence != null) {
                    var pct = Math.round(t.speakerConfidence * 100);
                    confidence = '<span class="speaker-confidence"><i data-lucide="shield-check"></i>' + pct + '% match</span>';
                }
                var gps = "";
                if (t.latitude != null && t.longitude != null) {
                    var mapsUrl = "https://www.google.com/maps?q=" + t.latitude + "," + t.longitude;
                    gps = '<a class="gps-link" href="' + mapsUrl + '" target="_blank" rel="noopener"><i data-lucide="map-pin"></i>' + Number(t.latitude).toFixed(4) + ", " + Number(t.longitude).toFixed(4) + "</a>";
                }

                html += '<div class="transcript-card">' +
                    '<div class="transcript-meta">' +
                    '<span class="time-stamp"><i data-lucide="clock"></i>' + cardTime + '</span>' +
                    confidence +
                    (gps ? gps : "") +
                    '</div>' +
                    '<div class="transcript-text">' + escapeHtml(displayText) + '</div>' +
                    '</div>';
            });
            html += '</details>';
        });

        list.innerHTML = html;
        if (window.lucide) lucide.createIcons();
    }

    // --------------- Members (Dashboard) ---------------
    function loadMemberCache() {
        if (!currentOrgId) return Promise.resolve();
        return db.collection("orgs").doc(currentOrgId).collection("members").get()
            .then(function (snapshot) {
                memberCache.clear();
                snapshot.forEach(function (doc) {
                    memberCache.set(doc.id, doc.data());
                });
            });
    }

    function renderMembersSidebar() {
        var sidebarList = document.getElementById("sidebar-member-list");
        if (!sidebarList) return;
        
        var fragment = document.createDocumentFragment();
        memberCache.forEach(function (data, uid) {
            var row = document.createElement("div");
            row.className = "sidebar-member-row";
            if (uid === selectedMemberId) row.classList.add("active");
            row.onclick = function () { selectMember(uid); };

            var initials = getInitials(data.displayName || data.email);
            var level = data.accessLevel || 10;
            var levelName = getLevelName(level);
            var levelClass = levelName.toLowerCase();

            row.innerHTML = '<div class="sidebar-avatar">' + escapeHtml(initials) + '</div>' +
                '<div class="sidebar-member-info">' +
                '<span class="sidebar-member-name">' + escapeHtml(data.displayName || data.email.split('@')[0]) + '</span>' +
                '<span class="role-badge ' + levelClass + '">' + levelName + '</span>' +
                '</div>';
            fragment.appendChild(row);
        });

        sidebarList.innerHTML = "";
        sidebarList.appendChild(fragment);

        var allMembersBtn = document.getElementById("sidebar-all-members");
        if (allMembersBtn) {
            allMembersBtn.classList.toggle("active", !selectedMemberId);
            allMembersBtn.onclick = function () { selectMember(null); };
        }

        var toggleBtn = document.getElementById("sidebar-toggle-btn");
        if (toggleBtn) {
            toggleBtn.onclick = function () {
                var sidebar = document.getElementById("members-sidebar");
                sidebar.classList.toggle("sidebar-collapsed");
                var iconEl = toggleBtn.querySelector("i, svg");
                if (sidebar.classList.contains("sidebar-collapsed")) {
                    iconEl.setAttribute("data-lucide", "panel-left-open");
                } else {
                    iconEl.setAttribute("data-lucide", "panel-left-close");
                }
                if (window.lucide) lucide.createIcons();
            };
        }
    }

    function selectMember(uid) {
        selectedMemberId = uid;
        renderMembersSidebar();
        loadTranscripts();
    }

    function toggleSettingsModal(open) {
        var modal = document.getElementById("settings-overlay");
        if (modal) modal.hidden = !open;
        if (open) renderMemberList();
    }

    function renderMemberList() {
        var list = document.getElementById("member-list");
        if (!list) return;
        var fragment = document.createDocumentFragment();
        memberCache.forEach(function (data, uid) {
            var row = document.createElement("div");
            row.className = "member-row";
            var level = data.accessLevel || 10;
            var levelName = getLevelName(level);
            
            var html = '<div class="member-info">' +
                '<div class="member-name">' + escapeHtml(data.displayName || data.email) + '</div>' +
                '<div class="member-email">' + escapeHtml(data.email) + '</div>' +
                '</div>' +
                '<span class="role-badge ' + levelName.toLowerCase() + '">' + levelName + '</span>';
            
            if (hasPermission(ACCESS_LEVELS.ADMIN) && uid !== auth.currentUser.uid) {
                html += '<button class="member-remove-btn" onclick="removeMember(\'' + uid + '\')"><i data-lucide="trash-2"></i></button>';
            }
            row.innerHTML = html;
            fragment.appendChild(row);
        });
        list.innerHTML = "";
        list.appendChild(fragment);
        if (window.lucide) lucide.createIcons();
    }

    function generateInvite(accessLevel) {
        var code = "INV-" + Math.random().toString(36).substring(2, 10).toUpperCase();
        db.collection("orgs").doc(currentOrgId).collection("invites").add({
            code: code,
            accessLevel: accessLevel,
            roleName: getLevelName(accessLevel).toLowerCase(),
            used: false,
            createdAt: firebase.firestore.FieldValue.serverTimestamp()
        }).then(function () {
            var display = document.getElementById("invite-code-display");
            display.innerHTML = '<div class="invite-code-display">' +
                '<span class="invite-code-text">' + code + '</span>' +
                '<button class="copy-btn" onclick="navigator.clipboard.writeText(\'' + code + '\')">Copy</button>' +
                '</div>';
        });
    }

    // --------------- Record Panel (Always-On) ---------------
    var isLive = false;
    var micStream = null;
    var audioContext = null;
    var micSource = null;
    var analyserNode = null;
    var scriptNode = null;
    var vadInterval = null;
    var speechActive = false;
    var chunkSamples = [];
    var chunkStartedAt = 0;
    var totalChunks = 0, transcribedChunks = 0, pendingChunks = 0;
    var currentGps = null;
    var recordSessionId = null;
    var MIN_CHUNK_S = 1.0, MAX_CHUNK_S = 15.0;

    function initTestPanel() {
        var testPanel = document.getElementById("test-panel");
        var toggleBtn = document.getElementById("toggle-test-btn");
        var closeBtn = document.getElementById("close-test-panel");
        var pushBtn = document.getElementById("test-push-btn");
        var batchBtn = document.getElementById("test-batch-btn");
        var clearBtn = document.getElementById("test-clear-btn");
        var statusEl = document.getElementById("test-status");

        function setStatus(msg) {
            if (statusEl) statusEl.textContent = msg;
        }

        if (toggleBtn) {
            toggleBtn.onclick = function () {
                if (!testPanel) return;
                testPanel.hidden = !testPanel.hidden;
            };
        }

        if (closeBtn) {
            closeBtn.onclick = function () {
                if (testPanel) testPanel.hidden = true;
            };
        }

        if (pushBtn) {
            pushBtn.onclick = function () {
                var text = (document.getElementById("test-text").value || "").trim();
                var lat = parseFloat(document.getElementById("test-lat").value) || 0;
                var lng = parseFloat(document.getElementById("test-lng").value) || 0;
                if (!text) { setStatus("Enter some text first."); return; }
                var user = auth.currentUser;
                if (!user) { setStatus("Not logged in."); return; }
                setStatus("Pushing...");
                db.collection(transcriptsPath()).add({
                    text: text,
                    timestamp: Date.now(),
                    sessionId: "test-" + Date.now(),
                    userId: user.uid,
                    recordedBy: user.uid,
                    orgId: currentOrgId || null,
                    location: { lat: lat, lng: lng }
                }).then(function () {
                    setStatus("Pushed 1 transcript.");
                    loadTranscripts();
                }).catch(function (err) {
                    setStatus("Error: " + err.message);
                });
            };
        }

        if (batchBtn) {
            batchBtn.onclick = function () {
                var user = auth.currentUser;
                if (!user) { setStatus("Not logged in."); return; }
                setStatus("Pushing 5 random transcripts...");
                var batch = db.batch();
                for (var i = 1; i <= 5; i++) {
                    var ref = db.collection(transcriptsPath()).doc();
                    batch.set(ref, {
                        text: "Test transcript #" + i + " — " + new Date().toLocaleTimeString(),
                        timestamp: Date.now() - (i * 1000),
                        sessionId: "test-batch-" + Date.now(),
                        userId: user.uid,
                        recordedBy: user.uid,
                        orgId: currentOrgId || null,
                        location: { lat: 37.7749 + (Math.random() - 0.5) * 0.01, lng: -122.4194 + (Math.random() - 0.5) * 0.01 }
                    });
                }
                batch.commit().then(function () {
                    setStatus("Pushed 5 random transcripts.");
                    loadTranscripts();
                }).catch(function (err) {
                    setStatus("Error: " + err.message);
                });
            };
        }

        if (clearBtn) {
            clearBtn.onclick = function () {
                if (!confirm("Delete ALL transcripts in this collection? This cannot be undone.")) return;
                setStatus("Clearing...");
                db.collection(transcriptsPath()).get().then(function (snapshot) {
                    if (snapshot.empty) { setStatus("Nothing to clear."); return; }
                    var batch = db.batch();
                    snapshot.docs.forEach(function (doc) { batch.delete(doc.ref); });
                    return batch.commit();
                }).then(function () {
                    setStatus("All transcripts cleared.");
                    loadTranscripts();
                }).catch(function (err) {
                    setStatus("Error: " + err.message);
                });
            };
        }
    }

    function initRecordPanel() {
        var goLiveBtn = document.getElementById("go-live-btn");
        var goStopBtn = document.getElementById("go-stop-btn");
        var recordPanel = document.getElementById("record-panel");
        var vadStatusEl = document.getElementById("vad-status");
        var eventLogEl = document.getElementById("event-log");

        function logEvent(msg) {
            if (!eventLogEl) return;
            var line = document.createElement("div");
            line.className = "event-log-line";
            line.textContent = "[" + new Date().toLocaleTimeString() + "] " + msg;
            eventLogEl.prepend(line);
        }

        function updateStats() {
            var stats = document.getElementById("chunk-stats");
            if (stats) stats.textContent = "Chunks: " + totalChunks + " | Transcribed: " + transcribedChunks + " | Pending: " + pendingChunks;
        }

        function startMeter() {
            var fill = document.querySelector(".audio-level-fill");
            function frame() {
                if (!isLive || !analyserNode) return;
                var data = new Uint8Array(analyserNode.frequencyBinCount);
                analyserNode.getByteFrequencyData(data);
                var sum = 0; for (var i = 0; i < data.length; i++) sum += data[i];
                var avg = sum / data.length;
                if (fill) fill.style.width = Math.min(100, avg * 2) + "%";
                requestAnimationFrame(frame);
            }
            requestAnimationFrame(frame);
        }

        function stopMeter() {
            var fill = document.querySelector(".audio-level-fill");
            if (fill) fill.style.width = "0%";
        }

        function fetchGps() {
            if (!navigator.geolocation) return;
            navigator.geolocation.watchPosition(function (pos) {
                currentGps = { latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracy: pos.coords.accuracy };
                var gpsEl = document.getElementById("record-gps-display");
                if (gpsEl) gpsEl.textContent = "GPS: " + currentGps.latitude.toFixed(5) + ", " + currentGps.longitude.toFixed(5);
            });
        }

        function getRecordSessionId() {
            if (!recordSessionId) recordSessionId = "sess_" + Date.now();
            return recordSessionId;
        }

        function vadTick() {
            if (!analyserNode) return;
            var data = new Float32Array(analyserNode.fftSize);
            analyserNode.getFloatTimeDomainData(data);
            var sum = 0; for (var i = 0; i < data.length; i++) sum += data[i] * data[i];
            var rms = Math.sqrt(sum / data.length);
            var sensitivity = parseFloat(document.getElementById("sensitivity-slider").value);
            
            if (rms > sensitivity) {
                if (!speechActive) {
                    speechActive = true; chunkStartedAt = Date.now();
                    if (vadStatusEl) { vadStatusEl.textContent = "SPEAKING"; vadStatusEl.classList.add("vad-speech"); }
                }
            } else if (speechActive && (Date.now() - chunkStartedAt) > 1500) {
                finalizeChunk("silence");
            }
            if (speechActive && (Date.now() - chunkStartedAt) > (MAX_CHUNK_S * 1000)) {
                finalizeChunk("limit");
            }
        }

        function uploadToAssemblyAI(blob) {
            return fetch("https://api.assemblyai.com/v2/upload", {
                method: "POST", headers: { "Authorization": "b3e9447e1458498f804f323e2cc1834e" }, body: blob
            }).then(function (r) { return r.json(); }).then(function (upload) {
                return fetch("https://api.assemblyai.com/v2/transcript", {
                    method: "POST", headers: { "Authorization": "b3e9447e1458498f804f323e2cc1834e", "Content-Type": "application/json" },
                    body: JSON.stringify({ audio_url: upload.upload_url })
                }).then(function (r) { return r.json(); });
            }).then(function (transcript) {
                return new Promise(function (res, rej) {
                    function poll() {
                        fetch("https://api.assemblyai.com/v2/transcript/" + transcript.id, {
                            headers: { "Authorization": "b3e9447e1458498f804f323e2cc1834e" }
                        }).then(function (r) { return r.json(); }).then(function (t) {
                            if (t.status === "completed") res(t);
                            else if (t.status === "error") rej(new Error(t.error));
                            else setTimeout(poll, 1000);
                        });
                    }
                    poll();
                });
            });
        }

        function encodeWAV(samples, sampleRate) {
            var buffer = new ArrayBuffer(44 + samples.length * 2);
            var view = new DataView(buffer);
            function writeString(s, o) { for (var i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); }
            writeString("RIFF", 0); view.setUint32(4, 32 + samples.length * 2, true); writeString("WAVE", 8);
            writeString("fmt ", 12); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
            view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
            writeString("data", 36); view.setUint32(40, samples.length * 2, true);
            var index = 44; for (var i = 0; i < samples.length; i++) {
                var s = Math.max(-1, Math.min(1, samples[i]));
                view.setInt16(index, s < 0 ? s * 0x8000 : s * 0x7FFF, true); index += 2;
            }
            return new Blob([view], { type: "audio/wav" });
        }

        function finalizeChunk(reason) {
            speechActive = false;
            if (vadStatusEl) vadStatusEl.classList.remove("vad-speech");
            var duration = (Date.now() - chunkStartedAt) / 1000;
            var samples = flattenSamples(chunkSamples);
            chunkSamples = [];
            if (duration < MIN_CHUNK_S || samples.length === 0) {
                if (vadStatusEl) vadStatusEl.textContent = "Listening...";
                logEvent("Chunk discarded (too short)");
                return;
            }
            totalChunks++; pendingChunks++; updateStats();
            logEvent("Uploading chunk #" + totalChunks + " (" + duration.toFixed(1) + "s, " + reason + ")");
            var sampleRate = audioContext ? audioContext.sampleRate : 16000;
            var wavBlob = encodeWAV(samples, sampleRate);
            var chunkNum = totalChunks;
            uploadToAssemblyAI(wavBlob).then(function (result) {
                var text = result.text || "";
                pendingChunks--; transcribedChunks++; updateStats();
                if (!text.trim()) { logEvent("Chunk #" + chunkNum + ": (no speech)"); return; }
                logEvent("Transcript #" + chunkNum + ": " + (text.length > 50 ? text.substring(0, 50) + "..." : text));
                var doc = { text: text.trim(), timestamp: Date.now(), sessionId: getRecordSessionId(), userId: auth.currentUser.uid };
                if (currentOrgId) doc.orgId = currentOrgId;
                if (auth.currentUser) doc.recordedBy = auth.currentUser.uid;
                db.collection(transcriptsPath()).add(doc).then(function () { logEvent("Saved chunk #" + chunkNum); loadTranscripts(); });
            }).catch(function (err) { pendingChunks--; updateStats(); logEvent("Error #" + chunkNum + ": " + err.message); });
            if (isLive && vadStatusEl) vadStatusEl.textContent = "Listening...";
        }

        function flattenSamples(buffers) {
            var totalLength = 0;
            for (var i = 0; i < buffers.length; i++) totalLength += buffers[i].length;
            var merged = new Float32Array(totalLength);
            var offset = 0;
            for (var i = 0; i < buffers.length; i++) { merged.set(buffers[i], offset); offset += buffers[i].length; }
            return merged;
        }

        function startAudioCapture(stream) {
            audioContext = new (window.AudioContext || window.webkitAudioContext)();
            micSource = audioContext.createMediaStreamSource(stream);
            analyserNode = audioContext.createAnalyser();
            analyserNode.fftSize = 2048;
            micSource.connect(analyserNode);
            scriptNode = audioContext.createScriptProcessor(4096, 1, 1);
            micSource.connect(scriptNode);
            scriptNode.connect(audioContext.destination);
            scriptNode.onaudioprocess = function (e) {
                if (speechActive) { var channelData = e.inputBuffer.getChannelData(0); chunkSamples.push(new Float32Array(channelData)); }
            };
        }

        function stopAudioCapture() {
            if (scriptNode) { scriptNode.disconnect(); scriptNode = null; }
            if (micSource) { micSource.disconnect(); micSource = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            analyserNode = null;
        }

        if (goLiveBtn) {
            goLiveBtn.addEventListener("click", function () {
                if (vadStatusEl) vadStatusEl.textContent = "Requesting microphone...";
                recordSessionId = null; totalChunks = 0; transcribedChunks = 0; pendingChunks = 0; updateStats();
                if (eventLogEl) eventLogEl.innerHTML = "";
                var constraints = { audio: { channelCount: 1, sampleRate: { ideal: 16000 } } };
                navigator.mediaDevices.getUserMedia(constraints).then(function (stream) {
                    micStream = stream; isLive = true; startAudioCapture(stream); startMeter(); fetchGps();
                    vadInterval = setInterval(vadTick, 50);
                    if (vadStatusEl) vadStatusEl.textContent = "Listening...";
                    if (goLiveBtn) goLiveBtn.hidden = true; if (goStopBtn) goStopBtn.hidden = false;
                    if (recordPanel) recordPanel.classList.add("is-recording");
                    logEvent("Live capture started");
                }).catch(function (err) { if (vadStatusEl) vadStatusEl.textContent = "Microphone error: " + err.message; });
            });
        }

        if (goStopBtn) {
            goStopBtn.addEventListener("click", function () {
                isLive = false;
                if (speechActive) finalizeChunk("stopped");
                if (vadInterval) { clearInterval(vadInterval); vadInterval = null; }
                stopMeter(); stopAudioCapture();
                if (micStream) { micStream.getTracks().forEach(function (t) { t.stop(); }); micStream = null; }
                if (goStopBtn) goStopBtn.hidden = true; if (goLiveBtn) goLiveBtn.hidden = false;
                if (recordPanel) recordPanel.classList.remove("is-recording");
                if (vadStatusEl) vadStatusEl.textContent = "Idle";
                logEvent("Live capture stopped");
            });
        }
    }

    function formatTimestamp(ms) {
        var d = new Date(ms);
        return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) + " " + d.toLocaleTimeString();
    }

    // Call boot once DOM is ready if scripts are already here
    if (document.readyState === "complete" || document.readyState === "interactive") {
        boot();
    } else {
        window.addEventListener("DOMContentLoaded", boot);
    }

})();