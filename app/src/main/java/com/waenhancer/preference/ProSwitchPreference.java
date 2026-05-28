package com.waenhancer.preference;

import android.content.Context;
import android.text.Html;
import android.util.AttributeSet;

import androidx.preference.PreferenceManager;

import com.waenhancer.BuildConfig;

import rikka.material.preference.MaterialSwitchPreference;

public class ProSwitchPreference extends MaterialSwitchPreference {

    public ProSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public ProSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public ProSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        CharSequence originalTitle = getTitle();
        if (originalTitle == null) {
            originalTitle = "";
        }

        if (BuildConfig.HAS_PRO_FEATURES) {
            // Built with pro features - display Pro chip in purple
            String newTitle = originalTitle + " <font color='#8B5CF6'><b>[Pro]</b></font>";
            setTitle(Html.fromHtml(newTitle, Html.FROM_HTML_MODE_LEGACY));
        } else {
            // Built without pro features - display missing pro module chip in red
            String newTitle = originalTitle + " <font color='#EF4444'><b>[missing pro module]</b></font>";
            setTitle(Html.fromHtml(newTitle, Html.FROM_HTML_MODE_LEGACY));
            
            // Disable preference entirely
            setEnabled(false);
            
            // Force state to false and save it
            setChecked(false);
            var prefs = getSharedPreferences() != null
                    ? getSharedPreferences()
                    : PreferenceManager.getDefaultSharedPreferences(getContext());
            if (getKey() != null) {
                prefs.edit().putBoolean(getKey(), false).apply();
            }
        }
    }
}
