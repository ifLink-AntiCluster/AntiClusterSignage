package jp.iflink.anticluster_signage.task;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.model.CounterDevice;
import jp.iflink.anticluster_signage.model.ScannedDevice;
import jp.iflink.anticluster_signage.setting.UpdateMethod;
import jp.iflink.anticluster_signage.util.DataStore;
import jp.iflink.anticluster_signage.util.FileHandler;


public class BleScanTask implements Runnable {
    private static final String TAG = "BLE";
    public static final String ACTION_SCAN = TAG+".SCAN";
    public static final String NAME = "BleScan";
    private static final int REQUEST_ENABLE_BT = 1048;

    // コンテキスト
    private Context applicationContext;
    // ブロードキャストマネージャ
    private LocalBroadcastManager broadcastMgr;
    // アプリ共通設定
    private SharedPreferences prefs;

    private BluetoothManager mBtManager;
    private BluetoothAdapter mBtAdapter;
    private BluetoothLeScanner mBTLeScanner;
    private BluetoothGatt mBtGatt;
    private int mStatus;

    private ScanCallback mScanCallback;
    private Date lastScanCallbackTime;
    private AtomicInteger mAlertCounter = new AtomicInteger(0);
    private AtomicInteger mCautionCounter = new AtomicInteger(0);
    private AtomicInteger mDistantCounter = new AtomicInteger(0);
    private AtomicInteger mFarCounter = new AtomicInteger(0);
    private List<ScannedDevice> mScanDeviceList;

    // 設定値
    private AtomicInteger THRESHOLD_NEAR = new AtomicInteger(0);
    private AtomicInteger THRESHOLD_AROUND = new AtomicInteger(0);
    private int ALERT_TIMER;
    private boolean LOGGING_SETTING;

    private ScanSettings scSettings;
    private List<ScanFilter> mScanFilters;

    private FileHandler fileHandler;

    private ScheduledFuture<?> routine1mFuture;
    private ScheduledFuture<?> routine10mFuture;
    private ScheduledExecutorService routineScheduler;
    private String mRecordTime;
    private Date prevRecordTime;
    // データストア
    private DataStore dataStore;
    // スキャン結果キュー
    private BlockingQueue<ScanResult> scanResultQueue;

    // BLE scan off/onタイマ
    private int ScanRestartCount;
    private static final int SCAN_RESTART_TIMER = 10;
    // Scan結果更新タイマ
    private int ScanUpdateCount;
    // スキャン実行状態
    private boolean scanning;

    // Advertise Flags
    private static final int BLE_CAPABLE_CONTROLLER = 0x08;
    private static final int BLE_CAPABLE_HOST = 0x10;
    // BLE Company code
    private static final int COMPANY_CODE_APPLE = 0x004C;
    private static final int IPHONE_THRESHOLD = -80;
    // update method and per minutes
    private int mUpdateMethod;
    private int mUpdatePerMinutes;

