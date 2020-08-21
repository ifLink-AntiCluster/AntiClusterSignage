package jp.iflink.anticluster_signage.model;

import android.bluetooth.BluetoothDevice;

import static java.lang.Boolean.FALSE;

public  class ScannedDevice {
    private static final String UNKNOWN = "Unknown";
    /** BluetoothDevice Address */
    private String mDeviceAddress;
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
    private boolean m1stDetect;
    /** Count type */
    private CountType countType;
    /** target */
    private boolean mTarget;

    public ScannedDevice(BluetoothDevice device, int rssi, long currentTime) {
        reset(device, rssi, currentTime);
    }

    public void reset(BluetoothDevice device, int rssi, long currentTime){
        if (device == null) {
            throw new IllegalArgumentException("BluetoothDevice is null");
        }
        mDeviceAddress = device.getAddress();
        mDisplayName = device.getName();
        if (mDisplayName == null || mDisplayName.isEmpty()) {
            mDisplayName = UNKNOWN;
        }
        mStartTime = -1;
        mCurrentTime = currentTime;
        mAlertFlag = false;
        mRssi = rssi;
        m1stDetect = false;
        countType = null;
        mTarget = true;
    }

    public String getDeviceAddress() { return mDeviceAddress; }

    public int getRssi() { return mRssi;}

    public void setRssi(int rssi) { this.mRssi = rssi; }

    public String getDisplayName() { return mDisplayName; }

    public long getmStartTime() { return mStartTime; }

    public void setmStartTime(long startTime) { this.mStartTime = startTime; }

    public long getmCurrentTime() { return mCurrentTime; }

    public void setCurrentTime(long currentTime) { this.mCurrentTime = currentTime; }

    public boolean getAlertFlag() { return mAlertFlag; }

    public void setAlertFlag(boolean flag) { this.mAlertFlag = flag; }

    public boolean getm1stDetect() { return m1stDetect; }

    public void setm1stDetect(boolean flag) { this.m1stDetect = flag; }

    public CountType getCountType() { return countType; }

    public void setCountType(CountType countType) { this.countType = countType; }

    public boolean isTarget() { return mTarget; }

    public void setTarget(boolean target) { this.mTarget = target; }
}
