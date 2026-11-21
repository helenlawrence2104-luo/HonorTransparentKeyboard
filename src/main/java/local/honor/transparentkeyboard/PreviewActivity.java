package local.honor.transparentkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class PreviewActivity extends Activity {
    private LinearLayout body;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        body.setPadding(padding, padding, padding, padding);

        text("通透键盘", 24);
        Button preview = new Button(this);
        preview.setText("预览实际键盘");
        preview.setOnClickListener(v -> startActivity(new Intent(this, KeyboardOpticsTestActivity.class)));
        body.addView(preview);

        heading("透明度");
        Switch follow = new Switch(this);
        follow.setText("跟随荣耀系统通透");
        follow.setChecked(prefs.getInt("followSystem", 1) == 1);
        body.addView(follow);
        LinearLayout background = slider("键盘背景", "degree", PreviewSettings.DEFAULT_DEGREE, 0, 100, "%");
        background.setVisibility(follow.isChecked() ? android.view.View.GONE : android.view.View.VISIBLE);
        follow.setOnCheckedChangeListener((button, checked) -> {
            save("followSystem", checked ? 1 : 0);
            background.setVisibility(checked ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        slider("按键和工具栏底板", "keys", PreviewSettings.DEFAULT_KEYS, 0, 100, "%");

        heading("按键振动");
        Switch custom = new Switch(this);
        custom.setText("自定义按键振动");
        custom.setChecked(prefs.getInt("haptic", PreviewSettings.DEFAULT_HAPTIC) == 1);
        body.addView(custom);
        text("关闭后使用输入法原有振动。", 13);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        body.addView(section);
        LinearLayout controls = body;
        body = section;
        boolean amplitude = supportsAmplitude();
        if (amplitude) {
            slider("振动强度", "hapticIntensity", PreviewSettings.DEFAULT_HAPTIC_INTENSITY, 0, 100, "%");
        }
        slider("单次脉冲时长", "hapticDuration", PreviewSettings.DEFAULT_HAPTIC_DURATION, 5, 80, " 毫秒");

        RadioGroup rhythms = new RadioGroup(this);
        rhythms.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"单次", "双次", "三次"};
        for (int i = 0; i < names.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(1301 + i);
            radio.setText(names[i]);
            rhythms.addView(radio);
        }
        int rhythm = Math.max(0, Math.min(2, prefs.getInt("hapticRhythm", PreviewSettings.DEFAULT_HAPTIC_RHYTHM)));
        rhythms.check(1301 + rhythm);
        body.addView(rhythms);
        LinearLayout gap = slider("脉冲间隔", "hapticInterval", PreviewSettings.DEFAULT_HAPTIC_INTERVAL, 20, 120, " 毫秒");
        gap.setVisibility(rhythm == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        rhythms.setOnCheckedChangeListener((group, id) -> {
            if (id < 1301 || id > 1303) return;
            save("hapticRhythm", id - 1301);
            gap.setVisibility(id == 1301 ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        body = controls;
        section.setVisibility(custom.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE);
        custom.setOnCheckedChangeListener((button, checked) -> {
            save("haptic", checked ? 1 : 0);
            section.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
        });
        if (!amplitude) text("本机未报告振幅控制，可调时长和节奏。", 13);
        if (Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
            text("系统触感反馈已关闭。", 13);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
    }

    private LinearLayout slider(String name, String key, int defaultValue, int min, int max, String unit) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        body.addView(group);
        int value = Math.max(min, Math.min(max, prefs.getInt(key, defaultValue)));
        TextView label = new TextView(this);
        label.setTextSize(16);
        label.setText(name + "  " + value + unit);
        group.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(name);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        group.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int current = min + progress;
                label.setText(name + "  " + current + unit);
                if (user) save(key, current);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        return group;
    }

    private void heading(String value) {
        TextView heading = text(value, 19);
        heading.setPadding(0, dp(14), 0, dp(4));
    }

    private TextView text(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        body.addView(label);
        return label;
    }

    private void save(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        getContentResolver().notifyChange(SettingsProvider.URI, null);
    }

    private boolean supportsAmplitude() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = getSystemService(VibratorManager.class);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = getSystemService(Vibrator.class);
        }
        return vibrator != null && vibrator.hasAmplitudeControl();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
