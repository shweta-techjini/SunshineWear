package com.example.android.sunshine.app.wear;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
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
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Set;

public class AppListenWearService extends WearableListenerService implements DataApi.DataListener, MessageApi.MessageListener {

    private static GoogleApiClient googleApiClient;

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    public AppListenWearService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("AppListenWearService", "onStartCommand method call");
        initializeGoogleApiClient();
        sendUpdatedDataToWear();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.d("AppListenWearService", "onDatachanges method call");
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem dataItem = event.getDataItem();
                Log.d("AppListWearService", "dataItem toString " + dataItem.toString());
                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                Set<String> keySet = dataMap.keySet();
                Iterator<String> iterator = keySet.iterator();
                while (iterator.hasNext()) {
                    Log.d("AppListWearService", "onDataChanges key is : " + iterator.next() + " and value is :: " + dataMap.get(iterator.next()));
                }

            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                // DataItem deleted
            }
        }
        super.onDataChanged(dataEventBuffer);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        Log.d("AppListenWearService", "message is :: " + messageEvent);
        if (messageEvent.getPath().equals("/weather-info")) {
            Log.d("AppListenWearService", "send data to wear if there is");
            SunshineSyncAdapter.syncImmediately(getApplicationContext());
        }
    }

    private void initializeGoogleApiClient() {
        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this).addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                @Override
                public void onConnected(Bundle bundle) {
                    Log.d("AppListenWearService", "OnConnectedCall");
                    Wearable.DataApi.addListener(googleApiClient, AppListenWearService.this);
                    Wearable.MessageApi.addListener(googleApiClient, AppListenWearService.this);
                    sendData();
                }

                @Override
                public void onConnectionSuspended(int i) {
                    Log.d("AppListenWearService", "onConnectionSuspendedCall");

                }
            }).addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                @Override
                public void onConnectionFailed(ConnectionResult connectionResult) {
                    Log.d("AppListenWearService", "onConnectionFailed");
                }
            }).addApi(Wearable.API).build();
        }
    }

    private void sendUpdatedDataToWear() {
        Log.d("AppListenWearService", "sendUpdatedDataToWear");
        if (!googleApiClient.isConnected()) {
            googleApiClient.connect();
        } else {
            sendData();
        }

    }

    private void sendData() {
        Context context = getApplicationContext();
        String locationQuery = Utility.getPreferredLocation(context);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        // we'll query our contentProvider, as always
        Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);
            String desc = cursor.getString(INDEX_SHORT_DESC);

            String highTemp = Utility.formatTemperature(getApplicationContext(), high);
            String lowTemp = Utility.formatTemperature(getApplicationContext(), low);

            int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
            Resources resources = context.getResources();
            Bitmap weatherIcon = BitmapFactory.decodeResource(resources, iconId);
            if (googleApiClient.isConnected()) {
                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/weather-info");
                putDataMapRequest.getDataMap().putString("w_high", highTemp);
                putDataMapRequest.getDataMap().putString("w_low", lowTemp);
                putDataMapRequest.getDataMap().putString("description", desc);
                putDataMapRequest.getDataMap().putAsset("w_icon", createAssetFromBitmap(weatherIcon));

                PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();
                Wearable.DataApi.putDataItem(googleApiClient, putDataRequest).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        if (dataItemResult.getStatus().isSuccess()) {
                            Log.d("AppListenWearService", "send data to wear app" + dataItemResult.getDataItem().getUri());
                        } else {
                            Log.d("AppListenWearService", "failed to send data to wear app.");
                        }
                        googleApiClient.disconnect();
                    }
                });
            }
        }
    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }
}
