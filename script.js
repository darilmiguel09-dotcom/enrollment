const $ = id => document.getElementById(id);

const read = (key, fallback) => {
    try {
        return JSON.parse(
            localStorage.getItem(key) || JSON.stringify(fallback)
        );
    } catch {
        return fallback;
    }
};

function accounts() {
    return read('taal_accounts', {});
}

function records() {
    return read('taal_enrollments', []);
}

// ---- Optional Java backend (TaalServer.java) ----
// When the site is opened through TaalServer, data is saved on the server.
// Otherwise (file:// or any plain web server) it falls back to this browser's storage.
async function api(path, options) {
    if (!location.protocol.startsWith('http')) return null;

    try {
        const res = await fetch(path, options);
        const type = res.headers.get('content-type') || '';

        if (!type.includes('application/json')) return null;

        return { status: res.status, ok: res.ok, data: await res.json() };
    } catch {
        return null;
    }
}

const FORM_HEADERS = { 'Content-Type': 'application/x-www-form-urlencoded' };

function requireLogin() {
    if (sessionStorage.getItem('taal_logged_in') !== 'yes') {
        alert('Please log in first.');
        location.href = 'login.html';
        return false;
    }
    return true;
}

// Protect enrollment page
if (
    location.pathname.endsWith('/enrollment.html') ||
    location.pathname === 'enrollment.html'
) {
    if (sessionStorage.getItem('taal_logged_in') !== 'yes') {
        alert('Please log in first to access enrollment.');
        location.href = 'login.html';
    }
}

// Switch login, register, and forgot password forms
function setAuth(mode) {
    ['loginForm', 'registerForm', 'forgotForm'].forEach(id => {
        if ($(id)) $(id).hidden = true;
    });

    if ($(mode + 'Form')) {
        $(mode + 'Form').hidden = false;
    }

    ['loginTab', 'registerTab'].forEach(id => {
        if ($(id)) {
            $(id).classList.toggle('active', id === mode + 'Tab');
        }
    });
}

// Student login
if ($('loginForm')) {
    $('loginForm').addEventListener('submit', e => {
        e.preventDefault();

        let email = $('loginEmail').value.trim().toLowerCase();
        let password = $('loginPass').value;

        if (accounts()[email] === password) {
            sessionStorage.setItem('taal_logged_in', 'yes');
            location.href = 'enrollment.html';
        } else {
            alert('Incorrect Gmail address or password.');
        }
    });
}

// Create account
if ($('registerForm')) {
    $('registerForm').addEventListener('submit', e => {
        e.preventDefault();

        let email = $('regEmail').value.trim().toLowerCase();
        let password = $('regPass').value;

        if (!email.endsWith('@gmail.com')) {
            return alert('Use a Gmail address ending in @gmail.com.');
        }

        if (password !== $('regConfirm').value) {
            return alert('Passwords do not match.');
        }

        let a = accounts();

        if (a[email]) {
            return alert('This Gmail is already registered.');
        }

        a[email] = password;

        localStorage.setItem('taal_accounts', JSON.stringify(a));

        alert('Account created. Please log in.');

        $('registerForm').reset();
        setAuth('login');
    });
}

// Forgot password
if ($('forgotForm')) {
    $('forgotForm').addEventListener('submit', e => {
        e.preventDefault();

        let email = $('forgotEmail').value.trim().toLowerCase();
        let password = $('newPassword').value;

        if (password !== $('confirmNewPassword').value) {
            return alert('Passwords do not match.');
        }

        let a = accounts();

        if (!a[email]) {
            return alert('Account not found.');
        }

        a[email] = password;

        localStorage.setItem('taal_accounts', JSON.stringify(a));

        alert('Password changed in this browser demo. No email was sent.');

        $('forgotForm').reset();
        setAuth('login');
    });
}

