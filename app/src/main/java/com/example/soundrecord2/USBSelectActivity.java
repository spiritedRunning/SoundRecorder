package com.example.soundrecord2;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;

import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import com.xqd.sdk.audio.UsbAudioManager;
import com.xqd.sdk.audio.UsbAudioListenter;
import com.xqd.sdk.audio.UsbAudioDevice;

import java.util.ArrayList;
import java.util.List;


public class USBSelectActivity extends Activity  implements AdapterView.OnItemClickListener{
    private static final String TAG = "USBSelectActivity";
    private  static final int MSG_DEVICE_LIST_CHANGED = 1;
    private  static final int MSG_DEVICE_STATE_CHANGED = 2;

    private  static final int USB_AUDIO_DEVICE_ACTION_ADDED = 0;
    private  static final int USB_AUDIO_DEVICE_ACTION_REMOVED = 1;

    private static final int DEVICE_PLAYBACK = 1;
    private static final int DEVICE_CAPTURE = 2;

    private UsbAudioManager mUsbAudioManager;

    private ListView playbackListView;
    private ListView captureListView;
    private List<UsbAudioDevice> mPlaybackDeviceList;
    private List<UsbAudioDevice> mCaptureDeviceList;
    private MyAdapter mPlaybackAdapter;
    private MyAdapter mCaptureAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mPlaybackDeviceList = new ArrayList<UsbAudioDevice>();
        mCaptureDeviceList  = new ArrayList<UsbAudioDevice>();
        playbackListView =  findViewById(R.id.playbackListView);
        captureListView = findViewById(R.id.captureListView);
        mPlaybackAdapter =new MyAdapter(this, mPlaybackDeviceList);
        mCaptureAdapter = new MyAdapter(this, mCaptureDeviceList);
        playbackListView.setAdapter(mPlaybackAdapter);
        playbackListView.setOnItemClickListener(this);
        captureListView.setAdapter(mCaptureAdapter);
        captureListView.setOnItemClickListener(this);
        mUsbAudioManager = new UsbAudioManager(getApplicationContext(), mUsbAudioListenter);
    }
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

        switch(parent.getId()) {
            case R.id.captureListView:
                Log.d(TAG, "onItemClick from capture ListView");
                handleDeviceStateChanged(DEVICE_CAPTURE, position);
                break;
            case R.id.playbackListView:
                Log.d(TAG, "onItemClick from playback ListView");
                handleDeviceStateChanged(DEVICE_PLAYBACK, position);
                break;
            default:
                break;

        }
        /*UsbAudioDevice device = mPlaybackDeviceList.get(position);
        boolean state = mUsbAudioManager.deviceInUse(device);
        Log.d(TAG, "usb in use =======> "  + state);

        mUsbAudioManager.setDeviceState(device, !state);
        mHandle.sendEmptyMessage(MSG_DEVICE_LIST_CHANGED);*/
    }

    private void notifyDeviceStateChange(int deviceType) {
        Message msg = new Message();
        msg.what = MSG_DEVICE_LIST_CHANGED;
        msg.arg1 = deviceType;
        mHandle.sendMessage(msg);
    }

    private void notifyDeviceStateChange(UsbAudioDevice device, int state) {
        Message msg = new Message();
        msg.what = MSG_DEVICE_STATE_CHANGED;
        msg.arg1 = state;
        msg.obj = device;
        mHandle.sendMessage(msg);
    }

    private void handleDeviceStateChanged(int deviceType, int position) {
        UsbAudioDevice device;
        if (deviceType ==DEVICE_PLAYBACK) {
            device = mPlaybackDeviceList.get(position);
        } else if (deviceType ==DEVICE_CAPTURE) {
            device = mCaptureDeviceList.get(position);
        } else {
            return;
        }
        boolean state = mUsbAudioManager.deviceInUse(device);
        Log.d(TAG, "usb in use =======> "  + state);

        mUsbAudioManager.setDeviceState(device, !state);
        notifyDeviceStateChange(deviceType);
    }

    private Handler mHandle = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == MSG_DEVICE_LIST_CHANGED) {
                Log.d(TAG, "MSG_DEVICE_LIST_CHANGED====>");
                if (msg.arg1 == DEVICE_PLAYBACK) {
                    mPlaybackAdapter.notifyDataSetChanged();
                } else if(msg.arg1 == DEVICE_CAPTURE ){
                    mCaptureAdapter.notifyDataSetChanged();
                }
            } else if (msg.what == MSG_DEVICE_STATE_CHANGED) {
                Log.d(TAG, "MSG_DEVICE_STATE_CHANGED====>");
                UsbAudioDevice device = (UsbAudioDevice)msg.obj;
                int state = msg.arg1;
                if (device.mType == DEVICE_PLAYBACK) {
                    if (state == USB_AUDIO_DEVICE_ACTION_ADDED) {
                        synchronized(mPlaybackDeviceList) {
                            mPlaybackDeviceList.add(device);
                            mPlaybackAdapter.notifyDataSetChanged();
                        }
                    } else if (state == USB_AUDIO_DEVICE_ACTION_REMOVED) {
                        synchronized(mPlaybackDeviceList) {
                            mPlaybackDeviceList.remove(device);
                            mPlaybackAdapter.notifyDataSetChanged();
                        }
                    }

                } else if(device.mType == DEVICE_CAPTURE ){
                    if (state == USB_AUDIO_DEVICE_ACTION_ADDED) {
                        synchronized(mCaptureDeviceList) {
                            mCaptureDeviceList.add(device);
                            mCaptureAdapter.notifyDataSetChanged();
                        }
                    } else if (state == USB_AUDIO_DEVICE_ACTION_REMOVED) {
                        synchronized(mCaptureDeviceList) {
                            mCaptureDeviceList.remove(device);
                            mCaptureAdapter.notifyDataSetChanged();
                        }
                    }
                }
            }
        }
    };

    private UsbAudioListenter mUsbAudioListenter = new UsbAudioListenter() {

        @Override
        public void onPlaybackDeviceAdded(UsbAudioDevice device) {
            //mPlaybackDeviceList.add(device);
            notifyDeviceStateChange(device, USB_AUDIO_DEVICE_ACTION_ADDED);
            //mHandle.sendEmptyMessage(MSG_DEVICE_LIST_CHANGED);
            Log.d(TAG, "onPlaybackDeviceAdded: " + device.toString());
        }

        @Override
        public void onPlaybackDeviceRemoved(UsbAudioDevice device){
            //mPlaybackDeviceList.remove(device);
            notifyDeviceStateChange(device, USB_AUDIO_DEVICE_ACTION_REMOVED);
            //mHandle.sendEmptyMessage(MSG_DEVICE_LIST_CHANGED);
            Log.d(TAG, "onPlaybackDeviceRemoved: "  + device.toString());
        }

        @Override
        public void onCaptureDeviceAdded(UsbAudioDevice device) {
            //mCaptureDeviceList.add(device);
            notifyDeviceStateChange(device, USB_AUDIO_DEVICE_ACTION_ADDED);
            Log.d(TAG, "onCaptureDeviceAdded: "  + device.toString());
        }

        @Override
        public void onCaptureDeviceRemoved(UsbAudioDevice device) {
            //mCaptureDeviceList.remove(device);
            notifyDeviceStateChange(device, USB_AUDIO_DEVICE_ACTION_REMOVED);
            Log.d(TAG, "onCaptureDeviceRemoved: " + device.toString());
        }
    };

    public class MyAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        private List<UsbAudioDevice> mDatas;

        public MyAdapter(Context context, List<UsbAudioDevice> datas) {

            mInflater = LayoutInflater.from(context);
            mDatas = datas;
        }


        @Override
        public int getCount() {
            return mDatas.size();
        }

        @Override
        public Object getItem(int position) {
            return mDatas.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder = null;
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.item_listview2, parent, false);
                holder = new ViewHolder();

                holder.cardIndexTv = (TextView) convertView.findViewById(R.id.cardIndexTv);
                holder.cardTypeTv = (TextView) convertView.findViewById(R.id.cardTypeTv);
                holder.cardNameTv = (TextView) convertView.findViewById(R.id.cardNameTv);
                holder.radioButton = (RadioButton) convertView.findViewById(R.id.radioButton);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            UsbAudioDevice device = mDatas.get(position);
            String type;
            if (device.mType == 1) {
                type = getResources().getString(R.string.device_type_playback);
            } else if (device.mType == 2) {
                type = getResources().getString(R.string.device_type_capture);
            } else {
                type = getResources().getString(R.string.device_type_unknown);
            }
            holder.cardIndexTv.setText("Card ID:"+String.valueOf(device.mCard));
            holder.cardTypeTv.setText(type);
            holder.cardNameTv.setText(device.mCardName);
            holder.radioButton.setChecked(device.isInUse());


            return convertView;
        }

        private class ViewHolder {
            TextView cardIndexTv;
            TextView cardTypeTv;
            TextView cardNameTv;
            RadioButton radioButton;
        }

    }
}
