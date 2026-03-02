function initChess(gameId) {
    if (window.location.pathname !== '/chess/' + gameId) {
        history.replaceState(null, '', '/chess/' + gameId);
    }

    var chessContent = document.getElementById('chess-content');
    var eventSource = null;
    var countdownInterval = null;
    var reconnectTimeout = null;
    var isConnecting = false;
    var lastPingTime = Date.now();
    var pingCheckInterval = null;
    var selectedFrom = null;
    var legalTargets = [];
    var draggingFrom = null;
    var legalTargetSet = {};
    var boardSnapshot = {};
    var animationTimeout = null;
    var suppressClickUntil = 0;
    var dragGhost = null;
    var touchMoved = false;
    var touchFrom = null;
    var touchActive = false;
    var lastTouchHandledAt = 0;
    var lastTouchHandledSquare = null;
    var lastUpdateAt = Date.now();
    var moveInFlight = false;

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
                    fetch('/chess/' + gameId + '/check-disconnects', { method: 'POST' });
                }
            });
        }, 1000);
    }

    function connect() {
        if (isConnecting) return;
        isConnecting = true;

        if (reconnectTimeout) {
            clearTimeout(reconnectTimeout);
            reconnectTimeout = null;
        }

        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }

        eventSource = new EventSource('/chess/' + gameId + '/events');
        lastPingTime = Date.now();

        eventSource.addEventListener('open', function() {
            isConnecting = false;
        });

        eventSource.addEventListener('chess-update', function(e) {
            lastUpdateAt = Date.now();
            moveInFlight = false;
            var previousSnapshot = boardSnapshot;
            var patched = patchIncrementalState(e.data);
            if (!patched) {
                chessContent.innerHTML = e.data;
            }
            htmx.process(chessContent);
            selectedFrom = null;
            legalTargets = [];
            legalTargetSet = {};
            updateMoveUI();
            bindBoardInteractions();
            boardSnapshot = captureBoardSnapshot();
            if (previousSnapshot && Object.keys(previousSnapshot).length > 0) {
                playMoveAnimations(previousSnapshot);
            }
            startCountdowns();
        });

        eventSource.addEventListener('ping', function() {
            lastPingTime = Date.now();
        });

        eventSource.onerror = function() {
            isConnecting = false;
            moveInFlight = false;
            if (eventSource) {
                eventSource.close();
                eventSource = null;
            }
            if (!reconnectTimeout) {
                reconnectTimeout = setTimeout(function() {
                    reconnectTimeout = null;
                    connect();
                }, 1000);
            }
        };
    }

    function patchIncrementalState(newHtml) {
        var currentBoard = chessContent.querySelector('.chess-board');
        if (!currentBoard) return false;

        var tmp = document.createElement('div');
        tmp.innerHTML = newHtml;
        var incomingBoard = tmp.querySelector('.chess-board');
        if (!incomingBoard) return false;

        patchBoardSquares(currentBoard, incomingBoard);

        var currentPlayers = chessContent.querySelector('.players-section');
        var incomingPlayers = tmp.querySelector('.players-section');
        if (currentPlayers && incomingPlayers) {
            currentPlayers.replaceWith(incomingPlayers);
        }

        var currentPhase = chessContent.querySelector('.phase-info');
        var incomingPhase = tmp.querySelector('.phase-info');
        if (!currentPhase || !incomingPhase) return false;

        var currentBoardShell = currentPhase.querySelector('.chess-board-shell');
        var incomingBoardShell = incomingPhase.querySelector('.chess-board-shell');
        if (!currentBoardShell || !incomingBoardShell) return false;

        incomingBoardShell.replaceWith(currentBoardShell);
        currentPhase.replaceWith(incomingPhase);

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
        });
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
                    showToast(invalidMsg);
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                }
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

    function showToast(message) {
        if (!message) return;

        var existing = document.getElementById('chess-toast');
        if (existing) existing.remove();

        var toast = document.createElement('div');
        toast.id = 'chess-toast';
        toast.className = 'chess-toast';
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
        ghost.style.position = 'fixed';
        ghost.style.left = '-1000px';
        ghost.style.top = '-1000px';
        ghost.style.pointerEvents = 'none';
        document.body.appendChild(ghost);
        return ghost;
    }

    function clearHighlights() {
        document.querySelectorAll('.chess-square').forEach(function(square) {
            square.classList.remove('selected', 'legal-target', 'capture-target');
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
        }, 340);
    }

    function fetchLegalMoves(fromSquare) {
        return fetch('/chess/' + gameId + '/legal-moves?from=' + encodeURIComponent(fromSquare))
            .then(function(response) {
                if (!response.ok) return '';
                return response.text();
            })
            .then(function(text) {
                if (!text) return [];
                return text.split(',').filter(function(x) { return x; });
            })
            .catch(function() { return []; });
    }

    function bindBoardInteractions() {
        var fromInput = document.getElementById('chess-from');
        var toInput = document.getElementById('chess-to');
        if (!fromInput || !toInput) return;

        function activateSquare(square, currentPiece) {
            if (!selectedFrom) {
                if (!currentPiece()) return;
                selectedFrom = square;
                toInput.value = '';
                legalTargets = [];
                legalTargetSet = {};
                applyHighlights();
                updateMoveUI();
                fetchLegalMoves(selectedFrom).then(function(moves) {
                    legalTargets = moves;
                    legalTargetSet = {};
                    moves.forEach(function(move) { legalTargetSet[move] = true; });
                    applyHighlights();
                    updateMoveUI();
                });
                return;
            }

            if (square === selectedFrom) {
                selectedFrom = null;
                legalTargets = [];
                legalTargetSet = {};
                toInput.value = '';
                applyHighlights();
                updateMoveUI();
                return;
            }

            if (!legalTargetSet[square]) {
                if (currentPiece()) {
                    selectedFrom = square;
                    toInput.value = '';
                    legalTargets = [];
                    legalTargetSet = {};
                    applyHighlights();
                    updateMoveUI();
                    fetchLegalMoves(selectedFrom).then(function(moves) {
                        legalTargets = moves;
                        legalTargetSet = {};
                        moves.forEach(function(move) { legalTargetSet[move] = true; });
                        applyHighlights();
                        updateMoveUI();
                    });
                } else if (selectedFrom) {
                    // Mobile race safety: allow optimistic submit while highlights are still loading.
                    toInput.value = square;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(selectedFrom, square);
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                }
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

            squareEl.addEventListener('dragstart', function(ev) {
                var pieceEl = squareEl.querySelector('.chess-piece');
                if (!currentPiece()) {
                    ev.preventDefault();
                    return;
                }
                draggingFrom = square;
                selectedFrom = square;
                toInput.value = '';
                fetchLegalMoves(selectedFrom).then(function(moves) {
                    legalTargets = moves;
                    legalTargetSet = {};
                    moves.forEach(function(move) { legalTargetSet[move] = true; });
                    applyHighlights();
                    updateMoveUI();
                });
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
                if (legalTargetSet[square]) {
                    ev.preventDefault();
                    ev.dataTransfer.dropEffect = 'move';
                }
            });

            squareEl.addEventListener('drop', function(ev) {
                if (!draggingFrom) return;
                ev.preventDefault();
                if (!legalTargetSet[square]) return;
                toInput.value = square;
                applyHighlights();
                updateMoveUI();
                submitMove(selectedFrom, square);
                hideCoachmark();
                localStorage.setItem('chess_hint_seen', '1');
            });

            squareEl.addEventListener('dragend', function() {
                var pieceEl = squareEl.querySelector('.chess-piece');
                draggingFrom = null;
                if (pieceEl) pieceEl.classList.remove('dragging');
                if (dragGhost && dragGhost.parentNode) {
                    dragGhost.parentNode.removeChild(dragGhost);
                }
                dragGhost = null;
            });

            squareEl.addEventListener('touchstart', function(ev) {
                touchActive = true;
                touchMoved = false;
                touchFrom = square;
                if (selectedFrom && selectedFrom !== square && !currentPiece()) {
                    toInput.value = square;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(selectedFrom, square);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                } else if (!selectedFrom || selectedFrom !== square) {
                    activateSquare(square, currentPiece);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                }
                if (ev.cancelable) ev.preventDefault();
            }, { passive: false });

            squareEl.addEventListener('touchmove', function() {
                touchMoved = true;
            }, { passive: true });

            squareEl.addEventListener('touchend', function(ev) {
                if (!touchMoved) {
                    if (selectedFrom !== square) {
                        activateSquare(square, currentPiece);
                        lastTouchHandledAt = Date.now();
                        lastTouchHandledSquare = square;
                    }
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
                    toInput.value = targetSquare;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(touchFrom, targetSquare);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = targetSquare;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                }

                touchFrom = null;
                touchActive = false;
            }, { passive: false });

            squareEl.addEventListener('touchcancel', function() {
                touchMoved = false;
                touchFrom = null;
                touchActive = false;
            }, { passive: true });

            squareEl.addEventListener('pointerdown', function(ev) {
                if (ev.pointerType !== 'touch') return;
                touchActive = true;
                touchMoved = false;
                touchFrom = square;
                if (selectedFrom && selectedFrom !== square && !currentPiece()) {
                    toInput.value = square;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(selectedFrom, square);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                } else if (!selectedFrom || selectedFrom !== square) {
                    activateSquare(square, currentPiece);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = square;
                }
                if (ev.cancelable) ev.preventDefault();
            }, { passive: false });

            squareEl.addEventListener('pointermove', function(ev) {
                if (ev.pointerType !== 'touch' || !touchActive) return;
                touchMoved = true;
            }, { passive: true });

            squareEl.addEventListener('pointerup', function(ev) {
                if (ev.pointerType !== 'touch' || !touchActive) return;
                if (ev.cancelable) ev.preventDefault();

                if (!touchMoved) {
                    if (selectedFrom !== square) {
                        activateSquare(square, currentPiece);
                        lastTouchHandledAt = Date.now();
                        lastTouchHandledSquare = square;
                    }
                    touchFrom = null;
                    touchActive = false;
                    return;
                }

                var targetEl = document.elementFromPoint(ev.clientX, ev.clientY);
                var targetSquareEl = targetEl ? targetEl.closest('.chess-square') : null;
                var targetSquare = targetSquareEl ? targetSquareEl.getAttribute('data-square') : null;

                if (touchFrom && targetSquare && targetSquare !== touchFrom) {
                    toInput.value = targetSquare;
                    applyHighlights();
                    updateMoveUI();
                    submitMove(touchFrom, targetSquare);
                    lastTouchHandledAt = Date.now();
                    lastTouchHandledSquare = targetSquare;
                    hideCoachmark();
                    localStorage.setItem('chess_hint_seen', '1');
                }

                touchFrom = null;
                touchActive = false;
            }, { passive: false });

            squareEl.addEventListener('pointercancel', function(ev) {
                if (ev.pointerType !== 'touch') return;
                touchMoved = false;
                touchFrom = null;
                touchActive = false;
            }, { passive: true });
        });

        applyHighlights();
        updateMoveUI();
        maybeShowCoachmark();
    }

    document.addEventListener('visibilitychange', function() {
        if (document.visibilityState === 'visible') {
            connect();
        }
    });

    pingCheckInterval = setInterval(function() {
        var timeSinceLastPing = Date.now() - lastPingTime;
        if (timeSinceLastPing > 45000) {
            connect();
        }
    }, 10000);

    connect();
    startCountdowns();
    bindBoardInteractions();
    boardSnapshot = captureBoardSnapshot();

    window.addEventListener('beforeunload', function() {
        if (countdownInterval) clearInterval(countdownInterval);
        if (pingCheckInterval) clearInterval(pingCheckInterval);
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        if (eventSource) eventSource.close();
    });
}
