package cn.dumate.a6studio.runtime

/**
 * 源自 Jingdou Studio 的 AutoJs6 护栏运行时底座（aj6_kit.js v1.0，严格 ES5 语法）。
 *
 * 内置真机实战护栏：
 * - 无障碍控件树优先 + 离屏包围盒校验（inScreen）
 * - bounds 中心点安全点击
 * - 遇安全验证 / 风控立即停手等待（guardRisk）
 * - 危险落地页（结算/支付/购物车/授权弹窗）自动回退（guardRisky）
 * - 倒计时归零 + 余量轮询（waitCountdown）
 */
object Aj6KitRuntime {

    private val PART_1 = """
/**
 * aj6_kit.js  v1.0  —  Jingdou Studio 运行时（目标端库）
 * ------------------------------------------------------------------
 * 设计原则（全部来自本仓库十几个京东脚本的真机结论，不是凭空写的）：
 *   ① 只用无障碍控件树做主路径，OCR 只做兜底（jd_channel_common.js 头注）；
 *   ② 文本存在 ≠ 可见：京东把离屏节点留在树里，必须验包围盒（inScreen）；
 *   ③ 点击一律按 bounds 中心 click(x,y)，不依赖 clickable / compass 兜底；
 *   ④ 遇「安全验证 / 京东验证」停下等人，绝不硬闯（jd_频道_*、jd_9.9包邮 v2.4）；
 *   ⑤ 危险落地页（结算/支付/购物车）立即 back，绝不点按钮（jd_9.9包邮 v2.14）；
 *   ⑥ 浏览类任务必须「轮询倒计时归零 + 余量」再点返回，禁止固定 sleep（jd_JOY游记 v1.2）；
 *   ⑦ 逐条任务要能去重、能轮转（defer）、能记 leftover，否则把没做完报成 0（v2.9/v2.19）；
 *   ⑧ AutoJs6 自己抢前台不算「跨 App」（v2.19 isSelfApp）。
 *
 * 兼容性：ES5 语法（var / function），Rhino 与 V8 引擎均可运行。
 * 可测试性：所有设备 API 走 PRIM 封装层，Node 端可整套替换（见 test/fake_aj6.mjs）。
 * ------------------------------------------------------------------
 */

var Kit = (function () {

    /* ==================================================================
     * 一、设备 API 封装层
     * 目的：把「哪些 AutoJs6 API 被用到」显式列出来，并可整体替换。
     * 解析顺序：globalThis 上的同名函数 → 脚本全局作用域里的函数。
     * ================================================================== */

    function root() {
        try { if (typeof globalThis !== 'undefined' && globalThis) return globalThis; } catch (e) { }
        try { if (typeof global !== 'undefined' && global) return global; } catch (e) { }
        return null;
    }

    /**
     * 注入表。键名 = AutoJs6 的全局函数名，因此 Node 端只要按同名打桩就能整套替换。
     * 例：Kit.configure({ click: fn, swipe: fn, sleep: fn, ocr: {...} })
     */
    var OVR = {};

    function over(name) {
        return Object.prototype.hasOwnProperty.call(OVR, name) ? OVR[name] : null;
    }

    /** 优先用注入实现，其次用全局同名函数，最后用脚本作用域里的同名函数 */
    function call(name, args, fallback) {
        var o = over(name);
        if (o) { return (typeof o === 'function') ? o.apply(null, args) : o; }
        var r = root();
        if (r && typeof r[name] === 'function') { try { return r[name].apply(null, args); } catch (e) { } }
        if (fallback) { return fallback.apply(null, args); }
        try { return eval(name).apply(null, args); } catch (e) { return undefined; }
    }

    /** 读取注入对象上的属性（device / app / files / images / ocr 这类命名空间） */
    function ns(name) {
        var o = over(name);
        if (o) return o;
        var r = root();
        if (r && r[name]) return r[name];
        try { return eval(name); } catch (e) { return null; }
    }

    var PRIM = {
        /* ---- 查询 ---- */
        text: function (v) { return call('text', [v]); },
        textContains: function (v) { return call('textContains', [v]); },
        desc: function (v) { return call('desc', [v]); },
        descContains: function (v) { return call('descContains', [v]); },
        className: function (v) { return call('className', [v]); },
        /**
         * 全树节点（含**无文字**的可点击容器）。
         *
         * 为什么需要它：takeSnap() 只收「text 或 desc 非空」的节点（否则快照会被
         * 无数无名容器塞爆）。但「点2个商品」任务的商品卡恰恰是 clickable 且
         * text/desc 全空的纯图片卡 —— 快照里永远没有它们。
         * 真机 15:33 实测（scratch/do_click2items.mjs 就是这么跑通的）：
         *   左 [27,1626][534,2328] / 右 [546,1626][1053,2328]，clickable=true，无文字。
         * 所以这种「按几何找无名可点卡」的场景必须单独走一次全树遍历。
         */
        allNodes: function () {
            var c = call('className', [/.*/]);
            if (!c) return null;
            try { return c.find(); } catch (e) { return null; }
        },

        /* ---- 动作 ---- */
        click: function (x, y) { return call('click', [x, y]); },
        longClick: function (x, y) { return call('longClick', [x, y]); },
        swipeIn: function (a, b, c, d, ms) { return call('swipe', [a, b, c, d, ms]); },
        backIn: function () { return call('back', []); },
        homeIn: function () { return call('home', []); },
        inputIn: function (t) { return call('input', [t]); },
        setTextIn: function (i, t) { return call('setText', [i, t]); },
        sleepIn: function (ms) { call('sleep', [ms]); },
        toastIn: function (m) { call('toast', [m]); },

        /* ---- 环境 ---- */
        currentPackage: function () { return String(call('currentPackage', []) || ''); },
        currentActivity: function () { return String(call('currentActivity', []) || ''); },
        width: function () {
            var d = ns('device');
            var w = d && typeof d.width === 'number' ? d.width : 0;
            return w || 1080;
        },
        height: function () {
            var d = ns('device');
            var h = d && typeof d.height === 'number' ? d.height : 0;
            return h || 2400;
        },
        launchApp: function (pkg) {
            var a = ns('app');
            if (a && a.launch) { try { a.launch(pkg); return true; } catch (e) { return false; } }
            return false;
        },
        openUrl: function (u) {
            var a = ns('app');
            if (a && a.openUrl) { try { a.openUrl(u); return true; } catch (e) { return false; } }
            return false;
        },

        /* ---- 文件 ---- */
        writeFile: function (p, s) {
            var f = ns('files');
            if (f && f.write) { try { f.write(p, s); } catch (e) { } }
        },
        appendFile: function (p, s) {
            var f = ns('files');
            if (f && f.append) { try { f.append(p, s); } catch (e) { } }
        },

        /* ---- 截图 / OCR ---- */
        captureScreen: function () {
            var im = ns('images');
            if (im && im.captureScreen) { try { return im.captureScreen(); } catch (e) { return null; } }
            return null;
        },
        clip: function (img, x, y, w, h) {
            var im = ns('images');
            if (im && im.clip) { try { return im.clip(img, x, y, w, h); } catch (e) { return null; } }
            return null;
        },
        recycle: function (img) {
            try { if (img && img.recycle) img.recycle(); } catch (e) { }
        },
        ocrDetect: function (img) {
            var o = ns('ocr');
            if (o && o.detect) { try { return o.detect(img) || []; } catch (e) { return []; } }
            return [];
        },

        /* ---- 时钟（测试可注入虚拟时间，避免真等） ---- */
        now: function () {
            var n = over('now');
            if (typeof n === 'function') return n();
            if (typeof __simNow === 'function') return __simNow();
            return Date.now();
        },
        clock: function () {
            var c = over('clock');
            if (typeof c === 'function') return c();
            var d = new Date();
            var p = function (v) { return (v < 10 ? '0' : '') + v; };
            return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
                + '.' + ('00' + d.getMilliseconds()).slice(-3);
        },

        /** 日志出口。默认 console.log；测试可注入收集器以断言日志内容。 */
        logLine: function (s) {
            var f = over('logLine');
            if (typeof f === 'function') { try { f(s); return; } catch (e) { } }
            try { console.log(s); } catch (e) { }
        }
    };

    /** 整体替换设备 API（Node 测试 / 其它容器用） */
    function configure(patch) {
        for (var k in patch) {
            if (Object.prototype.hasOwnProperty.call(patch, k)) OVR[k] = patch[k];
        }
        return PRIM;
    }

    /* ==================================================================
     * 二、几何与字符串工具
     * ================================================================== */

    function isArr(v) { return Object.prototype.toString.call(v) === '[object Array]'; }

    function rectOf(n) {
        var b = null;
        try { b = n.bounds(); } catch (e) { b = null; }
        if (!b) return null;
        var l = b.left, t = b.top, r = b.right, bo = b.bottom;
        if (typeof l !== 'number' || typeof t !== 'number') return null;
        return {
            left: l, top: t, right: r, bottom: bo,
            width: r - l, height: bo - t,
            cx: Math.round((l + r) / 2), cy: Math.round((t + bo) / 2)
        };
    }

    /**
     * 在屏内判定。容差 +40 是 jd_channel_common.js 的实测值：
     * 京东某些卡片底部被系统手势条压住 2~4px，严格 <= H 会全部被丢弃。
     */
    function inScreen(b, H, W, slack) {
        if (!b) return false;
        var s = (typeof slack === 'number') ? slack : 40;
        return (b.right - b.left) > 0 && (b.bottom - b.top) > 0
            && b.top >= -s && b.bottom <= H + s
            && b.left >= -s && b.right <= W + s;
    }

    function inZones(cy, zones) {
        if (!zones || !zones.length) return false;
        for (var i = 0; i < zones.length; i++) {
            var z = zones[i];
            if (cy >= z[0] && cy <= z[1]) return true;
        }
        return false;
    }

    function padStr(s, n) { var r = String(s); while (r.length < n) r = '0' + r; return r; }

    /* ==================================================================
     * 三、引擎
     * ================================================================== */

    function boot(cfgIn) {
        var cfg = cfgIn || {};
        var pol = cfg.policy || {};
        var pkg = cfg.pkg || '';
        var selfRe = cfg.selfPkgPattern ? new RegExp(cfg.selfPkgPattern, 'i') : /autojs6|org\.autojs/i;
        var riskKws = pol.riskKeywords || ['检测到安全风险', '京东验证', '请完成验证', '安全验证'];
        var riskyRe = pol.riskyActivityRe ? new RegExp(pol.riskyActivityRe, 'i') : null;
        var forbid = pol.forbiddenZones || [];
        /**
         * skipKeywords：任务标题命中这些词就整条跳过，不点。
         *
         * 来源：jd_京东服务.js 的 CFG.skipKeywords —— 「邀请好友 / 下单 / 支付 / 绑卡」
         * 这类任务**不是浏览类**，点进去会跳出京东、要求真实操作（下单/实名），
         * 自动化去做要么做不成、要么动到用户的钱。原脚本是手写黑名单逐个判断，
         * 这里提到 policy 层，所有流程共用一份，且 AI 补丁改不动（guardOps 保护 policy）。
         *
         * 命中后的动作：记入 stats.skipped + 记一行日志 + **不点**，继续下一条。
         * 不是「静默丢弃」—— 收尾的 leftover 统计里会看到。
         */
        var skipKws = pol.skipKeywords || [];
        var maxRuntime = pol.maxRuntimeMs || 540000;
        var riskWait = pol.riskWaitMs || 90000;
        var W = PRIM.width(), H = PRIM.height();

        var t0 = PRIM.now();
        var logFile = cfg.logFile || ('jdstudio_' + (cfg.id || 'flow') + '.log');
        var stats = { acted: 0, skipped: 0, failed: 0, leftover: 0, leftoverTitles: [], scrolls: 0, clicks: 0 };
        var cache = { nodes: null, at: 0 };

        /**
         * OCR 快照缓存。
         *
         * 为什么要缓存：真机实测一次全屏 OCR 1643ms（jd_JOY游记 实测 1690ms），
         * 而 canvas 页（BabelActivity，无障碍节点总数 = 0）一个流程里要在同一帧上
         * 连问好几个问题（「有没有签到卡」「有没有刮卡+1」「有没有开心收下」）。
         * 不缓存就是每问一次重截一次屏 —— 3 个问题 = 5s 纯等待。
         *
         * 缓存键带 band：不同裁剪区是不同的帧内容，不能混用。
         * 失效时机：任何会改变画面的动作（点击 / 滑动 / 返回 / 切 App）都会走
         * invalidate()，那里一并清掉 —— 忘了清就会拿旧帧做判断，
         * 表现为「点了没反应」（看着像点击失效，其实是读了旧截图）。
         */
        var ocache = { lines: null, at: 0, key: '' };
        var _lastRiskAt = 0;

        /* ---------------- 日志 ---------------- */

        function log(m) {
            var line = '[' + PRIM.clock() + '] ' + m;
            PRIM.logLine(line);
            PRIM.appendFile(logFile, line + '\n');
        }

        function stat(k, d) {
            if (typeof stats[k] !== 'number') stats[k] = 0;
            stats[k] += (typeof d === 'number' ? d : 1);
        }

        function elapsed() { return PRIM.now() - t0; }

        function overBudget() {
            if (elapsed() > maxRuntime) {
                log('  ! 已达单次运行上限 ' + Math.round(maxRuntime / 1000) + 's，收工');
                return true;
            }
            return false;
        }

        /* ---------------- 前台判断 ---------------- */

        function isSelfApp() { return selfRe.test(PRIM.currentPackage()); }

        function isFg() {
            var p = PRIM.currentPackage();
            if (!pkg) return true;
            if (p === pkg) return true;
            return isSelfApp();   // AutoJs6 自己抢前台 ≠ 离开目标 App
        }

        /**
         * 跨 App 时控件树是别家的，缓存会污染后续判断（jd_秒杀.js 的坑：
         * 跨 App 落地页回来缓存还是上一屏 → 判成「没跳走」→ 反复重点）。
         */
        function inOtherApp() {
            var p = PRIM.currentPackage();
            return !!(p && pkg && p !== pkg && !isSelfApp());
        }

        /** 桌面由 launcher 承载，组件内部可能仍是目标 App 的 WebView，只能看包名 */
        function isDesktop() {
            return /launcher|home|桌面/i.test(PRIM.currentPackage());
        }

        /* ---------------- 风控与系统弹窗 ---------------- */

        function atRisk() {
            for (var i = 0; i < riskKws.length; i++) {
                try { if (PRIM.textContains(riskKws[i]).exists()) return riskKws[i]; } catch (e) { }
            }
            return null;
        }

        /**
         * 遇到验证页：通知人 → 等（默认 90s）。等不到就停手，绝不尝试绕过。
         * 3 秒内重复调用直接返回 false，避免同一个验证页被反复 toast 刷屏。
         */
        function guardRisk() {
            var hit = atRisk();
            if (!hit) return true;
            if (PRIM.now() - _lastRiskAt < 3000) return false;
            _lastRiskAt = PRIM.now();
            log('  ⚠ 命中风控关键词「' + hit + '」，等待人工完成（上限 ' + Math.round(riskWait / 1000) + 's）');
            PRIM.toastIn('检测到安全验证，请在手机上完成后脚本自动继续');
            var st = PRIM.now();
            while (PRIM.now() - st < riskWait) {
                if (!atRisk()) { log('  · 验证已通过，继续'); return true; }
                PRIM.sleepIn(1500);
            }
            log('  ✗ 验证未在时限内通过，停止本次流程');
            return false;
        }

        /** 系统定位/通知弹窗：只点「取消」类按钮，绝不代为授权 */
        function dismissDialogs() {
"""

