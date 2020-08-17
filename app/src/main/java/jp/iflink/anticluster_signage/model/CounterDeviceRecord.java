package jp.iflink.anticluster_signage.model;

import java.util.Date;

public class CounterDeviceRecord {

    public CounterDeviceRecord(){}

    public CounterDeviceRecord(CounterDevice data, Date recordTime){
        this.recordTime = recordTime;
        this.alertCounter = data.mAlertCounter;
        this.cautionCounter = data.mCautionCounter;
        this.distantCounter = data.mDistantCounter;
    }

    public static CounterDeviceRecord of(int mAlertCounter, int mCautionCounter, int mDistantCounter, Date recordTime){
        return new CounterDeviceRecord(new CounterDevice(mAlertCounter,mCautionCounter,mDistantCounter), recordTime);
    }

    public int getTotal(){
        return Math.max(alertCounter, 0) +
                Math.max(cautionCounter, 0)  +
                Math.max(distantCounter, 0) ;
    }

    // 記録時刻
    public Date recordTime;
    //  濃厚接触数
    public int alertCounter;
    //  至近距離数
    public int cautionCounter;
    //  周囲数
    public int distantCounter;
}
