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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.santhoshn.wearable.R;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);


    private static final String TAG = SunshineFace.class.getName();
    private static final String MIN_TEMPRATURE = "sunshine.data.min_temp";
    private static final String MAX_TEMPRATURE = "sunshine.data.max_temp";
    private static final String WEATHER_ID = "sunshine.data.weather_id";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


    private int minTemp = 55;
    private int maxTemp = 77;
    private int weatherId = 525;
    private int currentWeatherId = -1;
    private boolean didRecieveData = true;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineFace.Engine> mWeakReference;

        EngineHandler(SunshineFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mWeatherIconPaint;
        Bitmap mWeatherBitmap;


        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat dateFormat;

        boolean mAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;
        float mLineHeight;
        float mDividerWidth;
        float mTempLineWidth;
        float mTempHighXOffset;
        float mTempLowXOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mDividerWidth = resources.getDimension(R.dimen.divider_line_width);

            mTempHighXOffset = resources.getDimension(R.dimen.round_temp_high_x_offset);
            mTempLowXOffset = resources.getDimension(R.dimen.round_temp_low_x_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = createTextPaint(resources.getColor(R.color.primary_text_color));
            mDatePaint = createTextPaint(resources.getColor(R.color.secondary_text_color));
            mHighTempPaint = createTextPaint(resources.getColor(R.color.primary_text_color));
            mLowTempPaint = createTextPaint(resources.getColor(R.color.secondary_text_color));
            mWeatherIconPaint = new Paint();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                Log.d(TAG, "onVisibilityChanged - Visible =" + visible);
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                mGoogleApiClient.connect();
            } else {
                Log.d(TAG, "onVisibilityChanged - Visible = " + visible);
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Log.d(TAG, "removing listener ");
                    Wearable.DataApi.removeListener(mGoogleApiClient, Engine.this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            dateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.secondary_text_size));

            float tempTextSize = resources.getDimension(isRound ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);
            mHighTempPaint.setTextSize(tempTextSize);
            mLowTempPaint.setTextSize(tempTextSize);
            mTempLineWidth = resources.getDimension(R.dimen.temp_line_width);
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
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
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
                    mTapCount++;
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


            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);


            String timeText = String.format("%s:%s", formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY)), formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE)));
            canvas.drawText(timeText, getXPosition(bounds, mTimePaint.measureText(timeText)), mYOffset, mTimePaint);

            String dateText = dateFormat.format(mDate);
            canvas.drawText(dateText, getXPosition(bounds, mDatePaint.measureText(dateText)), mYOffset + mLineHeight, mDatePaint);

            canvas.drawLine(bounds.centerX() - mDividerWidth / 2, mYOffset + (mLineHeight * 2), bounds.centerX() + mDividerWidth / 2, mYOffset + (mLineHeight * 2), mDatePaint);

            if (didRecieveData) {
                if (weatherId != currentWeatherId) {
                    currentWeatherId = weatherId;
                    loadWeatherIcon(currentWeatherId);
                }

                float iconStartX = bounds.centerX() - mTempLineWidth / 2;
                if (mWeatherBitmap != null) {
                    canvas.drawBitmap(mWeatherBitmap, iconStartX, mYOffset + (float) (mLineHeight * 2.5), mWeatherIconPaint);
                }
                canvas.drawText(maxTemp + "˚", iconStartX + mTempHighXOffset, mYOffset + (mLineHeight * 4), mHighTempPaint);
                canvas.drawText(minTemp + "˚", iconStartX + mTempLowXOffset, mYOffset + (mLineHeight * 4), mLowTempPaint);
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

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private void loadWeatherIcon(int weatherId) {
            int icon = -1;

            if (weatherId >= 200 && weatherId <= 232) {
                icon = R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                icon = R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                icon = R.drawable.ic_rain;
            } else if (weatherId == 511) {
                icon = R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                icon = R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                icon = R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                icon = R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                icon = R.drawable.ic_storm;
            } else if (weatherId == 800) {
                icon = R.drawable.ic_clear;
            } else if (weatherId == 801) {
                icon = R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                icon = R.drawable.ic_cloudy;
            }

            mWeatherBitmap = null;
            if (icon != -1) {
                Drawable weatherDrawable = getResources().getDrawable(icon);
                mWeatherBitmap = ((BitmapDrawable) weatherDrawable).getBitmap();
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Connected to Google API!");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Google API Connection got Suspended!");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Google API Connection Failed!");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    Log.d(TAG, "Data Item Changed");
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/sunshine") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        if (dataMap != null) {
                            Log.d(TAG, "Data Map is " + dataMap.toString());
                            weatherId = dataMap.getInt(WEATHER_ID);
                            maxTemp = ((Double) dataMap.getDouble(MAX_TEMPRATURE)).intValue();
                            minTemp = ((Double) dataMap.getDouble(MIN_TEMPRATURE)).intValue();
                            didRecieveData = true;
                            invalidate();
                        }
                    }
                }
            }
        }
    }

    private float getXPosition(Rect rect, float textWidth) {
        return rect.centerX() - (textWidth / 2);
    }
}