    private val PART_2 = """
            var kws = ['定位服务未开启', '使用您的定位信息', '开启定位', '允许通知'];
            var present = false;
            for (var i = 0; i < kws.length; i++) {
                try { if (PRIM.textContains(kws[i]).exists()) { present = true; break; } } catch (e) { }
            }
            if (!present) return false;
            var labels = ['取消', '以后再说', '暂不', '不允许', '关闭'];
            for (var k = 0; k < labels.length; k++) {
                var n = null;
                try { n = PRIM.text(labels[k]).findOnce(); } catch (e) { n = null; }
                var b = n ? rectOf(n) : null;
                if (b && inScreen(b, H, W)) {
                    log('  · 关闭系统弹窗「' + labels[k] + '」');
                    PRIM.click(b.cx, b.cy);
                    PRIM.sleepIn(800);
                    return true;
                }
            }
            return false;
        }

        /** 危险落地页（结算/下单/支付/购物车）立即后退出，绝不点任何按钮 */
        function guardRisky() {
            if (!riskyRe) return false;
            for (var i = 0; i < 4; i++) {
                var a = PRIM.currentActivity();
                if (!riskyRe.test(a)) return i > 0;
                log('  · 安全阀：危险页面 ' + a + '，back 退出');
                PRIM.backIn();
                PRIM.sleepIn(1500);
            }
            return true;
        }

        /** 每个动作前的统一护栏。返回 false = 必须停手 */
        function guard() {
            if (overBudget()) return false;
            if (!guardRisk()) return false;
            dismissDialogs();
            guardRisky();
            return true;
        }

        /* ---------------- 控件树快照 ---------------- */

        /**
         * 一次遍历抓全屏节点。对应 jd_9.9包邮.js 的 snap()：
         * 单次抓取 + 1.2s 缓存，避免同一屏反复 find() 把单步耗时从 300ms 抬到 2s。
         */
        function takeSnap() {
            var out = [];

            function push(n, viaDesc) {
                var b = rectOf(n);
                if (!b) return null;
                var t = '', d = '';
                try { t = n.text() || ''; } catch (e) { }
                try { d = n.desc() || ''; } catch (e) { }
                if (!t && !d) return null;
                var ck = false;
                try { ck = !!n.clickable(); } catch (e) { }
                var it = {
                    node: n, t: t, d: d, clickable: ck, viaDesc: !!viaDesc,
                    left: b.left, top: b.top, right: b.right, bottom: b.bottom,
                    cx: b.cx, cy: b.cy, w: b.width, h: b.height
                };
                out.push(it);
                return it;
            }

            // ① 文本节点
            try {
                var arr = PRIM.textContains('').find();
                if (arr) { for (var i = 0; i < arr.size(); i++) push(arr.get(i), false); }
            } catch (e) { }

            // ② desc 节点单独抓一遍：京东大量图标只有 desc（「sign icon」「9.9包邮, 1元特价」）
            //    同一节点若已被文本遍历收录（t 非空），这里按坐标去重
            try {
                var arr2 = PRIM.descContains('').find();
                if (arr2) {
                    for (var j = 0; j < arr2.size(); j++) {
                        var n2 = arr2.get(j);
                        var b2 = rectOf(n2);
                        if (!b2) continue;
                        var d2 = '';
                        try { d2 = n2.desc() || ''; } catch (e) { }
                        if (!d2) continue;
                        var dup = false;
                        for (var k = 0; k < out.length; k++) {
                            if (out[k].left === b2.left && out[k].top === b2.top
                                && out[k].right === b2.right && out[k].bottom === b2.bottom) { dup = true; break; }
                        }
                        if (dup) continue;
                        push(n2, true);
                    }
                }
            } catch (e) { }

            return out;
        }

        function snap() {
            if (cache.nodes && (PRIM.now() - cache.at) < 1200 && !inOtherApp()) return cache.nodes;
            cache.nodes = takeSnap();
            cache.at = PRIM.now();
            return cache.nodes;
        }

        function invalidate() { cache.nodes = null; cache.at = 0; ocache.lines = null; ocache.at = 0; ocache.key = ''; }

        /* ---------------- 选择器 ---------------- */

        function matchStr(actual, sel) {
            if (sel.match === 'regex') {
                try { return new RegExp(sel.value, 'i').test(actual); } catch (e) { return false; }
            }
            if (sel.match === 'contains') return actual.indexOf(sel.value) >= 0;
            return actual === sel.value;
        }

        function passFilters(it, f) {
            if (!f) {
                if (forbid.length && inZones(it.cy, forbid)) return false;
                return true;
            }
            if (f.inScreen !== false && !inScreen({ left: it.left, top: it.top, right: it.right, bottom: it.bottom }, H, W)) return false;
            if (f.minSize) { if (it.w < f.minSize[0] || it.h < f.minSize[1]) return false; }
            if (f.topBand) { if (it.cy < f.topBand[0] || it.cy > f.topBand[1]) return false; }
            if (f.requireClickable && !it.clickable) return false;
            if (f.excludeZones && inZones(it.cy, f.excludeZones)) return false;
            if (forbid.length && inZones(it.cy, forbid)) return false;   // 全局禁点带优先
            return true;
        }

        /** 按 sel.pick 排序（first=上→下、center=最靠近屏幕中线、lowest=最靠下） */
        function sortPick(list, sel) {
            if (sel.pick === 'center') {
                list.sort(function (a, b) {
                    return Math.abs(a.cy - H / 2) - Math.abs(b.cy - H / 2);
                });
            } else if (sel.pick === 'lowest') {
                list.sort(function (a, b) { return b.cy - a.cy; });
            } else {
                list.sort(function (a, b) { return a.top - b.top || a.left - b.left; });
            }
            return list;
        }

        /** 按 selector 找全部候选；candidates 逐个尝试，命中即止（多用作文案兜底） */
        function findAll(sel) {
            if (!sel) return [];
            var nodes = snap();
            var vals = (sel.candidates && sel.candidates.length) ? sel.candidates
                : (sel.value != null ? [String(sel.value)] : []);
            if (sel.by === 'point') {
                return [{
                    node: null, t: '', d: '', clickable: true,
                    left: sel.point[0], top: sel.point[1],
                    right: sel.point[0] + 1, bottom: sel.point[1] + 1,
                    cx: sel.point[0], cy: sel.point[1], w: 1, h: 1
                }];
            }

            /**
             * 桌面小组件：不能走快照 + 通用过滤，必须**翻页找**。
             * 见 findDesktopWidget 的注释（真机实测：目标组件常在屏外，
             * left 远大于屏幕宽、宽度为负）。这里包成一个 node-like 对象，
             * 让 clickSel/tapNode 照常工作。
             */
            if (sel.by === 'desktopWidget') {
                var kws = (sel.candidates && sel.candidates.length) ? sel.candidates
                    : (sel.value != null ? [String(sel.value)] : []);
                var w = findDesktopWidget(kws, sel.maxPages || 6);
                if (!w) return [];
                return [{
                    node: null, t: '', d: w.label, clickable: true,
                    left: w.rect.left, top: w.rect.top, right: w.rect.right, bottom: w.rect.bottom,
                    cx: w.x, cy: w.y, w: w.rect.right - w.rect.left, h: w.rect.bottom - w.rect.top
                }];
            }
            /**
             * by:'ocr' —— 读屏幕上「没有无障碍文本」的区域。
             *
             * 真机实测（2026-11-23）两个都要记住，别只看一条：
             *   ① 13:40 首次进 BabelActivity，H5 还没渲染完，ui_dump 节点总数 = 0，
             *      OCR 能读到 50 行 →「无障碍全空、只能靠 OCR」；
             *   ② 13:56 同一页面渲染完成后，ui_dump 有 128 行、51 个文字节点，
             *      desc 里就是完整任务信息（如 `做任务解锁刮卡 逛精选活动 浏览6s 刮卡加1次`）。
             *
             * 由此得出两条硬约束（都是真机上踩出来的）：
             *   · OCR 不能替代无障碍 —— 同一份 IR 在「① 时刻」能跑，在「② 时刻」会点歪：
             *     「签到卡」OCR 命中左侧已刮过的装饰卡 @82,526，而真正的签到入口是
             *     无障碍 desc「今日已刮过签到卡，好运符」；「刮卡+1」OCR 命中的是任务卡上的
             *     说明文字 `80/5|浏览6s刮卡+1` @547,668，点它不会刮卡。
             *     → 所以 sel.by='ocr' 加了 a11yFirst：先用无障碍 desc/text 找同关键词，
             *       找到就用无障碍的坐标（更准、可点、带层级语义）。
             *   · OCR 只适合无障碍真的读不到的地方。默认 a11yFirst = true。
             */
            if (sel.by === 'ocr') {
                var ocrF = sel.filters || {};
                var ocrX = sel.ocr || {};

                /**
                 * 无障碍优先：用同一组关键词去无障碍 desc/text 里找。
                 * 关掉的条件只有「明确知道这段是 canvas 自绘、无障碍读不到」，
                 * 或者调试时想看 OCR 原始命中（ocr.a11yFirst = false）。
                 */
                if (ocrX.a11yFirst !== false) {
                    var a11yHits = [];
                    for (var av = 0; av < vals.length; av++) {
                        for (var ai = 0; ai < nodes.length; ai++) {
                            var an = nodes[ai];
                            if (!matchStr(an.d, { match: sel.match, value: vals[av] })
                                && !matchStr(an.t, { match: sel.match, value: vals[av] })) continue;
                            if (ocrX.requireClickable && !an.clickable) continue;
                            if (!passFilters(an, sel.filters)) continue;
                            var adup = false;
                            for (var ak = 0; ak < a11yHits.length; ak++) {
                                if (a11yHits[ak] === an) { adup = true; break; }
                            }
                            if (!adup) a11yHits.push(an);
                        }
                        if (a11yHits.length && sel.pick !== 'all') break;
                    }
                    if (a11yHits.length) {
                        // 打上 src 便于日志区分：到底走的是无障碍还是 OCR
                        for (var az = 0; az < a11yHits.length; az++) a11yHits[az].src = 'a11y';
                        return sortPick(a11yHits, sel);
                    }
                }
                ocrSubstrWarn(vals);
                var ocrHits = ocrFindAll(vals, {
                    band: ocrX.band || (ocrF.topBand ? [ocrF.topBand[0], ocrF.topBand[1]] : null),
                    match: sel.match || 'contains',
                    ttlMs: ocrX.ttlMs,
                    force: ocrX.force,
                    minY: ocrX.minY,
                    maxY: ocrX.maxY
                });
                var ocrOut = [];
                for (var oi = 0; oi < ocrHits.length; oi++) {
                    var lh = ocrHits[oi];
                    var hw = lh.right != null ? (lh.right - lh.left) : 120;
                    var hh = lh.bottom != null ? (lh.bottom - lh.top) : 40;
                    var cand = {
                        node: null, t: lh.t, d: '', clickable: true,
                        left: lh.left, top: lh.top,
                        right: lh.left + hw, bottom: lh.top + hh,
                        cx: lh.cx, cy: lh.cy, w: hw, h: hh, src: 'ocr'
                    };
                    // OCR 文字节点本身没有 clickable 属性，requireClickable 对 OCR 无意义，
                    // 这里只对「尺寸/屏内/禁点带」这类几何条件生效。
                    if (!passFilters(cand, sel.filters ? {
                        inScreen: sel.filters.inScreen !== false,
                        minSize: sel.filters.minSize,
                        topBand: sel.filters.topBand,
                        excludeZones: sel.filters.excludeZones
                    } : null)) continue;
                    ocrOut.push(cand);
                }
                return sortPick(ocrOut, sel);
            }

            var out = [];
            for (var v = 0; v < vals.length; v++) {
                for (var i = 0; i < nodes.length; i++) {
                    var it = nodes[i];
                    var hit = false;
                    if (sel.by === 'text') hit = matchStr(it.t, { match: sel.match, value: vals[v] });
                    else if (sel.by === 'desc') hit = matchStr(it.d, { match: sel.match, value: vals[v] });
                    else if (sel.by === 'desc-or-text') hit = matchStr(it.d, { match: sel.match, value: vals[v] }) || matchStr(it.t, { match: sel.match, value: vals[v] });
                    if (!hit) continue;
                    if (!passFilters(it, sel.filters)) continue;
                    var dup = false;
                    for (var k = 0; k < out.length; k++) if (out[k] === it) { dup = true; break; }
                    if (!dup) out.push(it);
                }
                if (out.length && sel.pick !== 'all') break;
            }
            return sortPick(out, sel);
        }

        function findOne(sel) { var a = findAll(sel); return a.length ? a[0] : null; }
        function exists(sel) { return !!findOne(sel); }

        /* ---------------- 点击 ---------------- */

        function tapPoint(x, y, label) {
            // 禁点带语义：只在「目标 App 自己的容器页」上防误点：
            // 京东首页/浮层的底部 tab 栏在 y≈2130~2400，误点会直接退出任务浮层。
            // 但落地页（H5/商品详情）的「立即返回」也在同一高度，那里必须能点，
            // 所以按当前页面类型放行 —— 无脑全局禁点会把所有落地页返回点死。
            if (inZones(y, forbid) && !isLandingPage()) {
                log('  ✗ 拒绝点击禁点带 y=' + y + '（' + label + '）');
                return false;
            }
            log('  · 点击「' + label + '」@(' + x + ',' + y + ')');
            PRIM.click(x, y);
            stat('clicks');
            invalidate();
            PRIM.sleepIn(cfg.afterTapMs || 900);
            return true;
        }

        /**
         * 落地页判定：京东活动/浏览任务的落地页都是 WebView 或商品详情，
         * 与「首页/浮层」（MainFrame / TTTMultiPage）区分为不同 activity。
         */
        function isLandingPage() {
            var a = PRIM.currentActivity();
            var p = PRIM.currentPackage();
            if (p && pkg && p !== pkg) return false;
            if (/MainFrameActivity|TTTMultiPage|SplashActivity/i.test(a)) return false;
            return /WebView|WebActivity|ProductDetail|Taro|BrowserActivity|TMall|H5/i.test(a);
        }

        function tapNode(it, label) {
            if (!it) return false;
            return tapPoint(it.cx, it.cy, label || it.t || it.d || '?');
        }

        /** 点击 + 验证 + 重试。坐标失效问题由内部重找解决，调用方不必管 */
        function clickSel(sel, opts) {
            opts = opts || {};
            var attempts = (opts.retry && opts.retry.attempts) || 1;
            var backoff = (opts.retry && opts.retry.backoffMs) || 800;
            for (var a = 0; a < attempts; a++) {
                if (!guard()) return { ok: false, reason: 'guard' };
                var it = findOne(sel);
                if (!it) {
                    log('  ! 第 ' + (a + 1) + ' 次未找到目标：' + JSON.stringify(sel.value || sel.candidates || sel.point));
                    PRIM.sleepIn(backoff);
                    continue;
                }
                var before = signature();
                if (!tapNode(it, opts.label || sel.value)) { PRIM.sleepIn(backoff); continue; }
                if (!opts.verify) return { ok: true, x: it.cx, y: it.cy, label: it.t || it.d };
                if (waitVerify(opts.verify, before)) return { ok: true, x: it.cx, y: it.cy, label: it.t || it.d };
                log('  ! 点击后未观察到预期变化，重试');
                invalidate();
                PRIM.sleepIn(backoff);
            }
            return { ok: false, reason: opts.retry && opts.retry.onFail === 'abort_flow' ? 'abort' : 'not_found' };
        }

        /* ---------------- 验证（四信号投票） ---------------- */

        function pageTitle() {
            var nodes = snap();
"""

