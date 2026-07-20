/**
 * Xuantong Admin — 公共 JavaScript 工具
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
        var settled = false;

        function finish(value) {
            if (settled) return;
            settled = true;
            document.removeEventListener('keydown', onKey);
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            resolve(value);
        }

        okBtn.addEventListener('click', function() { finish(true); });
        cancelBtn.addEventListener('click', function() { finish(false); });
        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) finish(false);
        });

        // 键盘支持
        function onKey(e) {
            if (e.key === 'Escape') finish(false);
            if (e.key === 'Enter') finish(true);
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
            await fetch('/api/auth/logout', {
                method: 'POST',
                headers: { 'X-Xuantong-CSRF': getXuantongCsrfToken() }
            });
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
    updateThemeToggle(saved);
}

function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') || 'light';
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    safeStorage.set('theme', next);
    updateThemeToggle(next);
}

function updateThemeToggle(theme) {
    var button = document.querySelector('.theme-toggle');
    if (!button) return;
    var dark = theme === 'dark';
    button.innerHTML = '<i class="fas ' + (dark ? 'fa-sun' : 'fa-moon') + '" aria-hidden="true"></i>';
    button.title = dark ? '切换到亮色主题' : '切换到暗色主题';
    button.setAttribute('aria-label', button.title);
}

// 初始化主题
initTheme();

/* ============================================================
   响应式侧边栏 + 桌面端折叠
   ============================================================ */
function initSidebar() {
    if (!document.querySelector('.sidebar')) return;

    // 桌面端：恢复折叠状态
    if (window.innerWidth > 768) {
        var saved = safeStorage.get('sidebar_collapsed', 'false');
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
        toggle.type = 'button';
        toggle.title = '打开导航菜单';
        toggle.setAttribute('aria-label', '打开导航菜单');
        toggle.setAttribute('aria-expanded', 'false');
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
    var toggle = document.querySelector('.sidebar-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', sidebar && sidebar.classList.contains('open') ? 'true' : 'false');
}

function closeSidebar() {
    var sidebar = document.querySelector('.sidebar');
    var overlay = document.querySelector('.sidebar-overlay');
    if (sidebar) sidebar.classList.remove('open');
    if (overlay) overlay.classList.remove('show');
    var toggle = document.querySelector('.sidebar-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', 'false');
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
        var saved = safeStorage.get('sidebar_collapsed', 'false');
        collapseSidebar(saved === 'true');
    }
});

// 初始化
document.addEventListener('DOMContentLoaded', function() {
    updateThemeToggle(document.documentElement.getAttribute('data-theme') || 'light');
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

async function apiGetText(url) {
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
        return await response.text();
    } catch (error) {
        if (error.message !== 'Unauthorized' && error.message !== 'Forbidden') {
            console.error('API 请求失败:', url, error);
        }
        throw error;
    }
}

function newXuantongOperationId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID();
    }
    var random = Math.random().toString(16).slice(2);
    return Date.now().toString(16) + '-' + random + '-' + random.slice(0, 8);
}

function getXuantongCsrfToken() {
    var prefix = 'XUANTONG_CSRF=';
    var cookies = String(document.cookie || '').split(';');
    for (var i = 0; i < cookies.length; i++) {
        var item = cookies[i].trim();
        if (item.indexOf(prefix) === 0) {
            return decodeURIComponent(item.substring(prefix.length));
        }
    }
    return '';
}

