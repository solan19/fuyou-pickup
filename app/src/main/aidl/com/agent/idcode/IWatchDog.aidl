// 看门人（独立特权进程）对外接口
//
// ★ 为什么用 AIDL：WatchUserService 跑在 Shizuku 拉起的**独立进程**里（UID 2000 shell / 0 root），
//   主进程拿到的是 BinderProxy，不能强转成本地类 —— 跨进程调用必须走 AIDL（或手写 transact）。
//
// ★ 为什么是 setToken 而不是让看门人自己去读：看门人以 shell UID 运行，
//   **读不到** /data/data/com.agent.idcode/shared_prefs（属主是 App UID，权限 700）。
//   所以令牌由主进程在绑定成功时经 Binder 传进来，看门人只把它存在自己进程的内存里 ——
//   这样一来令牌既不落外部存储（不外泄），主进程真死了看门人也还握着它。
package com.agent.idcode;

interface IWatchDog {
    /** 主进程把复活令牌交给看门人（仅存内存，不落盘） */
    void setToken(String token);

    /** 心跳：确认看门人还活着 */
    void ping();

    /** 立即执行一次检查 + 必要时复活，返回 0 表示已发送 */
    int recover(String pkg);

    /**
     * ★ vc303（格口码查询）：以 **shell 权限**执行 /system/bin/input 命令。
     *   为什么必须由这个进程来做：input 注入触摸/文本需要 INJECT_EVENTS，
     *   **普通 App 拿不到**，而本进程由 Stellar 以 UID 2000(shell)/0(root) 拉起 ⇒ 有这个权限。
     *   ⇒ 不需要开无障碍，也不需要 adb 连电脑 —— 复用同志已授权的 Stellar 即可。
     *
     * @param args 形如 ["text","JT..."] / ["tap","660","554"] / ["keyevent","66"]
     * @return 0 成功；非 0 = 失败（入参为空 / 特权不足 / 执行异常）
     */
    int injectInput(in String[] args);

    /** 查本进程实际 UID（诊断用：确认是否真以 shell 特权运行） */
    int selfUid();
}