    private val PART_3 = """
            var best = null;
            for (var i = 0; i < nodes.length; i++) {
                var it = nodes[i];
                if (!it.t) continue;
                if (it.cy > 320 || it.cy < 60) continue;
                if (!best || it.cy < best.cy) best = it;
            }
            return best ? best.t : '';
        }

        function signature() {
            return {
                act: PRIM.currentActivity(),
                title: pageTitle(),
                container: (cfg.containerSel ? exists(cfg.containerSel) : null),
                text: (cfg.anchorSel ? exists(cfg.anchorSel) : null),
                at: PRIM.now()
            };
        }

        function waitVerify(v, before) {
            var ms = v.timeoutMs || 4000;
            var st = PRIM.now();
            while (PRIM.now() - st < ms) {
                var sigs = v.signals || ['activityChanged'];
                for (var i = 0; i < sigs.length; i++) {
                    var s = sigs[i];
                    if (s === 'activityChanged' && before && PRIM.currentActivity() !== before.act) return true;
                    if (s === 'titleChanged' && before) {
                        var tt = pageTitle();
                        if (tt && tt !== before.title) return true;
                    }
                    if (s === 'containerGone' && cfg.containerSel) { invalidate(); if (!exists(cfg.containerSel)) return true; }
                    if (s === 'nodeGone' && v.expect) { invalidate(); if (!exists(v.expect)) return true; }
                    if (s === 'textAppeared' && v.expect) { invalidate(); if (exists(v.expect)) return true; }
                }
                PRIM.sleepIn(300);
            }
            return false;
        }

        /* ---------------- 任务行四元组绑定 ---------------- */

        /**
         * 从「按钮锚点」反推整行：标题/进度/副标题必须同横带且左对齐。
         * 抽象自 jd_9.9包邮.js parseTasks()：不做行结构校验时，会把浮层背后
         * 商品流里的频道入口错绑成任务行（实测「1元包邮」@189 两次误杀）。
         */
        function rowBind(anchor, rb) {
            rb = rb || {};
            var nodes = snap();
            var out = { title: '', sub: '', prog: null, bad: '' };
            var titleDy = rb.titleDy || [-25, 20];
            var subDy = rb.subDy || [20, 110];
            var xTol = (typeof rb.xTolerance === 'number') ? rb.xTolerance : 12;
            var titleCand = null, progCand = null, subPool = [];
            for (var i = 0; i < nodes.length; i++) {
                var it = nodes[i];
                if (it.clickable) continue;
                if (!it.t) continue;
                if (it.left < 150 || it.left > 790) continue;
                if (it.t === '(' || it.t === ')' || it.t === '+') continue;
                if (/^\d+${'$'}/.test(it.t)) continue;
                var d = it.top - anchor.top;
                if (d >= titleDy[0] && d <= titleDy[1]) {
                    var pm = /^[(（]?\s*(\d+)\s*\/\s*(\d+)\s*[）)]?${'$'}/.exec(it.t);
                    if (pm) { if (!progCand) progCand = { cur: parseInt(pm[1], 10), total: parseInt(pm[2], 10) }; continue; }
                    if (!titleCand || Math.abs(it.top - anchor.top) < Math.abs(titleCand.top - anchor.top)) titleCand = it;
                } else if (d > subDy[0] && d <= subDy[1]) {
                    subPool.push(it);
                }
            }
            var subCand = null;
            if (titleCand && subPool.length) {
                for (var s = 0; s < subPool.length; s++) {
                    if (Math.abs(subPool[s].left - titleCand.left) <= xTol) { subCand = subPool[s]; break; }
                }
            }
            out.title = titleCand ? titleCand.t : '';
            out.sub = subCand ? subCand.t : '';
            out.prog = progCand;
            if (!titleCand) out.bad = '无标题';
            else if (Math.abs(titleCand.top - anchor.top) > 25) out.bad = '标题偏移';
            else if (subCand && Math.abs(subCand.top - titleCand.top) < 15) out.bad = '副标题过近';
            else if (subCand && Math.abs(subCand.left - titleCand.left) > xTol) out.bad = '副标题未左对齐';
            return out;
        }

        /* ---------------- 探针 ---------------- */

        function quantKey(label, x, y) {
            return label + '|' + Math.round(x / 30) + '|' + Math.round(y / 30);
        }

        function probe(sel) {
            var list = findAll(sel);
            var out = [];
            var rb = sel.rowBind;
            var scan = sel.dwellScan;
            for (var i = 0; i < list.length; i++) {
                var it = list[i];
                var row = rb ? rowBind(it, rb) : { title: it.t, sub: it.d, prog: null, bad: '' };

                /**
                 * 停留秒数在「点击前」就要读到 —— 点完页面跳走，卡片上的小字就没了。
                 * 三个来源（按可靠性排序）：
                 *   ① rowBind 的副标题（任务行结构里最准）
                 *   ② dwellScan：卡片 bounds 附近所有文本里的「浏览N秒」
                 *      （jd_秒杀.js 的卡片 desc 只有「做任务解锁刮卡 任务名」，
                 *       秒数写在同一横带的副标题上，不扫描就永远读不到）
                 *   ③ 调用方默认值
                 */
                var dwell = parseDwellSec(row.sub, 0) || parseDwellSec(it.d, 0) || parseDwellSec(it.t, 0) || null;
                if (!dwell && scan) {
                    /**
                     * 只在「这张卡自己的范围」内扫 —— 轮播卡是并排的，
                     * 横向放宽到 400px 会把隔壁卡片的「浏览15秒」读进来，
                     * 结果所有任务的停留时长都变成同一个错误值（模拟器抓出来的真实 bug）。
                     * 默认横向范围 = [卡片左边缘, 卡片右边缘 + 20]。
                     */
                    var dy = scan.dyRange || [-30, 240];
                    var dx = scan.dxRange || [0, (it.w || 240) + 20];
                    var nodes = snap();
                    for (var s = 0; s < nodes.length; s++) {
                        var nb = nodes[s];
                        var relY = nb.top - it.top;
                        var relX = nb.left - it.left;
                        if (relY < dy[0] || relY > dy[1]) continue;
                        if (relX < dx[0] || relX > dx[1]) continue;
                        var v = parseDwellSec(nb.t, 0) || parseDwellSec(nb.d, 0);
                        if (v) { dwell = v; break; }
                    }
                }

                out.push({
                    key: quantKey(row.title || it.t || it.d, it.cx, it.cy),
                    label: row.title || it.t || it.d,
                    title: row.title, sub: row.sub, prog: row.prog, bad: row.bad,
                    dwellSec: dwell,
                    x: it.cx, y: it.cy, node: it.node, raw: it
                });
            }

            /**
             * skipKeywords 过滤：命中就剔除，**但记账**。
             *
             * 为什么不在 findAll 阶段过滤：那里还不知道 title，
             * 而黑名单是拿 title 匹配的（按钮文案可能都是「去完成」）。
             * 所以只能在这里、rowBind 之后过滤。
             */
            if (skipKws.length) {
                var kept = [];
                for (var q = 0; q < out.length; q++) {
                    var ttl = out[q].title || out[q].label || '';
                    var hitKw = null;
                    for (var k = 0; k < skipKws.length; k++) {
                        if (skipKws[k] && ttl.indexOf(skipKws[k]) >= 0) { hitKw = skipKws[k]; break; }
                    }
                    if (hitKw) {
                        stats.skipped++;
                        log('  ⤼ 跳过「' + ttl + '」（命中 skipKeywords: ' + hitKw + '，需人工操作）');
                    } else {
                        kept.push(out[q]);
                    }
                }
                out = kept;
            }
            return out;
        }

        /* ---------------- 等待 ---------------- */

        function waitFor(sel, ms, itv) {
            var st = PRIM.now();
            var step = itv || 500;
            do {
                if (!guardRisk()) return null;
                invalidate();
                var it = findOne(sel);
                if (it) return it;
                PRIM.sleepIn(step);
            } while (PRIM.now() - st < (ms || 10000));
            return null;
        }

        function waitGone(sel, ms) {
            var st = PRIM.now();
            do {
                invalidate();
                if (!exists(sel)) return true;
                PRIM.sleepIn(400);
            } while (PRIM.now() - st < (ms || 6000));
            return false;
        }

        /**
         * 从任务副标题里读停留秒数。
         * 来源优先级（jd_秒杀.js browseCard 实测）：
         *   ① 「全部任务」面板读到的时长表（最准，含「点击5个商品且浏览15秒」）
         *   ② 卡片 desc / 副标题里的「浏览N秒」
         *   ③ 调用方给的默认值（该脚本用 15 秒兜底）
         * 关键在于**必须在点击前读**——点完页面就跳走了，副标题也就没了。
         */
        function parseDwellSec(text, dflt) {
            // 数字直接返回：probe 已经把 dwellSec 算成 number，
            // 若这里再当字符串去 match「浏览N秒」，必然失配并退回默认值
            // （实测 bug：读到了 3，却全部按 15s 停留）。
            if (typeof text === 'number') return text > 0 ? text : (typeof dflt === 'number' ? dflt : 0);
            if (text) {
                var s = String(text);
                var m = s.match(/浏览\s*(\d+)\s*[秒sS]/);
                if (m) return parseInt(m[1], 10);
                m = s.match(/剩余\s*(\d+)\s*[秒sS]/);
                if (m) return parseInt(m[1], 10);
                m = s.match(/^(\d{1,3})\s*[秒sS]?${'$'}/);
                if (m) return parseInt(m[1], 10);
            }
            return (typeof dflt === 'number') ? dflt : 0;
        }

        /**
         * 把目标 App 拉回前台。
         * jd_秒杀.js 实测两条路：
         *   ① app.launch —— 保留任务栈，首选；
         *   ② 手势条横滑 —— 跨 App（跳到了别家的页面）时，launch 往往回到首页、
         *      把秒杀页的进度冲掉，只能从底部手势条横滑切回。
         * 顺序很重要：先 launch（便宜、无损），失败再横滑。
         */
        /**
         * goDesktop —— 回到桌面，并确认「真的停在桌面」而不是应用抽屉。
         *
         * 为什么需要它：jd_秒杀.js 的 findWidgetByDesc() 第一件事就是 home()，
         * 然后**检查是不是停在应用抽屉**（一堆应用图标、没有小组件），
         * 是的话 back 退出。原脚本注释写得很清楚：
         *   「home() 有时会停在应用抽屉（一堆应用图标、没有小组件），
         *     此时点坐标会点到别的 App」
         * IR 原先没有这个能力，于是入口节点直接在当前页面（可能是 AutoJs6、
         * 可能是京东）找桌面组件，必然 not_found（2026-11-23 真机实测两次）。
         *
         * 判据：isDesktop() 只看包名（launcher|home|桌面），
         *   应用抽屉的包名通常也是 launcher —— 所以还要看有没有小组件容器
         *   `com.android.launcher3.widget.CustomLauncherAppWidgetHostView`。
         */
        function goDesktop() {
            var WIDGET_CLS = 'com.android.launcher3.widget.CustomLauncherAppWidgetHostView';
            function hasWidget() {
                try { return PRIM.className(WIDGET_CLS).find().size() > 0; } catch (e) { return false; }
            }
            log('  · 回桌面找小组件');
            for (var attempt = 0; attempt < 3; attempt++) {
                try { PRIM.homeIn(); } catch (e) { }
                PRIM.sleepIn(1800);
                if (!isDesktop()) continue;
                if (hasWidget()) { invalidate(); return true; }
                // 像是应用抽屉：没有小组件容器 → back 退出抽屉
                log('  · 当前像是应用抽屉（无小组件），back 退出');
                try { PRIM.backIn(); } catch (e) { }
                PRIM.sleepIn(1600);
                if (hasWidget()) { invalidate(); return true; }
            }
            invalidate();
            var ok = isDesktop();
            log(ok ? '  · 已回桌面（但没看到小组件容器）' : '  · 回桌面失败（当前 ' + PRIM.currentPackage() + '）');
            return ok;
        }

        /**
         * findDesktopWidget —— 在桌面上翻页找小组件，返回 {x, y, label} 或 null。
         *
         * 为什么必须翻页：真机实测（2026-11-23 13:33，CPH2581 1080x2376）：
         *   第 1 页：[时钟] 在屏内（left=48 width=984），其余 6 个组件**全在屏外**
         *           [京东秒杀] left=3291 right=1080 宽=-2211  ← 负宽度
         *   第 2 页：[时钟] width=-49（已滑出），[每日领京豆] left=1129 仍在左侧外
         *   第 3 页：[每日领京豆] left=48 width=492 ← 终于进屏
         * 也就是：桌面有 5~6 屏，目标组件可能在任意一屏。
         * 原脚本 jd_秒杀.js:870-890 就是 `for(var p=0;p<6;p++)` + `swipe(900,1200,150,1200,300)` 翻页。
         *
         * 离屏判据（照抄原脚本的实测结论，不要用通用的 inScreen）：
         *   `b.left > device.width`  → 在右侧屏
         *   `b.right <= b.left`      → 宽度为负，被裁掉
         */
        function findDesktopWidget(kws, maxPages) {
            maxPages = maxPages || 6;
            var CLS = 'com.android.launcher3.widget.CustomLauncherAppWidgetHostView';
            var wanted = [];
            if (isArr(kws)) wanted = kws; else if (kws) wanted = [String(kws)];

            function scanOnce() {
                var arr = null;
                try { arr = PRIM.className(CLS).find(); } catch (e) { return null; }
                var n = 0;
                try { n = arr.size(); } catch (e) { return null; }
                for (var i = 0; i < n; i++) {
                    var it = null, b = null, d = '';
                    try { it = arr.get(i); } catch (e) { continue; }
                    try { b = it.bounds(); } catch (e) { b = null; }
                    try { d = it.desc() || ''; } catch (e) { d = ''; }
                    if (!b) continue;
                    if (b.left > W) continue;            // 在右侧屏
                    if (b.right <= b.left) continue;     // 宽度为负（被裁）
                    for (var k = 0; k < wanted.length; k++) {
                        if (d.indexOf(wanted[k]) >= 0) {
                            return { x: Math.round((b.left + b.right) / 2), y: Math.round((b.top + b.bottom) / 2), label: d, rect: b };
                        }
                    }
                }
                return null;
            }

            for (var p = 0; p < maxPages; p++) {
                var hit = scanOnce();
                if (hit) {
                    if (p > 0) log('  · 翻到第 ' + (p + 1) + ' 屏找到「' + hit.label + '」');
                    return hit;
                }
                log('  · 第 ' + (p + 1) + ' 屏没有，右滑翻页');
                try { PRIM.swipeIn(Math.round(W * 0.83), Math.round(H * 0.5), Math.round(W * 0.14), Math.round(H * 0.5), 300); } catch (e) { }
                PRIM.sleepIn(1300);
                invalidate();
            }
            return null;
        }

        function recoverForeground(opts) {
            opts = opts || {};
            if (isFg()) return true;
            var strategies = opts.strategies || ['launch', 'gestureBar'];
            for (var i = 0; i < strategies.length; i++) {
                var s = strategies[i];
                if (s === 'launch') {
                    log('  · 目标 App 不在前台（' + PRIM.currentPackage() + '），先 launch 拉回');
                    PRIM.launchApp(pkg);
                    var st = PRIM.now();
                    while (PRIM.now() - st < (opts.waitMs || 8000)) {
                        if (isFg()) { invalidate(); return true; }
                        PRIM.sleepIn(400);
                    }
                } else if (s === 'gestureBar') {
                    // 底部手势条横滑切回上一个 App。y 取 screenH-10（手势条本身），
                    // 让系统识别为「横滑切换」而不是页面内滑动。
                    log('  · launch 未拉回，改用手势条横滑切回');
                    var y = H - 10;
                    for (var k = 0; k < 3; k++) {
                        try { PRIM.swipeIn(Math.round(W * 0.3), y, Math.round(W * 0.7), y, 110); } catch (e) { }
                        PRIM.sleepIn(1200);
                        if (isFg()) { invalidate(); log('  · 已切回目标 App'); return true; }
                    }
                }
            }
            log('  ! 未能拉回目标 App');
"""

