package jp.iflink.anticluster_signage.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Point;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;

import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.BarChart;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import jp.iflink.anticluster_signage.BuildConfig;
import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.ims.AntiClusterSignageIms;
import jp.iflink.anticluster_signage.setting.CountPeriodType;
import jp.iflink.anticluster_signage.task.BleScanTask;
import jp.iflink.anticluster_signage.util.DataStore;
import jp.iflink.anticluster_signage.util.GraphManager;

import static android.content.Context.WINDOW_SERVICE;

public class HomeFragment extends Fragment implements IServiceFragment {
    private static final String TAG = "Home";
    /** ログ出力切替フラグ. */
    private boolean bDBG = false;
    // グラフエリア
    private BarChart mBarChartGraph;
    private LinearLayout mBarChartGraphBlock;
    // データストア
    private DataStore dataStore;
    // グラフマネージャ
    private GraphManager graphManager;
    // 前回集計時刻テキスト表示
    private TextView mRecordTime;
    private TextView mRecordTimePrefix;
    private LinearLayout mRecordTimeBlock;
    // 前回集計時時刻
    private Date mPrevRecordTime;
    // カウント値テキスト表示
    private TextView mNearCount;
    private TextView mAroundCount;
    private TextView mFarCount;
    // 設定ボタン
    private FloatingActionButton mSettingButton;
    // カウント詳細
    private TableLayout mCountDetailTable;
    private TextView mAllDeviceCount;
    private TextView mDeviceCount;
    private TextView mScanDeviceCount;
    // 各種設定値
    private int mUpdateTime;
    private boolean mDrawCountDetail;
    private int countPeriodType;
    // 画面更新用タイマー
    private Timer screenUpdateTimer;
    // データ集計用タイマー
    private Timer recordTimeTimer;
    // 共通ハンドラ
    private Handler mHandler;
    // アプリ共通設定
    private SharedPreferences prefs;
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

