package local.honor.transparentkeyboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.content.SharedPreferences;

/** Exposes visual parameters only. Mutations stay private to the module UI. */
public final class SettingsProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://local.honor.transparentkeyboard.settings/config");
    public static final String PREFS = "visual_settings";
    static volatile String status = "等待输入法连接";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if ("get".equals(method)) {
            SharedPreferences p = getContext().getSharedPreferences(PREFS, 0);
            out.putInt("degree", p.getInt("degree", 60));
            out.putInt("material", p.getInt("material", 0));
            out.putInt("blur", p.getInt("blur", -1));
            out.putInt("keys", p.getInt("keys", 45));
            out.putInt("glass", p.getInt("glass", 60));
            out.putLong("restart", p.getLong("restart", 0));
            out.putInt("hwWash", p.getInt("hwWash", 0));
            out.putString("status", status);
        } else if ("report".equals(method)) {
            String[] pkgs = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (pkgs != null) for (String pkg : pkgs) if ("com.baidu.input_hihonor".equals(pkg)) {
                status = arg == null ? "已连接" : arg;
                break;
            }
        } else if ("debugHwWash".equals(method)) {
            // v1.7 A/B switch for the handwriting-area white wash; adb shell / root only, no UI
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != 2000) throw new SecurityException("shell only");
            getContext().getSharedPreferences(PREFS, 0).edit().putInt("hwWash", "1".equals(arg) ? 1 : 0).commit();
            getContext().getContentResolver().notifyChange(URI, null);
            out.putString("hwWash", "1".equals(arg) ? "native" : "hidden");
        } else throw new IllegalArgumentException("Unsupported method");
        return out;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