    private val PART_4 = """
            return false;
        }

        /* ---------------- 其它动作 ---------------- */

        function swipeByRatio(r, ms) {
            var x1 = Math.round(W * r[0]), y1 = Math.round(H * r[1]);
            var x2 = Math.round(W * r[2]), y2 = Math.round(H * r[3]);
            log('  · 滑动 (' + x1 + ',' + y1 + ') → (' + x2 + ',' + y2 + ')');
            try { PRIM.swipeIn(x1, y1, x2, y2, ms || 500); } catch (e) { log('  ! 滑动失败 ' + e); }
            stat('scrolls');
            invalidate();
            PRIM.sleepIn(cfg.afterSwipeMs || 1100);
        }

        function inputText(sel, text) {
            var it = findOne(sel);
            if (!it) return false;
            tapNode(it, '输入框');
            PRIM.sleepIn(400);
            try { PRIM.inputIn(String(text)); } catch (e) {
                try { PRIM.setTextIn(0, String(text)); } catch (e2) { return false; }
            }
            PRIM.sleepIn(300);
            return true;
        }

        function longPress(sel, ms) {
            var it = findOne(sel);
            if (!it) return false;
            try { PRIM.longClick(it.cx, it.cy); } catch (e) { return false; }
            invalidate();
            PRIM.sleepIn(ms || 800);
            return true;
        }

        function back() {
            log('  · back');
            try { PRIM.backIn(); } catch (e) { }
            invalidate();
            PRIM.sleepIn(900);
        }

        /**
         * backToBean —— 不管落在哪个页面，都把「京豆任务页」弄回来。
         *
         * 真机事故（2026-11-23 14:33）：点「去浏览」后落到
         * `com.jd.lib.productdetail.ProductDetailActivity`（裸商品详情页），
         * 那里**既没有「立即返回」按钮、也没有任务倒计时**，只有商品内容。
         * waitCountdown 找不到返回按钮就只记一句日志，于是流程卡在商品页，
         * 后续每轮都「找不到全部任务」直到 idle 退出。
         *
         * 原脚本 jd_秒杀.js 的 doLandingStay 结尾有兜底：
         *   没有「立即返回」→ back() → 若弹挽留弹窗（确定离开/残忍离开）再点一次。
         * 这里把同一套逻辑收进运行时，并用 isBeanPage 判定收工。
         *
         * 判定「已经是京豆页就不 back」：照抄原脚本 safeBack 的教训 ——
         * 在天铁领豆页 back 会直接退出任务浮层（用户明确要求过不要 back）。
         */
        function isBeanPage() {
            // 先看无障碍（快），读不到再看 OCR
            var kws = ['全部任务', '做任务解锁刮卡', '立即刮卡', '天天领豆'];
            for (var i = 0; i < kws.length; i++) {
                try {
                    if (PRIM.descContains(kws[i]).find().size() > 0) return true;
                } catch (e) { }
                try {
                    if (PRIM.textContains(kws[i]).find().size() > 0) return true;
                } catch (e) { }
            }
            return ocrFindAll(kws, { force: true, match: 'contains' }).length > 0;
        }

        /** 挽留弹窗：京东在 back 之后会弹「确定离开/残忍离开/继续浏览」 */
        function dismissLeaveDialog() {
            var kws = ['确定离开', '残忍离开', '放弃福利', '继续浏览', '再逛逛'];
            for (var i = 0; i < kws.length; i++) {
                var n = null, b = null;
                try { n = PRIM.text(kws[i]).findOnce(); } catch (e) { n = null; }
                if (!n) { try { n = PRIM.desc(kws[i]).findOnce(); } catch (e) { n = null; } }
                if (n) { try { b = n.bounds(); } catch (e) { b = null; } }
                if (b && inScreen(b, H, W)) {
                    log('  · 关闭挽留弹窗「' + kws[i] + '」');
                    PRIM.click(Math.round((b.left + b.right) / 2), Math.round((b.top + b.bottom) / 2));
                    PRIM.sleepIn(1200);
                    invalidate();
                    return true;
                }
            }
            return false;
        }

        /**
         * 回到京豆页。返回 true=已在京豆页。
         * 顺序（每一步都先判「是不是已经在京豆页了」）：
         *   ① 点「立即返回」（任务落地页专用按钮）
         *   ② back（商品详情/普通 H5）
         *   ③ dismissLeaveDialog（京东的挽留弹窗，挡住 back 时）
         *   ④ recoverForeground（跳到了别家 App）
         */
        function backToBean(opts) {
            opts = opts || {};
            var tries = opts.tries || 4;
            if (isBeanPage()) { log('  · 已在京豆页，无需返回'); return true; }
            for (var t = 0; t < tries; t++) {
                /**
                 * ⚠ 先判「有没有退出京东」。
                 *
                 * 真机实测（2026-11-23 14:41）：商品详情页按 back **会直接退出京东**，
                 * 落到微信（com.tencent.mm）。此时再按 back 毫无意义 ——
                 * 那是在别人的 App 里后退，只会把用户越带越远。
                 * 正确做法是立刻 launch 把京东拉回来，而不是继续 back。
                 *
                 * 这也是 14:41:34 那条「没能回到京豆页（当前 BabelActivity）」
                 * 的成因：back 已经把京东切走，recover 又把它拉回来，
                 * 拉回来时页面恰好是 BabelActivity，但等到循环退出时才判，已经晚了。
                 */
                if (!isFg()) {
                    log('  · [回任务页] 已离开京东（当前 ' + PRIM.currentPackage() + '），先 launch 拉回');
                    if (recoverForeground({ strategies: ['launch', 'gestureBar'], waitMs: 8000 })) {
                        // launch 回来通常还停在离开时那一页，需要再判一次
                        var st0 = PRIM.now();
                        while (PRIM.now() - st0 < 6000) {
                            PRIM.sleepIn(700);
                            if (isBeanPage()) { log('  · ✓ 拉回后已在京豆页'); return true; }
                        }
                    }
                    continue;
                }
                // ① 「立即返回」：先无障碍，再 OCR
                var rb = null;
                try {
                    var n = PRIM.textContains('立即返回').findOnce();
                    if (n) { var b = n.bounds(); if (b && inScreen(b, H, W)) rb = b; }
                } catch (e) { rb = null; }
                if (!rb) {
                    var hit = ocrFindAll(['立即返回'], { force: true, match: 'contains' })[0];
                    if (hit) rb = { left: hit.left, top: hit.top, right: hit.right, bottom: hit.bottom };
                }
                if (rb) {
                    var cx = Math.round((rb.left + rb.right) / 2);
                    var cy = Math.round((rb.top + rb.bottom) / 2);
                    log('  · [回任务页] 点「立即返回」@(' + cx + ',' + cy + ')');
                    if (!tapPoint(cx, cy, '立即返回')) { PRIM.sleepIn(800); continue; }
                } else {
                    /**
                     * ② back
                     *
                     * ⚠ back 间隔必须短（默认 1.8s），且**不要先等一轮再 back**。
                     *
                     * 真机对照实测（2026-11-23 16:29~16:30，同机同页，两组独立实验）：
                     *   · 间隔 1.8s back 两次 → 第 2 次就到 BabelActivity（两次均成功）
                     *   · 间隔 6.0s back 四次 → **四次都停在小程序里**（IR 的 backToBean 实测）
                     *
                     * 原因是小程序容器（MantoActivityUp1）的落地页会自己重置状态：
                     * 等 6 秒再 back，等于每次都回到「刚进入」的样子，永远退不出。
                     * 这是「等太久反而退不出」，不是超时不够 —— 别去调大 waitMs。
                     */
                    var gap = opts.backGapMs || 1800;
                    log('  · [回任务页] 没看到「立即返回」，直接 back 第 ' + (t + 1) + ' 次');
                    try { PRIM.backIn(); } catch (e) { }
                    invalidate();
                    /**
                     * back 之后就在 gap 内复查，**不要额外再加等待**。
                     *
                     * 实测有效的节奏是「back → 约 1.8s → back」（2 次就出去）。
                     * 早期写成 back → sleep(gap) → 再复查 1.5s，实际间隔变成 3.3s，
                     * 加上 waitMs 剩下的时间甚至到 8s —— 全都不行。
                     * 所以复查必须**嵌在 gap 里**：总间隔 = gap，不多不少。
                     */
                    var gst = PRIM.now();
                    var got = false;
                    while (PRIM.now() - gst < gap) {
                        PRIM.sleepIn(500);
                        /**
                         * 命中后必须 invalidate()。
                         *
                         * 真机 / 模拟器都踩到过：页面刚从落地页回到京豆页时，
                         * 1.2s 的节点快照缓存里还是**落地页**的内容。
                         * 这里若不清缓存，紧接着的判据（「回来看到立即刮卡吗」）
                         * 读到的仍是旧帧 → 把已成功的任务判成「未计入」。
                         */
                        if (isBeanPage()) { got = true; break; }
                    }
                    if (got) { invalidate(); log('  · ✓ 已回到京豆页'); return true; }
                    // ③ 挽留弹窗
                    dismissLeaveDialog();
                    continue;
                }
                /**
                 * 每次动作后给页面时间渲染 —— 但**复查窗口要短**。
                 *
                 * 早期这里写成「等满 waitMs 再去下一轮」，于是 back 的实际间隔
                 * 就等于 waitMs（6s），正好踩中上面那个「等太久退不出」的坑：
                 * 真机实测 back 间隔 6s × 5 次全部失败，间隔 1.8s × 2 次就成功。
                 * 现在：这一轮只复查 1.5s（够 Babel 渲染），不回去就**立刻进下一轮 back**。
                 * waitMs 只作为「单轮最多等多久」的上限，不再决定 back 节奏。
                 */
                var st = PRIM.now();
                var probeUntil = st + 1500;
                while (PRIM.now() < probeUntil) {
                    PRIM.sleepIn(500);
                    /**
                     * 命中后必须 invalidate()。
                     *
                     * 真机 / 模拟器都踩到过：页面刚从落地页回到京豆页时，
                     * 1.2s 的节点快照缓存里还是**落地页**的内容。
                     * 这里若不清缓存，紧接着的判据（「回来看到立即刮卡吗」）
                     * 读到的仍是旧帧 → 把已成功的任务判成「未计入」，
                     * 白跑一轮还记一条 ✗（真机 15:02 就是这么误报的）。
                     */
                    if (isBeanPage()) {
                        invalidate();
                        log('  · ✓ 已回到京豆页');
                        return true;
                    }
                }
            }
            // 兜底：可能 bp 后又跑偏了
            if (!isFg() && recoverForeground({ waitMs: 6000 }) && isBeanPage()) {
                log('  · ✓ 最终 recover 后已在京豆页');
                return true;
            }
            log('  ! 没能回到京豆页（当前 ' + PRIM.currentActivity() + '）');
            return false;
        }

        /* ---------------- 点「并排商品」（点2个商品 类任务） ---------------- */

        /**
         * findProductCards —— 在视口下方找「并排的商品卡」。
         *
         * 为什么要有这个能力（用户 2026-11-23 口述，真机 15:33~15:34 实测通过）：
         *   「天天刮京豆」页有一个「点2个商品 领3京豆」的任务。做法**不是**点任务卡，
         *   而是直接点页面下方的商品：视口底部并排两张商品图，点左、回来、点右、
         *   回来，任务卡上就出现「点击领取3京豆」，点它才入账。
         *   用户原话：「不用找任务卡，直接点两个商品，视口下方就是并排的两个商品，
         *   点左边再点右边，再找页面上有没有领取的文案，点击就可以领取了。」
         *
         * 判据全部来自真机 ui_dump（1080x2376）：
         *   左商品 clickable bounds [27,1626][534,2328] center=(280,1977)
         *   右商品 clickable bounds [546,1626][1053,2328] center=(799,1977)
         *
         *   · clickable = true    —— 商品卡整块可点
         *   · t / d 都为空       —— 商品卡在这页**没有文字**；有文字的是标题/价格，
         *                           点标题不保证跳转（真机点空过）
         *   · 宽 > 屏宽 30%       —— 并排两卡各约一半宽；滤掉图标、小按钮
         *   · 高 > 屏高 12%       —— 滤掉细长条（tab 栏、分隔线）
         *   · 包含关系去重        —— 父容器与子容器都会 clickable，不去重会点两次同一张
         *
         * 只走无障碍：OCR 读不到「无文字的图片」，所以无障碍全空时这个任务做不了。
         * 这不是缺陷，是页面的物理事实 —— 日志里会如实写「只找到 0 张」。
         */
        function findProductCards(o) {
            o = o || {};
            var band = o.band || null;
            if (!band && o.bandRatio) {
                band = [Math.round(H * o.bandRatio[0]), Math.round(H * o.bandRatio[1])];
            }
            if (!band) band = [Math.round(H * 0.62), H];
            var minW = o.minWidth || Math.round(W * 0.3);
            var minH = o.minHeight || Math.round(H * 0.12);
            var limit = o.limit || 4;

            /**
             * 数据源：全树遍历，不是 snap()。
             * snap() 只留「有文字」的节点，而商品卡是**纯图片**（text/desc 全空），
             * 用 snap() 永远拿到 0 张 —— 模拟器第一次跑就暴露了这一点
             * （日志「只找到 0 张」而画面明明有两张卡）。
             */
            var raw = PRIM.allNodes();
            var out = [];
            var i;
            if (raw && raw.size) {
                for (i = 0; i < raw.size(); i++) {
                    var n = raw.get(i);
                    var ck = false;
                    try { ck = !!n.clickable(); } catch (e) { ck = false; }
                    if (!ck) continue;
                    var b = rectOf(n);
                    if (!b) continue;
                    var t = '', d = '';
                    try { t = n.text() || ''; } catch (e) { t = ''; }
                    try { d = n.desc() || ''; } catch (e) { d = ''; }
                    if (t || d) continue;
                    if (b.cy < band[0] || b.cy > band[1]) continue;
                    if (b.width < minW || b.height < minH) continue;
                    if (forbid.length && inZones(b.cy, forbid)) continue;
                    out.push({
                        node: n, t: '', d: '', clickable: true, src: 'a11y',
                        left: b.left, top: b.top, right: b.right, bottom: b.bottom,
                        cx: b.cx, cy: b.cy, w: b.width, h: b.height
                    });
                }
            } else {
                /** 兜底：容器不给 className 时退回快照（至少不会崩） */
                log('  ! 全树遍历不可用（className.find 返回空），退回快照找商品卡');
                var nodes = snap();
                for (i = 0; i < nodes.length; i++) {
                    var it = nodes[i];
                    if (!it.clickable || it.t || it.d) continue;
                    if (it.cy < band[0] || it.cy > band[1]) continue;
                    if (it.w < minW || it.h < minH) continue;
                    if (forbid.length && inZones(it.cy, forbid)) continue;
                    out.push(it);
                }
            }

            /** 包含关系去重：父子容器都会 clickable，不去重会把同一张卡当两张点两次 */
            var keep = [];
            for (i = 0; i < out.length; i++) {
                var cur = out[i];
                var swallowed = false;
                for (var k = 0; k < out.length; k++) {
                    if (k === i) continue;
                    var oth = out[k];
                    var curInOth = cur.left >= oth.left - 8 && cur.top >= oth.top - 8
                        && cur.right <= oth.right + 8 && cur.bottom <= oth.bottom + 8;
                    if (!curInOth) continue;
                    /** 完全重合时只留一个（用索引次序决定，保证确定性） */
                    if (cur.left === oth.left && cur.top === oth.top
                        && cur.right === oth.right && cur.bottom === oth.bottom) {
                        if (k < i) { swallowed = true; break; }
                        continue;
                    }
                    swallowed = true;
                    break;
                }
                if (!swallowed) keep.push(cur);
            }
            out = keep;

            out.sort(function (a, b) { return a.left - b.left; });
            if (out.length > limit) out = out.slice(0, limit);
            return out;
        }

        /**
         * tapProducts —— 「点 N 个并排商品 → 逐个回到京豆页 → 点领取」。
         *
         * 返回 { ok, tapped, claimed, why }。只有**领到**才算 ok：
         *   点了商品但没出现「已领取」时不记 acted，否则 whileBudget 会一直认为
         *   「这轮有推进」，变成死循环点商品（真机上表现为白点几十次）。
         *
         * 不滑动页面（用户明确要求）。找不到商品卡就直接返回，交给 IR 的 onFail。
         */
        function tapProducts(o) {
            o = o || {};
            var res = { ok: false, tapped: 0, claimed: false, why: '' };
            var need = (typeof o.count === 'number' && o.count > 0) ? o.count : 2;
"""

