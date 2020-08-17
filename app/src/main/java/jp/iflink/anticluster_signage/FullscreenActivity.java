package jp.iflink.anticluster_signage;

import android.Manifest;
import android.annotation.SuppressLint;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.preference.PreferenceManager;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;

import jp.iflink.anticluster_signage.ims.AntiClusterSignageIms;
import jp.iflink.anticluster_signage.task.BleScanTask;
import jp.iflink.anticluster_signage.ui.IServiceFragment;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity {
    private static final String TAG = "Main";

    // アプリ共通設定
    private SharedPreferences prefs;
    // フラグメントマネージャ
    private FragmentManager fragmentManager;
    // Appバー構成設定
    private AppBarConfiguration mAppBarConfig;

    // アプリの必要権限
    String[] permissions = new String[] {
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        mVisible = true;
        mContentView = findViewById(R.id.fullscreen_content);
        fragmentManager = getSupportFragmentManager();

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        mAppBarConfig = new AppBarConfiguration.Builder(navController.getGraph())
                .build();
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfig);

        // Android6.0以降の場合、権限を要求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermission();
        }
        // アプリ共通設定を取得
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        // サービスを開始
        startMicroService();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity onDestroy");
        // BLEスキャン用のレシーバーを解除
        LocalBroadcastManager broadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        broadcastMgr.unregisterReceiver(bleScanReceiver);
        // メインサービスのアンバインド
        unbindService(mServiceConnection);
        // バックグラウンド動作判定
        Resources rsrc = getResources();
        boolean runInBackground = prefs.getBoolean("runin_background", rsrc.getBoolean(R.bool.default_runin_background));
        if (!runInBackground){
            // バックグラウンド動作しない場合、メインサービスを停止
            Intent serviceIntent = new Intent(this, AntiClusterSignageIms.class);
            stopService(serviceIntent);
        }
    }

    private void startMicroService(){
        // サービスの開始
        Intent serviceIntent = new Intent(this, AntiClusterSignageIms.class);
        serviceIntent.putExtra(BleScanTask.NAME, true);

        ComponentName service;
        if (Build.VERSION.SDK_INT >= 26) {
            // Android8以降の場合
            service = startForegroundService(serviceIntent);
        } else {
            // Android7以前の場合
            service = startService(serviceIntent);
        }
        if (service != null){
            // 既にサービスが起動済みの場合は、サービスにバインドする
            bindService(serviceIntent, mServiceConnection, 0);
        }
    }

    // BLEスキャン用レシーバ
    private BroadcastReceiver bleScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // メインサービスにバインド
            Intent serviceIntent = new Intent(FullscreenActivity.this, AntiClusterSignageIms.class);
            bindService(serviceIntent, mServiceConnection, 0);
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentname, IBinder binder) {
            Log.d(TAG, "MainActivity onServiceConnected");
            // サービス取得
            AntiClusterSignageIms mainService = ((AntiClusterSignageIms.LocalBinder) binder).getService();
            // BLEスキャンタスク取得
            BleScanTask bleScanTask = mainService.getBleScanTask();
            if (bleScanTask != null){
                // Bluetooth有効化
                bleScanTask.enableBluetooth(FullscreenActivity.this);
                // BLEスキャン開始
                bleScanTask.startScan();
            }
            // フラグメントにサービスを設定
            NavHostFragment navHost = (NavHostFragment) fragmentManager.findFragmentById(R.id.nav_host_fragment);
            if (navHost != null){
                for (Fragment fragment : navHost.getChildFragmentManager().getFragments()){
                    if (fragment instanceof IServiceFragment){
                        ((IServiceFragment)fragment).setService(mainService);
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentname) {
            Log.d(TAG, "MainActivity onServiceDisconnected");
            // フラグメントのサービスをクリア
            NavHostFragment navHost = (NavHostFragment) fragmentManager.findFragmentById(R.id.nav_host_fragment);
            if (navHost != null){
                for (Fragment fragment : navHost.getChildFragmentManager().getFragments()){
                    if (fragment instanceof IServiceFragment){
                        ((IServiceFragment)fragment).setService(null);
                    }
                }
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.M)
    private void requestPermission() {
        ArrayList<String> list = new ArrayList<>();

        for (String permission : permissions) {
            int check = ContextCompat.checkSelfPermission(getBaseContext(), permission);
            if (check != PackageManager.PERMISSION_GRANTED) {
                list.add(permission);
            }
        }
        if (!list.isEmpty()) {
            requestPermissions(list.toArray(new String[list.size()]), 1);
        }
    }

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 3000;
    private final Handler mHideHandler = new Handler();
    private View mContentView;
    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        //mHideHandler.removeCallbacks(mShowPart2Runnable);
        //mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    @Override
    public void onStart() {
        super.onStart();
        //Log.d(TAG, "MainActivity onStart");
    }

    @Override
    public void onStop() {
        super.onStop();
        //Log.d(TAG, "MainActivity onStop");
    }

    private void showMessage(final String message){
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
