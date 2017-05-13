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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.AudioInConfig;
import com.google.assistant.embedded.v1alpha1.AudioOutConfig;
import com.google.assistant.embedded.v1alpha1.ConverseConfig;
import com.google.assistant.embedded.v1alpha1.ConverseRequest;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.assistant.embedded.v1alpha1.EmbeddedAssistantGrpc;
import com.google.auth.oauth2.UserCredentials;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;

public class BreadboardAssistantService extends Service implements Button.OnButtonEventListener {
    private static final String TAG = BreadboardAssistantService.class.getSimpleName();

    public static final String ACTION_CONVERSE_RESULT = "com.example.androidthings.assistant.ACTION_CONVERSE_RESULT";
    public static final String ARG_CONVERSE_UTTERANCE = "com.example.androidthings.assistant.ARG_CONVERSE_UTTERANCE";

    // Peripheral constants.
    private static final String I2S_BUS = "I2S1";

    private static final String BUTTON_PIN = "BCM23";
    private static final String LED_PIN = "BCM24";
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static final AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;
    private static final AudioInConfig ASSISTANT_AUDIO_REQUEST_CONFIG =
            AudioInConfig.newBuilder()
                    .setEncoding(ENCODING_INPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();
    private static final AudioOutConfig ASSISTANT_AUDIO_RESPONSE_CONFIG =
            AudioOutConfig.newBuilder()
                    .setEncoding(ENCODING_OUTPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final int SAMPLE_BLOCK_SIZE = 1024;

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";
    private static final UserCredentials ASSISTANT_CREDENTIALS = new UserCredentials(
            Credentials.CLIENT_ID,
            Credentials.CLIENT_SECRET,
            Credentials.REFRESH_TOKEN
    );

    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<ConverseRequest> mAssistantRequestObserver;
    private final StreamObserver<ConverseResponse> mAssistantResponseObserver =
            new StreamObserver<ConverseResponse>() {
                @Override
                public void onNext(final ConverseResponse value) {
                    switch (value.getConverseResponseCase()) {
                        case EVENT_TYPE:
                            Log.d(TAG, "converse response event: " + value.getEventType());
                            break;
                        case RESULT:
                            final String spokenRequestText = value.getResult().getSpokenRequestText();
                            if (!spokenRequestText.isEmpty()) {
                                Log.i(TAG, "assistant request text: " + spokenRequestText);

                                final Intent broadcast = new Intent(ACTION_CONVERSE_RESULT);
                                broadcast.putExtra(ARG_CONVERSE_UTTERANCE, spokenRequestText);
                                sendBroadcast(broadcast);
                            }
                            break;
                        case AUDIO_OUT:
                            final ByteBuffer audioData =
                                    ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                            Log.d(TAG, "converse audio size: " + audioData.remaining());
                            mAudioTrack.write(audioData, audioData.remaining(), AudioTrack.WRITE_BLOCKING);
                            if (mLed != null) {
                                try {
                                    mLed.setValue(!mLed.getValue());
                                } catch (final IOException e) {
                                    Log.e(TAG, "error toggling LED:", e);
                                }
                            }
                            break;
                        case ERROR:
                            Log.e(TAG, "converse response error: " + value.getError());
                            break;
                    }
                }

                @Override
                public void onError(final Throwable t) {
                    Log.e(TAG, "converse error:", t);
                }

                @Override
                public void onCompleted() {
                    Log.i(TAG, "assistant response finished");
                    if (mLed != null) {
                        try {
                            mLed.setValue(false);
                        } catch (final IOException e) {
                            Log.e(TAG, "error turning off LED:", e);
                        }
                    }
                }
            };

    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;

    // Hardware peripherals.
    private BreadboardDriver mBreadboard;
    private Button mButton;
    private Gpio mLed;

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private final Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting assistant request");
            mAudioRecord.startRecording();
            mAssistantRequestObserver = mAssistantService.converse(mAssistantResponseObserver);
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder().setConfig(
                    ConverseConfig.newBuilder()
                            .setAudioInConfig(ASSISTANT_AUDIO_REQUEST_CONFIG)
                            .setAudioOutConfig(ASSISTANT_AUDIO_RESPONSE_CONFIG)
                            .build()).build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private final Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            final ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            final int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
            Log.d(TAG, "streaming ConverseRequest: " + result);
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private final Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            mAudioTrack.play();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {

        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());

        try {
            Log.d(TAG, "creating voice hat driver");
            mBreadboard = new BreadboardDriver(I2S_BUS, AUDIO_FORMAT_STEREO);
            mBreadboard.registerAudioInputDriver();

            mButton = new Button(BUTTON_PIN, Button.LogicState.PRESSED_WHEN_HIGH);
            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);
            final PeripheralManagerService pioService = new PeripheralManagerService();
            mLed = pioService.openGpio(LED_PIN);
            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
        } catch (final IOException e) {
            Log.d(TAG, "error creating voice hat driver:", e);
            return null;
        }

        final AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        final int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.d(TAG, "setting volume to: " + maxVolume);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        final int outputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(outputBufferSize)
                .build();
        mAudioTrack.play();
        final int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();

        final ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                .withCallCredentials(MoreCallCredentials.from(ASSISTANT_CREDENTIALS));

        return null;
    }

    @Override
    public void onButtonEvent(final Button button, final boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(pressed);
            }
        } catch (final IOException e) {
            Log.d(TAG, "error toggling LED:", e);
        }
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mLed != null) {
            try {
                mLed.close();
            } catch (final IOException e) {
                Log.d(TAG, "error closing LED", e);
            }
            mLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (final IOException e) {
                Log.d(TAG, "error closing button", e);
            }
            mButton = null;
        }
        if (mBreadboard != null) {
            try {
                mBreadboard.unregisterAudioInputDriver();
                mBreadboard.close();
            } catch (final IOException e) {
                Log.d(TAG, "error closing voice hat driver", e);
            }
            mBreadboard = null;
        }
        mAssistantHandler.post(() -> mAssistantHandler.removeCallbacks(mStreamAssistantRequest));
        mAssistantThread.quitSafely();
    }
}
