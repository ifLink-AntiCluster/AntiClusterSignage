package jp.iflink.anticluster_signage.model;

import android.bluetooth.BluetoothDevice;

import static java.lang.Boolean.FALSE;

public  class ScannedDevice {
    private static final String UNKNOWN = "Unknown";
    /** BluetoothDevice */
    private BluetoothDevice mDevice;
    /** RSSI */
    private int mRssi;
    /** Display Name */
    private String mDisplayName;
    /** Start Time **/
    private long mStartTime;
    /** Current Time **/
    private long mCurrentTime;
    /** alert flag **/
    private boolean mAlertFlag;
    /** 1st Detect flag **/
    private Boolean m1stDetect;


    public ScannedDevice(BluetoothDevice device, int rssi) {
        if (device == null) {
            throw new IllegalArgumentException("BluetoothDevice is null");
        }
        mDevice = device;
        mDisplayName = device.getName();
        if ((mDisplayName == null) || (mDisplayName.length() == 0)) {
            mDisplayName = UNKNOWN;
        }
        mRssi = rssi;
        m1stDetect = FALSE;
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    public int getRssi() {
        return mRssi;
    }

    public void setRssi(int rssi) {
        mRssi = rssi;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public long getmStartTime() {
        return mStartTime;
    }

    public void setmStartTime(long startTime) {
        mStartTime = startTime;
    }

    public long getmCurrentTime() {
        return mCurrentTime;
    }

    public void setCurrentTime(long currentTime) {
        mCurrentTime = currentTime;
    }

    public boolean getAlertFlag() {
        return mAlertFlag;
    }

    public void setAlertFlag(boolean flag) { mAlertFlag = flag; }

    public boolean getm1stDetect() { return m1stDetect; }

    public void setm1stDetect(boolean flag) { m1stDetect = flag; }
}
