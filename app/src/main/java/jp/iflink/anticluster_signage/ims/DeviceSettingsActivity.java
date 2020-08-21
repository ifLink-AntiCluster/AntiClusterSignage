package jp.iflink.anticluster_signage.ims;


import android.content.Intent;

import androidx.annotation.NonNull;

import jp.co.toshiba.iflink.ui.BaseSettingsActivity;
import jp.iflink.anticluster_signage.BuildConfig;
import jp.iflink.anticluster_signage.R;

public class DeviceSettingsActivity extends BaseSettingsActivity {
    /**
     * Preferences名.
     */
    public static final String PREFERENCE_NAME = BuildConfig.APPLICATION_ID+ "_preferences";

    @Override
    protected final int getPreferencesResId() {
        return R.xml.pref_anticlustersignage;
    }

    @NonNull
    @Override
    protected final String getPreferencesName() {
        return PREFERENCE_NAME;
    }

    @Override
    protected final Intent getIntentForService() {
        Intent intent = new Intent(getApplicationContext(), AntiClusterSignageIms.class);
        intent.setPackage(getClass().getPackage().getName());
        return intent;
    }
}
