package jp.iflink.anticluster_signage.model;

public class CounterDevice {

    public int mAlertCounter;
    public int mCautionCounter;
    public int mDistantCounter;

    public CounterDevice(){}

    public CounterDevice(int mAlertCounter, int mCautionCounter, int mDistantCounter) {
        this.mAlertCounter = mAlertCounter;
        this.mCautionCounter = mCautionCounter;
        this.mDistantCounter = mDistantCounter;
    }

    public int getNearCount(){
        return this.mAlertCounter + this.mCautionCounter;
    }

    public int getAroundCount(){
        return this.mDistantCounter;
    }

    public int getTotal(){
        return Math.max(mAlertCounter, 0) +
                Math.max(mCautionCounter, 0)  +
                Math.max(mDistantCounter, 0) ;
    }

    public boolean hasData(){
        return (mCautionCounter >= 0) && (mAlertCounter >= 0) && (mDistantCounter >= 0);
    }
}
