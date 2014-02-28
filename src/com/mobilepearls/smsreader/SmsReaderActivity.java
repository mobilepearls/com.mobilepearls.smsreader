package com.mobilepearls.smsreader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;

public class SmsReaderActivity extends PreferenceActivity {

	private PrefsFragment fragment = new PrefsFragment();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
	}

	@Override
	protected void onResume() {
		super.onResume();

		final boolean[] ttsSuccess = new boolean[1];
		final CountDownLatch ttsInitLatch = new CountDownLatch(1);

		new Thread() {
			@Override
			public void run() {
				TextToSpeech tts = new TextToSpeech(SmsReaderActivity.this, new OnInitListener() {
					@Override
					public void onInit(int status) {
						ttsSuccess[0] = (status == TextToSpeech.SUCCESS);
						ttsInitLatch.countDown();
					}
				});

				try {
					ttsInitLatch.await();
				} catch (InterruptedException e) {
					Log.wtf("mobilepearls", "When waiting for ttsInitLatch", e);
					finish();
					return;
				}

				if (!ttsSuccess[0]) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							new AlertDialog.Builder(SmsReaderActivity.this).setIcon(android.R.drawable.ic_dialog_alert)
									.setTitle(R.string.tts_init_failed_title).setMessage(R.string.tts_init_failed_message)
									.setPositiveButton(android.R.string.ok, new OnClickListener() {
										@Override
										public void onClick(DialogInterface dialog, int which) {
											startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
										}
									}).show();
						}
					});
					return;
				}

				List<Locale> possibleLocales = new ArrayList<Locale>();
				for (Locale locale : Locale.getAvailableLocales()) {
					if (!("".equals(locale.getCountry()) && "".equals(locale.getVariant())))
						continue;
					try {
						int langAvailability = tts.isLanguageAvailable(locale);
						if (!(langAvailability == TextToSpeech.LANG_MISSING_DATA || langAvailability == TextToSpeech.LANG_NOT_SUPPORTED)) {
							possibleLocales.add(locale);
						}
					} catch (IllegalArgumentException e) {
						// Ignore.
					}
				}
				tts.shutdown();

				final CharSequence entries[] = new String[possibleLocales.size()];
				final CharSequence entryValues[] = new String[possibleLocales.size()];
				for (int i = 0; i < possibleLocales.size(); i++) {
					entries[i] = possibleLocales.get(i).getDisplayName();
					entryValues[i] = possibleLocales.get(i).getLanguage();
				}

				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (entries.length == 0) {
							new AlertDialog.Builder(SmsReaderActivity.this).setIcon(android.R.drawable.ic_dialog_alert)
									.setTitle(R.string.tts_no_language_found_title).setMessage(R.string.tts_no_language_found_message)
									.setPositiveButton(android.R.string.ok, new OnClickListener() {
										@Override
										public void onClick(DialogInterface dialog, int which) {
											startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
										}
									}).show();
						} else {
							ListPreference languagePreference = (ListPreference) fragment.findPreference("language");
							languagePreference.setEntries(entries);
							languagePreference.setEntryValues(entryValues);
						}
					}
				});
			}
		}.start();

	}

	public static class PrefsFragment extends PreferenceFragment {
		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);
			addPreferencesFromResource(R.xml.preferences);

			ListPreference listPreference = (ListPreference) findPreference("language");
			if (listPreference.getValue() != null) {
				String newLanguageName = new Locale(listPreference.getValue()).getDisplayName();
				listPreference.setSummary(getString(R.string.pref_language_summary_selected, newLanguageName));
			}
			listPreference.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
				@Override
				public boolean onPreferenceChange(Preference preference, Object newValue) {
					String newLanguageCode = newValue.toString();
					String newLanguageName = new Locale(newLanguageCode).getDisplayName();
					preference.setSummary(getString(R.string.pref_language_summary_selected, newLanguageName));
					return true;
				}
			});

		}
	}

}
