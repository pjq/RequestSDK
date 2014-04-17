package com.englishtown.zenon;

import com.zenon.sdk.core.CallManager;
import com.zenon.sdk.core.EventDispatcher;
import com.zenon.sdk.core.EventManager;
import com.zenon.sdk.core.Logger;
import com.zenon.sdk.core.Zebra;
import com.zenon.sdk.core.ZenonPhoneManager;
import com.zenon.sdk.core.ConnectionManager;
import com.zenon.sdk.configuration.ConfigManager;
import com.zenon.sdk.configuration.UserConstants;
import com.zenon.sdk.configuration.InvalidSettingsException;
import com.zenon.sdk.configuration.SettingItemNames;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText mCallerText;
    private EditText mCallerPassText;
    private EditText mPartnerText;
    private EditText mServicenameText;
    private EditText mCLSText;
    private Button mLoginButton;
    private EditText mCalleeText;
    private Button mCallVideoButton;
    private Button mCallVoiceButton;
    private static int screenWidth = -1;
    private static int screenHeight = -1;
    private ProgressDialog dialog = null;
    CallManager mCM = null;
    ConfigManager settings = null;
    ZenonPhoneManager mPM = ZenonPhoneManager.getInstance();
    public static PendingIntent pendingIntent = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.modifyLogging(true);
        Logger.debug("MainActivity: onCreate");
        if (CallUIManager.isVideoScreenActive()) {
            Logger.debug("MainActivity: CallUIManager is active so launching it");
            startActivity(new Intent(this, CallUIManager.class));
            //finish();
            return;
        } else {
            Logger.debug("MainActivity: CallUIManager is not active so not launching it");
        }
        setContentView(R.layout.main);
        ConnectionManager.setActivityContext(this);
        ConnectionManager.setGlobalContext(this);
        ConnectionManager.setCurrentContext(this);
        ConfigManager.initSettings(this);
        settings = ConfigManager.getInstance();

        if (ConfigManager.isFirstInstall(this)) {
            try {
                settings.createDefaultSettings();
            } catch (InvalidSettingsException e) {
                e.printStackTrace();
            }
        }

        mCallerText = (EditText) findViewById(R.id.main_caller_text);
        mCallerPassText = (EditText) findViewById(R.id.main_caller_pass_text);
        mPartnerText = (EditText) findViewById(R.id.main_partner_text);
        mServicenameText = (EditText) findViewById(R.id.main_servicename_text);
        mCLSText = (EditText) findViewById(R.id.cls_text);
        mLoginButton = (Button) findViewById(R.id.main_login_button);
        mCalleeText = (EditText) findViewById(R.id.main_callee_text);
        mCallVideoButton = (Button) findViewById(R.id.main_call_video_button);
        mCallVoiceButton = (Button) findViewById(R.id.main_call_voice_button);

        mCallerText.setText(getString(R.string.test_name));
        mCallerPassText.setText(getString(R.string.test_password));
        mCalleeText.setText(getString(R.string.test_connect_name));
        mPM.start(this);
        settings.set(SettingItemNames.settings_cls.toString(), this.getString(R.string.default_cls));
        settings.set(SettingItemNames.settings_servicename.toString(), this.getString(R.string.servicename));
        Display display = getWindowManager().getDefaultDisplay();
        screenWidth = display.getWidth();
        screenHeight = display.getHeight();
        EventManager.addListener(this, this.mLoginResultReceiver, EventDispatcher.LOGIN_RESULT);
        EventManager.addListener(this, this.mACTION_INCOMING_ResultReceiver, EventDispatcher.ACTION_INCOMING_RESULT);
        EventManager.addListener(this, this.mLogoutResultReceiver, EventDispatcher.LOGOUT_RESULT);
        mCM = CallManager.getInstance();
        Intent intent = new Intent(this, CallUIManager.class);
        intent.setAction(CallUIManager.ACTION_INCOMING);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, Intent.FILL_IN_DATA);
    }

    private void updateSettings() {
        settings.set(SettingItemNames.settings_cls.toString(), mCLSText.getText().toString());
        settings.set(SettingItemNames.settings_servicename.toString(), mServicenameText.getText().toString());
        settings.set(SettingItemNames.settings_textpartner.toString(), mPartnerText.getText().toString());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.debug("MainActivity: onDestroy()");
        mPM.stop();
        EventManager.removeListener(this, this.mLoginResultReceiver);
        EventManager.removeListener(this, this.mACTION_INCOMING_ResultReceiver);
        EventManager.removeListener(this, this.mLogoutResultReceiver);
    }

    @Override
    public void onPause() {
        super.onPause();
        Logger.debug("MainActivity: onPause()");
    }


    @Override
    public void onResume() {
        super.onResume();
        Logger.debug("MainActivity: onResume()");
        if (CallUIManager.isVideoScreenActive()) {
            Logger.debug("MainActivity: CallUIManager is active so launching it");
            startActivity(new Intent(this, CallUIManager.class));
            //finish();
            return;
        } else {
            Logger.debug("MainActivity: CallUIManager is not active so not launching it");
        }
    }


    public void onLoginClick(View v) {
        updateSettings();

        dialog = new ProgressDialog(this);
        if (mLoginButton.getText().toString().contains("Login")) {
            dialog = ProgressDialog.show(this, "Please Wait", "Logging In...");
        } else {
            dialog = ProgressDialog.show(this, "Please Wait", "Logging Out...");
        }

        new Thread(new Runnable() {
            public void run() {
                if (mLoginButton.getText().toString().contains("Login")) {
                    ConnectionManager.loginToServer(ConfigManager.getInstance().getCommonParam(SettingItemNames.settings_cls).toString(),
                            mCallerText.getText().toString(), mCallerPassText.getText().toString(), MainActivity.this, false);
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mCM.logout();

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    ConnectionManager.logout();
                                }
                            }).start();
                        }
                    });

                }
            }
        }).start();
    }

    public void onCallClick(View v) {
        String callLineNumber = "1";//System.currentTimeMillis()+"";
        if (mCalleeText.getText().toString().equals("")) {
            mCallerText.setText("VS1");
        }
        mCM.placeCall(((v == mCallVideoButton) ? UserConstants.VIDEO_CALL : UserConstants.VOICE_CALL),
                (mCalleeText.getText().toString() + "#" + callLineNumber), "", "",
                true, pendingIntent);
        Intent callIntent = new Intent(this, CallUIManager.class);
        callIntent.putExtra("calledLineNumber", callLineNumber);
        this.startActivity(callIntent);
    }

    /*Reciever responsible to listen and recieve the login result
     * and start a new activity
	 */
    private BroadcastReceiver mLoginResultReceiver = new BroadcastReceiver() {

        @Override

        public void onReceive(Context arg0, Intent intent) {

            Logger.info("--------Intent in login screen recieved---------");
            Logger.info("intent.getAction() 1: " + intent.getAction());
            if (intent.getAction().equals(EventDispatcher.LOGIN_RESULT)) {
                Bundle b = intent.getExtras();
                if (b.getStringArray(EventDispatcher.LOGIN_RESULT) == null) {
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    Logger.info("intent.getAction() 2: " + intent.getAction());
                    Toast.makeText(arg0, "Network Error\n" + intent.getAction(), Toast.LENGTH_SHORT).show();
                } else {
                    final String[] loginUrlResponse = b.getStringArray(EventDispatcher.LOGIN_RESULT);
                    if (loginUrlResponse == null) {
                        if (dialog != null && dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(arg0, "Network Error", Toast.LENGTH_SHORT).show();
                    } else if (!loginUrlResponse[0].contains("error_code=\"0\"")) {
                        if (dialog != null && dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(arg0, "Login Failed", Toast.LENGTH_SHORT).show();
                    } else if (loginUrlResponse[0].contains("error_code=\"0\"")) {
                        Logger.info("---------mLoginResultReceiver unregistered");
                        if (dialog != null && dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(MainActivity.this, "Successful Login!", Toast.LENGTH_SHORT).show();
                        mLoginButton.setText("Logout");
                        mCalleeText.setVisibility(View.VISIBLE);
                        mCallVideoButton.setVisibility(View.VISIBLE);
                        mCallVoiceButton.setVisibility(View.VISIBLE);
                        mCallerText.setEnabled(false);
                        mCallerPassText.setEnabled(false);
                        ((TextView) findViewById(R.id.main_callee_text_label)).setVisibility(View.VISIBLE);
                        mCalleeText.setText(getString(R.string.test_connect_name));

                    }
                }
            } else {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                Logger.info("intent.getAction() 3: " + intent.getAction());
                Toast.makeText(arg0, "Network Error\n" + intent.getAction(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    /*Reciever responsible to listen and recieve the logout result
     * and start a new activity
	 */
    private BroadcastReceiver mLogoutResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent intent) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            Toast.makeText(MainActivity.this, "Successful Logout!", Toast.LENGTH_SHORT).show();
            mLoginButton.setText("Login");
            mCalleeText.setVisibility(View.GONE);
            mCallVideoButton.setVisibility(View.GONE);
            mCallVoiceButton.setVisibility(View.GONE);
            mCallerText.setEnabled(true);
            mCallerPassText.setEnabled(true);
            ((TextView) findViewById(R.id.main_callee_text_label)).setVisibility(View.GONE);
        }
    };

    /*Reciever responsible to listen and recieve the Incoming Call Event
      * and start a new activity
      */
    private BroadcastReceiver mACTION_INCOMING_ResultReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context arg0, Intent intent) {
            Logger.info("Incoming Call...");
            Zebra mZebraIncoming = (Zebra) intent.getExtras().getSerializable(EventDispatcher.ACTION_INCOMING_RESULT);
            final String mSessionId = mZebraIncoming.getZEventKeyValue("chat_session_id");
            final String mContactAddress = mZebraIncoming.getZEventKeyValue("contactaddress");
            final String mContactType = mZebraIncoming.getZEventKeyValue("contacttype");
            final String mContactId = mZebraIncoming.getZEventKeyValue("contactid");
            final String mContactName = mZebraIncoming.getZEventKeyValue("contactname");
            final String mAvatar_id = mZebraIncoming.getZEventKeyValue("avatar_id");
            final String call_type = mZebraIncoming.getZEventKeyValue("type");

            Intent intent1 = new Intent(MainActivity.this, CallUIManager.class);
            intent1.setAction(CallUIManager.ACTION_INCOMING_SHOWGUI);
            Logger.info("mContactAddress: " + mContactAddress);
            Logger.info("mContactName: " + mContactName);
            intent1.putExtra("callerAddress", mContactAddress);
            intent1.putExtra("callerName", mContactName);
            if (call_type.equalsIgnoreCase("video")) {
                intent1.putExtra("calltype", UserConstants.VIDEO_CALL);
            } else {
                intent1.putExtra("calltype", UserConstants.VOICE_CALL);
            }
            MainActivity.this.startActivity(intent1);
        }
    };

    public static int getScreenWidth() {
        return screenWidth;
    }

    public static int getScreenHeight() {
        return screenHeight;
    }

}
