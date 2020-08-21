package jp.iflink.anticluster_signage.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jp.iflink.anticluster_signage.R;
import jp.iflink.anticluster_signage.model.CounterDevice;
import jp.iflink.anticluster_signage.model.CounterDeviceRecord;

public class DataStore {
    private static final String TAG = "DataStore";
    //public static final int START_HOUR = 9;
    public static final int COUNT_HOURS = 12;

    private CounterDevice[] counterInfo;

    // ファイルハンドラ
    private FileHandler fileHandler;
    private static final String fileName = "data.txt";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";
    // アプリ共通設定
    private SharedPreferences prefs;
    // リソース
    private Resources rsrc;

    public DataStore(Context context, SharedPreferences prefs){
        this.fileHandler = new FileHandler(context);
        this.prefs = prefs;
        this.rsrc = context.getResources();
    }

    public void init() {
        // データの初期化
        initData();
    }

    public File writeRecord(CounterDevice data, Date recordTime) throws IOException {
        List<CounterDeviceRecord> recordList;
        // データファイルの存在チェック
        File file = fileHandler.getFile(fileName);
        if (file.exists() && file.canRead()){
            // 既にデータファイルが存在する場合はすべて読み込み
            recordList = load();
        } else {
            recordList = new ArrayList<>();
        }
        // レコードを作成
        CounterDeviceRecord record = new CounterDeviceRecord(data, recordTime);
        //  リストに追加
        recordList.add(record);
        // リストから範囲外（現在～２週間前でない）のレコードを削除
        removeOutdatedRecord(recordList, recordTime);
        // リストを保存
        return save(recordList);
    }

