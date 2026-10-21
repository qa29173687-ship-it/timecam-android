# 時間相機 Android（TimeCam）

給中和安邦社宅報修現場使用的浮水印相機（Kotlin + CameraX）。

- 照片／錄影切換，浮水印（社宅名稱、時間、日期星期、案號、棟別樓層、備註）直接燒錄進照片與影片
- 縮放（雙指／滑桿）、點擊對焦測光、曝光補償、閃光燈（自動/開/關）、手電筒、前後鏡頭、HDR／夜間（裝置支援時）
- 儲存至 DCIM/時間相機，檔名如 `20261021_101402_A-1023.jpg`

建置：`./gradlew assembleRelease`（簽章設定放在未納入版控的 `keystore/keystore.properties`）。
