# -*- coding: utf-8 -*-
"""生成/更新仓库根的 update.json —— **版本分发的唯一入口**。

★★ 发布闸门：只有用户（同志）明确说「分发这个版本 / 发布更新」时才允许运行，
   且必须显式带 --confirm。平时代码照常提交，但这个文件保持上一次已确认的版本。
   本脚本**只写文件**，不会自动 git commit / push；推送也需用户确认。

用法示例（版本号必须与 app/build.gradle.kts 一致；APK 放仓库 update/ 目录）：
  python tools/publish_update.py --confirm \
      --version-code 745 --version-name 1.4.26 \
      --apk update/fuyou-1.4.26.apk \
      --apk-url "https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update/fuyou-1.4.26.apk" \
      --notes "写明本版到底改了什么" \
      --force

字段说明（App 端 UpdateChecker 读同一份 schema）：
  schema       清单格式版本，当前 1
  published    false = 未分发（App 显示"暂未发布更新"）；true = 已分发
  versionCode  整数，App 用 longVersionCode 数值比较，不做字符串比较
  apkUrl       APK 的公开下载地址；私有仓库的 raw 地址没有 token 读不到，
               分发时请确保已安装用户能访问（公开仓库 / Release / 自有静态托管）
  sha256       已签名 APK 的 SHA-256，App 下载后校验，不一致拒绝安装
  force        是否强制更新。**不弹窗**：只让关于页该行显示红色「必须更新」常驻态
  minSupport   低于此 versionCode 的旧版本才提示（0 = 不限制）
  publishedAt  ISO 时间，仅展示用
"""
import argparse
import datetime
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "update.json")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--confirm", action="store_true", help="必须显式确认才允许分发")
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--apk", required=True, help="已签名的 APK 路径（用于算 SHA-256）")
    ap.add_argument("--apk-url", required=True, help="已安装用户可访问的 APK 下载地址")
    ap.add_argument("--notes", default="")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--min-support", type=int, default=0)
    ap.add_argument("--unpublish", action="store_true", help="撤回分发（published=false）")
    a = ap.parse_args()

    if not a.confirm:
        print("REFUSED: 未带 --confirm。版本分发必须由用户主动确认。", file=sys.stderr)
        sys.exit(2)
    if not os.path.isfile(a.apk):
        print("REFUSED: APK 不存在: " + a.apk, file=sys.stderr)
        sys.exit(2)
    if not a.unpublish and not a.apk_url.startswith("https://"):
        print("REFUSED: apk-url 必须是 https:// 且已安装用户可访问", file=sys.stderr)
        sys.exit(2)

    data = {
        "schema": 1,
        "published": not a.unpublish,
        "versionCode": a.version_code,
        "versionName": a.version_name,
        "apkUrl": "" if a.unpublish else a.apk_url,
        "sha256": sha256_of(a.apk),
        "notes": a.notes,
        "force": bool(a.force),
        "minSupport": a.min_support,
        "publishedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("已生成 " + OUT)
    print(json.dumps(data, ensure_ascii=False, indent=2))
    print("注意：脚本未提交、未推送。请人工确认后由助手 commit + push，分发才生效。")


if __name__ == "__main__":
    main()
