package com.mobilepearls.smsreader;

import java.util.HashMap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * A BroadcastReceiver registered with the <code>android.provider.Telephony.SMS_RECEIVED</code> intent filter.
 */
public class SmsReceiver extends BroadcastReceiver {

	/**
	 * Extract the SMS messages contained in an intent received due to the
	 * <code>android.provider.Telephony.SMS_RECEIVED</code> intent filter, returning them mapped from originating
	 * address to message text.
	 * 
	 * Returns a HashMap so that the result may be serialized and put as an intent extra.
	 */
	private static HashMap<String, String> retrieveMessages(Intent intent) {
		Bundle extras = intent.getExtras();
		if (extras == null) {
			Log.e("mobilepearls", "SmsReceiver: intent.getExtras() is null - ignoring");
			return new HashMap<String, String>();
		}

		final Object[] pdus = (Object[]) extras.get("pdus");
		if (pdus == null || pdus.length == 0) {
			Log.e("mobilepearls", "SmsReceiver: intent.getExtras().get('pdus') is null or empty - ignoring");
			return new HashMap<String, String>();
		}
		HashMap<String, String> result = new HashMap<String, String>(pdus.length);

		for (int i = 0; i < pdus.length; i++) {
			SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdus[i]);
			String originatingAddress = sms.getOriginatingAddress();
			String previousText = result.get(originatingAddress);
			if (previousText == null) {
				result.put(sms.getOriginatingAddress(), sms.getMessageBody());
			} else {
				result.put(originatingAddress, previousText + sms.getMessageBody());
			}
		}
		return result;
	}

	/**
	 * Called by the system in the main thread when a SMS is received. Parses the message(s) from the intent and
	 * delegates to {@link SmsReaderService} to do actual work in separate thread.
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		HashMap<String, String> messages = retrieveMessages(intent);
		Intent serviceIntent = new Intent(context, SmsReaderService.class);
		serviceIntent.putExtra(SmsReaderService.MESSAGES_EXTRA, messages);
		context.startService(serviceIntent);
	}

}
