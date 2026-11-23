# 问题报告云函数（腾讯云）

用户在「上次意外退出了 / 运行中出了问题」弹窗里点「发送给开发者」后，报告会发到这个云函数。云函数做三件事：

1. 存进对象存储（COS），每份报告一个文本文件，按日期分目录；
2. 推送到你的微信；
3. 把报告编号回给 App，用户反馈时报这个编号就能找到对应文件。

App 里没有任何腾讯云密钥。云函数用自己的执行角色写 COS，App 只知道一个公开地址和一个口令。只用 Python 标准库，不需要安装依赖。

## 一、对象存储（COS）

1. 控制台 → 对象存储 → 存储桶列表 → 创建存储桶。
   - 名称：例如 `photoall-reports`，系统会自动在后面加上 APPID，变成 `photoall-reports-125xxxxxxx`。
   - 所属地域：例如广州 `ap-guangzhou`，云函数也要建在这个地域。
   - 访问权限：**私有读写**。
2. 记下完整桶名（带 APPID）和地域代码。

## 二、执行角色（让云函数能写这个桶）

1. 控制台 → 访问管理 → 策略 → 新建自定义策略 → 按策略语法创建，粘贴下面的内容，把桶名、地域、APPID 换成你的：

```json
{
  "version": "2.0",
  "statement": [{
    "effect": "allow",
    "action": ["name/cos:PutObject"],
    "resource": ["qcs::cos:ap-guangzhou:uid/125xxxxxxx:photoall-reports-125xxxxxxx/*"]
  }]
}
```

2. 访问管理 → 角色 → 新建角色 → 腾讯云产品服务 → 云函数（SCF）→ 关联上面那条策略，起个名字，例如 `PhotoAllReport`。

这个角色只能往这个桶里写文件，读不了，也删不了。

## 三、微信推送（二选一）

- **Server酱**：打开 sct.ftqq.com，用微信扫码登录，在 SendKey 页面复制以 `SCT` 开头的那串。推送会通过「方糖」服务号发到你的微信。
- **企业微信群机器人**：在企业微信里建一个群 → 添加群机器人 → 复制它的 Webhook 地址。

同一类问题（相同原因、相同出错位置、相同机型）一天里只在第 1、5、20、100 份时推送，不会刷屏；每一份都会存进 COS。

## 四、云函数

1. 在本目录运行 `python pack.py`，生成 `report.zip`。这个脚本会给 `scf_bootstrap` 设好可执行权限、统一成 LF 换行，在 Windows 上直接压缩不行。
2. 控制台 → 云函数 → 函数服务 → 新建：
   - 从头开始；函数类型选 **Web 函数**。
   - 地域：和存储桶相同。
   - 运行环境：**Python 3.10**（3.9 也可以）。
   - 函数代码：本地上传 zip 包 → 选 `report.zip`；监听端口 9000。
   - 高级配置：
     - 启动命令：选「自定义模板」，内容和 `scf_bootstrap` 一样（控制台会用这里的内容覆盖包里的那份）。
     - 执行角色：启用，选 `PhotoAllReport`。
     - 内存 128 MB，执行超时 15 秒。
     - 环境变量：

| 键 | 值 |
|---|---|
| `COS_BUCKET` | `photoall-reports-125xxxxxxx` |
| `COS_REGION` | `ap-guangzhou` |
| `SERVERCHAN_KEY` | `SCT...`（用企业微信就改填 `WECOM_WEBHOOK`） |
| `APP_TOKEN` | 自己编一串随机字符，例如 `pa-7f3k9q2m` |
| `DAILY_LIMIT` | 可选，默认 500 |
| `PER_IP_LIMIT` | 可选，默认 30 |

3. 创建完成后，在「函数 URL」（或「触发管理」）里开启公网访问，鉴权方式选「不鉴权」，然后复制访问地址（`https://....`）。

## 五、先测一下

把下面的地址和口令换成你的，在电脑上运行：

```bash
curl -X POST "https://你的函数地址" -H "X-PhotoAll-Token: pa-7f3k9q2m" -H "Content-Type: application/json" -d "{\"title\":\"测试\",\"text\":\"Photo All 问题报告 · 测试\\n手机：测试机\"}"
```

- 返回 `{"id": "...."}`，说明已经通了。COS 里会出现 `日期/编号.txt`，微信会收到一条推送。
- 返回 403：口令不对。
- 返回 502：写 COS 失败，去检查执行角色和桶名。可以在云函数的「日志查询」里看到 `cos failed:` 后面的原因。

## 六、写进 App

在 `android/local.properties` 里加两行（这个文件不提交到仓库）：

```
report.url=https://你的函数地址
report.token=pa-7f3k9q2m
```

然后重新打包。没填 `report.url` 的版本没有发送按钮，和以前一样只能复制。

## 本地调试

```bash
DRY_RUN=1 APP_TOKEN=test python app.py
```

服务监听在 9000 端口，报告写进 `./out`，不碰 COS，也不推送。在手机或模拟器上先运行 `adb reverse tcp:9000 tcp:9000`，再把 `report.url` 填成 `http://127.0.0.1:9000/`，App 就能发到这台电脑上。App 只允许对 127.0.0.1 和 localhost 用明文 HTTP。

## 费用

云函数和 COS 都有每月免费额度。每天几百份报告、每份几十 KB，基本落在免费额度里。
