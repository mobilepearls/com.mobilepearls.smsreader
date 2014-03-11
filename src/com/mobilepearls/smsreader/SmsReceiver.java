package com.mobilepearls.smsreader;

import java.util.HashMap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsMessage;

/**
 * A BroadcastReceiver registered with the {@link Intents#SMS_RECEIVED_ACTION} intent filter.
 */
public class SmsReceiver extends BroadcastReceiver {

	/**
	 * Called by the system in the main thread when a SMS is received. Parses the message(s) from the intent and delegates to {@link SmsReaderService} to do
	 * actual work in separate thread.
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		HashMap<String, String> messagesMap = new HashMap<>();
		SmsMessage[] messages = Intents.getMessagesFromIntent(intent);
		for (SmsMessage message : messages) {
			String number = message.getOriginatingAddress();
			String body = message.getMessageBody();
			String existingBody = messagesMap.get(number);
			if (existingBody != null) {
				body = existingBody + body;
			}
			messagesMap.put(number, body);
		}
		Intent serviceIntent = new Intent(context, SmsReaderService.class);
		serviceIntent.putExtra(SmsReaderService.MESSAGES_EXTRA, messagesMap);
		context.startService(serviceIntent);
	}

}
