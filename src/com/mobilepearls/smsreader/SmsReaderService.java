package com.mobilepearls.smsreader;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.PhoneLookup;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * An intent service which receives SMS messages through the {@link #MESSAGES_EXTRA} attribute and speaks the result.
 */
public class SmsReaderService extends IntentService {

	private static final int NOTIFICATION_TTS_FAILURE = 1;

	private static final String[] DISPLAY_NAME_PROJECTION = { PhoneLookup.DISPLAY_NAME };
	static final String MESSAGES_EXTRA = "messages";

	private static String getContactNameFromNumber(Context context, String number) {
		Uri contactUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
		Cursor c = context.getContentResolver().query(contactUri, DISPLAY_NAME_PROJECTION, null, null, null);
		try {
			return c.moveToFirst() ? c.getString(c.getColumnIndex(PhoneLookup.DISPLAY_NAME)) : null;
		} finally {
			c.close();
		}
	}

	@SuppressWarnings("deprecation")
	private static boolean isHeadsetOn(Context context) {
		AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		// isWiredHeadsetOn() is deprecated due to misleading name, but no better alternative exists.
		// isBluetoothA2dpOn() does not need to imply a headset, but we assume that here.
		return (am.isWiredHeadsetOn() || am.isBluetoothA2dpOn());
	}

	/** Messages maps from numbers to text. */
	public static void speak(final Context context, final SmsMessage[] messages) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

		if (!prefs.getBoolean("enabled", true))
			return;

		boolean requireHeadphones = prefs.getBoolean("require_headphones", true);
		if (requireHeadphones && !isHeadsetOn(context)) {
			Log.i("mobilepearls", "Headphones required but not plugged in - ignoring SMS");
			return;
		}

		TelephonyManager telManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		if (telManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
			Log.i("mobilepearls", "In call - ignoring SMS");
			return;
		}

		String language = prefs.getString("language", "en");

		final boolean[] ttsSuccess = new boolean[1];
		final CountDownLatch ttsInitLatch = new CountDownLatch(1);

		TextToSpeech tts = new TextToSpeech(context, new OnInitListener() {
			@Override
			public void onInit(int status) {
				ttsSuccess[0] = (status == TextToSpeech.SUCCESS);
				ttsInitLatch.countDown();
			}
		});

		try {
			ttsInitLatch.await();

			if (!ttsSuccess[0]) {
				Intent startIntent = new Intent(context, SmsReaderActivity.class);
				Notification notification = new Notification.Builder(context).setAutoCancel(true).setTicker(context.getString(R.string.tts_init_failed_title))
						.setContentTitle(context.getString(R.string.tts_init_failed_title)).setContentText(context.getString(R.string.tts_init_failed_message))
						.setSmallIcon(R.drawable.ic_stat_ttsfailure).setContentIntent(PendingIntent.getActivity(context, 0, startIntent, 0)).build();
				NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
				manager.notify(NOTIFICATION_TTS_FAILURE, notification);
				return;
			}

			final CountDownLatch ttsSpeechLatch = new CountDownLatch(1);

			Locale speechLocale = new Locale(language);

			// http://developer.android.com/reference/android/speech/tts/TextToSpeech.html#setLanguage(java.util.Locale)
			int setLanguageReturnValue = tts.setLanguage(speechLocale);
			switch (setLanguageReturnValue) {
			case TextToSpeech.LANG_AVAILABLE:
				// Denotes the language is available for the language by the locale, but not the country and variant.
				Log.v("mobilepearls", "LANG_AVAILABLE: " + language);
				break;
			case TextToSpeech.LANG_COUNTRY_AVAILABLE:
				// Denotes the language is available for the language and country specified by the locale, but not the
				// variant.
				Log.v("mobilepearls", "LANG_COUNTRY_AVAILABLE: " + language);
				break;
			case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
				// Denotes the language is available exactly as specified by the locale.
				Log.v("mobilepearls", "LANG_COUNTRY_VAR_AVAILABLE: " + language);
				break;
			case TextToSpeech.LANG_MISSING_DATA:
				// Denotes the language data is missing.
			case TextToSpeech.LANG_NOT_SUPPORTED:
				// Denotes the language is not supported.
				Intent startIntent = new Intent(context, SmsReaderActivity.class);
				boolean missingData = setLanguageReturnValue == TextToSpeech.LANG_MISSING_DATA;
				String title = context.getString(missingData ? R.string.tts_missing_data_title : R.string.tts_lang_unavailable_title);

				Notification notification = new Notification.Builder(context).setAutoCancel(true).setTicker(title).setContentTitle(title)
						.setContentText(context.getString(R.string.tts_for_language_message, speechLocale.getDisplayLanguage()))
						.setSmallIcon(R.drawable.ic_stat_ttsfailure).setContentIntent(PendingIntent.getActivity(context, 0, startIntent, 0)).build();
				NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
				manager.notify(NOTIFICATION_TTS_FAILURE, notification);
				return;
			default:
				Log.wtf("mobilepearls", "Unknown return value: " + setLanguageReturnValue);
				break;
			}

			StringBuilder buffer = new StringBuilder();
			for (SmsMessage message : messages) {
				String number = message.getOriginatingAddress();
				String contactName = getContactNameFromNumber(context, number);
				String body = message.getMessageBody();
				String speech = ((contactName == null) ? number : contactName);
				if (!prefs.getBoolean("only_read_sender_name", false)) {
					speech += ": " + body;
				}
				if (buffer.length() > 0)
					buffer.append("\n");
				buffer.append(speech);
			}

			tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {

				@Override
				public void onStart(String utteranceId) {
					// Ignore.
				}

				@Override
				public void onError(String utteranceId) {
					Log.e("mobilepearls", "tts.onError()");
					ttsSpeechLatch.countDown();
				}

				@Override
				public void onDone(String utteranceId) {
					ttsSpeechLatch.countDown();
				}
			});

			// Give the system notification signal a moment:
			Thread.sleep(1500);

			HashMap<String, String> params = new HashMap<String, String>();
			params.put(Engine.KEY_PARAM_STREAM, Integer.toString(AudioManager.STREAM_SYSTEM));
			params.put(Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id");
			tts.speak(buffer.toString(), TextToSpeech.QUEUE_ADD, params);

			ttsSpeechLatch.await();
		} catch (Exception e) {
			Log.wtf("mobilepearls", "Exception in SmsReaderService", e);
		} finally {
			tts.shutdown();
		}
	}

	/**
	 * Created and configured as non-reference counted in {@link #onCreate()}, acquired in {@link #onStartCommand(Intent, int, int)} and released in
	 * {@link #onDestroy()}.
	 */
	private WakeLock wakeLock;

	public SmsReaderService() {
		super(SmsReaderService.class.getName());
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.v("mobilepearls", "onCreate - creating wakeLock");
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SmsReaderService.class.getName());
		this.wakeLock.setReferenceCounted(false);
	}

	@Override
	public int onStartCommand(final Intent intent, int flags, int startId) {
		Log.v("mobilepearls", "onStartCommand - acquiring wakeLock");
		wakeLock.acquire();
		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onDestroy() {
		Log.v("mobilepearls", "onDestroy - releasing wakeLock");
		wakeLock.release();
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		SmsMessage[] messages = (SmsMessage[]) intent.getSerializableExtra(MESSAGES_EXTRA);
		speak(SmsReaderService.this, messages);
	}

}
