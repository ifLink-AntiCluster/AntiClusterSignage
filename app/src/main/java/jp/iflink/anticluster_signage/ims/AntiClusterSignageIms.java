package jp.iflink.anticluster_signage.ims;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.co.toshiba.iflink.epaapi.EPADevice;
import jp.co.toshiba.iflink.epaapi.utils.NotificationHelper;
import jp.co.toshiba.iflink.imsif.DeviceConnector;
import jp.co.toshiba.iflink.imsif.IIfLinkConnectorCallback;
import jp.co.toshiba.iflink.imsif.IIfLinkConnectorService;
import jp.co.toshiba.iflink.imsif.IfLinkConnector;
import jp.co.toshiba.iflink.imsif.IfLinkSettings;
import jp.co.toshiba.iflink.ui.PermissionActivity;
import jp.iflink.anticluster_signage.FullscreenActivity;
import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.task.BleScanTask;
import jp.iflink.anticluster_signage.util.ReflectionUtil;

public class AntiClusterSignageIms extends IfLinkConnector {
    private static final String SERVICE_NAME = "AntiClusterSignageIms";
    private static final String CHANNEL_ID = "ims";
    private static final String CONTENT_TEXT = "サービスを実行中です";
    // IntentServiceから継承したプロパティ
    private volatile Looper mServiceLooper;
    //@UnsupportedAppUsage
    private volatile ServiceHandler mServiceHandler;
    private String mName;

