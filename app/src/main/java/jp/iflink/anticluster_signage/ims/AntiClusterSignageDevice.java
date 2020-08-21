package jp.iflink.anticluster_signage.ims;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Looper;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.Log;
import android.widget.Toast;

import java.util.Date;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import jp.co.toshiba.iflink.epaapi.EPAdata;

import jp.co.toshiba.iflink.imsif.IfLinkConnector;
import jp.co.toshiba.iflink.imsif.DeviceConnector;
import jp.co.toshiba.iflink.imsif.IfLinkSettings;
import jp.co.toshiba.iflink.imsif.IfLinkAlertException;
import jp.co.toshiba.iflink.ui.PermissionActivity;
import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.setting.CountPeriodType;
import jp.iflink.anticluster_signage.task.BleScanTask;

public class AntiClusterSignageDevice extends DeviceConnector {
    /** ログ出力用タグ名. */
    private static final String TAG = "ANTICLUSTERSIGNAGE-DEV";
    /** ログ出力切替フラグ. */
    private boolean bDBG = false;
    /** 処理実行のハンドラ. */
    private Handler handler = new Handler(Looper.getMainLooper());

    /** データ送信間隔[秒]. */
    private AtomicInteger sendDataInterval;
    /** データ送信用タイマー. */
    private Timer sendDataTimer;
    // 最新のスキャンコールバック時刻
    private Date lastScanCallbackTime;
    // カウント期間の種別
    private int countPeriodType;

    /**
     * コンストラクタ.
     *
     * @param ims IMS
     */
    public AntiClusterSignageDevice(final IfLinkConnector ims) {
        super(ims, MONITORING_LEVEL3, PermissionActivity.class);
        // リソースを取得
        Resources rsrc = mIms.getResources();
        // デバイス情報を設定
        mDeviceName = rsrc.getString(R.string.ims_device_name);
        mDeviceSerial = rsrc.getString(R.string.ims_device_serial);
        mSchemaName = rsrc.getString(R.string.ims_schema_name);
        // スキーマ情報を設定
        setSchema();

        mCookie = IfLinkConnector.EPA_COOKIE_KEY_TYPE + "=" + IfLinkConnector.EPA_COOKIE_VALUE_CONFIG
                + IfLinkConnector.COOKIE_DELIMITER
                + IfLinkConnector.EPA_COOKIE_KEY_TYPE + "=" + IfLinkConnector.EPA_COOKIE_VALUE_ALERT
                + IfLinkConnector.COOKIE_DELIMITER
                + IfLinkConnector.EPA_COOKIE_KEY_DEVICE + "=" + mDeviceName
                + IfLinkConnector.COOKIE_DELIMITER
                + IfLinkConnector.EPA_COOKIE_KEY_ADDRESS + "=" + IfLinkConnector.EPA_COOKIE_VALUE_ANY;

        mAssetName= rsrc.getString(R.string.ims_asset_name);
        // 設定値の読み込み
        SharedPreferences prefs = mIms.getSharedPreferences(DeviceSettingsActivity.PREFERENCE_NAME, 0);
        sendDataInterval = new AtomicInteger(getIntFromString(prefs, "send_data_interval", rsrc.getInteger(R.integer.default_send_data_interval)));
        countPeriodType = Integer.parseInt(prefs.getString("count_period_type", rsrc.getString(R.string.default_count_period_type)));

        // デバイス登録
        // 基本は、デバイスとの接続確立後、デバイスの対応したシリアル番号に更新してからデバイスを登録してください。
        addDevice();
        // 基本は、デバイスとの接続が確立した時点で呼び出します。
        notifyConnectDevice();
    }

    @Override
    public boolean onStartDevice() {
        if (bDBG) Log.d(TAG, "onStartDevice");
        // デバイスからのデータ送信開始処理
        startSendDataTimer();
        // 送信開始が別途完了通知を受ける場合には、falseを返してください。
        return true;
    }

