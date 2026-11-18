package com.rightnews.live;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        // v3.12: the job runs for news notify OR keyword alerts.
        NewsJobService.ensureScheduled(c);
        // v3.8: lottery draw alarms do not survive a reboot.
        LotteryNotifier.scheduleAll(c);
    }
}
