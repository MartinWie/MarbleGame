function initChess(gameId) {
    if (window.location.pathname !== '/chess/' + gameId) {
        history.replaceState(null, '', '/chess/' + gameId);
    }

    var chessContent = document.getElementById('chess-content');
    var resumeStalePingMs = parseInt(chessContent && chessContent.getAttribute('data-resume-stale-ping-ms') || '15000', 10);
    if (!Number.isFinite(resumeStalePingMs)) {
        resumeStalePingMs = 15000;
    }
    resumeStalePingMs = Math.max(5000, Math.min(120000, resumeStalePingMs));
    var webSocket = null;
    var countdownInterval = null;
    var reconnectTimeout = null;
    var isConnecting = false;
    var connectAttemptStartedAt = 0;
    var lastResumeReconnectAt = 0;
    var debugLastResumeTrigger = 0;
    var debugResumeReconnectAttempts = 0;
    var debugDisableScheduledReconnect = false;
    var reconnectDelayMs = 1000;
    var connectWatchdogTimeout = null;
    var realtimeShared = window.realtimeShared;
    var uiShared = window.uiShared;
    var consecutiveConnectFailures = 0;
    var realtimeFailureBannerShown = false;
    var lastPingTime = Date.now();
    var pingCheckInterval = null;
    var selectedFrom = null;
    var legalTargets = [];
    var draggingFrom = null;
    var legalTargetSet = {};
    var boardSnapshot = {};
    var animationTimeout = null;
    var moveTrailRevealTimeout = null;
    var moveTrailCleanupTimeout = null;
    var moveTrailRunId = 0;
    var suppressClickUntil = 0;
    var dragGhost = null;
    var touchMoved = false;
    var touchFrom = null;
    var touchActive = false;
    var touchHandledOnStart = false;
    var lastTouchHandledAt = 0;
    var lastTouchHandledSquare = null;
    var usePointerTouch = !!window.PointerEvent;
    var lastUpdateAt = Date.now();
    var chessClockInterval = null;
    var moveInFlight = false;
    var soundMuted = localStorage.getItem('marblegame_sound_muted') === '1';
    var audioCtx = null;
    var audioUnlocked = false;
    var previousTurnColor = '';
    var lastAnimatedMoveMeta = '';
    var rightArrowFromSquare = null;
    var rightArrowDragActive = false;
    var boardArrows = [];
    var boardMarks = [];
    var boardArrowPreview = null;
    var boardArrowHeadId = 'chess-arrow-head-' + gameId;
    var boardArrowHeadAltId = 'chess-arrow-head-alt-' + gameId;
    var boardArrowGlobalBound = false;
    var chessDebugEnabled = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';

    function closeRealtimeConnection() {
        if (connectWatchdogTimeout) {
            clearTimeout(connectWatchdogTimeout);
            connectWatchdogTimeout = null;
        }
        if (realtimeShared) {
            webSocket = realtimeShared.closeWebSocket(webSocket);
        } else if (webSocket) {
            webSocket.onopen = null;
            webSocket.onmessage = null;
            webSocket.onerror = null;
            webSocket.onclose = null;
            try {
                webSocket.close();
            } catch (_) {}
            webSocket = null;
        }
    }

    function bindShareButton() {
        if (uiShared) {
            uiShared.bindShareButton({ buttonId: 'share-btn', toastDurationMs: 1800 });
            return;
        }
        var shareBtn = document.getElementById('share-btn');
        if (!shareBtn || shareBtn.dataset.shareBound === '1') return;
        shareBtn.dataset.shareBound = '1';

        function toAbsoluteShareUrl(pathOrUrl) {
            if (!pathOrUrl) return window.location.href;
            try {
                var parsed = new URL(pathOrUrl, window.location.origin);
                if (!/^https?:$/.test(parsed.protocol)) return window.location.href;
                if (parsed.origin !== window.location.origin) return window.location.href;
                return parsed.toString();
            } catch (_) {
                return window.location.origin + pathOrUrl;
            }
        }

        function showCopied(btn) {
            var shareText = btn.dataset.shareText || 'Share';
            var copiedText = btn.dataset.copiedText || shareText;
            btn.setAttribute('aria-label', copiedText);
            btn.setAttribute('title', copiedText);
            btn.classList.add('copied');
            showToast(copiedText, true);
            setTimeout(function() {
                btn.setAttribute('aria-label', shareText);
                btn.setAttribute('title', shareText);
                btn.classList.remove('copied');
            }, 2000);
        }

        function fallbackCopy(url, btn) {
            var ta = document.createElement('textarea');
            ta.value = url;
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            ta.setSelectionRange(0, 99999);
            try {
                document.execCommand('copy');
                showCopied(btn);
            } catch (_) {
                prompt('Copy this link:', url);
            }
            document.body.removeChild(ta);
        }

        function clipboardCopy(url, btn) {
            if (navigator.clipboard && navigator.clipboard.writeText && window.isSecureContext) {
                navigator.clipboard.writeText(url).then(function() {
                    showCopied(btn);
                }).catch(function() {
                    fallbackCopy(url, btn);
                });
            } else {
                fallbackCopy(url, btn);
            }
        }

        shareBtn.addEventListener('click', function() {
            var btn = this;
            var shareUrl = toAbsoluteShareUrl(btn.dataset.shareUrl || '');
            var shareTitle = btn.dataset.shareTitle || '';
            var shareMessage = btn.dataset.shareMessage || '';
            var isMobile = /iPhone|iPad|iPod|Android/i.test(navigator.userAgent);

            if (isMobile && navigator.share) {
                navigator.share({
                    title: shareTitle,
                    text: shareMessage,
                    url: shareUrl,
                }).catch(function() {});
                return;
            }

            clipboardCopy(shareUrl, btn);
        });
    }

    function showRealtimeFailureBanner() {
        if (realtimeFailureBannerShown) return;
        realtimeFailureBannerShown = true;
        showToast('Realtime connection lost. Trying to reconnect...', true);
    }

    function ensureAudioContext() {
        if (!audioCtx) {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (Ctx) audioCtx = new Ctx();
        }
        return audioCtx;
    }

    function unlockAudio() {
        var ctx = ensureAudioContext();
        if (!ctx) return;
        if (ctx.state === 'suspended') {
            ctx.resume().catch(function() {});
        }
        audioUnlocked = true;
    }

    function playTone(freq, durationMs, gain) {
        if (soundMuted) return;
        var ctx = ensureAudioContext();
        if (!ctx || !audioUnlocked) return;
        var osc = ctx.createOscillator();
        var amp = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.value = freq;
        amp.gain.value = gain;
        osc.connect(amp);
        amp.connect(ctx.destination);
        var now = ctx.currentTime;
        osc.start(now);
        amp.gain.exponentialRampToValueAtTime(0.0001, now + durationMs / 1000);
        osc.stop(now + durationMs / 1000);
    }

    function playRoundStartSound() {
        playTone(523, 180, 0.06);
        setTimeout(function() { playTone(659, 220, 0.05); }, 140);
    }

    function playYourTurnSound() {
        playTone(784, 220, 0.05);
    }

    function syncSoundButton() {
        if (uiShared) return;
        var btn = document.getElementById('sound-btn');
        if (!btn) return;
        var onText = btn.dataset.soundOn || 'Sound On';
        var offText = btn.dataset.soundOff || 'Sound Off';
        var iconWrap = btn.querySelector('.sound-icon');
        if (iconWrap) {
            iconWrap.innerHTML = soundMuted ? '🔇' : '🔊';
        }
        var stateText = soundMuted ? offText : onText;
        btn.setAttribute('aria-label', stateText);
        btn.setAttribute('title', stateText);
    }

    function bindSoundButton() {
        if (uiShared) {
            uiShared.bindSoundButton({
                buttonId: 'sound-btn',
                storageKey: 'marblegame_sound_muted',
                onToggle: function(nextMuted) {
                    soundMuted = !!nextMuted;
                    unlockAudio();
                },
            });
            return;
        }
        var btn = document.getElementById('sound-btn');
        if (!btn || btn.dataset.soundBound === '1') return;
        btn.dataset.soundBound = '1';
        syncSoundButton();
        btn.addEventListener('click', function() {
            soundMuted = !soundMuted;
            localStorage.setItem('marblegame_sound_muted', soundMuted ? '1' : '0');
            unlockAudio();
            syncSoundButton();
        });
    }

    function bindQrButton() {
        var qrBtn = document.getElementById('qr-btn');
        var qrModal = document.getElementById('qr-modal');
        var qrImage = document.getElementById('qr-image');
        var shareBtn = document.getElementById('share-btn');
        if (!qrBtn || !qrModal || !qrImage || !shareBtn || qrBtn.dataset.qrBound === '1') return;
        qrBtn.dataset.qrBound = '1';

        qrModal.addEventListener('close', function() {
            qrBtn.classList.remove('active');
            qrBtn.setAttribute('aria-expanded', 'false');
        });

        qrModal.addEventListener('click', function(e) {
            var box = qrModal.querySelector('.qr-modal-box');
            if (box && !box.contains(e.target)) {
                qrModal.close();
            }
        });

        qrBtn.addEventListener('click', function() {
            var sharePath = shareBtn.dataset.shareUrl || '';
            if (!sharePath) return;
            if (qrImage.dataset.src !== sharePath) {
                qrImage.src = '/qr?target=' + encodeURIComponent(sharePath);
                qrImage.dataset.src = sharePath;
            }
            if (!qrModal.open) {
                qrModal.showModal();
                qrBtn.classList.add('active');
                qrBtn.setAttribute('aria-expanded', 'true');
            } else {
                qrModal.close();
                qrBtn.classList.remove('active');
                qrBtn.setAttribute('aria-expanded', 'false');
            }
        });
    }

    function bindSurrenderButton() {
        var surrenderBtn = document.getElementById('surrender-btn');
        var surrenderModal = document.getElementById('surrender-modal');
        var cancelBtn = document.getElementById('surrender-cancel-btn');
        var confirmBtn = document.getElementById('surrender-confirm-btn');
        if (!surrenderBtn || !surrenderModal || !cancelBtn || !confirmBtn || surrenderBtn.dataset.surrenderBound === '1') return;
        surrenderBtn.dataset.surrenderBound = '1';

        surrenderModal.addEventListener('close', function() {
            surrenderBtn.classList.remove('active');
        });

        surrenderModal.addEventListener('click', function(e) {
            var box = surrenderModal.querySelector('.surrender-modal-box');
            if (box && !box.contains(e.target)) {
                surrenderModal.close();
            }
        });

        surrenderBtn.addEventListener('click', function() {
            surrenderBtn.classList.add('active');
            if (typeof surrenderModal.showModal === 'function') {
                if (!surrenderModal.open) {
                    surrenderModal.showModal();
                }
            } else {
                surrenderModal.setAttribute('open', '');
            }
        });

        cancelBtn.addEventListener('click', function() {
            if (surrenderModal.open) {
                surrenderModal.close();
            }
            surrenderBtn.classList.remove('active');
        });

        confirmBtn.addEventListener('click', function() {
            if (surrenderModal.open) {
                surrenderModal.close();
            }
            surrenderBtn.classList.remove('active');
        });
    }

    function startCountdowns() {
        if (countdownInterval) clearInterval(countdownInterval);
        countdownInterval = setInterval(function() {
            var countdowns = document.querySelectorAll('.player-countdown');
            countdowns.forEach(function(el) {
                var seconds = parseInt(el.dataset.seconds) - 1;
                if (el.dataset.fired === 'true') return;
                el.dataset.seconds = seconds;
                var timerSpan = el.querySelector('.countdown-timer');
                if (timerSpan) timerSpan.textContent = seconds + 's';
                if (seconds <= 0) {
                    el.dataset.fired = 'true';
                    el.dataset.seconds = '0';
                }
            });

            var autoRestartLine = document.querySelector('.auto-restart-line');
            if (autoRestartLine && autoRestartLine.dataset.seconds) {
                var s = parseInt(autoRestartLine.dataset.seconds, 10);
                if (!isNaN(s) && s > 0) {
                    autoRestartLine.dataset.seconds = String(s - 1);
                    var txt = autoRestartLine.textContent || '';
                    autoRestartLine.textContent = txt.replace(/\d+s/, (s - 1) + 's');
                }
            }
        }, 1000);

        var boardEl = document.querySelector('.chess-board');
        var timedMode = boardEl && boardEl.getAttribute('data-timed-mode') === '1';
        if (chessClockInterval) {
            clearInterval(chessClockInterval);
            chessClockInterval = null;
        }
        if (timedMode) {
            chessClockInterval = setInterval(function() {
                updateLocalClockLine();
            }, 1000);
        }
    }

    function updateLocalClockLine() {
        var boardEl = document.querySelector('.chess-board');
        if (!boardEl || boardEl.getAttribute('data-timed-mode') !== '1') return;
        if (boardEl.getAttribute('data-clock-started') !== '1') return;

        var turn = boardEl.getAttribute('data-turn') || '';
        var w = parseInt(boardEl.getAttribute('data-white-seconds') || '0', 10);
        var b = parseInt(boardEl.getAttribute('data-black-seconds') || '0', 10);

        if (turn === 'white' && w > 0) {
            w -= 1;
            boardEl.setAttribute('data-white-seconds', String(w));
        } else if (turn === 'black' && b > 0) {
            b -= 1;
            boardEl.setAttribute('data-black-seconds', String(b));
        }

        function formatClock(seconds) {
            var s = Math.max(0, parseInt(seconds || 0, 10));
            var m = Math.floor(s / 60);
            var rem = s % 60;
            return m + ':' + (rem < 10 ? '0' : '') + rem;
        }

        var ownEl = document.querySelector('.clock-own');
        var enemyEl = document.querySelector('.clock-enemy');
        if (ownEl) {
            var ownSide = ownEl.getAttribute('data-clock-side') || 'white';
            ownEl.textContent = formatClock(ownSide === 'white' ? w : b);
        }
        if (enemyEl) {
            var enemySide = enemyEl.getAttribute('data-clock-side') || 'black';
            enemyEl.textContent = formatClock(enemySide === 'white' ? w : b);
        }
    }

    function handleChessUpdate(data) {
        lastUpdateAt = Date.now();
        moveInFlight = false;
        var prevTurn = previousTurnColor;
        var previousSnapshot = boardSnapshot;
        var patched = patchIncrementalState(data);
        if (!patched) {
            chessContent.innerHTML = data;
        }
        htmx.process(chessContent);
        selectedFrom = null;
        legalTargets = [];
        legalTargetSet = {};
        updateMoveUI();
        bindShareButton();
        bindSoundButton();
        bindQrButton();
        bindSurrenderButton();
        bindBoardInteractions();
        boardSnapshot = captureBoardSnapshot();
        var boardEl = document.querySelector('.chess-board');
        if (boardEl) {
            previousTurnColor = boardEl.getAttribute('data-turn') || '';
            if (!boardEl.getAttribute('data-last-move-meta')) {
                lastAnimatedMoveMeta = '';
            }
            var myColor = boardEl.getAttribute('data-your-color') || '';
            if (prevTurn && previousTurnColor && prevTurn !== previousTurnColor && myColor === previousTurnColor) {
                playYourTurnSound();
            }
            var moveMeta = boardEl.getAttribute('data-last-move-meta') || '';
            if (moveMeta && moveMeta !== lastAnimatedMoveMeta) {
                playMoveAnimations(previousSnapshot);
                lastAnimatedMoveMeta = moveMeta;
            }
        }
        if (!prevTurn && previousTurnColor) {
            playRoundStartSound();
        }
        startCountdowns();
    }

    function scheduleReconnect() {
        if (debugDisableScheduledReconnect) {
            return;
        }
        if (realtimeShared) {
            var state = {
                reconnectTimeout: reconnectTimeout,
                reconnectDelayMs: reconnectDelayMs,
            };
            realtimeShared.scheduleReconnect(state, function() {
                reconnectTimeout = null;
                connect();
            });
            reconnectTimeout = state.reconnectTimeout;
            reconnectDelayMs = state.reconnectDelayMs;
            return;
        }
        if (!reconnectTimeout) {
            var delay = reconnectDelayMs;
            var jitter = Math.floor(Math.random() * 250);
            var waitMs = delay + jitter;
            reconnectTimeout = setTimeout(function() {
                reconnectTimeout = null;
                connect();
            }, waitMs);
            reconnectDelayMs = Math.min(30000, reconnectDelayMs * 2);
        }
    }

    function connectWs() {
        if (typeof window.WebSocket !== 'function') {
            isConnecting = false;
            consecutiveConnectFailures += 1;
            if (consecutiveConnectFailures >= 5) {
                showRealtimeFailureBanner();
            }
            scheduleReconnect();
            return;
        }
        var wsTarget = realtimeShared
            ? realtimeShared.wsUrl('/ws/chess/' + gameId)
            : ((window.location.protocol === 'https:' ? 'wss://' : 'ws://') + window.location.host + '/ws/chess/' + gameId);
        webSocket = new WebSocket(wsTarget);
        window.__lastWs = webSocket;
        if (chessDebugEnabled) {
            window.__chessDebug = window.__chessDebug || {};
            window.__chessDebug.wsState = function() {
                return {
                    hasWs: !!webSocket,
                    readyState: webSocket ? webSocket.readyState : -1,
                    isConnecting: isConnecting,
                    reconnectDelayMs: reconnectDelayMs,
                    connectAttemptStartedAt: connectAttemptStartedAt,
                    lastResumeTriggerAt: debugLastResumeTrigger,
                    resumeReconnectAttempts: debugResumeReconnectAttempts,
                };
            };
        }
        lastPingTime = Date.now();

        connectWatchdogTimeout = setTimeout(function() {
            if (isConnecting && webSocket && webSocket.readyState === WebSocket.CONNECTING) {
                isConnecting = false;
                closeRealtimeConnection();
                scheduleReconnect();
            }
        }, 10000);

        webSocket.onopen = function() {
            isConnecting = false;
            connectAttemptStartedAt = 0;
            reconnectDelayMs = 1000;
            consecutiveConnectFailures = 0;
            realtimeFailureBannerShown = false;
            if (connectWatchdogTimeout) {
                clearTimeout(connectWatchdogTimeout);
                connectWatchdogTimeout = null;
            }
        };

        webSocket.onmessage = function(e) {
            var text = typeof e.data === 'string' ? e.data : '';
            if (!text) return;
            if (text === '__PING__') {
                lastPingTime = Date.now();
                return;
            }
            if (text.indexOf('__CHESS_UPDATE__:') === 0) {
                lastPingTime = Date.now();
                handleChessUpdate(text.slice('__CHESS_UPDATE__:'.length));
            }
        };

        webSocket.onerror = function() {
            // Keep reconnect logic centralized in onclose.
        };

        webSocket.onclose = function() {
            isConnecting = false;
            connectAttemptStartedAt = 0;
            moveInFlight = false;
            if (connectWatchdogTimeout) {
                clearTimeout(connectWatchdogTimeout);
                connectWatchdogTimeout = null;
            }
            closeRealtimeConnection();
            consecutiveConnectFailures += 1;
            if (consecutiveConnectFailures >= 5) {
                showRealtimeFailureBanner();
            }
            scheduleReconnect();
        };
    }

    function connect() {
        if (isConnecting) return;
        isConnecting = true;
        connectAttemptStartedAt = Date.now();

        if (reconnectTimeout) {
            if (realtimeShared) {
                realtimeShared.clearReconnectTimer({ reconnectTimeout: reconnectTimeout });
                reconnectTimeout = null;
            } else {
                clearTimeout(reconnectTimeout);
                reconnectTimeout = null;
            }
        }

        closeRealtimeConnection();
        connectWs();
    }

    function ensureConnectedAfterResume() {
        var now = Date.now();
        if (now - lastResumeReconnectAt < 700) {
            return;
        }
        lastResumeReconnectAt = now;

        var readyState = webSocket ? webSocket.readyState : WebSocket.CLOSED;
        var isOpen = readyState === WebSocket.OPEN;
        var isConnectingSocket = readyState === WebSocket.CONNECTING;
        var staleOpenConnection = isOpen && (now - lastPingTime > resumeStalePingMs);
        var stuckConnect = (isConnecting || isConnectingSocket) && connectAttemptStartedAt > 0 && (now - connectAttemptStartedAt > 4000);

        if (isOpen && !staleOpenConnection && !stuckConnect) {
            return;
        }

        if (isConnectingSocket && !stuckConnect) {
            return;
        }

        if (stuckConnect || !isOpen || staleOpenConnection) {
            debugLastResumeTrigger = now;
            debugResumeReconnectAttempts += 1;
            isConnecting = false;
            connect();
        }
    }

    function debugSimulateResumeRecovery() {
        debugLastResumeTrigger = Date.now();
        debugResumeReconnectAttempts += 1;
        isConnecting = false;
        connect();
    }

    function debugGetResumeConfig() {
        return {
            resumeStalePingMs: resumeStalePingMs,
        };
    }

    function debugSetResumeState(state) {
        if (!state) return;
        if (typeof state.lastPingDeltaMs === 'number') {
            lastPingTime = Date.now() - Math.max(0, state.lastPingDeltaMs);
        }
        if (typeof state.isConnecting === 'boolean') {
            isConnecting = !!state.isConnecting;
        }
        if (typeof state.connectAttemptAgeMs === 'number') {
            connectAttemptStartedAt = Date.now() - Math.max(0, state.connectAttemptAgeMs);
        }
        if (typeof state.forceReadyState === 'number' && webSocket) {
            try {
                Object.defineProperty(webSocket, 'readyState', {
                    configurable: true,
                    get: function() { return state.forceReadyState; },
                });
            } catch (_) {}
        }
    }

    function onResumeVisibilityChange() {
        if (document.visibilityState === 'visible') {
            ensureConnectedAfterResume();
        }
    }

    function onResumeFocus() {
        ensureConnectedAfterResume();
    }

    function onResumePageShow() {
        ensureConnectedAfterResume();
    }

    function patchIncrementalState(newHtml) {
        var currentBoard = chessContent.querySelector('.chess-board');
        if (!currentBoard) return false;

        var tmp = document.createElement('div');
        tmp.innerHTML = newHtml;
        var incomingBoard = tmp.querySelector('.chess-board');
        if (!incomingBoard) return false;

        var currentPerspective = currentBoard.getAttribute('data-perspective') || 'white';
        var incomingPerspective = incomingBoard.getAttribute('data-perspective') || 'white';
        if (currentPerspective !== incomingPerspective) {
            clearBoardAnnotations();
            return false;
        }

        if (currentBoard.querySelector('.square-label') && !incomingBoard.querySelector('.square-label')) return false;

        var currentYourColor = currentBoard.getAttribute('data-your-color') || '';
        var incomingYourColor = incomingBoard.getAttribute('data-your-color') || '';
        if (currentYourColor !== incomingYourColor) {
            clearBoardAnnotations();
            return false;
        }

        var currentFirstSquare = currentBoard.querySelector('.chess-square');
        var incomingFirstSquare = incomingBoard.querySelector('.chess-square');
        if (!currentFirstSquare || !incomingFirstSquare) return false;
        if (currentFirstSquare.getAttribute('data-square') !== incomingFirstSquare.getAttribute('data-square')) return false;

        patchBoardSquares(currentBoard, incomingBoard);

        var currentPlayers = chessContent.querySelector('.players-section');
        var incomingPlayers = tmp.querySelector('.players-section');
        if (currentPlayers && incomingPlayers) {
            currentPlayers.replaceWith(incomingPlayers);
        }

        var currentStatusStrip = chessContent.querySelector('.chess-status-strip');
        var incomingStatusStrip = tmp.querySelector('.chess-status-strip');
        if (!currentStatusStrip || !incomingStatusStrip) return false;

        var currentBoardStage = chessContent.querySelector('.chess-board-stage-outside');
        var incomingBoardStage = tmp.querySelector('.chess-board-stage-outside');
        if (!currentBoardStage || !incomingBoardStage) return false;

        var currentBoardShell = currentBoardStage.querySelector('.chess-board-shell');
        var incomingBoardShell = incomingBoardStage.querySelector('.chess-board-shell');
        if (!currentBoardShell || !incomingBoardShell) return false;

        incomingBoardShell.replaceWith(currentBoardShell);
        currentStatusStrip.replaceWith(incomingStatusStrip);
        currentBoardStage.replaceWith(incomingBoardStage);

        var currentStatus = chessContent.querySelector('.your-status');
        var incomingStatus = tmp.querySelector('.your-status');
        if (currentStatus && incomingStatus) {
            currentStatus.replaceWith(incomingStatus);
        } else if (currentStatus && !incomingStatus) {
            currentStatus.remove();
        } else if (!currentStatus && incomingStatus) {
            chessContent.appendChild(incomingStatus);
        }

        return true;
    }

    function patchBoardSquares(currentBoard, incomingBoard) {
        currentBoard.setAttribute('data-last-move-meta', incomingBoard.getAttribute('data-last-move-meta') || '');
        currentBoard.setAttribute('data-perspective', incomingBoard.getAttribute('data-perspective') || 'white');
        currentBoard.setAttribute('data-checked-king', incomingBoard.getAttribute('data-checked-king') || '');
        currentBoard.setAttribute('data-show-last-move', incomingBoard.getAttribute('data-show-last-move') || '0');
        currentBoard.setAttribute('data-turn', incomingBoard.getAttribute('data-turn') || '');
        currentBoard.setAttribute('data-your-color', incomingBoard.getAttribute('data-your-color') || '');
        currentBoard.setAttribute('data-en-passant', incomingBoard.getAttribute('data-en-passant') || '');
        currentBoard.setAttribute('data-castle-wk', incomingBoard.getAttribute('data-castle-wk') || '0');
        currentBoard.setAttribute('data-castle-wq', incomingBoard.getAttribute('data-castle-wq') || '0');
        currentBoard.setAttribute('data-castle-bk', incomingBoard.getAttribute('data-castle-bk') || '0');
        currentBoard.setAttribute('data-castle-bq', incomingBoard.getAttribute('data-castle-bq') || '0');
        currentBoard.setAttribute('data-timed-mode', incomingBoard.getAttribute('data-timed-mode') || '0');
        currentBoard.setAttribute('data-white-seconds', incomingBoard.getAttribute('data-white-seconds') || '0');
        currentBoard.setAttribute('data-black-seconds', incomingBoard.getAttribute('data-black-seconds') || '0');
        currentBoard.setAttribute('data-clock-started', incomingBoard.getAttribute('data-clock-started') || '0');

        currentBoard.querySelectorAll('.chess-square').forEach(function(squareEl) {
            var square = squareEl.getAttribute('data-square');
            if (!square) return;

            var incomingSquare = incomingBoard.querySelector('.chess-square[data-square="' + square + '"]');
            if (!incomingSquare) return;

            var incomingPiece = incomingSquare.getAttribute('data-piece') || '';
            squareEl.setAttribute('data-piece', incomingPiece);
            squareEl.setAttribute('draggable', incomingSquare.getAttribute('draggable') || 'false');

            var incomingPieceEl = incomingSquare.querySelector('.chess-piece');
            var currentPieceEl = squareEl.querySelector('.chess-piece');
            if (currentPieceEl && incomingPieceEl) {
                currentPieceEl.className = incomingPieceEl.className;
                currentPieceEl.textContent = incomingPieceEl.textContent || '';
            }

            var currentRankLabel = squareEl.querySelector('.rank-label');
            var incomingRankLabel = incomingSquare.querySelector('.rank-label');
            if (incomingRankLabel && !currentRankLabel) {
                var newRankLabel = incomingRankLabel.cloneNode(true);
                squareEl.insertBefore(newRankLabel, squareEl.firstChild);
            } else if (!incomingRankLabel && currentRankLabel) {
                currentRankLabel.remove();
            }

            var currentFileLabel = squareEl.querySelector('.file-label');
            var incomingFileLabel = incomingSquare.querySelector('.file-label');
            if (incomingFileLabel && !currentFileLabel) {
                var newFileLabel = incomingFileLabel.cloneNode(true);
                squareEl.insertBefore(newFileLabel, squareEl.firstChild);
            } else if (!incomingFileLabel && currentFileLabel) {
                currentFileLabel.remove();
            }
        });

        applyHighlights();
        renderBoardArrows();
    }

    function applyRenderedState(htmlFragment) {
        if (!chessContent) return;
        chessContent.innerHTML = htmlFragment;
        htmx.process(chessContent);
        selectedFrom = null;
        legalTargets = [];
        legalTargetSet = {};
        updateMoveUI();
        bindShareButton();
        bindSoundButton();
        bindQrButton();
        bindSurrenderButton();
        bindBoardInteractions();
        boardSnapshot = captureBoardSnapshot();
        lastUpdateAt = Date.now();
        var boardEl = document.querySelector('.chess-board');
        if (boardEl) {
            previousTurnColor = boardEl.getAttribute('data-turn') || '';
            var moveMeta = boardEl.getAttribute('data-last-move-meta') || '';
            if (moveMeta) {
                lastAnimatedMoveMeta = moveMeta;
            }
        }
        startCountdowns();
    }

    function refreshStateFromServer() {
        return fetch('/chess/' + gameId)
            .then(function(response) {
                if (!response.ok) return '';
                return response.text();
            })
            .then(function(html) {
                if (!html) return;
                var tmp = document.createElement('div');
                tmp.innerHTML = html;
                var incomingContent = tmp.querySelector('#chess-content');
                if (incomingContent) {
                    applyRenderedState(incomingContent.innerHTML);
                }
            })
            .catch(function() {});
    }

    function ensureMoveVisibleOrRefresh(fromSquare, toSquare, submittedAt) {
        setTimeout(function() {
            var updateSeen = lastUpdateAt > submittedAt;
            var boardEl = document.querySelector('.chess-board');
            var moveMeta = boardEl ? (boardEl.getAttribute('data-last-move-meta') || '') : '';
            var expectedToken = fromSquare + '-' + toSquare;
            var moveSeen = moveMeta.indexOf(expectedToken) !== -1;
            if (!updateSeen || !moveSeen) {
                refreshStateFromServer();
            }
        }, 1200);
    }

    function updateMoveUI() {
        var fromInput = document.getElementById('chess-from');
        var toInput = document.getElementById('chess-to');

        if (!fromInput || !toInput) return;
        fromInput.value = selectedFrom || '';
    }

    function submitMove(fromSquare, toSquare) {
        if (!fromSquare || !toSquare || moveInFlight) return;
        var moveForm = document.querySelector('.chess-move-form');
        var invalidMsg = moveForm ? moveForm.dataset.invalidMsg : 'Invalid move';
        var networkMsg = moveForm ? moveForm.dataset.networkMsg : 'Connection problem';
        var submittedAt = Date.now();

        moveInFlight = true;

        var body = new URLSearchParams({ from: fromSquare, to: toSquare });

        // Clear selection/highlights right away to avoid stale move options.
        selectedFrom = null;
        legalTargets = [];
        legalTargetSet = {};
        var fromInput = document.getElementById('chess-from');
        var toInput = document.getElementById('chess-to');
        if (fromInput) fromInput.value = '';
        if (toInput) toInput.value = '';
        applyHighlights();
        updateMoveUI();

        fetch('/chess/' + gameId + '/move', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: body.toString(),
        })
            .then(function(response) {
                if (!response.ok) {
                    moveInFlight = false;
                    if (response.status === 409) {
                        showToast(moveForm ? (moveForm.dataset.notYourTurnMsg || invalidMsg) : invalidMsg);
                    } else {
                        showToast(invalidMsg);
                    }
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                    return;
                }
                syncTurnMetadataAfterLocalMove(fromSquare, toSquare);
                ensureMoveVisibleOrRefresh(fromSquare, toSquare, submittedAt);
            })
            .catch(function() {
                moveInFlight = false;
                showToast(networkMsg);
            });
    }

    function maybeShowCoachmark() {
        if (localStorage.getItem('chess_hint_seen') === '1') return;
        if (document.getElementById('chess-coachmark')) return;

        var moveForm = document.querySelector('.chess-move-form');
        if (!moveForm) return;

        var coach = document.createElement('div');
        coach.id = 'chess-coachmark';
        coach.className = 'chess-coachmark';
        coach.textContent = moveForm.dataset.hintText || 'Tap piece, then target. Or drag.';
        document.body.appendChild(coach);

        requestAnimationFrame(function() {
            coach.classList.add('show');
        });

        setTimeout(function() {
            hideCoachmark();
        }, 5000);
    }

    function hideCoachmark() {
        var coach = document.getElementById('chess-coachmark');
        if (!coach) return;
        coach.classList.remove('show');
        setTimeout(function() {
            if (coach.parentNode) coach.parentNode.removeChild(coach);
        }, 220);
    }


    function showToast(message, atTop) {
        if (uiShared) {
            uiShared.showToast(message, atTop, 1800);
            return;
        }
        if (!message) return;

        var existing = document.getElementById('chess-toast');
        if (existing) existing.remove();

        var toast = document.createElement('div');
        toast.id = 'chess-toast';
        toast.className = 'chess-toast';
        if (atTop) toast.classList.add('toast-top');
        toast.textContent = message;
        document.body.appendChild(toast);

        requestAnimationFrame(function() {
            toast.classList.add('show');
        });

        setTimeout(function() {
            toast.classList.remove('show');
            setTimeout(function() {
                if (toast.parentNode) toast.parentNode.removeChild(toast);
            }, 220);
        }, 1800);
    }

    function createDragGhost(pieceEl) {
        var ghost = document.createElement('div');
        ghost.className = 'chess-drag-ghost';
        ghost.textContent = pieceEl ? (pieceEl.textContent || '') : '';
        if (pieceEl && pieceEl.classList.contains('piece-black')) {
            ghost.classList.add('piece-black');
        } else {
            ghost.classList.add('piece-white');
        }
        ghost.style.position = 'fixed';
        ghost.style.left = '-1000px';
        ghost.style.top = '-1000px';
        ghost.style.pointerEvents = 'none';
        document.body.appendChild(ghost);
        return ghost;
    }

    function supportsRightMouseArrows() {
        if (!window.matchMedia) return false;
        return window.matchMedia('(hover: hover) and (pointer: fine)').matches;
    }

    function ensureArrowLayer() {
        var boardShell = document.getElementById('chess-board-shell');
        var boardEl = boardShell ? boardShell.querySelector('.chess-board') : null;
        if (!boardShell || !boardEl) return null;

        var svg = boardShell.querySelector('.chess-arrow-layer');
        if (!svg) {
            var ns = 'http://www.w3.org/2000/svg';
            svg = document.createElementNS(ns, 'svg');
            svg.classList.add('chess-arrow-layer');
            svg.setAttribute('aria-hidden', 'true');
            svg.setAttribute('focusable', 'false');

            var defs = document.createElementNS(ns, 'defs');
            var marker = document.createElementNS(ns, 'marker');
            marker.setAttribute('id', boardArrowHeadId);
            marker.setAttribute('markerWidth', '14');
            marker.setAttribute('markerHeight', '14');
            marker.setAttribute('refX', '2.8');
            marker.setAttribute('refY', '7');
            marker.setAttribute('orient', 'auto');
            marker.setAttribute('markerUnits', 'userSpaceOnUse');

            var markerPath = document.createElementNS(ns, 'path');
            markerPath.setAttribute('d', 'M 0 0 L 14 7 L 0 14 z');
            markerPath.setAttribute('fill', '#ffc669');

            var markerAlt = document.createElementNS(ns, 'marker');
            markerAlt.setAttribute('id', boardArrowHeadAltId);
            markerAlt.setAttribute('markerWidth', '14');
            markerAlt.setAttribute('markerHeight', '14');
            markerAlt.setAttribute('refX', '2.8');
            markerAlt.setAttribute('refY', '7');
            markerAlt.setAttribute('orient', 'auto');
            markerAlt.setAttribute('markerUnits', 'userSpaceOnUse');

            var markerPathAlt = document.createElementNS(ns, 'path');
            markerPathAlt.setAttribute('d', 'M 0 0 L 14 7 L 0 14 z');
            markerPathAlt.setAttribute('fill', '#73dcff');
            defs.appendChild(marker);
            marker.appendChild(markerPath);
            defs.appendChild(markerAlt);
            markerAlt.appendChild(markerPathAlt);
            svg.appendChild(defs);
            boardShell.appendChild(svg);
        }

        var boardRect = boardEl.getBoundingClientRect();
        var width = Math.max(1, Math.round(boardRect.width));
        var height = Math.max(1, Math.round(boardRect.height));
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        return svg;
    }

    function squareCenterPoint(square) {
        var boardEl = document.querySelector('.chess-board');
        if (!boardEl) return null;
        var squareEl = boardEl.querySelector('.chess-square[data-square="' + square + '"]');
        if (!squareEl) return null;

        var boardRect = boardEl.getBoundingClientRect();
        var squareRect = squareEl.getBoundingClientRect();
        return {
            x: (squareRect.left - boardRect.left) + (squareRect.width / 2),
            y: (squareRect.top - boardRect.top) + (squareRect.height / 2),
            boardWidth: boardRect.width,
        };
    }

    function drawBoardArrow(svg, arrow) {
        if (!arrow || !arrow.from || !arrow.to || arrow.from === arrow.to) return;
        var fromPoint = squareCenterPoint(arrow.from);
        var toPoint = squareCenterPoint(arrow.to);
        if (!fromPoint || !toPoint) return;

        var dx = toPoint.x - fromPoint.x;
        var dy = toPoint.y - fromPoint.y;
        var len = Math.sqrt(dx * dx + dy * dy);
        if (len < 4) return;

        var dirX = dx / len;
        var dirY = dy / len;
        var strokeWidth = Math.max(4, Math.min(8.5, fromPoint.boardWidth / 88));
        var startPad = Math.min(12, len * 0.18);
        var endPad = Math.min(len * 0.42, 11.6);
        var x1 = fromPoint.x + (dirX * startPad);
        var y1 = fromPoint.y + (dirY * startPad);
        var x2 = toPoint.x - (dirX * endPad);
        var y2 = toPoint.y - (dirY * endPad);
        var markerId = arrow.alt ? boardArrowHeadAltId : boardArrowHeadId;

        var ns = 'http://www.w3.org/2000/svg';
        var line = document.createElementNS(ns, 'line');
        line.classList.add('chess-arrow-shape', 'chess-arrow-line');
        if (arrow.preview) line.classList.add('preview');
        if (arrow.alt) line.classList.add('alt');
        line.setAttribute('x1', String(x1));
        line.setAttribute('y1', String(y1));
        line.setAttribute('x2', String(x2));
        line.setAttribute('y2', String(y2));
        line.setAttribute('stroke-width', String(strokeWidth));
        line.setAttribute('marker-end', 'url(#' + markerId + ')');

        var originDot = document.createElementNS(ns, 'circle');
        originDot.classList.add('chess-arrow-shape', 'chess-arrow-origin');
        if (arrow.preview) originDot.classList.add('preview');
        if (arrow.alt) originDot.classList.add('alt');
        originDot.setAttribute('cx', String(x1));
        originDot.setAttribute('cy', String(y1));
        originDot.setAttribute('r', String(Math.max(1.5, strokeWidth * 0.42)));

        svg.appendChild(originDot);
        svg.appendChild(line);
    }

    function renderBoardArrows() {
        var svg = ensureArrowLayer();
        if (!svg) return;

        svg.querySelectorAll('.chess-arrow-shape').forEach(function(el) {
            el.remove();
        });

        boardArrows.forEach(function(arrow) {
            drawBoardArrow(svg, arrow);
        });

        if (boardArrowPreview) {
            drawBoardArrow(svg, { from: boardArrowPreview.from, to: boardArrowPreview.to, alt: !!boardArrowPreview.alt, preview: true });
        }

        document.querySelectorAll('.chess-square.marked, .chess-square.marked-alt').forEach(function(el) {
            el.classList.remove('marked', 'marked-alt');
        });
        boardMarks.forEach(function(mark) {
            var squareEl = document.querySelector('.chess-square[data-square="' + mark.square + '"]');
            if (!squareEl) return;
            squareEl.classList.add(mark.alt ? 'marked-alt' : 'marked');
        });
    }

    function clearBoardAnnotations() {
        if (!boardArrows.length && !boardMarks.length && !boardArrowPreview) return;
        boardArrows = [];
        boardMarks = [];
        boardArrowPreview = null;
        renderBoardArrows();
    }

    if (chessDebugEnabled) {
        window.__chessDebug = window.__chessDebug || {};
        window.__chessDebug.clearAnnotations = clearBoardAnnotations;
        window.__chessDebug.setDisableScheduledReconnect = function(disabled) {
            debugDisableScheduledReconnect = !!disabled;
        };
        window.__chessDebug.onResumeVisibilityChange = onResumeVisibilityChange;
        window.__chessDebug.onResumeFocus = onResumeFocus;
        window.__chessDebug.onResumePageShow = onResumePageShow;
        window.__chessDebug.simulateResumeRecovery = debugSimulateResumeRecovery;
        window.__chessDebug.getResumeConfig = debugGetResumeConfig;
        window.__chessDebug.setResumeState = debugSetResumeState;
        window.__chessDebug.getAnnotations = function() {
            return {
                arrows: boardArrows.map(function(a) { return { from: a.from, to: a.to, alt: !!a.alt }; }),
                marks: boardMarks.map(function(m) { return { square: m.square, alt: !!m.alt }; }),
                preview: boardArrowPreview ? { from: boardArrowPreview.from, to: boardArrowPreview.to, alt: !!boardArrowPreview.alt } : null,
            };
        };
        window.__chessDebug.ensureConnectedAfterResume = ensureConnectedAfterResume;
    }

    function toggleBoardMark(square, alt) {
        if (!square) return;
        var existingIndex = -1;
        for (var i = 0; i < boardMarks.length; i += 1) {
            if (boardMarks[i].square === square && !!boardMarks[i].alt === !!alt) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            boardMarks.splice(existingIndex, 1);
        } else {
            boardMarks.push({ square: square, alt: !!alt });
        }
        renderBoardArrows();
    }

    function toggleBoardArrow(fromSquare, toSquare, alt) {
        if (!fromSquare || !toSquare) return;
        if (fromSquare === toSquare) {
            toggleBoardMark(fromSquare, alt);
            return;
        }
        var existingIndex = -1;
        for (var i = 0; i < boardArrows.length; i += 1) {
            if (boardArrows[i].from === fromSquare && boardArrows[i].to === toSquare && !!boardArrows[i].alt === !!alt) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            boardArrows.splice(existingIndex, 1);
        } else {
            boardArrows.push({ from: fromSquare, to: toSquare, alt: !!alt });
        }
        renderBoardArrows();
    }

    function clearHighlights() {
        document.querySelectorAll('.chess-square').forEach(function(square) {
            square.classList.remove('selected', 'legal-target', 'capture-target', 'check-capture-target', 'last-move-from', 'last-move-to', 'checked-king');
        });
    }

    function applyHighlights() {
        clearHighlights();

        var boardEl = document.querySelector('.chess-board');
        var checkedKingSquare = boardEl ? (boardEl.getAttribute('data-checked-king') || '') : '';
        if (checkedKingSquare) {
            var checkedEl = document.querySelector('.chess-square[data-square="' + checkedKingSquare + '"]');
            if (checkedEl) checkedEl.classList.add('checked-king');
        }

        if (boardEl && boardEl.getAttribute('data-show-last-move') === '1' && !checkedKingSquare) {
            var moveMeta = boardEl.getAttribute('data-last-move-meta') || '';
            if (moveMeta.indexOf(':') !== -1) {
                var movePart = moveMeta.split(':')[1] || '';
                var parts = movePart.split('-');
                if (parts.length === 2) {
                    var fromEl = document.querySelector('.chess-square[data-square="' + parts[0] + '"]');
                    var toEl = document.querySelector('.chess-square[data-square="' + parts[1] + '"]');
                    if (!fromEl || !toEl) {
                        return;
                    }
                    if (fromEl) fromEl.classList.add('last-move-from');
                    if (toEl) toEl.classList.add('last-move-to');
                }
            }
        }

        if (selectedFrom) {
            var selected = document.querySelector('.chess-square[data-square="' + selectedFrom + '"]');
            if (selected) selected.classList.add('selected');
        }
        legalTargets.forEach(function(target) {
            var el = document.querySelector('.chess-square[data-square="' + target + '"]');
            if (!el) return;
            el.classList.add('legal-target');
            var targetPiece = el.getAttribute('data-piece') || '';
            if (targetPiece) {
                el.classList.add('capture-target');
                if (checkedKingSquare) {
                    el.classList.add('check-capture-target');
                }
            }
        });
    }

    function captureBoardSnapshot() {
        var snapshot = {};
        document.querySelectorAll('.chess-square').forEach(function(squareEl) {
            var square = squareEl.getAttribute('data-square');
            if (!square) return;
            snapshot[square] = squareEl.getAttribute('data-piece') || '';
        });
        return snapshot;
    }

    function playMoveAnimations(previousSnapshot) {
        var boardEl = document.querySelector('.chess-board');
        if (!boardEl) return;

        var moveMeta = boardEl.getAttribute('data-last-move-meta') || '';
        if (!moveMeta || moveMeta.indexOf(':') === -1) return;

        var sepIndex = moveMeta.indexOf(':');
        var kind = moveMeta.slice(0, sepIndex);
        var move = moveMeta.slice(sepIndex + 1);
        var parts = move.split('-');
        if (parts.length !== 2) return;

        var from = parts[0];
        var to = parts[1];

        animatePieceTravel(kind, from, to, previousSnapshot || {});

        if (animationTimeout) {
            clearTimeout(animationTimeout);
            animationTimeout = null;
        }

        document.querySelectorAll('.chess-square').forEach(function(el) {
            el.classList.remove('moved', 'captured');
        });

        var movedEl = document.querySelector('.chess-square[data-square="' + to + '"]');
        if (movedEl) movedEl.classList.add('moved');

        if (kind === 'capture' || kind === 'enpassant') {
            var capturedSquare = to;
            if (kind === 'enpassant') {
                var fromRank = parseInt(from[1], 10);
                var toRank = parseInt(to[1], 10);
                var capturedRank = fromRank;
                if (toRank > fromRank) capturedRank = toRank - 1;
                if (toRank < fromRank) capturedRank = toRank + 1;
                capturedSquare = to[0] + String(capturedRank);
            }
            var capturedEl = document.querySelector('.chess-square[data-square="' + capturedSquare + '"]');
            if (capturedEl) capturedEl.classList.add('captured');
        }

        if (kind === 'castle') {
            var rookTo = null;
            if (from === 'e1' && to === 'g1') rookTo = 'f1';
            else if (from === 'e1' && to === 'c1') rookTo = 'd1';
            else if (from === 'e8' && to === 'g8') rookTo = 'f8';
            else if (from === 'e8' && to === 'c8') rookTo = 'd8';
            if (rookTo) {
                var rookEl = document.querySelector('.chess-square[data-square="' + rookTo + '"]');
                if (rookEl) rookEl.classList.add('moved');
            }
        }

        animationTimeout = setTimeout(function() {
            document.querySelectorAll('.chess-square').forEach(function(el) {
                el.classList.remove('moved', 'captured');
            });
        }, 1560);
    }

    function moveMetaOwnColor(myColor, boardEl) {
        if (!boardEl) return false;
        var moveMeta = boardEl.getAttribute('data-last-move-meta') || '';
        if (!moveMeta || moveMeta.indexOf(':') === -1) return false;
        var move = moveMeta.split(':')[1] || '';
        var parts = move.split('-');
        if (parts.length !== 2) return false;
        var to = parts[1];
        var toEl = document.querySelector('.chess-square[data-square="' + to + '"]');
        if (!toEl) return false;
        var piece = toEl.getAttribute('data-piece') || '';
        if (!piece) return false;
        return (myColor === 'white' && piece === piece.toUpperCase()) || (myColor === 'black' && piece === piece.toLowerCase());
    }

    function pieceGlyph(piece) {
        switch (piece) {
            case 'K': return '♔\uFE0E';
            case 'Q': return '♕\uFE0E';
            case 'R': return '♖\uFE0E';
            case 'B': return '♗\uFE0E';
            case 'N': return '♘\uFE0E';
            case 'P': return '♙\uFE0E';
            case 'k': return '♚\uFE0E';
            case 'q': return '♛\uFE0E';
            case 'r': return '♜\uFE0E';
            case 'b': return '♝\uFE0E';
            case 'n': return '♞\uFE0E';
            case 'p': return '♟\uFE0E';
            default: return '';
        }
    }

    function animatePieceTravel(kind, from, to, previousSnapshot) {
        var travelDurationMs = 700;
        var revealLeadMs = 120;
        moveTrailRunId += 1;
        var runId = moveTrailRunId;

        if (moveTrailRevealTimeout) {
            clearTimeout(moveTrailRevealTimeout);
            moveTrailRevealTimeout = null;
        }
        if (moveTrailCleanupTimeout) {
            clearTimeout(moveTrailCleanupTimeout);
            moveTrailCleanupTimeout = null;
        }

        document.querySelectorAll('.move-trail-piece').forEach(function(el) {
            if (el && el.parentNode) el.parentNode.removeChild(el);
        });
        document.querySelectorAll('.chess-piece.move-arrival-hidden').forEach(function(el) {
            el.classList.remove('move-arrival-hidden');
        });
        var activeShell = document.getElementById('chess-board-shell');
        if (activeShell) {
            activeShell.classList.remove('animating');
        }

        var fromEl = document.querySelector('.chess-square[data-square="' + from + '"]');
        var toEl = document.querySelector('.chess-square[data-square="' + to + '"]');
        var boardShell = document.getElementById('chess-board-shell');
        if (!fromEl || !toEl || !boardShell) return;

        var finalToPiece = toEl.getAttribute('data-piece') || '';
        var movingPiece = (previousSnapshot && previousSnapshot[from]) || '';
        var fromPieceEl = fromEl.querySelector('.chess-piece');
        var destinationPieceEl = toEl.querySelector('.chess-piece');
        if (
            !movingPiece &&
            fromPieceEl &&
            fromPieceEl.textContent &&
            fromPieceEl.textContent.trim() &&
            fromPieceEl.textContent.trim() !== '·'
        ) {
            var fromGlyph = fromPieceEl.textContent.trim();
            if (fromGlyph === '♔' || fromGlyph === '♚') movingPiece = fromPieceEl.classList.contains('piece-black') ? 'k' : 'K';
            else if (fromGlyph === '♕' || fromGlyph === '♛') movingPiece = fromPieceEl.classList.contains('piece-black') ? 'q' : 'Q';
            else if (fromGlyph === '♖' || fromGlyph === '♜') movingPiece = fromPieceEl.classList.contains('piece-black') ? 'r' : 'R';
            else if (fromGlyph === '♗' || fromGlyph === '♝') movingPiece = fromPieceEl.classList.contains('piece-black') ? 'b' : 'B';
            else if (fromGlyph === '♘' || fromGlyph === '♞') movingPiece = fromPieceEl.classList.contains('piece-black') ? 'n' : 'N';
            else movingPiece = fromPieceEl.classList.contains('piece-black') ? 'p' : 'P';
        }
        if (!movingPiece && finalToPiece) {
            movingPiece = finalToPiece;
        }
        var glyph = pieceGlyph(movingPiece);
        if (!glyph) return;

        boardShell.classList.add('animating');

        var shellRect = boardShell.getBoundingClientRect();
        var fromRect = fromEl.getBoundingClientRect();
        var toRect = toEl.getBoundingClientRect();
        var fromCenterX = fromRect.left - shellRect.left + fromRect.width / 2;
        var fromCenterY = fromRect.top - shellRect.top + fromRect.height / 2;
        var toCenterX = toRect.left - shellRect.left + toRect.width / 2;
        var toCenterY = toRect.top - shellRect.top + toRect.height / 2;
        var piece = document.createElement('div');
        var pieceColorClass = '';
        if (movingPiece === movingPiece.toUpperCase()) pieceColorClass = ' piece-white';
        else pieceColorClass = ' piece-black';
        piece.className = 'move-trail-piece' + pieceColorClass;
        piece.textContent = glyph;
        piece.style.left = fromCenterX + 'px';
        piece.style.top = fromCenterY + 'px';
        boardShell.appendChild(piece);

        if (destinationPieceEl) {
            destinationPieceEl.classList.add('move-arrival-hidden');
        }

        var dx = toCenterX - fromCenterX;
        var dy = toCenterY - fromCenterY;
        if (piece.animate) {
            piece.animate(
                [
                    { transform: 'translate(-50%, -50%)', opacity: 1 },
                    { transform: 'translate(' + dx + 'px,' + dy + 'px) translate(-50%, -50%)', opacity: 1 },
                ],
                { duration: travelDurationMs, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)', fill: 'forwards' },
            );
        } else {
            requestAnimationFrame(function() {
                piece.style.transform = 'translate(' + dx + 'px,' + dy + 'px) translate(-50%, -50%)';
                piece.style.opacity = '1';
            });
        }

        if (kind === 'capture' || kind === 'enpassant') {
            var capturedSquare = to;
            if (kind === 'enpassant') {
                var fromRank = parseInt(from[1], 10);
                var toRank = parseInt(to[1], 10);
                var capturedRank = fromRank;
                if (toRank > fromRank) capturedRank = toRank - 1;
                if (toRank < fromRank) capturedRank = toRank + 1;
                capturedSquare = to[0] + String(capturedRank);
            }
            var capturedEl = document.querySelector('.chess-square[data-square="' + capturedSquare + '"]');
            if (capturedEl) {
                var spark = document.createElement('div');
                spark.className = 'capture-spark';
                capturedEl.appendChild(spark);
                setTimeout(function() {
                    if (spark.parentNode) spark.parentNode.removeChild(spark);
                }, 420);
            }
        }

        moveTrailRevealTimeout = setTimeout(function() {
            if (runId !== moveTrailRunId) return;
            var restoredDestinationPieceEl = toEl.querySelector('.chess-piece');
            if (restoredDestinationPieceEl) {
                restoredDestinationPieceEl.classList.remove('move-arrival-hidden');
            }
            moveTrailRevealTimeout = null;
        }, Math.max(0, travelDurationMs - revealLeadMs));

        moveTrailCleanupTimeout = setTimeout(function() {
            if (runId !== moveTrailRunId) return;
            if (piece.parentNode) piece.parentNode.removeChild(piece);
            var restoredDestinationPieceEl = toEl.querySelector('.chess-piece');
            if (restoredDestinationPieceEl) {
                restoredDestinationPieceEl.classList.remove('move-arrival-hidden');
            }
            boardShell.classList.remove('animating');
            moveTrailCleanupTimeout = null;
        }, travelDurationMs + 20);
    }

    function syncTurnMetadataAfterLocalMove(from, to) {
        var boardEl = document.querySelector('.chess-board');
        if (!boardEl) return;
        var currentTurn = boardEl.getAttribute('data-turn') || '';
        if (currentTurn === 'white') boardEl.setAttribute('data-turn', 'black');
        else if (currentTurn === 'black') boardEl.setAttribute('data-turn', 'white');

        var piece = '';
        var movedTo = document.querySelector('.chess-square[data-square="' + to + '"]');
        if (movedTo) piece = movedTo.getAttribute('data-piece') || '';
        var isPawn = piece && piece.toLowerCase() === 'p';
        var fromRank = parseInt(from[1], 10);
        var toRank = parseInt(to[1], 10);
        if (isPawn && Math.abs(toRank - fromRank) === 2) {
            boardEl.setAttribute('data-en-passant', from[0] + String((fromRank + toRank) / 2));
        } else {
            boardEl.setAttribute('data-en-passant', '');
        }
    }

    function boardState() {
        var boardEl = document.querySelector('.chess-board');
        var s = {
            board: {},
            turn: boardEl ? (boardEl.getAttribute('data-turn') || '') : '',
            yourColor: boardEl ? (boardEl.getAttribute('data-your-color') || '') : '',
            enPassant: boardEl ? (boardEl.getAttribute('data-en-passant') || '') : '',
            castleWK: boardEl ? boardEl.getAttribute('data-castle-wk') === '1' : false,
            castleWQ: boardEl ? boardEl.getAttribute('data-castle-wq') === '1' : false,
            castleBK: boardEl ? boardEl.getAttribute('data-castle-bk') === '1' : false,
            castleBQ: boardEl ? boardEl.getAttribute('data-castle-bq') === '1' : false,
        };
        document.querySelectorAll('.chess-square').forEach(function(el) {
            var sq = el.getAttribute('data-square');
            if (!sq) return;
            s.board[sq] = el.getAttribute('data-piece') || '';
        });
        return s;
    }

    function pieceColor(piece) {
        if (!piece) return '';
        return piece === piece.toUpperCase() ? 'white' : 'black';
    }

    function sq(file, rank) {
        return String.fromCharCode('a'.charCodeAt(0) + file) + String(rank + 1);
    }

    function toCoord(square) {
        return { file: square.charCodeAt(0) - 'a'.charCodeAt(0), rank: parseInt(square[1], 10) - 1 };
    }

    function cloneBoard(board) {
        var out = {};
        Object.keys(board).forEach(function(k) { out[k] = board[k]; });
        return out;
    }

    function pathClear(board, from, to) {
        var a = toCoord(from);
        var b = toCoord(to);
        var df = Math.sign(b.file - a.file);
        var dr = Math.sign(b.rank - a.rank);
        var f = a.file + df;
        var r = a.rank + dr;
        while (f !== b.file || r !== b.rank) {
            if (board[sq(f, r)]) return false;
            f += df;
            r += dr;
        }
        return true;
    }

    function canAttack(board, piece, from, to) {
        var a = toCoord(from);
        var b = toCoord(to);
        var df = b.file - a.file;
        var dr = b.rank - a.rank;
        var p = piece.toLowerCase();
        if (p === 'p') {
            var dir = piece === piece.toUpperCase() ? 1 : -1;
            return Math.abs(df) === 1 && dr === dir;
        }
        if (p === 'n') return (Math.abs(df) === 1 && Math.abs(dr) === 2) || (Math.abs(df) === 2 && Math.abs(dr) === 1);
        if (p === 'b') return Math.abs(df) === Math.abs(dr) && pathClear(board, from, to);
        if (p === 'r') return (df === 0 || dr === 0) && pathClear(board, from, to);
        if (p === 'q') return ((df === 0 || dr === 0) || (Math.abs(df) === Math.abs(dr))) && pathClear(board, from, to);
        if (p === 'k') return Math.abs(df) <= 1 && Math.abs(dr) <= 1;
        return false;
    }

    function kingSquare(board, color) {
        var target = color === 'white' ? 'K' : 'k';
        var keys = Object.keys(board);
        for (var i = 0; i < keys.length; i++) {
            if ((board[keys[i]] || '') === target) return keys[i];
        }
        return '';
    }

    function isSquareAttacked(board, square, byColor) {
        var keys = Object.keys(board);
        for (var i = 0; i < keys.length; i++) {
            var from = keys[i];
            var piece = board[from] || '';
            if (!piece || pieceColor(piece) !== byColor) continue;
            if (canAttack(board, piece, from, square)) return true;
        }
        return false;
    }

    function applyPseudoMove(state, from, to) {
        var board = cloneBoard(state.board);
        var piece = board[from];
        var target = board[to];
        if (!piece) return null;
        var out = {
            board: board,
            enPassant: '',
            castleWK: state.castleWK,
            castleWQ: state.castleWQ,
            castleBK: state.castleBK,
            castleBQ: state.castleBQ,
        };

        var p = piece.toLowerCase();
        if (p === 'k' && Math.abs(toCoord(to).file - toCoord(from).file) === 2) {
            var rookFrom = '';
            var rookTo = '';
            if (from === 'e1' && to === 'g1') { rookFrom = 'h1'; rookTo = 'f1'; out.castleWK = false; out.castleWQ = false; }
            else if (from === 'e1' && to === 'c1') { rookFrom = 'a1'; rookTo = 'd1'; out.castleWK = false; out.castleWQ = false; }
            else if (from === 'e8' && to === 'g8') { rookFrom = 'h8'; rookTo = 'f8'; out.castleBK = false; out.castleBQ = false; }
            else if (from === 'e8' && to === 'c8') { rookFrom = 'a8'; rookTo = 'd8'; out.castleBK = false; out.castleBQ = false; }
            if (!rookFrom || !board[rookFrom]) return null;
            delete board[from];
            delete board[rookFrom];
            board[to] = piece;
            board[rookTo] = pieceColor(piece) === 'white' ? 'R' : 'r';
            out.board = board;
            return out;
        }

        if (p === 'p' && !target && from[0] !== to[0] && state.enPassant && state.enPassant === to) {
            var capRank = toCoord(from).rank;
            var capSq = to[0] + String(capRank + 1);
            delete board[capSq];
        }

        delete board[from];
        board[to] = piece;

        var fr = from[1];
        var tr = to[1];
        if (p === 'p' && Math.abs(parseInt(tr, 10) - parseInt(fr, 10)) === 2) {
            var mid = (parseInt(fr, 10) + parseInt(tr, 10)) / 2;
            out.enPassant = from[0] + String(mid);
        }

        if (from === 'e1' || piece === 'K') { out.castleWK = false; out.castleWQ = false; }
        if (from === 'e8' || piece === 'k') { out.castleBK = false; out.castleBQ = false; }
        if (from === 'h1' || to === 'h1') out.castleWK = false;
        if (from === 'a1' || to === 'a1') out.castleWQ = false;
        if (from === 'h8' || to === 'h8') out.castleBK = false;
        if (from === 'a8' || to === 'a8') out.castleBQ = false;
        out.board = board;
        return out;
    }

    function pseudoLegal(state, from, to) {
        if (from === to) return false;
        var piece = state.board[from] || '';
        if (!piece) return false;
        if (pieceColor(piece) !== state.yourColor) return false;
        if (state.yourColor !== state.turn) return false;
        var target = state.board[to] || '';
        if (target && pieceColor(target) === state.yourColor) return false;

        var a = toCoord(from);
        var b = toCoord(to);
        var df = b.file - a.file;
        var dr = b.rank - a.rank;
        var p = piece.toLowerCase();

        if (p === 'p') {
            var dir = state.yourColor === 'white' ? 1 : -1;
            var startRank = state.yourColor === 'white' ? 1 : 6;
            if (df === 0 && dr === dir && !target) return true;
            if (df === 0 && dr === 2 * dir && a.rank === startRank && !target) {
                var midSq = sq(a.file, a.rank + dir);
                return !state.board[midSq];
            }
            if (Math.abs(df) === 1 && dr === dir) {
                if (target) return true;
                return state.enPassant && state.enPassant === to;
            }
            return false;
        }
        if (p === 'n') return (Math.abs(df) === 1 && Math.abs(dr) === 2) || (Math.abs(df) === 2 && Math.abs(dr) === 1);
        if (p === 'b') return Math.abs(df) === Math.abs(dr) && pathClear(state.board, from, to);
        if (p === 'r') return (df === 0 || dr === 0) && pathClear(state.board, from, to);
        if (p === 'q') return ((df === 0 || dr === 0) || (Math.abs(df) === Math.abs(dr))) && pathClear(state.board, from, to);
        if (p === 'k') {
            if (Math.abs(df) <= 1 && Math.abs(dr) <= 1) return true;
            if (dr === 0 && Math.abs(df) === 2) {
                if (state.yourColor === 'white' && from === 'e1' && to === 'g1') return state.castleWK && !state.board['f1'] && !state.board['g1'];
                if (state.yourColor === 'white' && from === 'e1' && to === 'c1') return state.castleWQ && !state.board['d1'] && !state.board['c1'] && !state.board['b1'];
                if (state.yourColor === 'black' && from === 'e8' && to === 'g8') return state.castleBK && !state.board['f8'] && !state.board['g8'];
                if (state.yourColor === 'black' && from === 'e8' && to === 'c8') return state.castleBQ && !state.board['d8'] && !state.board['c8'] && !state.board['b8'];
            }
            return false;
        }
        return false;
    }

    function localLegalMoves(fromSquare) {
        var state = boardState();
        if (!state || state.yourColor === 'spectator') return [];
        var out = [];
        for (var f = 0; f < 8; f++) {
            for (var r = 0; r < 8; r++) {
                var to = sq(f, r);
                if (!pseudoLegal(state, fromSquare, to)) continue;
                var next = applyPseudoMove(state, fromSquare, to);
                if (!next) continue;
                var myKing = kingSquare(next.board, state.yourColor);
                if (!myKing) continue;
                var opp = state.yourColor === 'white' ? 'black' : 'white';
                if (isSquareAttacked(next.board, myKing, opp)) continue;
                out.push(to);
            }
        }
        return out;
    }

    function bindBoardInteractions() {
        var fromInput = document.getElementById('chess-from');
        var toInput = document.getElementById('chess-to');
        if (!fromInput || !toInput) return;

        if (!boardArrowGlobalBound) {
            boardArrowGlobalBound = true;
            document.addEventListener('mouseup', function(ev) {
                if (ev.button !== 2) return;
                if (rightArrowDragActive) {
                    boardArrowPreview = null;
                    renderBoardArrows();
                }
                rightArrowDragActive = false;
                rightArrowFromSquare = null;
            });
            window.addEventListener('resize', function() {
                renderBoardArrows();
            });
        }

        function setSelectedSquare(square) {
            selectedFrom = square;
            toInput.value = '';
            legalTargets = [];
            legalTargetSet = {};
            if (selectedFrom) {
                legalTargets = localLegalMoves(selectedFrom);
                legalTargets.forEach(function(move) { legalTargetSet[move] = true; });
            }
            applyHighlights();
            updateMoveUI();
        }

        function isOwnPiece(piece) {
            if (!piece) return false;
            var boardEl = document.querySelector('.chess-board');
            if (!boardEl) return false;
            var yourColor = boardEl.getAttribute('data-your-color') || '';
            if (yourColor === 'white') return piece === piece.toUpperCase();
            if (yourColor === 'black') return piece === piece.toLowerCase();
            return false;
        }

        function activateSquare(square, currentPiece) {
            if (supportsRightMouseArrows() && (boardArrows.length > 0 || boardMarks.length > 0 || boardArrowPreview)) {
                clearBoardAnnotations();
            }
            if (!selectedFrom) {
                var startPiece = currentPiece();
                if (!startPiece || !isOwnPiece(startPiece)) return;
                setSelectedSquare(square);
                return;
            }

            if (square === selectedFrom) {
                setSelectedSquare(null);
                return;
            }

            var pieceAtSquare = currentPiece();
            if (pieceAtSquare && isOwnPiece(pieceAtSquare)) {
                setSelectedSquare(square);
                return;
            }

            toInput.value = square;
            applyHighlights();
            updateMoveUI();
            submitMove(selectedFrom, square);
            hideCoachmark();
            localStorage.setItem('chess_hint_seen', '1');
        }

        document.querySelectorAll('.chess-square').forEach(function(squareEl) {
            if (squareEl.dataset.bound === '1') {
                return;
            }
            squareEl.dataset.bound = '1';

            var square = squareEl.getAttribute('data-square');
            function currentPiece() {
                return squareEl.getAttribute('data-piece') || '';
            }

            squareEl.addEventListener('click', function() {
                if (Date.now() < suppressClickUntil) return;
                if (Date.now() - lastTouchHandledAt < 600 && lastTouchHandledSquare === square) return;
                activateSquare(square, currentPiece);
            });

            squareEl.addEventListener('contextmenu', function(ev) {
                if (!supportsRightMouseArrows()) return;
                ev.preventDefault();
            });

            squareEl.addEventListener('mousedown', function(ev) {
                if (!supportsRightMouseArrows()) return;
                if (ev.button !== 2) return;
                ev.preventDefault();
                rightArrowFromSquare = square;
                rightArrowDragActive = true;
                boardArrowPreview = { from: square, to: square, alt: !!ev.shiftKey };
                renderBoardArrows();
            });

            squareEl.addEventListener('mouseup', function(ev) {
                if (!supportsRightMouseArrows()) return;
                if (ev.button !== 2) return;
                if (!rightArrowDragActive || !rightArrowFromSquare) return;
                ev.preventDefault();
                boardArrowPreview = null;
                toggleBoardArrow(rightArrowFromSquare, square, !!ev.shiftKey);
                rightArrowDragActive = false;
                rightArrowFromSquare = null;
            });

            squareEl.addEventListener('mouseenter', function(ev) {
                if (!supportsRightMouseArrows()) return;
                if (!rightArrowDragActive || !rightArrowFromSquare) return;
                boardArrowPreview = { from: rightArrowFromSquare, to: square, alt: !!ev.shiftKey };
                renderBoardArrows();
            });

            squareEl.addEventListener('mousemove', function(ev) {
                if (!supportsRightMouseArrows()) return;
                if (!rightArrowDragActive || !rightArrowFromSquare) return;
                var alt = !!ev.shiftKey;
                if (!boardArrowPreview || boardArrowPreview.to !== square || !!boardArrowPreview.alt !== alt) {
                    boardArrowPreview = { from: rightArrowFromSquare, to: square, alt: alt };
                    renderBoardArrows();
                }
            });

            squareEl.addEventListener('dragstart', function(ev) {
                var pieceEl = squareEl.querySelector('.chess-piece');
                if (!currentPiece() || !isOwnPiece(currentPiece())) {
                    ev.preventDefault();
                    return;
                }
                draggingFrom = square;
                setSelectedSquare(square);
                ev.dataTransfer.setData('text/plain', square);
                ev.dataTransfer.effectAllowed = 'move';
                if (pieceEl) {
                    dragGhost = createDragGhost(pieceEl);
                    ev.dataTransfer.setDragImage(dragGhost, 18, 18);
                }
                if (pieceEl) pieceEl.classList.add('dragging');
                updateMoveUI();
            });

            squareEl.addEventListener('dragover', function(ev) {
                if (!draggingFrom) return;
                if (square !== draggingFrom) {
                    ev.preventDefault();
                    ev.dataTransfer.dropEffect = 'move';
                }
            });

            squareEl.addEventListener('dragenter', function(ev) {
                if (!draggingFrom || square === draggingFrom) return;
                ev.preventDefault();
                squareEl.classList.add('drag-hover');
            });

            squareEl.addEventListener('dragleave', function() {
                squareEl.classList.remove('drag-hover');
            });

            squareEl.addEventListener('drop', function(ev) {
                if (!draggingFrom) return;
                ev.preventDefault();
                if (square === draggingFrom) return;
                if (isOwnPiece(currentPiece())) return;
                toInput.value = square;
                applyHighlights();
                updateMoveUI();
                submitMove(selectedFrom || draggingFrom, square);
                squareEl.classList.remove('drag-hover');
                hideCoachmark();
                localStorage.setItem('chess_hint_seen', '1');
            });

            squareEl.addEventListener('dragend', function() {
                var pieceEl = squareEl.querySelector('.chess-piece');
                draggingFrom = null;
                if (pieceEl) pieceEl.classList.remove('dragging');
                document.querySelectorAll('.chess-square.drag-hover').forEach(function(el) { el.classList.remove('drag-hover'); });
                if (dragGhost && dragGhost.parentNode) {
                    dragGhost.parentNode.removeChild(dragGhost);
                }
                dragGhost = null;
            });

            squareEl.addEventListener('touchstart', function(ev) {
                if (usePointerTouch) return;
                touchActive = true;
                touchMoved = false;
                touchFrom = square;
                touchHandledOnStart = false;
                if (selectedFrom === square) {
                    activateSquare(square, currentPiece)
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    touchHandledOnStart = false;
                } else if (selectedFrom && selectedFrom !== square && !isOwnPiece(currentPiece())) {
                    toInput.value = square;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(selectedFrom, square);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                    touchHandledOnStart = true;
                } else if (!selectedFrom || selectedFrom !== square) {
                    activateSquare(square, currentPiece);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    touchHandledOnStart = false;
                }
                if (ev.cancelable) ev.preventDefault();
            }, { passive: false });

            squareEl.addEventListener('touchmove', function() {
                if (usePointerTouch) return;
                touchMoved = true;
            }, { passive: true });

            squareEl.addEventListener('touchend', function(ev) {
                if (usePointerTouch) return;
                if (touchHandledOnStart && !touchMoved) {
                    touchFrom = null;
                    touchActive = false;
                    touchHandledOnStart = false;
                    return;
                }
                if (!touchMoved) {
                    touchFrom = null;
                    touchActive = false;
                    return;
                }

                var touch = ev.changedTouches && ev.changedTouches[0];
                if (!touch) {
                    touchFrom = null;
                    touchActive = false;
                    return;
                }

                var targetEl = document.elementFromPoint(touch.clientX, touch.clientY);
                var targetSquareEl = targetEl ? targetEl.closest('.chess-square') : null;
                var targetSquare = targetSquareEl ? targetSquareEl.getAttribute('data-square') : null;

                if (touchFrom && targetSquare && targetSquare !== touchFrom) {
                    var sourceSquare = selectedFrom || touchFrom;
                    var targetPiece = targetSquareEl ? (targetSquareEl.getAttribute('data-piece') || '') : '';
                    if (sourceSquare && !isOwnPiece(targetPiece)) {
                        toInput.value = targetSquare;
                        applyHighlights();
                        updateMoveUI();
                        submitMove(sourceSquare, targetSquare);
                        lastTouchHandledAt = Date.now();
                        lastTouchHandledSquare = targetSquare;
                        hideCoachmark();
                        localStorage.setItem('chess_hint_seen', '1');
                    }
                }

                touchFrom = null;
                touchActive = false;
                touchHandledOnStart = false;
            }, { passive: false });

            squareEl.addEventListener('touchcancel', function() {
                if (usePointerTouch) return;
                touchMoved = false;
                touchFrom = null;
                touchActive = false;
                touchHandledOnStart = false;
            }, { passive: true });

            squareEl.addEventListener('pointerdown', function(ev) {
                if (!usePointerTouch) return;
                if (ev.pointerType !== 'touch') return;
                touchActive = true;
                touchMoved = false;
                touchFrom = square;
                touchHandledOnStart = false;
                if (selectedFrom === square) {
                    activateSquare(square, currentPiece)
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    touchHandledOnStart = false;
                } else if (selectedFrom && selectedFrom !== square && !isOwnPiece(currentPiece())) {
                    toInput.value = square;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(selectedFrom, square);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                    touchHandledOnStart = true;
                } else if (!selectedFrom || selectedFrom !== square) {
                    activateSquare(square, currentPiece);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    touchHandledOnStart = false;
                }
                if (ev.cancelable) ev.preventDefault();
            }, { passive: false });

            squareEl.addEventListener('pointermove', function(ev) {
                if (!usePointerTouch) return;
                if (ev.pointerType !== 'touch' || !touchActive) return;
                touchMoved = true;
            }, { passive: true });

            squareEl.addEventListener('pointerup', function(ev) {
                if (!usePointerTouch) return;
                if (ev.pointerType !== 'touch' || !touchActive) return;
                if (ev.cancelable) ev.preventDefault();

                if (touchHandledOnStart && !touchMoved) {
                    touchFrom = null;
                    touchActive = false;
                    touchHandledOnStart = false;
                    return;
                }

                if (!touchMoved) {
                    touchFrom = null;
                    touchActive = false;
                    return;
                }

                var targetEl = document.elementFromPoint(ev.clientX, ev.clientY);
                var targetSquareEl = targetEl ? targetEl.closest('.chess-square') : null;
                var targetSquare = targetSquareEl ? targetSquareEl.getAttribute('data-square') : null;

                if (touchFrom && targetSquare && targetSquare !== touchFrom) {
                    var sourceSquare = selectedFrom || touchFrom;
                    var pointerTargetPiece = targetSquareEl ? (targetSquareEl.getAttribute('data-piece') || '') : '';
                    if (sourceSquare && !isOwnPiece(pointerTargetPiece)) {
                        toInput.value = targetSquare;
                        applyHighlights();
                        updateMoveUI();
                        submitMove(sourceSquare, targetSquare);
                        lastTouchHandledAt = Date.now();
                        lastTouchHandledSquare = targetSquare;
                        hideCoachmark();
                        localStorage.setItem('chess_hint_seen', '1');
                    }
                }

                touchFrom = null;
                touchActive = false;
                touchHandledOnStart = false;
            }, { passive: false });

            squareEl.addEventListener('pointercancel', function(ev) {
                if (!usePointerTouch) return;
                if (ev.pointerType !== 'touch') return;
                touchMoved = false;
                touchFrom = null;
                touchActive = false;
                touchHandledOnStart = false;
            }, { passive: true });
        });

        applyHighlights();
        updateMoveUI();
        renderBoardArrows();
        maybeShowCoachmark();
    }

    document.addEventListener('visibilitychange', onResumeVisibilityChange);

    window.addEventListener('focus', onResumeFocus);

    window.addEventListener('pageshow', onResumePageShow);

    pingCheckInterval = setInterval(function() {
        var timeSinceLastPing = Date.now() - lastPingTime;
        if (timeSinceLastPing > 45000) {
            connect();
        }
    }, 10000);

    connect();
    startCountdowns();
    bindShareButton();
    bindSoundButton();
    bindQrButton();
    bindSurrenderButton();
    bindBoardInteractions();
    boardSnapshot = captureBoardSnapshot();
    var initialBoard = document.querySelector('.chess-board');
    if (initialBoard) {
        previousTurnColor = initialBoard.getAttribute('data-turn') || '';
    }
    document.addEventListener('pointerdown', unlockAudio, { once: true, passive: true });

    window.addEventListener('beforeunload', function() {
        if (countdownInterval) clearInterval(countdownInterval);
        if (chessClockInterval) clearInterval(chessClockInterval);
        if (pingCheckInterval) clearInterval(pingCheckInterval);
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        closeRealtimeConnection();
    });
}
