# 发版流程

这份文档记录从改完代码到两个平台都能下载到 APK 的完整步骤，以及踩过的坑。
目标是：**任何一次发版都走同一条路，两个平台的标签、版本号、安装包完全对应。**

## 0. 版本号约定

- 格式 `MAJOR.MINOR.PATCH`，只增不减，如 `1.1.0` → `1.1.1` → `1.2.0`。
- **git tag 必须与 `versionName` 完全一致**：tag `v1.1.0` ↔ `versionName "1.1.0"`。
- `versionCode` 每次发版 +1，只能是整数且单调递增（Android 靠它判断升级）。
- 两个平台用**同一套 tag**。不要再出现 Gitee 用 `v1.0`、GitHub 用 `v1.0.0` 这种混用——之前就是这么乱掉的，最后只能删号重建。

## 1. 改版本号

`app/build.gradle`：

```gradle
defaultConfig {
    versionCode 3          // 每次 +1
    versionName "1.2.0"    // 与 tag 一致
}
```

## 2. 构建 + 逻辑回归

```bash
# 构建
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug

# 纯 JVM 逻辑用例（车牌解析 / CSV 容错导入），不依赖模拟器
javac -encoding UTF-8 -d build/test-classes \
  app/src/main/java/com/platelookup/{PlateParser,CsvImporter,Vehicle}.java \
  ../tools/PlateParserTest.java
java -Dfile.encoding=UTF-8 -cp build/test-classes PlateParserTest
```

改动涉及识别、导入、查询这三条主链路时，再起模拟器测一遍：

```bash
emulator -avd test34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.platelookup/.MainActivity
```

最低检查项：

- [ ] 冷启动不崩、相机预览正常出画
- [ ] 完整车牌命中 → 显示姓名/手机号
- [ ] 无手机号记录 → 姓名正常、手机号「无信息」、拨打按钮隐藏
- [ ] 未登记车牌 → 「本地库中无此车牌」
- [ ] 手动输入部分车牌（如 `23456`）→ 唯一命中直接显示；`23` → 弹出选择框
- [ ] CSV 导入含空手机号 / 脏车牌 → 人数与名单一致，提示新增/更新/无号/跳过

debug 包可以用图片跑一次识别，不用真对着车牌：

```bash
adb push plate.png /data/local/tmp/plate.png
adb shell am start -n com.platelookup/.MainActivity -e test_image /data/local/tmp/plate.png
```

## 3. 产物与校验值

```bash
cp app/build/outputs/apk/debug/app-debug.apk ../dist/chepai-chaxun-v1.2.0-debug.apk
stat -c%s ../dist/chepai-chaxun-v1.2.0-debug.apk      # 字节数，写进发行说明
sha256sum ../dist/chepai-chaxun-v1.2.0-debug.apk      # 校验值，写进发行说明
```

发行说明存 `dist/RELEASE_NOTES_v1.2.0.md`，结构参考已有文件：本次改了什么 → 安装说明 → 校验信息表 → 兼容性。

## 4. 提交、打 tag、推两个远端

```bash
git add -A && git commit -m "feat: v1.2.0 说明"
git tag -a v1.2.0 -m "v1.2.0 一句话说明"
git push origin master && git push origin v1.2.0      # Gitee
git push github master && git push github v1.2.0      # GitHub
```

远端别名：`origin` = Gitee，`github` = GitHub。GitHub 推不了 HTTPS（本机代理对 `github.com:443` 返回 502），走 SSH：

```bash
GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_github -o IdentitiesOnly=yes" git push github master
```

推完核对两边标签与提交是否一致：

```bash
git ls-remote --tags origin && git ls-remote --tags github
```

## 5. 建发行版并挂 APK

### 5.1 网页操作（不想给令牌就走这条）

- GitHub：仓库 → Releases → Draft a new release → 选 tag → 填标题、粘说明 → 上传 APK → Publish
- Gitee：仓库 → 发行版 → 新建发行版 → 选 tag → 填标题、粘说明 → 上传附件 → 发布

### 5.2 命令行（批量、可复现）

令牌**只放在环境变量里**，不要写进命令、脚本或仓库：

