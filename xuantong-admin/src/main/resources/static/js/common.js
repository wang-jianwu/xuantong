/**
 * Xuantong Config Admin — 公共 JavaScript 工具
 * escapeHtml · Toast 通知 · 确认弹窗 · localStorage 安全封装 · 主题切换 · 侧边栏
 */

/* ============================================================
   HTML 转义（防 XSS）
   ============================================================ */
function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/* ============================================================
   Toast 通知（替代 alert）
   ============================================================ */
function showToast(message, type) {
    type = type || 'info';
    var container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    var icons = {
        success: 'fa-check-circle',
        error: 'fa-exclamation-circle',
        warning: 'fa-exclamation-triangle',
        info: 'fa-info-circle'
    };

    var toast = document.createElement('div');
    toast.className = 'toast-item toast-' + type;
    toast.innerHTML = '<i class="fas ' + (icons[type] || icons.info) + '"></i> ' + escapeHtml(message);
    toast.addEventListener('click', function() { dismissToast(toast); });
    container.appendChild(toast);

    // 自动消失
    var duration = type === 'error' ? 5000 : 3000;
    setTimeout(function() { dismissToast(toast); }, duration);
}

function dismissToast(toast) {
    if (!toast || toast.classList.contains('toast-out')) return;
    toast.classList.add('toast-out');
    toast.addEventListener('animationend', function() {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
    });
}

/* ============================================================
   确认弹窗（替代 confirm，返回 Promise）
   ============================================================ */
function showConfirm(title, message, confirmText, cancelText, confirmClass) {
    return new Promise(function(resolve) {
        var overlay = document.createElement('div');
        overlay.className = 'confirm-overlay';

        var btnClass = confirmClass || 'btn-danger';
        var okText = confirmText || '确定';
        var noText = cancelText || '取消';

        overlay.innerHTML =
            '<div class="confirm-dialog">' +
            '<h5>' + escapeHtml(title) + '</h5>' +
            '<p>' + escapeHtml(message) + '</p>' +
            '<div class="confirm-actions">' +
            '<button class="btn btn-secondary cancel-btn">' + escapeHtml(noText) + '</button>' +
            '<button class="btn ' + btnClass + ' ok-btn">' + escapeHtml(okText) + '</button>' +
            '</div></div>';

        document.body.appendChild(overlay);

        var okBtn = overlay.querySelector('.ok-btn');
        var cancelBtn = overlay.querySelector('.cancel-btn');

        function cleanup() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }

        okBtn.addEventListener('click', function() { cleanup(); resolve(true); });
        cancelBtn.addEventListener('click', function() { cleanup(); resolve(false); });
        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) { cleanup(); resolve(false); }
        });

        // 键盘支持
        function onKey(e) {
            if (e.key === 'Escape') { cleanup(); resolve(false); document.removeEventListener('keydown', onKey); }
            if (e.key === 'Enter') { cleanup(); resolve(true); document.removeEventListener('keydown', onKey); }
        }
        document.addEventListener('keydown', onKey);

        okBtn.focus();
    });
}

/* ============================================================
   localStorage 安全封装（隐私模式/无痕模式兼容）
   ============================================================ */
var safeStorage = {
    get: function(key, defaultValue) {
        try {
            var val = localStorage.getItem(key);
            return val !== null ? val : (defaultValue !== undefined ? defaultValue : null);
        } catch (e) {
            return defaultValue !== undefined ? defaultValue : null;
        }
    },
    set: function(key, value) {
        try {
            localStorage.setItem(key, value);
            return true;
        } catch (e) {
            return false;
        }
    },
    remove: function(key) {
        try {
            localStorage.removeItem(key);
            return true;
        } catch (e) {
            return false;
        }
    },
    getJSON: function(key, defaultValue) {
        try {
            var val = localStorage.getItem(key);
            return val ? JSON.parse(val) : (defaultValue !== undefined ? defaultValue : null);
        } catch (e) {
            return defaultValue !== undefined ? defaultValue : null;
        }
    },
    setJSON: function(key, value) {
        try {
            localStorage.setItem(key, JSON.stringify(value));
            return true;
        } catch (e) {
            return false;
        }
    }
};

/* ============================================================
   登出（统一实现）
   ============================================================ */
async function logout() {
    try {
        var confirmed = await showConfirm('退出登录', '确定要退出登录吗？', '退出', '取消', 'btn-primary');
        if (confirmed) {
            await fetch('/api/auth/logout');
            window.location.href = '/login';
        }
    } catch (error) {
        console.error('退出失败:', error);
        showToast('退出失败', 'error');
    }
}

/* ============================================================
   主题切换
   ============================================================ */
function initTheme() {
    var saved = safeStorage.get('theme', 'light');
    document.documentElement.setAttribute('data-theme', saved);
}

function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') || 'light';
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    safeStorage.set('theme', next);
}

// 初始化主题
initTheme();

/* ============================================================
   响应式侧边栏 + 桌面端折叠
   ============================================================ */
