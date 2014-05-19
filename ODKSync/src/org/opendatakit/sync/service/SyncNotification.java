package org.opendatakit.sync.service;


import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

public final class SyncNotification {
  private static final String LOGTAG = SyncNotification.class.getSimpleName();

  private final Context cntxt;
  private final String appName;
  private final NotificationManager notificationManager;

  private int messageNum;
  private String updateText;
  private SyncProgressState progressState;
  
  public SyncNotification(Context context, String appName) {
    this.cntxt = context;
    this.appName = appName;
    this.notificationManager = (NotificationManager) cntxt
        .getSystemService(Context.NOTIFICATION_SERVICE);
    this.messageNum = 0;
    this.updateText = null;
    this.progressState = SyncProgressState.INIT;
  }

  public synchronized void updateNotification(SyncProgressState pgrState, String text, int maxProgress,
      int progress, boolean indeterminateProgress) {
    this.progressState = pgrState;
    this.updateText = text;
    Notification.Builder builder = new Notification.Builder(cntxt);
    builder.setContentTitle("ODK syncing " + appName).setContentText(text).setAutoCancel(false).setOngoing(true);
    builder.setSmallIcon(android.R.drawable.ic_popup_sync);
    builder.setProgress(maxProgress, progress, indeterminateProgress);

    Notification syncNotif = builder.getNotification();
    // syncNotif.flags |= Notification.FLAG_NO_CLEAR;

    notificationManager.notify(appName, messageNum, syncNotif);
    Log.e(LOGTAG, messageNum + " Update SYNC Notification -" + appName + " TEXT:" + text + " PROG:"
        + progress);

  }

  public synchronized String getUpdateText() {
    return updateText;
  }

  public synchronized SyncProgressState getProgressState() {
    return progressState;
  }

  public synchronized void clearNotification() {
    notificationManager.cancel(appName, messageNum);
  }

}
