package jp.iflink.anticluster_signage.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import jp.iflink.anticluster_signage.model.CounterDevice;
import jp.iflink.anticluster_signage.model.CounterDeviceRecord;
import jp.iflink.anticluster_signage.util.DataStore;

public class SampleDataStore extends DataStore {
    private static final String TAG = "SampleDataStore";
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    public SampleDataStore(Context context, SharedPreferences prefs) {
        super(context, prefs);
    }

    @Override
    public List<CounterDeviceRecord> load() throws IOException {
        SimpleDateFormat dtFmt = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US);
        List<CounterDeviceRecord> recordList = new ArrayList<>();

        Calendar today = Calendar.getInstance();
        if (today.getTime().before(getDayHourStart(today.getTime()))){
            // 開始時刻以前の場合は、昨日の23:59:59とする
            today.add(Calendar.DATE, -1);
            today.set(Calendar.HOUR_OF_DAY, 23);
            today.set(Calendar.MINUTE, 59);
            today.set(Calendar.SECOND, 59);
        }
        String todayStr = new SimpleDateFormat("yyyy-MM-dd").format(today.getTime())+"T";

        try {
            recordList.add(CounterDeviceRecord.of(5, 15, 0, dtFmt.parse(todayStr+"09:10:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"09:20:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"09:30:00")));
            recordList.add(CounterDeviceRecord.of(8, 23, 0, dtFmt.parse(todayStr+"09:40:00")));
            recordList.add(CounterDeviceRecord.of(8, 20, 0, dtFmt.parse(todayStr+"09:50:00")));
            recordList.add(CounterDeviceRecord.of(10, 25, 0, dtFmt.parse(todayStr+"10:00:00")));
            recordList.add(CounterDeviceRecord.of(20, 25, 0, dtFmt.parse(todayStr+"10:10:00")));
            recordList.add(CounterDeviceRecord.of(20, 25, 0, dtFmt.parse(todayStr+"10:20:00")));
            recordList.add(CounterDeviceRecord.of(15, 35, 0, dtFmt.parse(todayStr+"10:30:00")));
            recordList.add(CounterDeviceRecord.of(14, 30, 0, dtFmt.parse(todayStr+"10:40:00")));
            recordList.add(CounterDeviceRecord.of(5, 15, 0, dtFmt.parse(todayStr+"10:50:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"11:00:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"11:10:00")));
            recordList.add(CounterDeviceRecord.of(8, 23, 0, dtFmt.parse(todayStr+"11:20:00")));
            recordList.add(CounterDeviceRecord.of(8, 20, 0, dtFmt.parse(todayStr+"11:30:00")));
            recordList.add(CounterDeviceRecord.of(10, 25, 0, dtFmt.parse(todayStr+"11:40:00")));
            recordList.add(CounterDeviceRecord.of(11, 27, 0, dtFmt.parse(todayStr+"11:50:00")));
            recordList.add(CounterDeviceRecord.of(9, 23, 0, dtFmt.parse(todayStr+"12:00:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"12:10:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"12:20:00")));
            recordList.add(CounterDeviceRecord.of(10, 15, 0, dtFmt.parse(todayStr+"12:30:00")));
            recordList.add(CounterDeviceRecord.of(8, 15, 0, dtFmt.parse(todayStr+"12:40:00")));

        } catch (ParseException e) {
            Log.e(TAG, e.getMessage(), e);
        }

        return recordList;
    }

    @Override
    public File writeRecord(CounterDevice data, Date recordTime) throws IOException {
        return null;
    }
}
