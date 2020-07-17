package com.ictpack.sms;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.telephony.SmsManager;
import android.util.Log;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.provider.Telephony;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

@NativePlugin(
    permissions={
	Manifest.permission.RECEIVE_SMS,
	Manifest.permission.SEND_SMS
  })
public class IctpackSms extends Plugin {
    private static final String LOG_TAG = "ictpack-capacitor-sms";
	private static final String ACTION_START_WATCH = "startWatch";
	private static final String ACTION_STOP_WATCH = "stopWatch";
	private static final String SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED";

	public static final int START_WATCH_REQ_CODE = 0;
	public static final int PERMISSION_DENIED_ERROR = 20;

	private static final String ERR_SERVICE_NOTFOUND = "ERR_SERVICE_NOTFOUND";
	private static final String ERR_NO_NUMBERS = "ERR_NO_NUMBERS";
	private static final String ERR_NO_TEXT = "ERR_NO_TEXT";
    private static final String INTENT_FILTER_SMS_SENT="SMS_SENT";
	private BroadcastReceiver mReceiver = null;
    
    @PluginMethod()
    public void startWatch(PluginCall call) {
    	if(!checkSupport())
			call.reject("Device Not Supported");

        if(!hasRequiredPermissions())
            pluginRequestAllPermissions();

		if (this.mReceiver == null) {
			this.createIncomingSMSReceiver(call);
		}
		Log.d(LOG_TAG, ACTION_START_WATCH);
      call.resolve();
    }


    @PluginMethod()
    private void stopWatch(PluginCall call) {
		Log.d(LOG_TAG, ACTION_STOP_WATCH);
		if (this.mReceiver != null) {
			try {
				getContext().unregisterReceiver(this.mReceiver);
			} catch (Exception e) {
				String errorMsg = "error unregistering network receiver: " + e.getMessage();
				Log.d(LOG_TAG, errorMsg);
				call.reject(errorMsg);
			} finally {
				this.mReceiver = null;
			}
        }
        call.resolve();;
	}


	@PluginMethod()
	public void send(PluginCall call) {
		saveCall(call);
		sendSms(call);
	}

	/**
	 *
	 * @param call
	 */
	private void sendSms(final PluginCall call) {
		if(!checkSupport())
			call.reject("Device Not Supported");

		if(!hasRequiredPermissions())
			pluginRequestAllPermissions();

		JSArray numberArray = call.getArray("numbers");

		String text = call.getString("text");

		if (text == null || text.length() == 0) {
			call.reject(ERR_NO_TEXT);
			return;
		}

		String separator = ";";
		if (android.os.Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
			separator = ",";
		}

		String phoneNumber = "";
		try {
			phoneNumber = numberArray.join(separator).replace("\"", "");
		} catch (JSONException ignore) {}


		text = text.replace("\\n", System.getProperty("line.separator"));
		_send(call,phoneNumber,text);
	}
	/**
	 *
	 * @param call
	 */
    protected void createIncomingSMSReceiver(PluginCall call) {
		this.mReceiver = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {
				if (intent.getAction().equals(SMS_RECEIVED_ACTION)) {
					// Create SMS container
					String smsBody = "";
					SmsMessage smsmsg = null;
					// Determine which API to use
					if (Build.VERSION.SDK_INT >= 19) {
						try {

							for (SmsMessage smsMessage : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
								smsBody += smsMessage.getMessageBody();
								smsmsg = smsMessage;
							}
						} catch (Exception e) {
							Log.d(LOG_TAG, e.getMessage());
						}
					} else {
						Bundle bundle = intent.getExtras();
						Object pdus[] = (Object[]) bundle.get("pdus");
						try {
							smsmsg = SmsMessage.createFromPdu((byte[]) pdus[0]);
						} catch (Exception e) {
							Log.d(LOG_TAG, e.getMessage());
						}
					}
					// Get SMS contents as JSON
					if (smsmsg != null) {
						JSObject jsms = IctpackSms.this.getJsonFromSmsMessage(smsmsg,smsBody);
						IctpackSms.this.onSMSArrive(jsms);
						Log.d(LOG_TAG, jsms.toString());
					} else {
						Log.d(LOG_TAG, "smsmsg is null");
					}
				}
			}
		};
		IntentFilter filter = new IntentFilter(SMS_RECEIVED_ACTION);
		try {
			getContext().registerReceiver(this.mReceiver, filter);
		} catch (Exception e) {
			String errorMsg = "error registering broadcast receiver: " + e.getMessage();
			Log.d(LOG_TAG,errorMsg);
			call.reject(errorMsg);
		}
    }

    private JSObject getJsonFromSmsMessage(SmsMessage sms, String smsBody) {
		JSObject json = new JSObject();
		try {
			json.put("address", sms.getOriginatingAddress());
			json.put("body", smsBody); // May need sms.getMessageBody.toString()
			json.put("date_sent", sms.getTimestampMillis());
			json.put("date", System.currentTimeMillis());
			json.put("service_center", sms.getServiceCenterAddress());
		} catch (Exception e) {
			Log.d(LOG_TAG, e.getMessage());
		}
		return json;
    }
    
    private void onSMSArrive(JSObject json) {
		notifyListeners("onSMSArrive",json);
	}

	/**
	 *
	 * @param call
	 * @param phoneNumber
	 * @param message
	 */
	private void _send(final PluginCall call, String phoneNumber, String message) {
		SmsManager manager = SmsManager.getDefault();
		final ArrayList<String> parts = manager.divideMessage(message);
		// by creating this broadcast receiver we can check whether or not the SMS was sent
		final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

			boolean anyError = false; //use to detect if one of the parts failed
			int partsCount = parts.size(); //number of parts to send

			@Override
			public void onReceive(Context context, Intent intent) {
				switch (getResultCode()) {
					case SmsManager.STATUS_ON_ICC_SENT:
					case Activity.RESULT_OK:
						break;
					case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
					case SmsManager.RESULT_ERROR_NULL_PDU:
					case SmsManager.RESULT_ERROR_RADIO_OFF:
					case SmsManager.RESULT_ERROR_NO_SERVICE:
						anyError = true;
						break;
				}
				// trigger the callback only when all the parts have been sent
				partsCount--;
				if (partsCount == 0) {
					if (anyError) {
						call.reject("failed");
					} else {
						call.resolve();
					}
					getContext().unregisterReceiver(this);
				}
			}
		};
		// randomize the intent filter action to avoid using the same receiver
		String intentFilterAction = INTENT_FILTER_SMS_SENT + java.util.UUID.randomUUID().toString();
		getContext().registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));
		PendingIntent sentIntent = PendingIntent.getBroadcast(bridge.getActivity(), 0, new Intent(intentFilterAction), 0);
		// depending on the number of parts we send a text message or multi parts
		if (parts.size() > 1) {
			ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
			for (int i = 0; i < parts.size(); i++) {
				sentIntents.add(sentIntent);
			}
			manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
		}
		else {
			manager.sendTextMessage(phoneNumber, null, message, sentIntent, null);
		}

	}

	/**
	 *
	 * @return
	 */
	private boolean checkSupport() {
		Activity ctx = bridge.getActivity();
		return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
	}

}
