function initHomePage() {
    var STORAGE_TIMED_ENABLED = 'marblegame_chess_timed_enabled';
    var STORAGE_CLOCK_MINUTES = 'marblegame_chess_clock_minutes';

    function parseStoredMinutes(raw) {
        var n = parseInt(raw || '', 10);
        if (isNaN(n)) return null;
        if (n < 1) return 1;
        if (n > 60) return 60;
        return n;
    }

    function applyPersistedChessOptions() {
        var timedCheckbox = document.getElementById('timed-mode');
        var minutesInput = document.getElementById('clock-minutes');
        var timedConfig = document.getElementById('timed-config-wrap') || document.querySelector('.timed-config');
        if (!timedCheckbox || !minutesInput || !timedConfig) return;

        var storedTimed = localStorage.getItem(STORAGE_TIMED_ENABLED);
        if (storedTimed === '1' || storedTimed === '0') {
            timedCheckbox.checked = storedTimed === '1';
        }

        var storedMinutes = parseStoredMinutes(localStorage.getItem(STORAGE_CLOCK_MINUTES));
        if (storedMinutes !== null) {
            minutesInput.value = String(storedMinutes);
        }

        timedConfig.classList.toggle('hidden', !timedCheckbox.checked);
    }

    function bindPersistedChessOptions() {
        var timedCheckbox = document.getElementById('timed-mode');
        var minutesInput = document.getElementById('clock-minutes');
        var chessForm = document.getElementById('create-form-chess');
        if (!timedCheckbox || !minutesInput || !chessForm) return;
        if (chessForm.dataset.chessPrefsBound === '1') return;
        chessForm.dataset.chessPrefsBound = '1';

        timedCheckbox.addEventListener('change', function() {
            localStorage.setItem(STORAGE_TIMED_ENABLED, timedCheckbox.checked ? '1' : '0');
        });

        function persistMinutes() {
            var parsed = parseStoredMinutes(minutesInput.value);
            if (parsed === null) return;
            minutesInput.value = String(parsed);
            localStorage.setItem(STORAGE_CLOCK_MINUTES, String(parsed));
        }

        minutesInput.addEventListener('change', persistMinutes);
        minutesInput.addEventListener('blur', persistMinutes);

        chessForm.addEventListener('submit', function() {
            localStorage.setItem(STORAGE_TIMED_ENABLED, timedCheckbox.checked ? '1' : '0');
            persistMinutes();
        });
    }

    applyPersistedChessOptions();
    bindPersistedChessOptions();
}