    private static final float BASE_LS_WIDTH_DP = 640f;
    private static final float BASE_LS_HEIGHT_DP = 360f;
    private class ZoomRate {
        float vertical = 1.0f;
        float horizon = 1.0f;
    }
    private static final float BG_IMG_WIDTH_PX = 1600f;
    private static final float BG_IMG_HEIGHT_PX = 900f;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "HomeFragment onCreate");
        // ハンドラの初期化
        mHandler = new Handler();
        // アプリ共通設定を取得
        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        // データストアの初期化
        dataStore = new DataStore(getContext(), prefs);
        dataStore.init();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "HomeFragment onCreateView");
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_home, container, false);
        // 倍率の取得
        ZoomRate zoom = getScreenZoomRate();
        //showMessage("zoom.vertical="+zoom.vertical+" zoom.horizon="+zoom.horizon);
        // カウント値テキスト表示
        mNearCount = root.findViewById(R.id.tv_near);
        mAroundCount = root.findViewById(R.id.tv_around);
        mFarCount = root.findViewById(R.id.tv_far);
        adjustPosition(zoom, mNearCount, false);
        adjustPosition(zoom, mAroundCount, false);
        adjustPosition(zoom, mFarCount, false);
        // バージョンを表示
        TextView mVersion = root.findViewById(R.id.tv_version);
        mVersion.setText(BuildConfig.VERSION_NAME);
        adjustPosition(zoom, mVersion, true);
        // 前回集計時刻
        mRecordTime = root.findViewById(R.id.tv_recordtime);
        mRecordTimePrefix = root.findViewById(R.id.tv_recordtime_prefix);
        mRecordTimeBlock = root.findViewById(R.id.tv_recordtime_block);
        adjustPosition(zoom, mRecordTimeBlock, true, mRecordTime, mRecordTimePrefix);
        // 設定ボタン
        mSettingButton = root.findViewById(R.id.btn_setting);
        adjustPosition(zoom, mSettingButton, false);
        mSettingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavHostFragment.findNavController(HomeFragment.this)
                        .navigate(R.id.action_HomeFragment_to_SettingFragment);
            }
        });
        // カウント詳細
        mCountDetailTable = root.findViewById(R.id.tbl_count_detail);
        mAllDeviceCount = root.findViewById(R.id.tv_alldevice_count);
        mDeviceCount = root.findViewById(R.id.tv_device_count);
        mScanDeviceCount = root.findViewById(R.id.tv_scan_device_count);
        adjustPosition(zoom, mCountDetailTable, false, mAllDeviceCount, mDeviceCount, mScanDeviceCount);
        // グラフ描画の準備
        mBarChartGraph = root.findViewById(R.id.chart_graph);
        mBarChartGraphBlock = root.findViewById(R.id.chart_graph_block);
        graphManager = new GraphManager(mBarChartGraph, prefs, getResources());
        // グラフ位置の調整
        adjustPosition(zoom, mBarChartGraphBlock, mBarChartGraph);

        return root;
    }

    private ZoomRate getScreenZoomRate(){
        ZoomRate zoom = new ZoomRate();
        final float density = getResources().getDisplayMetrics().density;
        WindowManager wm = (WindowManager)getActivity().getSystemService(WINDOW_SERVICE);
        if(wm == null) {
            return zoom;
        }
        Display disp = wm.getDefaultDisplay();
        Point realSize = new Point();
        disp.getRealSize(realSize);
        int rswDp = (int)(realSize.x/density);
        int rshDp = (int)(realSize.y/density);
        zoom.horizon = rswDp / BASE_LS_WIDTH_DP;
        zoom.vertical = rshDp / BASE_LS_HEIGHT_DP;

        float bgHorizon = BG_IMG_WIDTH_PX/realSize.x;
        float bgVertical  = BG_IMG_HEIGHT_PX/realSize.y;
        if (bgHorizon > bgVertical){
            // 横の倍率について、画像の伸縮率を掛ける
            zoom.horizon = zoom.horizon * (bgHorizon/bgVertical);
        }
        return zoom;
    }

    private void adjustPosition(ZoomRate zoom, TextView view, boolean isLeft){
        // マージンの調整
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        lp.topMargin = (int)(lp.topMargin * zoom.vertical);
        if (isLeft){
            lp.leftMargin = (int)(lp.leftMargin * zoom.horizon);
        } else {
            lp.rightMargin = (int)(lp.rightMargin * zoom.horizon);
        }
        // フォントサイズの調整
        float zoomText = Math.min(zoom.vertical, zoom.horizon);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,  view.getTextSize() * zoomText);
        // レイアウト調整リクエスト
        view.requestLayout();
    }

    private void adjustPosition(ZoomRate zoom, LinearLayout block, boolean isLeft, TextView... views){
        // マージンの調整
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) block.getLayoutParams();
        lp.topMargin = (int)(lp.topMargin * zoom.vertical);
        if (isLeft){
            lp.leftMargin = (int)(lp.leftMargin * zoom.horizon);
        } else {
            lp.rightMargin = (int)(lp.rightMargin * zoom.horizon);
        }
        for (TextView view : views){
            // フォントサイズの調整
            float zoomText = Math.min(zoom.vertical, zoom.horizon);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX,  view.getTextSize() * zoomText);
            // レイアウト調整リクエスト
            view.requestLayout();
        }
        // レイアウト調整リクエスト
        block.requestLayout();
    }

    private void adjustPosition(ZoomRate zoom, LinearLayout block, BarChart chart){
        // マージンの調整
        ViewGroup.MarginLayoutParams blockLp = (ViewGroup.MarginLayoutParams) block.getLayoutParams();
        blockLp.topMargin = (int)(blockLp.topMargin * zoom.vertical);
        blockLp.leftMargin = (int)(blockLp.leftMargin * zoom.horizon);
        // サイズの調整
        ViewGroup.MarginLayoutParams chartLp = (ViewGroup.MarginLayoutParams) chart.getLayoutParams();
        chartLp.width = (int)(chartLp.width * zoom.horizon);
        chartLp.height = (int)(chartLp.height * zoom.vertical);
        // レイアウト調整リクエスト
        chart.requestLayout();
        block.requestLayout();
    }

    private void adjustPosition(ZoomRate zoom, View view, boolean isLeft){
        // マージンの調整
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        lp.topMargin = (int)(lp.topMargin * zoom.vertical);
        if (isLeft){
            lp.leftMargin = (int)(lp.leftMargin * zoom.horizon);
        } else {
            lp.rightMargin = (int)(lp.rightMargin * zoom.horizon);
        }
        // レイアウト調整リクエスト
        view.requestLayout();
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "HomeFragment onStart");
        // デバッグフラグの設定
        bDBG = getDebugFlag(prefs);
        // カウント表示のクリア
        mNearCount.setText("");
        mAroundCount.setText("");
        mFarCount.setText("");
        // 各種設定の取得
        Resources rsrc = getResources();
        mUpdateTime = getIntFromString(prefs, "update_time", rsrc.getInteger(R.integer.default_update_time));
        mDrawCountDetail = getBoolean(prefs,"draw_count_detail", false);
        countPeriodType = Integer.parseInt(prefs.getString("count_period_type", rsrc.getString(R.string.default_count_period_type)));

        if (mDrawCountDetail){
            // カウント詳細を表示
            if (mCountDetailTable.getVisibility() != View.VISIBLE){
                mCountDetailTable.setVisibility(View.VISIBLE);
            }
        } else {
            // カウント詳細を消す
            mCountDetailTable.setVisibility(View.INVISIBLE);
        }

        // 画面更新用定期処理（5秒間隔）
        if (screenUpdateTimer == null) {
            screenUpdateTimer = new Timer(true);
            screenUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // BLEスキャンタスク取得
                            BleScanTask bleService = getBleScanTask();
                            if (bleService == null) {
                                if (bDBG) Log.w(TAG, "bleService is null");
                                return;
                            }
                            // カウント表示の更新
                            if (countPeriodType == CountPeriodType.ABSOLUTE) {
                                mNearCount.setText(String.valueOf(bleService.getNearCount()));
                                mAroundCount.setText(String.valueOf(bleService.getAroundCount()));
                                mFarCount.setText(String.valueOf(bleService.getFarCount()));
                            } else if (countPeriodType == CountPeriodType.RELATIVE){
                                mNearCount.setText(String.valueOf(bleService.getCurrentNearCount()));
                                mAroundCount.setText(String.valueOf(bleService.getCurrentAroundCount()));
                                mFarCount.setText(String.valueOf(bleService.getCurrentFarCount()));
                            } else {
                                Log.e(TAG, "countPeriodType is unknown");
                            }
                            // カウント詳細の描画
                            if (mDrawCountDetail){
                                mAllDeviceCount.setText(String.valueOf(bleService.getAllDeviceCount()));
                                mDeviceCount.setText(String.valueOf(bleService.getDeviceCount()));
                                mScanDeviceCount.setText(String.valueOf(bleService.getScanDeviceCount()));
                            }
                        }
                    });
                }
            }, 0, mUpdateTime * 1000);
        }

        if (recordTimeTimer == null) {
            recordTimeTimer = new Timer(true);
            // 初期実行待ち時間（次の分の05秒）
            int timerDelay = getDelayMillisToNextMinute() + 5000;
            // 前回集計時刻、グラフ更新用定期処理（1分間隔）
            recordTimeTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    // 10分毎に実施
                    if (!isJustTimeMinuteOf(10)) {
                        return;
                    }
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // BLEスキャンタスク取得
                            BleScanTask bleService = getBleScanTask();
                            if (bleService == null) {
                                if (bDBG) Log.w(TAG, "bleService is null");
                                return;
                            }
                            // 前回集計時刻を更新
                            mRecordTime.setText(bleService.getRecordTime());
                            mRecordTimeBlock.setVisibility(View.VISIBLE);
                            // グラフ更新
                            mPrevRecordTime = graphManager.loadData(getToday());
                            graphManager.updateGraph();
                        }
                    });
                }
            }, timerDelay, 1000 * 60);
        }

        // グラフマネージャの初期化
        graphManager.init(dataStore);
        // データ読み込み、前回集計時刻の取得
        this.mPrevRecordTime = graphManager.loadData(getToday());
        if (mPrevRecordTime != null){
            // 前回集計時刻を更新
            mRecordTime.setText(getDateTimeText(mPrevRecordTime));
            mRecordTimeBlock.setVisibility(View.VISIBLE);
        }
        // グラフの描画
        graphManager.updateGraphDayTimes();
    }

    @Override
    public void onStop() {
        super.onStop();
        //Log.d(TAG, "HomeFragment onStop");
    }

    @Override
    public void onResume(){
        super.onResume();
        //Log.d(TAG, "HomeFragment onResume");
    }

    @Override
    public void onPause(){
        super.onPause();
        //Log.d(TAG, "HomeFragment onPause");
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        Log.d(TAG, "HomeFragment onDestroy");
        if (screenUpdateTimer != null){
            screenUpdateTimer.cancel();
            screenUpdateTimer = null;
        }
        if (recordTimeTimer != null){
            recordTimeTimer.cancel();
            recordTimeTimer = null;
        }
    }

    private BleScanTask getBleScanTask(){
        if (mService == null || mService.get() == null){
            return null;
        }
        return mService.get().getBleScanTask();
    }

    private Date getToday(){
        Calendar today = Calendar.getInstance();
        if (today.getTime().before(dataStore.getDayHourStart(today.getTime()))){
            // 開始時刻以前の場合は、昨日の23:59:59とする
            today.add(Calendar.DATE, -1);
            today.set(Calendar.HOUR_OF_DAY, 23);
            today.set(Calendar.MINUTE, 59);
            today.set(Calendar.SECOND, 59);
        }
        return today.getTime();
    }

    private int getDelayMillisToNextMinute(){
        Calendar nextMinute = Calendar.getInstance();
        nextMinute.set(Calendar.SECOND, 0);
        nextMinute.set(Calendar.MILLISECOND, 0);
        nextMinute.add(Calendar.MINUTE, 1);
        return (int)(nextMinute.getTime() .getTime() - new Date().getTime());
    }

    private boolean isJustTimeMinuteOf(int minute){
        // 時刻を1分単位の精度にして指定したminute毎かどうかを判定
        return (int)(new Date().getTime()/60000) % minute == 0;
    }

    private String getDateTimeText(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(date);
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

    private boolean getDebugFlag(SharedPreferences prefs){
        Set<String> loglevelSet  = prefs.getStringSet("loglevel", null);
        if (loglevelSet != null && loglevelSet.contains(AntiClusterSignageIms.LOG_LEVEL_CUSTOM_IMS)){
            return true;
        }
        return false;
    }

    private void showMessage(final String message){
        Activity activity = getActivity();
        if (activity == null){
            return;
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
