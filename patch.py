import os
import sys

TARGET_FILE = "app/src/main/java/xiao/bu/tv/HlsProxyServer.java"

if not os.path.exists(TARGET_FILE):
    print(f"[!] 找不到文件: {TARGET_FILE}，请确保在 NativeWasmTv2 根目录下运行")
    sys.exit(1)

with open(TARGET_FILE, "r", encoding="utf-8") as f:
    content = f.read()

# 修改点 1：移除普通流的预取限制
old_1 = """            // Clear media streams directly to IJK. Whole-body prefetch both delays
            // its first bytes and rejects 27-45 MiB UHD segments at the memory cap.
            if(!hasAesSegmentKey(url)&&!needsCjsTransform(url)&&canStreamWithoutRewrite(url))continue;"""
new_1 = """            // 【修改】放开对普通流的预取限制，让代理提前把切片下到内存"""

# 修改点 2：预取失败安全降级
old_2 = """            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("HLS segment prefetch failed", cause);"""
new_2 = """            // 【修改】预取失败（比如切片太大或网络超时），返回 null，让播放器回退到边下边播
            Log.w(TAG, "HLS segment prefetch failed, falling back: " + segmentName(originUrl), error.getCause());
            return null;"""

# 修改点 3：加大预取数量 (2 -> 3)
old_3 = """            prefetchGenericSegments(mediaSegments, 2 + adaptiveBufferLevel);"""
new_3 = """            prefetchGenericSegments(mediaSegments, 3 + adaptiveBufferLevel);"""

changed = False
if old_1 in content:
    content = content.replace(old_1, new_1)
    print("[+] 成功修改：放开普通流预取限制")
    changed = True
else:
    print("[-] 未找到修改点 1（放开普通流预取限制），可能代码已经变过")

if old_2 in content:
    content = content.replace(old_2, new_2)
    print("[+] 成功修改：预取失败安全降级")
    changed = True
else:
    print("[-] 未找到修改点 2（预取失败安全降级）")

if old_3 in content:
    content = content.replace(old_3, new_3)
    print("[+] 成功修改：预取数量 2 改为 3")
    changed = True
else:
    print("[-] 未找到修改点 3（预取数量调整）")

if changed:
    with open(TARGET_FILE, "w", encoding="utf-8") as f:
        f.write(content)
    print("[*] 文件保存成功！")
else:
    print("[!] 没有任何代码被修改，请检查。")
