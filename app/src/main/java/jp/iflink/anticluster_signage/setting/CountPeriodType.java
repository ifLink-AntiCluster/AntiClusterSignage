package jp.iflink.anticluster_signage.setting;

public class CountPeriodType {
    // 絶対間隔：一定間隔でカウント＆リセットされるものを取得
    public static final int ABSOLUTE = 1;
    // 相対間隔：要求された時点から遡って指定分取得
    public static final int RELATIVE = 2;
}