async function apiPost(url, data, operationId) {
    try {
        var response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Xuantong-Operation-Id': operationId || newXuantongOperationId(),
                'X-Xuantong-CSRF': getXuantongCsrfToken()
            },
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
            headers: {
                'Content-Type': 'application/json',
                'X-Xuantong-CSRF': getXuantongCsrfToken()
            },
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
        var response = await fetch(url, {
            method: 'DELETE',
            headers: { 'X-Xuantong-CSRF': getXuantongCsrfToken() }
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

/** 校验 Solon Result，并直接返回 data。 */
function requireApiSuccess(result) {
    if (!result || Number(result.code) !== 200) {
        throw new Error((result && (result.description || result.message)) || '请求失败');
    }
    return result.data;
}

/** 在现有 URL 后追加非空查询参数。 */
function withQuery(url, params) {
    var query = new URLSearchParams();
    Object.keys(params || {}).forEach(function(key) {
        var value = params[key];
        if (value !== undefined && value !== null && value !== '') {
            query.set(key, String(value));
        }
    });
    var encoded = query.toString();
    if (!encoded) return url;
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + encoded;
}

/** 校验并返回统一分页合同，避免页面误把 PageResult 当数组使用。 */
function requirePageResult(result) {
    var page = requireApiSuccess(result);
    if (!page || !Array.isArray(page.items)) {
        throw new Error('服务端返回了无效的分页数据');
    }
    return page;
}

/** 渲染统一的分页控件；onChange(page, pageSize) 负责重新加载数据。 */
function renderPagination(containerId, pageResult, onChange) {
    var container = document.getElementById(containerId);
    if (!container) return;
    if (!pageResult) {
        container.innerHTML = '';
        return;
    }

    var current = Math.max(1, Number(pageResult.page) || 1);
    var pageSize = Math.max(1, Number(pageResult.pageSize) || 20);
    var totalItems = Math.max(0, Number(pageResult.totalItems) || 0);
    var totalPages = Math.max(0, Number(pageResult.totalPages) || 0);
    var lastPage = Math.max(1, totalPages);
    var sizes = [20, 50, 100, 200];
    if (sizes.indexOf(pageSize) < 0) sizes.push(pageSize);
    sizes.sort(function(a, b) { return a - b; });

    container.className = 'pagination';
    container.innerHTML =
        '<span class="page-summary">共 ' + totalItems + ' 条，第 ' + current + ' / ' + lastPage + ' 页</span>' +
        '<button type="button" class="page-btn" data-page="1" ' + (current <= 1 ? 'disabled' : '') + '>首页</button>' +
        '<button type="button" class="page-btn" data-page="' + Math.max(1, current - 1) + '" ' + (current <= 1 ? 'disabled' : '') + '>上一页</button>' +
        '<button type="button" class="page-btn" data-page="' + Math.min(lastPage, current + 1) + '" ' + (!pageResult.hasNext ? 'disabled' : '') + '>下一页</button>' +
        '<button type="button" class="page-btn" data-page="' + lastPage + '" ' + (!pageResult.hasNext ? 'disabled' : '') + '>末页</button>' +
        '<label class="page-size">每页 <select class="form-select form-select-sm" aria-label="每页条数">' +
        sizes.map(function(size) {
            return '<option value="' + size + '" ' + (size === pageSize ? 'selected' : '') + '>' + size + '</option>';
        }).join('') + '</select> 条</label>';

    container.querySelectorAll('[data-page]').forEach(function(button) {
        button.addEventListener('click', function() {
            if (!button.disabled) {
                Promise.resolve(onChange(Number(button.dataset.page), pageSize))
                    .catch(function(error) {
                        showToast(error.message || '分页加载失败', 'error');
                    });
            }
        });
    });
    var sizeSelect = container.querySelector('select');
    if (sizeSelect) {
        sizeSelect.addEventListener('change', function() {
            Promise.resolve(onChange(1, Number(sizeSelect.value)))
                .catch(function(error) {
                    showToast(error.message || '分页加载失败', 'error');
                });
        });
    }
}

/** 解析当前控制面导出的 Prometheus 文本指标。 */
function parsePrometheus(text) {
    var metrics = {};
    String(text || '').split(/\r?\n/).forEach(function(line) {
        var valueLine = line.trim();
        if (!valueLine || valueLine.charAt(0) === '#') return;
        var match = valueLine.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{[^}]*\})?\s+([^\s]+)$/);
        if (!match) return;
        var value = Number(match[2]);
        if (Number.isFinite(value)) metrics[match[1]] = value;
    });
    return metrics;
}

/* ============================================================
   格式化时间
   ============================================================ */
function formatTime(ts) {
    if (!ts) return '-';
    return new Date(ts).toLocaleString('zh-CN');
}

function formatDateTime(value) {
    if (value == null || value === '') return '-';
    var date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString('zh-CN', { hour12: false });
}

function formatBytes(bytes) {
    var value = Number(bytes);
    if (!Number.isFinite(value) || value < 0) return '-';
    if (value === 0) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
    var amount = value / Math.pow(1024, index);
    return amount.toFixed(index === 0 || amount >= 100 ? 0 : 1) + ' ' + units[index];
}
