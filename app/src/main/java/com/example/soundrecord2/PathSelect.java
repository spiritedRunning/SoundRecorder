package com.example.soundrecord2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class PathSelect extends ListActivity implements Button.OnClickListener {
	private List<String> items = null;
	private List<String> paths = null;
	private String sdcardPath = "/mnt";
	private String curPath = "/mnt/sdcard";
	private String INTERNAL_SDCARD = "";
	private String EXTERNAL_SDCARD = "";
	private TextView mPath;
	private static final int HANDLER_SET_LISTADAPTER = 1;
	private PathSelectAdapter mSelectAdapter = null;

	final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case HANDLER_SET_LISTADAPTER:
				mSelectAdapter = new PathSelectAdapter(PathSelect.this, items,
						paths);
				setListAdapter(mSelectAdapter);
				mSelectAdapter.notifyDataSetChanged();
				break;
			}
		}
	};

	private void configSDCardPath() {
		File sdcardDirectory = null;
		sdcardDirectory = SoundRecorder.getInternalStorageDirectory();
		if (sdcardDirectory != null) {
			INTERNAL_SDCARD = sdcardDirectory.getAbsolutePath();
		}

		sdcardDirectory = SoundRecorder.getExternalStorageDirectory();
		if (sdcardDirectory != null) {
			EXTERNAL_SDCARD = sdcardDirectory.getAbsolutePath();
		}
	}

	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		// requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.pathselect);
		configSDCardPath();
		mPath = (TextView) findViewById(R.id.mPath);
		Button buttonConfirm = (Button) findViewById(R.id.buttonConfirm);
		buttonConfirm.setOnClickListener(this);
		Button buttonCancle = (Button) findViewById(R.id.buttonCancle);
		buttonCancle.setOnClickListener(this);
		getFileDir(sdcardPath);
	}

	private void getFileDir(String filePath) {
		final String tmpFilePath = filePath;
		mPath.setText(tmpFilePath);

		// retrieve all of files under path, put it in worker thread
		new Thread() {
			public void run() {
				items = new ArrayList<String>();
				paths = new ArrayList<String>();
				File f = new File(tmpFilePath);
				File[] files = f.listFiles();
				if (!tmpFilePath.equals(sdcardPath)) {
					items.add("back");
					paths.add(f.getParent());
				}
				if (files != null) {
					for (int i = 0; i < files.length; i++) {
						File file = files[i];
						if (file.isDirectory()) {
							String path = file.getAbsolutePath();
							if (path.startsWith(EXTERNAL_SDCARD)
									|| path.startsWith(INTERNAL_SDCARD)) {
								items.add(file.getName());
								paths.add(file.getPath());
							}
						}
					}
				}

				// notify main thread to update UI
				Message msg = new Message();
				msg.what = HANDLER_SET_LISTADAPTER;
				mHandler.sendMessage(msg);
			}
		}.start();
	}

	public void onClick(View button) {
		if (!button.isEnabled())
			return;
		switch (button.getId()) {
		case R.id.buttonConfirm:
			if (!(curPath.startsWith(EXTERNAL_SDCARD) || curPath
					.startsWith(INTERNAL_SDCARD))) {
				curPath = INTERNAL_SDCARD;
				Toast.makeText(getApplicationContext(), R.string.path_default,
						Toast.LENGTH_SHORT).show();
			}
			Intent data = new Intent(PathSelect.this, SoundRecorder.class);
			Bundle bundle = new Bundle();
			bundle.putString("file", curPath);
			data.putExtras(bundle);
			setResult(2, data);
			Toast.makeText(getApplicationContext(), R.string.path_save,
					Toast.LENGTH_LONG).show();
			finish();
			break;
		case R.id.buttonCancle:
			Toast.makeText(getApplicationContext(), R.string.path_nosave,
					Toast.LENGTH_LONG).show();
			finish();
			break;
		}
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		if (position >= paths.size()) {
			return;
		}
		curPath = paths.get(position);
		getFileDir(curPath);
	}

}
