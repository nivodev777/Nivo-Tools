package com.nivodev.nivotools.ui.tools.alarm;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Best-effort jumps into the screens that unblock automatic ring pop-ups:
 * OEM auto-start managers (MIUI/HyperOS, EMUI, ColorOS, OriginOS...) first,
 * then the notification settings with the full-screen intent toggle.
 */
public final class AlarmReliability {
    private AlarmReliability() {
    }

    /** True when the ring screen may not lift over the lock screen right now. */
    public static boolean needsSetup(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm = (NotificationManager)
                        c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        return false;
    }

    /** Opens the most useful settings page available on this device. */
    public static void openSettings(Context c) {
        if (tryOemAutostart(c)) {
            return;
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Throwable t) {
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
        }
    }

    /** Known OEM auto-start screens; works only when the OEM skin is installed. */
    private static boolean tryOemAutostart(Context c) {
        String[][] entries = {
                {"com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
        };
        for (String[] e : entries) {
            try {
                Intent in = new Intent()
                        .setComponent(new ComponentName(e[0], e[1]))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (c.getPackageManager().resolveActivity(in, 0) != null) {
                    c.startActivity(in);
                    return true;
                }
            } catch (Throwable t) {
            }
        }
        return false;
    }
}
