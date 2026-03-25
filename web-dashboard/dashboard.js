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
    var setupScreen = document.getElementById("setup-screen");
    var loginError = document.getElementById("login-error");
    var userEmailSpan = document.getElementById("user-email");
    var logoutBtn = document.getElementById("logout-btn");
    var transcriptList = document.getElementById("transcript-list");
    var filterStart = document.getElementById("filter-start");
    var filterEnd = document.getElementById("filter-end");
    var filterBtn = document.getElementById("filter-btn");
    var clearFilterBtn = document.getElementById("clear-filter-btn");

    // Setup screen refs
    var setupUserEmail = document.getElementById("setup-user-email");
    var createOrgName = document.getElementById("create-org-name");
    var createOrgBtn = document.getElementById("create-org-btn");
    var joinInviteCode = document.getElementById("join-invite-code");
    var joinOrgBtn = document.getElementById("join-org-btn");
    var setupError = document.getElementById("setup-error");
    var setupLoading = document.getElementById("setup-loading");
    var orgLabel = document.getElementById("org-label");
    var orgLabelText = document.getElementById("org-label-text");

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

    // --------------- Boot ---------------
    function boot() {
        firebase.initializeApp(firebaseConfig);
        auth = firebase.auth();
        db = firebase.firestore();

        auth.onAuthStateChanged(function (user) {
            if (user) {
                resolveOrgMembership(user.uid).then(function (membership) {
                    if (membership) {
                        setOrgContext(membership.orgId, membership.accessLevel, membership.assignedOfficers, membership.orgName);
                        showDashboard(user);
                    } else {
                        showSetupScreen(user);
                    }
                }).catch(function (err) {
                    console.error("Failed to resolve org membership", err);
                    showSetupScreen(user);
                });
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
        initSetupScreen();
        initTestPanel();
        initRecordPanel();

        // Settings modal
        document.getElementById("settings-btn").addEventListener("click", function () {
            toggleSettingsModal(true);
        });
        document.getElementById("settings-close-btn").addEventListener("click", function () {
            toggleSettingsModal(false);
        });
        document.getElementById("settings-overlay").addEventListener("click", function (e) {
            if (e.target === e.currentTarget) toggleSettingsModal(false);
        });
        document.getElementById("generate-invite-btn").addEventListener("click", function () {
            var level = parseInt(document.getElementById("invite-role-select").value, 10);
            generateInvite(level);
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
        currentOrgId = null;
        currentUserLevel = null;
        assignedOfficers = [];
        currentOrgName = null;
        selectedMemberId = null;
        memberCache.clear();
        orgLabel.hidden = true;
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
        setupScreen.hidden = true;
        dashboardScreen.hidden = true;
        stopAutoRefresh();
    }

    function showSetupScreen(user) {
        loginScreen.hidden = true;
        setupScreen.hidden = false;
        dashboardScreen.hidden = true;
        setupUserEmail.textContent = user.email;
        setupError.hidden = true;
        setupLoading.hidden = true;
        stopAutoRefresh();
    }

    function showDashboard(user) {
        loginScreen.hidden = true;
        setupScreen.hidden = true;
        dashboardScreen.hidden = false;
        userEmailSpan.textContent = user.email;

        // Level-based UI gating
        document.getElementById("settings-btn").hidden = !hasPermission(ACCESS_LEVELS.ADMIN);
        document.getElementById("toggle-record-btn").hidden = (currentUserLevel !== ACCESS_LEVELS.OFFICER);

        // Members sidebar: visible for supervisors and above
        var sidebar = document.getElementById("members-sidebar");
        sidebar.hidden = !hasPermission(ACCESS_LEVELS.SUPERVISOR);

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
        return db.collectionGroup("members")
            .where("uid", "==", uid)
            .limit(1)
            .get()
            .then(function (snapshot) {
                if (snapshot.empty) return null;
                var doc = snapshot.docs[0];
                var data = doc.data();
                var orgId = doc.ref.parent.parent.id;
                return db.collection("orgs").doc(orgId).get().then(function (orgDoc) {
                    var orgName = orgDoc.exists ? orgDoc.data().name : orgId;
                    return {
                        orgId: orgId,
                        accessLevel: data.accessLevel,
                        assignedOfficers: data.assignedOfficers || [],
                        orgName: orgName
                    };
                });
            });
    }

    function setOrgContext(orgId, accessLevel, officers, orgName) {
        currentOrgId = orgId;
        currentUserLevel = accessLevel;
        assignedOfficers = officers || [];
        currentOrgName = orgName || orgId;
        orgLabelText.textContent = currentOrgName;
        orgLabel.hidden = false;
    }

    function handleCreateOrg(orgName) {
        var user = auth.currentUser;
        var orgRef = db.collection("orgs").doc();
        var orgId = orgRef.id;
        var batch = db.batch();
        batch.set(orgRef, {
            name: orgName,
            createdBy: user.uid,
            createdAt: firebase.firestore.FieldValue.serverTimestamp()
        });
        batch.set(db.collection("orgs").doc(orgId).collection("members").doc(user.uid), {
            uid: user.uid,
            email: user.email,
            accessLevel: ACCESS_LEVELS.OWNER,
            joinedAt: firebase.firestore.FieldValue.serverTimestamp()
        });
        return batch.commit().then(function () {
            return orgId;
        });
    }

    function handleJoinOrg(inviteCode) {
        var user = auth.currentUser;
        return db.collectionGroup("invites")
            .where("code", "==", inviteCode)
            .where("used", "==", false)
            .limit(1)
            .get()
            .then(function (snapshot) {
                if (snapshot.empty) {
                    throw new Error("Invalid or expired invite code.");
                }
                var inviteDoc = snapshot.docs[0];
                var inviteData = inviteDoc.data();
                var orgId = inviteDoc.ref.parent.parent.id;
                var accessLevel = inviteData.accessLevel || ACCESS_LEVELS.OFFICER;

                var batch = db.batch();
                batch.set(db.collection("orgs").doc(orgId).collection("members").doc(user.uid), {
                    uid: user.uid,
                    email: user.email,
                    accessLevel: accessLevel,
                    assignedOfficers: inviteData.assignedOfficers || [],
                    joinedAt: firebase.firestore.FieldValue.serverTimestamp()
                });
                batch.update(inviteDoc.ref, {
                    used: true,
                    usedBy: user.uid,
                    usedAt: firebase.firestore.FieldValue.serverTimestamp()
                });
                return batch.commit().then(function () {
                    return { orgId: orgId, accessLevel: accessLevel, assignedOfficers: inviteData.assignedOfficers || [] };
                });
            });
    }

    function transcriptsPath() {
        return "transcripts";
    }

    function initSetupScreen() {
        // Tab switching
        var tabs = setupScreen.querySelectorAll(".setup-tab");
        var tabContents = setupScreen.querySelectorAll(".setup-tab-content");
        tabs.forEach(function (tab) {
            tab.addEventListener("click", function () {
                tabs.forEach(function (t) { t.classList.remove("active"); });
                tabContents.forEach(function (tc) { tc.classList.remove("active"); });
                tab.classList.add("active");
                var targetId = tab.getAttribute("data-tab") === "create" ? "create-org-form" : "join-org-form";
                document.getElementById(targetId).classList.add("active");
                setupError.hidden = true;
            });
        });

        // Create org
        createOrgBtn.addEventListener("click", function () {
            var name = createOrgName.value.trim();
            if (!name) {
                setupError.textContent = "Please enter an organization name.";
                setupError.hidden = false;
                return;
            }
            setupError.hidden = true;
            setupLoading.hidden = false;
            createOrgBtn.disabled = true;

            handleCreateOrg(name).then(function (orgId) {
                setOrgContext(orgId, ACCESS_LEVELS.OWNER, [], name);
                showDashboard(auth.currentUser);
            }).catch(function (err) {
                setupError.textContent = err.message || "Failed to create organization.";
                setupError.hidden = false;
            }).finally(function () {
                setupLoading.hidden = true;
                createOrgBtn.disabled = false;
            });
        });

        // Join org
        joinOrgBtn.addEventListener("click", function () {
            var code = joinInviteCode.value.trim();
            if (!code) {
                setupError.textContent = "Please enter an invite code.";
                setupError.hidden = false;
                return;
            }
            setupError.hidden = true;
            setupLoading.hidden = false;
            joinOrgBtn.disabled = true;

            handleJoinOrg(code).then(function (result) {
                return db.collection("orgs").doc(result.orgId).get().then(function (orgDoc) {
                    var orgName = orgDoc.exists ? orgDoc.data().name : result.orgId;
                    setOrgContext(result.orgId, result.accessLevel, result.assignedOfficers, orgName);
                    showDashboard(auth.currentUser);
                });
            }).catch(function (err) {
                setupError.textContent = err.message || "Failed to join organization.";
                setupError.hidden = false;
            }).finally(function () {
                setupLoading.hidden = true;
                joinOrgBtn.disabled = false;
            });
        });

        if (window.lucide) lucide.createIcons();
    }

    // --------------- Member Cache ---------------
    function loadMemberCache() {
        if (!currentOrgId) return Promise.resolve();
        return db.collection("orgs/" + currentOrgId + "/members").get()
            .then(function (snapshot) {
                memberCache.clear();
                snapshot.docs.forEach(function (doc) {
                    var d = doc.data();
                    memberCache.set(doc.id, {
                        displayName: d.displayName || d.email || doc.id,
                        email: d.email || "",
                        accessLevel: d.accessLevel || ACCESS_LEVELS.OFFICER
                    });
                });
            })
            .catch(function (err) {
                console.error("Failed to load member cache", err);
            });
    }

    // --------------- Members Sidebar ---------------
    function getInitials(name) {
        var parts = name.split(/\s+/);
        if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
        return name.substring(0, 2).toUpperCase();
    }

    function getLevelClassName(level) {
        var name = LEVEL_NAMES[level];
        return name ? name.toLowerCase() : "officer";
    }

    function renderMembersSidebar() {
        var container = document.getElementById("sidebar-member-list");
        var html = "";

        memberCache.forEach(function (member, uid) {
            var isActive = (uid === selectedMemberId);
            var initials = getInitials(member.displayName);
            var levelClass = getLevelClassName(member.accessLevel);

            html += '<div class="sidebar-member-row' + (isActive ? ' active' : '') + '" data-uid="' + uid + '" role="button" tabindex="0">';
            html += '<div class="sidebar-avatar">' + escapeHtml(initials) + '</div>';
            html += '<div class="sidebar-member-info">';
            html += '<div class="sidebar-member-name">' + escapeHtml(member.displayName) + '</div>';
            html += '<div class="sidebar-member-email">' + escapeHtml(member.email) + '</div>';
            html += '</div>';
            html += '<span class="role-badge ' + levelClass + '">' + getLevelName(member.accessLevel) + '</span>';
            html += '</div>';
        });

        container.innerHTML = html;

        // Wire click handlers on member rows
        container.querySelectorAll(".sidebar-member-row").forEach(function (row) {
            row.addEventListener("click", function () {
                selectMember(row.getAttribute("data-uid"));
            });
        });

        // Wire "All Members" button
        var allMembersBtn = document.getElementById("sidebar-all-members");
        allMembersBtn.onclick = function () {
            selectMember(null);
        };

        // Update active states
        updateSidebarActiveStates();

        // Wire sidebar toggle
        var toggleBtn = document.getElementById("sidebar-toggle-btn");
        toggleBtn.onclick = function () {
            var sidebar = document.getElementById("members-sidebar");
            sidebar.classList.toggle("sidebar-collapsed");
            var icon = toggleBtn.querySelector("i");
            if (sidebar.classList.contains("sidebar-collapsed")) {
                icon.setAttribute("data-lucide", "panel-left-open");
            } else {
                icon.setAttribute("data-lucide", "panel-left-close");
            }
            if (window.lucide) lucide.createIcons();
        };

        if (window.lucide) lucide.createIcons();
    }

    function selectMember(uid) {
        selectedMemberId = uid;
        updateSidebarActiveStates();
        loadTranscripts();
    }

    function updateSidebarActiveStates() {
        var allBtn = document.getElementById("sidebar-all-members");
        var rows = document.querySelectorAll("#sidebar-member-list .sidebar-member-row");

        if (!selectedMemberId) {
            allBtn.classList.add("active");
        } else {
            allBtn.classList.remove("active");
        }

        rows.forEach(function (row) {
            if (row.getAttribute("data-uid") === selectedMemberId) {
                row.classList.add("active");
            } else {
                row.classList.remove("active");
            }
        });

        // Show/hide filter indicator
        var header = document.querySelector(".sidebar-header");
        var indicator = header.querySelector(".sidebar-filter-active");
        if (selectedMemberId) {
            if (!indicator) {
                indicator = document.createElement("span");
                indicator.className = "sidebar-filter-active";
                header.querySelector(".sidebar-title").appendChild(indicator);
            }
        } else if (indicator) {
            indicator.remove();
        }
    }

    // --------------- Settings Modal ---------------
    function toggleSettingsModal(open) {
        var overlay = document.getElementById("settings-overlay");
        overlay.hidden = !open;
        if (open) {
            renderMemberList();
            document.getElementById("invite-code-display").innerHTML = "";
            if (window.lucide) lucide.createIcons();
        }
    }

    function renderMemberList() {
        var container = document.getElementById("member-list");
        var currentUid = auth.currentUser ? auth.currentUser.uid : null;
        var html = "";

        memberCache.forEach(function (member, uid) {
            var isCurrentUser = (uid === currentUid);
            var levelName = getLevelName(member.accessLevel).toLowerCase();
            html += '<div class="member-row" data-uid="' + uid + '">';
            html += '<div class="member-info">';
            html += '<div class="member-name">' + escapeHtml(member.displayName) + '</div>';
            html += '<div class="member-email">' + escapeHtml(member.email) + '</div>';
            html += '</div>';
            html += '<span class="role-badge ' + levelName + '">' + getLevelName(member.accessLevel) + '</span>';
            html += '<select class="member-role-select" data-uid="' + uid + '"' + (isCurrentUser ? ' disabled' : '') + '>';
            [ACCESS_LEVELS.OWNER, ACCESS_LEVELS.ADMIN, ACCESS_LEVELS.SUPERVISOR, ACCESS_LEVELS.OFFICER].forEach(function (lvl) {
                html += '<option value="' + lvl + '"' + (lvl === member.accessLevel ? ' selected' : '') + '>' + getLevelName(lvl) + '</option>';
            });
            html += '</select>';
            if (!isCurrentUser) {
                html += '<button class="member-remove-btn" data-uid="' + uid + '" title="Remove member">';
                html += '<i data-lucide="trash-2"></i>';
                html += '</button>';
            }
            html += '</div>';
        });

        if (memberCache.size === 0) {
            html = '<div class="empty-state" style="padding:24px"><p>No members found.</p></div>';
        }

        container.innerHTML = html;
        if (window.lucide) lucide.createIcons();

        container.querySelectorAll(".member-role-select").forEach(function (sel) {
            sel.addEventListener("change", function () {
                changeMemberRole(sel.getAttribute("data-uid"), sel.value);
            });
        });

        // Wire remove listeners
        container.querySelectorAll(".member-remove-btn").forEach(function (btn) {
            btn.addEventListener("click", function () {
                removeMember(btn.getAttribute("data-uid"));
            });
        });
    }

    function changeMemberRole(uid, newLevel) {
        var level = parseInt(newLevel, 10);
        db.doc("orgs/" + currentOrgId + "/members/" + uid).update({ accessLevel: level })
            .then(function () {
                var cached = memberCache.get(uid);
                if (cached) cached.accessLevel = level;
                renderMemberList();
                if (hasPermission(ACCESS_LEVELS.SUPERVISOR)) {
                    renderMembersSidebar();
                }
            })
            .catch(function (err) {
                console.error("Failed to change role", err);
                alert("Failed to update role: " + err.message);
            });
    }

    function removeMember(uid) {
        var member = memberCache.get(uid);
        var name = member ? member.displayName : uid;
        if (!confirm("Remove " + name + " from this organization?")) return;

        db.doc("orgs/" + currentOrgId + "/members/" + uid).delete()
            .then(function () {
                memberCache.delete(uid);
                if (selectedMemberId === uid) {
                    selectMember(null);
                }
                renderMemberList();
                if (hasPermission(ACCESS_LEVELS.SUPERVISOR)) {
                    renderMembersSidebar();
                }
            })
            .catch(function (err) {
                console.error("Failed to remove member", err);
                alert("Failed to remove member: " + err.message);
            });
    }

    function generateInvite(accessLevel) {
        var display = document.getElementById("invite-code-display");
        var btn = document.getElementById("generate-invite-btn");
        btn.disabled = true;
        display.innerHTML = '<span style="color:var(--text-secondary);font-size:13px">Generating...</span>';

        db.collection("orgs/" + currentOrgId + "/invites").add({
            accessLevel: accessLevel,
            createdBy: auth.currentUser.uid,
            createdAt: Date.now(),
            used: false
        }).then(function (docRef) {
            var code = docRef.id;
            display.innerHTML = '<span class="invite-code-text">' + escapeHtml(code) + '</span>'
                + '<button class="copy-btn" id="copy-invite-btn"><i data-lucide="copy"></i> Copy</button>';
            if (window.lucide) lucide.createIcons();
            document.getElementById("copy-invite-btn").addEventListener("click", function () {
                navigator.clipboard.writeText(code).then(function () {
                    var copyBtn = document.getElementById("copy-invite-btn");
                    copyBtn.classList.add("copied");
                    copyBtn.innerHTML = '<i data-lucide="check"></i> Copied';
                    if (window.lucide) lucide.createIcons();
                    setTimeout(function () {
                        copyBtn.classList.remove("copied");
                        copyBtn.innerHTML = '<i data-lucide="copy"></i> Copy';
                        if (window.lucide) lucide.createIcons();
                    }, 2000);
                });
            });
        }).catch(function (err) {
            display.innerHTML = '<span style="color:var(--error);font-size:13px">Error: ' + escapeHtml(err.message) + '</span>';
        }).finally(function () {
            btn.disabled = false;
        });
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
        var col = db.collection(transcriptsPath());
        var query = col.orderBy("timestamp", "desc").limit(500);

        if (currentOrgId) {
            query = query.where("orgId", "==", currentOrgId);
        }

        // Member filter from sidebar takes priority
        if (selectedMemberId) {
            query = query.where("recordedBy", "==", selectedMemberId);
        } else if (currentUserLevel === ACCESS_LEVELS.OFFICER) {
            query = query.where("recordedBy", "==", auth.currentUser.uid);
        } else if (currentUserLevel === ACCESS_LEVELS.SUPERVISOR && assignedOfficers.length > 0) {
            query = query.where("recordedBy", "in", assignedOfficers);
        }
        // admin/owner without selectedMemberId: no additional recordedBy filter

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
            transcriptList.innerHTML = '<div class="empty-state"><i data-lucide="alert-triangle"></i><p>Failed to load transcripts.</p></div>';
            lucide.createIcons();
        });
    }

    // --------------- Conversation Clustering (content + time) ---------------
    var STOP_WORDS = new Set([
        "a","an","the","is","are","was","were","be","been","being","have","has","had",
        "do","does","did","will","would","shall","should","may","might","can","could",
        "i","me","my","we","our","you","your","he","him","his","she","her","it","its",
        "they","them","their","this","that","these","those","am","not","no","or","and",
        "but","if","then","so","at","by","for","from","in","into","of","on","to","with",
        "about","up","out","off","over","under","again","further","just","also","very",
        "too","quite","really","here","there","when","where","how","what","which","who",
        "all","any","both","each","every","some","such","than","other","only","own",
        "same","more","most","much","many","as","like","get","got","go","went","going",
        "said","told","asked","let","see","saw","know","knew","think","thought","come",
        "came","take","took","make","made","give","gave","put","set","still","back",
        "well","way","because","through","between","after","before","around","down"
    ]);

    function extractKeywords(text) {
        if (!text) return [];
        return text.toLowerCase()
            .replace(/[^a-z0-9\s]/g, " ")
            .split(/\s+/)
            .filter(function (w) { return w.length > 2 && !STOP_WORDS.has(w); });
    }

    function jaccardSimilarity(setA, setB) {
        if (setA.length === 0 && setB.length === 0) return 0;
        var a = new Set(setA);
        var b = new Set(setB);
        var intersection = 0;
        a.forEach(function (w) { if (b.has(w)) intersection++; });
        var union = new Set(setA.concat(setB)).size;
        return union === 0 ? 0 : intersection / union;
    }

    function timeSimilarity(gapMs) {
        // 1.0 at 0 gap, decays exponentially. ~0.5 at 2 min, ~0.1 at 5 min, ~0 at 10 min
        return Math.exp(-gapMs / 150000);
    }

    function clusterIntoConversations(items) {
        if (items.length === 0) return [];

        var sorted = items.slice().sort(function (a, b) {
            return (a.timestamp || 0) - (b.timestamp || 0);
        });

        var conversations = [];
        var current = {
            items: [sorted[0]],
            start: sorted[0].timestamp || 0,
            end: sorted[0].timestamp || 0,
            keywords: extractKeywords(sorted[0].correctedText || sorted[0].text)
        };

        for (var i = 1; i < sorted.length; i++) {
            var item = sorted[i];
            var ts = item.timestamp || 0;
            var gap = ts - current.end;
            var itemText = item.correctedText || item.text || "";
            var itemKeywords = extractKeywords(itemText);

            // Combine all keywords from current conversation for broader matching
            var contentScore = jaccardSimilarity(current.keywords, itemKeywords);
            var timeScore = timeSimilarity(gap);

            // Weighted: content matters more than time
            var continuity = (contentScore * 0.6) + (timeScore * 0.4);

            // Hard cutoff: >10 min gap always splits regardless of content
            var hardSplit = gap > 10 * 60 * 1000;

            if (hardSplit || continuity < 0.15) {
                conversations.push(current);
                current = {
                    items: [item],
                    start: ts,
                    end: ts,
                    keywords: itemKeywords
                };
            } else {
                current.items.push(item);
                current.end = ts;
                // Accumulate keywords for the conversation
                current.keywords = current.keywords.concat(itemKeywords);
            }
        }
        conversations.push(current);

        conversations.reverse();
        return conversations;
    }

    function generateConversationTitle(convo) {
        // Find the most keyword-dense chunk as the "topic" chunk
        var bestText = "";
        var bestScore = -1;
        convo.items.forEach(function (item) {
            var text = item.correctedText || item.text || "";
            var kw = extractKeywords(text);
            if (kw.length > bestScore) {
                bestScore = kw.length;
                bestText = text;
            }
        });
        if (bestText.length > 80) return bestText.substring(0, 77) + "...";
        if (bestText.length > 0) return bestText;
        return "Untitled Conversation";
    }

    function formatTimeRange(startMs, endMs) {
        var s = new Date(startMs);
        var e = new Date(endMs);
        var date = s.toLocaleDateString(undefined, { month: "short", day: "numeric" });
        var startTime = s.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
        var endTime = e.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
        if (startMs === endMs) return date + " " + startTime;
        return date + " " + startTime + " — " + endTime;
    }

    function formatDuration(startMs, endMs) {
        var diff = Math.max(0, endMs - startMs);
        var secs = Math.floor(diff / 1000);
        if (secs < 60) return secs + "s";
        var mins = Math.floor(secs / 60);
        secs = secs % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        var hrs = Math.floor(mins / 60);
        mins = mins % 60;
        return hrs + "h " + mins + "m";
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

        var conversations = clusterIntoConversations(items);
        var html = "";

        conversations.forEach(function (convo, idx) {
            var title = escapeHtml(generateConversationTitle(convo));
            var timeRange = formatTimeRange(convo.start, convo.end);
            var duration = formatDuration(convo.start, convo.end);
            var count = convo.items.length;
            var isOpen = idx < 3 ? "open" : "";

            // Check if any item has GPS
            var hasGps = convo.items.some(function (t) { return t.latitude != null; });

            html += '<details class="conversation-group" ' + isOpen + '>';
            html += '<summary class="conversation-header">';
            html += '  <div class="conversation-title-row">';
            html += '    <i data-lucide="message-square-text" class="convo-icon"></i>';
            html += '    <span class="conversation-title">' + title + '</span>';
            html += '  </div>';
            html += '  <div class="conversation-meta">';
            html += '    <span class="convo-time"><i data-lucide="clock"></i> ' + escapeHtml(timeRange) + '</span>';
            html += '    <span class="convo-duration">' + escapeHtml(duration) + '</span>';
            html += '    <span class="convo-count">' + count + ' chunk' + (count !== 1 ? 's' : '') + '</span>';
            if (hasGps) html += '    <span class="convo-gps-badge"><i data-lucide="map-pin"></i></span>';
            html += '  </div>';
            html += '</summary>';

            // Render chunks chronologically within conversation
            var chronoItems = convo.items.slice().sort(function (a, b) {
                return (a.timestamp || 0) - (b.timestamp || 0);
            });
            chronoItems.forEach(function (t) {
                html += renderCard(t);
            });

            html += '</details>';
        });

        transcriptList.innerHTML = html;
        if (window.lucide) lucide.createIcons();
    }

    function renderCard(t) {
        var displayText = t.correctedText || t.text || "(empty)";
        var time = t.timestamp ? formatTimestamp(t.timestamp) : "—";
        var gps = "";
        if (t.latitude != null && t.longitude != null) {
            var lat = Number(t.latitude).toFixed(6);
            var lng = Number(t.longitude).toFixed(6);
            var mapsUrl = "https://www.google.com/maps?q=" + lat + "," + lng;
            gps = '<a class="gps-link" href="' + mapsUrl + '" target="_blank" rel="noopener">'
                + '<i data-lucide="map-pin"></i> ' + lat + ', ' + lng + '</a>';
        }

        var officerLabel = "";
        if (currentUserLevel !== ACCESS_LEVELS.OFFICER && t.recordedBy) {
            var member = memberCache.get(t.recordedBy);
            var name = member ? member.displayName : t.recordedBy;
            officerLabel = '<span class="officer-name">' + escapeHtml(name) + '</span>';
        }

        return '<div class="transcript-card">'
            + '<div class="transcript-meta">'
            + '<div class="time-stamp"><i data-lucide="clock"></i><span>' + escapeHtml(time) + '</span>' + officerLabel + '</div>'
            + gps
            + '</div>'
            + '<div class="transcript-text">' + escapeHtml(displayText) + '</div>'
            + '</div>';
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
        if (currentOrgId) {
            data.orgId = currentOrgId;
        }
        if (auth.currentUser) {
            data.recordedBy = auth.currentUser.uid;
        }
        return db.collection(transcriptsPath()).add(data);
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
        db.collection(transcriptsPath()).get().then(function (snapshot) {
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

    // --------------- Record Panel (Always-On Chunked Pipeline) ---------------
    function initRecordPanel() {
        if (currentUserLevel && currentUserLevel !== ACCESS_LEVELS.OFFICER) {
            document.getElementById("toggle-record-btn").hidden = true;
            return;
        }
        var recordPanel = document.getElementById("record-panel");
        var goLiveBtn = document.getElementById("go-live-btn");
        var goStopBtn = document.getElementById("go-stop-btn");
        var vadStatusEl = document.getElementById("vad-status");
        var gpsDisplay = document.getElementById("record-gps-display");
        var chunkStatsEl = document.getElementById("chunk-stats");
        var eventLogEl = document.getElementById("event-log");
        var sensitivitySlider = document.getElementById("sensitivity-slider");
        var sensitivityValue = document.getElementById("sensitivity-value");
        var meterFill = document.querySelector("#audio-level-meter .audio-level-fill");

        var ASSEMBLYAI_KEY = "75b10e24655f44b5bba36598b4351c23";

        var audioContext = null;
        var analyserNode = null;
        var micStream = null;
        var scriptNode = null;
        var micSource = null;
        var meterRaf = null;
        var vadInterval = null;
        var isLive = false;

        // VAD state
        var vadThreshold = 0.015;
        var speechActive = false;
        var speechStartTime = 0;
        var silenceStartTime = 0;
        var SPEECH_ONSET_MS = 300;
        var SPEECH_OFFSET_MS = 800;
        var MAX_CHUNK_S = 30;
        var MIN_CHUNK_S = 1;
        var energyAboveThreshold = false;
        var energyAboveStartTime = 0;

        // Chunk recording state
        var chunkSamples = [];
        var chunkStartedAt = 0;

        // Stats
        var totalChunks = 0;
        var transcribedChunks = 0;
        var pendingChunks = 0;

        var currentGps = null;
        var recordSessionId = null;

        // Toggle panel
        document.getElementById("toggle-record-btn").addEventListener("click", function () {
            recordPanel.hidden = !recordPanel.hidden;
            if (!recordPanel.hidden && window.lucide) lucide.createIcons();
        });
        document.getElementById("close-record-panel").addEventListener("click", function () {
            recordPanel.hidden = true;
        });

        // Sensitivity slider
        sensitivitySlider.addEventListener("input", function () {
            vadThreshold = parseFloat(sensitivitySlider.value);
            sensitivityValue.textContent = vadThreshold.toFixed(3);
        });

        // ----------- Event Log -----------
        function logEvent(msg) {
            var line = document.createElement("div");
            line.className = "event-log-line";
            var now = new Date();
            var ts = now.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
            line.textContent = "[" + ts + "] " + msg;
            eventLogEl.appendChild(line);
            eventLogEl.scrollTop = eventLogEl.scrollHeight;
            // Keep max 50 entries
            while (eventLogEl.children.length > 50) {
                eventLogEl.removeChild(eventLogEl.firstChild);
            }
        }

        // ----------- Stats -----------
        function updateStats() {
            chunkStatsEl.textContent = "Chunks: " + totalChunks + " | Transcribed: " + transcribedChunks + " | Pending: " + pendingChunks;
        }

        // ----------- Audio Level Meter -----------
        function startMeter() {
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
                    gpsDisplay.textContent = pos.coords.latitude.toFixed(6) + ", " + pos.coords.longitude.toFixed(6) + " (\u00b1" + Math.round(pos.coords.accuracy) + "m)";
                    logEvent("GPS acquired: " + pos.coords.latitude.toFixed(4) + ", " + pos.coords.longitude.toFixed(4));
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
            view.setUint16(20, 1, true);
            view.setUint16(22, numChannels, true);
            view.setUint32(24, sampleRate, true);
            view.setUint32(28, byteRate, true);
            view.setUint16(32, blockAlign, true);
            view.setUint16(34, bytesPerSample * 8, true);
            writeString(36, "data");
            view.setUint32(40, dataLength, true);

            var off = 44;
            for (var i = 0; i < samples.length; i++) {
                var s = Math.max(-1, Math.min(1, samples[i]));
                view.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
                off += 2;
            }

            return new Blob([view], { type: "audio/wav" });
        }

        // ----------- AssemblyAI -----------
        function uploadToAssemblyAI(wavBlob) {
            return fetch("https://api.assemblyai.com/v2/upload", {
                method: "POST",
                headers: {
                    "Authorization": ASSEMBLYAI_KEY,
                    "Content-Type": "application/octet-stream"
                },
                body: wavBlob
            })
            .then(function (resp) {
                if (!resp.ok) throw new Error("Upload failed: " + resp.status);
                return resp.json();
            })
            .then(function (data) {
                return fetch("https://api.assemblyai.com/v2/transcript", {
                    method: "POST",
                    headers: {
                        "Authorization": ASSEMBLYAI_KEY,
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify({ audio_url: data.upload_url, speech_models: ["universal-3-pro"] })
                });
            })
            .then(function (resp) {
                if (!resp.ok) throw new Error("Transcript request failed: " + resp.status);
                return resp.json();
            })
            .then(function (data) {
                return pollTranscript(data.id);
            });
        }

        function pollTranscript(transcriptId) {
            return new Promise(function (resolve, reject) {
                function check() {
                    fetch("https://api.assemblyai.com/v2/transcript/" + transcriptId, {
                        headers: { "Authorization": ASSEMBLYAI_KEY }
                    })
                    .then(function (resp) { return resp.json(); })
                    .then(function (data) {
                        if (data.status === "completed") {
                            resolve(data);
                        } else if (data.status === "error") {
                            reject(new Error("Transcription error: " + (data.error || "unknown")));
                        } else {
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

        // ----------- VAD (Energy-based) -----------
        function computeRMS() {
            var dataArray = new Float32Array(analyserNode.fftSize);
            analyserNode.getFloatTimeDomainData(dataArray);
            var sum = 0;
            for (var i = 0; i < dataArray.length; i++) {
                sum += dataArray[i] * dataArray[i];
            }
            return Math.sqrt(sum / dataArray.length);
        }

        function vadTick() {
            if (!isLive || !analyserNode) return;

            var rms = computeRMS();
            var now = Date.now();

            if (rms >= vadThreshold) {
                if (!energyAboveThreshold) {
                    energyAboveThreshold = true;
                    energyAboveStartTime = now;
                }
                silenceStartTime = 0;

                // Speech onset: energy above threshold for SPEECH_ONSET_MS
                if (!speechActive && (now - energyAboveStartTime >= SPEECH_ONSET_MS)) {
                    speechActive = true;
                    chunkStartedAt = now;
                    chunkSamples = [];
                    vadStatusEl.textContent = "Speech detected...";
                    vadStatusEl.classList.add("vad-speech");
                    logEvent("Speech detected");
                }
            } else {
                energyAboveThreshold = false;
                energyAboveStartTime = 0;

                if (speechActive) {
                    if (!silenceStartTime) {
                        silenceStartTime = now;
                    }
                    // Speech offset: energy below threshold for SPEECH_OFFSET_MS
                    if (now - silenceStartTime >= SPEECH_OFFSET_MS) {
                        finalizeChunk("silence");
                    }
                }
            }

            // Force-split at MAX_CHUNK_S
            if (speechActive && (now - chunkStartedAt >= MAX_CHUNK_S * 1000)) {
                finalizeChunk("max-duration");
            }
        }

        function finalizeChunk(reason) {
            speechActive = false;
            silenceStartTime = 0;
            energyAboveThreshold = false;
            energyAboveStartTime = 0;
            vadStatusEl.textContent = "Processing chunk...";
            vadStatusEl.classList.remove("vad-speech");

            var duration = (Date.now() - chunkStartedAt) / 1000;
            var samples = flattenSamples(chunkSamples);
            chunkSamples = [];

            if (duration < MIN_CHUNK_S || samples.length === 0) {
                vadStatusEl.textContent = "Listening...";
                logEvent("Chunk discarded (too short: " + duration.toFixed(1) + "s)");
                return;
            }

            totalChunks++;
            pendingChunks++;
            updateStats();
            logEvent("Chunk #" + totalChunks + " (" + duration.toFixed(1) + "s, " + reason + ")");

            var sampleRate = audioContext ? audioContext.sampleRate : 16000;
            var wavBlob = encodeWAV(samples, sampleRate);
            var chunkNum = totalChunks;

            logEvent("Uploading chunk #" + chunkNum + "...");

            uploadToAssemblyAI(wavBlob)
                .then(function (result) {
                    var text = result.text || "";
                    pendingChunks--;
                    transcribedChunks++;
                    updateStats();

                    if (!text.trim()) {
                        logEvent("Chunk #" + chunkNum + ": (no speech)");
                        return;
                    }

                    var preview = text.length > 50 ? text.substring(0, 50) + "..." : text;
                    logEvent("Transcript #" + chunkNum + ": " + preview);

                    // Write to Firestore
                    var doc = {
                        text: text.trim(),
                        timestamp: Date.now(),
                        sessionId: getRecordSessionId(),
                        userId: auth.currentUser ? auth.currentUser.uid : "web-recorder"
                    };
                    if (currentOrgId) doc.orgId = currentOrgId;
                    if (auth.currentUser) doc.recordedBy = auth.currentUser.uid;
                    if (result.words) doc.words = result.words;
                    if (currentGps) {
                        doc.latitude = currentGps.latitude;
                        doc.longitude = currentGps.longitude;
                        doc.accuracy = currentGps.accuracy;
                    }
                    db.collection(transcriptsPath()).add(doc).then(function () {
                        logEvent("Saved chunk #" + chunkNum + " to Firestore");
                        loadTranscripts();
                    }).catch(function (err) {
                        logEvent("Firestore error: " + err.message);
                    });
                })
                .catch(function (err) {
                    pendingChunks--;
                    updateStats();
                    logEvent("Error chunk #" + chunkNum + ": " + err.message);
                });

            // Resume listening immediately
            if (isLive) {
                vadStatusEl.textContent = "Listening...";
            }
        }

        function flattenSamples(buffers) {
            var totalLength = 0;
            for (var i = 0; i < buffers.length; i++) totalLength += buffers[i].length;
            var merged = new Float32Array(totalLength);
            var offset = 0;
            for (var i = 0; i < buffers.length; i++) {
                merged.set(buffers[i], offset);
                offset += buffers[i].length;
            }
            return merged;
        }

        // ----------- Audio Capture (ScriptProcessor for raw PCM) -----------
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
                if (speechActive) {
                    var channelData = e.inputBuffer.getChannelData(0);
                    chunkSamples.push(new Float32Array(channelData));
                }
            };
        }

        function stopAudioCapture() {
            if (scriptNode) { scriptNode.disconnect(); scriptNode = null; }
            if (micSource) { micSource.disconnect(); micSource = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            analyserNode = null;
        }

        // ----------- Go Live / Stop -----------
        goLiveBtn.addEventListener("click", function () {
            vadStatusEl.textContent = "Requesting microphone...";
            recordSessionId = null;
            totalChunks = 0;
            transcribedChunks = 0;
            pendingChunks = 0;
            updateStats();
            eventLogEl.innerHTML = "";

            var constraints = { audio: { channelCount: 1, sampleRate: { ideal: 16000 } } };

            navigator.mediaDevices.getUserMedia(constraints)
                .then(function (stream) {
                    micStream = stream;
                    isLive = true;
                    startAudioCapture(stream);
                    startMeter();
                    fetchGps();

                    // Start VAD polling every 50ms
                    vadInterval = setInterval(vadTick, 50);

                    vadStatusEl.textContent = "Listening...";
                    goLiveBtn.hidden = true;
                    goStopBtn.hidden = false;
                    recordPanel.classList.add("is-recording");
                    logEvent("Live capture started");
                })
                .catch(function (err) {
                    vadStatusEl.textContent = "Microphone error: " + err.message;
                });
        });

        goStopBtn.addEventListener("click", function () {
            isLive = false;

            // If speech was active, finalize the current chunk
            if (speechActive) {
                finalizeChunk("stopped");
            }

            if (vadInterval) { clearInterval(vadInterval); vadInterval = null; }
            stopMeter();
            stopAudioCapture();

            if (micStream) {
                micStream.getTracks().forEach(function (t) { t.stop(); });
                micStream = null;
            }

            goStopBtn.hidden = true;
            goLiveBtn.hidden = false;
            recordPanel.classList.remove("is-recording");
            vadStatusEl.textContent = "Idle";
            vadStatusEl.classList.remove("vad-speech");
            logEvent("Live capture stopped");
        });

    } // end initRecordPanel

})();
