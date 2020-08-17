package jp.iflink.anticluster_signage.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.ims.AntiClusterSignageIms;
import jp.iflink.anticluster_signage.task.BleScanTask;

public class SettingsFragment extends PreferenceFragmentCompat
        implements IServiceFragment, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "Settings";
    /** ログ出力切替フラグ. */
    private boolean bDBG = false;

    // メインサービス
    private static WeakReference<AntiClusterSignageIms> mService;

    public void setService(AntiClusterSignageIms mainService){
        if (mainService != null){
            mService = new WeakReference<AntiClusterSignageIms>(mainService);
        } else if (mService != null) {
            mService.clear();
            mService = null;
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Log.d(TAG, "onCreatePreferences");
        // SwitchPreferenceCompat設定項目値がString型になっていた場合、boolean型に変換する
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Map<String,?> prefMap = prefs.getAll();
        Map<String,Boolean> newValueMap = new HashMap<>();
        for (String key : new String[]{"runin_background","logging_ble_scan","draw_count_detail"}){
            Object value = prefMap.get(key);
            if (value instanceof String){
                newValueMap.put(key, Boolean.valueOf((String)value));
            }
        }
        if (!newValueMap.isEmpty()){
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String,Boolean> entry : newValueMap.entrySet()){
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            editor.apply();
            editor.commit();
        }
        // 設定ファイル読み込み
        setPreferencesFromResource(R.xml.preferences, rootKey);
        // listenerの登録
        prefs.registerOnSharedPreferenceChangeListener(this);
        // inputTypeを設定
        setupInputType("rssi_near", InputType.TYPE_CLASS_NUMBER+InputType.TYPE_NUMBER_FLAG_SIGNED);
        //setupInputType("alert_timer", InputType.TYPE_CLASS_NUMBER);
        setupInputType("rssi_around", InputType.TYPE_CLASS_NUMBER+InputType.TYPE_NUMBER_FLAG_SIGNED);
        setupInputType("update_time", InputType.TYPE_CLASS_NUMBER);
        setupInputType("graph_min_y", InputType.TYPE_CLASS_NUMBER);
        setupInputType("graph_start_hour", InputType.TYPE_CLASS_NUMBER);
        setupInputType("graph_count_hours", InputType.TYPE_CLASS_NUMBER);
        // デバッグフラグの設定
        bDBG = getDebugFlag(prefs);
    }

    private void setupInputType(final String key, final int inputType){
        EditTextPreference pref = findPreference(key);
        if (pref != null) {
            pref.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
                @Override
                public void onBindEditText(@NonNull EditText editText) {
                    editText.setInputType(inputType);
                }
             });
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.d(TAG, "onSharedPreferenceChanged");
        // BLEスキャンタスク取得
        BleScanTask bleService = getBleScanTask();
        if (bleService == null) {
            if (bDBG) Log.w(TAG, "bleService is null");
            return;
        }
        switch(key){
            case "scan_mode":
                int scan_mode = Integer.parseInt(prefs.getString("scan_mode","0"));
                bleService.changeSettings(scan_mode);
                bleService.restartScan();
                break;
            case "logging_ble_scan":
                bleService.setLoggingSetting(prefs.getBoolean(key, false));
                break;
            case "rssi_near":
                bleService.setThresholdNear(getIntFromString(prefs, key, 0));
                break;
            case "alert_timer":
                bleService.setAlertTimer(getIntFromString(prefs, key, 0));
                break;
            case "rssi_around":
                bleService.setThresholdAround(getIntFromString(prefs, key, 0));
                break;
            default:
                break;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    private BleScanTask getBleScanTask(){
        if (mService == null || mService.get() == null){
            return null;
        }
        return mService.get().getBleScanTask();
    }

    private int getIntFromString(SharedPreferences prefs, String key, int defaultValue){
        String value = prefs.getString(key, String.valueOf(defaultValue));
        return Integer.parseInt(value);
    }

    private boolean getDebugFlag(SharedPreferences prefs){
        Set<String> loglevelSet  = prefs.getStringSet("loglevel", null);
        if (loglevelSet != null && loglevelSet.contains(AntiClusterSignageIms.LOG_LEVEL_CUSTOM_IMS)){
            return true;
        }
        return false;
    }
}
