/*
 * Copyright (C) 2012 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.sync.activities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opendatakit.sync.OdkSyncServiceProxy;
import org.opendatakit.sync.R;
import org.opendatakit.sync.SyncApp;
import org.opendatakit.sync.SyncConsts;
import org.opendatakit.sync.SyncPreferences;
import org.opendatakit.sync.exceptions.NoAppNameSpecifiedException;
import org.opendatakit.sync.files.SyncUtil;
import org.opendatakit.sync.service.SyncProgressState;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * An activity for downloading from and uploading to an ODK Aggregate instance.
 * 
 */
public class SyncActivity extends Activity {

  static final String LOGTAG = SyncActivity.class.getSimpleName();

  private static final String ACCOUNT_TYPE_G = "com.google";
  private static final String URI_FIELD_EMPTY = "http://";

  private static final int MENU_ABOUT = 2;
  
  private static final int AUTHORIZE_ACCOUNT_RESULT_ID = 1;
  private static final int ABOUT_ACTIVITY_CODE = 2;

  static final AtomicBoolean refreshRequired = new AtomicBoolean(false);

  public static final void refreshActivityUINeeded() {
    refreshRequired.set(true);
  }

  private EditText uriField;
  private Spinner accountListSpinner;

  String appName;
  private AccountManager accountManager;

  private TextView progressState;
  private TextView progressMessage;

  private boolean authorizeSinceCompletion = true;
  private boolean authorizeAccountSuccessful;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    appName = getIntent().getStringExtra(SyncConsts.INTENT_KEY_APP_NAME);
    if (appName == null) {
      appName = SyncUtil.getDefaultAppName();
    }
    accountManager = AccountManager.get(this);

    setTitle("ODK SYNC");
    setContentView(R.layout.aggregate_activity);
    findViewComponents();
    try {
      SyncPreferences prefs = new SyncPreferences(this, appName);
      initializeData(prefs);
      refreshActivityUINeeded();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    authorizeAccountSuccessful = false;

  }

  @Override
  protected void onStart() {
    super.onStart();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshActivityUINeeded(); 
    launchUpdateThread();
  }

