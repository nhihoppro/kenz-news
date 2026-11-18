package com.rightnews.live;
import android.content.*;
import java.util.*;

// v3.8: fires when a region's draw starts — posts the
// notification (if enabled) and schedules the next day's
// alarm for that region, so the daily cycle continues
// without the app being opened.
public class LotteryAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int region = i == null ? -1 : i.getIntExtra("region", -1);
        if (region < 0 || region > 2) return;
        if (LotteryNotifier.isEnabled(c, region)) {
            // The inexact alarm may land late; still worth
            // telling the user while the draw runs (~50 min).
            Calendar now = Calendar.getInstance();
            int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int start = LotteryFetcher.DRAW_START_MIN[region];
            if (mins >= start - 5 && mins <= start + 60) LotteryNotifier.notifyNow(c, region);
        }
        LotteryNotifier.scheduleRegion(c, region);
    }
}
