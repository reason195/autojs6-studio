/* AutoJs6 Studio · 积木库
 * 每个积木 = 一个可拖拽的语句/容器，gen() 负责生成真实 AutoJs6 代码。
 * 只收录 AutoJs6 官方文档与 MCP 工具中确认存在的能力；带 ⚠ 的积木依赖插件或系统权限。
 */
(function () {
  const CATS = [
    { id: 'flow', name: '流程与控制', color: '#6b7cff' },
    { id: 'app', name: '应用与设备', color: '#2fb1a8' },
    { id: 'ui', name: 'UI 无障碍', color: '#e0733c' },
    { id: 'gesture', name: '手势与按键', color: '#c05b8f' },
    { id: 'vision', name: '图像 / OCR', color: '#8a63d2' },
    { id: 'data', name: '数据 / 网络 / 文件', color: '#3d8bd4' },
    { id: 'debug', name: '调试与诊断', color: '#d4a017' },
    { id: 'jd', name: '京东领券中心（实战）', color: '#d94f4f' },
    { id: 'module', name: '我的模块', color: '#64748b' }
  ];

  const P = {
    text: (k, l, d) => ({ key: k, label: l, kind: 'text', default: d == null ? '' : d }),
    expr: (k, l, d) => ({ key: k, label: l, kind: 'expr', default: d == null ? '' : d }),
    num: (k, l, d) => ({ key: k, label: l, kind: 'number', default: d == null ? 0 : d }),
    sel: (k, l, d, o) => ({ key: k, label: l, kind: 'select', default: d, options: o }),
    code: (k, l, d) => ({ key: k, label: l, kind: 'code', default: d == null ? '' : d }),
    chk: (k, l, d) => ({ key: k, label: l, kind: 'checkbox', default: !!d })
  };

const BY = [
    { v: 'text', t: '文本' }, { v: 'desc', t: '描述' }, { v: 'id', t: 'id' },
    { v: 'textContains', t: '文本包含' }, { v: 'textMatches', t: '文本正则' },
    { v: 'descContains', t: '描述包含' }, { v: 'descMatches', t: '描述正则' },
    { v: 'idMatches', t: 'id 正则' }, { v: 'className', t: '类名' },
    { v: 'classNameMatches', t: '类名正则' }
  ];
  const BYP = () => P.sel('by', '定位方式', 'text', BY);

  const B = {};
  const defs = [];
  function def(d) { d.params = d.params || []; d.slots = d.slots || []; B[d.type] = d; defs.push(d); }

  /* ===================== 流程与控制 ===================== */
  def({
    type: 'comment', cat: 'flow', name: '注释', params: [P.text('text', '内容', '说明')],
    gen: (b, c) => '// ' + (c.v(b, 'text') || '').replace(/\n/g, ' ')
  });
  def({
    type: 'autoWait', cat: 'flow', name: '等待无障碍服务就绪',
    gen: () => 'auto.waitFor();'
  });
  def({
    type: 'consoleShow', cat: 'flow', name: '显示控制台浮窗',
    gen: () => 'console.show();'
  });
  def({
    type: 'log', cat: 'flow', name: '输出日志', params: [P.text('text', '文本', 'hello AutoJs6')],
    gen: (b, c) => 'log(' + c.q(c.v(b, 'text')) + ');'
  });
  def({
    type: 'logExpr', cat: 'flow', name: '输出表达式', params: [P.expr('expr', '表达式', '1 + 1')],
    gen: (b, c) => 'log(' + (c.v(b, 'expr') || "''") + ');'
  });
  def({
    type: 'toast', cat: 'flow', name: '弹出 Toast', params: [P.text('text', '文本', '开始执行')],
    gen: (b, c) => 'toast(' + c.q(c.v(b, 'text')) + ');'
  });
  def({
    type: 'sleep', cat: 'flow', name: '等待 {ms} 毫秒', params: [P.num('ms', '毫秒', 1000)],
    gen: (b, c) => 'sleep(' + (Number(c.v(b, 'ms')) || 0) + ');'
  });
  def({
    type: 'varSet', cat: 'flow', name: '定义变量 {name} = {expr}',
    params: [P.text('name', '变量名', 'value'), P.expr('expr', '值', "''")],
    gen: (b, c) => 'let ' + (c.v(b, 'name') || 'v') + ' = ' + (c.v(b, 'expr') || 'null') + ';'
  });
  def({
    type: 'varAssign', cat: 'flow', name: '赋值 {name} = {expr}',
    params: [P.text('name', '变量名', 'value'), P.expr('expr', '值', '0')],
    gen: (b, c) => (c.v(b, 'name') || 'v') + ' = ' + (c.v(b, 'expr') || 'null') + ';'
  });
  def({
    type: 'if', cat: 'flow', name: '如果 {cond}', params: [P.expr('cond', '条件', 'true')],
    slots: [{ key: 'body', label: '那么' }, { key: 'else', label: '否则' }],
    gen: (b, c) => {
      let s = 'if (' + (c.v(b, 'cond') || 'false') + ') {\n' + c.body(b, 'body', true) + '\n}';
      if (c.has(b, 'else')) s += ' else {\n' + c.body(b, 'else', true) + '\n}';
      return s;
    }
  });
  def({
    type: 'forRange', cat: 'flow', name: '循环 {i} 从 0 到 {n}',
    params: [P.text('i', '循环变量', 'i'), P.num('n', '次数', 3)],
    slots: [{ key: 'body', label: '循环体' }],
    gen: (b, c) => {
      const i = b.params.i || 'i';
      return 'for (let ' + i + ' = 0; ' + i + ' < ' + (Number(c.v(b, 'n')) || 0) + '; ' + i + '++) {\n' + c.body(b, 'body', true) + '\n}';
    }
  });
  def({
    type: 'forOf', cat: 'flow', name: '遍历 {item} ∈ {list}',
    params: [P.text('item', '元素变量', 'item'), P.expr('list', '列表', '[]')],
    slots: [{ key: 'body', label: '循环体' }],
    gen: (b, c) => 'for (const ' + (b.params.item || 'item') + ' of (' + (c.v(b, 'list') || '[]') + ')) {\n' + c.body(b, 'body', true) + '\n}'
  });
  def({
    type: 'whileGuard', cat: 'flow', name: '循环直到（最多 {max} 轮）',
    params: [P.expr('cond', '条件', 'true'), P.num('max', '最大轮数', 20)],
    slots: [{ key: 'body', label: '循环体' }],
    gen: (b, c) => {
      const g = c.nv('guard');
      return 'let ' + g + ' = 0;\nwhile (' + (c.v(b, 'cond') || 'false') + ') {\n  if (++' + g + ' > ' + (Number(c.v(b, 'max')) || 20) + ') { log("循环保护触发: ' + (c.v(b, 'cond') || '') + '"); break; }\n' + c.body(b, 'body', 1) + '\n}';
    }
  });
  def({
    type: 'tryCatch', cat: 'flow', name: '尝试 / 捕获 {err}',
    params: [P.text('err', '异常变量', 'e'), P.chk('rethrow', '捕获后重新抛出', false)],
    slots: [{ key: 'body', label: '尝试' }, { key: 'catch', label: '捕获' }],
    gen: (b, c) => {
      const err = b.params.err || 'e';
      let s = 'try {\n' + c.body(b, 'body', true) + '\n} catch (' + err + ') {\n' + c.body(b, 'catch', true);
      if (b.params.rethrow) s += '\n  throw ' + err + ';';
      s += '\n}';
      return s;
    }
  });
  def({
    type: 'funcDef', cat: 'flow', name: '定义函数 {name}({args})',
    params: [P.text('name', '函数名', 'doTask'), P.text('args', '参数列表', 'task'), P.chk('isAsync', '生成 async 函数', false)],
    slots: [{ key: 'body', label: '函数体' }],
    gen: (b, c) => 'function ' + (b.params.name || 'fn') + '(' + (b.params.args || '') + ') {\n' + c.body(b, 'body', true) + '\n}'
  });
  def({
    type: 'funcCall', cat: 'flow', name: '调用函数 {name}({args})',
    params: [P.text('name', '函数名', 'doTask'), P.expr('args', '实参', "'a'")],
    gen: (b, c) => (b.params.name || 'fn') + '(' + (c.v(b, 'args') || '') + ');'
  });
  def({
    type: 'exitScript', cat: 'flow', name: '结束脚本',
    gen: () => 'exit();'
  });
  def({
    type: 'retry', cat: 'flow', name: '重试 {times} 次（间隔 {delay}ms）',
    params: [P.num('times', '次数', 3), P.num('delay', '间隔', 800)],
    slots: [{ key: 'body', label: '尝试' }, { key: 'catch', label: '失败处理' }],
    gen: (b, c) => {
      const n = Number(c.v(b, 'times')) || 3, d = Number(c.v(b, 'delay')) || 0;
return 'for (let attempt = 1; attempt <= ' + n + '; attempt++) {\n  try {\n' + c.body(b, 'body', 2)
        + '\n    break;\n  } catch (e) {\n    log("第 " + attempt + " 次尝试失败: " + e);\n' + c.body(b, 'catch', 2)
        + '\n    if (attempt === ' + n + ') throw e;\n    sleep(' + d + ');\n  }\n}';
    }
  });

  /* ===================== 应用与设备 ===================== */
  def({
    type: 'launchPackage', cat: 'app', name: '启动应用 {pkg}',
    params: [P.text('pkg', '包名', 'com.jingdong.app.mall'), P.num('wait', '等待毫秒', 1500)],
    gen: (b, c) => 'app.launchPackage(' + c.q(c.v(b, 'pkg')) + ');\nsleep(' + (Number(c.v(b, 'wait')) || 0) + ');'
  });
  def({
    type: 'openUrl', cat: 'app', name: '打开链接 / 深链', params: [P.text('url', 'URL', 'https://pro.m.jd.com/')],
    gen: (b, c) => 'app.openUrl(' + c.q(c.v(b, 'url')) + ');'
  });
  def({
    type: 'killApp', cat: 'app', name: '结束应用 {pkg}',
    params: [P.text('pkg', '包名', 'com.jingdong.app.mall')],
    gen: (b, c) => 'app.kill(' + c.q(c.v(b, 'pkg')) + ');\nsleep(800);'
  });
  def({
    type: 'currentPkgVar', cat: 'app', name: '记录当前包名到 {name}',
    params: [P.text('name', '变量名', 'pkg')],
    gen: (b, c) => 'let ' + (b.params.name || 'pkg') + ' = currentPackage();\nlog("当前包名: " + ' + (b.params.name || 'pkg') + ');'
  });
  def({
    type: 'waitForPackage', cat: 'app', name: '等待包名 {pkg}（{timeout}ms）',
    params: [P.text('pkg', '包名', 'com.jingdong.app.mall'), P.num('timeout', '超时', 8000)],
    gen: (b, c) => 'waitForPackage(' + c.q(c.v(b, 'pkg')) + ', ' + (Number(c.v(b, 'timeout')) || 8000) + ');'
  });
  def({
    type: 'keepScreenOn', cat: 'app', name: '保持屏幕常亮 {ms} 毫秒',
    params: [P.num('ms', '毫秒', 1800000)],
    gen: (b, c) => 'device.keepScreenOn(' + (Number(c.v(b, 'ms')) || 0) + ');'
  });
  def({
    type: 'vibrate', cat: 'app', name: '震动 {ms} 毫秒', params: [P.num('ms', '毫秒', 300)],
    gen: (b, c) => 'device.vibrate(' + (Number(c.v(b, 'ms')) || 0) + ');'
  });
  def({
    type: 'clipSet', cat: 'app', name: '写入剪贴板', params: [P.text('text', '文本', '')],
    gen: (b, c) => 'setClip(' + c.q(c.v(b, 'text')) + ');'
  });
  def({
    type: 'clipGet', cat: 'app', name: '读取剪贴板到 {name}', params: [P.text('name', '变量名', 'clip')],
    gen: (b, c) => 'let ' + (b.params.name || 'clip') + ' = getClip();'
  });
  def({
    type: 'shellCmd', cat: 'app', name: '执行 Shell：{var} = {cmd}',
    params: [P.text('var', '结果变量', 'r'), P.code('cmd', '命令', 'echo hi')],
    gen: (b, c) => 'let ' + (b.params.var || 'r') + ' = shell(' + c.q(c.v(b, 'cmd')) + ', true);\nlog(String(' + (b.params.var || 'r') + '));'
  });

  /* ===================== UI 无障碍 ===================== */
  def({
    type: 'waitText', cat: 'ui', name: '等待控件出现（{timeout}ms）',
    params: [BYP(), P.text('value', '值', '更多任务'), P.num('timeout', '超时', 8000)],
    gen: (b, c) => {
      const n = c.nv('wait'), t = Number(c.v(b, 'timeout')) || 3000, v = c.v(b, 'value');
return [
        'let ' + n + ' = ' + c.sel(b) + '.findOne(' + t + ');',
        'if (' + n + ') { log("控件已出现: " + ' + c.q(v) + '); } else { log("等待控件超时: " + ' + c.q(v) + '); }'
      ].join('\n');
    }
  });
  def({
    type: 'clickNode', cat: 'ui', name: '点击控件 {value}', params: [BYP(), P.text('value', '值', '立即领奖'), P.code('expr', '或表达式', ''), P.num('timeout', '查找超时', 3000)],
    gen: (b, c) => {
      const n = c.nv('node'), t = Number(c.v(b, 'timeout')) || 0;
      return 'let ' + n + ' = ' + c.sel(b) + '.findOne(' + t + ');\nif (' + n + ') {\n  ' + n + '.click();\n  log("点击: " + ' + c.q(c.v(b, 'value')) + ');\n} else {\n  log("未找到控件: " + ' + c.q(c.v(b, 'value')) + ');\n}';
    }
  });
  def({
    type: 'longClickNode', cat: 'ui', name: '长按控件 {value}', params: [BYP(), P.text('value', '值', '删除'), P.code('expr', '或表达式', ''), P.num('timeout', '超时', 3000)],
    gen: (b, c) => {
      const n = c.nv('node');
      return 'let ' + n + ' = ' + c.sel(b) + '.findOne(' + (Number(c.v(b, 'timeout')) || 0) + ');\nif (' + n + ') { ' + n + '.longClick(); } else { log("未找到控件: " + ' + c.q(c.v(b, 'value')) + '); }';
    }
  });
  def({
    type: 'setTextNode', cat: 'ui', name: '向输入框 {value} 输入 {text}',
    params: [BYP(), P.text('value', '值', '搜索'), P.code('expr', '或表达式', ''), P.text('text', '输入内容', 'AutoJs6')],
    gen: (b, c) => {
      const n = c.nv('node');
      return 'let ' + n + ' = ' + c.sel(b) + '.findOne(3000);\nif (' + n + ') { ' + n + '.setText(' + c.q(c.v(b, 'text')) + '); } else { log("未找到输入框: " + ' + c.q(c.v(b, 'value')) + '); }';
    }
  });
  def({
    type: 'nodeTextVar', cat: 'ui', name: '读取控件文本到 {var}', params: [P.text('var', '变量名', 't'), BYP(), P.text('value', '值', '更多任务'), P.code('expr', '或表达式', '')],
    gen: (b, c) => {
      const v = b.params.var || 't', n = c.nv('node');
      return 'let ' + n + ' = ' + c.sel(b) + '.findOne(2000);\nlet ' + v + ' = ' + n + ' ? ' + n + '.text() : null;\nlog("' + v + ' = " + ' + v + ');';
    }
  });
  def({
    type: 'existsVar', cat: 'ui', name: '{var} = 控件是否存在', params: [P.text('var', '变量名', 'ok'), BYP(), P.text('value', '值', '已完成'), P.code('expr', '或表达式', '')],
    gen: (b, c) => 'let ' + (b.params.var || 'ok') + ' = ' + c.sel(b) + '.exists();\nlog("存在性: " + ' + (b.params.var || 'ok') + ');'
  });
  def({
    type: 'scrollNode', cat: 'ui', name: '滚动控件（{dir}）',
    params: [P.sel('dir', '方向', 'forward', [{ v: 'forward', t: '向前/下' }, { v: 'backward', t: '向后/上' }]), BYP(), P.text('value', '值', ''), P.code('expr', '或表达式', ''), P.num('times', '次数', 1)],
    gen: (b, c) => {
      const times = Number(c.v(b, 'times')) || 1;
      const call = b.params.dir === 'backward' ? 'scrollBackward' : 'scrollForward';
      let s = '';
      for (let i = 0; i < times; i++) s += c.sel(b) + '.findOne(2000).' + call + '();\n';
      return s.replace(/\n$/, '');
    }
  });
  def({
    type: 'pressBack', cat: 'ui', name: '按返回键', gen: () => 'back();'
  });
  def({
    type: 'pressHome', cat: 'ui', name: '回到桌面', gen: () => 'home();'
  });
  def({
    type: 'coordClick', cat: 'ui', name: '点击坐标 ({x}, {y})',
    params: [P.num('x', 'x', 540), P.num('y', 'y', 1200)],
    gen: (b, c) => 'click(' + (Number(c.v(b, 'x')) || 0) + ', ' + (Number(c.v(b, 'y')) || 0) + ');'
  });

  /* ===================== 手势与按键 ===================== */
  def({
    type: 'swipe', cat: 'gesture', name: '滑动 ({x1},{y1}) → ({x2},{y2})',
    params: [P.num('x1', 'x1', 540), P.num('y1', 'y1', 1600), P.num('x2', 'x2', 540), P.num('y2', 'y2', 600), P.num('dur', '时长', 400)],
    gen: (b, c) => 'swipe(' + [c.v(b, 'x1'), c.v(b, 'y1'), c.v(b, 'x2'), c.v(b, 'y2')].map(Number).join(', ') + ', ' + (Number(c.v(b, 'dur')) || 300) + ');'
  });
  def({
    type: 'swipeUpList', cat: 'gesture', name: '列表上滑一屏',
    gen: () => 'swipe(device.width / 2, device.height * 0.78, device.width / 2, device.height * 0.28, 420);'
  });
  def({
    type: 'edgeBackGesture', cat: 'gesture', name: '左边缘右滑（切回上个应用）',
    gen: () => 'gesture(2, device.height * 0.5, device.width * 0.65, device.height * 0.5, 320);'
  });
  def({
    type: 'pressKey', cat: 'gesture', name: '按键 {key}',
    params: [P.sel('key', '按键', 'back', [{ v: 'back', t: '返回' }, { v: 'home', t: 'Home' }, { v: 'recents', t: '任务键' }, { v: 'menu', t: '菜单' }])],
    gen: (b, c) => 'press(' + c.q(b.params.key || 'back') + ');'
  });

  /* ===================== 图像 / OCR ===================== */
  def({
    type: 'requestCapture', cat: 'vision', name: '申请截屏权限',
    gen: () => 'if (!images.requestScreenCapture()) {\n  toast("截屏权限被拒绝");\n  exit();\n}'
  });
  def({
    type: 'findImage', cat: 'vision', name: '找图 {path} → {var}（阈值 {th}）',
    params: [P.text('var', '结果变量', 'pt'), P.text('path', '模板路径', '/sdcard/模板.png'), P.num('th', '阈值', 0.85)],
    gen: (b, c) => {
      const v = b.params.var || 'pt';
      return 'let ' + v + ' = images.findImage(images.captureScreen(), images.read(' + c.q(c.v(b, 'path')) + '), { threshold: ' + (Number(c.v(b, 'th')) || 0.8) + ' });\n'
        + 'if (' + v + ') { log("找到图片: " + ' + v + '.x + "," + ' + v + '.y); } else { log("未找到图片"); }';
    }
  });
  def({
    type: 'ocrText', cat: 'vision', name: 'OCR 识别屏幕 → {var}',
    params: [P.text('var', '结果变量', 'txt')],
    gen: (b, c) => {
      const v = b.params.var || 'txt';
      return 'let ' + v + ' = ocr.recognizeText(images.captureScreen());\nlog("OCR: " + ' + v + ');';
    }
  });
  def({
    type: 'saveScreen', cat: 'vision', name: '截图保存到 {path}',
    params: [P.text('path', '路径', '/sdcard/autojs6/shot.png')],
    gen: (b, c) => 'files.ensureDir(' + c.q(c.v(b, 'path').replace(/[^/]+$/, '')) + ');\nimages.save(images.captureScreen(), ' + c.q(c.v(b, 'path')) + ', "png", 100);\nlog("截图已保存");'
  });

  /* ===================== 数据 / 网络 / 文件 ===================== */
  def({
    type: 'filesWrite', cat: 'data', name: '写文件 {path}',
    params: [P.text('path', '路径', '/sdcard/autojs6/log.txt'), P.expr('content', '内容', '"hello"'), P.chk('append', '追加模式', true)],
    gen: (b, c) => 'files.ensureDir(' + c.q((c.v(b, 'path') || '').replace(/[^/]+$/, '')) + ');\n'
      + (b.params.append ? 'files.append(' : 'files.write(') + c.q(c.v(b, 'path')) + ', ' + (c.v(b, 'content') || '""') + ');'
  });
  def({
    type: 'filesRead', cat: 'data', name: '读文件 {path} → {var}',
    params: [P.text('var', '变量名', 'txt'), P.text('path', '路径', '/sdcard/autojs6/log.txt')],
    gen: (b, c) => 'let ' + (b.params.var || 'txt') + ' = files.exists(' + c.q(c.v(b, 'path')) + ') ? files.read(' + c.q(c.v(b, 'path')) + ') : null;'
  });
  def({
    type: 'filesExistsVar', cat: 'data', name: '{var} = 文件是否存在', params: [P.text('var', '变量名', 'has'), P.text('path', '路径', '/sdcard/autojs6/log.txt')],
    gen: (b, c) => 'let ' + (b.params.var || 'has') + ' = files.exists(' + c.q(c.v(b, 'path')) + ');'
  });
  def({
    type: 'storagePut', cat: 'data', name: '本地存储写入 {key}',
    params: [P.text('key', '键', 'count'), P.expr('value', '值', '0')],
    gen: (b, c) => 'storages.create("autojs6-studio").put(' + c.q(c.v(b, 'key')) + ', ' + (c.v(b, 'value') || 'null') + ');'
  });
  def({
    type: 'storageGet', cat: 'data', name: '本地存储读取 {key} → {var}',
    params: [P.text('key', '键', 'count'), P.text('var', '变量名', 'count')],
    gen: (b, c) => 'let ' + (b.params.var || 'v') + ' = storages.create("autojs6-studio").get(' + c.q(c.v(b, 'key')) + ', null);'
  });
  def({
    type: 'httpGet', cat: 'data', name: 'GET {url} → {var}',
    params: [P.text('var', '结果变量', 'res'), P.text('url', 'URL', 'https://httpbin.org/get')],
    gen: (b, c) => {
      const v = b.params.var || 'res';
      return 'let ' + v + ' = http.get(' + c.q(c.v(b, 'url')) + ');\nlog("HTTP " + ' + v + '.statusCode + " " + ' + v + '.body.string());';
    }
  });
  def({
    type: 'httpPostJson', cat: 'data', name: 'POST JSON {url} → {var}',
    params: [P.text('var', '结果变量', 'res'), P.text('url', 'URL', 'https://httpbin.org/post'), P.expr('payload', '请求体', '{}')],
    gen: (b, c) => {
      const v = b.params.var || 'res';
      return 'let ' + v + ' = http.postJson(' + c.q(c.v(b, 'url')) + ', ' + (c.v(b, 'payload') || '{}') + ');\nlog("HTTP " + ' + v + '.statusCode);';
    }
  });
  def({
    type: 'threadStart', cat: 'data', name: '子线程执行', slots: [{ key: 'body', label: '线程体' }],
    gen: (b, c) => 'threads.start(function () {\n' + c.body(b, 'body', true) + '\n});'
  });

  /* ===================== 调试与诊断 ===================== */
  def({
    type: 'assertBlock', cat: 'debug', name: '断言 {cond}',
    params: [P.expr('cond', '条件', 'true'), P.text('msg', '失败信息', '断言失败')],
    gen: (b, c) => 'if (!(' + (c.v(b, 'cond') || 'true') + ')) {\n  throw new Error(' + c.q('断言失败: ' + (c.v(b, 'msg') || '')) + ');\n}'
  });
  def({
    type: 'timerStart', cat: 'debug', name: '开始计时 → {var}',
    params: [P.text('var', '变量名', 't0')],
    gen: (b, c) => 'let ' + (b.params.var || 't0') + ' = Date.now();'
  });
  def({
    type: 'timerLap', cat: 'debug', name: '输出耗时（{label}）',
    params: [P.text('label', '标签', '步骤'), P.text('var', '计时变量', 't0')],
    gen: (b, c) => 'log(' + c.q((c.v(b, 'label') || '') + ' 耗时 ') + ' + (Date.now() - ' + (b.params.var || 't0') + ') + "ms");'
  });
  def({
    type: 'dumpUi', cat: 'debug', name: '输出当前界面控件摘要',
    gen: () => 'let _nodes = descMatches(".*").find();\nlog("控件数: " + _nodes.length);\n_nodes.forEach(function (n) {\n  log([n.text(), n.desc(), n.id(), JSON.stringify(n.bounds())].join(" | "));\n});'
  });

  /* ===================== 京东领券中心（实战模板） ===================== */
  def({
    type: 'jdEnter', cat: 'jd', name: '① 进入京东领券中心（深链）',
    gen: () => [
      '// 固定深链进入；已在领券中心时复用当前页面（见分享会话第 4 段）',
      'app.launchPackage("com.jingdong.app.mall");',
      'sleep(1500);',
      'if (currentPackage() !== "com.jingdong.app.mall") {',
      '  toast("请先安装并登录京东");',
      '  exit();',
      '}',
      'app.openUrl("https://pro.m.jd.com/mall/active/VAjs3vpayA513UwxL5XC4eGBXqY/index.html");',
      'sleep(2500);',
      'log("已请求进入领券中心");'
    ].join('\n')
  });
  def({
    type: 'jdSign', cat: 'jd', name: '② 签到（天天领豆 → 更多任务）',
    gen: () => [
      '// 签到标志物：按钮从「天天领豆/立即领奖」变为「更多任务」',
      'let signNode = text("天天领豆").findOne(8000) || text("立即领奖").findOne(1000);',
      'if (signNode) {',
      '  signNode.click();',
      '  sleep(1500);',
      '  log("已点击签到");',
      '} else {',
      '  log("未发现签到按钮，可能已签到");',
      '}',
      'let moreMark = desc("更多任务").findOne(2000) || text("更多任务").findOne(2000);',
      'if (moreMark) { log("签到标志物已出现"); } else { log("警告：签到标志物未出现"); }'
    ].join('\n')
  });
  def({
    type: 'jdOpenPanel', cat: 'jd', name: '③ 打开任务浮层（双通道定位）',
    gen: () => [
      '// id 不变但文案会变：先 desc，再 id，最后 text；坐标不缓存',
      'let entry = desc("更多任务").findOne(3000) || id("coupon-left-entry").findOne(1500) || text("更多任务").findOne(1500);',
      'if (!entry) {',
      '  toast("未找到任务入口");',
      '  log("入口定位失败");',
      '  exit();',
      '}',
      'entry.click();',
      'sleep(1800);',
      'log("任务浮层已打开");'
    ].join('\n')
  });
  def({
    type: 'jdTaskLoop', cat: 'jd', name: '④ 遍历京豆任务（每页最多 {max} 个）',
    params: [P.text('taskVar', '任务变量', 'task'), P.num('max', '单轮上限', 8)],
    slots: [{ key: 'body', label: '单个任务' }],
    gen: (b, c) => {
      const tv = b.params.taskVar || 'task', max = Number(c.v(b, 'max')) || 8;
      return [
        '// 副标题含「得\\d+京豆」的任务才算目标；抽奖/券/红包跳过',
        'let _roundTasks = 0;',
        'for (let round = 0; round < 3; round++) {',
        '  let rows = descMatches(".*得\\\\d+京豆.*").find();',
        '  if (rows.length === 0) { break; }',
        '  for (let ti = 0; ti < rows.length && _roundTasks < ' + max + '; ti++) {',
        '    let ' + tv + ' = rows[ti];',
        '    _roundTasks++;',
        c.body(b, 'body', 2),
        '    sleep(600);',
        '  }',
        '}',
        'log("本轮处理任务: " + _roundTasks);'
      ].join('\n');
    }
  });
  def({
    type: 'jdDoTask', cat: 'jd', name: '⑤ 执行任务（点去完成 → 等倒计时 → 返回）',
    params: [P.text('taskVar', '任务变量', 'task'), P.num('stay', '无倒计时停留毫秒', 7000)],
    gen: (b, c) => {
      const tv = b.params.taskVar || 'task', stay = Number(c.v(b, 'stay')) || 7000;
      return [
        'if (' + tv + ') {',
        '  ' + tv + '.click();',
        '  sleep(1500);',
        '  // 落地页有倒计时就等「已完成/点击立即返回」，没有就计时后 back',
        '  let done = text("已完成").exists() || text("点击立即返回").exists();',
        '  if (done) { log("任务完成标记出现"); } else { sleep(' + stay + '); }',
        '  back();',
        '  sleep(1200);',
        '}'
      ].join('\n');
    }
  });
  def({
    type: 'jdCrossApp', cat: 'jd', name: '⑥ 跨 app 任务：跳转后立即切回',
    gen: () => [
      '// 跳到京东金融后不要在其中等待：手势条右滑切回京东',
      'sleep(800);',
      'gesture(2, device.height * 0.5, device.width * 0.65, device.height * 0.5, 320);',
      'sleep(1500);',
      'log("已切回京东");'
    ].join('\n')
  });
  def({
    type: 'jdClosePopup', cat: 'jd', name: '⑦ 关遮挡弹窗并重读（×3）',
    gen: () => [
      '// 关键：完成任务会弹无文字空弹窗，必须先关闭再重读，才不会被判成 0 任务',
      'for (let ci = 0; ci < 3; ci++) {',
      '  let closeNode = desc("关闭").findOne(700) || text("关闭").findOne(300) || id("close").findOne(300);',
      '  if (closeNode) { closeNode.click(); sleep(500); } else { break; }',
      '}',
      'sleep(400);',
      'log("重读任务数: " + descMatches(".*得\\\\d+京豆.*").find().length);'
    ].join('\n')
  });
  def({
    type: 'jdFinishGuard', cat: 'jd', name: '⑧ 收工判定（下滑扫描，最多 {rounds} 轮）',
    params: [P.num('rounds', '最大轮数', 12), P.num('zero', '连续空屏阈值', 3)],
    gen: (b, c) => {
      const rounds = Number(c.v(b, 'rounds')) || 12, zero = Number(c.v(b, 'zero')) || 3;
      return [
        '// 浮层会动态刷新：首屏 16 个可能全是抽奖，必须下滑扫完才能判收工',
        'let emptyRounds = 0;',
        'for (let r = 0; r < ' + rounds + '; r++) {',
        '  let list = descMatches(".*得\\\\d+京豆.*").find();',
        '  if (list.length > 0) { emptyRounds = 0; break; }',
        '  emptyRounds++;',
        '  if (emptyRounds >= ' + zero + ') { break; }',
        '  swipe(device.width / 2, device.height * 0.78, device.width / 2, device.height * 0.28, 420);',
        '  sleep(800);',
        '}',
        'if (emptyRounds >= ' + zero + ') { log("收工：连续 " + emptyRounds + " 轮未发现京豆任务"); } else { log("仍有任务，继续处理"); }'
      ].join('\n');
    }
  });

  /* ===================== 我的模块 ===================== */
  def({
    type: 'moduleCall', cat: 'module', name: '调用模块 {module}',
    params: [P.sel('module', '模块', '', []), P.expr('args', '实参', '')],
    gen: (b, c) => c.callModule(b)
  });

  A6.catalog = {
    CATS, BY, defs, B,
    byType: t => B[t],
    byCat: id => defs.filter(d => d.cat === id),
    colors: () => Object.fromEntries(CATS.map(c => [c.id, c.color]))
  };
})();