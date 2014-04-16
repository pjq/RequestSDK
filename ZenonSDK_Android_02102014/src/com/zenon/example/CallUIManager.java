package com.zenon.example;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.zenon.sdk.configuration.UserConstants;
import com.zenon.sdk.core.CallManager;
import com.zenon.sdk.core.ConnectionManager;
import com.zenon.sdk.core.EventDispatcher;
import com.zenon.sdk.core.EventManager;
import com.zenon.sdk.core.Logger;
import com.zenon.sdk.core.Zebra;
import com.zenon.sdk.core.ZenonDeviceUtilities;
import com.zenon.sdk.core.ZenonPhoneCall;
import com.zenon.sdk.view.ZenonLocalVideoView;
import com.zenon.sdk.view.ZenonRemoteVideoView;

public class CallUIManager extends Activity {

    public static final String ACTION_INCOMING = "com.zenon.sdk.CallUIManager.INCOMING";
    public static final String ACTION_INCOMING_SHOWGUI = "com.zenon.sdk.CallUIManager.INCOMINGGUI";

    private static final long OVERLAY_ANIMATION_DURATION = 500;

    private static final String WAKELOCK_KEY = "CALL_WAKE_LOCK";
    private static PowerManager.WakeLock mWakeLock;
    private static boolean isCallScreenActive = false;
    private boolean isHeadSetPluggedIn = false;
    private HeadphoneReceiver mHeadphoneReceiver;

    private enum CallTerminatedReason {
        UNDEFINED,
        LOCAL_REJECTED,
        LOCAL_HANGUP,
        REMOTE_HANGUP
    }
    
    private enum CallFailedReason{
    	UNDEFINED,
    	LOCAL_CLEARED,
    	LOCAL_NOT_ACCEPTED,
    	LOCAL_DECLINED,
    	REMOTE_CLEARED,
    	REMOTE_REFUSED,
    	REMOTE_NOT_ACCEPTED,
    	REMOTE_STOPPED_CALLING,
    	TRANSPORT_ERROR_CLEARED,
    	TRANSPORT_FAILED_TO_ESTABLISH,
    	GATEKEEPER_CLEARED,
    	NO_USER_AVAILABLE,
    	BANDWIDTH_LOW,
    	NO_COMMON_CAPABILITIES,
    	CALL_FORWARDED_USING_FACILITY,
    	SECURITY_CHECK_FAILED,
    	LOCAL_BUSY,
    	LOCAL_CONGESTED,
    	REMOTE_BUSY,
    	REMOTE_CONGESTED,
    	REMOTE_UNREACHABLE,
    	NO_REMOTE_ENDPOINT,
    	REMOTE_OFFLINE,
    	TEMPORARY_REMOTE_FAILURE,
    	UNMAPPED_Q931_CAUSE,
    	ENFORCED_DURATION_LIMIT,
    	INVALID_CONFERENCE_ID,
    	MISSING_DIALTONE,
    	MISSING_RINGBACKTONE,
    	LINE_OUT_OF_SERVICE,
    	ANOTHER_CALL_ANSWERED,
    	GATEKEEPER_ADMISSION_FAILED    	
    }

    private enum CallState {
        NO_CALL,
        CONNECTING,
        RINGING,
        RINGING_REMOTE,
        IN_PROGRESS,
        TERMINATED,
        FAILED,
    }

    private enum PresentationState {
        HIDDEN,
        HIDING,
        PRESENTED,
        PRESENTING,
    }

    private ZenonPhoneCall mCurrentCall;
    private ZenonPhoneCall.Type mCurrentCallType;
    private CallState mCallState;
    private Date mCallStartTime;
    private boolean mCallWasAnswered;
    private CallTerminatedReason mAppTermReason;
    private CallFailedReason mAppFailReason;
    private boolean mOverlayIsOn;
    private PresentationState mOverlayPresentationState;
    private boolean mTransitionalOverlayOn;

    private ZenonLocalVideoView mCameraPreview;
    private ZenonRemoteVideoView mRemoteVideoView;

    private View mTouchLockOverlay;

    private LinearLayout mTopOverlay;
    private TextView mNameText;
    private TextView mStatusText;

    private LinearLayout mMiddleOverlay;
    private ToggleButton mMuteButton;
    private ToggleButton mSpeakerButton;

    private LinearLayout mBottomOverlay;
    private Button mLeftBottomButton;
    private Button mRightBottomButton;
    private Button mSingleBottomButton;

    private Runnable mStatusUpdateTask;
    private final Handler mStatusUpdateHandler = new Handler();
    private final Handler mDelayedHandler = new Handler();

    private Ringtone mRingtone;
    private ToneGenerator mToneGenerator;
    private boolean mInboundRinging;
    private boolean mOutboundRinging;
    private boolean mMuted;
    private boolean mSpeakerPhone;
    private int mAudioStream = (ZenonDeviceUtilities.isGalaxyS() || ZenonDeviceUtilities.isGalaxyTab()) ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_VOICE_CALL;

    private MyOrientationEventListener mOrientationEventListener;
    private ZenonPhoneCall.CameraOrientation mCurrentOri = ZenonPhoneCall.CameraOrientation.UNKNOWN;

    private SensorEventListener mProximitySensorListener;
    private boolean mProximityMonitoringOn = false;
    private String lineNumber = "1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        setContentView(R.layout.call);
        isCallScreenActive = true;
        ConnectionManager.setActivityContext(this);
 	    ConnectionManager.setGlobalContext(this);
 	    ConnectionManager.setCurrentContext(this);
        mCameraPreview = (ZenonLocalVideoView)findViewById(R.id.call_camerapreview);
        mRemoteVideoView = (ZenonRemoteVideoView)findViewById(R.id.call_remotevideoview);
        mCameraPreview.setZOrderMediaOverlay(true);
        mRemoteVideoView.setZOrderMediaOverlay(false);

        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();
        int previewWidth = width / 3;
        int previewHeight = (previewWidth * 4) / 3;
        float scale = CallUIManager.this.getResources().getDisplayMetrics().density;
        int margin = (int)(10 * scale + 0.5f);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(previewWidth, previewHeight, Gravity.BOTTOM | Gravity.LEFT);
        layoutParams.setMargins(margin, margin, margin, margin);
        mCameraPreview.setLayoutParams(layoutParams);
        