  @Override
  protected void onPause() {
    stopUpdateThread();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    SyncApp.getInstance().getOdkSyncServiceProxy().shutdown();
    super.onDestroy();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    MenuItem item = menu.add(Menu.NONE, MENU_ABOUT, Menu.NONE, getString(R.string.about));
    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == MENU_ABOUT) {
      Intent i = new Intent(this, AboutWrapperActivity.class);
      i.putExtra(SyncConsts.INTENT_KEY_APP_NAME, appName);
      startActivityForResult(i, ABOUT_ACTIVITY_CODE);
    }
    return super.onOptionsItemSelected(item);
  }
  
  private void findViewComponents() {
    uriField = (EditText) findViewById(R.id.aggregate_activity_uri_field);
    accountListSpinner = (Spinner) findViewById(R.id.aggregate_activity_account_list_spinner);
    progressState = (TextView) findViewById(R.id.aggregate_activity_progress_state);
    progressMessage = (TextView) findViewById(R.id.aggregate_activity_progress_message);
  }

  private void initializeData(SyncPreferences prefs) {
    // Add accounts to spinner
    Account[] accounts = accountManager.getAccountsByType(ACCOUNT_TYPE_G);
    List<String> accountNames = new ArrayList<String>(accounts.length);
    for (int i = 0; i < accounts.length; i++)
      accountNames.add(accounts[i].name);

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.select_dialog_item, accountNames);
    accountListSpinner.setAdapter(adapter);

    // Set saved server url
    String serverUri = prefs.getServerUri();

    if (serverUri == null)
      uriField.setText(URI_FIELD_EMPTY);
    else
      uriField.setText(serverUri);

    // Set chosen account
    String accountName = prefs.getAccount();
    if (accountName != null) {
      int index = accountNames.indexOf(accountName);
      accountListSpinner.setSelection(index);
    }
  }

  AlertDialog.Builder buildOkMessage(String title, String message) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setCancelable(false);
    builder.setPositiveButton(getString(R.string.ok), null);
    builder.setTitle(title);
    builder.setMessage(message);
    return builder;
  }

  /**
   * Hooked up to save settings button in aggregate_activity.xml
   */
  public void onClickSaveSettings(View v) {
    // show warning message
    AlertDialog.Builder msg = buildOkMessage(getString(R.string.confirm_change_settings),
        getString(R.string.change_settings_warning));

    msg.setPositiveButton(getString(R.string.save), new OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {

        try {
          SyncPreferences prefs = new SyncPreferences(SyncActivity.this, appName);
          // save fields in preferences
          String uri = uriField.getText().toString();
          if (uri.equals(URI_FIELD_EMPTY))
            uri = null;
          String accountName = (String) accountListSpinner.getSelectedItem();

          prefs.setServerUri(uri);
          prefs.setAccount(accountName);
          // SS Oct 15: clear the auth token here.
          // TODO if you change a user you can switch to their privileges
          // without this.
          Log.d(LOGTAG, "[onClickSaveSettings][onClick] invalidated authtoken");
          invalidateAuthToken(SyncActivity.this, appName);
          refreshActivityUINeeded();
        } catch (NoAppNameSpecifiedException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });

    msg.setNegativeButton(getString(R.string.cancel), null);
    msg.show();

  }

  /**
   * Hooked up to authorizeAccountButton's onClick in aggregate_activity.xml
   */
  public void onClickAuthorizeAccount(View v) {
    try {
      Log.d(LOGTAG, "[onClickAuthorizeAccount] invalidated authtoken");
      invalidateAuthToken(SyncActivity.this, appName);
      SyncPreferences prefs = new SyncPreferences(this, appName);
      Intent i = new Intent(this, AccountInfoActivity.class);
      Account account = new Account(prefs.getAccount(), ACCOUNT_TYPE_G);
      i.putExtra(SyncConsts.INTENT_KEY_APP_NAME, appName);
      i.putExtra(AccountInfoActivity.INTENT_EXTRAS_ACCOUNT, account);
      startActivityForResult(i, AUTHORIZE_ACCOUNT_RESULT_ID);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  /**
   * Hooked to syncNowButton's onClick in aggregate_activity.xml
   */
  public void onClickSyncNowPush(View v) {
    Log.d(LOGTAG, "in onClickSyncNowPush");
    // ask whether to sync app files and table-level files

    // show warning message
    AlertDialog.Builder msg = buildOkMessage(getString(R.string.confirm_reset_app_server),
        getString(R.string.reset_app_server_warning));

    msg.setPositiveButton(getString(R.string.reset), new OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        try {
          SyncPreferences prefs = new SyncPreferences(SyncActivity.this, appName);
          String accountName = prefs.getAccount();
          Log.e(LOGTAG, "[onClickSyncNowPush] timestamp: " + System.currentTimeMillis());
          if (accountName == null) {
            Toast.makeText(SyncActivity.this, getString(R.string.choose_account),
                Toast.LENGTH_SHORT).show();
          } else {
            try {
              disableButtons();
              SyncApp.getInstance().getOdkSyncServiceProxy().pushToServer(appName);
            } catch (RemoteException e) {
              Log.e(LOGTAG, "Problem with push command");
            }
          }
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });

    msg.setNegativeButton(getString(R.string.cancel), null);
    msg.show();
  }

  /**
   * Hooked to syncNowButton's onClick in aggregate_activity.xml
   */
  public void onClickSyncNowPull(View v) {
    Log.d(LOGTAG, "in onClickSyncNowPull");
    // ask whether to sync app files and table-level files
    try {
      SyncPreferences prefs = new SyncPreferences(this, appName);
      String accountName = prefs.getAccount();
      Log.e(LOGTAG, "[onClickSyncNowPull] timestamp: " + System.currentTimeMillis());
      if (accountName == null) {
        Toast.makeText(this, getString(R.string.choose_account), Toast.LENGTH_SHORT).show();
      } else {
        try {
          disableButtons();
          SyncApp.getInstance().getOdkSyncServiceProxy().synchronizeFromServer(appName);
        } catch (RemoteException e) {
          Log.e(LOGTAG, "Problem with pull command");
        }
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

  }

  public static void invalidateAuthToken(Context context, String appName) {
    try {
      SyncPreferences prefs = new SyncPreferences(context, appName);
      AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE_G, prefs.getAuthToken());
      prefs.setAuthToken(null);
      refreshActivityUINeeded();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    // Check that the result was ok for the authorize
    if (requestCode == AUTHORIZE_ACCOUNT_RESULT_ID && resultCode == Activity.RESULT_OK) {
      this.authorizeAccountSuccessful = true;
      this.authorizeSinceCompletion = true;
    }
    refreshActivityUINeeded();
  }

  private synchronized void launchUpdateThread() {
    while (doUpdateGUI == null || !doUpdateGUI.start() ) {
      PollingUpdateRunnable pur = new PollingUpdateRunnable();
      Thread thread = new Thread(null, pur, "pollingUI");
      pur.setThread(thread);
      doUpdateGUI = pur;
    }
  }

  private synchronized void stopUpdateThread() {
    PollingUpdateRunnable pur = doUpdateGUI;
    doUpdateGUI = null;
    if (pur != null) {
      pur.stop();
    }
  }

  private static final int DELAY_PROGRESS_REFRESH = 500;

  private PollingUpdateRunnable doUpdateGUI = null;

  private class PollingUpdateRunnable implements Runnable {
    public AtomicBoolean stopSignal = new AtomicBoolean(false);
    private Thread runningThread = null;
    private boolean started = false;

    public void setThread(Thread runningThread) {
      this.runningThread = runningThread;
    }

    public synchronized boolean start() {
      
      if (stopSignal.get()) {
        // something is waiting for this to die...
        return false;
      }
      
      if (!runningThread.isAlive()) {
        if (!started) {
          // haven't started it yet
          started = true;
          runningThread.start();
          return true;
        }
        // it died (uncaught exception?)...
        return false;
      }
      // it is alive...
      return true;
    }

    public synchronized void stop() {
      stopSignal.set(true);
      if (runningThread != null) {
        for (; runningThread.isAlive();) {
          runningThread.interrupt();
          try {
            runningThread.join();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }
    }

    @Override
    public void run() {
      while (!stopSignal.get()) {
        try {

          runOnUiThread(new Runnable() {
            public void run() {
              SyncActivity.this.updateProgress();
            }
          });

          Thread.sleep(DELAY_PROGRESS_REFRESH);
        } catch (InterruptedException e) {
          Log.i(SyncActivity.LOGTAG, "Thread interrupt exception");
        }
      }
    }
  };

  
  private SyncProgressState priorProgress = null;
  
  void updateProgress() {
    try {

      OdkSyncServiceProxy syncProxy = SyncApp.getInstance().getOdkSyncServiceProxy();
      SyncProgressState progress = syncProxy.getSyncProgress(appName);
      
      if ( progress != priorProgress ) {
        refreshActivityUINeeded();
      }
      priorProgress = progress;
      
      if (progressState != null) {
        if (progress == null) {
          progressState.setText("NULL");
        } else {
          progressState.setText(progress.name());
        }
      } else {
        Log.e(LOGTAG, "NULL progressState variable");
      }

      String msg = syncProxy.getSyncUpdateMessage(appName);
      if (progressMessage != null) {
        if (progress == null) {
          progressMessage.setText("NULL");
        } else {
          progressMessage.setText(msg);
        }
      } else {
        Log.e(LOGTAG, "NULL progressMessage variable");
      }

      if (SyncActivity.refreshRequired.get()) {
        SyncActivity.refreshRequired.set(false);
        try {
          SyncPreferences prefs = new SyncPreferences(this, appName);
          String accountName = prefs.getAccount();
          String serverUri = prefs.getServerUri();

          boolean haveSettings = (accountName != null) && (serverUri != null);
            
          boolean isIdle = ( progress == null || 
              progress == SyncProgressState.COMPLETE || progress == SyncProgressState.ERROR );

          if ( isIdle && !authorizeSinceCompletion ) {
            if ( progress != null && progress != SyncProgressState.COMPLETE ) {
              authorizeAccountSuccessful = false;
            }
          }
          
          boolean restOfButtons = haveSettings && authorizeAccountSuccessful && isIdle;
          
          findViewById(R.id.aggregate_activity_save_settings_button).setEnabled(isIdle);
          findViewById(R.id.aggregate_activity_authorize_account_button)
            .setEnabled(isIdle && !authorizeAccountSuccessful);
          findViewById(R.id.aggregate_activity_sync_now_push_button).setEnabled(restOfButtons);
          findViewById(R.id.aggregate_activity_sync_now_pull_button).setEnabled(restOfButtons);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

    } catch (RemoteException e) {
      Log.e(LOGTAG, "Problem with update messages");
    } catch (Exception e) {
      e.printStackTrace();
      Log.e(LOGTAG, "in runnable for updateProgress");
    }
  }

  private void disableButtons() {
    authorizeSinceCompletion = false;
    findViewById(R.id.aggregate_activity_save_settings_button).setEnabled(false);
    findViewById(R.id.aggregate_activity_authorize_account_button).setEnabled(false);
    findViewById(R.id.aggregate_activity_sync_now_push_button).setEnabled(false);
    findViewById(R.id.aggregate_activity_sync_now_pull_button).setEnabled(false);
  }
}
