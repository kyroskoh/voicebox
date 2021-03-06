package com.typpo.voicebox;

import java.io.File;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Environment;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;

public class VoiceBoxActivity extends Activity {

	private DropboxAPI<AndroidAuthSession> mDBApi;
	private boolean mRecording;
	private String mLastFilePath;
	private Audio mAudio;
	private ConnectionWatcher mConnection;
	private TextView mLink;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mRecording = false;

		setContentView(R.layout.main);
		mLink = (TextView) findViewById(R.id.lnkLogin);

		Init();
	}

	public void Init() {
		AndroidAuthSession session = buildSession();
		mDBApi = new DropboxAPI<AndroidAuthSession>(session);
		updateLinkState();

		mConnection = new ConnectionWatcher(this, new IConnectionCallback() {
			public void StateChanged(ConnectionState cur, ConnectionState prev) {
				if (cur.equals(ConnectionState.CONNECTED)
						&& !prev.equals(ConnectionState.CONNECTED)
						&& !prev.equals(ConnectionState.NULL)) {
					toast("Detected new connection...");
					UploadAll();
				}
			}
		});
		mConnection.Listen();
	}

	public void MainButtonClick(View v) {
		if (!mDBApi.getSession().isLinked()) {
			toast("Please link your Dropbox account first.");
			Authenticate(this);
			return;
		}

		Button b = (Button) v;
		if (!mRecording) {
			mAudio = new Audio();
			mLastFilePath = mAudio.StartRecording();
			if (mLastFilePath == null) {
				toast("Sorry, there was a problem writing your storage device/SD card. Make sure you have an SD card and it's not mounted on your computer.");
				return;
			}
			mRecording = true;
			b.setText("Stop Recording");
		} else {
			mAudio.StopRecording();
			mRecording = false;
			Upload("/", mLastFilePath);

			b.setText("Start Recording");
		}
	}

	public void LoginLink(View v) {
		if (mDBApi.getSession().isLinked()) {
			Logout();
		} else {
			Authenticate(this);
		}
	}

	public void AuthenticateButtonClick(View v) {
		Authenticate(this);
	}

	public void Authenticate(Activity a) {
		mDBApi.getSession().startAuthentication(a);

	}

	public void Upload(String filename, String path) {
		if (!mConnection.getState().equals(ConnectionState.CONNECTED)) {
			toast("Couldn't find a data connection, so saving file for upload later...");
			return;
		}

		Uploader u = new Uploader(this, mDBApi, filename, new File(path));
		u.execute();
		/*
		 * File f = new File(path);
		 * 
		 * String fileContents = "Hello World!"; ByteArrayInputStream
		 * inputStream = new ByteArrayInputStream( fileContents.getBytes()); try
		 * { Entry newEntry = mDBApi.putFile("/testing.txt", inputStream,
		 * fileContents.length(), null, null); Log .i("DbExampleLog",
		 * "The uploaded file's rev is: " + newEntry.rev); } catch
		 * (DropboxUnlinkedException e) { // User has unlinked, ask them to link
		 * again here. Log.e("DbExampleLog", "User has unlinked."); } catch
		 * (DropboxException e) {
		 * toast("Something went wrong while uploading :("); }
		 */
	}

	public void UploadAll() {
		File dir = new File(Environment.getExternalStorageDirectory()
				.getAbsolutePath()
				+ Constants.APP_DIR);

		File[] children = dir.listFiles();
		if (children != null) {
			for (int i = 0; i < children.length; i++) {
				// Get filename of file or directory
				File f = children[i];
				if (f.getAbsolutePath().endsWith(".3gp")) {
					Upload(f.getName(), f.getAbsolutePath());
				}
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (mDBApi.getSession().authenticationSuccessful()) {
			try {
				// MANDATORY call to complete auth.
				// Sets the access token on the session
				mDBApi.getSession().finishAuthentication();

				AccessTokenPair tokens = mDBApi.getSession()
						.getAccessTokenPair();

				// Provide your own storeKeys to persist the access token pair
				// A typical way to store tokens is using SharedPreferences
				storeKeys(tokens.key, tokens.secret);

				// update UI
				updateLinkState();
			} catch (IllegalStateException e) {
				toast("There was a problem authenticating your account.");
			}
		}
	}

	public void Logout() {
		mDBApi.getSession().unlink();
		clearKeys();

		toast("Successfully unlinked account.");
		updateLinkState();
	}

	private void updateLinkState() {
		String str;
		if (mDBApi.getSession().isLinked()) {
			str = Constants.UNLINK_TEXT;
		} else {
			str = Constants.LINK_TEXT;
		}
		SpannableString content = new SpannableString(str);
		content.setSpan(new UnderlineSpan(), 0, content.length(), 0);
		mLink.setText(content);
	}

	private void storeKeys(String key, String secret) {
		// Save the access key for later
		SharedPreferences prefs = getSharedPreferences(
				Constants.ACCOUNT_PREFS_NAME, 0);
		Editor edit = prefs.edit();
		edit.putString(Constants.ACCESS_KEY_NAME, key);
		edit.putString(Constants.ACCESS_SECRET_NAME, secret);
		edit.commit();
	}

	private void clearKeys() {
		SharedPreferences prefs = getSharedPreferences(
				Constants.ACCOUNT_PREFS_NAME, 0);
		Editor edit = prefs.edit();
		edit.clear();
		edit.commit();
	}

	/**
	 * @return Array of [access_key, access_secret], or null if none stored
	 */
	private String[] getKeys() {
		SharedPreferences prefs = getSharedPreferences(
				Constants.ACCOUNT_PREFS_NAME, 0);
		String key = prefs.getString(Constants.ACCESS_KEY_NAME, null);
		String secret = prefs.getString(Constants.ACCESS_SECRET_NAME, null);
		if (key != null && secret != null) {
			String[] ret = new String[2];
			ret[0] = key;
			ret[1] = secret;
			return ret;
		} else {
			return null;
		}
	}

	private AndroidAuthSession buildSession() {
		AppKeyPair appKeyPair = new AppKeyPair(Constants.APP_KEY,
				Constants.APP_SECRET);
		AndroidAuthSession session;

		String[] stored = getKeys();
		if (stored != null) {
			AccessTokenPair accessToken = new AccessTokenPair(stored[0],
					stored[1]);
			session = new AndroidAuthSession(appKeyPair, Constants.ACCESS_TYPE,
					accessToken);
		} else {
			session = new AndroidAuthSession(appKeyPair, Constants.ACCESS_TYPE);
		}

		return session;
	}

	private void toast(String msg) {
		Toast e = Toast.makeText(this, msg, Toast.LENGTH_LONG);
		e.show();
	}

}