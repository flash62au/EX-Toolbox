/* Copyright (C) 2017 M. Steve Todd mstevetodd@gmail.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package dcc_ex.ex_toolbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import dcc_ex.ex_toolbox.logviewer.ui.LogViewerActivity;
import dcc_ex.ex_toolbox.type.message_type;
import dcc_ex.ex_toolbox.util.LocaleHelper;

public class reconnect_status extends AppCompatActivity {

    private threaded_application mainapp;  // hold pointer to mainapp
    private String prog = "";
    private boolean backOk = true;
    private boolean retryFirst = false;

    private LinearLayout screenNameLine;
    private Toolbar toolbar;
    private LinearLayout statusLine;

    //Handle messages from the communication thread back to this thread (responses from withrottle)
    @SuppressLint("HandlerLeak")
    class reconnect_status_handler extends Handler {

        public reconnect_status_handler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case message_type.RESPONSE:
                    if (!retryFirst) {              // Got a message from WiThrottle server so the socket must already be ok.  This means the
                        reconnected();              // RETRY/RECONNECT sequence was over before this Screen came up, so just resume normal ops.
                    }
                    break;
                case message_type.WIT_CON_RETRY:
                    retryFirst = true;
                    refresh_reconnect_status(msg.obj.toString());
                    break;
                case message_type.WIT_CON_RECONNECT:
                    refresh_reconnect_status(msg.obj.toString());
                    reconnected();
                    break;
                case message_type.RESTART_APP:
                case message_type.RELAUNCH_APP:
                case message_type.DISCONNECT:
                case message_type.SHUTDOWN:
                    disconnect();
                    break;
            }
        }
    }

    public void refresh_reconnect_status(String status) {
        TextView tv = findViewById(R.id.reconnect_status);
        if (status != null) {
            prog = prog + ".";
            if (prog.length() > 5)
                prog = ".";
            String rslt = status + prog;
            tv.setText(rslt);
        }
    }

    public void reconnected() {
        if (backOk) {                        // ensure we only run this once
            backOk = false;
            TextView tv = findViewById(R.id.reconnect_help);
            tv.setText(getString(R.string.reconnect_success));
            if (mainapp.reconnect_status_msg_handler != null) {
                mainapp.reconnect_status_msg_handler.postDelayed(delayCloseScreen, 500L);
            } else {
                Log.d("EX_Toolbox", "Reconnect: handler already null");
                closeScreen();
            }
        }
    }

    @SuppressLint("Recycle")
    private Runnable delayCloseScreen = new Runnable() {
        @Override
        public void run() {
            closeScreen();
        }
    };

    private void closeScreen() {
//        this.finish();                  //end this activity
//        connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);utes
        startACoreActivity(this, mainapp.getCvProgrammerIntent(), false, 0);
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainapp = (threaded_application) getApplication();
        if (mainapp.isForcingFinish()) {     // expedite
            return;
        }

        mainapp.applyTheme(this);

        setContentView(R.layout.reconnect_page);

        //put pointer to this activity's handler in main app's shared variable (If needed)
        mainapp.reconnect_status_msg_handler = new reconnect_status_handler(Looper.getMainLooper());

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String msg = extras.getString("status");
            if (msg != null) {
                refresh_reconnect_status(msg);
            }
        }
        retryFirst = false;

        if (mainapp.prefFeedbackOnDisconnect) {
            MediaPlayer mediaPlayer = MediaPlayer.create(getApplicationContext(), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            try {
                mediaPlayer.start();
            } catch (Exception e) {
                Log.d("EX_Toolbox", "Reconnect: Unable to play notification sound");
            }

            mainapp.vibrate(new long[]{1000, 500, 1000, 500, 1000, 500});
        }

        screenNameLine = findViewById(R.id.screen_name_line);
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        statusLine = findViewById(R.id.status_line);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
            mainapp.setToolbarTitle(toolbar, statusLine, screenNameLine,
                    getApplicationContext().getResources().getString(R.string.app_name),
                    getApplicationContext().getResources().getString(R.string.app_name_reconnect_status),
                    "");
        }

    } //end onCreate

    @Override
    public void onResume() {
        super.onResume();
        if (mainapp.isForcingFinish()) { //expedite
            this.finish();
            return;
        }
        mainapp.setActivityOrientation(this);  //set screen orientation based on prefs
        this.backOk = true;
    }

    /**
     * Called when the activity is finished.
     */
    @Override
    public void onDestroy() {
        Log.d("EX_Toolbox", "reconnect_status.onDestroy() called");
        super.onDestroy();
        if (mainapp.reconnect_status_msg_handler !=null) {
            mainapp.reconnect_status_msg_handler.removeCallbacksAndMessages(null);
            mainapp.reconnect_status_msg_handler = null;
        } else {
            Log.d("EX_Toolbox", "onDestroy: mainapp.reconnect_status_msg_handler is null. Unable to removeCallbacksAndMessages");
        }
    }

    //Handle pressing of the back button to end this activity
    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BACK && this.backOk) {
            mainapp.checkExit(this, true);
            return true;
        }
        mainapp.exitDoubleBackButtonInitiated = 0;
        return (super.onKeyDown(key, event));
    }

    private void disconnect() {
        this.finish();
    }


    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    // common startActivity()
    // used for swipes for the main activities only
    protected void startACoreActivity(Activity activity, Intent in, boolean swipe, float deltaX) {
        if (activity != null && in != null) {
            in.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(in);
            overridePendingTransition(mainapp.getFadeIn(swipe, deltaX), mainapp.getFadeOut(swipe, deltaX));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.reconnect_status_menu, menu);

        mainapp.reformatMenu(menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        if (item.getItemId() == R.id.exit_mnu) {
            mainapp.checkAskExit(this, true);
            return true;
        } else if (item.getItemId() == R.id.logviewer_menu) {
            Intent logviewer = new Intent().setClass(this, LogViewerActivity.class);
            startActivity(logviewer);
            connection_activity.overridePendingTransition(this, R.anim.fade_in, R.anim.fade_out);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

}