        mCameraPreview.setListener(new ZenonLocalVideoView.zenonCameraPreviewListener() {
            @Override
            public void onPreviewSizeChanged(int width, int height, int padding) {
                Display display = getWindowManager().getDefaultDisplay();
                int displayWidth = display.getWidth();
                int displayHeight = display.getHeight();

                int optimalPreviewWidth = displayWidth / 3;
                int lowerPreviewWidth = displayWidth / 4;
                int upperPreviewWidth = displayWidth * 5 / 12;
                int optimalPreviewHeight = displayHeight / 3;
                int lowerPreviewHeight = displayHeight / 4;
                int upperPreviewHeight = displayHeight * 5 / 12;

                int previewWidth = 0;
                int previewHeight = 0;

                if (width > lowerPreviewWidth && height > lowerPreviewHeight) {
                    int divisor = 1;
                    int bestDiffFromOptimal = Integer.MAX_VALUE;
                    while (true) {
                        if (width / divisor < lowerPreviewWidth && height / divisor < lowerPreviewHeight) {
                            break;
                        } else if ((width % divisor == 0) && (height % divisor == 0)) {
                            int dPreviewWidth = width / divisor;
                            int dPreviewHeight = height / divisor;

                            if (dPreviewWidth >= lowerPreviewWidth && dPreviewWidth <= upperPreviewWidth) {
                                int thisDiffFromOptimal = Math.abs((int)dPreviewWidth - optimalPreviewWidth) + 
                                                          Math.abs((int)dPreviewHeight - optimalPreviewHeight);
                                if (thisDiffFromOptimal < bestDiffFromOptimal) {
                                    previewWidth = (int)dPreviewWidth;
                                    previewHeight = (int)dPreviewHeight;
                                    bestDiffFromOptimal = thisDiffFromOptimal;
                                }
                            }
                        }

                        divisor++;
                    }
                } else {
                    int multiplier = 1;
                    int bestDiffFromOptimal = Integer.MAX_VALUE;
                    while (true) {
                        int dPreviewWidth = width * multiplier;
                        int dPreviewHeight = height * multiplier;

                        if (dPreviewWidth > upperPreviewWidth || dPreviewHeight > upperPreviewHeight) {
                            break;
                        } else {
                            int thisDiffFromOptimal = Math.abs((int)dPreviewWidth - optimalPreviewWidth) + 
                                                      Math.abs((int)dPreviewHeight - optimalPreviewHeight);
                            if (thisDiffFromOptimal < bestDiffFromOptimal) {
                                previewWidth = (int)dPreviewWidth;
                                previewHeight = (int)dPreviewHeight;
                                bestDiffFromOptimal = thisDiffFromOptimal;
                            }
                        }

                        multiplier++;
                    }
                }

                if (previewWidth == 0) {
                    if ((width >= lowerPreviewWidth && width <= upperPreviewWidth) && 
                        (height >= lowerPreviewHeight && height <= upperPreviewHeight))
                    {
                        previewWidth = width;
                        previewHeight = height;
                    } else {
                        previewWidth = optimalPreviewWidth;
                        previewHeight = (previewWidth * height) / width;
                    }
                }

                float scale = CallUIManager.this.getResources().getDisplayMetrics().density;
                int margin = (int)(10 * scale + 0.5f);
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(previewWidth+(padding*2), previewHeight+(padding*2), Gravity.BOTTOM | Gravity.LEFT);
                layoutParams.setMargins(margin, margin, margin, margin);
                mCameraPreview.setLayoutParams(layoutParams);
            }
        });

        mTouchLockOverlay = (View)findViewById(R.id.call_touchlockoverlay);
        mTouchLockOverlay.setOnTouchListener(new MyTouchLockTouchListener());

        mOverlayIsOn = true;
        mOverlayPresentationState = PresentationState.PRESENTED;
        mTransitionalOverlayOn = false;

