package com.example.soundrecord2;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.StatFs;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Calculates remaining recording time based on available disk space and
 * optionally a maximum recording file size.
 * <p>
 * The reason why this is not trivial is that the file grows in blocks every few
 * seconds or so, while we want a smooth countdown.
 */

class RemainingTimeCalculator {
    public static final int UNKNOWN_LIMIT = 0;
    public static final int FILE_SIZE_LIMIT = 1;
    public static final int DISK_SPACE_LIMIT = 2;

    // which of the two limits we will hit (or have fit) first
    private int mCurrentLowerLimit = UNKNOWN_LIMIT;

    private File mSDCardDirectory;

    // State for tracking file size of recording.
    private File mRecordingFile;
    private long mMaxBytes;

    // Rate at which the file grows
    private int mBytesPerSecond;

    // time at which number of free blocks last changed
    private long mBlocksChangedTime;
    // number of available blocks at that time
    private long mLastBlocks;

    // time at which the size of the file has last changed
    private long mFileSizeChangedTime;
    // size of the file at that time
    private long mLastFileSize;

    public RemainingTimeCalculator() {
        mSDCardDirectory = SoundRecorder.getInternalStorageDirectory();
    }

    public void chooseSDCard(boolean external) {
        if (external) {
            mSDCardDirectory = SoundRecorder.getExternalStorageDirectory();
        } else {
            mSDCardDirectory = SoundRecorder.getInternalStorageDirectory();
        }
    }

    /**
     * If called, the calculator will return the minimum of two estimates: how
     * long until we run out of disk space and how long until the file reaches
     * the specified size.
     *
     * @param file     the file to watch
     * @param maxBytes the limit
     */

    public void setFileSizeLimit(File file, long maxBytes) {
        mRecordingFile = file;
        mMaxBytes = maxBytes;
    }

    /**
     * Resets the interpolation.
     */
    public void reset() {
        mCurrentLowerLimit = UNKNOWN_LIMIT;
        mBlocksChangedTime = -1;
        mFileSizeChangedTime = -1;
    }

    /**
     * Returns how long (in seconds) we can continue recording.
     */
    public long timeRemaining() {
        // Calculate how long we can record based on free disk space

        StatFs fs = new StatFs(mSDCardDirectory.getAbsolutePath());
        long blocks = fs.getAvailableBlocks();
        long blockSize = fs.getBlockSize();
        long now = System.currentTimeMillis();

        if (mBlocksChangedTime == -1 || blocks != mLastBlocks) {
            mBlocksChangedTime = now;
            mLastBlocks = blocks;
        }

        /*
         * The calculation below always leaves one free block, since free space
         * in the block we're currently writing to is not added. This last block
         * might get nibbled when we close and flush the file, but we won't run
         * out of disk.
         */

        // at mBlocksChangedTime we had this much time
        long result = mLastBlocks * blockSize / mBytesPerSecond;
        // so now we have this much time
        result -= (now - mBlocksChangedTime) / 1000;

        if (mRecordingFile == null) {
            mCurrentLowerLimit = DISK_SPACE_LIMIT;
            return result;
        }

        // If we have a recording file set, we calculate a second estimate
        // based on how long it will take us to reach mMaxBytes.

        mRecordingFile = new File(mRecordingFile.getAbsolutePath());
        long fileSize = mRecordingFile.length();
        if (mFileSizeChangedTime == -1 || fileSize != mLastFileSize) {
            mFileSizeChangedTime = now;
            mLastFileSize = fileSize;
        }

        long result2 = (mMaxBytes - fileSize) / mBytesPerSecond;
        result2 -= (now - mFileSizeChangedTime) / 1000;
        result2 -= 1; // just for safety

        mCurrentLowerLimit = result < result2 ? DISK_SPACE_LIMIT
                : FILE_SIZE_LIMIT;

        return Math.min(result, result2);
    }

    /**
     * Indicates which limit we will hit (or have hit) first, by returning one
     * of FILE_SIZE_LIMIT or DISK_SPACE_LIMIT or UNKNOWN_LIMIT. We need this to
     * display the correct message to the user when we hit one of the limits.
     */
    public int currentLowerLimit() {
        return mCurrentLowerLimit;
    }

    /**
     * Is there any point of trying to start recording?
     */
    public boolean diskSpaceAvailable() {
        StatFs fs = new StatFs(mSDCardDirectory.getAbsolutePath());
        // keep one free block
        return fs.getAvailableBlocks() > 1;
    }

    /**
     * Sets the bit rate used in the interpolation.
     *
     * @param bitRate the bit rate to set in bits/sec.
     */
    public void setBitRate(int bitRate) {
        mBytesPerSecond = bitRate / 8;
    }
}

