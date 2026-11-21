/* AutoJs6 Studio · 本机配置
 * 这里放"常用设备"清单：打开应用就出现在设备下拉里，空项目也会带上。
 * 只放地址与名字，不要写令牌——令牌只在设备页里手工填，且不落盘。
 * 换设备/换 IP 直接改这个文件，改完刷新页面即可。
 */
(function () {
  const devices = [
    { url: 'http://192.168.1.17:9637/mcp', name: '魅族' },
    { url: 'http://192.168.1.6:9637/mcp', name: '一加' }
  ];
  A6.config = {
    devices: devices,
    defaultUrl: (devices[0] || {}).url || ''
  };
})();