    private val PART_5 = """
            var cards = findProductCards(o);

            if (cards.length < need) {
                res.why = '视口下方只找到 ' + cards.length + ' 张并排商品卡（需要 ' + need + ' 张）';
                log('  · ' + res.why);
                if (cards.length) {
                    var brief = [];
                    for (var b = 0; b < cards.length; b++) {
                        brief.push('@(' + cards[b].cx + ',' + cards[b].cy + ')'
                            + cards[b].w + 'x' + cards[b].h);
                    }
                    log('  · 候选商品卡：' + brief.join(' / '));
                }
                return res;
            }

            var afterTap = o.afterTapMs || ((cfg.afterTapMs || 900) + 1600);
            var backWait = o.backWaitMs || 5000;

            for (var c = 0; c < need; c++) {
                if (!guard()) { res.why = '护栏拦下（风控/超时）'; break; }
                var card = cards[c];
                log('  · 点第 ' + (c + 1) + ' 张商品卡 @(' + card.cx + ',' + card.cy + ')');
                if (!tapPoint(card.cx, card.cy, '商品' + (c + 1))) {
                    res.why = '第 ' + (c + 1) + ' 张商品卡点击失败';
                    break;
                }
                PRIM.sleepIn(afterTap);
                if (!isBeanPage()) {
                    log('  · 跳到了 ' + PRIM.currentActivity() + '，back 回京豆页');
                    try { PRIM.backIn(); } catch (e) { }
                    invalidate();
                    /**
                     * back 之后**不能只等**：真机上 back 有时被京东的挽留弹窗吃掉，
                     * 有时商品页是「详情页套详情页」需要退两层。
                     * 所以这里每 1.5s 复查一次，依次尝试：关弹窗 → 离开京东就 launch →
                     * 还在京东就再 back（上限 3 次）。全程用 isBeanPage 判定，不靠 activity 名。
                     */
                    var backs = 1, lastAt = PRIM.now(), st = PRIM.now(), backOk = false;
                    var gapBack = o.backGapMs || 1800;
                    while (PRIM.now() - st < backWait) {
                        PRIM.sleepIn(500);
                        if (isBeanPage()) { backOk = true; break; }
                        if (PRIM.now() - lastAt < gapBack) continue;
                        lastAt = PRIM.now();
                        if (dismissLeaveDialog()) { st = PRIM.now(); continue; }
                        if (!isFg()) {
                            log('  · 已离开京东（' + PRIM.currentPackage() + '），launch 拉回');
                            recoverForeground({ waitMs: 4000 });
                            st = PRIM.now();
                            continue;
                        }
                        if (backs < 4) {
                            log('  · back 后还没回京豆页，再 back 一次（第 ' + (backs + 1) + ' 次）');
                            try { PRIM.backIn(); } catch (e) { }
                            invalidate();
                            backs++;
                            lastAt = PRIM.now();
                        }
                    }
                    if (!backOk) {
                        res.why = '第 ' + (c + 1) + ' 个商品 back 后没回到京豆页（'
                            + PRIM.currentActivity() + '）';
                        log('  ! ' + res.why);
                        break;
                    }
                    log('  · ✓ 已回到京豆页');
                }
                res.tapped++;
            }

            if (res.tapped < need) {
                if (!res.why) res.why = '只点了 ' + res.tapped + '/' + need + ' 张商品';
                return res;
            }

            /**
             * 领取文案。默认**不含**裸「领取」：`contains('领取')` 会命中「已领取奖励」，
             * 于是每轮都去点一个已经领完的按钮（这条坑在 ocrSubstrWarn 里写过同类事故）。
             */
            var claimKws = o.claimKeywords || ['点击领取', '立即领取', '去领取'];
            var doneKws = o.doneKeywords || ['已领取'];
            var tries = o.claimTries || 3;

            for (var t = 0; t < tries; t++) {
                if (t) PRIM.sleepIn(o.claimGapMs || 2000);
                if (!guard()) break;
                var hits = ocrFindAll(claimKws, { force: true, match: 'contains' });
                var pick = null;
                for (var h = 0; h < hits.length; h++) {
                    var line = hits[h];
                    var isDone = false;
                    for (var d = 0; d < doneKws.length; d++) {
                        if (line.t.indexOf(doneKws[d]) >= 0) { isDone = true; break; }
                    }
                    if (isDone) continue;
                    if (!inScreen({ left: line.left, top: line.top, right: line.right, bottom: line.bottom }, H, W)) continue;
                    pick = line;
                    break;
                }
                if (!pick) {
                    log('  · 第 ' + (t + 1) + ' 次没看到可点的领取文案（' + claimKws.join('/') + '）');
                    continue;
                }
                log('  · 看到领取文案「' + pick.t + '」@(' + pick.cx + ',' + pick.cy + ')，点击');
                if (!tapPoint(pick.cx, pick.cy, '领取')) continue;
                PRIM.sleepIn(o.claimWaitMs || 2500);
                invalidate();
                var done = ocrFindAll(doneKws, { force: true, match: 'contains' });
                if (done.length) {
                    res.claimed = true;
                    res.ok = true;
                    log('  · ✓ 领取成功（页面已显示「' + done[0].t + '」）');
                    break;
                }
                log('  · 点了领取但没出现「' + doneKws.join('/') + '」，再试一次');
            }

            if (!res.ok) {
                res.why = res.why || ('点了 ' + res.tapped + ' 张商品，但没领到（没出现已领取文案）');
            }
            return res;
        }

        /* ---------------- OCR 兜底 ---------------- */

        function ocrLines(band) {
            var img = null, out = [];
            try {
                img = PRIM.captureScreen();
                if (!img) return [];
                var src = img;
                if (band) {
                    src = PRIM.clip(img, 0, band[0], W, band[1] - band[0]) || img;
                }
                var rs = PRIM.ocrDetect(src) || [];
                var dy = band ? band[0] : 0;
                for (var i = 0; i < rs.length; i++) {
                    var b = rs[i].bounds;
                    if (!b) continue;
                    var t = (rs[i].text || '').replace(/\s/g, '');
                    if (!t) continue;
                    out.push({
                        t: t,
                        left: b.left, top: b.top + dy,
                        right: b.right, bottom: b.bottom + dy,
                        w: b.right - b.left, h: b.bottom - b.top,
                        cx: Math.round((b.left + b.right) / 2),
                        cy: Math.round((b.top + b.bottom) / 2) + dy
                    });
                }
                if (band && src && src !== img) PRIM.recycle(src);
            } catch (e) { log('  ! OCR 异常 ' + e); }
            PRIM.recycle(img);
            return out;
        }

        function ocrFind(kws, band) {
            var list = ocrLines(band);
            var arr = isArr(kws) ? kws : [kws];
            for (var i = 0; i < list.length; i++) {
                for (var k = 0; k < arr.length; k++) {
                    if (list[i].t.indexOf(arr[k]) >= 0) return list[i];
                }
            }
            return null;
        }

        /**
         * OCR 快照：同一帧（同 band、未失效）只截一次屏。
         *
         * ttlMs 默认 1500 —— 照抄原脚本 ocrItems() 的缓存窗口。
         * 为什么要给窗口而不是「一直用到失效」：
         *   浮层常有自动播放的动画、倒计时、轮播，画面自己就会变；
         *   1.5s 是原脚本在「天天刮京豆」这类页面调出来的经验值，
         *   再长就会出现「按旧坐标点击，点歪」。
         *
         * force=true 时**强制重截**（倒计时类场景必须绕过缓存，
         * 否则读数滞后会把归零时刻整体推后 —— 这条经验写在 scanLanding 里）。
         */
        function ocrSnap(o) {
            o = o || {};
            var band = o.band || null;
            var ttl = (typeof o.ttlMs === 'number') ? o.ttlMs : 1500;
            if (o.force) { ocache.lines = null; ocache.at = 0; ocache.key = ''; }
            var key = band ? (band[0] + ':' + band[1]) : 'full';
            if (ocache.lines && ocache.key === key && (PRIM.now() - ocache.at) < ttl) {
                return ocache.lines;
            }
            var lines = ocrLines(band);
            ocache.lines = lines;
            ocache.at = PRIM.now();
            ocache.key = key;
            return lines;
        }

        /**
         * 按 OCR 找全部命中行（同一帧内可拿到多个目标）。
         *
         * 为什么要「全部」而不是「第一个」：
         *   京豆任务页一屏上有「签到卡」「刮卡+1」「每日奖励」「开心收下」多个按钮，
         *   需要按坐标排序后逐个处理；只取第一个会漏掉其余（原脚本 ocrItems()
         *   就是返回数组再逐个 filter 的）。
         *
         * opts: { band, match: 'contains'|'eq'|'regex', ttlMs, force, maxY, minY }
         *
         * 子串撞车记录：同一关键词只喊一次，避免每轮刷屏。
         *
         * 必须声明在 ocrFindAll **之前**。早期写在它下面（函数声明会提升、
         * 但 `var _substrWarned = {}` 的赋值不会），于是第一次调用时它是 undefined，
         * `_substrWarned[...] = true` 直接抛 TypeError ——
         * 而 ocrFindAll 的调用方包着 try/catch，异常被吞掉，
         * 表现为「OCR 明明读到那行字，命中数却是 0」。
         * 这个坑在写测试时立刻暴露了（[4d] 命中 0 条）。
         */
        var _substrWarned = {};

        function ocrFindAll(kws, o) {
            o = o || {};
            var arr = isArr(kws) ? kws : (kws == null ? [] : [kws]);
            var list = ocrSnap(o);
            var match = o.match || 'contains';
            var out = [];
            for (var i = 0; i < list.length; i++) {
                var line = list[i];
                if (o.minY != null && line.cy < o.minY) continue;
                if (o.maxY != null && line.cy > o.maxY) continue;
                var hit = false;
                for (var k = 0; k < arr.length && !hit; k++) {
                    var kw = String(arr[k]);
                    if (match === 'eq') hit = (line.t === kw);
                    else if (match === 'regex') {
                        try { hit = new RegExp(kw, 'i').test(line.t); } catch (e) { hit = false; }
                    } else hit = (line.t.indexOf(kw) >= 0);
                }
                if (!hit) continue;
                var dup = false;
                for (var d = 0; d < out.length; d++) {
                    if (out[d] === line) { dup = true; break; }
                }
                if (!dup) out.push(line);
            }

            /**
             * 子串撞车告警（真机事故 2026-11-23）。
             *
             * 京豆页中心按钮只有一行字，两态互斥：
             *   「立即刮卡」      → 有卡
             *   「做任务解锁刮卡」→ 没卡
             * 但 `contains('立即刮卡')` **会命中「做任务解锁刮卡」**（后者包含前者）。
             * 后果：明明没卡，`K.exists()` 却返回 true，于是每轮都去点一个
             * 已经不存在的「立即刮卡」，整条「能刮就刮」的分支从未真正执行 ——
             * 而且日志看起来完全正常（「点击「立即刮卡」」）。
             *
             * 这类错误靠读 IR 发现不了，只能运行时把「关键词互为子串」这件事喊出来。
             * 不自动改成 eq：那会让「我要找包含这个字的整行」的场景静默失效。
             */
            if (match === 'contains' && !o._noWarn) {
                for (var w = 0; w < arr.length; w++) {
                    for (var v = 0; v < list.length; v++) {
                        var t = list[v].t;
                        if (t === String(arr[w])) continue;           // 精确相同不算撞车
                        if (t.indexOf(String(arr[w])) >= 0) {
                            _substrWarned[String(arr[w])] = true;
                        }
                    }
                }
            }
            // 稳定排序：同一横带（±30px）内按左→右，否则按上→下。
            // 任务页的按钮常常一行多个，纯按 top 排会把同一行的顺序打乱。
            out.sort(function (a, b) {
                if (Math.abs(a.cy - b.cy) > 30) return a.cy - b.cy;
                return a.left - b.left;
            });
            return out;
        }

        /**
         * 子串撞车日志出口：由调用方在拿到结果后调用（同一关键词只喊一次）。
         */
        function ocrSubstrWarn(kws) {
            var arr = isArr(kws) ? kws : [kws];
            for (var i = 0; i < arr.length; i++) {
                if (_substrWarned[String(arr[i])]) {
                    log('  ⚠ OCR「' + arr[i] + '」是更长文案的子串：contains 会误命中，'
                        + '这类互斥文案请把 match 改成 "eq"');
                    delete _substrWarned[String(arr[i])];
                }
            }
        }

        /** 按 OCR 点击。命中即点，未命中返回 null（调用方决定记失败还是走 onFail） */
        function ocrClick(kws, o) {
            o = o || {};
            var hit = o.all ? ocrFindAll(kws, o)[0] : (function () {
                var all = ocrFindAll(kws, o);
                return all.length ? all[0] : null;
            })();
            if (!hit) {
                log('  ! OCR 未命中「' + (isArr(kws) ? kws.join('/') : kws) + '」');
                return null;
            }
            var label = hit.t + '@ocr';
            if (!tapPoint(hit.cx, hit.cy, label)) return null;
            return hit;
        }

        /** 按 OCR 判断存在（复用缓存；不点击） */
        function ocrExists(kws, o) {
            return ocrFindAll(kws, o).length > 0;
        }

        /**
         * 把 OCR 命中行压成一行日志：「签到卡@(880,1412) / 刮卡+1@(870,1735)」。
         *
         * 排障时需要的是「识别到了什么、在哪」，而不是一个真假值 ——
         * 真机上坐标点歪时，只有把坐标打出来才能一眼看出是 OCR 认错字还是点击没生效。
         * limit 默认 8：canvas 页一屏常有 40+ 行，全打出来会把日志淹掉。
         */
        function ocrBrief(lines, limit) {
            if (!lines || !lines.length) return '（空）';
            var n = Math.min(lines.length, (typeof limit === 'number' ? limit : 8));
            var a = [];
            for (var i = 0; i < n; i++) {
                a.push(lines[i].t + '@(' + lines[i].cx + ',' + lines[i].cy + ')');
            }
            if (lines.length > n) a.push('…还有 ' + (lines.length - n) + ' 行');
            return a.join(' / ');
        }

        /**
         * 解析倒计时秒数。正则沿用 jd_JOY游记.js v1.2 实测集：
         *   继续浏览4秒 / 剩余10秒 / 10秒 / 10s / 00:10
         * 排除「恰好等于当前时刻的 mm:ss」，否则状态栏时间会被当成倒计时。
         */
        function parseCountdown(t) {
            if (!t) return null;
            var s = null, m;
            m = t.match(/(?:继续)?浏览\s*(\d+)\s*[秒sS]/); if (m) s = parseInt(m[1], 10);
            if (s == null) { m = t.match(/剩余\s*(\d+)\s*[秒sS]/); if (m) s = parseInt(m[1], 10); }
            if (s == null && /^\d{1,2}\s*[秒sS]${'$'}/.test(t)) s = parseInt(t, 10);
            if (s == null && /^\d{1,2}[:：]\d{2}${'$'}/.test(t)) {
                var d = new Date();
                var norm = t.replace('：', ':');
                var nowA = d.getHours() + ':' + padStr(d.getMinutes(), 2);
                var nowB = padStr(d.getHours(), 2) + ':' + padStr(d.getMinutes(), 2);
                if (norm !== nowA && norm !== nowB) {
                    var p = norm.split(':');
                    var ss = parseInt(p[1], 10);
                    if (ss <= 59) s = parseInt(p[0], 10) * 60 + ss;
"""

