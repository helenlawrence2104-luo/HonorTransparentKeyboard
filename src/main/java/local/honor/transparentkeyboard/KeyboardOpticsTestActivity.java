package local.honor.transparentkeyboard;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public test artwork behind the system IME. This activity does not sample its window. */
public final class KeyboardOpticsTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout frame = new FrameLayout(this);
        String layout = getIntent().getStringExtra("test_layout");
        if ("stock".equals(layout) || "content".equals(layout) || "both".equals(layout)) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            frame.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t == ob - ot) return;
                WindowInsets insets = view.getRootWindowInsets();
                int bottom = Build.VERSION.SDK_INT >= 30 && insets != null
                        ? insets.getInsets(WindowInsets.Type.ime()).bottom : -1;
                Log.i("HonorGlassHost", layout + " viewport=" + (r - l) + "x" + (b - t)
                        + " imeBottom=" + bottom);
            });
        }
        String background = getIntent().getStringExtra("test_background");
        if ("white".equals(background)) frame.setBackgroundColor(0xffffffff);
        else if ("black".equals(background)) frame.setBackgroundColor(0xff202020);
        else frame.addView(new PublicTestPatternView(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xfff7f7fa);
        controls.setPadding(dp(16), dp(8), dp(16), dp(8));
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        TextView title = new TextView(this);
        title.setText("真实键盘折射测试");
        title.setTextSize(20);
        controls.addView(title);
        TextView help = new TextView(this);
        help.setText("观察按键边缘的斜线是否弯曲。点击按钮可对照收起和展开。");
        controls.addView(help);
        EditText input = new EditText(this);
        input.setHint("公开测试输入框");
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (layout != null) input.setPrivateImeOptions(KeyboardInsets.TEST_OPTION + layout);
        controls.addView(input);
        LinearLayout buttons = new LinearLayout(this);
        controls.addView(buttons);
        InputMethodManager ime = getSystemService(InputMethodManager.class);
        Button hide = new Button(this);
        hide.setText("收起键盘");
        hide.setOnClickListener(v -> ime.hideSoftInputFromWindow(input.getWindowToken(), 0));
        buttons.addView(hide, new LinearLayout.LayoutParams(0, -2, 1));
        Button show = new Button(this);
        show.setText("展开键盘");
        show.setOnClickListener(v -> {
            input.requestFocus();
            ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        buttons.addView(show, new LinearLayout.LayoutParams(0, -2, 1));
        setContentView(frame);
        input.requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