    public List<CounterDeviceRecord> load() throws IOException {
        SimpleDateFormat dtFmt = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US);
        List<CounterDeviceRecord> recordList = new ArrayList<>();
        // データファイルを取得
        File file = fileHandler.getFile(fileName);
        if (file.exists() && file.canRead()){
            try(BufferedReader reader = new BufferedReader(new FileReader(file))) {
                // ヘッダ行を読み飛ばす
                String line = reader.readLine();
                //  データ行をすべて読み込む
                while((line = reader.readLine()) != null){
                    String[] columns = line.split(",");
                    if (columns.length != 4){
                        continue;
                    }
                    try {
                        // レコードを作成
                        CounterDeviceRecord record = new CounterDeviceRecord();
                        record.recordTime  = dtFmt.parse(columns[0]);
                        record.alertCounter = Integer.parseInt(columns[1]);
                        record.cautionCounter = Integer.parseInt(columns[2]);
                        record.distantCounter = Integer.parseInt(columns[3]);
                        //  リストに追加
                        recordList.add(record);
                    } catch (ParseException e){
                        Log.e(TAG, e.getMessage(), e);
                    }
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        return recordList;
    }

    protected File save(List<CounterDeviceRecord> recordList) throws IOException {
        SimpleDateFormat dtFmt = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US);
        // データファイルを取得
        File file = fileHandler.getFile(fileName);
        try (
                BufferedWriter writer = new BufferedWriter(new FileWriter(file))
        ) {
            // ヘッダ行を出力
            writer.write("recordTime,alertCounter,cautionCounter,distantCounter");
            writer.newLine();
            // データ行をすべて出力
            for (CounterDeviceRecord record : recordList){
                writer.write(dtFmt.format(record.recordTime) + ",");
                writer.write(record.alertCounter+ ",");
                writer.write(record.cautionCounter+ ",");
                writer.write(record.distantCounter + "");
                writer.newLine();
            }
        }
        return file;
    }

    public Date update(List<CounterDeviceRecord> recordList, Date baseDate){
        // 描画開始時刻[時] を取得
        final int START_HOUR = getIntFromString(prefs, "graph_start_hour", rsrc.getInteger(R.integer.default_graph_start_hour));
        // 前回集計時刻
        Date prevRecordTime = null;
        // 10分単位のグラフデータマップ
        Map<Date, CounterDeviceRecord> graphDataMap = new TreeMap<>();
        if (!recordList.isEmpty()){
            // グラフデータの読み込み
            Date toDate = getTenMinutesDate(baseDate, false);
            Date baseHourDate = getHourDate(toDate);
            Date fromDate = getFromDateByDays(baseHourDate, -13);
            // 基準時刻から2週間前までのデータを取得する
            for (CounterDeviceRecord record : recordList) {
                if (!fromDate.after(record.recordTime) && !toDate.before(record.recordTime)) {
                    // 10分単位の時刻を取得
                    Date recordTime10m = getTenMinutesDate(record.recordTime, true);
                    // 10分単位でデータをサマリする
                    CounterDeviceRecord summary = graphDataMap.get(recordTime10m);
                    if (summary == null){
                        summary = new CounterDeviceRecord();
                        summary.cautionCounter = record.cautionCounter;
                        summary.alertCounter = record.alertCounter;
                        summary.distantCounter = record.distantCounter;
                        summary.recordTime = recordTime10m;
                        graphDataMap.put(recordTime10m, summary);
                    } else {
                        summary.cautionCounter += record.cautionCounter;
                        summary.alertCounter += record.alertCounter;
                        summary.distantCounter += record.distantCounter;
                    }
                    // 最も近い記録時刻を前回集計時刻とする
                    if (prevRecordTime == null || record.recordTime.after(prevRecordTime)){
                        prevRecordTime = record.recordTime;
                    }
                }
            }
        }
        // 10分単位のグラフデータリストに変換
        Collection<CounterDeviceRecord> graphDataList = graphDataMap.values();
        // グラフデータの初期化
        counterInfo = new CounterDevice[6*COUNT_HOURS];
        {
            // 読み込み開始時刻と終了時刻を設定
            Date toDate = getTenMinutesDate(baseDate, true);
            Date baseTime = toDate;
            Date fromDate = getDayHour(toDate, START_HOUR);
            // 基準時刻を含む当日分のデータのみ取得する
            for (CounterDeviceRecord data : graphDataList) {
                if (!fromDate.after(data.recordTime) && !toDate.before(data.recordTime)){
                    // 時間[HOUR]を取得する
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(data.recordTime);
                    int hour = cal.get(Calendar.HOUR_OF_DAY);
                    // 分[MINUTE]を取得する
                    int minute = cal.get(Calendar.MINUTE);
                    // インデックスを求める
                    int idx = (hour-START_HOUR)*6 + minute/10;
                    if (idx < 0 || idx >= counterInfo.length){
                        // 描画対象外の時間の場合、追加しない
                        continue;
                    }
                    // 対象の時分[HOUR-MINUTE]に加算する
                    if (counterInfo[idx]  == null){
                        counterInfo[idx] = new CounterDevice();
                    }
                    counterInfo[idx].mAlertCounter += data.alertCounter;
                    counterInfo[idx].mCautionCounter += data.cautionCounter;
                    counterInfo[idx].mDistantCounter += data.distantCounter;
                }
            }
            for (int idx=0; idx<counterInfo.length; idx++){
                if (counterInfo[idx] == null){
                    // 基準日時の時間[HOUR]を取得する
                    int hour = idx/6 + START_HOUR;
                    int minute = (idx%6) * 10;
                    Calendar counterDate = Calendar.getInstance();
                    if (hour < 24){
                        counterDate.set(Calendar.HOUR_OF_DAY, hour);
                    } else {
                        counterDate.add(Calendar.DATE, 1);
                        counterDate.set(Calendar.HOUR_OF_DAY, hour-24);
                    }
                    counterDate.set(Calendar.MINUTE, minute);
                    if (counterDate.getTime().before(baseDate)) {
                        // 過去の無実行時間のデータは-1に設定する
                        counterInfo[idx] = new CounterDevice();
                        counterInfo[idx].mAlertCounter = -1;
                        counterInfo[idx].mCautionCounter = -1;
                        counterInfo[idx].mDistantCounter = -1;
                    } else {
                        // 未来の無実行時間のデータは0に設定する
                        counterInfo[idx] = new CounterDevice();
                    }
                }
            }
        }
        return prevRecordTime;
    }

    public Date getDayHourStart(Date targetDate){
        // 描画開始時刻[時] を取得
        final int START_HOUR = getIntFromString(prefs, "graph_start_hour", rsrc.getInteger(R.integer.default_graph_start_hour));
        return getDayHour(targetDate, START_HOUR);
    }

    private static Date getDayHour(Date targetDate, int hour){
        // 判定開始時刻を設定
        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        // 時刻部分をクリア
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date getDayOnly(Date targetDate){
        return getDayHour(targetDate, 0);
    }

    private static Date getHalfDayOnly(Date targetDate){
        Calendar cal = Calendar.getInstance();
        cal.setTime(targetDate);
        if (cal.get(Calendar.HOUR_OF_DAY) < 12){
            cal.set(Calendar.HOUR_OF_DAY, 0);
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 12);
        }
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date getTenMinutesDate(Date baseDate, boolean toGraphDate){
        Calendar cal = Calendar.getInstance();
        cal.setTime(baseDate);
        // 10分単位の時刻に調整する
        int tenMinutes = (cal.get(Calendar.MINUTE)/10)*10;
        cal.set(Calendar.MINUTE, tenMinutes);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (toGraphDate){
            // 記録時刻はグラフ時刻から10分後なので、10分引いて調整する
            cal.add(Calendar.MINUTE, -10);
        }
        return cal.getTime();
    }

    private static Date getHourDate(Date baseDate){
        Calendar cal = Calendar.getInstance();
        cal.setTime(baseDate);
        // 分[MINUTE]以下をクリア
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date getFromDateByHours(Date toDate, int hours){
        Calendar cal = Calendar.getInstance();
        cal.setTime(toDate);
        // 分[MINUTE]以下をクリア
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // 指定した分[MINUTE]数を加算
        cal.add(Calendar.HOUR_OF_DAY, hours);
        return cal.getTime();
    }

    private static Date getDayEndDate(Date baseDate){
        Calendar cal = Calendar.getInstance();
        cal.setTime(baseDate);
        // 時刻を23:59:59.999 に設定
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTime();
    }

    private static Date getFromDateByDays(Date toDate, int days){
        Calendar cal = Calendar.getInstance();
        cal.setTime(toDate);
        // 時間[HOUR]以下をクリア
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        // 指定した日数を加算
        cal.add(Calendar.DATE, days);
        return cal.getTime();
    }

    private static int getDiffDays(Date baseDay, Date targetDay) {
        // ミリ秒単位での差分算出
        long diffTime = baseDay.getTime() - targetDay.getTime();
        // 日単位の差分を求める
        return (int)(diffTime / (1000 * 60 * 60 * 24));
    }

    private static int getDiffMinutes(Date baseTime, Date targetTime){
        // ミリ秒単位での差分算出
        long diffTime = baseTime.getTime() - targetTime.getTime();
        // 分単位の差分を求める
        return (int)(diffTime / (1000 * 60));
    }

    protected void removeOutdatedRecord(List<CounterDeviceRecord> recordList, Date baseDate) {
        Date toDate = getDayEndDate(baseDate);
        Date fromDate = getFromDateByDays(toDate, -13);
        // リストから範囲外（現在～２週間前でない）のレコードを削除
        Iterator<CounterDeviceRecord> recordIt = recordList.iterator();
        while (recordIt.hasNext()) {
            CounterDeviceRecord record = recordIt.next();
            if (fromDate.after(record.recordTime) || toDate.before(record.recordTime)) {
                recordIt.remove();
            }
        }
    }

    private void initData(){
        // グラフデータの初期化
        counterInfo = new CounterDevice[6*COUNT_HOURS];
        for(int idx = 0; idx < counterInfo.length; idx++){
            counterInfo[idx] = new CounterDevice();
        }
    }

    public CounterDevice[] getCounterInfo() {
        return counterInfo;
    }

    private int getIntFromString(SharedPreferences prefs, String key, int defaultValue){
        String value = prefs.getString(key, String.valueOf(defaultValue));
        return Integer.parseInt(value);
    }
}