    private void startSendDataTimer(){
        // データ送信タイマーを停止
        stopSendDataTimer();
        // 初回実行時刻（次分の00秒）を取得
        Calendar startTime = Calendar.getInstance();
        startTime.add(Calendar.MINUTE, 1);
        startTime.set(Calendar.SECOND, 0);
        startTime.set(Calendar.MILLISECOND, 0);
        // データ送信タイマーを再設定
        sendDataTimer = new Timer(true);
        sendDataTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    public void run() {
                        // BLEスキャンタスク取得
                        BleScanTask bleService = ((AntiClusterSignageIms)mIms).getBleScanTask();
                        if (bleService == null){
                            if (bDBG) Log.w(TAG, "bleService is null");
                            return;
                        }
                        // カウント値を取得
                        int near, around, far;
                        if (countPeriodType == CountPeriodType.ABSOLUTE){
                            near = bleService.getNearCount();
                            around = bleService.getAroundCount();
                            far = bleService.getFarCount();
                        } else if (countPeriodType == CountPeriodType.RELATIVE){
                            near = bleService.getCurrentNearCount();
                            around = bleService.getCurrentAroundCount();
                            far = bleService.getCurrentFarCount();
                        } else {
                            Log.e(TAG, "countPeriodType is unknown");
                            return;
                        }
                        String unitId = bleService.getUnitId();
                        // データ送信
                        sendData(near, around, near+around, far, unitId);
                    }
                });
            }
        }, startTime.getTime(), sendDataInterval.get() * 1000);
    }

    private void stopSendDataTimer(){
        if (sendDataTimer != null) {
            sendDataTimer.cancel();
        }
    }

    @Override
    public boolean onStopDevice() {
        if (bDBG) Log.d(TAG, "onStopDevice");
        // デバイスからのデータ送信停止処理
        if (sendDataTimer != null) {
            sendDataTimer.cancel();
        }
        // 送信停止が別途完了通知を受ける場合には、falseを返してください。
        return true;
    }

    /**
     * カウント値を送信する.
     * このメソッドをデバイスからデータを受信したタイミング等で呼び出せば.
     * ifLink Core側にデータが送信されます.
     *
     * @param near 3m以内のカウント値.
     * @param around 3～10mのカウント値.
     * @param active 0～10mのカウント値.
     * @param far 10m以上のカウント値.
     * @param unitId 個体識別ID.
     */
    public void sendData(final int near, final int around, final int active, final int far, final String unitId) {
        Log.i(TAG, String.format("sendDeviceData near=%d,around=%d,active=%d,far=%d,unit_id=%s" , near, around, active, far, unitId));
        // 送信データクリア
        clearData();
        // データの登録.
        addData(new EPAdata("near", "int", String.valueOf(near)));
        addData(new EPAdata("around", "int", String.valueOf(around)));
        addData(new EPAdata("active", "int", String.valueOf(active)));
        addData(new EPAdata("far", "int", String.valueOf(far)));
        addData(new EPAdata("unit_id", "string", unitId));
        //ifLink Coreへデータを送信する.
        notifyRecvData();
    }

    @Override
    public void enableLogLocal(final boolean enabled) {
        bDBG = enabled;
    }

    @Nullable
    @Override
    protected XmlResourceParser getResourceParser(final Context context) {
        Resources resources = context.getResources();
        if (resources != null) {
            return context.getResources().getXml(R.xml.schema_anticlustersignage);
        } else {
            return null;
        }
    }

    @Override
    protected void onUpdateConfig(@NonNull IfLinkSettings settings) throws IfLinkAlertException {
        if (bDBG) Log.d(TAG, "onUpdateConfig");
        // リソースを取得
        Resources rsrc = mIms.getResources();
        // BLEスキャンタスク取得
        BleScanTask bleService = ((AntiClusterSignageIms)mIms).getBleScanTask();
        // スキャンモード
        if (bleService != null){
            int scan_mode = Integer.parseInt(settings.getStringValue("scan_mode", rsrc.getString(R.string.default_scan_mode)));
            // スキャンモードを変更
            boolean changed = bleService.changeSettings(scan_mode);
            if (changed){
                // スキャンモードが変更された場合は、スキャンを再始動
                bleService.restartScan();
            }
        }
        // アプリケーションのログを残す
        if (bleService != null){
            boolean logging_ble_scan = settings.getBooleanValue("logging_ble_scan", rsrc.getBoolean(R.bool.default_logging_ble_scan));
            bleService.setLoggingSetting(logging_ble_scan);
        }
        // 電波強度:3mの推定基準
        if (bleService != null) {
            checkUpdateAndSet(bleService.getThresholdNearForUpdate(), settings, "rssi_near", rsrc.getInteger(R.integer.default_rssi_near));
        }
        // 電波強度:10mの推定基準
        if (bleService != null) {
            checkUpdateAndSet(bleService.getThresholdAroundForUpdate(), settings, "rssi_around", rsrc.getInteger(R.integer.default_rssi_around));
        }
        // データ送信間隔
        if (checkUpdateAndSet(this.sendDataInterval, settings, "send_data_interval", rsrc.getInteger(R.integer.default_send_data_interval))){
            // 変更されていた場合は、タイマーを再設定する
            startSendDataTimer();
        }
        // カウント集計期間
        if (bleService != null) {
            checkUpdateAndSet(bleService.getCountPeriodMinutesForUpdate(), settings, "count_period_minutes", rsrc.getInteger(R.integer.default_count_period_minutes));
        }
        // カウント期間の種別
        this.countPeriodType = Integer.parseInt(settings.getStringValue("count_period_type", rsrc.getString(R.string.default_count_period_type)));
    }

    private boolean checkUpdateAndSet(AtomicInteger current, IfLinkSettings settings, String key, int defaultValue) throws IfLinkAlertException {
        int value = settings.getIntValue(key, defaultValue);
        if (value != current.get()){
            // 更新されていた場合は設定
            current.set(value);
            if (bDBG) Log.d(TAG, "parameter[" + key + "] = " + value);
            return true;
        }
        return false;
    }

    // デバイスとの接続経路が有効かをチェックする処理
    @Override
    public final boolean checkPathConnection() {
        //if (bDBG) Log.d(TAG, "checkPathConnection");
        AntiClusterSignageIms ims = (AntiClusterSignageIms)mIms;
        // BLEスキャンタスクが存在し、稼働中かどうかチェック
        if (bDBG) Log.d(TAG, "checkPathConnection scanTask="+(ims.getBleScanTask()!=null)+" run="+ims.isRunning());
        return (ims.getBleScanTask() != null && ims.isRunning());
    }

    // デバイスとの接続経路を有効にする処理
    @Override
    public final boolean reconnectPath() {
        //if (bDBG) Log.d(TAG, "reconnectPath");
        AntiClusterSignageIms ims = (AntiClusterSignageIms)mIms;
        if (bDBG) Log.d(TAG, "checkPathConnection scanTask="+(ims.getBleScanTask()!=null)+" run="+ims.isRunning());
        if (ims.getBleScanTask() != null && ims.isRunning()){
            // BLEスキャンタスクが存在し、稼働中の場合は何もしない
            return true;
        }
        // サービスの開始
        Intent serviceIntent = new Intent(mIms.getApplicationContext(), AntiClusterSignageIms.class);
        serviceIntent.putExtra(BleScanTask.NAME, true);
        if (Build.VERSION.SDK_INT >= 26) {
            // Android8以降の場合);
            mIms.startForegroundService(serviceIntent);
        } else {
            // Android7以前の場合
            mIms.startService(serviceIntent);
        }
        return true;
    }

    // デバイスとの接続が維持されているかをチェックする処理
    @Override
    public final boolean checkDeviceConnection() {
        //if (bDBG) Log.d(TAG, "checkDeviceConnection");
        AntiClusterSignageIms ims = (AntiClusterSignageIms)mIms;
        if (bDBG) Log.d(TAG, "checkDeviceConnection1 scanTask="+(ims.getBleScanTask()!=null)+" run="+ims.isRunning());
        // BLEスキャンタスクが存在し、稼働中かどうかチェック
        if  (ims.getBleScanTask() == null || !ims.isRunning()){
            return false;
        }
        // 前回のスキャンコールバック時刻を取得
        Date beforeTime = this.lastScanCallbackTime;
        if (beforeTime == null){
            // 初回の場合、最新のスキャンコールバック時刻を取得
            this.lastScanCallbackTime = ims.getBleScanTask().getLastScanCallbackTime();
            if (this.lastScanCallbackTime != null){
                // スキャンコールバック時刻が入っていた場合は、稼働中と判定
                if (bDBG) Log.d(TAG, "checkDeviceConnection2 isAlive=true");
                return true;
            } else {
                // そうでない場合は、現在日時を設定
                beforeTime = new Date();
            }
        }
        boolean isAlive = false;
        // 最大5秒間チェック
        for (int cnt=1; cnt<=5; cnt++){
            try {
                // 1秒待機
                Thread.sleep(1000);
                // BLEスキャンタスクが存在し、稼働中かどうかチェック
                if  (ims.getBleScanTask() == null || !ims.isRunning()){
                    // 稼働中でない場合は判定を終了
                    break;
                }
                // 最新のスキャンコールバック時刻を取得
                this.lastScanCallbackTime = ims.getBleScanTask().getLastScanCallbackTime();
                // スキャンコールバック時刻が更新されているかどうかチェック
                Date afterTime = lastScanCallbackTime;
                if (afterTime != null && beforeTime.getTime() != afterTime.getTime()){
                    // 更新されていた場合、判定を終了
                    isAlive = true;
                    break;
                }
            } catch (InterruptedException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        if (bDBG) Log.d(TAG, "checkDeviceConnection2 isAlive="+isAlive);
        return isAlive;
    }

    // デバイスとの再接続処理
    @Override
    public final boolean reconnectDevice() {
        //if (bDBG) Log.d(TAG, "reconnectDevice");
        AntiClusterSignageIms ims = (AntiClusterSignageIms)mIms;
        if (bDBG) Log.d(TAG, "reconnectDevice scanTask="+(ims.getBleScanTask()!=null)+" run="+ims.isRunning());
        // BLEスキャンタスクが存在し、稼働中かどうかチェック
        if  (ims.getBleScanTask() == null || !ims.isRunning()){
            return false;
        }
        // スキャンの再始動はIMS内で行っている為、ここでは実施しない
        //ims.getBleScanTask().restartScan();
        return true;
    }

    private int getIntFromString(SharedPreferences prefs, String key, int defaultValue){
        String value = prefs.getString(key, String.valueOf(defaultValue));
        return Integer.parseInt(value);
    }

    private void showMessage(final String message){
        final Context ctx = mIms.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