// School level: Junior High (Grades 7-10) or Senior High (Grades 11-12)
if ($('enrollmentForm') && $('grade')) {
    const GRADES = {
        'Junior High School': [7, 8, 9, 10],
        'Senior High School': [11, 12]
    };

    function applyLevel() {
        const picked = document.querySelector('input[name="School Level"]:checked');
        const level = picked ? picked.value : '';
        const shs = level === 'Senior High School';
        const grade = $('grade');

        grade.innerHTML = '<option value="">' +
            (level ? 'Select grade' : 'Select level first') + '</option>';

        (GRADES[level] || []).forEach(n => {
            grade.add(new Option('Grade ' + n, 'Grade ' + n));
        });

        grade.disabled = !level;

        document.querySelectorAll('.shs-only').forEach(box => {
            box.hidden = !shs;

            box.querySelectorAll('select').forEach(select => {
                select.disabled = !shs;
                if (!shs) select.value = '';
            });
        });

        if (!shs) {
            $('strand').innerHTML = '<option value="">Select track first</option>';
        } else {
            $('strand').disabled = !$('track').value;
        }
    }

    document.querySelectorAll('input[name="School Level"]').forEach(radio => {
        radio.addEventListener('change', applyLevel);
    });

    $('enrollmentForm').addEventListener('reset', () => setTimeout(applyLevel, 0));
}

// Track and strand selection
if ($('track')) {
    $('track').addEventListener('change', () => {
        let list = {
            Academic: ['GAS', 'HUMSS', 'STEM', 'ABM'],
            TVL: ['HE', 'CP', 'CE']
        }[$('track').value] || [];

        let strand = $('strand');

        strand.innerHTML = '<option value="">Select strand</option>';

        list.forEach(value => {
            strand.add(new Option(value, value));
        });

        strand.disabled = !list.length;
    });
}

// Enrollment form submission
if ($('enrollmentForm')) {
    $('enrollmentForm').addEventListener('submit', async e => {
        e.preventDefault();

        if (!requireLogin() || !e.target.reportValidity()) {
            return;
        }

        let data = {};

        new FormData(e.target).forEach((value, key) => {
            data[key] = value;
        });

        data['Submission Date'] = new Date().toLocaleString();

        // Try the Java server first
        let server = await api('/api/enroll', {
            method: 'POST',
            headers: FORM_HEADERS,
            body: new URLSearchParams(new FormData(e.target)).toString()
        });

        if (server) {
            if (!server.ok) {
                return alert(server.data.error || 'Could not save the enrollment.');
            }

            data['Submission Date'] = server.data.submissionDate || data['Submission Date'];

            localStorage.setItem('taal_last_submission', JSON.stringify(data));
            localStorage.setItem('taal_last_where', 'server');

            location.href = 'confirmation.html';
            return;
        }

        // No server: save in this browser
        let all = records();

        let clean = value =>
            String(value || '')
                .trim()
                .toLowerCase()
                .replace(/\s+/g, ' ');

        let duplicate = all.some(record =>
            (data.LRN && clean(record.LRN) === clean(data.LRN)) ||
            (
                clean(record['Student Name']) === clean(data['Student Name']) &&
                clean(record['Birth Date']) === clean(data['Birth Date'])
            )
        );

        if (duplicate) {
            return alert('Duplicate enrollment detected. This record was not saved.');
        }

        all.push(data);

        localStorage.setItem('taal_enrollments', JSON.stringify(all));
        localStorage.setItem('taal_last_submission', JSON.stringify(data));
        localStorage.setItem('taal_last_where', 'browser');

        location.href = 'confirmation.html';
    });
}

// Display enrollment confirmation
if ($('review')) {
    if (localStorage.getItem('taal_last_where') === 'server') {
        if ($('confirmMsg')) $('confirmMsg').textContent =
            'Your enrollment has been submitted to the school.';
        if ($('confirmNotice')) $('confirmNotice').textContent =
            'Your record was saved on the school server. Keep a copy of these details.';
    }

    let data = read('taal_last_submission', {});

    Object.entries(data).forEach(([key, value]) => {
        let div = document.createElement('div');
        div.className = 'item';

        let bold = document.createElement('b');
        bold.textContent = key;

        let span = document.createElement('span');
        span.textContent = value;

        div.append(bold, span);
        $('review').append(div);
    });
}