function initSidebar() {
    // 桌面端：恢复折叠状态
    if (window.innerWidth > 768) {
        var saved = safeStorage.get('sidebar_collapsed', 'true');
        if (saved === 'true') {
            collapseSidebar(true);
        }
        return;
    }

    // 移动端：创建汉堡按钮和遮罩
    var toggle = document.querySelector('.sidebar-toggle');
    if (!toggle) {
        toggle = document.createElement('button');
        toggle.className = 'sidebar-toggle';
        toggle.innerHTML = '<i class="fas fa-bars"></i>';
        toggle.addEventListener('click', toggleSidebar);
        document.body.appendChild(toggle);
    }

    var overlay = document.querySelector('.sidebar-overlay');
    if (!overlay) {
        overlay = document.createElement('div');
        overlay.className = 'sidebar-overlay';
        overlay.addEventListener('click', closeSidebar);
        document.body.appendChild(overlay);
    }
}

function toggleSidebar() {
    var sidebar = document.querySelector('.sidebar');
    var overlay = document.querySelector('.sidebar-overlay');
    if (sidebar) sidebar.classList.toggle('open');
    if (overlay) overlay.classList.toggle('show');
}

function closeSidebar() {
    var sidebar = document.querySelector('.sidebar');
    var overlay = document.querySelector('.sidebar-overlay');
    if (sidebar) sidebar.classList.remove('open');
    if (overlay) overlay.classList.remove('show');
}

/** 桌面端侧边栏折叠/展开 */
function toggleCollapse() {
    var sidebar = document.querySelector('.sidebar');
    if (!sidebar) return;
    var isCollapsed = sidebar.classList.contains('collapsed');
    collapseSidebar(!isCollapsed);
}

function collapseSidebar(collapsed) {
    var sidebar = document.querySelector('.sidebar');
    if (!sidebar) return;

    if (collapsed) {
        sidebar.classList.add('collapsed');
    } else {
        sidebar.classList.remove('collapsed');
    }
    safeStorage.set('sidebar_collapsed', collapsed ? 'true' : 'false');
}

// 点击页面内容关闭移动端侧边栏
document.addEventListener('click', function(e) {
    if (window.innerWidth <= 768) {
        var sidebar = document.querySelector('.sidebar');
        var toggle = document.querySelector('.sidebar-toggle');
        if (sidebar && sidebar.classList.contains('open')
            && !sidebar.contains(e.target)
            && !(toggle && toggle.contains(e.target))) {
            closeSidebar();
        }
    }
});

// 窗口大小变化时处理
window.addEventListener('resize', function() {
    if (window.innerWidth > 768) {
        closeSidebar();
        var saved = safeStorage.get('sidebar_collapsed', 'true');
        collapseSidebar(saved === 'true');
    }
});

// 初始化
document.addEventListener('DOMContentLoaded', function() {
    initSidebar();
    // 绑定侧边栏折叠按钮
    var collapseBtn = document.getElementById('sidebar-collapse-btn');
    if (collapseBtn) {
        collapseBtn.addEventListener('click', toggleCollapse);
    }
});

/* ============================================================
   API 请求封装（统一错误处理）
   ============================================================ */
async function apiGet(url) {
    try {
        var response = await fetch(url);
        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('Unauthorized');
        }
        if (response.status === 403) {
            showToast('权限不足，仅管理员可操作', 'error');
            throw new Error('Forbidden');
        }
        return await response.json();
    } catch (error) {
        if (error.message !== 'Unauthorized' && error.message !== 'Forbidden') {
            console.error('API 请求失败:', url, error);
        }
        throw error;
    }
}

async function apiPost(url, data) {
    try {
        var response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('Unauthorized');
        }
        if (response.status === 403) {
            showToast('权限不足，仅管理员可操作', 'error');
            throw new Error('Forbidden');
        }
        return await response.json();
    } catch (error) {
        if (error.message !== 'Unauthorized' && error.message !== 'Forbidden') {
            console.error('API 请求失败:', url, error);
        }
        throw error;
    }
}

async function apiPut(url, data) {
    try {
        var response = await fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('Unauthorized');
        }
        if (response.status === 403) {
            showToast('权限不足，仅管理员可操作', 'error');
            throw new Error('Forbidden');
        }
        return await response.json();
    } catch (error) {
        if (error.message !== 'Unauthorized' && error.message !== 'Forbidden') {
            console.error('API 请求失败:', url, error);
        }
        throw error;
    }
}

async function apiDelete(url) {
    try {
        var response = await fetch(url, { method: 'DELETE' });
        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('Unauthorized');
        }
        if (response.status === 403) {
            showToast('权限不足，仅管理员可操作', 'error');
            throw new Error('Forbidden');
        }
        return await response.json();
    } catch (error) {
        if (error.message !== 'Unauthorized' && error.message !== 'Forbidden') {
            console.error('API 请求失败:', url, error);
        }
        throw error;
    }
}

/* ============================================================
   格式化时间
   ============================================================ */
function formatTime(ts) {
    if (!ts) return '-';
    return new Date(ts).toLocaleString('zh-CN');
}
