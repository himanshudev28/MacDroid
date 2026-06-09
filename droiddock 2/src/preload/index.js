import { contextBridge, ipcRenderer, webUtils } from 'electron'

contextBridge.exposeInMainWorld('droid', {
  tools: () => ipcRenderer.invoke('tools'),
  devices: () => ipcRenderer.invoke('devices:get'),
  onDevices: (cb) => {
    const fn = (_e, list) => cb(list)
    ipcRenderer.on('devices', fn)
    return () => ipcRenderer.removeListener('devices', fn)
  },
  info: (serial) => ipcRenderer.invoke('device:info', serial),
  list: (serial, path) => ipcRenderer.invoke('fs:list', serial, path),
  pull: (serial, remotePath, name) => ipcRenderer.invoke('fs:pull', serial, remotePath, name),
  fsDelete: (remotePath) => ipcRenderer.invoke('fs:delete', remotePath),
  fsRename: (remotePath, newName) => ipcRenderer.invoke('fs:rename', remotePath, newName),
  fsCancel: (transferId) => ipcRenderer.invoke('fs:cancel', transferId),
  onTransferProgress: (cb) => {
    const fn = (_e, p) => cb(p)
    ipcRenderer.on('transfer-progress', fn)
    return () => ipcRenderer.removeListener('transfer-progress', fn)
  },
  pushDialog: (serial) => ipcRenderer.invoke('fs:pushDialog', serial),
  pushPaths: (serial, paths) => ipcRenderer.invoke('fs:pushPaths', serial, paths),
  screenshot: (serial) => ipcRenderer.invoke('shot', serial),
  mirror: (serial) => ipcRenderer.invoke('mirror', serial),
  goWireless: (serial) => ipcRenderer.invoke('adb:wireless', serial),
  pairWireless: (hostPort, code) => ipcRenderer.invoke('adb:pair', hostPort, code),
  unpair: () => ipcRenderer.invoke('adb:unpair'),
  pairedInfo: () => ipcRenderer.invoke('adb:pairedInfo'),
  reconnectNow: () => ipcRenderer.invoke('adb:reconnectNow'),
  qrPairStart: (serviceName, password) =>
    ipcRenderer.invoke('adb:qrPairStart', serviceName, password),
  qrPairCancel: () => ipcRenderer.invoke('adb:qrPairCancel'),
  onQrStatus: (cb) => {
    const fn = (_e, s) => cb(s)
    ipcRenderer.on('adb-qr-status', fn)
    return () => ipcRenderer.removeListener('adb-qr-status', fn)
  },
  camera: (serial) => ipcRenderer.invoke('camera', serial),
  openDownloads: () => ipcRenderer.invoke('open:downloads'),
  wifiStatus: () => ipcRenderer.invoke('wifi:status'),
  wifiPayload: () => ipcRenderer.invoke('wifi:payload'),
  wifiSendClip: () => ipcRenderer.invoke('wifi:sendClip'),
  wifiToggleNotif: () => ipcRenderer.invoke('wifi:toggleNotif'),
  smsThreads: () => ipcRenderer.invoke('sms:threads'),
  smsMessages: (threadId) => ipcRenderer.invoke('sms:messages', threadId),
  smsSend: (address, text) => ipcRenderer.invoke('sms:send', address, text),
  mediaCmd: (cmd, value) => ipcRenderer.invoke('media:cmd', cmd, value),
  onMedia: (cb) => {
    const fn = (_e, m) => cb(m)
    ipcRenderer.on('media', fn)
    return () => ipcRenderer.removeListener('media', fn)
  },
  onSmsChanged: (cb) => {
    const fn = () => cb()
    ipcRenderer.on('sms-changed', fn)
    return () => ipcRenderer.removeListener('sms-changed', fn)
  },
  // notifications (data already streams over the wifi link)
  onNotification: (cb) => {
    const fn = (_e, n) => cb(n)
    ipcRenderer.on('notification', fn)
    return () => ipcRenderer.removeListener('notification', fn)
  },
  onNotificationRemoved: (cb) => {
    const fn = (_e, n) => cb(n)
    ipcRenderer.on('notification-removed', fn)
    return () => ipcRenderer.removeListener('notification-removed', fn)
  },
  notifReply: (key, text) => ipcRenderer.invoke('notif:reply', key, text),
  notifDismiss: (key) => ipcRenderer.invoke('notif:dismiss', key),
  // device-info pushed by the companion (app-link transport)
  onDeviceInfo: (cb) => {
    const fn = (_e, info) => cb(info)
    ipcRenderer.on('device-info', fn)
    return () => ipcRenderer.removeListener('device-info', fn)
  },
  // settings
  settingsGet: () => ipcRenderer.invoke('settings:get'),
  settingsSet: (key, value) => ipcRenderer.invoke('settings:set', key, value),
  // contacts (served by the Android app)
  contactsList: () => ipcRenderer.invoke('contacts:list'),
  callContact: (number) => ipcRenderer.invoke('contact:call', number),
  smsContact: (number) => ipcRenderer.invoke('contact:sms', number),
  // photos (over adb)
  photosList: () => ipcRenderer.invoke('photos:list'),
  photosThumb: (item) => ipcRenderer.invoke('photos:thumb', item),
  photosPull: (item) => ipcRenderer.invoke('photos:pull', item),
  photosOpen: (item) => ipcRenderer.invoke('photos:open', item),
  onWifi: (cb) => {
    const fn = (_e, s) => cb(s)
    ipcRenderer.on('wifi', fn)
    return () => ipcRenderer.removeListener('wifi', fn)
  },
  onWifiEvent: (cb) => {
    const fn = (_e, ev) => cb(ev)
    ipcRenderer.on('wifi-event', fn)
    return () => ipcRenderer.removeListener('wifi-event', fn)
  },
  // device volume (ADB)
  volumeGet: () => ipcRenderer.invoke('volume:get'),
  volumeSet: (level, currentLevel) => ipcRenderer.invoke('volume:set', level, currentLevel),
  // call control
  callEnd: () => ipcRenderer.invoke('call:end'),
  callSpeaker: () => ipcRenderer.invoke('call:speaker'),
  callMute: () => ipcRenderer.invoke('call:mute'),
  callDtmf: (digit) => ipcRenderer.invoke('call:dtmf', digit),
  callStartPolling: (serial) => ipcRenderer.invoke('call:startPolling', serial),
  onCallState: (cb) => {
    const fn = (_e, s) => cb(s)
    ipcRenderer.on('call-state', fn)
    return () => ipcRenderer.removeListener('call-state', fn)
  },
  filePath: (file) => webUtils.getPathForFile(file)
})
