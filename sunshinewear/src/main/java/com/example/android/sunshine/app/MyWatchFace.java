/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final String WEATHER_REQUEST = "/weather-request";
    private static final String WEATHER_INFO = "/weather-info";
    private static final String WEATHER_HIGH = "w_high";
    private static final String WEATHER_LOW = "w_low";
    private static final String WEATHER_ICON = "w_icon";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;

        // Weather Paint
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Bitmap mWeatherStatus;

        // Weather data
        String defaultHighTemp = "0°";
        String defaultLowTemp = "0°";

        boolean mAmbient;
        GoogleApiClient mGoogleApiClient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        // Date and Time Paint
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mDatePaint;
        Paint mLinePaint;

        // Date and Time Format
        Calendar mCalendar;
        SimpleDateFormat dateFormat;
        String mHourFormat;
        String mMinuteFormat;
        boolean isWeatherDataAvailable;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            mWeatherStatus = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear, options);

            mHourPaint = createWhiteBoldPaint();
            mMinutePaint = createWhiteNormalPaint();
            mDatePaint = createLtBluePaint();
            mHighTempPaint = createWhiteBoldPaint();
            mLowTempPaint = createLtBluePaint();
            mLinePaint = createLtBluePaint();

            mCalendar = Calendar.getInstance();
            dateFormat = new SimpleDateFormat(resources.getString(R.string.date_format));
            dateFormat.setTimeZone(mCalendar.getTimeZone());
            mHourFormat = resources.getString(R.string.hour_format);
            mMinuteFormat = resources.getString(R.string.minute_format);
            isWeatherDataAvailable = false;
            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext()).addConnectionCallbacks(this).addOnConnectionFailedListener(this).addApi(Wearable.API).build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
            super.onDestroy();
            isWeatherDataAvailable = false;
        }

        private Paint createWhiteBoldPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createWhiteNormalPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLtBluePaint() {
            Paint paint = new Paint();
            paint.setColor(getColor(R.color.light_blue));
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                if (!mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.connect();
                }
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
//                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float largeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float medium = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_medium : R.dimen.digital_text_size_medium);
            float smallTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_small : R.dimen.digital_text_size_small);

            mHourPaint.setTextSize(largeTextSize);
            mMinutePaint.setTextSize(largeTextSize);
            mDatePaint.setTextSize(smallTextSize);
            mHighTempPaint.setTextSize(medium);
            mLowTempPaint.setTextSize(medium);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int centerX = bounds.centerX();
            int centerY = bounds.centerY();

            // Draw line
            int halfLineWidth = bounds.width() / 8;
            canvas.drawLine((centerX - halfLineWidth), centerY, (centerX + halfLineWidth), centerY, mLinePaint);

            // Draw Date
            String todayDate = dateFormat.format(mCalendar.getTime());
            Rect rect = new Rect();
            mDatePaint.getTextBounds(todayDate, 0, todayDate.length(), rect);
            int dateYPos = centerY - rect.height() - 2;
            canvas.drawText(todayDate, centerX - (rect.width() / 2), dateYPos, mDatePaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            rect.setEmpty();

            // Draw hour
            String hourString = String.format(mHourFormat, mCalendar.get(Calendar.HOUR));
            mHourPaint.getTextBounds(hourString, 0, hourString.length(), rect);
            int hourYPos = dateYPos - rect.height() + 14;
            canvas.drawText(hourString, centerX - rect.width(), hourYPos, mHourPaint);

            // Draw minutes
            canvas.drawText(String.format(mMinuteFormat, mCalendar.get(Calendar.MINUTE)), centerX + 2, hourYPos, mMinutePaint);

            if (isWeatherDataAvailable) {
                // draw bitmap
                int imageXPos = centerX / 2 - mWeatherStatus.getWidth() / 2;
                int imageYPos = centerY + 10;
                canvas.drawBitmap(mWeatherStatus, imageXPos, imageYPos, null);

                int tempYPos = imageYPos + (mWeatherStatus.getHeight() / 2) + 8;
                int tempXPos = centerX + 10;
                // draw high temp
                canvas.drawText(defaultHighTemp, tempXPos, tempYPos, mHighTempPaint);

                // draw low temp
                rect.setEmpty();
                mHighTempPaint.getTextBounds(defaultHighTemp, 0, defaultHighTemp.length(), rect);

                canvas.drawText(defaultLowTemp, tempXPos + rect.width() + 1, tempYPos, mLowTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d("MyWatchFace", "OnConnectedCall");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            getDataFromMobileApp();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("MyWatchFace", "onConnectionSuspendedCall");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("MyWatchFace", "onConnectionFailed" + connectionResult);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d("MyWatchFace", "onDataChanged");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    processDataItem(event.getDataItem());
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        private String getLocalNodeId() {
            NodeApi.GetLocalNodeResult nodeResult = Wearable.NodeApi.getLocalNode(mGoogleApiClient).await();
            return nodeResult.getNode().getId();
        }

        private void getDataFromMobileApp() {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                    .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                        @Override
                        public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                            final List<Node> nodes = getConnectedNodesResult.getNodes();

                            for (Node node : nodes) {
                                Wearable.MessageApi.sendMessage(mGoogleApiClient
                                        , node.getId()
                                        , WEATHER_REQUEST
                                        , new byte[0]).setResultCallback(
                                        new ResultCallback<MessageApi.SendMessageResult>() {
                                            @Override
                                            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                                if (sendMessageResult.getStatus().isSuccess()) {
                                                    Log.d("MyWatchFace", "Message successfully sent");
                                                } else {
                                                    Log.d("MyWatchFace", "Message failed to send");
                                                }
                                            }
                                        }
                                );
                            }
                        }
                    });
        }

        private void processDataItem(DataItem dataItem) {
            DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
            String path = dataItem.getUri().getPath();
            Log.d("MyWatchFace", "path is ::" + path);
            if (path.equals(WEATHER_INFO)) {
                isWeatherDataAvailable = true;
                String highTemp = dataMap.getString(WEATHER_HIGH);
                String lowTemp = dataMap.getString(WEATHER_LOW);
                Asset asset = dataMap.getAsset(WEATHER_ICON);
                loadBitmapFromAsset(asset);
                defaultHighTemp = highTemp;
                defaultLowTemp = lowTemp;
                invalidate();
            }
        }

        public void loadBitmapFromAsset(final Asset asset) {
            Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                @Override
                public void onResult(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                    InputStream assetInputStream = getFdForAssetResult.getInputStream();
                    // decode the stream into a bitmap
                    mWeatherStatus = BitmapFactory.decodeStream(assetInputStream);
                    invalidate();
                }
            });
        }
    }
}