public class SoundRecorder extends AppCompatActivity implements Button.OnClickListener,
        Recorder.OnStateChangedListener {
    static final String TAG = "SoundRecorder";
    static final String STATE_FILE_NAME = "soundrecorder.state";
    static final String RECORDER_STATE_KEY = "recorder_state";
    static final String SAMPLE_INTERRUPTED_KEY = "sample_interrupted";
    static final String MAX_FILE_SIZE_KEY = "max_file_size";

    private static final String ACTION_SOUNDRECORDER_PAUSE = "com.android.soundercorder.soundercorder.pause";

    static final String AUDIO_3GPP = "audio/3gpp";
    static final String AUDIO_AMR = "audio/amr";
    static final String AUDIO_ANY = "audio/*";
    static final String ANY_ANY = "*/*";

    static final int BITRATE_AMR = 5900; // bits/sec
    static final int BITRATE_3GPP = 5900;
    private static final int START_RECORDING_DIALOG_SHOW = 1;

    private static String EXTERNAL_SDCARD = "";

    private static String INTERNAL_SDCARD = "";

    private static final int PATHSELECT_RESULT_CODE = 1;
    private static String SELECTED_PATH = "";

    private Dialog mdialog;

    // private static final String DATA_PATH = "/data";
    // private long mMemoryThreshold;
    // private FullToast fullToast;
    // private StatFs mDataFileStats;

    WakeLock mWakeLock;

    KeyguardManager keyGuardManager = null;
    private volatile boolean disableKeyguarFlag = false;

    String mRequestedType = AUDIO_AMR;
    Recorder mRecorder;
    boolean mSampleInterrupted = false;
    String mErrorUiMessage = null; // Some error messages are displayed in the
    // UI,
    // not a dialog. This happens when a
    // recording
    // is interrupted for some reason.

    private boolean mIsPaused = false;
    public Object mEmptyLock = new Object();
    long mMaxFileSize = -1; // can be specified in the intent
    RemainingTimeCalculator mRemainingTimeCalculator;

    String mTimerFormat;
    final Handler mHandler = new Handler();
    Runnable mUpdateTimer = new Runnable() {
        public void run() {
            updateTimerView();
        }
    };

    /* ==Fix tool check issue== */
    public boolean fromMMS = false;

    private boolean sdCard = true;
    // mm 04 fix bug 6811
    boolean isRecord = false;
    boolean isRequestType = false;
    ImageButton mRecordButton;
    ImageButton mPlayButton;
    ImageButton mStopButton;
    Button usbSelectBtn;

    ImageView mStateLED;
    TextView mStateMessage1;
    TextView mStateMessage2;
    ProgressBar mStateProgressBar;
    TextView mTimerView;

    LinearLayout mExitButtons;
    Button mAcceptButton;
    Button mDiscardButton;
    VUMeter mVUMeter;
    private BroadcastReceiver mSDCardMountEventReceiver = null;

    public static final String ACTION_EVENT_REMINDER = "android.intent.action.EVENT_REMINDER";

    public static boolean isExternalStorageMounted() {
        return Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED);
    }

    public static File getInternalStorageDirectory() {
        File sdcardDirectory = null;

        if (isExternalStorageMounted()) {
            sdcardDirectory = Environment.getExternalStorageDirectory();
        }

        return sdcardDirectory;
    }

    public static File getExternalStorageDirectory() {
        File sdcardDirectory = null;

        if (isExternalStorageMounted()) {
            sdcardDirectory = Environment.getExternalStorageDirectory();
        }
        return sdcardDirectory;
    }

    private void configSDCardPath() {
        File sdcardDirectory = null;
        sdcardDirectory = getInternalStorageDirectory();
        if (sdcardDirectory != null) {
            INTERNAL_SDCARD = sdcardDirectory.getAbsolutePath();
        }

        sdcardDirectory = getExternalStorageDirectory();
        if (sdcardDirectory != null) {
            EXTERNAL_SDCARD = sdcardDirectory.getAbsolutePath();
        }
    }

    @Override
    public void onCreate(Bundle icycle) {
        super.onCreate(icycle);

        requestPermission();
        Intent i = getIntent();
        if (i != null) {
            String s = i.getType();
            if (s != null) {
                isRequestType = true;
            }
            if (AUDIO_AMR.equals(s) || AUDIO_3GPP.equals(s)) {
                mRequestedType = s;
            } else if (AUDIO_ANY.equals(s)) {
                mRequestedType = AUDIO_AMR;
            } else if (s != null) {
                // we only support amr and 3gpp formats right now
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
            final String EXTRA_MAX_BYTES = MediaStore.Audio.Media.EXTRA_MAX_BYTES;
            mMaxFileSize = i.getLongExtra(EXTRA_MAX_BYTES, -1);

            // Start add attachment from MMS,mm 04 fix bug 5619
            String action = i.getAction();
            if (Intent.ACTION_GET_CONTENT.equals(action)
                    || MediaStore.Audio.Media.RECORD_SOUND_ACTION
                    .equals(action)) {
                fromMMS = true;
                // 3 minutes limited, 38 is head length, 1seconds more than 180
                // is for safety
                // mMaxFileSize = 1600 * 182 + 37;
                mMaxFileSize = i.getLongExtra(
                        "android.provider.MediaStore.extra.MAX_BYTES", 0);
            }
            // End
        }

        // if (AUDIO_ANY.equals(mRequestedType) ||
        // ANY_ANY.equals(mRequestedType)) {
        //
        // //mRequestedType = AUDIO_3GPP;
        // //fix eavoo bug 3643
        // mRequestedType = AUDIO_AMR;
        // }

        setContentView(R.layout.main);

        configSDCardPath();

        mdialog = new AlertDialog.Builder(this)
                .setNegativeButton(
                        getResources().getString(R.string.button_cancel), null)
                .setMessage(
                        getResources()
                                .getString(R.string.storage_is_not_enough))
                .setCancelable(true).create();
        mRecorder = new Recorder(this);
        mRecorder.setOnStateChangedListener(this);
        mRemainingTimeCalculator = new RemainingTimeCalculator();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "SoundRecorder");

        initResourceRefs();

        setResult(RESULT_CANCELED);
        registerExternalStorageListener();
        if (icycle != null) {
            Bundle recorderState = icycle.getBundle(RECORDER_STATE_KEY);
            if (recorderState != null) {
                mRecorder.restoreState(recorderState);
                mSampleInterrupted = recorderState.getBoolean(
                        SAMPLE_INTERRUPTED_KEY, false);
                mMaxFileSize = recorderState.getLong(MAX_FILE_SIZE_KEY, -1);
            }
        }

        updateUi();
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            int REQUEST_CODE_CONTACT = 101;
            String[] permissions = {Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
			//验证是否许可权限
            for (String str : permissions) {
                if (this.checkSelfPermission(str) != PackageManager.PERMISSION_GRANTED) {
                    this.requestPermissions(permissions, REQUEST_CODE_CONTACT);
                    return;
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onstart6 is runing");
        registerReceiver();
    }

    private void openDisableKeyGuard() {
        if (!disableKeyguarFlag) {
            keyGuardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            KeyguardLock unLock = keyGuardManager.newKeyguardLock("unlock");
            unLock.disableKeyguard();
            disableKeyguarFlag = true;
        }
    }

    private void cancleDisableKeyGuard() {
        if (keyGuardManager != null && disableKeyguarFlag) {
            keyGuardManager.exitKeyguardSecurely(null);
            disableKeyguarFlag = false;
        }
    }

    private BroadcastReceiver reminderReceiver = null;

    /*
     * Registers an intent to listen for android.intent.action.EVENT_REMINDER
     * notifications in order to preventing music record when the calendar music
     * to remind.
     */
    private void registerReceiver() {
        if (reminderReceiver == null) {
            reminderReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "intent active");
                    String action = intent.getAction();
                    if (action.equals(SoundRecorder.ACTION_EVENT_REMINDER)) {
                        Log.i(TAG, "intent" + intent);
                        if (mRecorder.state() == Recorder.RECORDING_STATE) {
                            mRecordButton.setImageResource(R.drawable.record);
                            mRecorder.pauseRecording();
                        }
                    }
                }
            };
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(SoundRecorder.ACTION_EVENT_REMINDER);
        filter.addDataScheme("content");
        registerReceiver(reminderReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setActivityState(false);
        sdCardCheck();
        if (!haveEnoughStorage()) {
            if (mdialog != null) {
                mdialog.show();
            }
        }
    }

    private void setActivityState(Boolean bool) {
        synchronized (mEmptyLock) {
            mIsPaused = bool;
        }
    }

    public boolean getActivityState() {
        return mIsPaused;
    }

    private void sdCardCheck() {
        if (!isExternalStorageMounted() || !sdCard) {
            mErrorUiMessage = getResources().getString(R.string.insert_sd_card);
        } else {
            mErrorUiMessage = null;
        }
        updateUi();
    }

    private boolean isInExternalSDCard(String path) {
        if (path == null) {
            return false;
        }

        return isExternalStorageMounted() && path.startsWith(EXTERNAL_SDCARD);
    }

    /**
     * * internal available space < 5% external availabel space < 50K return
     * false
     *
     * @return true is enough,or false
     */
    private boolean haveEnoughStorage() {
        boolean isEnough = true;
        boolean isExternalUsed = isInExternalSDCard(SELECTED_PATH);

        File savePath = null;
        if (isExternalUsed) {
            savePath = getExternalStorageDirectory();
        } else {
            savePath = getFilesDir();
        }

        /*
         * if
         * (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED
         * ) && sdCard) {
         */
        final StatFs stat = new StatFs(savePath.getPath());
        final long blockSize = stat.getBlockSize();
        final long totalBlocks = stat.getBlockCount();
        final long availableBlocks = stat.getAvailableBlocks();

        long mTotalSize = totalBlocks * blockSize;
        long mAvailSize = availableBlocks * blockSize;
        // Log.w(TAG, "occupied space is up to "+((mAvailSize * 100) /
        // mTotalSize));
        // isEnough = (mAvailSize * 100) / mTotalSize > 5 ? true : false;
        isEnough = mAvailSize < 50 * 1024 ? false : true;
        // }
        return isEnough;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        setContentView(R.layout.main);
        initResourceRefs();
        updateUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mRecorder.sampleLength() == 0)
            return;

        Bundle recorderState = new Bundle();

        mRecorder.saveState(recorderState);
        recorderState.putBoolean(SAMPLE_INTERRUPTED_KEY, mSampleInterrupted);
        recorderState.putLong(MAX_FILE_SIZE_KEY, mMaxFileSize);

        outState.putBundle(RECORDER_STATE_KEY, recorderState);
    }

    /*
     * Whenever the UI is re-created (due f.ex. to orientation change) we have
     * to reinitialize references to the views.
     */
    private void initResourceRefs() {
        mRecordButton = (ImageButton) findViewById(R.id.recordButton);
        mPlayButton = (ImageButton) findViewById(R.id.playButton);
        mStopButton = (ImageButton) findViewById(R.id.stopButton);
        usbSelectBtn = findViewById(R.id.usbSelectBtn);

        mRecordButton.setImageResource(R.drawable.record);

        mStateLED = (ImageView) findViewById(R.id.stateLED);
        mStateMessage1 = (TextView) findViewById(R.id.stateMessage1);
        mStateMessage2 = (TextView) findViewById(R.id.stateMessage2);
        mStateProgressBar = (ProgressBar) findViewById(R.id.stateProgressBar);
        mTimerView = (TextView) findViewById(R.id.timerView);

        mExitButtons = (LinearLayout) findViewById(R.id.exitButtons);
        mAcceptButton = (Button) findViewById(R.id.acceptButton);
        mDiscardButton = (Button) findViewById(R.id.discardButton);
        mVUMeter = (VUMeter) findViewById(R.id.uvMeter);

        mRecordButton.setOnClickListener(this);
        mPlayButton.setOnClickListener(this);
        mStopButton.setOnClickListener(this);
        mAcceptButton.setOnClickListener(this);
        mDiscardButton.setOnClickListener(this);
        usbSelectBtn.setOnClickListener(this);

        mTimerFormat = getResources().getString(R.string.timer_format);

        mVUMeter.setRecorder(mRecorder);
    }

    /*
     * Make sure we're not recording music playing in the background, ask the
     * MediaPlaybackService to pause playback.
     */
    private void stopAudioPlayback() {
        // Shamelessly copied from MediaPlaybackService.java, which
        // should be public, but isn't.
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");

        sendBroadcast(i);
    }

    private Handler hand = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "the Message is:" + msg.what);
            switch (msg.what) {
                case START_RECORDING_DIALOG_SHOW:
                    mRequestedType = (String) msg.obj;
                    Log.d(TAG, "mRequestedType is:" + mRequestedType);
                    startRecord();
                    break;
            }
        }
    };

    private void startRecord() {
        if (isRecord) {
            mRecorder.stop();
            saveSample();
            saveTost();
            isRecord = false;
        }
        // mm04 bug 2844
        Intent intent = new Intent();
        intent.setAction(ACTION_SOUNDRECORDER_PAUSE);
        sendBroadcast(intent);

        mRemainingTimeCalculator.reset();

        TelephonyManager pm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        TelephonyManager pm1 = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE
                + "1");
        if ((pm != null && pm.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK)
                || (pm1 != null && pm1.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK)) {
            // mErrorUiMessage =
            // getResources().getString(R.string.phone_message);
            Toast.makeText(getApplicationContext(), R.string.phone_message,
                    Toast.LENGTH_SHORT).show();
            finish();
        } else if (!isExternalStorageMounted() || !sdCard) {
            mSampleInterrupted = true;
            mErrorUiMessage = getResources().getString(R.string.insert_sd_card);
            updateUi();
            if (mRecorder.state() == Recorder.IDLE_STATE)
                mRecordButton.setImageResource(R.drawable.record);
        } else if (!mRemainingTimeCalculator.diskSpaceAvailable()) {
            mSampleInterrupted = true;
            mErrorUiMessage = getResources()
                    .getString(R.string.storage_is_full);
            updateUi();
        } else {
            stopAudioPlayback();
            if (AUDIO_AMR.equals(mRequestedType)) {
                mRemainingTimeCalculator.setBitRate(BITRATE_AMR);
                mRecorder.startRecording(MediaRecorder.OutputFormat.AMR_NB,
                        ".amr", this, SELECTED_PATH);

                openDisableKeyGuard();

            } else if (AUDIO_3GPP.equals(mRequestedType)) {
                mRemainingTimeCalculator.setBitRate(BITRATE_3GPP);
                mRecorder.startRecording(MediaRecorder.OutputFormat.THREE_GPP,
                        ".3gpp", this, SELECTED_PATH);

                openDisableKeyGuard();

            } else {
                throw new IllegalArgumentException(
                        "Invalid output file type requested");
            }

            if (mMaxFileSize != -1 && mMaxFileSize != 0) {
                mRemainingTimeCalculator.setFileSizeLimit(
                        mRecorder.sampleFile(), mMaxFileSize);
            }
        }
    }

    private void doRecord() {
        Log.d(TAG, "isRequestType " + isRequestType);
        if (!haveEnoughStorage()) {
            if (mdialog != null) {
                mdialog.show();
                return;
            }
        }
        if (mRecorder.state() == Recorder.IDLE_STATE) {
            if (!isExternalStorageMounted() || !sdCard) {
                return;
            }

            mRecordButton.setImageResource(R.drawable.suspended);
            if (!isRequestType) {
                String record3Gpp = getResources().getString(
                        R.string.record_3gpp);
                String recordamr = getResources()
                        .getString(R.string.record_amr);
                AlertDialog dialog = new AlertDialog.Builder(SoundRecorder.this)
                        .setOnCancelListener(
                                new DialogInterface.OnCancelListener() {

                                    public void onCancel(DialogInterface dialog) {
                                        if (mRecorder.state() == Recorder.IDLE_STATE)
                                            mRecordButton
                                                    .setImageResource(R.drawable.record);
                                    }
                                })
                        .setItems(new String[]{record3Gpp, recordamr},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog,
                                                        int which) {
                                        Log.d(TAG, "which=" + which);
                                        Message m = new Message();
                                        m.what = START_RECORDING_DIALOG_SHOW;
                                        switch (which) {
                                            case 0:
                                                m.obj = AUDIO_3GPP;
                                                break;
                                            case 1:
                                                m.obj = AUDIO_AMR;
                                            default:
                                                m.obj = AUDIO_AMR;
                                        }
                                        mRecordButton.setEnabled(false);
                                        hand.sendMessage(m);
                                    }
                                }).setTitle(R.string.select_file_type).create();
                if (!isRecord) {
                    dialog.show();
                } else {
                    startRecord();
                }
            } else {
                startRecord();
            }
            return;
        } else if (mRecorder.state() == Recorder.RECORDING_STATE) {
            mRecordButton.setImageResource(R.drawable.record);
            mRecorder.pauseRecording();

        } else if (mRecorder.state() == Recorder.SUSPENDED_STATE) {
            mRecordButton.setImageResource(R.drawable.suspended);
            mRecorder.resumeRecording();
        }
    }

    /*
     * Handle the buttons.
     */
    public void onClick(View button) {
        if (!button.isEnabled())
            return;
        switch (button.getId()) {
            case R.id.recordButton:
                doRecord();
                break;
            case R.id.playButton:
                mRecorder.startPlayback(this);
                break;
            case R.id.stopButton:
                mRecorder.stop();
                mRecordButton.setImageResource(R.drawable.record);
                isRecord = true;
                break;
            case R.id.acceptButton:
                mRecorder.stop();
                saveSample();
                isRecord = false;
                if (fromMMS) {
                    finish();
                    break;
                } else
                    this.saveTost();
                // finish();
                break;
            case R.id.discardButton:
                mRecorder.delete();
                noSaveTost();
                isRecord = false;
                // finish();
                break;

            case R.id.usbSelectBtn:
                startActivity(new Intent(SoundRecorder.this, USBSelectActivity.class));
                break;
        }
    }

    // fix 926 Bake key save by repeated application error
    private void saveTost() {
        if (mRecorder.sampleLength() != 0) {
            Toast.makeText(getApplicationContext(), R.string.recording_save,
                    Toast.LENGTH_SHORT).show();
        }
        mRecorder.mSampleFile = null;
        mRecorder.mSampleLength = 0;
        updateUi();
    }

    private void noSaveTost() {
        Toast.makeText(getApplicationContext(), R.string.recording_nosave,
                Toast.LENGTH_SHORT).show();

        mRecorder.mSampleFile = null;
        mRecorder.mSampleLength = 0;
        updateUi();
    }

    /*
     * Handle the "back" hardware key.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            switch (mRecorder.state()) {
                case Recorder.IDLE_STATE:
                    if (fromMMS && mRecorder.sampleLength() > 0) {
                        mRecorder.clear();
                        break;
                    }
                    if (mRecorder.sampleLength() > 0) {
                        saveSample();
                        this.saveTost();
                        // mRecorder.clear();
                        // noSaveTost();
                    } else {
                        finish();
                    }
                    break;
                case Recorder.PLAYING_STATE:
                    mRecorder.stop();
                    // noSaveTost();
                    saveSample();
                    this.saveTost();
                    break;
                case Recorder.RECORDING_STATE:
                case Recorder.SUSPENDED_STATE:
                    // mRecorder.clear();
                    // noSaveTost();
                    mRecorder.stop();
                    if (mRecorder.state() == Recorder.IDLE_STATE)
                        mRecordButton.setImageResource(R.drawable.record);
                    break;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            doRecord();
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onStop() {

        synchronized (mEmptyLock) {
            setActivityState(true);
            mSampleInterrupted = mRecorder.state() == Recorder.RECORDING_STATE;
            if (mRecorder.state() == Recorder.RECORDING_STATE) {
                mRecorder.stop();
                mRecordButton.setImageResource(R.drawable.record);
            }
        }

        mRecorder.stop();
        Log.i(TAG, "SoundRecorder  --> stop is called");
        super.onStop();
        if (reminderReceiver != null) {
            unregisterReceiver(reminderReceiver);
        }

        cancleDisableKeyGuard();
    }

    @Override
    protected void onPause() {
        sdCard = true;
        if ((mRecorder.state() == Recorder.PLAYING_STATE)
                || (mRecorder.state() == Recorder.RECORDING_STATE)) {
            mRecorder.stop();
            mRecordButton.setImageResource(R.drawable.record);
        }
        super.onPause();
    }

    /*
     * If we have just recorded a smaple, this adds it to the media data base
     * and sets the result to the sample's URI.
     */
    private void saveSample() {
        if (mRecorder.sampleLength() == 0)
            return;
        Uri uri = null;
        try {
            uri = this.addToMediaDB(mRecorder.sampleFile());
        } catch (UnsupportedOperationException ex) { // Database manipulation
            // failure
            return;
        }
        if (uri == null) {
            return;
        }
        setResult(RESULT_OK, new Intent().setData(uri));
    }

    /*
     * Called on destroy to unregister the SD card mount event receiver.
     */
    @Override
    public void onDestroy() {
        if (mSDCardMountEventReceiver != null) {
            unregisterReceiver(mSDCardMountEventReceiver);
            mSDCardMountEventReceiver = null;
        }
        if (mdialog != null) {
            mdialog.dismiss();
        }
        super.onDestroy();
    }

    /*
     * Registers an intent to listen for ACTION_MEDIA_EJECT/ACTION_MEDIA_MOUNTED
     * notifications.
     */
    private void registerExternalStorageListener() {
        if (mSDCardMountEventReceiver == null) {
            mSDCardMountEventReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                        sdCard = false;
                        mRecorder.delete();
                        mRecordButton.setImageResource(R.drawable.record);
                    } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                        mSampleInterrupted = false;
                        sdCard = true;
                        mErrorUiMessage = null;
                        updateUi();
                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_MEDIA_EJECT);
            iFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            iFilter.addDataScheme("file");
            registerReceiver(mSDCardMountEventReceiver, iFilter);
        }
    }

    /*
     * A simple utility to do a query into the databases.
     */
    private Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        try {
            ContentResolver resolver = getContentResolver();
            if (resolver == null) {
                return null;
            }
            return resolver.query(uri, projection, selection, selectionArgs,
                    sortOrder);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    /*
     * Add the given audioId to the playlist with the given playlistId; and
     * maintain the play_order in the playlist.
     */
    private void addToPlaylist(ContentResolver resolver, int audioId,
                               long playlistId) {
        String[] cols = new String[]{"count(*)"};
        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                playlistId);
        Cursor cur = resolver.query(uri, cols, null, null, null);
        cur.moveToFirst();
        final int base = cur.getInt(0);
        cur.close();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                Integer.valueOf(base + audioId));
        values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
        resolver.insert(uri, values);
    }

    /*
     * Obtain the id for the default play list from the audio_playlists table.
     */
    private int getPlaylistId(Resources res) {
        Uri uri = MediaStore.Audio.Playlists.getContentUri("external");
        final String[] ids = new String[]{MediaStore.Audio.Playlists._ID};
        final String where = MediaStore.Audio.Playlists.NAME + "=?";
        final String[] args = new String[]{res
                .getString(R.string.audio_db_playlist_name)};
        Cursor cursor = query(uri, ids, where, args, null);
        if (cursor == null) {
            Log.v(TAG, "query returns null");
        }
        int id = -1;
        if (cursor != null) {
            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                id = cursor.getInt(0);
            }
        }
        cursor.close();
        return id;
    }

    /*
     * Create a playlist with the given default playlist name, if no such
     * playlist exists.
     */
    private Uri createPlaylist(Resources res, ContentResolver resolver) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Playlists.NAME,
                res.getString(R.string.audio_db_playlist_name));
        Uri uri = resolver.insert(
                MediaStore.Audio.Playlists.getContentUri("external"), cv);
        if (uri == null) {
            new AlertDialog.Builder(this).setTitle(R.string.app_name)
                    .setMessage(R.string.error_mediadb_new_record)
                    .setPositiveButton(R.string.button_ok, null)
                    .setCancelable(false).show();
        }
        return uri;
    }

    /*
     * Adds file and returns content uri.
     */
    private Uri addToMediaDB(File file) {
        if (file == null)
            return null;

        Resources res = getResources();
        ContentValues cv = new ContentValues();
        long current = System.currentTimeMillis();
        long modDate = file.lastModified();
        Date date = new Date(current);
        SimpleDateFormat formatter = new SimpleDateFormat(
                res.getString(R.string.audio_db_title_format));
        String title = formatter.format(date);
        long sampleLengthMillis = mRecorder.sampleLength() * 1000L;

        // Lets label the recorded audio file as NON-MUSIC so that the file
        // won't be displayed automatically, except for in the playlist.
        cv.put(MediaStore.Audio.Media.IS_MUSIC, "1");

        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, file.getAbsolutePath());
        cv.put(MediaStore.Audio.Media.DATE_ADDED, (int) (current / 1000));
        cv.put(MediaStore.Audio.Media.DATE_MODIFIED, (int) (modDate / 1000));
        cv.put(MediaStore.Audio.Media.DURATION, sampleLengthMillis);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, mRequestedType);
        cv.put(MediaStore.Audio.Media.ARTIST,
                res.getString(R.string.audio_db_artist_name));
        cv.put(MediaStore.Audio.Media.ALBUM,
                res.getString(R.string.audio_db_album_name));
        Log.d(TAG, "Inserting audio record: " + cv.toString());
        ContentResolver resolver = getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Log.d(TAG, "ContentURI: " + base);
        Uri result = resolver.insert(base, cv);
        if (result == null) {
            new AlertDialog.Builder(this).setTitle(R.string.app_name)
                    .setMessage(R.string.error_mediadb_new_record)
                    .setPositiveButton(R.string.button_ok, null)
                    .setCancelable(false).show();
            return null;
        }
        if (getPlaylistId(res) == -1) {
            createPlaylist(res, resolver);
        }
        int audioId = Integer.valueOf(result.getLastPathSegment());
        addToPlaylist(resolver, audioId, getPlaylistId(res));

        // Notify those applications such as Music listening to the
        // scanner events that a recorded audio file just created.
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result));
        return result;
    }

    /**
     * Update the big MM:SS timer. If we are in playback, also update the
     * progress bar.
     */
    private void updateTimerView() {
        // Resources res = getResources();
        int state = mRecorder.state();

        boolean ongoing = state == Recorder.RECORDING_STATE
                || state == Recorder.PLAYING_STATE
                || state == Recorder.SUSPENDED_STATE;

        long time = ongoing ? mRecorder.progress() : mRecorder.sampleLength();
        if (mRecorder.progress() > mRecorder.sampleLength()
                && state == Recorder.PLAYING_STATE)
            time = mRecorder.sampleLength();
        String timeStr = String.format(mTimerFormat, time / 60, time % 60);
        mTimerView.setText(timeStr);

        if (state == Recorder.PLAYING_STATE) {
            if (mRecorder.sampleLength() != 0) {
                mStateProgressBar.setProgress((int) (100 * time / mRecorder
                        .sampleLength()));
            } else {
                mStateProgressBar.setProgress(mStateProgressBar.getMax());
            }
        } else if (state == Recorder.RECORDING_STATE) {
            updateTimeRemaining();
        }

        if (ongoing)
            mHandler.postDelayed(mUpdateTimer, 200);
    }

    /*
     * Called when we're in recording state. Find out how much longer we can go
     * on recording. If it's under 5 minutes, we display a count-down in the UI.
     * If we've run out of time, stop the recording.
     */
    private void updateTimeRemaining() {
        long t = mRemainingTimeCalculator.timeRemaining();
        // t = 5;
        if (t <= 0) {
            mSampleInterrupted = true;

            int limit = mRemainingTimeCalculator.currentLowerLimit();
            switch (limit) {
                case RemainingTimeCalculator.DISK_SPACE_LIMIT:
                    mErrorUiMessage = getResources().getString(
                            R.string.storage_is_full);
                    break;
                case RemainingTimeCalculator.FILE_SIZE_LIMIT:
                    mErrorUiMessage = getResources().getString(
                            R.string.max_length_reached);
                    break;
                default:
                    mErrorUiMessage = null;
                    break;
            }

            mRecorder.stop();
            return;
        }

        Resources res = getResources();
        String timeStr = "";

        if (t < 60)
            timeStr = String.format(res.getString(R.string.sec_available), t);
        else if (t < 540)
            timeStr = String.format(res.getString(R.string.min_available),
                    t / 60 + 1);

        mStateMessage1.setText(timeStr);
    }

    /**
     * Shows/hides the appropriate child views for the new state.
     */
    private void updateUi() {
        Resources res = getResources();

        switch (mRecorder.state()) {
            case Recorder.IDLE_STATE:
                if (mRecorder.sampleLength() == 0) {
                    mRecordButton.setEnabled(true);
                    mRecordButton.setFocusable(true);
                    mPlayButton.setEnabled(false);
                    mPlayButton.setFocusable(false);
                    mStopButton.setEnabled(false);
                    mStopButton.setFocusable(false);
                    mRecordButton.requestFocus();

                    mStateMessage1.setVisibility(View.INVISIBLE);
                    mStateLED.setVisibility(View.INVISIBLE);
                    mStateLED.setImageResource(R.drawable.idle_led);
                    mStateMessage2.setVisibility(View.INVISIBLE);
                    mStateMessage2.setText(res.getString(R.string.press_record));

                    mExitButtons.setVisibility(View.INVISIBLE);
                    mVUMeter.setVisibility(View.VISIBLE);

                    mStateProgressBar.setVisibility(View.INVISIBLE);

                    setTitle(res.getString(R.string.record_your_message));
                } else {
                    mRecordButton.setEnabled(true);
                    mRecordButton.setFocusable(true);
                    mPlayButton.setEnabled(true);
                    mPlayButton.setFocusable(true);
                    mStopButton.setEnabled(false);
                    mStopButton.setFocusable(false);

                    mStateMessage1.setVisibility(View.INVISIBLE);
                    mStateLED.setVisibility(View.INVISIBLE);
                    mStateMessage2.setVisibility(View.INVISIBLE);

                    mExitButtons.setVisibility(View.VISIBLE);
                    mVUMeter.setVisibility(View.INVISIBLE);

                    mStateProgressBar.setVisibility(View.INVISIBLE);

                    setTitle(res.getString(R.string.message_recorded));
                }
                mRecordButton.setImageResource(R.drawable.record);
                // mm04 fix bug 3448

                // if (mSampleInterrupted) {
                // mStateMessage2.setVisibility(View.VISIBLE);
                // mStateMessage2.setText(res.getString(R.string.recording_stopped));
                // mStateLED.setImageResource(R.drawable.idle_led);
                // mStateLED.setVisibility(View.VISIBLE);
                // }

                if (mErrorUiMessage != null) {
                    mStateMessage1.setText(mErrorUiMessage);
                    mStateMessage1.setVisibility(View.VISIBLE);
                }

                break;
            case Recorder.RECORDING_STATE:
                mRecordButton.setEnabled(true);
                mRecordButton.setFocusable(true);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.VISIBLE);
                mStateLED.setVisibility(View.VISIBLE);
                mStateLED.setImageResource(R.drawable.recording_led);
                mStateMessage2.setVisibility(View.VISIBLE);
                mStateMessage2.setText(res.getString(R.string.recording));

                mExitButtons.setVisibility(View.INVISIBLE);
                mVUMeter.setVisibility(View.VISIBLE);

                mStateProgressBar.setVisibility(View.INVISIBLE);

                setTitle(res.getString(R.string.record_your_message));

                break;

            case Recorder.PLAYING_STATE:
                mRecordButton.setEnabled(false);
                mRecordButton.setFocusable(false);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.INVISIBLE);
                mStateLED.setVisibility(View.INVISIBLE);
                mStateMessage2.setVisibility(View.INVISIBLE);

                mExitButtons.setVisibility(View.VISIBLE);
                mVUMeter.setVisibility(View.INVISIBLE);

                mStateProgressBar.setVisibility(View.VISIBLE);

                setTitle(res.getString(R.string.review_message));

                break;
            case Recorder.SUSPENDED_STATE:
                mRecordButton.setEnabled(true);
                mRecordButton.setFocusable(false);
                mPlayButton.setEnabled(false);
                mPlayButton.setFocusable(false);
                mStopButton.setEnabled(true);
                mStopButton.setFocusable(true);

                mStateMessage1.setVisibility(View.VISIBLE);
                mStateLED.setVisibility(View.VISIBLE);
                mStateLED.setImageResource(R.drawable.idle_led);
                mStateMessage2.setVisibility(View.VISIBLE);
                mStateMessage2.setText(res.getString(R.string.recording));

                mExitButtons.setVisibility(View.INVISIBLE);
                mVUMeter.setVisibility(View.VISIBLE);

                mStateProgressBar.setVisibility(View.INVISIBLE);

                setTitle(res.getString(R.string.record_your_message));

                break;
        }

        updateTimerView();
        mVUMeter.invalidate();
    }

    /*
     * Called when Recorder changed it's state.
     */
    public void onStateChanged(int state) {
        if (state == Recorder.SUSPENDED_STATE || state == Recorder.IDLE_STATE) {
            mRemainingTimeCalculator.reset();
        }

        synchronized (mEmptyLock) {
            if (mIsPaused) {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
                if (state == Recorder.RECORDING_STATE) {
                    mRecorder.setState(Recorder.IDLE_STATE);
                }
            } else {
                if (state == Recorder.PLAYING_STATE
                        || state == Recorder.RECORDING_STATE) {
                    mSampleInterrupted = false;
                    mErrorUiMessage = null;
                    mWakeLock.acquire(); // we don't want to go to sleep while
                    // recording or playing
                } else {
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                        cancleDisableKeyGuard();
                    }
                }
            }
            updateUi();
        }
    }

    /*
     * Called when MediaPlayer encounters an error.
     */
    public void onError(int error) {
        Resources res = getResources();

        String message = null;
        switch (error) {
            case Recorder.SDCARD_ACCESS_ERROR:
                message = res.getString(R.string.error_sdcard_access);
                break;
            case Recorder.IN_CALL_RECORD_ERROR:
                // TODO: update error message to reflect that the recording could
                // not be
                // performed during a call.
            case Recorder.INTERNAL_ERROR:
                message = res.getString(R.string.error_app_internal);
                break;
        }
        if (message != null) {
            new AlertDialog.Builder(this).setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(R.string.button_ok, null)
                    .setCancelable(false).show();
        }
    }

    // add by lish 12.02.15
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (PATHSELECT_RESULT_CODE == requestCode) {
            Bundle bundle = null;
            if (data != null && (bundle = data.getExtras()) != null) {
                SELECTED_PATH = bundle.getString("file");
                mRemainingTimeCalculator
                        .chooseSDCard(isInExternalSDCard(SELECTED_PATH));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent = null;
        switch (item.getItemId()) {
            case R.id.menu_recording_file_list:
                intent = new Intent(SoundRecorder.this, RecordingFileList.class);
                break;
            case R.id.menu_set_save_path:
                intent = new Intent(SoundRecorder.this, PathSelect.class);
                break;
            default:
                break;
        }
        if (intent != null) {
            startActivityForResult(intent, PATHSELECT_RESULT_CODE);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