    // IntentServiceから継承した内部クラス
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            onHandleIntent((Intent)msg.obj);
            stopSelf(msg.arg1);
        }
    }

    // BLEスキャンタスク
    private BleScanTask bleScanTask;
    // バインダー
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends IIfLinkConnectorService.Stub {
        public AntiClusterSignageIms getService() {
            return AntiClusterSignageIms.this;
        }
        @Override
        public void registerCallback(IIfLinkConnectorCallback callback) throws RemoteException {
            getConfigBinder().registerCallback(callback);
        }
        @Override
        public void unregisterCallback() throws RemoteException {
            getConfigBinder().unregisterCallback();
        }
        @Override
        public void setConfig(IfLinkSettings settings) throws RemoteException {
            getConfigBinder().setConfig(settings);
            // SwitchPreferenceCompat設定の値がString型で保持されているので、Boolean型に変換する
            SharedPreferences.Editor editor = getApplicationContext().getSharedPreferences(DeviceSettingsActivity.PREFERENCE_NAME, 0).edit();
            Map<String, Object> map =  settings.getMap();
            editor.putBoolean("logging_ble_scan", Boolean.valueOf((String)map.get("logging_ble_scan")));
            editor.putBoolean("runin_background", Boolean.valueOf((String)map.get("runin_background")));
            editor.putBoolean("draw_count_detail", Boolean.valueOf((String)map.get("draw_count_detail")));
            editor.apply();
            editor.commit();
        }
        @Override
        public IfLinkSettings getConfig() throws RemoteException {
            return getConfigBinder().getConfig();
        }
    }
    private IIfLinkConnectorService.Stub getConfigBinder() {
        return ReflectionUtil.getFieldValue(TAG, IfLinkConnector.class, "mConfigBinder", this);
    }

    // ステータス
    private boolean running;

    /** ログ出力用タグ名 */
    private static final String TAG = "ANTICLUSTERSIGNAGE-IMS";
    /** ログ出力レベル：CustomDevice */
    private static final String LOG_LEVEL_CUSTOM_DEV = "CUSTOM-DEV";
    /** ログ出力レベル：CustomIms */
    public static final String LOG_LEVEL_CUSTOM_IMS = "CUSTOM-IMS";
    /** ログ出力切替フラグ */
    private boolean bDBG = false;
    /** デバイス*/
    private AntiClusterSignageDevice mDevice;

    /**
     * コンストラクタ.
     */
    public AntiClusterSignageIms() {
        super(SERVICE_NAME);
        this.mName = SERVICE_NAME;
    }

    @WorkerThread
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.d(TAG, "onHandleIntent");
        // 処理実行
        execute(intent);
    }

    public void execute(@Nullable Intent intent){
        Log.d(TAG, "service execute");
        // アプリ共通設定を取得
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        // パラメータを取得
        boolean blescan_task;
        if (intent != null){
            blescan_task = intent.getBooleanExtra(BleScanTask.NAME, true);
        } else {
            blescan_task = true;
        }
        Log.d(TAG, "blescan_task="+blescan_task+", intent="+intent);
        // 前回のタスクが残っている場合は事前に停止
        stopTask();
        if (blescan_task){
            // BLEスキャンタスクを起動
            this.bleScanTask = new BleScanTask();
            boolean success = bleScanTask.init(getApplicationContext(), prefs);
            if (success){
                bleScanTask.initScan();
                new Thread(bleScanTask).start();
            } else {
                bleScanTask = null;
            }
        }
        running = true;
        while (running && (bleScanTask != null)) {
            Thread.yield();
        }
    }

    @Override
    public void onCreate() {
        // during processing, and to have a static startService(Context, Intent)
        // method that would launch the service & hand off a wakelock.
        super.onCreate();
        HandlerThread thread = new HandlerThread("IntentService[" + mName + "]");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        Log.d(TAG, "service create");
    }

    @Override
    public void onStart(@Nullable Intent intent, int startId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "service start");
        // IfLinkConnectorのonStartCommand()相当の処理を実施
        int flag = super_onStartCommand(intent, flags, startId);
        // for IntentService
        onStart(intent, startId);
        // return result of IfLinkConnector#onStartCommand()
        return flag;
    }

    /**
     * IfLinkConnectorのonStartCommand()相当の処理を実施
     * ※Notificationの仕方をカスタマイズしている為、止む無く処理を再実装
     * @see jp.co.toshiba.iflink.imsif.IfLinkConnector#onStartCommand(Intent, int, int)
     */
    private int super_onStartCommand(Intent intent, int flags, int startId) {
        if (this.bDBG) {
            Log.d("IFLINK-CNCTR", "onStartCommand");
        }

        if(Build.VERSION.SDK_INT >= 26) {
            // Android8以降の場合、フォアグラウンド開始処理を実施
            startForeground();
        }

        String command = null;
        if (intent != null) {
            command = intent.getStringExtra("COMMAND");
        }

        if (command == null) {
            if (this.bDBG) {
                Log.d("IFLINK-CNCTR", "--START");
            }

            int mEpaControl = IFLINK_CONNECT_ON_CREATE;
            try {
                mEpaControl = ReflectionUtil.getFieldIntValue(IfLinkConnector.class, "mEpaControl", this);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                Log.e(TAG, e.getMessage(), e);
            }

            if (mEpaControl == IFLINK_CONNECT_ON_CREATE) {
                this.bindEpaService();
            }

            this.checkPermissions();
        } else {
            byte var6 = -1;
            switch(command.hashCode()) {
                case -653054860:
                    if (command.equals("PERMISSON_GRANTED")) {
                        var6 = 1;
                    }
                    break;
                case 2555906:
                    if (command.equals("STOP")) {
                        var6 = 0;
                    }
            }

            switch(var6) {
                case 0:
                    if (this.bDBG) {
                        Log.d("IFLINK-CNCTR", "--STOP");
                    }

                    this.stopHealthCheck();
                    this.onStopIMS();
                    this.unbindEpaService();
                    this.stopSelf();
                    return START_NOT_STICKY;
                case 1:
                    if (this.bDBG) {
                        Log.d("IFLINK-CNCTR", "--PERMISSION_GRANTED");
                    }

                    this.onPermissionGranted();
                    //this.restartDevicesIfError();
                    try {
                        ReflectionUtil.invokeMethod(IfLinkConnector.class, "restartDevicesIfError", this);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        Log.e(TAG, e.getMessage(), e);
                    }
                    break;
                default:
                    Log.e("IFLINK-CNCTR", "unsupported command:" + command);
            }
        }

        return START_STICKY;
    }

    @RequiresApi(api = 26)
    private void startForeground(){
        Log.d(TAG, "service start foreground");

        Context context = getApplicationContext();
        Resources rsrc = context.getResources();
        // タイトルを取得
        final String TITLE = rsrc.getString(R.string.app_name);
        // 通知マネージャを生成
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // 通知チャンネルを生成
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, TITLE, NotificationManager.IMPORTANCE_DEFAULT);
        if(notificationManager != null) {
            // 通知バーをタップした時のIntentを作成
            Intent notifyIntent = new Intent(context, FullscreenActivity.class);
            notifyIntent.putExtra("fromNotification", true);
            PendingIntent intent = PendingIntent.getActivity(context, 0, notifyIntent, PendingIntent.FLAG_CANCEL_CURRENT);
            // サービス起動の通知を送信
            notificationManager.createNotificationChannel(channel);
            //Notification notification = new Notification.Builder(context, CHANNEL_ID)
            Notification notification = new NotificationHelper(this)
                    .getNotification(this.getClass().getSimpleName())
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(TITLE)
                    .setContentText(CONTENT_TEXT)
                    .setContentIntent(intent)
                    .build();
            // フォアグラウンドで実行
            startForeground(1, notification);
        }
    }

    @Override
    public void onDestroy() {
        mServiceLooper.quit();

        Log.d(TAG, "service done");
        // タスクを停止
        stopTask();
        // 通知を除去
        if(Build.VERSION.SDK_INT >= 26) {
            stopForeground(true);
        }
        super.onDestroy();
    }

    public boolean isRunning(){
        return running;
    }

    protected void stopTask(){
        running = false;
        try {
            // BLEスキャンタスクを停止
            if (bleScanTask != null) {
                this.bleScanTask.stop();
                this.bleScanTask = null;
            }
        } catch (Exception e){
            Log.e(TAG, e.getMessage(), e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    public BleScanTask getBleScanTask() {
        return this.bleScanTask;
    }

    public BluetoothAdapter getBtAdapter() {
        if (this.bleScanTask == null){
            return null;
        }
        return this.bleScanTask.getBtAdapter();
    }

    @NonNull
    @Override
    protected final String getPreferencesName() {
        return DeviceSettingsActivity.PREFERENCE_NAME;
    }

    @Override
    protected final void updateLogLevelSettings(final Set<String> settings) {
        if (bDBG) Log.d(TAG, "LogLevel settings=" + settings);
        super.updateLogLevelSettings(settings);

        boolean isEnabledLog = false;
        if (settings.contains(LOG_LEVEL_CUSTOM_IMS)) {
            isEnabledLog = true;
        }
        bDBG = isEnabledLog;

        isEnabledLog = false;
        if (settings.contains(LOG_LEVEL_CUSTOM_DEV)) {
            isEnabledLog = true;
        }
        for (DeviceConnector device : mDeviceList) {
            device.enableLogLocal(isEnabledLog);
        }
    }

    @Override
    protected void onActivationResult(final boolean result, final EPADevice epaDevice) {
        this.mDevice = new AntiClusterSignageDevice(this);
    }

    @Override
    protected final String[] getPermissions() {
        if (bDBG) Log.d(TAG, "getPermissions");
        // AndroidManifest.xmlのパーミッション
        List<String> permissions = new ArrayList<>(Arrays.asList(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ));
        if(Build.VERSION.SDK_INT >= 26) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        return permissions.toArray(new String[permissions.size()]);
    }

    @Override
    protected void onPermissionGranted() {
        // パーミッションを許可された場合の処理
        super.onPermissionGranted();
    }

    @Override
    protected Class getPermissionActivityClass() {
        return PermissionActivity.class;
    }
}
