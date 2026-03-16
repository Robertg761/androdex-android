<p align="center">
  <img src="assets/feature-graphic-1024x500.png" alt="Relaydex banner" />
</p>

# Relaydex

[English README](README.md)

Relaydex は、[Remodex](https://github.com/Emanuele-web04/remodex) をベースにした独立 fork です。目的はひとつに絞っています。

- Windows 上でローカル Codex を動かす
- `relaydex up` でローカル bridge を起動する
- Android からその Codex セッションを remote control する

Codex 本体はホスト PC 上で動き続け、Android アプリはペア済みの remote client として動作します。

## このリポジトリについて

このリポジトリは monorepo です。

- `phodex-bridge/`: npm 配布用の CLI bridge
- `android/`: Android クライアント本体
- `CodexMobile/`: upstream iOS 実装。プロトコル参照と互換確認用

現状の既定フローでは、relay として `api.phodex.app` を利用します。必要なら互換 relay の self-host も可能です。

## クレジットと立場

Relaydex は [Remodex](https://github.com/Emanuele-web04/remodex) の独立 fork です。元プロジェクトの作者は Emanuele Di Pietro です。

- これは公式 Remodex アプリではありません
- upstream 作者の公認やサポートを示すものではありません
- Windows host + Android remote-control 体験に特化して再構成しています

## これは何か

Relaydex は、スマホ上で Codex そのものを動かすアプリではありません。

- ローカル bridge と Codex はホスト PC 上で動きます
- Android はペア済み remote client として動きます
- git 操作や workspace 変更もホスト側で実行されます

## 現在の公開方針

- Windows 側 bridge は `relaydex` として npm 配布済み
- Android アプリの公式 Play 配布は準備中
- 試したい人は `android/` から自分でビルドして動かせます
- 後日の Play 配布に興味がある人は、Google Group の waitlist に参加できます

つまり、現時点では「リポジトリは公開」「公式 Android 配布は後で公開」という構成です。

## いま試す方法

まず Windows 側では次の流れです。

```sh
npm install -g relaydex
relaydex up
```

そのあと Android アプリで QR を読み込みます。

Android アプリを今すぐ試したい場合は、[Docs/ANDROID_BUILD_FROM_SOURCE.md](Docs/ANDROID_BUILD_FROM_SOURCE.md) を見て source build してください。

## Windows + Android クイックスタート

[Docs/WINDOWS_ANDROID_QUICKSTART.md](Docs/WINDOWS_ANDROID_QUICKSTART.md) を参照してください。

短い流れは次のとおりです。

1. Windows に Node.js と Codex CLI を入れる
2. `relaydex` をインストールする
3. ローカル project ディレクトリで `relaydex up` を実行する
4. Android アプリを開く
5. QR か pairing payload で接続する

## Android アプリを自分でビルドする

Play 配布前でも、Android Studio か Gradle で自分でビルドして試せます。

- source build 手順: [Docs/ANDROID_BUILD_FROM_SOURCE.md](Docs/ANDROID_BUILD_FROM_SOURCE.md)
- Android project: [`android/`](android/)

## Android クローズドテストについて

Google Play 上の公式 Android 配布はまだ準備中です。クローズドテスト募集を本格的に始める前に、連絡先や組織情報の整備を進めています。

いま公開している主な導線:

- クローズドテスト案内ページ: `https://ranats.github.io/relaydex/closed-test.html`
- Google Group waitlist: `https://groups.google.com/g/relaydex-android-testers`
- GitHub Issues: `https://github.com/Ranats/relaydex/issues`

クローズドテスト中の build は無料配布前提で運用し、本番 Android 版は後で有料化する想定です。

大事な点:

- 今 Google Group に参加すること自体は、Play の 12 人 / 14 日条件の達成にはまだ直接カウントされません
- その条件を進めるには、後日あらためて Play 上で opt-in とインストールをしてもらう必要があります
- いま試したい人は source build が最短です

## フィードバックの送り先

試した内容や不具合は、次のどちらかに送ってください。

- GitHub Issues: `https://github.com/Ranats/relaydex/issues`
- サポートメール: `saxophonia991@gmail.com`

あると助かる情報:

- 端末機種と Android バージョン
- `relaydex up` で接続できたか
- QR / payload / reconnect のどれを使ったか
- どの画面で詰まったか
- 再現手順
- スクリーンショット

## Android アプリでできること

現在の Android クライアントが対応している機能:

- QR pairing
- pairing payload の手入力
- スレッド一覧
- 既存スレッドの表示
- 新規スレッド作成
- プロンプト送信
- ストリーミング出力表示
- approval prompt への応答
- saved pairing からの reconnect
- モデルと reasoning 設定

## セキュリティ

モバイル client と bridge は、upstream Remodex と同じ E2EE セッションモデルを使います。wire 互換を保つため、内部フィールド名に `mac` や `iphone` が残る箇所がありますが、これはプロトコル上の名残であり、実際の platform 制約ではありません。

## 公開前に見ておく資料

- [Docs/ANDROID_FORK_GUIDE.md](Docs/ANDROID_FORK_GUIDE.md)
- [Docs/ANDROID_BUILD_FROM_SOURCE.md](Docs/ANDROID_BUILD_FROM_SOURCE.md)
- [Docs/CLOSED_TEST_PLAN.md](Docs/CLOSED_TEST_PLAN.md)
- [Docs/WINDOWS_ANDROID_QUICKSTART.md](Docs/WINDOWS_ANDROID_QUICKSTART.md)
- [Docs/LAUNCH_COPY.md](Docs/LAUNCH_COPY.md)
- [Docs/PLAY_STORE_COPY.md](Docs/PLAY_STORE_COPY.md)
- [Docs/PLAY_CONSOLE_SETUP.md](Docs/PLAY_CONSOLE_SETUP.md)
- [Docs/PRIVACY_POLICY.md](Docs/PRIVACY_POLICY.md)
- [Docs/TESTER_RECRUITMENT_COPY.md](Docs/TESTER_RECRUITMENT_COPY.md)
- [Docs/RELEASE_CHECKLIST.md](Docs/RELEASE_CHECKLIST.md)

## ライセンス

[ISC](LICENSE)
