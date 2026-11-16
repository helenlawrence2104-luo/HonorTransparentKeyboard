package local.honor.transparentkeyboard;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Local controls and preview. Input text is never stored or transmitted. */
public final class PreviewActivity extends Activity {
    private SharedPreferences prefs;
    private SeekBar degree, blur, keys, glass;
    private TextView degreeLabel, blurLabel, keyLabel, glassLabel, status;
    private LinearLayout body;
    private RadioGroup materials;
    private EditText edit;
    private boolean initializing;
    private final Handler handler = new Handler();
    private final Runnable poll = new Runnable() { public void run() {
        try {
            Bundle b = getContentResolver().call(SettingsProvider.URI, "get", null, null);
            status.setText(b == null ? "等待输入法连接" : b.getString("status"));
        } catch (Exception e) { status.setText("等待输入法连接"); }
        handler.postDelayed(this, 1000);
    }};
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(35,42,50));
        parent.addView(v); return v;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(SettingsProvider.PREFS, 0);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(8));
        body.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(143,218,224), Color.rgb(245,204,145), Color.rgb(174,157,226)}));
        label(body, "通透键盘 · 自由调节", 22);
        label(body, "自动保存，实时应用到所有应用的默认键盘", 13);
        degreeLabel = label(body, "", 16);
        degree = new SeekBar(this); degree.setId(1001); degree.setContentDescription("通透程度"); degree.setMax(100); body.addView(degree);
        materials = new RadioGroup(this); materials.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton liquid = new RadioButton(this); liquid.setId(1100); liquid.setText("液态玻璃"); materials.addView(liquid);
        RadioButton frosted = new RadioButton(this); frosted.setId(1101); frosted.setText("磨砂玻璃"); materials.addView(frosted);
        body.addView(materials);
        blurLabel = label(body, "", 16);
        blur = new SeekBar(this); blur.setId(1002); blur.setContentDescription("背景模糊强度"); blur.setMax(61); body.addView(blur);
        keyLabel = label(body, "", 16);
        keys = new SeekBar(this); keys.setId(1003); keys.setContentDescription("按键与顶部按钮透明度"); keys.setMax(100); body.addView(keys);
        glassLabel = label(body, "", 16);
        glass = new SeekBar(this); glass.setId(1004); glass.setContentDescription("玻璃强度"); glass.setMax(100); body.addView(glass);
        LinearLayout actions = new LinearLayout(this);
        Button clear = new Button(this); clear.setText("更通透");
        Button reset = new Button(this); reset.setText("恢复默认");
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(46), 1));
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1)); body.addView(actions);
        LinearLayout confirm = new LinearLayout(this);
        Button apply = new Button(this); apply.setText("确定修改");
        Button restart = new Button(this); restart.setText("重启输入法");
        confirm.addView(apply, new LinearLayout.LayoutParams(0, dp(46), 1));
        confirm.addView(restart, new LinearLayout.LayoutParams(0, dp(46), 1)); body.addView(confirm);
        status = label(body, "等待输入法连接", 12);
        edit = new EditText(this); edit.setSingleLine(true); edit.setHint("点击这里，边调节边看效果"); edit.setTextSize(16); body.addView(edit);
        label(body, "透明度越大越透；玻璃强度控制高光边框、阴影与立体感，纯白/纯黑背景也能看清按键。\n键盘菜单 → 通透键盘，也可打开此面板。", 12);
        setContentView(body);
        loadControls();
        applyExtras();
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { labels(); if (user) save(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { save(); }
        };
        degree.setOnSeekBarChangeListener(listener); blur.setOnSeekBarChangeListener(listener); keys.setOnSeekBarChangeListener(listener); glass.setOnSeekBarChangeListener(listener);
        materials.setOnCheckedChangeListener((g, id) -> { if (!initializing) save(); });
        clear.setOnClickListener(v -> { initializing = true; keys.setProgress(70); initializing = false; preset(100, 0, 0); });
        reset.setOnClickListener(v -> { initializing = true; keys.setProgress(0); initializing = false; preset(60, 0, -1); });
        apply.setOnClickListener(v -> { save(); status.setText("修改已发送，等待输入法回执…"); });
        restart.setOnClickListener(v -> {
            save();
            prefs.edit().putLong("restart", System.currentTimeMillis()).apply();
            getContentResolver().notifyChange(SettingsProvider.URI, null);
            status.setText("正在请求重启输入法…");
            handler.postDelayed(() -> {
                edit.requestFocus();
                InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                ime.restartInput(edit); ime.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            }, 1500);
        });
        edit.requestFocus();
        edit.postDelayed(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT), 350);
    }
    private void preset(int d, int m, int b) {
        initializing = true; degree.setProgress(d); materials.check(m == 0 ? 1100 : 1101); blur.setProgress(b + 1); initializing = false; labels(); save();
    }
    private void loadControls() { keys.setProgress(prefs.getInt("keys", 45)); glass.setProgress(prefs.getInt("glass", 60)); preset(prefs.getInt("degree", 60), prefs.getInt("material", 0), prefs.getInt("blur", -1)); }
    private void labels() {
        degreeLabel.setText("通透程度：" + degree.getProgress() + " / 100");
        int b = blur.getProgress() - 1;
        blurLabel.setText("背景模糊：" + (b < 0 ? "自动（随通透程度变化）" : b + " / 60"));
        keyLabel.setText("按键与顶部按钮透明度：" + keys.getProgress() + "%");
        glassLabel.setText("玻璃强度：" + glass.getProgress() + "%");
    }
    private void save() {
        if (initializing) return;
        prefs.edit().putInt("degree", degree.getProgress()).putInt("material", materials.getCheckedRadioButtonId() == 1101 ? 1 : 0)
            .putInt("blur", blur.getProgress() - 1).putInt("keys", keys.getProgress()).putInt("glass", glass.getProgress()).apply();
        getContentResolver().notifyChange(SettingsProvider.URI, null);
    }
    /** Optional test extras (adb am start --ei keys N --ei glass N --ei degree N --es bg white|black). */
    private void applyExtras() {
        android.content.Intent i = getIntent();
        if (i == null) return;
        String bg = i.getStringExtra("bg");
        if ("white".equals(bg)) body.setBackgroundColor(Color.WHITE);
        else if ("black".equals(bg)) { body.setBackgroundColor(Color.BLACK); tint(body, Color.rgb(230,230,230)); }
        boolean changed = false;
        initializing = true;
        if (i.hasExtra("keys")) { keys.setProgress(i.getIntExtra("keys", 45)); changed = true; }
        if (i.hasExtra("glass")) { glass.setProgress(i.getIntExtra("glass", 60)); changed = true; }
        if (i.hasExtra("degree")) { degree.setProgress(i.getIntExtra("degree", 60)); changed = true; }
        initializing = false;
        labels();
        if (changed) save();
    }
    private static void tint(android.view.ViewGroup g, int color) {
        for (int k = 0; k < g.getChildCount(); k++) {
            android.view.View v = g.getChildAt(k);
            if (v instanceof TextView && !(v instanceof Button) && !(v instanceof EditText)) ((TextView)v).setTextColor(color);
            if (v instanceof android.view.ViewGroup) tint((android.view.ViewGroup)v, color);
        }
    }
    @Override protected void onNewIntent(android.content.Intent intent) { super.onNewIntent(intent); setIntent(intent); applyExtras(); }
    @Override protected void onResume() { super.onResume(); handler.post(poll); }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
}
