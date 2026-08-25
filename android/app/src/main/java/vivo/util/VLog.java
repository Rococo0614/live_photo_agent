package vivo.util;

import android.util.Log;

/**
 * vivo 内部日志类的兼容 stub。
 *
 * 背景：videoeditorsdk 编译时链接了 vivo.util.VLog，但该类既不在 AAR 内，
 * 也不在本机 /system/framework/vivo-framework.jar 中（已用 dex 反查确认）。
 * 因此 SDK 任何触及日志的路径都会抛
 *     NoClassDefFoundError: Failed resolution of: Lvivo/util/VLog;
 *
 * 由于它不在 bootclasspath 上，App 自带同名类不会与系统冲突，
 * 直接转发到 android.util.Log 即可。
 *
 * 方法集合是从 SDK 字节码常量池反查出来的实际调用签名，勿随意增删。
 */
public final class VLog {

    private VLog() {
    }

    public static int v(String tag, String msg) {
        return Log.v(tag, msg);
    }

    public static int d(String tag, String msg) {
        return Log.d(tag, msg);
    }

    public static int i(String tag, String msg) {
        return Log.i(tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return Log.i(tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        return Log.w(tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return Log.w(tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        return Log.e(tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return Log.e(tag, msg, tr);
    }
}
