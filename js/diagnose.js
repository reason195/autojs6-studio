/* AutoJs6 Studio · 诊断引擎（静态规则 + 运行日志回放） */
(function () {
  function collect(project) {
    const items = [];
    const walk = (list, scope, ancestors) => {
      (list || []).forEach((b, i) => {
        items.push({ b, scope, index: i + 1, ancestors });
        Object.keys(b.children || {}).forEach(k => walk(b.children[k], scope, ancestors.concat(b)));
      });
    };
    walk(project.root.children.body, '主程序', []);
    (project.modules || []).forEach(m => walk(m.children.body, '模块「' + m.name + '」', []));
    return items;
  }

  const typeOf = it => it.b.type;
  const hasAncestor = (it, type) => it.ancestors.some(a => a.type === type);

  function scan(project) {
    const issues = [];
    const items = collect(project);
    const add = (level, title, hint, it) => issues.push({
      level, title, hint,
      loc: it ? it.scope + ' · 第 ' + it.index + ' 块' : '项目',
      blockId: it ? it.b.id : null
    });

    if (!project.root.children.body.length) add('info', '主程序还是空的', '把左侧积木拖进来，或点「示例」载入京东领券中心流程');

    const hasAutoWait = items.some(it => typeOf(it) === 'autoWait');
    const uiTypes = ['clickNode', 'longClickNode', 'setTextNode', 'waitText', 'scrollNode', 'nodeTextVar', 'existsVar'];
    const firstUi = items.find(it => uiTypes.indexOf(typeOf(it)) >= 0);
    if (firstUi && !hasAutoWait && !items.some(it => it.scope === firstUi.scope && typeOf(it) === 'funcDef')) {
      add('warn', 'UI 操作前没有 auto.waitFor()', '无障碍服务未就绪时选择器会全部落空；在主程序开头加一块「等待无障碍服务就绪」', firstUi);
    }

    items.forEach(it => {
      const b = it.b, t = typeOf(it), p = b.params || {};

      if ((t === 'clickNode' || t === 'longClickNode' || t === 'setTextNode') && !String(p.value || '').trim()) {
        add('error', '控件定位值为空', '空值会匹配到意外节点；填写文本 / desc / id', it);
      }
      if (t === 'waitText' && !Number(p.timeout)) {
        add('warn', '等待控件未设置超时', '超时为 0 会立即返回，容易误判「控件已出现」', it);
      }
      if (t === 'sleep') {
        const ms = Number(p.ms) || 0;
        if (ms <= 0) add('warn', 'sleep(0) 无实际等待', '改为 300~1500ms，或改用控件等待', it);
        else if (ms > 30000) add('warn', '单次等待超过 30 秒', '长等待会拖慢整条流程，建议拆成「轮询 + 短 sleep」', it);
        if (ms >= 3000 && hasAncestor(it, 'forRange') ) add('info', '循环内有 3 秒以上等待', '总耗时会乘上循环次数，注意超时', it);
      }
      if (t === 'whileGuard' && Number(p.max) > 50) {
        add('warn', '循环上限过大', '上限过大会让脚本长时间挂住，建议 ≤ 30', it);
      }
      if ((t === 'if' || t === 'forRange' || t === 'forOf' || t === 'whileGuard' || t === 'tryCatch' || t === 'funcDef' || t === 'threadStart' || t === 'retry')
        && !Object.keys(b.children || {}).some(k => (b.children[k] || []).length)) {
        add('warn', '容器积木是空的', '空容器不产生任何代码，拖入子积木后再运行', it);
      }
      if (t === 'funcCall' && !String(p.name || '').trim()) add('error', '函数名为空', '填上要调用的函数名', it);
      if (t === 'moduleCall' && !p.module) add('error', '未选择模块', '在参数里选择一个「我的模块」', it);
      if ((t === 'varSet' || t === 'varAssign') && !String(p.name || '').trim()) add('error', '变量名为空', '补一个合法标识符', it);

      if (t === 'httpGet' || t === 'httpPostJson') {
        if (!hasAncestor(it, 'tryCatch') && !hasAncestor(it, 'retry')) {
          add('warn', '网络请求没有异常保护', '用「尝试/捕获」或「重试」包住，避免断网直接终止脚本', it);
        }
      }

      if (t === 'jdSign' && !items.some(x => typeOf(x) === 'jdEnter')) {
        add('info', '签到前没有「进入领券中心」', '冷启动时签到模块可能不渲染，先执行 ① 进入领券中心', it);
      }
      if (t === 'jdTaskLoop') {
        const hasClose = items.some(x => typeOf(x) === 'jdClosePopup');
        if (!hasClose) add('warn', '任务循环缺少「关弹窗并重读」', '任务完成后会弹无文字空弹窗，直接把任务数读成 0，脚本会误收工（分享会话里的核心坑）', it);
        const bodyEmpty = !((b.children || {}).body || []).length;
        if (bodyEmpty) add('warn', '任务循环体为空', '循环体里至少放「执行任务」或「跨 app 切回」', it);
      }
      if (items.some(x => typeOf(x) === 'jdTaskLoop') && !items.some(x => typeOf(x) === 'jdFinishGuard')) {
        const last = items.filter(x => typeOf(x) === 'jdTaskLoop').pop();
        add('warn', '缺少「收工判定」', '浮层首屏可能全是抽奖/券，0 任务要下滑扫描后再收工，否则会漏做京豆任务', last);
      }
    });

    const names = {};
    (project.modules || []).forEach(m => {
      const n = String(m.name || '').trim();
      if (!n) add('error', '模块名为空', '模块名用于生成函数名，不能为空');
      else if (names[n]) add('error', '模块名重复：' + n, '重名会生成同名函数，互相覆盖');
      names[n] = true;
      if (!(m.children.body || []).length) add('info', '模块「' + n + '」是空的', '空模块可以占位，但调用它什么也不做');
    });

    if (!(project.tests || []).length) add('info', '还没有测试用例', '「测试」页可以给关键流程加断言，改积木后先跑测试再上真机');

    const order = { error: 0, warn: 1, info: 2 };
    issues.sort((a, b) => order[a.level] - order[b.level]);
    return issues;
  }

  function fromRun(run) {
    const issues = [];
    if (!run) return issues;
    if (run.status === 'error' && run.error) {
      issues.push({ level: 'error', title: '运行抛出异常', hint: String(run.error.message || run.error), loc: '运行日志', blockId: null });
    }
    const logs = run.logs || [];
    const count = (re) => logs.filter(l => re.test(l.msg)).length;
    const miss = count(/等待控件超时/);
    if (miss) issues.push({ level: 'warn', title: '有 ' + miss + ' 次控件等待超时', hint: '检查定位值是否随文案变化；入口按钮文案会从「立即领奖」变成「更多任务」', loc: '运行日志', blockId: null });
    const guard = count(/循环保护触发/);
    if (guard) issues.push({ level: 'error', title: '循环保护触发 ' + guard + ' 次', hint: '循环条件可能永远为真，或没在成功分支里更新条件', loc: '运行日志', blockId: null });
    const assertFail = count(/断言失败/);
    if (assertFail) issues.push({ level: 'error', title: '断言失败 ' + assertFail + ' 次', hint: '测试用例里的关键假设不成立', loc: '运行日志', blockId: null });
    const hasFinish = logs.some(l => /收工/.test(l.msg));
    const hasClose = logs.some(l => /关闭遮挡弹窗|重读任务数/.test(l.msg));
    if (hasFinish && !hasClose) issues.push({ level: 'warn', title: '疑似「0 任务」误判收工', hint: '收工前没有关闭弹窗/重读记录，补一块「关遮挡弹窗并重读」', loc: '运行日志', blockId: null });
    if (run.virtualMs > 120000) issues.push({ level: 'info', title: '模拟耗时 ' + Math.round(run.virtualMs / 1000) + ' 秒', hint: '真机上会更慢，建议精简等待或改成控件等待', loc: '运行日志', blockId: null });
    return issues;
  }

  A6.diag = { scan, fromRun, collect };
})();