// Admin login
if ($('adminLoginForm')) {
    $('adminLoginForm').addEventListener('submit', async e => {
        e.preventDefault();

        // Try the Java server first
        let server = await api('/api/admin/login', {
            method: 'POST',
            headers: FORM_HEADERS,
            body: new URLSearchParams({
                user: $('adminUser').value,
                pass: $('adminPass').value
            }).toString()
        });

        if (server) {
            if (server.ok) {
                sessionStorage.setItem('taal_admin_token', server.data.token);
                sessionStorage.setItem('taal_admin_logged_in', 'yes');
                location.reload();
            } else {
                alert(server.data.error || 'Incorrect admin login.');
            }
            return;
        }

        // No server: demo login in this browser
        if (
            $('adminUser').value === 'admin01' &&
            $('adminPass').value === 'admin0623'
        ) {
            sessionStorage.setItem('taal_admin_logged_in', 'yes');
            location.reload();
        } else {
            alert('Incorrect admin login.');
        }
    });
}

// Get records from the server (admin token) or from this browser
async function loadRecords() {
    let token = sessionStorage.getItem('taal_admin_token');

    if (token) {
        let server = await api('/api/admin/enrollments', {
            headers: { 'X-Admin-Token': token }
        });

        if (server && server.ok) return server.data;

        if (server && server.status === 401) {
            alert('Your admin session expired. Please log in again.');
            adminLogout();
            return [];
        }
    }

    return records();
}

// Display enrollment records
async function showRecords() {
    if (!$('recordsList')) return;

    let all = await loadRecords();

    $('recordCount').textContent = all.length;
    $('recordsList').innerHTML = '';

    if (!all.length) {
        $('recordsList').textContent = 'No enrollment records yet.';
        return;
    }

    all.slice().reverse().forEach(record => {
        let div = document.createElement('div');
        div.className = 'item';

        let bold = document.createElement('b');
        bold.textContent = record['Student Name'] || 'Unnamed';

        let span = document.createElement('span');
        span.textContent =
            'LRN: ' + (record.LRN || '') +
            ' | ' + (record['Grade Level'] || '');

        div.append(bold, span);
        $('recordsList').append(div);
    });
}

// Admin dashboard
if ($('adminPanel')) {
    let loggedIn =
        sessionStorage.getItem('taal_admin_logged_in') === 'yes';

    $('adminLogin').hidden = loggedIn;
    $('adminPanel').hidden = !loggedIn;

    if (loggedIn) {
        showRecords();
    }
}

// Admin logout
function adminLogout() {
    sessionStorage.removeItem('taal_admin_logged_in');
    sessionStorage.removeItem('taal_admin_token');
    location.reload();
}

// Export records to CSV
async function exportCSV() {
    let all = await loadRecords();

    if (!all.length) {
        return alert('No records to export.');
    }

    let headers = [
        ...new Set(all.flatMap(record => Object.keys(record)))
    ];

    let quote = value =>
        '"' + String(value ?? '').replace(/"/g, '""') + '"';

    let csv =
        '\uFEFF' +
        [
            headers.map(quote).join(','),
            ...all.map(record =>
                headers.map(header => quote(record[header])).join(',')
            )
        ].join('\r\n');

    let blob = new Blob([csv], {
        type: 'text/csv;charset=utf-8'
    });

    let url = URL.createObjectURL(blob);

    let link = document.createElement('a');
    link.href = url;
    link.download = 'Taal_HS_Enrollment_Records.csv';
    link.click();

    URL.revokeObjectURL(url);
}

// Student logout
function logoutStudent() {
    sessionStorage.removeItem('taal_logged_in');
    location.href = 'index.html';
}