        mRemoteVideoView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    CallUIManager.this.toggleOverlay();
                }
                return true;
            }
        });
        
        mCameraPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    CallUIManager.this.mCameraPreview.toggleCamera();
                }
                return true;
            }
        });

        mOrientationEventListener = new MyOrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL);

        mProximitySensorListener = new MyProximityEventListener();

        mTopOverlay = (LinearLayout)findViewById(R.id.call_top_overlay);
        mNameText = (TextView)findViewById(R.id.call_name_text);
        mStatusText = (TextView)findViewById(R.id.call_status_text);

        mMiddleOverlay = (LinearLayout)findViewById(R.id.call_middle_overlay);
        mMuteButton = (ToggleButton)findViewById(R.id.call_mute_btn);
        mSpeakerButton = (ToggleButton)findViewById(R.id.call_speaker_btn);

        mBottomOverlay = (LinearLayout)findViewById(R.id.call_bottom_overlay);
        mLeftBottomButton = (Button)findViewById(R.id.call_bottom_btn_left);
        mRightBottomButton = (Button)findViewById(R.id.call_bottom_btn_right);
        mSingleBottomButton = (Button)findViewById(R.id.call_bottom_btn_single);

        // Ignore presses when held to the face 
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        setVolumeControlStream(mAudioStream);
        mRingtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        mToneGenerator = new ToneGenerator(mAudioStream, ToneGenerator.MAX_VOLUME);
        mInboundRinging = false;
        mOutboundRinging = false;
        EventManager.addListener(this, this.mCALL_ESTABLISHED_EVENT_RECEIVER, EventDispatcher.CALL_ESTABLISHED_RESULT);
        EventManager.addListener(this, this.mACTION_OUTGOING_EVENT_RECEIVER, EventDispatcher.ACTION_OUTGOING_RESULT);
        EventManager.addListener(this, this.mCALL_DISCONNECT_EVENT_RECEIVER, EventDispatcher.CALL_DISCONNECT_RESULT);
        EventManager.addListener(this, this.mMLS_HANDLER, EventDispatcher.MLS_RESULT);
        EventManager.addListener(this, this.mREMOTE_CAMERA_ORIENTATION_CHANGED_EVENT_RECEIVER, EventDispatcher.REMOTE_CAMERA_ORIENTATION_CHANGED_RESULT);
        
        mHeadphoneReceiver = new HeadphoneReceiver();
        
        handleIntent(getIntent());
    }

    private BroadcastReceiver mCALL_ESTABLISHED_EVENT_RECEIVER = new BroadcastReceiver(){

	    public void onReceive(Context arg0, Intent intent) {
	    	Logger.info("Entering mCALL_ANSWEREDResultReceiver 1");
	    	if(intent.getAction().equals(EventDispatcher.CALL_ESTABLISHED_RESULT))
	    	{
	    		callEstablished();
	    	}
	    }
    };
    private BroadcastReceiver mACTION_OUTGOING_EVENT_RECEIVER = new BroadcastReceiver(){

	    public void onReceive(Context arg0, Intent intent) {
	    	Logger.info("Entering mACTION_OUTGOING_ResultReceiver 1");
	    	if(intent.getAction().equals(EventDispatcher.ACTION_OUTGOING_RESULT))
	    	{
	    		Logger.info("Entering mACTION_OUTGOING_ResultReceiver 2");
	    		mCurrentCall = CallManager.getCall();
	    		if (mCurrentCall != null) {
					Logger.info("Call Successfully placed");
				    mCurrentCall.setCameraPreview(mCameraPreview);
				    mCurrentCall.setRemoteVideoView(mRemoteVideoView);
				    mCurrentCallType = mCurrentCall.getCallType();
				    mNameText.setText(mCurrentCall.getPeers().get(0).getName());
				    setCallState(CallState.CONNECTING);
				    startCall();
				} else {
					Logger.info("Outgoing call failed");
					mAppFailReason = CallFailedReason.LOCAL_CLEARED;
				    setCallState(CallState.FAILED);
				}
	    	}
	    }
	};
	
	 private BroadcastReceiver mCALL_DISCONNECT_EVENT_RECEIVER = new BroadcastReceiver(){

		    public void onReceive(Context arg0, Intent intent) {
		    	if(intent.getAction().equals(EventDispatcher.CALL_DISCONNECT_RESULT))
		    	{
		    		String[] intent_data = intent.getExtras().getStringArray(EventDispatcher.CALL_DISCONNECT_RESULT);
		    		String error_code = intent_data[0];
		    		Logger.info("Remote Party Ended the Call");
		    		Toast.makeText(CallUIManager.this, error_code, Toast.LENGTH_LONG).show();
		    		if(error_code.equals("Remote Hung Up")){
		    			mAppTermReason = CallTerminatedReason.REMOTE_HANGUP;
		    			setCallState(CallState.TERMINATED);
		    		}else{
		    			//mFai
		    			setCallState(CallState.FAILED);
		    		}
		            //mAppTermReason = error_code;//CallTerminatedReason.REMOTE_HANGUP;
		            
		            mStatusText.setText(error_code);
		            if(mCurrentCall != null){ 
		            	mCurrentCall.end();
		            	stopCall();
		            }
		            Logger.info("Dismissing the Call Activity");		
		            dismiss();
		            
		    	}
		    }
	 };
	 
	 private BroadcastReceiver mMLS_HANDLER = new BroadcastReceiver(){

		    public void onReceive(Context arg0, Intent intent) {
		    	System.out.println("--------Intent in mMLS_ResultReceiver recieved---------");
		    	if(intent.getAction().equals(EventDispatcher.MLS_RESULT))
		    	{
		    	  		System.out.println("MLS received ...");
		    	  		String[] intent_data = intent.getExtras().getStringArray(EventDispatcher.MLS_RESULT);
		    	  		Zebra mlsZebra = new Zebra(); 
		    	  		mlsZebra = mlsZebra.ParseZEBRAMessage(intent_data[3]);
		    	  		
		    	  		System.out.println("calledLineNumber in MLS: "+lineNumber);
		    	  		if(checkMLSValueSet(mlsZebra,"2")){
		    	  			/* Start Ringing */
		    	  			Logger.info("Start Outbound Ringing");
				    		setCallState(CallState.RINGING_REMOTE);
		    	  		}else if(checkMLSValueSet(mlsZebra,"4")){
		    	  			/* Stop Ringing */
		    	  			Logger.info("Stop Outbound Ringing");
				    		setCallState(CallState.IN_PROGRESS);
		    	  		}else if(checkMLSValueSet(mlsZebra,"1") ||
		    	  				checkMLSValueSet(mlsZebra,"3") ||
		    	  				checkMLSValueSet(mlsZebra,"")){
		    	  			/* Do Nothing */
		    	  		}else{
		    	  			checkMLSFailCode(mlsZebra);
		    	  			setCallState(CallState.FAILED); 		
				            if(mCurrentCall != null){ 
				            	mCurrentCall.end();
				            	stopCall();
				            }
				            Logger.info("Dismissing the Call Activity");
				            CallManager.getInstance().endCall();
				            dismiss();
		    	  		}
		    	}
		    }
		 };
	 
	 private BroadcastReceiver mREMOTE_CAMERA_ORIENTATION_CHANGED_EVENT_RECEIVER = new BroadcastReceiver(){

		    public void onReceive(Context arg0, Intent intent) {
		    	if(intent.getAction().equals(EventDispatcher.REMOTE_CAMERA_ORIENTATION_CHANGED_RESULT))
		    	{
		    		Logger.info("Handle Remote Camera Orientation Changed");
		    		final ZenonPhoneCall.CameraOrientation ori = CallManager.getRemoteOrientation();
		    		runOnUiThread(new Runnable(){
		        		public void run(){
				    		System.out.println("onRemoteCameraOrientationChanged: Orientation: "+ori);
				    		switch(ori) {
					            case LANDSCAPE_UP:
					                mRemoteVideoView.setRemoteRotation(0);
					                break;
					            case PORTRAIT_LEFT:
					                mRemoteVideoView.setRemoteRotation(90);
					                break;
					            case LANDSCAPE_DOWN:
					                mRemoteVideoView.setRemoteRotation(180);
					                break;
					            case PORTRAIT_RIGHT:
					                mRemoteVideoView.setRemoteRotation(270);
					                break;
				            }
		        		}
		    		});
		    	}
		    }
	 };
	 
	 
	 private class HeadphoneReceiver extends BroadcastReceiver {
	        private boolean mStoredSpeakerphoneOnState = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            // NB: Some older devices (e.g. HTC Desire running 2.2.2) also have state = 2 to indicate normal stereo headphones, and 1 is headphones with mic.
            // This is confusingly not documented at http://developer.android.com/reference/android/content/Intent.html#ACTION_HEADSET_PLUG
            int state = intent.getIntExtra("state",0); // 0 unplugged, 1 plugged, 2 also plugged

            Logger.debug("onReceive Headphone STATE: "+state);

            if (state == 1 || state == 2) { // headphones are plugged in
                // force the speakerphone off but store the current state first so we can revert neatly when unplugged
                mStoredSpeakerphoneOnState = mSpeakerPhone;
                setSpeakerPhone(false); // CallActivity implementation
                isHeadSetPluggedIn = true;
            }
            else { // headphones are not plugged in
                // revert back to the stored state
                setSpeakerPhone(mStoredSpeakerphoneOnState); // CallActivity implementation
                isHeadSetPluggedIn = false;
            }
        }
    }
	private void checkMLSFailCode(Zebra mlsZebra){
		if(checkMLSValueSet(mlsZebra,"0")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Local Cleared");
  			mAppFailReason = CallFailedReason.LOCAL_CLEARED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-1")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Local Not Accepted");
  			mAppFailReason = CallFailedReason.LOCAL_NOT_ACCEPTED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-2")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Local Declined");
  			mAppFailReason = CallFailedReason.LOCAL_DECLINED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-3")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Cleared");
  			mAppFailReason = CallFailedReason.REMOTE_CLEARED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-4")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Refused");
  			mAppFailReason = CallFailedReason.REMOTE_REFUSED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-5")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Not Accepted");
  			mAppFailReason = CallFailedReason.REMOTE_NOT_ACCEPTED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-6")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Stopped Calling");
  			mAppFailReason = CallFailedReason.REMOTE_STOPPED_CALLING;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-7")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Transport Error Cleared Call");
  			mAppFailReason = CallFailedReason.TRANSPORT_ERROR_CLEARED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-8")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Transport Connection Failed");
  			mAppFailReason = CallFailedReason.TRANSPORT_FAILED_TO_ESTABLISH;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-9")){
  			/* Stop Ringing and show Failed */
  			Logger.info("GateKeeper Cleared Call");
  			mAppFailReason = CallFailedReason.GATEKEEPER_CLEARED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-10")){
  			/* Stop Ringing and show Failed */
  			Logger.info("No such User");
  			mAppFailReason = CallFailedReason.NO_USER_AVAILABLE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-11")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Insufficient Bandwidth");
  			mAppFailReason = CallFailedReason.BANDWIDTH_LOW;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-12")){
  			/* Stop Ringing and show Failed */
  			Logger.info("No Common Capabilities");
  			mAppFailReason = CallFailedReason.NO_COMMON_CAPABILITIES;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-13")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Call Forwarded using Facility");
  			mAppFailReason = CallFailedReason.CALL_FORWARDED_USING_FACILITY;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-14")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Security Check Failed");
  			mAppFailReason = CallFailedReason.SECURITY_CHECK_FAILED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-15")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Local Busy");
  			mAppFailReason = CallFailedReason.LOCAL_BUSY;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-16")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Local Congested");
  			mAppFailReason = CallFailedReason.LOCAL_CONGESTED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-17")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Busy");
  			mAppFailReason = CallFailedReason.REMOTE_BUSY;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-18")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Congested");
  			mAppFailReason = CallFailedReason.REMOTE_CONGESTED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-19")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Unreachable");
  			mAppFailReason = CallFailedReason.REMOTE_UNREACHABLE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-20")){
  			/* Stop Ringing and show Failed */
  			Logger.info("No Such Remote EndPoint");
  			mAppFailReason = CallFailedReason.NO_REMOTE_ENDPOINT;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-21")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Remote Party Offline");
  			mAppFailReason = CallFailedReason.REMOTE_OFFLINE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-22")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Temporary Remote Party Failure");
  			mAppFailReason = CallFailedReason.TEMPORARY_REMOTE_FAILURE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-23")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Unmapped Q931 Cause Code");
  			mAppFailReason = CallFailedReason.UNMAPPED_Q931_CAUSE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-24")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Enforced Duration Limit");
  			mAppFailReason = CallFailedReason.ENFORCED_DURATION_LIMIT;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-25")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Invalid Conference ID");
  			mAppFailReason = CallFailedReason.INVALID_CONFERENCE_ID;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-26")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Missing DialTone");
  			mAppFailReason = CallFailedReason.MISSING_DIALTONE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-27")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Missing RingBack Tone");
  			mAppFailReason = CallFailedReason.MISSING_RINGBACKTONE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-28")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Line Out of Service");
  			mAppFailReason = CallFailedReason.LINE_OUT_OF_SERVICE;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-29")){
  			/* Stop Ringing and show Failed */
  			Logger.info("Another Call Answered");
  			mAppFailReason = CallFailedReason.ANOTHER_CALL_ANSWERED;				    		
  		}else if(checkMLSValueSet(mlsZebra,"-30")){
  			/* Stop Ringing and show Failed */
  			Logger.info("GateKeeper Admission Failed");
  			mAppFailReason = CallFailedReason.GATEKEEPER_ADMISSION_FAILED;				    		
  		}else{
  			Logger.info("Random Case");
  			mAppFailReason = CallFailedReason.LOCAL_CLEARED;
  		}
	}
	private boolean checkMLSValueSet(Zebra mlsZebra, String codeValue){
		if(mlsZebra.getZEventKeyValue("info_1").equalsIgnoreCase(codeValue) ||
  				mlsZebra.getZEventKeyValue("info_2").equalsIgnoreCase(codeValue) ||
  				mlsZebra.getZEventKeyValue("info_3").equalsIgnoreCase(codeValue) ||
  				mlsZebra.getZEventKeyValue("info_4").equalsIgnoreCase(codeValue) ||
  				mlsZebra.getZEventKeyValue("info_5").equalsIgnoreCase(codeValue) ||
  				mlsZebra.getZEventKeyValue("info_6").equalsIgnoreCase(codeValue) ||
  				(mlsZebra.getZEventKeyValue("info_"+lineNumber) != null &&
    	  				mlsZebra.getZEventKeyValue("info_"+lineNumber).equalsIgnoreCase(codeValue))){
  			/* Start Ringing */
  			return true;
  		}
		return false;
	}
    @Override
    protected void onResume() {
        super.onResume();
        Logger.debug("CallUIManager: onResume()");

        if (mCameraPreview != null) {
            mCameraPreview.onResume();
        }

        if (mRemoteVideoView != null) {
            mRemoteVideoView.onResume();
        }
        
        registerReceiver(mHeadphoneReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.debug("CallUIManager: onPause()");
        if (mCameraPreview != null) {
            mCameraPreview.onPause();
        }

        if (mRemoteVideoView != null) {
            mRemoteVideoView.onPause();
        }
        
        try{
        	unregisterReceiver(mHeadphoneReceiver);
        }catch(IllegalArgumentException e){
        	e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventManager.removeListener(this, this.mACTION_OUTGOING_EVENT_RECEIVER);
        EventManager.removeListener(this, this.mCALL_DISCONNECT_EVENT_RECEIVER);
        EventManager.removeListener(this, this.mCALL_ESTABLISHED_EVENT_RECEIVER);
        EventManager.removeListener(this, this.mMLS_HANDLER);
        EventManager.removeListener(this, this.mREMOTE_CAMERA_ORIENTATION_CHANGED_EVENT_RECEIVER);
        isCallScreenActive = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }

    public static boolean isVideoScreenActive(){
    	return isCallScreenActive;
    }
    /**
     * Manually handle configuration changes.
     * Inspiration taken from Android's Phone app by setting:
     *   android:configChanges="orientation|keyboardHidden"
     * such that we don't get destroyed and recreated on those config changes
     */
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleOverlay();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return false;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if(action == null) return;
        if (action.equals(ACTION_INCOMING)) {
        	mCurrentCall = CallManager.getInstance().takeCall(intent);
            if (mCurrentCall != null) {
            	Logger.info("ACTION_INCOMING received, call success");
                mCurrentCall.setCameraPreview(mCameraPreview);
                mCurrentCall.setRemoteVideoView(mRemoteVideoView);
                mCurrentCallType = mCurrentCall.getCallType();
                setCallState(CallState.IN_PROGRESS);
                startCall();
                mCurrentCall.accept();
                callEstablished();
            } else {
            	Logger.info("ACTION_INCOMING received");
            	mAppFailReason = CallFailedReason.LOCAL_CLEARED;
                setCallState(CallState.FAILED);
            }
        }else if(action.equals(ACTION_INCOMING_SHOWGUI)){
        	Logger.info("ACTION_INCOMING_SHOWGUI received, Setting Ringing");
        	Bundle b = intent.getExtras();
        	if(b != null){
	        	String mCallerAddress = b.getString("callerAddress");
	        	String mCallerName = b.getString("callerName");
	        	int mCallType = b.getInt("calltype");
	        	Logger.info("mCallerAddress: "+mCallerAddress);
			    Logger.info("mCallerName: "+mCallerName);
	        	if(mCallerName != null){
	        		mNameText.setText(mCallerName);
	        	}else if(mCallerAddress != null){
	        		mNameText.setText(mCallerAddress);
	        	}else{
	        		mNameText.setText("");
	        	}
	        	if(mCallType == UserConstants.VIDEO_CALL) mCurrentCallType = ZenonPhoneCall.Type.VIDEO;
	        	else mCurrentCallType = ZenonPhoneCall.Type.VOICE;
        	}else{
        		mNameText.setText("");
        		mCurrentCallType = ZenonPhoneCall.Type.VIDEO;
        	}        	
        	setCallState(CallState.RINGING);
        }else {
        
        	Logger.info("Outgoing Call Intent received");
        	Bundle b = intent.getExtras();
        	if(b!=null){
        		lineNumber = b.getString("calledLineNumber");
        	}
        }
    }

    private void setCallState(CallState callState) {
        mCallState = callState;

        if (callState == CallState.RINGING) {
            setInboundRinging(true);
        } else if (isInboundRinging()) {
            setInboundRinging(false);
        }
        if (callState == CallState.RINGING_REMOTE) {
            setOutboundRinging(true);
        } else if (isOutboundRinging()){
            setOutboundRinging(false);
        }

        updateStatusLabel();
        updateBottomBar();
        setupProximityMonitoring();
    }

    private void updateStatusLabel() {
        this.runOnUiThread(new Runnable() {
            public void run() {
                //mNameText.setText("To be Done");
                if(mCallState == null) return;
                switch (mCallState) {
                    case NO_CALL:
                        mStatusText.setText("Not In Call");
                        break;
                    case CONNECTING:
                        mStatusText.setText("Connecting...");
                        break;
                    case RINGING:
                    case RINGING_REMOTE:
                        mStatusText.setText("Ringing...");
                        break;
                    case IN_PROGRESS:
                        break;
                    case TERMINATED:
                        mStatusText.setText(termReasonString());
                        break;
                    case FAILED:
                        //mStatusText.setText("Call Failed");
                    	mStatusText.setText("Call Failed\n"+failReasonString());
                        break;
                }
            }
        });
    }

    private void updateBottomBar() {
        this.runOnUiThread(new Runnable() {
            public void run() {
                boolean doubleOn = true;
                if (mCallState == CallState.RINGING) {
                    mLeftBottomButton.setText("Reject");
                    if (mCurrentCallType == ZenonPhoneCall.Type.VOICE) {
                        mRightBottomButton.setText("Accept Voice");
                    } else {
                        mRightBottomButton.setText("Accept Video");
                    }
                } else if (mCallState == CallState.RINGING_REMOTE || 
                           mCallState == CallState.CONNECTING) {
                    mSingleBottomButton.setText("End Call");
                    doubleOn = false;
                } else if ((mCallState == CallState.TERMINATED && mCallWasAnswered) || 
                           mCallState == CallState.NO_CALL) {
                    mSingleBottomButton.setText("Return");
                    doubleOn = false;
                } else if (mCallState == CallState.FAILED || 
                           (mCallState == CallState.TERMINATED && !mCallWasAnswered)) {
                    mLeftBottomButton.setText("Call Back");
                    mRightBottomButton.setText("Return");
                } else {
                    mLeftBottomButton.setText("End Call");
                    if (mCurrentCallType == ZenonPhoneCall.Type.VOICE) {
                        mSingleBottomButton.setText("End Call");
                        doubleOn = false;
                    } else {
                        mRightBottomButton.setText("Return");
                    }
                }
                if (doubleOn) {
                    mSingleBottomButton.setVisibility(View.GONE);
                    mLeftBottomButton.setVisibility(View.VISIBLE);
                    mRightBottomButton.setVisibility(View.VISIBLE);
                } else {
                    mSingleBottomButton.setVisibility(View.VISIBLE);
                    mLeftBottomButton.setVisibility(View.GONE);
                    mRightBottomButton.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setOverlayOn(final boolean overlayOn, final boolean animated) {
        this.runOnUiThread(new Runnable() {
            public void run() {
                boolean newOverlayOn = overlayOn;
                if(mCurrentCallType == ZenonPhoneCall.Type.VOICE) newOverlayOn = true;
                if(mCallState != CallState.IN_PROGRESS) newOverlayOn = true;

                AnimationSet topAnimation = null;
                AnimationSet middleAnimation = null;
                AnimationSet bottomAnimation = null;
                if (newOverlayOn && 
                    (mOverlayPresentationState == PresentationState.HIDDEN || 
                     mOverlayPresentationState == PresentationState.HIDING))
                {
                    mOverlayPresentationState = PresentationState.PRESENTING;

                    Animation animation = null;

                    topAnimation = new AnimationSet(true);
                    animation = new TranslateAnimation(
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                        Animation.RELATIVE_TO_SELF, -1.0f, Animation.RELATIVE_TO_SELF, 0.0f
                    );
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    topAnimation.addAnimation(animation);

                    middleAnimation = new AnimationSet(true);
                    animation = new AlphaAnimation(0.0f, 1.0f);
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    middleAnimation.addAnimation(animation);

                    bottomAnimation = new AnimationSet(true);
                    animation = new TranslateAnimation(
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                        Animation.RELATIVE_TO_SELF, 1.0f, Animation.RELATIVE_TO_SELF, 0.0f
                    );
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    bottomAnimation.addAnimation(animation);

                    topAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {
                            mTopOverlay.setVisibility(View.VISIBLE);
                        }
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {}
                    });
                    middleAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {
                            mMiddleOverlay.setVisibility(View.VISIBLE);
                        }
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {}
                    });
                    bottomAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {
                            mBottomOverlay.setVisibility(View.VISIBLE);
                            mOverlayPresentationState = PresentationState.PRESENTED;
                            if (mTransitionalOverlayOn) {
                                Runnable dismissTask = new Runnable() {
                                    public void run() {
                                        CallUIManager.this.dismiss();
                                    }
                                };
                                mDelayedHandler.postDelayed(dismissTask, 1000);
                                mTransitionalOverlayOn = false;
                            }
                        }
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {}
                    });

                    mTopOverlay.startAnimation(topAnimation);
                    mMiddleOverlay.startAnimation(middleAnimation);
                    mBottomOverlay.startAnimation(bottomAnimation);
                } else if (!newOverlayOn && 
                           (mOverlayPresentationState == PresentationState.PRESENTED || 
                            mOverlayPresentationState == PresentationState.PRESENTING))
                {
                    mOverlayPresentationState = PresentationState.HIDING;

                    Animation animation = null;

                    topAnimation = new AnimationSet(true);
                    animation = new TranslateAnimation(
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, -1.0f
                    );
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    topAnimation.addAnimation(animation);

                    middleAnimation = new AnimationSet(true);
                    animation = new AlphaAnimation(1.0f, 0.0f);
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    middleAnimation.addAnimation(animation);

                    bottomAnimation = new AnimationSet(true);
                    animation = new TranslateAnimation(
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 0.0f,
                        Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF, 1.0f
                    );
                    animation.setDuration(animated ? OVERLAY_ANIMATION_DURATION : 0);
                    bottomAnimation.addAnimation(animation);

                    topAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {}
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {
                            mTopOverlay.setVisibility(View.GONE);
                        }
                    });
                    middleAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {}
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {
                            mMiddleOverlay.setVisibility(View.GONE);
                        }
                    });
                    bottomAnimation.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {}
                        @Override
                        public void onAnimationRepeat(Animation animation) {}
                        @Override
                        public void onAnimationEnd(Animation animation) {
                            mBottomOverlay.setVisibility(View.GONE);
                            mOverlayPresentationState = PresentationState.HIDDEN;
                        }
                    });

                    mTopOverlay.startAnimation(topAnimation);
                    mMiddleOverlay.startAnimation(middleAnimation);
                    mBottomOverlay.startAnimation(bottomAnimation);
                } else if (mTransitionalOverlayOn) {
                    Runnable dismissTask = new Runnable() {
                        public void run() {
                            CallUIManager.this.dismiss();
                        }
                    };
                    mDelayedHandler.postDelayed(dismissTask, 1000);
                    mTransitionalOverlayOn = false;
                }

                mOverlayIsOn = overlayOn;
            }
        });
    }

    private void toggleOverlay() {
        setOverlayOn(!mOverlayIsOn, true);
    }

    private void toggleMute() {
        setMuted(!mMuted);
    }

    private void toggleSpeaker() {
        setSpeakerPhone(!mSpeakerPhone);
    }

    public void onMuteClick(View v) {
        toggleMute();
    }

    public void onSpeakerClick(View v) {
        toggleSpeaker();
    }

    public void onLeftBottomClick(View v) {
        if (mCallState == CallState.RINGING) {
            rejectCall();
        }else {
            hangupCall(CallTerminatedReason.LOCAL_HANGUP);
        }
    }

    public void onRightBottomClick(View v) {
        if (mCallState == CallState.RINGING) {
        	answerCall();
        } else if (mCallState == CallState.FAILED || (mCallState == CallState.TERMINATED && !mCallWasAnswered)) {
            dismiss();
        } else {
            returnButtonPressed();
        }
    }

    public void onSingleBottomClick(View v) {
        if (mCallState == CallState.IN_PROGRESS && mCurrentCallType == ZenonPhoneCall.Type.VOICE) {
            hangupCall(CallTerminatedReason.LOCAL_HANGUP);
        } else if (mCallState == CallState.RINGING_REMOTE || 
                   mCallState == CallState.CONNECTING) {
            hangupCall(CallTerminatedReason.LOCAL_HANGUP);
        } else {
        	CallManager.getInstance().endCall();
            dismiss();
        }
    }

    private void resetSpeakerPhoneState() {
        AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        final boolean speakerPhone = audioManager.isSpeakerphoneOn();
        this.runOnUiThread(new Runnable() {
            public void run() {
                mSpeakerButton.setChecked(speakerPhone);
            }
        });
    }

    private void setInboundRinging(boolean inboundRinging) {
        if (mInboundRinging != inboundRinging) {
            if (inboundRinging == true) {
                mRingtone.play();
                AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                if (audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)) {
                    Vibrator vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
                    // Vibration (ms){ OFF,   ON,  OFF,   ON,  OFF,   ON}
                    long[] pattern = {  0L, 400L, 250L, 600L, 250L, 400L};
                    vibrator.vibrate(pattern, 2);
                }
            } else {
                mRingtone.stop();
                Vibrator vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
                vibrator.cancel();
            }
        }
        mInboundRinging = inboundRinging;
    }

    public boolean isInboundRinging() {
        return mInboundRinging;
    }

    private void setOutboundRinging(boolean outboundRinging) {
        if (mOutboundRinging != outboundRinging) {
            if (outboundRinging == true) {
                mToneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
            } else {
                mToneGenerator.stopTone();
            }
        }
        mOutboundRinging = outboundRinging;
    }

    public boolean isOutboundRinging() {
        return mOutboundRinging;
    }

    private void setProximityMonitoring(boolean on) {
        if (mProximityMonitoringOn != on) {
            mProximityMonitoringOn = on;
            SensorManager sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
            Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (on) {
                sensorManager.registerListener(mProximitySensorListener, proximitySensor, SensorManager.SENSOR_DELAY_UI);
            } else {
                sensorManager.unregisterListener(mProximitySensorListener);
                mTouchLockOverlay.setVisibility(View.GONE);
            }
        }
    }

    private void setMuted(final boolean muted) {
        if (mMuted != muted && mCurrentCall != null) {
        	CallManager.getInstance().setMuteMic(muted);
            this.runOnUiThread(new Runnable() {
                public void run() {
                    mMuteButton.setChecked(muted);
                }
            });
        }
        mMuted = muted;
    }

    public boolean isMuted() {
        return mMuted;
    }

    private void setSpeakerPhone(final boolean speakerPhone) {
        if (mSpeakerPhone != speakerPhone) {
            mCurrentCall.setSpeakerPhone(speakerPhone);
            this.runOnUiThread(new Runnable() {
                public void run() {
                    mSpeakerButton.setChecked(speakerPhone);
                }
            });
        }
        mSpeakerPhone = speakerPhone;
        setupProximityMonitoring();
    }

    public boolean isSpeakerPhone() {
        return mSpeakerPhone;
    }

    private synchronized void startCall() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, WAKELOCK_KEY);
        }

        resetSpeakerPhoneState();

        mCallWasAnswered = false;
        mAppTermReason = CallTerminatedReason.UNDEFINED;
        
        Logger.info("starCall: passed step 1");
        mWakeLock.acquire();

        if (mCurrentCallType == ZenonPhoneCall.Type.VIDEO) {
        	mRemoteVideoView.setVisibility(View.VISIBLE);
        	if(!isHeadSetPluggedIn){
        		setSpeakerPhone(true);
        	}
            mCurrentCall.unmuteOutboundVideo();
        } else {
        	mRemoteVideoView.setVisibility(View.GONE);
        	mCurrentCall.unmuteOutboundAudio();
        	if(!isHeadSetPluggedIn){
        		setSpeakerPhone(false);
        	}
        }
        Logger.info("starCall: passed step 2");
        
        mStatusUpdateTask = new Runnable() {
            public void run() {
                final Date currentTime = new Date();
                if (mCallState == CallState.IN_PROGRESS && mCallStartTime != null) {
                    long diffInMs = currentTime.getTime() - mCallStartTime.getTime();
                    long diffInSec = TimeUnit.MILLISECONDS.toSeconds(diffInMs);
                    long secs = diffInSec % 60;
                    long mins = (diffInSec - secs) / 60;
                    mStatusText.setText(String.format("%d:%02d", mins, secs));
                }
                mStatusUpdateHandler.postDelayed(this, 1000);
            }
        };
        Logger.info("starCall: passed step 3");
        mStatusUpdateHandler.removeCallbacks(mStatusUpdateTask);
        mStatusUpdateHandler.postDelayed(mStatusUpdateTask, 100);

        updateStatusLabel();
        Logger.info("starCall: passed step 4");
        mCurrentCall.setDeviceOrientation(mCurrentOri);
        mOrientationEventListener.enable();
        Logger.info("starCall: passed step 5");
    }

    private synchronized void stopCall() {
        mStatusUpdateHandler.removeCallbacks(mStatusUpdateTask);
    
        mCurrentCall.setCameraPreview((ZenonLocalVideoView)null);
        mCurrentCall.setRemoteVideoView((ZenonRemoteVideoView)null);
    
        mCurrentCall = null;
        mCallStartTime = null;
    
        setOverlayOn(true, true);
        setProximityMonitoring(false);
    
        mCurrentOri = ZenonPhoneCall.CameraOrientation.UNKNOWN;
        mOrientationEventListener.disable();
    
        mCameraPreview.setVisibility(View.GONE);
        mRemoteVideoView.setVisibility(View.GONE);
    
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    
        setIntent(null);
    }

    private void setupProximityMonitoring() {
        boolean enabled = (!mSpeakerPhone) && 
                          (mCurrentCallType == ZenonPhoneCall.Type.VOICE) && 
                          (mCallState == CallState.IN_PROGRESS || 
                           mCallState == CallState.RINGING || 
                           mCallState == CallState.RINGING_REMOTE);
        setProximityMonitoring(enabled);
    }

    private void returnButtonPressed() {
    	if(mCurrentCallType == null) return;
        switch (mCurrentCallType) {
            case VIDEO:
                setOverlayOn(false, true);
                break;
            case VOICE:
            default:
                break;
        }
    }

    private void answerCall() {
        Log.i("CallUIManager", "Entering answerCall");
        setCallState(CallState.CONNECTING);
        lineNumber = "1";//System.currentTimeMillis()+"";
        CallManager.getInstance().acceptIncomingCall(lineNumber, null);//Use a randomly generated Line Number
    }

    private void hangupCall(CallTerminatedReason reason) {
    	Log.i("CallUIManager", "Hanging up....");
        mAppTermReason = reason;
        setCallState(CallState.TERMINATED);
        if (mCurrentCall != null){
	        mCurrentCall.end();
	        stopCall();       
        }
        CallManager.getInstance().endCall();
        dismiss();
    }

    private void rejectCall() {
        mAppTermReason = CallTerminatedReason.LOCAL_REJECTED;
        setCallState(CallState.TERMINATED);
        dismiss();
        CallManager.getInstance().rejectCall();
    }

    private void callEstablished() {
        mCallWasAnswered = true;
        CallUIManager.this.mCallStartTime = new Date();
        setCallState(CallState.IN_PROGRESS);

        if (mCurrentCallType == ZenonPhoneCall.Type.VIDEO) {
        	Logger.info("ZenonCallType: Video");
        	mCameraPreview.setVisibility(View.VISIBLE);
            mRemoteVideoView.setVisibility(View.VISIBLE);
            if(!isHeadSetPluggedIn){
            	setSpeakerPhone(true);
            }
        } else {
        	Logger.info("ZenonCallType: VOICE");
        	mCameraPreview.setVisibility(View.GONE);
            mRemoteVideoView.setVisibility(View.GONE);
            if(!isHeadSetPluggedIn){
            	setSpeakerPhone(false);
            }
        }

        CallUIManager.this.mCameraPreview.toggleCamera();
        CallUIManager.this.mCameraPreview.toggleCamera();
        
        Runnable hideOverlayTask = new Runnable() {
            public void run() {
                CallUIManager.this.setOverlayOn(false, true);
            }
        };
        mDelayedHandler.postDelayed(hideOverlayTask, 3000);
    }

    private void dismiss() {
    	isCallScreenActive = false;
        finish();
        Log.i("CallUIManager", "Finished Activity...");
        mToneGenerator.stopTone();
        mRingtone.stop();
    }

    private String termReasonString() {    
        switch (mAppTermReason) {
            case UNDEFINED:
                return null;
            case LOCAL_HANGUP:
                return "Call Ended";
            case LOCAL_REJECTED:
                return "Call Rejected";
            case REMOTE_HANGUP:
                if (!mCallWasAnswered)
                    return "Missed Call";
                else
                    return "Call Ended";
        }
        return null;
    }
    
    private String failReasonString() { 
    	if(mAppFailReason == null) return "";
        switch (mAppFailReason) {
	        case UNDEFINED:
	            return null;
	        case LOCAL_CLEARED:
	            return "Local Party Ended Call";
	        case LOCAL_NOT_ACCEPTED:
	        	return "Local Party Not Accepted";
	        case LOCAL_DECLINED:
	        	return "Local Party Declined";
	        case REMOTE_CLEARED:
	        	return "Remote Party Cleared";
	        case REMOTE_REFUSED:
	        	return "Remote Party Refused";
	        case REMOTE_NOT_ACCEPTED:
	        	return "Remote Party Not Answered";
	        case REMOTE_STOPPED_CALLING:
	        	return "Remote Party Stopped Calling";
	        case TRANSPORT_ERROR_CLEARED:
	        	return "Transport Error";
	        case TRANSPORT_FAILED_TO_ESTABLISH:
	        	return "Transport Connetion Failed";
	        case GATEKEEPER_CLEARED:
	        	return "GateKeeper Cleared Call";
	        case NO_USER_AVAILABLE:
	        	return "No Such User Available";
	        case BANDWIDTH_LOW:
	        	return "Insufficient Bandwidth";
	        case NO_COMMON_CAPABILITIES:
	        	return "No Common Capabilities";
	        case CALL_FORWARDED_USING_FACILITY:
	        	return "Call Forwarded using FACILITY";
	        case SECURITY_CHECK_FAILED:
	        	return "Security Check Failed";
	        case LOCAL_BUSY:
	        	return "Local Party Busy";
	        case LOCAL_CONGESTED:
	        	return "Local Party Congested";
	        case REMOTE_BUSY:
	        	return "Remote Party Busy";
	        case REMOTE_CONGESTED:
	        	return "Remote Party Congested";
	        case REMOTE_UNREACHABLE:
	        	return "Remote Party Unreachable";
	        case NO_REMOTE_ENDPOINT:
	        	return "No Endpoint Running at Remote Party";
	        case REMOTE_OFFLINE:
	        	return "Remote Party Host Offline";
	        case TEMPORARY_REMOTE_FAILURE:
	        	return "Temporary Remote Failure";
	        case UNMAPPED_Q931_CAUSE:
	        	return "Remote Ended with Unmapped Q931";
	        case ENFORCED_DURATION_LIMIT:
	        	return "Enforced Duration Limit";
	        case INVALID_CONFERENCE_ID:
	        	return "Invalid Conference ID";
	        case MISSING_DIALTONE:
	        	return "Missing DialTone";
	        case MISSING_RINGBACKTONE:
	        	return "Missing RingBackTone";
	        case LINE_OUT_OF_SERVICE:
	        	return "Line Out of Service";
	        case ANOTHER_CALL_ANSWERED:
	        	return "Another Call Answered";
	        case GATEKEEPER_ADMISSION_FAILED: 
	        	return "GateKeeper Admission Failed";
	        default:
	        	return null;
        }
    }

    private class MyOrientationEventListener extends OrientationEventListener {

        public MyOrientationEventListener(Context context, int rate) {
            super(context, rate);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            // Update the display rotation as it may have changed. The camera preview and the remote video view need to
            // know this orientation so they compensate for the orientation of the display relative to the device when
            // displaying video.
            mCameraPreview.setDisplayRotationEnum(getWindowManager().getDefaultDisplay().getOrientation());
            mRemoteVideoView.setDisplayRotationEnum(getWindowManager().getDefaultDisplay().getOrientation());
            
            // Update the device rotation for the camera preview and the remote video view so they can can compensate
            // for how the device is being held.
            mCameraPreview.setDeviceRotationDegrees(orientation);
            mRemoteVideoView.setDeviceRotation(orientation);
            
            if (mCurrentCall == null) return;
            if (orientation == ORIENTATION_UNKNOWN) return;
            
            // The best orientation to view the video we are capturing may have changed.  
            ZenonPhoneCall.CameraOrientation newOri = mCameraPreview.getCameraOrientation();
            
            if (mCurrentOri != newOri && mCurrentCall != null) {
                mCurrentOri = newOri;
                // If the orientation has changed tell the remote device what the new orientation is. 
                mCurrentCall.setDeviceOrientation(mCurrentOri);
            }
        }

    }

    private class MyProximityEventListener implements SensorEventListener {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void onSensorChanged(SensorEvent event) {
            float max = event.sensor.getMaximumRange();
            float[] values = event.values;
            float proximityValue = values[0];
            if (proximityValue < max) {
                mTouchLockOverlay.setVisibility(View.VISIBLE);
            } else {
                mTouchLockOverlay.setVisibility(View.GONE);
            }
        }

    }

    private class MyTouchLockTouchListener implements View.OnTouchListener {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return true;
        }

    }

}