    @Override
    public void run(){
        // 保存日時[Hour単位]を読み込み
        int Saved_time = Integer.parseInt( prefs.getString("Saved_time","0"));
        if( (Integer.parseInt(getSystemTime()) - Saved_time) < 1){
            // 同一時間[Hour]の場合は、デバイス一覧を読み込み
            this.mScanDeviceList = loadList("device_list");
            this.allDeviceAddressSet = loadAddressSet("allDeviceAddressSet");
            this.deviceAddressSet = loadAddressSet("deviceAddressSet");
            // Alert,Near,Around,Farを復元
            for (ScannedDevice device : this.mScanDeviceList) {
                if(device.getAlertFlag()){
                    mAlertCounter.incrementAndGet();
                }
                else {
                    if( THRESHOLD_NEAR.get() <= device.getRssi()){
                        mCautionCounter.incrementAndGet();
                    } else if (THRESHOLD_AROUND.get() < device.getRssi()) {
                        mDistantCounter.incrementAndGet();
                    } else{
                        mFarCounter.incrementAndGet();
                    }
                }
            }
        }

        if (routine1mFuture == null){
            // データ更新用定期処理（1分間隔）
            int timerDelay = getDelayMillisToNextMinute();
            routine1mFuture = routineScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "update timer");
                    //final boolean justTime10m = isJustTimeMinuteOf(10);
                    if (mUpdateMethod == UpdateMethod.EVERY_TIME){
                        ScanUpdateCount++;
                        // スキャン結果の更新
                        if(ScanUpdateCount == mUpdatePerMinutes){
                            ScanResult result;
                            while ((result = scanResultQueue.poll()) != null){
                                try {
                                    update(result);
                                } catch (Exception e){
                                    Log.e(TAG, e.getMessage(), e);
                                }
                            }
                            ScanUpdateCount = 0;
                        }
                    }
                    // 10分でScan Stop/Start
                    ScanRestartCount++;
                    if(ScanRestartCount == SCAN_RESTART_TIMER){
                        restartScan();
                        ScanRestartCount = 0;
                    }
                }
            //}, timerDelay, 1000*60);
            }, timerDelay, 1000*60, TimeUnit.MILLISECONDS);
        }

        if (routine10mFuture == null){
            // データ集計用定期処理（30秒間隔でトリガーし、10分時で動作）
            int timerDelay = getDelayMillisToNext10Minute();
            routine10mFuture = routineScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    Date nowTime = new Date();
                    if (!isJustTimeMinuteOf(nowTime, 10)){
                        // 10分時のみ処理を実行する
                        return;
                    }
                    if (isSameRecordTime(nowTime, prevRecordTime, 10)){
                        // 同一記録時間帯の場合は、処理実行しない
                        return;
                    }
                    // データ保存
                    Log.d(TAG, "record data");
                    CounterDevice data = new CounterDevice();
                    data.mAlertCounter = mAlertCounter.get();
                    data.mCautionCounter = mCautionCounter.get();
                    data.mDistantCounter = mDistantCounter.get();
                    try {
                        // 集計を実施
                        Date recordTime = new Date();
                        dataStore.writeRecord(data, recordTime);
                        // 前回集計時刻を更新
                        prevRecordTime = recordTime;
                        mRecordTime = getDateTimeText(prevRecordTime);
                    } catch (IOException e) {
                        Log.e(TAG, e.getMessage(), e);
                        showMessage(applicationContext.getResources().getString(R.string.data_save_fail));
                    }
                    // データをクリア
                    scanDataClear();
                }
                //}, timerDelay, 1000*60);
            }, timerDelay, 1000*30, TimeUnit.MILLISECONDS);
        }
    }

    private boolean isJustTimeMinuteOf(Date date, int minute){
        // 時刻を1分単位の精度にして指定したminute毎かどうかを判定
        return (int)(date.getTime()/60000) % minute == 0;
    }

    private boolean isSameRecordTime(Date nowTime, Date prevTime, int minute){
        if (nowTime== null || prevTime == null){
            return false;
        }
        // 時刻を1分単位＊指定したminute毎の精度にして、同一記録時間帯かどうか判定
        int nowTimeVal = (int)(nowTime.getTime()/(60000 * minute));
        int prevTimeVal = (int)(prevTime.getTime()/(60000 * minute));
        return nowTimeVal == prevTimeVal;
    }

    public boolean init(Context applicationContext, SharedPreferences prefs) {
        // アプリケーションコンテキストを設定
        this.applicationContext = applicationContext;
        // ブロードキャストマネージャを生成
        this.broadcastMgr = LocalBroadcastManager.getInstance(applicationContext);
        // アプリ共通設定を取得
        this.prefs = prefs;
        // BLEステータス更新
        this.mStatus = BluetoothProfile.STATE_DISCONNECTED;

        // Bluetoothマネージャの生成
        if (this.mBtManager == null) {
            this.mBtManager = (BluetoothManager)applicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (this.mBtManager == null) {
                return false;
            }
        }
        // Bluetoothアダプタの生成
        this.mBtAdapter = this.mBtManager.getAdapter();

        // 設定値の読み込み
        Resources rsrc = applicationContext.getResources();
        this.THRESHOLD_NEAR.set(getIntFromString(prefs, "rssi_near", rsrc.getInteger(R.integer.default_rssi_near)));
        this.ALERT_TIMER = getIntFromString(prefs, "alert_timer", rsrc.getInteger(R.integer.default_alert_timer));
        this.THRESHOLD_AROUND.set(getIntFromString(prefs, "rssi_around", rsrc.getInteger(R.integer.default_rssi_around)));
        this.LOGGING_SETTING = getBoolean(prefs, "logging_ble_scan", rsrc.getBoolean(R.bool.default_logging_ble_scan));

        return this.mBtAdapter != null;
    }

    private void close() {
        if (this.mBtGatt == null) {
            return;
        }
        this.mBtGatt.close();
        this.mBtGatt = null;
    }

    private Set<String> allDeviceAddressSet;
    private Set<String> deviceAddressSet;

    public int getAllDeviceCount(){
        return allDeviceAddressSet.size();
    }
    public int getDeviceCount(){
        return deviceAddressSet.size();
    }
    public int getScanDeviceCount(){
        return mScanDeviceList.size();
    }

    public void initScan() {
        // データストアの初期化
        this.dataStore = new DataStore(this.applicationContext, this.prefs);
        // タイマーの初期化
        this.routineScheduler = Executors.newScheduledThreadPool(2);
        // デバイス一覧の初期化
        this.mScanDeviceList = new ArrayList<>();
        this.deviceAddressSet = new HashSet<>();
        this.allDeviceAddressSet = new HashSet<>();
        // カウンターの初期化
        this.mAlertCounter = new AtomicInteger(0);
        this.mCautionCounter = new AtomicInteger(0);
        this.mDistantCounter = new AtomicInteger(0);
        this.mFarCounter = new AtomicInteger(0);
        // リソースの取得
        Resources rsrc = applicationContext.getResources();
        // スキャンモードの取得
        int scan_mode = Integer.parseInt(prefs.getString("scan_mode",rsrc.getString(R.string.default_scan_mode)));
        // カウント更新方法の取得
        mUpdateMethod = Integer.parseInt(prefs.getString("update_method", rsrc.getString(R.string.default_scan_method)));
        mUpdatePerMinutes = prefs.getInt("update_per_minutes", rsrc.getInteger(R.integer.default_update_per_minutes));

        this.changeSettings(scan_mode);
        this.mScanFilters = new ArrayList<>();
        this.scanResultQueue  = new LinkedBlockingQueue<>();

        this.mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, final ScanResult result) {
                lastScanCallbackTime = new Date();
                allDeviceAddressSet.add(result.getDevice().getAddress());
                //Log.d(TAG, result.toString());
                if (checkScanResult(result)){
                    deviceAddressSet.add(result.getDevice().getAddress());
                    // スキャン対象の場合
                    if (mUpdateMethod == UpdateMethod.SEQUENTIAL){
                        // 逐次カウント更新の場合、即時実行
                        try {
                            update(result);
                        } catch (Exception e){
                            Log.e(TAG, e.getMessage(), e);
                        }
                    } else if (mUpdateMethod == UpdateMethod.EVERY_TIME){
                        // 毎回指定分毎カウント更新の場合、キューに追加
                        scanResultQueue.add(result);
                    }
                }
            }
        };

        this.fileHandler = new FileHandler(this.applicationContext);

        this.ScanRestartCount = 0;
        this.ScanUpdateCount = 0;

        // スキャン初期化完了を配信
        Intent intent = new Intent(ACTION_SCAN);
        broadcastMgr.sendBroadcast(intent);
    }

    public void stop(){
        close();
        // スキャンを停止
        stopScan();
        // タイマーを停止
        if (routineScheduler != null){
            routineScheduler.shutdown();
        }
        if (mScanDeviceList != null){
            // デバイスリストを保存
            saveList();
        }
    }

    public void enableBluetooth(Activity activity){
        // Bluetooth有効化
        BluetoothAdapter btAdapter = getBtAdapter();
        if (btAdapter != null) {
            if (!btAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    public BluetoothAdapter getBtAdapter() {
        return this.mBtAdapter;
    }

    public boolean changeSettings(int scan_mode){
        if (scSettings == null || scSettings.getScanMode() != scan_mode){
            ScanSettings.Builder scanSettings = new ScanSettings.Builder();
            // スキャンモードの設定
            scanSettings.setScanMode(scan_mode).build();
            scSettings = scanSettings.build();
            return true;
        }
        return false;
    }

    public synchronized void restartScan(){
        Log.d(TAG, "Scan Stop/Start");
        // BLEスキャン一時停止
        if (this.scanning){
            try {
                stopScan();
            } catch (Exception e){
                Log.e(TAG, e.getMessage(), e);
            }
        }
        // BLEスキャンリトライの精度向上の為、一時停止⇒再開までの間に1秒空ける
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, e.getMessage(), e);
        }
        // BLEスキャン再開
        try {
            startScan();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    public boolean isScanning(){
        return scanning;
    }

    public synchronized void startScan() {
        if (this.scanning){
            // 既に開始されている場合は何もしない
            return;
        }
        this.mBTLeScanner = this.mBtAdapter.getBluetoothLeScanner();

        if (this.mBtAdapter != null) {
            Log.d(TAG, "startScan()");
            mScanFilters.clear();
            mScanFilters.add(new ScanFilter.Builder().build());
            this.mBTLeScanner.startScan(mScanFilters, scSettings, mScanCallback);
            this.scanning = true;
        }
    }

    private synchronized void stopScan() {
        this.scanning = false;
        if (this.mBtAdapter != null) {
            if (this.mBTLeScanner != null){
                Log.d(TAG, "stopScan()");
                this.mBTLeScanner.stopScan(mScanCallback);
            }
        }
        else {
            Log.d(TAG, "BluetoothAdapter :null");
        }
    }

    private boolean checkScanResult(ScanResult result){
        BluetoothDevice device = result.getDevice();
        if (device == null || device.getAddress() == null || device.getAddress().isEmpty()) {
            // アドレスが未設定のデータは対象外
            return false;
        }
        if (device.getName() != null  && !device.getName().isEmpty()) {
            // デバイス名が設定されているデータは対象外
            return false;
        }
        SparseArray<byte[]> mnfrData = null;
        Integer advertiseFlags = null;
        if (result.getScanRecord() != null){
            mnfrData = result.getScanRecord().getManufacturerSpecificData();
            advertiseFlags = result.getScanRecord().getAdvertiseFlags();
        } else {
            // スキャンレコードが無いデータは対象外
            return false;
        }
        if( advertiseFlags == -1 || (advertiseFlags & (BLE_CAPABLE_CONTROLLER | BLE_CAPABLE_HOST)) == 0){
            // DeviceCapableにControllerとHostが無い場合は対象外
            return false;
        }
        return true;
    }

    private void update(ScanResult result) {
        final BluetoothDevice newDevice = result.getDevice();
        final int rssi = result.getRssi();
        final SparseArray<byte[]> mnfrData = result.getScanRecord().getManufacturerSpecificData();
        final int advertiseFlags = result.getScanRecord().getAdvertiseFlags();

        // スキャン日時を取得
        long rxTimestampMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime() + result.getTimestampNanos() / 1000000;
        Date scanDate = new Date(rxTimestampMillis);

        boolean contains = false;

        for (ScannedDevice device : this.mScanDeviceList) {
            // 受信済みチェック
            if (newDevice.getAddress().equals(device.getDevice().getAddress())) {
                // 受信済みの場合
                contains = true;
                device.setRssi(rssi); // update
                if(THRESHOLD_NEAR.get() <= rssi){
                    if(!device.getm1stDetect()){
                        device.setmStartTime(rxTimestampMillis);
                        device.setm1stDetect(true);
                        // many near yellow
                        debug_log(scanDate, newDevice, rssi,1,0, mnfrData,0,1, advertiseFlags);
                    }
                    else{
                        if(!device.getAlertFlag()) {
                            device.setCurrentTime(rxTimestampMillis);
                            // many near yellow
                            debug_log(scanDate, newDevice, rssi,1,(rxTimestampMillis - device.getmStartTime()), mnfrData,0,1, advertiseFlags);
                            if((rxTimestampMillis - device.getmStartTime()) > (ALERT_TIMER*60*1000)) {
                                mAlertCounter.incrementAndGet();
                                if (mCautionCounter.getAndDecrement()==0){
                                    mCautionCounter.set(0);
                                }
                                device.setAlertFlag(true);
                                // many near red
                                debug_log(scanDate, newDevice, rssi,1,(rxTimestampMillis - device.getmStartTime()), mnfrData,0,0, advertiseFlags);
                                //Log.d(TAG, "alert count up");
                            }
                        }
                        else{
                            // many near red
                            device.setCurrentTime(rxTimestampMillis);
                            debug_log(scanDate, newDevice, rssi,1,(rxTimestampMillis - device.getmStartTime()), mnfrData,0,0, advertiseFlags);
                        }
                    }
                }
                else if(THRESHOLD_AROUND.get() < rssi){
                    device.setm1stDetect(false);
                    // many around purple
                    debug_log(scanDate, newDevice, rssi,1,(rxTimestampMillis - device.getmStartTime()), mnfrData,1,1, advertiseFlags);
                }
                else{
                    device.setm1stDetect(false);
                    // many far purple
                    debug_log(scanDate,newDevice,rssi,1,(rxTimestampMillis - device.getmStartTime()), mnfrData,2,2, advertiseFlags);
                }
                break;
            }
        }
        if (!contains) {
            this.mScanDeviceList.add(new ScannedDevice(newDevice, rssi));
            // add new BluetoothDevice
            if (THRESHOLD_NEAR.get() <= rssi) {
                mCautionCounter.incrementAndGet();
                // once near yellow
                debug_log(scanDate, newDevice, rssi,0,0, mnfrData,0, 1, advertiseFlags);
            } else if (THRESHOLD_AROUND.get() < rssi) {
                mDistantCounter.incrementAndGet();
                // once around purple
                debug_log(scanDate, newDevice, rssi,0,0, mnfrData,1,2, advertiseFlags);
            } else {
                mFarCounter.incrementAndGet();
                // once far purple
                debug_log(scanDate, newDevice, rssi,0,0, mnfrData,2,2, advertiseFlags);
            }
        }
    }

    public Date getLastScanCallbackTime(){
        return lastScanCallbackTime;
    }

    public String getRecordTime(){ return mRecordTime;}

    public int getAlertCount(){
        return mAlertCounter.get();
    }

    public int mCautionCounter(){
        return mCautionCounter.get();
    }

    public int getNearCount(){
        return mCautionCounter.get() + mAlertCounter.get();
    }

    public int getAroundCount(){
        return mDistantCounter.get();
    }

    public int getFarCount(){
        return mFarCounter.get();
    }

    public AtomicInteger getThresholdNearForUpdate(){
        return THRESHOLD_NEAR;
    }

    public AtomicInteger getThresholdAroundForUpdate(){
        return THRESHOLD_AROUND;
    }

    public void setThresholdNear(int threshold){
        this.THRESHOLD_NEAR.set(threshold);
    }

    public void setThresholdAround(int threshold){
        this.THRESHOLD_AROUND.set(threshold);
    }

    public void setAlertTimer(int timer){
        this.ALERT_TIMER = timer;
    }

    public void setLoggingSetting(boolean check){
        this.LOGGING_SETTING = check;
    }

    public String getUnitId(){
        if (prefs == null){
            return "";
        }
        return prefs.getString("unit_id", "");
    }

    private void scanDataClear(){
        Log.d(TAG, "Clear scan Data");
        long systemTime = System.currentTimeMillis();
        Iterator<ScannedDevice> listIt = this.mScanDeviceList.iterator();
        while(listIt.hasNext()) {
            ScannedDevice device = listIt.next();
            long deviceTime = device.getmCurrentTime();
            if((device.getAlertFlag())&&(( systemTime - deviceTime)>(ALERT_TIMER*60*1000))){
                mAlertCounter.decrementAndGet();
                if (mAlertCounter.get()<0){
                    mAlertCounter.set(0);
                }
                listIt.remove();
            }
            else if(!device.getm1stDetect()){
                listIt.remove();
            }
            else if(!device.getAlertFlag()&&((systemTime - deviceTime)>(ALERT_TIMER*60*1000))){
                listIt.remove();
            }
        }
        mCautionCounter.set(0);
        mDistantCounter.set(0);
        mFarCounter.set(0);
        allDeviceAddressSet.clear();
        deviceAddressSet.clear();
}

    private void saveList() {
        Gson gson = new Gson();
        SharedPreferences.Editor editor = prefs.edit();
        // デバイスリスト
        editor.putString("device_list", gson.toJson(this.mScanDeviceList));
        // デバイスアドレスセット
        editor.putString("allDeviceAddressSet", gson.toJson(this.allDeviceAddressSet));
        editor.putString("deviceAddressSet", gson.toJson(this.deviceAddressSet));
        // 保存時刻
        editor.putString("Saved_time", getSystemTime());
        // 設定に保存
        editor.apply();
        editor.commit();
    }

    private List<ScannedDevice> loadList(String key) {
        List<ScannedDevice> list = new ArrayList<>();
        String strJson = prefs.getString(key, "");
        if(!strJson.isEmpty()) {
            try {
                Gson gson = new Gson();
                list = gson.fromJson(strJson, new TypeToken<ArrayList<ScannedDevice>>(){}.getType());
            } catch(Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        return list;
    }

    private Set<String> loadAddressSet(String key) {
        Set<String> list = new HashSet<>();
        String strJson = prefs.getString(key, "");
        if(!strJson.isEmpty()) {
            try {
                Gson gson = new Gson();
                list = gson.fromJson(strJson, new TypeToken<HashSet<String>>(){}.getType());
            } catch(Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        return list;
    }

    private String getSystemTime() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHH", Locale.getDefault());
        return sdf.format(date);
    }

    private int getDelayMillisToNextMinute(){
        Calendar nextTime = Calendar.getInstance();
        nextTime.set(Calendar.SECOND, 0);
        nextTime.set(Calendar.MILLISECOND, 0);
        nextTime.add(Calendar.MINUTE, 1);
        return (int)(nextTime.getTime() .getTime() - new Date().getTime());
    }

    private int getDelayMillisToNext10Minute(){
        Calendar nextTime = Calendar.getInstance();
        final int minute = nextTime.get(Calendar.MINUTE);
        nextTime.set(Calendar.SECOND, 0);
        nextTime.set(Calendar.MILLISECOND, 0);
        nextTime.set(Calendar.MINUTE, ((minute/10)+1)*10);
        return (int)(nextTime.getTime().getTime() - new Date().getTime());
    }

    private String getDateTimeText(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(date);
    }

    private void showMessage(final String message){
        if(this.applicationContext != null) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                public void run() {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void debug_log(Date scanDate, BluetoothDevice device, int rssi, int continuous, long count_time, SparseArray<byte[]> mnfrData, int distance, int judge, Integer flags){
        if(!this.LOGGING_SETTING){
            return;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        String dateStr = sdf.format(scanDate);
        String occur, dist, jud;
        String mnfrDataStr = spArrayToStr(mnfrData);
        final int company = getCompanyFromSpArray(mnfrData);

        SimpleDateFormat sdfYMDH = new SimpleDateFormat("yyyyMMdd_HH", Locale.US);
        String csvFileName = "AntiClusterLog_"+sdfYMDH.format(scanDate)+".txt";
        File csvFile = fileHandler.getFile(csvFileName);
        boolean isNewFile = !csvFile.exists();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile,true))) {
            if (isNewFile){
                // ヘッダー行を出力する
                writer.write("count_time,address,name,rssi,continuous,distance,judge,count_time,alert,near,around,far,manufacturer_specific_data,flags,company");
                writer.newLine();
            }
            writer.write(dateStr + ",");
            writer.write(device.getAddress()+ ",");
            writer.write(device.getName() + ",");
            writer.write(rssi + ",");
            if(continuous == 0){
                writer.write("once,");
                occur = "once";
            }else if(continuous == 1){
                writer.write("many,");
                occur = "many";
            } else {
                occur = "";
            }
            if(distance == 0){
                writer.write("near,");
                dist = "near";
            }else if(distance == 1){
                writer.write("around,");
                dist = "around";
            }else{
                writer.write("far,");
                dist = "far";
            }
            if(judge == 0){
                writer.write("red,");
                jud = "red";
            }else if(judge == 1){
                writer.write("yellow,");
                jud = "yellow";
            }else{
                writer.write("purple,");
                jud = "purple";
            }
            writer.write(count_time + ",");
            writer.write(mAlertCounter + ",");
            writer.write(mCautionCounter + ",");
            writer.write(mDistantCounter + ",");
            writer.write(mFarCounter + ",");
            writer.write(mnfrDataStr + ",");
            writer.write(flags + ",");
            writer.write(company + "");
            writer.newLine();
            Log.i(TAG,device.getAddress()+ ","+device.getName() + ","+ rssi + ","+ occur +"," +
                    ""+dist+","+jud+","+count_time+","+mAlertCounter+","+mCautionCounter+","+mDistantCounter+","+mFarCounter+","+
                    mnfrDataStr +","+flags +","+company);
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private static int getCompanyFromSpArray(SparseArray<byte[]> data){
        if (data != null && data.size()>0){
            return data.keyAt(0);
        }
        return -1;
    }

    private static String spArrayToStr(SparseArray<byte[]> data){
        if (data == null){
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        if (data.size()>0){
            for (int idx=0; idx<data.size(); idx++){
                int key = data.keyAt(idx);
                byte[] array = data.valueAt(idx);
                sb.append(key).append("=0x").append(binToHexStr(array)).append(",");
            }
            sb.setLength(sb.length()-",".length());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String binToHexStr(byte[] array){
        StringBuilder sb = new StringBuilder();
        for (byte d : array) {
            sb.append(String.format("%02X", d));
        }
        return sb.toString();
    }

    private int getIntFromString(SharedPreferences prefs, String key, int defaultValue){
        String value = prefs.getString(key, String.valueOf(defaultValue));
        return Integer.parseInt(value);
    }

    private boolean getBoolean(SharedPreferences prefs, String key, boolean defaultValue){
        boolean value;
        try {
            value = prefs.getBoolean(key, defaultValue);
        } catch (ClassCastException e){
            value = Boolean.valueOf(prefs.getString(key, String.valueOf(defaultValue)));
        }
        return value;
    }

}