```bash
# ---- GitHub ----
export GH_TOKEN=...        # 需要 repo 权限
curl -s -X POST -H "Authorization: token $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/jaspermzx/chepai-chaxun/releases \
  -d @release.json                       # {"tag_name","name","body","target_commitish":"master"}

curl -s -X POST -H "Authorization: token $GH_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @../../dist/chepai-chaxun-v1.2.0-debug.apk \
  "https://uploads.github.com/repos/jaspermzx/chepai-chaxun/releases/<release_id>/assets?name=chepai-chaxun-v1.2.0-debug.apk"

# ---- Gitee ----
export GT_TOKEN=...        # 需要 projects 权限
curl -s -X POST "https://gitee.com/api/v5/repos/jaspermzx/chepai-chaxun/releases" \
  -d "access_token=$GT_TOKEN" -d "tag_name=v1.2.0" -d "target_commitish=master" \
  --data-urlencode "name=v1.2.0 说明" --data-urlencode "body@../../dist/RELEASE_NOTES_v1.2.0.md"

curl -s -X POST "https://gitee.com/api/v5/repos/jaspermzx/chepai-chaxun/releases/<release_id>/attach_files" \
  -F "access_token=$GT_TOKEN" -F "file=@../../dist/chepai-chaxun-v1.2.0-debug.apk"
```

## 6. 发布后自查

必须做，别只看接口返回 200：

```bash
# 1) 从平台下载安装包，与本地构建比对哈希
curl -sL -o /tmp/check.apk "<发行版里的 APK 直链>"
sha256sum /tmp/check.apk ../dist/chepai-chaxun-v1.2.0-debug.apk    # 两者应完全一致

# 2) latest 指向是否为新版本
curl -s https://api.github.com/repos/jaspermzx/chepai-chaxun/releases/latest | grep tag_name

# 3) 标签指向的提交是否正确
git ls-remote --tags origin
```

同时更新 `README.md` 下载章节里的版本号与 SHA-256。

## 7. 令牌安全

- 令牌用完立刻去平台撤销：GitHub <https://github.com/settings/tokens>，Gitee <https://gitee.com/profile/personal_access_tokens>。
- 不要把令牌写进 `README`、脚本、`.git/config`、提交历史。临时文件用完删除。
- 只开最小权限：GitHub `repo`（建发行版/传附件够用），Gitee `projects`。

## 附：踩过的坑

| 现象 | 原因 | 做法 |
| --- | --- | --- |
| `git push` 到 GitHub 报 `CONNECT tunnel failed, response 502` | 本机代理不放行 `github.com:443` | 改用 SSH 推送；或 `--noproxy` 直连 |
| `POST /user/keys` 返回 404 | classic 令牌只有 `repo`，加用户级 SSH 公钥需要 `admin:public_key` | 改登记成**仓库级 Deploy Key**（`repo` 权限即可） |
| Gitee `DELETE /tags/{name}` 返回 404 | 该接口不好用 | `git push origin --delete <tag>` |
| Gitee 发行版的 tag 写错了想改 | 发行版的 tag 不支持修改 | 只能删掉发行版重建（附件会一起没，先备份 APK） |
| 上传后平台显示的大小比本地大几十字节 | multipart 表单开销 | 不算问题，下载回来算 SHA-256 确认即可 |
| `aapt2` 报 `'compat' is incompatible with implementationMode` | 枚举值是 `compatible` | 改 `app:implementationMode="compatible"` |
| 编译报 `Duplicate class kotlin.collections.jdk8.*` | ML Kit 的 kotlin-stdlib 1.8.22 与 CameraX 的 jdk7/jdk8 1.6.0 冲突 | `resolutionStrategy.force` 统一到 1.8.22 |
| 样式名 `Text.Label` 报 `resource style/Text not found` | 点号被当成继承父样式 | 样式名不要用点号 |
| 用 `adb shell input text` 只输入了前两位 | 键盘动画期间丢字符 | 逐位输入或先清空再输 |
| 移除网络权限后日志出现 `TransportRuntime ... ACCESS_NETWORK_STATE` | ML Kit 自带的遥测尝试调度上传，被权限挡住 | 预期行为，不影响识别 |
