package com.example.soundrecord2;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class RecordingFileList extends ListActivity implements
		AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener {

	private static final String TAG = "RecordingFileList";

	private ListView mListView;
	private CursorRecorderAdapter mAdapter;
	private Map<Integer, Boolean> checkboxes = new HashMap<Integer, Boolean>();
	private Map<Integer, RecorderItem> checkItem = new TreeMap<Integer, RecorderItem>();
	private List<RecorderItem> items = new ArrayList<RecorderItem>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.recording_file_list);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mListView = getListView();
		mAdapter = new CursorRecorderAdapter();
		setListAdapter(mAdapter);

		mListView.setOnItemClickListener(this);
		mListView.setOnItemLongClickListener(this);
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> adapter, View v, int pos,
			long id) {
		boolean result = false;
		RecorderItem item = mAdapter.findItem(pos);
		if (item != null) {
			RecorderItemClick click = new RecorderItemClick(
					RecorderItemClick.LONG_CLICK, item);
			click.show();
		}
		return result;
	}

	private void checkboxOnclick(int pos) {
		Boolean result = checkboxes.get(pos);
		if (result == null || result == false) {
			checkboxes.put(pos, true);
			RecorderItem item = mAdapter.findItem(pos);
			checkItem.put(pos, item);
		} else {
			checkboxes.put(pos, false);
			checkItem.remove(pos);
		}
	}

	private void invalidateCheckbox(CheckBox box, int pos) {
		Boolean result = checkboxes.get(pos);
		if (result == null || result == false) {
			box.setChecked(false);
		} else {
			box.setChecked(true);
		}
	}

	private static final int SELECT_ALL = 0;
	private static final int DELETE = 1;
	private static final int UN_SELECT_ALL = 2;

	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, SELECT_ALL, 0,
				getString(R.string.menu_recording_list_select_all));
		menu.add(0, DELETE, 1, getString(R.string.menu_recording_list_delete));
		menu.add(0, UN_SELECT_ALL, 2,
				getString(R.string.menu_recording_list_deselect_all));
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case SELECT_ALL:
			selectAll();
			break;
		case DELETE:
			if (checkItem != null && checkItem.size() == 0) {
				break;
			}
			showDelDialog();
			break;
		case UN_SELECT_ALL:
			unSelectAll();
			break;
		}
		return true;
	}

	private void unSelectAll() {
		clearContainer();
		mListView.invalidateViews();
	}

	private void selectAll() {
		if (items != null) {
			items.clear();
		}
		if (checkItem != null) {
			checkItem.clear();
		}
		getAllDatas();
		Integer index = 0;
		for (RecorderItem item : items) {
			checkboxes.put(index, true);
			checkItem.put(index, item);
			index++;
		}
		mListView.invalidateViews();
	}

	private void clearContainer() {
		if (items != null) {
			items.clear();
		}
		if (checkboxes != null) {
			checkboxes.clear();
		}
		if (checkItem != null) {
			checkItem.clear();
		}
	}

	private void deleteFinish() {
		clearContainer();
		mAdapter.notifyDataSetChanged();
		Toast.makeText(RecordingFileList.this,
				R.string.recording_file_delete_success, Toast.LENGTH_SHORT)
				.show();
	}

	private String[] cloum = new String[] { "_id,_data" };

	private void getAllDatas() {
		ContentResolver cr = getContentResolver();
		StringBuffer buff = new StringBuffer();
		buff.append("(").append(MediaStore.Audio.Media.MIME_TYPE)
				.append("='audio/amr' or ")
				.append(MediaStore.Audio.Media.MIME_TYPE)
				.append("='audio/3gpp') and ")
				.append(MediaStore.Audio.Media.DISPLAY_NAME)
				.append(" like 'recording%' or ")
				.append(MediaStore.Audio.Media.DISPLAY_NAME)
				.append(" like '.recording%'");
		Cursor cursor = cr.query(
				MediaStore.Audio.Media.getContentUri("external"), cloum,
				buff.toString(), null, null);
		try {
			while (cursor != null && cursor.moveToNext()) {
				items.add(new RecorderItem(cursor.getLong(0), cursor
						.getString(1), null));
			}
		} catch (Exception e) {
			Log.e(TAG, "getAllDatas e=" + e.toString());
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
	}

	private AlertDialog mDelDlg = null;

	private void showDelDialog() {
		if (mDelDlg == null) {
			mDelDlg = new AlertDialog.Builder(this)
					.setTitle(getString(android.R.string.dialog_alert_title))
					.setIcon(android.R.drawable.ic_dialog_alert)
					.setMessage(getString(R.string.confirm_del))
					.setPositiveButton(android.R.string.ok,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int which) {
									for (Map.Entry<Integer, RecorderItem> entry : checkItem
											.entrySet()) {
										deleteFile(entry.getValue());
									}
									deleteFinish();
									dismissDelDialog();
								}
							})
					.setNegativeButton(android.R.string.cancel,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int which) {
									dismissDelDialog();
								}
							}).create();
			mDelDlg.show();
		}
	}

	private void dismissDelDialog() {
		if (mDelDlg != null) {
			mDelDlg.dismiss();
			mDelDlg.cancel();
			mDelDlg = null;
		}
	}

	private void deleteFile(RecorderItem item) {
		getContentResolver().delete(
				ContentUris.withAppendedId(
						MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, item.id),
				null, null);
		File del = new File(item.data);
		if (!del.exists() || !del.delete()) {
			return;
		}
		mAdapter.deleteById(item.id);
	}

	@Override
	public void onItemClick(AdapterView<?> adapter, View v, int pos, long id) {
		RecorderItem item = mAdapter.findItem(pos);
		if (item != null) {
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setDataAndType(ContentUris.withAppendedId(
					MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
					item.mimeType);
			startActivity(intent);
		}
	}

	private class RecorderItemClick implements DialogInterface.OnClickListener {

		private static final int LONG_CLICK = 1;
		private static final int SHORT_CLICK = 2;

		private final int event;
		private final RecorderItem item;

		private RecorderItemClick(int event, RecorderItem item) {
			this.item = item;
			this.event = event;
		}

		@Override
		public void onClick(DialogInterface dialog, int which) {
			if (SHORT_CLICK == -which) {
				dialog.dismiss();
				return;
			}

			int row = -1;
			StringBuffer buff = new StringBuffer();
			buff.append(MediaStore.Audio.Media._ID).append("=").append(item.id);
			int toast_msg = -1;
			try {
				// delete database row
				row = RecordingFileList.this.getContentResolver().delete(
						MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
						buff.toString(), null);
				// validate database process
				if (row == -1) {
					toast_msg = R.string.recording_file_database_failed;
					return;
				}
				// validate file process
				File del = new File(item.data);
				if (!del.exists() || !del.delete()) {
					toast_msg = R.string.recording_file_delete_failed;
					return;
				}

				toast_msg = R.string.recording_file_delete_success;
			} catch (Exception e) {
				if (row == -1)
					toast_msg = R.string.recording_file_database_failed;
				Log.d(TAG, "execute delete recorder item failed; E: "
						+ (e != null ? e.getMessage() : "NULL"));
			} finally {
				mAdapter.deleteItem((row != -1 ? item : null));
				Toast.makeText(RecordingFileList.this, toast_msg,
						Toast.LENGTH_SHORT).show();
			}
		}

		void show() {
			if (event == 0 || item == null)
				throw new RuntimeException(
						"RecorderItemClick failed; event == " + event
								+ " --- item == " + item);

			new AlertDialog.Builder(RecordingFileList.this)
					.setTitle(R.string.recording_file_delete_alert_title)
					.setMessage(item.getAlertMessage())
					.setPositiveButton(R.string.button_delete, this)
					.setNegativeButton(R.string.button_cancel, this).show();
		}
	}

	private class CursorRecorderAdapter extends BaseAdapter {

		private final int INIT_SIZE = 10;
		private List<RecorderItem> mData = null;

		CursorRecorderAdapter() {
			super();
			mData = query();
		}

		@Override
		public int getCount() {
			return mData.size();
		}

		@Override
		public Object getItem(int pos) {
			return mData.get(pos);
		}

		@Override
		public long getItemId(int pos) {
			long result = -1L;
			RecorderItem item = findItem(pos);
			if (item != null)
				result = item.id;
			return result;
		}

		@Override
		public View getView(int pos, View cvt, ViewGroup pat) {
			if (cvt == null) {
				LayoutInflater flater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				cvt = flater.inflate(R.layout.recording_file_item, null);
				if (cvt == null)
					throw new RuntimeException(
							"inflater \"record_item.xml\" failed; pos == "
									+ pos);
			}

			RecorderItem item = findItem(pos);
			if (item == null)
				throw new RuntimeException("findItem() failed; pos == " + pos);

			TextView tv = null;
			// get "record_title"
			// tv = (TextView) cvt.findViewById(R.id.record_title);
			// tv.setText(item.title);
			// get "record_displayname"
			tv = (TextView) cvt.findViewById(R.id.record_displayname);
			tv.setText(item.title);
			// get "record_size"
			tv = (TextView) cvt.findViewById(R.id.record_size);
			tv.setText(item.getSize());
			// get "record_time"
			tv = (TextView) cvt.findViewById(R.id.record_time);
			tv.setText(item.getTime());
			CheckBox cb = (CheckBox) cvt.findViewById(R.id.recode_checkbox);
			cb.setTag(pos);
			cb.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					checkboxOnclick(Integer.parseInt(v.getTag().toString()));
				}
			});
			invalidateCheckbox(cb, pos);
			return cvt;
		}

		private ArrayList<RecorderItem> query() {
			ArrayList<RecorderItem> result = new ArrayList<RecorderItem>(
					INIT_SIZE);
			Cursor cur = null;
			try {
				StringBuffer buff = new StringBuffer();
				buff.append("(").append(MediaStore.Audio.Media.MIME_TYPE)
						.append("='audio/amr' or ")
						.append(MediaStore.Audio.Media.MIME_TYPE)
						.append("='audio/3gpp') and ")
						.append(MediaStore.Audio.Media.DISPLAY_NAME)
						.append(" like 'recording%' or ")
						.append(MediaStore.Audio.Media.DISPLAY_NAME)
						.append(" like '.recording%'");
				cur = RecordingFileList.this.getContentResolver()
						.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
								new String[] { RecorderItem._ID,
										RecorderItem._DATA, RecorderItem.SIZE,
										RecorderItem.TITLE,
										RecorderItem.DISPLAY_NAME,
										RecorderItem.MOD_DATE,
										RecorderItem.MIME_TYPE },
								buff.toString(), null, null);

				// read cursor
				int index = -1;
				for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
					index = cur.getColumnIndex(RecorderItem._ID);
					// create recorder object
					long id = cur.getLong(index);
					RecorderItem item = new RecorderItem(id);
					// set "data" value
					index = cur.getColumnIndex(RecorderItem._DATA);
					item.data = cur.getString(index);
					// set "size" value
					index = cur.getColumnIndex(RecorderItem.SIZE);
					item.size = cur.getLong(index);
					// set "title" value
					index = cur.getColumnIndex(RecorderItem.TITLE);
					item.title = cur.getString(index);
					// SET "display name" value
					index = cur.getColumnIndex(RecorderItem.DISPLAY_NAME);
					item.display_name = cur.getString(index);
					// set "time" value
					index = cur.getColumnIndex(RecorderItem.MOD_DATE);
					item.time = cur.getLong(index);
					// set "mime-type" value
					index = cur.getColumnIndex(RecorderItem.MIME_TYPE);
					item.mimeType = cur.getString(index);
					// add to mData
					result.add(item);
				}
			} catch (Exception e) {
				Log.v(TAG,
						"RecordingFileList.CursorRecorderAdapter failed; E: "
								+ e);
			} finally {
				if (cur != null)
					cur.close();
			}
			return result;
		}

		private RecorderItem findItem(int pos) {
			RecorderItem result = null;
			Object obj = getItem(pos);
			if (obj != null && obj instanceof RecorderItem) {
				result = (RecorderItem) obj;
			}
			return result;
		}

		private boolean deleteItem(RecorderItem item) {
			boolean result = false;
			if (item != null && mData != null) {
				Iterator<RecorderItem> it = mData.iterator();
				while (it.hasNext()) {
					RecorderItem del = it.next();
					if (item.id == del.id) {
						it.remove();
						result = true;
						break;
					}
				}
			}
			if (result)
				notifyDataSetChanged();
			return result;
		}

		private void deleteById(long id) {
			if (mData != null) {
				Iterator<RecorderItem> it = mData.iterator();
				while (it.hasNext()) {
					RecorderItem del = it.next();
					if (id == del.id) {
						it.remove();
						break;
					}
				}
			}
		}
	}

	@SuppressWarnings("unused")
	private class RecorderItem {
		private final long id;
		private String data;
		private String mimeType;
		private long size;
		private String title;
		private String display_name;
		private long time;

		private static final String _ID = MediaStore.Audio.Media._ID;
		private static final String SIZE = MediaStore.Audio.Media.SIZE;
		private static final String _DATA = MediaStore.Audio.Media.DATA;
		private static final String TITLE = MediaStore.Audio.Media.TITLE;
		private static final String DISPLAY_NAME = MediaStore.Audio.Media.DISPLAY_NAME;
		private static final String MOD_DATE = MediaStore.Audio.Media.DATE_MODIFIED;
		private static final String MIME_TYPE = MediaStore.Audio.Media.MIME_TYPE;

		private static final String AUDIO_AMR = "audio/amr";
		private static final String AUDIO_3GPP = "audio/3gpp";
		private static final double NUMBER_KB = 1024D;
		private static final double NUMBER_MB = NUMBER_KB * NUMBER_KB;

		RecorderItem(long id) {
			this.id = id;
		}

		RecorderItem(long id, String data, String mimeType) {
			this(id);
			this.data = data;
			this.mimeType = mimeType;
		}

		RecorderItem(long id, String data, String mimeType, long size,
				String title) {
			this(id, data, mimeType);
			this.size = size;
			this.title = title;
		}

		public String getSize() {
			StringBuffer buff = new StringBuffer();
			if (size > 0) {
				String format = null;
				double calculate = -1D;
				if (size < NUMBER_KB) {
					format = getResources().getString(
							R.string.list_recorder_item_size_format_b);
					int calculate_b = (int) size;
					buff.append(String.format(format, calculate_b));
				} else if (size < NUMBER_MB) {
					format = getResources().getString(
							R.string.list_recorder_item_size_format_kb);
					calculate = (size / NUMBER_KB);
					DecimalFormat df = new DecimalFormat(".##");
					String st = df.format(calculate);
					buff.append(String.format(format, st));
				} else {
					format = getResources().getString(
							R.string.list_recorder_item_size_format_mb);
					calculate = (size / NUMBER_MB);
					DecimalFormat df = new DecimalFormat(".##");
					String st = df.format(calculate);
					buff.append(String.format(format, st));
				}
			}
			return buff.toString();
		}

		public String getTime() {
			StringBuffer buff = new StringBuffer();
			if (time > 0) {
				String format = getResources().getString(
						R.string.list_recorder_item_time_format);
				java.util.Date d = new java.util.Date(time * 1000);
				java.text.DateFormat formatter_date = java.text.DateFormat
						.getDateInstance();
				java.text.DateFormat formatter_time = java.text.DateFormat
						.getTimeInstance();
				buff.append(String.format(
						format,
						new Object[] { formatter_date.format(d),
								formatter_time.format(d) }));
			}
			return buff.toString();
		}

		public String getAlertMessage() {
			String msg = getResources().getString(
					R.string.recording_file_delete_alert_message);
			String result = String.format(msg,
					(display_name != null ? display_name : ""));
			return result;
		}

		@Override
		public String toString() {
			StringBuffer buff = new StringBuffer();
			buff.append("id == ").append(id).append(" --- data == ")
					.append(data).append(" --- mimeType == ").append(mimeType)
					.append(" --- size == ").append(size)
					.append(" --- title == ").append(title)
					.append(" --- display_name == ").append(display_name)
					.append(" --- time == ").append(time);
			return buff.toString();
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		// TODO Auto-generated method stub
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			finish();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

}
