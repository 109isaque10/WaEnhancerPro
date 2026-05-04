package com.waenhancer.xposed.features.others;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.waenhancer.R;
import com.waenhancer.utils.FilePicker;
import com.waenhancer.xposed.utils.ThemeUtils;
import com.waenhancer.xposed.utils.ResId;

/**
 * An activity that runs inside the WhatsApp process to host WaEnhancer settings
 * when the current screen cannot present a support-fragment dialog.
 */
public class EmbeddedSettingsActivity extends AppCompatActivity {

    private SettingsViewBuilder.Host host;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ResId.initLocal(this);
        boolean isDark = ThemeUtils.isNightMode(this);
        android.util.Log.i("WAE", "EmbeddedSettingsActivity: Setting theme, isDark=" + isDark);
        setTheme((int) (isDark ? ResId.style.Theme : ResId.style.Theme_Light));
        super.onCreate(savedInstanceState);
        host = SettingsViewBuilder.buildHost(this);
        setContentView(host.root);
        
        // Status Bar Coloring
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(ThemeUtils.getThemeBackgroundColor(this, isDark));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                int flags = getWindow().getDecorView().getSystemUiVisibility();
                if (!isDark) {
                    flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        }

        FilePicker.registerFilePicker(this);

        host.backButton.setOnClickListener(v -> handleBack());
        updateToolbarTitle();
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbarTitle);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(host.container.getId(), new EmbeddedSettingsFragment())
                    .commitNowAllowingStateLoss();
            updateToolbarTitle();
        }
    }

    @Override
    public android.content.res.Resources getResources() {
        if (com.waenhancer.xposed.utils.XResManager.moduleResources != null) {
            return com.waenhancer.xposed.utils.XResManager.moduleResources;
        }
        return super.getResources();
    }

    @Override
    public void onBackPressed() {
        if (!handleBack()) {
            super.onBackPressed();
        }
    }

    private boolean handleBack() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return true;
        }
        finish();
        return false;
    }

    private void updateToolbarTitle() {
        Fragment current = getSupportFragmentManager().findFragmentById(host.container.getId());
        CharSequence title = getString(ResId.string.app_name);
        if (current instanceof EmbeddedBasePreferenceFragment) {
            EmbeddedBasePreferenceFragment embeddedFragment = (EmbeddedBasePreferenceFragment) current;
            title = embeddedFragment.getToolbarTitle();
        }
        host.titleView.setText(title);
    }
}
