# NITTC Scheduler



鶴岡高専用時間割・課題管理アプリ(Android)



## 概要

鶴岡高専では、普通の時間割に加え、A授業日とB授業日が決まっており、今日はAなのかBなのかを確認しないと今日はなにの授業があるのかがわかりません

なので、A/B日の表と時間割を入力することで、当日の時間割を出力するアプリを開発しました

~~NITTCは8校あるとか言わないでください リリースしてから気づきました~~



### 機能

* 時間割管理 (教科・担当教員・授業場所)
* 時間割と紐づけた課題・予定管理
* 時間割様式の設定機能 (コマ数や授業時間等を設定できるため、高専だけでなく一般校でも使用可能です)

### 実験的機能

* ローカルAIを使用して、画像から時間割を自動インポート (Gemma4, SmolVLM2, Qwen3.5が使用可能)
* カレンダーへ課題期日・授業時間を追加



## ローカルAIについて

このアプリはローカルAIを使うことができますが、ローカルAIはCPUリソース・RAMを大量に使用するため、使う際は使用するスマホのスペックをよく確認して使用してください

最低動作スペックは**RAM8GB以上・Snapdragon 7s Gen 3以上**、推奨スペックは**RAM12GB以上・Snapdragon 8+ Gen 1以上**です



また、モデルによってはダウンロードするためにHugging Faceのアクセストークン(ReadのみでOK)が必要です

なお、このアクセストークンはAIモデルをダウンロードするためだけに使用し、それ以外の用途には使用しません。



## ライセンス

MIT Licenseです

好きにいじくってもらって構いません



## 動作確認済みの機種

* Nothing Phone (2) Pong-B4.0-260226-0955
* Nothing Phone (3) Metroid-B4.0-260206-1135-JPN

## 動作イメージ
<img width="300" alt="HGLdI_la0AAvCw8" src="https://github.com/user-attachments/assets/adbde189-2862-4e6a-87ad-ab11743a8b12" />
<img width="300" alt="HGLdJEXbYAEnZYY" src="https://github.com/user-attachments/assets/971ccfcc-b59d-48e5-895b-2d4da1778b91" />
<img width="300" alt="HGLdJHIa4AAood3" src="https://github.com/user-attachments/assets/79d464d3-b98d-482c-86f0-51db5f25ee1d" />
<img width="300" alt="HGLdJEraEAAY6SM" src="https://github.com/user-attachments/assets/5353da59-663c-4d23-9372-4bf0f1cd7417" />







## Custom fork features

このカスタマイズフォークでは、MEGAアカウントへ直接ログインして行うアプリ単体の同期、最大5件の時程プリセット、授業開始前2件＋終了前2件の通知カスタマイズ、通知テンプレート変数、土曜授業のオン／オフに対応しています。MEGA同期では授業時刻を含むフルJSONデータを同期し、本家のSKTTP / Wi-Fi / Nearby / 信頼済み端末による同期実装は削除せず設定画面に残しています。

## MEGA standalone sync (custom fork)

利用者はアプリ内でMEGAのメールアドレス／パスワード（2段階認証を利用している場合は認証コード）を入力するだけで同期できます。AndroidのファイルピッカーやMEGAアプリは不要です。パスワードは保存せず、ログイン成功後はMEGAのセッショントークンを保存して次回以降のログインに再利用します。

メイン画面の同期ボタン／同期メニューはMEGA同期画面を開きます。本家のローカルWi-Fi / Nearby / 信頼済み端末同期は、設定の「旧・端末間同期」から引き続き利用できます。

同期ファイルはMEGA内の `/NITTC Scheduler/scheduler-sync.json` に保存します。アプリ起動中はローカルDBの変更を検知して即時同期し、MEGA側のノード更新も監視します。バックグラウンドではWorkManagerによる定期同期をフォールバックとして使用します。

Developer setup:

公式MEGA Android SDK AARを `app/libs/mega-sdk.aar` に配置します。このリポジトリには、固定した公式MEGA SDK v10.19.0から生成したAARを同梱しています。v10.19.0では従来のコンストラクタ引数としてApp Keyが残っていますが、SDK内部では使用されないため、Application Keyの登録やビルド設定は不要です。

MEGA SDKの実行はAndroid 9以降で有効にしています。NITTC Scheduler本体の既存minSdkは変更していません。

## Manual Android release

GitHub Actionsの `Build and release Android app` を手動実行すると、指定したVersion Name / Version Codeでテスト、Lint、署名付きRelease APKのビルド、署名とバージョンの検証、GitHub Releaseへの公開を一度に実行します。

初回実行前に、リポジトリのActions secretsへ次を登録してください。

* `NITTC_RELEASE_KEYSTORE_BASE64`（リリース用JKSをBase64化した値）
* `NITTC_RELEASE_STORE_PASSWORD`
* `NITTC_RELEASE_KEY_ALIAS`
* `NITTC_RELEASE_KEY_PASSWORD`

同じVersion Nameのリリースタグが既に存在する場合や、必須secretが不足している場合は公開せずに失敗します。
