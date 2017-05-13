/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

public class AssistantActivity extends Activity {

    private static final String TAG = AssistantActivity.class.getSimpleName();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VoiceHatAssistantService.ACTION_CONVERSE_RESULT.equals(intent.getAction())) {

                final String spokenRequestText = intent.getStringExtra(VoiceHatAssistantService.ARG_CONVERSE_UTTERANCE);
                Log.i(TAG, spokenRequestText);

            }
        }
    };

    private final IntentFilter mIntentFilter = new IntentFilter(VoiceHatAssistantService.ACTION_CONVERSE_RESULT);

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Bound to Assistant Service");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        registerReceiver(mBroadcastReceiver, mIntentFilter);

        //either start the originial voice hat service
//        bindService(new Intent(this, VoiceHatAssistantService.class), mServiceConnection, BIND_AUTO_CREATE);
        //or the one for the simple breadboard layout
        bindService(new Intent(this, BreadboardAssistantService.class), mServiceConnection, BIND_AUTO_CREATE);

    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mBroadcastReceiver);
        unbindService(mServiceConnection);
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}
