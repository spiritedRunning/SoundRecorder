package com.example.soundrecord2;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class Recorder implements OnCompletionListener, OnErrorListener {
	static final String TAG = "Recorder";
	static final String SAMPLE_PREFIX = "recording";
	static final String SAMPLE_PATH_KEY = "sample_path";
	static final String SAMPLE_LENGTH_KEY = "sample_length";

	public static final int IDLE_STATE = 0;
	public static final int RECORDING_STATE = 1;
	public static final int PLAYING_STATE = 2;
	public static final int SUSPENDED_STATE = 3;

	int mState = IDLE_STATE;

	public static final int NO_ERROR = 0;
	public static final int SDCARD_ACCESS_ERROR = 1;
	public static final int INTERNAL_ERROR = 2;
	public static final int IN_CALL_RECORD_ERROR = 3;

	private static final String DEFAULT_STORE_SUBDIR = "/recordings";

	private AudioManager mAudioMngr;

	public interface OnStateChangedListener {
		public void onStateChanged(int state);

		public void onError(int error);
	}

	OnStateChangedListener mOnStateChangedListener = null;

	long mSampleStart = 0; // time at which latest record or play operation
							// started
	int mSampleLength = 0; // length of current sample
	long mSuspended = 0;
	File mSampleFile = null;

	MediaRecorder mRecorder = null;
	MediaPlayer mPlayer = null;

	private SoundRecorder mSoundRecorderActivity;

	public Recorder(SoundRecorder activity) {
		mSoundRecorderActivity = activity;
	}

	public void saveState(Bundle recorderState) {
		if (mSampleFile != null) {
			recorderState.putString(SAMPLE_PATH_KEY,
					mSampleFile.getAbsolutePath());
		}
		recorderState.putInt(SAMPLE_LENGTH_KEY, mSampleLength);
	}

	public int getMaxAmplitude() {
		if (mState != RECORDING_STATE || mRecorder == null)
			return 0;
		return mRecorder.getMaxAmplitude();
	}

	public void restoreState(Bundle recorderState) {
		String samplePath = recorderState.getString(SAMPLE_PATH_KEY);
		if (samplePath == null)
			return;
		int sampleLength = recorderState.getInt(SAMPLE_LENGTH_KEY, -1);
		if (sampleLength == -1)
			return;

		File file = new File(samplePath);
		if (!file.exists())
			return;
		if (mSampleFile != null
				&& mSampleFile.getAbsolutePath().compareTo(
						file.getAbsolutePath()) == 0)
			return;

		delete();
		mSampleFile = file;
		mSampleLength = sampleLength;

		signalStateChanged(IDLE_STATE);
	}

	public void setOnStateChangedListener(OnStateChangedListener listener) {
		mOnStateChangedListener = listener;
	}

	public int state() {
		return mState;
	}

	public int progress() {
		if (mState == RECORDING_STATE) {
			return (int) ((System.currentTimeMillis() - mSampleStart + mSuspended) / 1000);
		} else if (mState == SUSPENDED_STATE) {
			return (int) ((mSuspended) / 1000);
		} else if (mState == PLAYING_STATE) {
			return (int) ((System.currentTimeMillis() - mSampleStart) / 1000);
		}
		return 0;
	}

	public int sampleLength() {
		return mSampleLength;
	}

	public File sampleFile() {
		return mSampleFile;
	}

	/**
	 * Resets the recorder state. If a sample was recorded, the file is deleted.
	 */
	public void delete() {
		stop();

		if (mSampleFile != null)
			mSampleFile.delete();

		mSampleFile = null;
		mSampleLength = 0;

		signalStateChanged(IDLE_STATE);
	}

	/**
	 * Resets the recorder state. If a sample was recorded, the file is left on
	 * disk and will be reused for a new recording.
	 */
	public void clear() {
		stop();

		mSampleLength = 0;

		signalStateChanged(IDLE_STATE);
	}

	private static final int SET_STATE = 0;
	private static final int SET_ERROR = 1;
	private static final int SET_IDLE = 2;

	Handler recordHandler = new Handler() {
		public void handleMessage(Message msg) {
			int what = msg.what;
			switch (what) {
			case SET_STATE:
				setState(RECORDING_STATE);
				break;
			case SET_IDLE:
				setState(IDLE_STATE);
				break;
			case SET_ERROR:
				int error = msg.arg1;
				setError(error);
				break;
			default:
				throw new RuntimeException("can't handle this code:" + what);
			}
		}
	};

	// public void startRecording(int outputfileformat, String extension,
	// Context context) {
	// add by lish 12.02.15
	public void startRecording(final int outputfileformat,
			final String extension, final Context context,
			final String selectedPath) {
		stop();

		File sampleDir = null;
		if (!selectedPath.equals("")) {
			sampleDir = new File(selectedPath);
		} else {
			sampleDir = new File(SoundRecorder.getInternalStorageDirectory()
					.getPath() + DEFAULT_STORE_SUBDIR);
			// sampleDir = new
			// File("/data/data/com.android.soundrecorder"+
			// DEFAULT_STORE_SUBDIR);
		}
		if (!sampleDir.isDirectory() && !sampleDir.mkdir()) {
			Log.e("SoundRecorder",
					"Recording File aborted - can't create base directory "
							+ sampleDir.getPath());
			recordHandler.obtainMessage(SET_ERROR, SDCARD_ACCESS_ERROR, 0)
					.sendToTarget();
			return;
		}

		try {
			mSampleFile = File.createTempFile(SAMPLE_PREFIX, extension,
					sampleDir);
		} catch (Exception e) {
			Log.d(TAG, "mSampleFile Exception" + e);
			recordHandler.obtainMessage(SET_ERROR, SDCARD_ACCESS_ERROR, 0)
					.sendToTarget();
			return;
		}

		new Thread() {
			public void run() {
				// }add by yangqingan 2011-11-27 for NEWMS00145725

				mRecorder = new MediaRecorder();
				mRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
				mRecorder.setOutputFormat(outputfileformat);
				mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
				mRecorder.setOutputFile(mSampleFile.getAbsolutePath());

				// Handle IOException
				try {
					mRecorder.prepare();
				} catch (IOException exception) {
					recordHandler.obtainMessage(SET_ERROR, INTERNAL_ERROR, 0)
							.sendToTarget();
					if (mRecorder != null) {
						mRecorder.reset();
						mRecorder.release();
						mRecorder = null;
					}
					return;
				}
				// Handle RuntimeException if the recording couldn't start
				try {
					// delete by liuyd for bug4242, deadlock happened between
					// multi thread, delete this incorrect lock
					// synchronized (mSoundRecorderActivity.mEmptyLock) {

					// fix bug 6022 start
					// obtain audio focus at the begin of recording
					AudioManager audioManager = (AudioManager) mSoundRecorderActivity
							.getSystemService(Context.AUDIO_SERVICE);
					audioManager.requestAudioFocus(mAudioFocusListener,
							AudioManager.STREAM_MUSIC,
							AudioManager.AUDIOFOCUS_GAIN);
					// fix bug 6022 end

					if (!mSoundRecorderActivity.getActivityState()) {
						mRecorder.start();
					} else {
						if (mRecorder != null) {
							mRecorder.reset();
							mRecorder.release();
							mRecorder = null;
						}
						recordHandler.obtainMessage(SET_IDLE).sendToTarget();
						return;
					}
					// }
				} catch (RuntimeException exception) {
					AudioManager audioMngr = (AudioManager) context
							.getSystemService(Context.AUDIO_SERVICE);
					boolean isInCall = ((audioMngr.getMode() == AudioManager.MODE_IN_CALL));
					if (isInCall) {
						recordHandler.obtainMessage(SET_ERROR,
								IN_CALL_RECORD_ERROR, 0).sendToTarget();
					} else {
						recordHandler.obtainMessage(SET_ERROR, INTERNAL_ERROR,
								0).sendToTarget();
					}
					recordHandler.obtainMessage(SET_IDLE).sendToTarget();
					if (mRecorder != null) {
						mRecorder.reset();
						mRecorder.release();
						mRecorder = null;
					}
					return;
				}
				mSampleStart = System.currentTimeMillis();
				mSuspended = 0;
				recordHandler.obtainMessage(SET_STATE).sendToTarget();
			};
		}.start();
	}

	public void pauseRecording() {
		if (mRecorder == null) {
			return;
		}
		try {
			// mRecorder.pause();//way
		} catch (IllegalStateException exception) {
			Log.e(TAG, "mRecorder pause error. " + exception);
			stopRecording();
			return;
		}
		mSuspended = System.currentTimeMillis() - mSampleStart + mSuspended;
		mSampleLength = (int) (mSampleLength + (System.currentTimeMillis() - mSampleStart) / 1000);
		setState(SUSPENDED_STATE);

		// fix bug 6022 start
		AudioManager audioManager = (AudioManager) mSoundRecorderActivity
				.getSystemService(Context.AUDIO_SERVICE);
		audioManager.abandonAudioFocus(mAudioFocusListener);
		// fix bug 6022 end
	}

	public void resumeRecording() {
		if (mRecorder == null) {
			return;
		}

		// fix bug 6022 start
		AudioManager audioManager = (AudioManager) mSoundRecorderActivity
				.getSystemService(Context.AUDIO_SERVICE);
		audioManager.requestAudioFocus(mAudioFocusListener,
				AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
		// fix bug 6022 end

		mSampleStart = System.currentTimeMillis();
		try {
			// mRecorder.resume();//way
		} catch (IllegalStateException exception) {
			Log.e(TAG, "mRecorder resume error. " + exception);
			stopRecording();
			return;
		}
		setState(RECORDING_STATE);
	}

	public void stopRecording() {
		if (mRecorder == null)
			return;

		try {
			mRecorder.stop();
		} catch (RuntimeException e) {
			if (mSampleFile != null) {
				mSampleFile.delete();
			}
			Log.w(TAG, "did you call stop() immediately after start()?", e);
		}
		mRecorder.release();
		mRecorder = null;

		if (mState == RECORDING_STATE) {
			mSampleLength = mSampleLength
					+ (int) ((System.currentTimeMillis() - mSampleStart) / 1000);
		}

		if (mSampleLength == 0) {
			if (mSampleFile != null) {
				mSampleFile.delete();
			}
		}
		setState(IDLE_STATE);

		// fix bug 6022 start
		AudioManager audioManager = (AudioManager) mSoundRecorderActivity
				.getSystemService(Context.AUDIO_SERVICE);
		audioManager.abandonAudioFocus(mAudioFocusListener);
		// fix bug 6022 end
	}

	public void startPlayback(Context context) {
		stop();
		mAudioMngr = (AudioManager) context
				.getSystemService(Context.AUDIO_SERVICE);
		mAudioMngr.requestAudioFocus(mAudioFocusListener,
				AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
		mPlayer = new MediaPlayer();
		try {
			mPlayer.setDataSource(mSampleFile.getAbsolutePath());
			mPlayer.setOnCompletionListener(this);
			mPlayer.setOnErrorListener(this);
			mPlayer.prepare();
			mPlayer.start();
		} catch (IllegalArgumentException e) {
			setError(INTERNAL_ERROR);
			mPlayer = null;
			return;
		} catch (IOException e) {
			Log.d(TAG, "IOException" + e);
			setError(SDCARD_ACCESS_ERROR);
			mPlayer = null;
			return;
		}

		mSampleStart = System.currentTimeMillis();
		setState(PLAYING_STATE);
	}

	private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
		public void onAudioFocusChange(int focusChange) {
			switch (focusChange) {
			case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
				if (mPlayer == null) {
					return;
				} else {
					mPlayer.pause();
				}
				break;
			case AudioManager.AUDIOFOCUS_GAIN:
				if (mPlayer == null) {
					return;
				} else {
					mPlayer.start();
				}
				break;
			case AudioManager.AUDIOFOCUS_LOSS:
				if (mPlayer == null && mRecorder == null) {
					return;
				} else if (mPlayer != null) {
					mPlayer.stop();
					mPlayer.release();
					mPlayer = null;
					if (mAudioMngr != null) {
						mAudioMngr.abandonAudioFocus(mAudioFocusListener);
					}
				} else {
					stopRecording();
				}
				break;
			}

		}
	};

	public void stopPlayback() {
		if (mPlayer == null) // we were not in playback
			return;

		mPlayer.stop();
		mPlayer.release();
		mPlayer = null;
		if (mAudioMngr != null) {
			mAudioMngr.abandonAudioFocus(mAudioFocusListener);
		}
		setState(IDLE_STATE);
	}

	public void stop() {
		stopRecording();
		stopPlayback();
	}

	public boolean onError(MediaPlayer mp, int what, int extra) {
		stop();
		setError(SDCARD_ACCESS_ERROR);
		return true;
	}

	public void onCompletion(MediaPlayer mp) {
		stop();
	}

	public void setState(int state) {
		mState = state;
		signalStateChanged(mState);
	}

	private void signalStateChanged(int state) {
		if (mOnStateChangedListener != null)
			mOnStateChangedListener.onStateChanged(state);
	}

	private void setError(int error) {
		if (mOnStateChangedListener != null)
			mOnStateChangedListener.onError(error);
	}
}