    private val PART_6 = """
                }
            }
            if (s != null && s >= 0 && s <= 900) return s;
            return null;
        }

        /**
         * 落地页停留（核心）。规则来自 jd_JOY游记 v1.2 的血债：
         * 固定 sleep(11000) 把页面加载算进去，实际有效浏览只有 8~9 秒 → 判「浏览时长不足」。
         * 正确做法：轮询倒计时归零 + graceMs 余量后再点返回；探不到倒计时才用 fallbackDwellMs。
         */
        function waitCountdown(cd) {
            cd = cd || {};
            var grace = cd.graceMs != null ? cd.graceMs : 2000;
            var poll = cd.pollMs || 600;
            var hard = cd.hardLimitMs || 120000;
            var kws = cd.keywords || ['立即返回', '点击立即返回', '已完成，点击立即返回'];
            var band = cd.band || null;

            // 停留时长的三个来源（优先级从高到低）：
            //   ① dwellSec —— 调用方从「当前任务副标题」里读出来的「浏览N秒」
            //   ② fallbackDwellMs
            //   ③ 默认 12s（= 京东常见 10s 要求 + 2s 余量）
            // jd_秒杀.js 的实测：同一个脚本里不同任务标注的秒数不一样（3s~15s），
            // 用统一常量必然有的判「不足」、有的白等。
            var fallback = cd.fallbackDwellMs != null ? cd.fallbackDwellMs : 12000;
            if (cd.dwellSec != null && cd.dwellSec > 0) {
                fallback = (cd.dwellSec + (grace / 1000)) * 1000;
                log('  · 任务标注浏览 ' + cd.dwellSec + 's，按标注时长停留');
            }
            var st = PRIM.now();
            /**
             * doneAt 必须在这里声明。
             * 早期漏了它，第 1324 行 `doneAt = PRIM.now()` 就变成了**隐式全局变量**：
             *   · 非严格模式下能跑，所以一直没报错；
             *   · 但两次 waitCountdown 会共用同一个全局值 —— 第一次结束了、
             *     第二次进循环时 doneAt 还是旧时间戳，于是 `(doneAt + grace) - now` 为负，
             *     余量等待被静默跳过（真实的「浏览时长不足」隐患）。
             */
            var lastSec = null, lastSeenAt = 0, zeroAt = 0, firstCdAt = 0, doneAt = 0;
            var sawCd = false, seq = [];
            log('  · 落地页停留：等倒计时归零（兜底 ' + Math.round(fallback / 1000)
                + 's，上限 ' + Math.round(hard / 1000) + 's）');

            while (PRIM.now() - st < hard) {
                var sc = scanLanding(band, kws, true);
                var sec = sc.sec;

                if (sec != null) {
                    if (!sawCd) firstCdAt = PRIM.now();
                    sawCd = true; lastSeenAt = PRIM.now();
                    if (sec !== lastSec) {
                        seq.push(sec);
                        if (seq.length === 1) log('  · 检测到倒计时 ' + sec + 's，等归零 + ' + Math.round(grace / 1000) + 's 余量');
                        lastSec = sec;
                    }
                    /**
                     * 归零时刻按「读到几秒就还差几秒」推算，并且**只向前收敛**（取 min）。
                     * jd_JOY游记 的教训：读到 1 秒不能算归零 —— 那还差 1 秒，
                     * 提前点返回正是「浏览时长不足」的直接成因（v1.1 八轮全被判不足）。
                     * 只有确实读到 0 秒，才算此刻归零。
                     */
                    var remain = sec > 0 ? sec : 0;
                    var cand = PRIM.now() + remain * 1000;
                    zeroAt = zeroAt ? Math.min(zeroAt, cand) : cand;
                }

                var can = false;
                if (sawCd) {
                    // 读到 0 且已停刷满余量 → 可以走
                    if (lastSec === 0 && PRIM.now() - lastSeenAt >= grace) can = true;
                    // 主路径：推算归零 + 余量已过
                    if (!can && zeroAt && PRIM.now() >= zeroAt + grace) can = true;
                } else if (PRIM.now() - st >= fallback) {
                    // 从未读到倒计时：必须真停满兜底时长，否则就是 v1.1 的固定 sleep 不足
                    log('  · 全程未探到倒计时，已停满兜底 ' + Math.round(fallback / 1000) + 's');
                    can = true;
                }

                if (can) { doneAt = PRIM.now(); break; }

                // 硬上限：读数异常时不要无限轮询
                if (sawCd && firstCdAt) {
                    var cap = Math.min(hard, (firstCdAt - st) + (fallback + grace) + 8000);
                    if (PRIM.now() - st > cap) {
                        log('  ! 停留超预期（已 ' + Math.round((PRIM.now() - st) / 1000) + 's），强制继续');
                        doneAt = PRIM.now(); break;
                    }
                }
                PRIM.sleepIn(sc.src === 'node' ? 400 : poll);   // 无障碍轮询可以更快
            }

            if (seq.length > 1) log('  · 倒计时轨迹: ' + seq.join('→') + '（' + seq.length + ' 次读数）');
            if (doneAt) {
                var wait = (doneAt + grace) - PRIM.now();
                if (wait > 0) { log('  · 再等 ' + Math.round(wait) + 'ms 余量'); PRIM.sleepIn(wait); }
            } else {
                log('  ! 到达停留硬上限 ' + Math.round(hard / 1000) + 's，直接尝试返回');
            }

            var rbPt = findReturnButton(band, kws);
            if (!rbPt) { log('  ! 没看到「立即返回」按钮，交给后续节点处理'); return null; }
            // 点击前走一次护栏：京东有些浏览任务落地在结算/下单页，那里绝不能点任何东西
            if (!guard()) { log('  ! 护栏拦截，未点击返回'); return null; }
            log('  · 点「立即返回」@(' + rbPt.cx + ',' + rbPt.cy + ')');
            PRIM.click(rbPt.cx, rbPt.cy);
            invalidate();
            PRIM.sleepIn(1200);
            return rbPt;
        }

        /**
         * 落地页扫描：**无障碍优先，OCR 兜底**。
         * jd_JOY游记 nodeOrOcrScan() 的实测结论：无障碍能拿到原生控件文字，比 OCR
         * 快且准；只有 canvas 页（读不到文本节点）才回退 OCR。原实现在这里只会 OCR，
         * 结果每个扫描周期贵 1.5~1.7s（真机实测 OCR 35 行耗时 1690ms）。
         */
        function scanLanding(band, kws, force) {
            var out = { rb: null, sec: null, src: 'node' };

            // ① 无障碍。倒计时每秒都在变，必须绕过 1.2s 快照缓存，
            //    否则读数滞后会把「归零时刻」整体推后（实测多等 2s）。
            //    jd_JOY游记 的 nodeOrOcrScan 也是每次都重新 find()，不缓存。
            if (force) invalidate();
            var nodes = snap();
            var inBand = function (cy) { return !band || (cy >= band[0] && cy <= band[1]); };
            for (var i = 0; i < nodes.length; i++) {
                var it = nodes[i];
                var txt = (it.t || '') + '|' + (it.d || '');
                if (!it.cy || it.cy < 150 || !inBand(it.cy)) continue;
                if (!out.rb) {
                    for (var k = 0; k < kws.length; k++) {
                        if (txt.indexOf(kws[k]) >= 0) { out.rb = { t: it.t || it.d, cx: it.cx, cy: it.cy }; break; }
                    }
                }
                if (out.sec == null) {
                    var v = parseCountdown(it.t) ;
                    if (v == null) v = parseCountdown(it.d);
                    if (v != null) out.sec = v;
                }
            }
            if (out.rb || out.sec != null) return out;

            // ② OCR 兜底
            out.src = 'ocr';
            var lines = ocrLines(band);
            for (var j = 0; j < lines.length; j++) {
                if (!out.rb) {
                    for (var m = 0; m < kws.length; m++) {
                        if (lines[j].t.indexOf(kws[m]) >= 0) { out.rb = { t: lines[j].t, cx: lines[j].cx, cy: lines[j].cy }; break; }
                    }
                }
                if (out.sec == null) {
                    var w = parseCountdown(lines[j].t);
                    if (w != null) out.sec = w;
                }
            }
            return out;
        }

        function findReturnButton(band, kws) {
            var sc = scanLanding(band, kws, false);   // 循环刚扫过，这里复用缓存即可
            return sc.rb ? { t: sc.rb.t, cx: sc.rb.cx, cy: sc.rb.cy } : null;
        }

        /**
         * toast 抓拍。京东活动 toast 是 H5 内绘制，events.observeToast() 抓不到
         * （jd_频道_京东校园 v7.0 实测），只能「点击后立刻连拍 + 裁剪 OCR」。
         * 实测存活约 1.4s，连拍 84~92ms/帧。
         */
        function toastBurst(kws, o) {
            o = o || {};
            var frames = o.frames || 8;
            var gap = o.gapMs || 110;
            var band = o.band || [850, 1650];
            var arr = isArr(kws) ? kws : [kws];
            var hit = null;
            for (var i = 0; i < frames; i++) {
                var lines = ocrLines(band);
                for (var j = 0; j < lines.length && !hit; j++) {
                    for (var k = 0; k < arr.length; k++) {
                        if (lines[j].t.indexOf(arr[k]) >= 0) { hit = lines[j].t; break; }
                    }
                }
                if (hit) break;
                PRIM.sleepIn(gap);
            }
            if (hit) log('  · 捕获 toast：「' + hit + '」');
            return hit;
        }

        /* ---------------- 启动 / 深链 ---------------- */

        function launch() {
            for (var k = 0; k < 2; k++) {
                if (!isFg()) {
                    log('  · 启动 ' + pkg + (k ? '（重试）' : ''));
                    PRIM.launchApp(pkg);
                }
                var st = PRIM.now();
                while (PRIM.now() - st < 20000) {
                    if (isFg()) return true;
                    PRIM.sleepIn(400);
                }
            }
            return false;
        }

        function openDeepLink(url) {
            log('  · 打开深链 ' + url);
            if (!PRIM.openUrl(url)) { log('  ! 深链打开失败'); return false; }
            var st = PRIM.now();
            while (PRIM.now() - st < (cfg.deeplinkWaitMs || 15000)) {
                if (isFg()) return true;
                PRIM.sleepIn(500);
            }
            return false;
        }

        /* ---------------- 收尾 ---------------- */

        function reportLeftover(title) {
            if (!title) return 0;
            for (var i = 0; i < stats.leftoverTitles.length; i++) {
                if (stats.leftoverTitles[i] === title) return stats.leftover;
            }
            stats.leftoverTitles.push(title);
            stats.leftover += 1;
            return stats.leftover;
        }

        function finish(result) {
            var r = result || (stats.leftover > 0 ? 'warn' : 'success');
            log('==== 结束：动作 ' + stats.acted + ' / 跳过 ' + stats.skipped
                + ' / 失败 ' + stats.failed + ' / 剩余 ' + stats.leftover
                + ' / 用时 ' + Math.round(elapsed() / 1000) + 's ====');
            if (stats.leftoverTitles.length) log('  · 未完成清单：' + stats.leftoverTitles.join('、'));
            PRIM.toastIn((cfg.name || cfg.id || 'flow') + '：' + String(r).toUpperCase());
            var RESULT = (r === 'success' ? true : (r === 'warn' ? 'WARN' : false));
            log('结果: ' + (RESULT === true ? 'SUCCESS' : (RESULT === 'WARN' ? 'WARN(有任务未做完)' : 'FAILED')));
            return RESULT;
        }

        return {
            cfg: cfg, stats: stats, log: log, stat: stat, elapsed: elapsed, overBudget: overBudget,
            curPkg: PRIM.currentPackage, curAct: PRIM.currentActivity,
            isFg: isFg, isDesktop: isDesktop, isSelfApp: isSelfApp,
            guard: guard, guardRisk: guardRisk, guardRisky: guardRisky, atRisk: atRisk, dismissDialogs: dismissDialogs,
            snap: snap, invalidate: invalidate, findOne: findOne, findAll: findAll, exists: exists,
            probe: probe, rowBind: rowBind, pageTitle: pageTitle, signature: signature,
            tapPoint: tapPoint, tapNode: tapNode, click: clickSel, back: back,
            swipe: swipeByRatio, input: inputText, longPress: longPress, launch: launch, openDeepLink: openDeepLink,
            waitFor: waitFor, waitGone: waitGone, waitVerify: waitVerify, waitCountdown: waitCountdown,
            parseDwellSec: parseDwellSec, recoverForeground: recoverForeground, isLandingPage: isLandingPage,
            goDesktop: goDesktop, findDesktopWidget: findDesktopWidget,
            ocrLines: ocrLines, ocrFind: ocrFind, ocrSnap: ocrSnap, ocrFindAll: ocrFindAll,
            ocrClick: ocrClick, ocrExists: ocrExists, ocrBrief: ocrBrief, ocrSubstrWarn: ocrSubstrWarn,
            isBeanPage: isBeanPage, backToBean: backToBean, dismissLeaveDialog: dismissLeaveDialog,
            findProductCards: findProductCards, tapProducts: tapProducts,
            parseCountdown: parseCountdown, toastBurst: toastBurst,
            finish: finish, reportLeftover: reportLeftover,
            inScreen: function (b) { return inScreen(b, H, W); },
            screen: { W: W, H: H }
        };
    }

    return { boot: boot, rectOf: rectOf, inScreen: inScreen, configure: configure, _prim: PRIM };
})();

if (typeof module !== 'undefined' && module.exports) module.exports = Kit;
"""

    val SOURCE: String by lazy {
        listOf(PART_1, PART_2, PART_3, PART_4, PART_5, PART_6).joinToString("\n")
    }
}