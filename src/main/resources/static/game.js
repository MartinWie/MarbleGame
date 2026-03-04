/**
 * Game page client-side logic for SSE handling, reconnection, and UI interactions.
 * 
 * Handles:
 * - SSE connection and reconnection on errors
 * - Ping timeout detection (reconnects if no ping for 45 seconds)
 * - Tab visibility changes (reconnects when tab becomes visible)
 * - Countdown timers for disconnected players
 * - Marble picker UI for placing marbles
 * - Marble animations (fly in/out)
 */
function initGame(gameId) {
    // Update URL to include game ID (for bookmarking/sharing)
    if (window.location.pathname !== '/game/' + gameId) {
        history.replaceState(null, '', '/game/' + gameId);
    }
    
    var gameContent = document.getElementById('game-content');
    var lastPingTime = Date.now();
    var eventSource = null;
    var pingCheckInterval = null;
    var countdownInterval = null;
    var previousMarbleCount = null;
    var reconnectTimeout = null;
    var isConnecting = false;

    function bindShareButton() {
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
            var shareText = btn.dataset.shareText || '';
            btn.textContent = btn.dataset.copiedText || shareText;
            btn.classList.add('copied');
            setTimeout(function() {
                btn.textContent = shareText;
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
    
    // Create animation container if it doesn't exist
    function getAnimationContainer() {
        var container = document.querySelector('.marble-animation-container');
        if (!container) {
            container = document.createElement('div');
            container.className = 'marble-animation-container';
            document.body.appendChild(container);
        }
        return container;
    }
    
    // Animate marbles flying out (when playing or losing)
    function animateMarblesOut(count, startElement, isLost) {
        var container = getAnimationContainer();
        var rect = startElement.getBoundingClientRect();
        var centerX = rect.left + rect.width / 2;
        var centerY = rect.top + rect.height / 2;
        
        for (var i = 0; i < Math.min(count, 10); i++) {
            (function(index) {
                setTimeout(function() {
                    var marble = document.createElement('div');
                    marble.className = 'animated-marble fly-out' + (isLost ? ' lost' : '');
                    // Spread marbles horizontally
                    var offsetX = (index - Math.min(count, 10) / 2) * 20;
                    marble.style.left = (centerX + offsetX - 16) + 'px';
                    marble.style.top = (centerY - 16) + 'px';
                    container.appendChild(marble);
                    
                    // Remove after animation
                    setTimeout(function() {
                        marble.remove();
                    }, 1200);
                }, index * 80);
            })(i);
        }
    }
    
    // Animate marbles flying in (when receiving)
    function animateMarblesIn(count, targetElement) {
        var container = getAnimationContainer();
        var rect = targetElement.getBoundingClientRect();
        var centerX = rect.left + rect.width / 2;
        var centerY = rect.top;
        
        for (var i = 0; i < Math.min(count, 10); i++) {
            (function(index) {
                setTimeout(function() {
                    var marble = document.createElement('div');
                    marble.className = 'animated-marble fly-in';
                    // Spread marbles horizontally
                    var offsetX = (index - Math.min(count, 10) / 2) * 25;
                    marble.style.left = (centerX + offsetX - 16) + 'px';
                    marble.style.top = (centerY - 16) + 'px';
                    container.appendChild(marble);
                    
                    // Remove after animation
                    setTimeout(function() {
                        marble.remove();
                    }, 1400);
                }, index * 120);
            })(i);
        }
    }
    
    // Get current marble count from status bar
    function getCurrentMarbleCount() {
        var statusBar = document.querySelector('.your-status strong');
        if (statusBar) {
            var count = parseInt(statusBar.textContent);
            return isNaN(count) ? null : count;
        }
        return null;
    }
    
    // Check for marble count changes and animate
    function checkMarbleChanges() {
        var currentCount = getCurrentMarbleCount();
        if (currentCount !== null && previousMarbleCount !== null) {
            var diff = currentCount - previousMarbleCount;
            var statusBar = document.querySelector('.your-status');
            var gameArea = document.querySelector('.game-area');
            if (diff > 0 && statusBar) {
                // Gained marbles - animate them flying in
                animateMarblesIn(diff, statusBar);
                statusBar.classList.add('marbles-changed', 'marbles-gained');
                setTimeout(function() {
                    statusBar.classList.remove('marbles-changed', 'marbles-gained');
                }, 600);
            } else if (diff < 0 && gameArea) {
                // Lost marbles - animate them flying away from game area
                animateMarblesOut(Math.abs(diff), gameArea, true);
                if (statusBar) {
                    statusBar.classList.add('marbles-changed', 'marbles-lost');
                    setTimeout(function() {
                        statusBar.classList.remove('marbles-changed', 'marbles-lost');
                    }, 600);
                }
            }
        }
        previousMarbleCount = currentCount;
    }
    
    // Countdown timer for disconnected players
    function startCountdowns() {
        if (countdownInterval) clearInterval(countdownInterval);
        countdownInterval = setInterval(function() {
            // Handle player disconnect countdowns
            var countdowns = document.querySelectorAll('.player-countdown');
            countdowns.forEach(function(el) {
                var seconds = parseInt(el.dataset.seconds) - 1;
                el.dataset.seconds = seconds;
                var timerSpan = el.querySelector('.countdown-timer');
                if (timerSpan) timerSpan.textContent = seconds + 's';
                if (seconds <= 0) {
                    // Grace period expired, notify server
                    fetch('/game/' + gameId + '/check-disconnects', { method: 'POST' });
                }
            });
            
            // Handle round result countdown (visual only - server auto-advances)
            var resultCountdown = document.querySelector('.result-countdown');
            if (resultCountdown) {
                var seconds = parseInt(resultCountdown.dataset.seconds) - 1;
                if (seconds >= 0) {
                    resultCountdown.dataset.seconds = seconds;
                    var timerSpan = resultCountdown.querySelector('.countdown-timer');
                    if (timerSpan) timerSpan.textContent = seconds;
                }
            }
        }, 1000);
    }
    
    // Setup marble picker click handlers
    function setupMarblePicker() {
        var picker = document.getElementById('marble-picker');
        if (!picker) return;
        var input = document.getElementById('marble-amount');
        var countDisplay = document.getElementById('selected-count');
        if (!input || !countDisplay) return;
        
        picker.onclick = function(e) {
            var btn = e.target;
            while (btn && !btn.classList.contains('marble-btn')) {
                btn = btn.parentElement;
            }
            if (!btn) return;
            
            var value = btn.getAttribute('data-value');
            input.value = value;
            countDisplay.textContent = value;
            
            var allBtns = picker.getElementsByClassName('marble-btn');
            for (var i = 0; i < allBtns.length; i++) {
                if (allBtns[i].getAttribute('data-value') === value) {
                    allBtns[i].classList.add('selected');
                } else {
                    allBtns[i].classList.remove('selected');
                }
            }
        };
        
        // Setup form submit handler for fly-out animation
        var form = document.getElementById('place-form');
        if (form) {
            form.addEventListener('htmx:beforeRequest', function(e) {
                var amount = parseInt(input.value) || 1;
                var selectedBtn = picker.querySelector('.marble-btn.selected');
                if (selectedBtn) {
                    animateMarblesOut(amount, selectedBtn);
                }
            });
        }
    }
    
    function connect() {
        // Prevent multiple simultaneous connection attempts
        if (isConnecting) {
            console.log('Connection already in progress, skipping');
            return;
        }
        isConnecting = true;
        
        // Clear any pending reconnect
        if (reconnectTimeout) {
            clearTimeout(reconnectTimeout);
            reconnectTimeout = null;
        }
        
        // Close existing connection if any
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
        
        console.log('SSE connecting...');
        eventSource = new EventSource('/game/' + gameId + '/events');
        lastPingTime = Date.now();
        
        eventSource.addEventListener('open', function(e) {
            console.log('SSE connection opened');
            isConnecting = false;
        });
        
        eventSource.addEventListener('game-update', function(e) {
            console.log('SSE game-update received, updating DOM');
            gameContent.innerHTML = e.data;
            htmx.process(gameContent);
            startCountdowns();
            setupMarblePicker();
            bindShareButton();
            bindQrButton();
            checkMarbleChanges();
            var countdowns = document.querySelectorAll('.player-countdown');
            console.log('Found ' + countdowns.length + ' countdown elements');
        });
        
        eventSource.addEventListener('ping', function(e) {
            lastPingTime = Date.now();
        });
        
        eventSource.onerror = function(e) {
            console.log('SSE connection error, readyState:', eventSource.readyState);
            isConnecting = false;
            
            // Close the broken connection
            if (eventSource) {
                eventSource.close();
                eventSource = null;
            }
            
            // Reconnect after a short delay (not a full page reload)
            if (!reconnectTimeout) {
                reconnectTimeout = setTimeout(function() {
                    reconnectTimeout = null;
                    console.log('Attempting SSE reconnect...');
                    connect();
                }, 1000);
            }
        };
    }
    
    // Check if we've received a ping recently (within 45 seconds)
    pingCheckInterval = setInterval(function() {
        var timeSinceLastPing = Date.now() - lastPingTime;
        if (timeSinceLastPing > 45000) {
            console.log('No ping received for 45s, reconnecting...');
            connect();
        }
    }, 10000);
    
    // Handle tab visibility changes - refresh state when tab becomes visible
    // This fixes sync issues caused by browser throttling background tabs
    document.addEventListener('visibilitychange', function() {
        if (document.visibilityState === 'visible') {
            console.log('Tab became visible, refreshing state...');
            connect();
        }
    });
    
    // Initial connection and countdowns
    connect();
    startCountdowns();
    setupMarblePicker();
    bindShareButton();
    bindQrButton();
    previousMarbleCount = getCurrentMarbleCount();
    
    // Cleanup on page unload
    window.addEventListener('beforeunload', function() {
        if (pingCheckInterval) clearInterval(pingCheckInterval);
        if (countdownInterval) clearInterval(countdownInterval);
        if (reconnectTimeout) clearTimeout(reconnectTimeout);
        if (eventSource) eventSource.close();
    });
}
