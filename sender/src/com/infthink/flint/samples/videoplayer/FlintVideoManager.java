package com.infthink.flint.samples.videoplayer;

import java.io.IOException;
import java.util.List;

import com.infthink.flint.samples.videoplayer.R;

import tv.matchstick.flint.ApplicationMetadata;
import tv.matchstick.flint.ConnectionResult;
import tv.matchstick.flint.Flint;
import tv.matchstick.flint.FlintDevice;
import tv.matchstick.flint.FlintManager;
import tv.matchstick.flint.FlintMediaControlIntent;
import tv.matchstick.flint.FlintStatusCodes;
import tv.matchstick.flint.MediaInfo;
import tv.matchstick.flint.MediaMetadata;
import tv.matchstick.flint.MediaStatus;
import tv.matchstick.flint.RemoteMediaPlayer;
import tv.matchstick.flint.ResultCallback;
import tv.matchstick.flint.Status;
import tv.matchstick.flint.Flint.ApplicationConnectionResult;
import tv.matchstick.flint.RemoteMediaPlayer.MediaChannelResult;
import tv.matchstick.flint.images.WebImage;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

public class FlintVideoManager {
    private static final String TAG = FlintVideoManager.class.getSimpleName();

    public static final double VOLUME_INCREMENT = 0.05;
    public static final double MAX_VOLUME_LEVEL = 20;

    private Context mContext;
    private Handler mHandler;
    private String mApplicationId;

    private FlintStatusChangeListener mStatusChangeListener;

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private FlintMediaRouterCallback mMediaRouterCallback;
    private FlintDevice mSelectedDevice;
    private FlintManager mApiClient;
    private FlintListener mFlintListener;
    private ConnectionCallbacks mConnectionCallbacks;

    private MediaInfo mMediaInfo;
    private RemoteMediaPlayer mMediaPlayer;
    private ApplicationMetadata mAppMetadata;

    private boolean mWaitingForReconnect;

    public FlintVideoManager(Context context, String applicationId,
            FlintStatusChangeListener listener) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mApplicationId = applicationId;
        mStatusChangeListener = listener;

        Log.d(TAG, "Application ID is: " + mApplicationId);
        mMediaRouter = MediaRouter.getInstance(context);
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(
                        FlintMediaControlIntent
                                .categoryForFlint(mApplicationId)).build();

        mMediaRouterCallback = new FlintMediaRouterCallback();
        addRouterCallback();

        mConnectionCallbacks = new ConnectionCallbacks();

        mFlintListener = new FlintListener();
    }

    private String getAppUrl() {
        return "http://openflint.github.io/simple-player-demo/receiver/index.html";
    }

    /**
     * Create mediarouter button
     * 
     * @param menu
     * @param menuResourceId
     * @return
     */
    public MenuItem addMediaRouterButton(Menu menu, int menuResourceId) {
        MenuItem mediaRouteMenuItem = menu.findItem(menuResourceId);
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat
                .getActionProvider(mediaRouteMenuItem);
        mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);
        return mediaRouteMenuItem;
    }

    private void addRouterCallback() {
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    public void destroy() {
        mMediaRouter.removeCallback(mMediaRouterCallback);
    }

    /**
     * When the user selects a device from the Flint button device list, the
     * application is informed of the selected device by extending
     * MediaRouter.Callback
     * 
     * @author changxing
     * 
     */
    private class FlintMediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteSelected(MediaRouter router, RouteInfo route) {
            Log.d(TAG, "onRouteSelected: route=" + route);
            FlintDevice device = FlintDevice.getFromBundle(route.getExtras());
            onDeviceSelected(device);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, RouteInfo route) {
            Log.d(TAG, "onRouteUnselected: route=" + route);
            FlintDevice device = FlintDevice.getFromBundle(route.getExtras());
            onDeviceUnselected(device);
        }
    }

    /**
     * Connect select device
     * 
     * @param device
     */
    private void onDeviceSelected(FlintDevice device) {
        setSelectedDevice(device);

        if (mStatusChangeListener != null)
            mStatusChangeListener.onDeviceSelected(device.getFriendlyName());
    }

    /**
     * Disconnect device
     * 
     * @param device
     */
    private void onDeviceUnselected(FlintDevice device) {
        setSelectedDevice(null);

        if (mStatusChangeListener != null)
            mStatusChangeListener.onDeviceUnselected();
    }

    private void setSelectedDevice(FlintDevice device) {
        mSelectedDevice = device;

        if (mSelectedDevice == null) {
            detachMediaPlayer();
            leaveApplication();
            if ((mApiClient != null) && mApiClient.isConnected()) {
                mApiClient.disconnect();
            }
        } else {
            Log.d(TAG, "acquiring controller for " + mSelectedDevice);
            try {
                Flint.FlintOptions.Builder apiOptionsBuilder = Flint.FlintOptions
                        .builder(mSelectedDevice, mFlintListener);

                mApiClient = new FlintManager.Builder(mContext)
                        .addApi(Flint.API, apiOptionsBuilder.build())
                        .addConnectionCallbacks(mConnectionCallbacks).build();
                mApiClient.connect();
            } catch (IllegalStateException e) {
                Log.w(TAG, "error while creating a device controller", e);
            }
        }
    }

    /**
     * FlintManager.ConnectionCallbacks and
     * FlintManager.OnConnectionFailedListener callbacks to be informed of the
     * connection status. All of the callbacks run on the main UI thread.
     * 
     * @author changxing
     * 
     */
    private class ConnectionCallbacks implements
            FlintManager.ConnectionCallbacks {
        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "ConnectionCallbacks.onConnectionSuspended");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mWaitingForReconnect = true;
                    detachMediaPlayer();
                    mStatusChangeListener.onConnectionSuspended();

                }
            });
        }

        @Override
        public void onConnected(final Bundle connectionHint) {
            Log.d(TAG, "ConnectionCallbacks.onConnected");
            if (!mApiClient.isConnected()) {
                return;
            }
            try {
                Flint.FlintApi.requestStatus(mApiClient);
            } catch (IOException e) {
                Log.d(TAG, "error requesting status", e);
            }

            mStatusChangeListener.onConnected();

            if (mWaitingForReconnect) {
                mWaitingForReconnect = false;
                if ((connectionHint != null)
                        && connectionHint
                                .getBoolean(Flint.EXTRA_APP_NO_LONGER_RUNNING)) {
                    Log.d(TAG, "App  is no longer running");
                    detachMediaPlayer();
                    mAppMetadata = null;
                    mStatusChangeListener.onNoLongerRunning(false);
                } else {
                    attachMediaPlayer();
                    requestMediaStatus();
                    mStatusChangeListener.onNoLongerRunning(true);
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.d(TAG, "onConnectionFailed");
            mStatusChangeListener.onConnectionFailed();
        }
    }

    /**
     * The Flint.Listener callbacks are used to inform the sender application
     * about receiver application events.
     * 
     * @author changxing
     * 
     */
    private class FlintListener extends Flint.Listener {
        @Override
        public void onVolumeChanged() {
            double volume = Flint.FlintApi.getVolume(mApiClient);
            boolean isMute = Flint.FlintApi.isMute(mApiClient);
            mStatusChangeListener.onVolumeChanged(volume, isMute);
        }

        @Override
        public void onApplicationStatusChanged() {
            String status = Flint.FlintApi.getApplicationStatus(mApiClient);
            Log.d(TAG, "onApplicationStatusChanged; status=" + status);
            mStatusChangeListener.onApplicationStatusChanged(status);
        }

        @Override
        public void onApplicationDisconnected(int statusCode) {
            Log.d(TAG, "onApplicationDisconnected: statusCode=" + statusCode);
            mAppMetadata = null;
            detachMediaPlayer();
            mStatusChangeListener.onApplicationDisconnected();
            if (statusCode != ConnectionResult.SUCCESS) {
                // This is an unexpected disconnect.
                mStatusChangeListener.onApplicationStatusChanged(mContext
                        .getString(R.string.status_app_disconnected));
            }
        }
    }

    /**
     * Launch receiver application.
     */
    public void launchApplication() {
        if (!mApiClient.isConnected()) {
            return;
        }

        Flint.FlintApi.launchApplication(mApiClient, getAppUrl(), true)
                .setResultCallback(
                        new ApplicationConnectionResultCallback("LaunchApp"));
    }

    /**
     * Join to receiver application.
     */
    public void joinApplication() {
        if (!mApiClient.isConnected()) {
            return;
        }

        Flint.FlintApi.joinApplication(mApiClient, getAppUrl())
                .setResultCallback(
                        new ApplicationConnectionResultCallback(
                                "JoinApplication"));
    }

    /**
     * Leave to receiver application.
     */
    public void leaveApplication() {
        if (!mApiClient.isConnected()) {
            return;
        }

        Flint.FlintApi.leaveApplication(mApiClient).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status result) {
                        if (result.isSuccess()) {
                            mAppMetadata = null;
                            detachMediaPlayer();
                            mStatusChangeListener.onLeaveApplication();
                        }
                    }
                });
    }

    /**
     * Stop receiver application.
     */
    public void stopApplication() {
        if (!mApiClient.isConnected()) {
            return;
        }

        Flint.FlintApi.stopApplication(mApiClient).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status result) {
                        if (result.isSuccess()) {
                            mAppMetadata = null;
                            detachMediaPlayer();
                            mStatusChangeListener.onStopApplication();
                            // updateButtonStates();
                        }
                    }
                });
    }

    /**
     * To use the media channel create an instance of RemoteMediaPlayer and set
     * the update listeners to receive media status updates.
     */
    private void attachMediaPlayer() {
        if (mMediaPlayer != null) {
            return;
        }

        mMediaPlayer = new RemoteMediaPlayer();
        mMediaPlayer
                .setOnStatusUpdatedListener(new RemoteMediaPlayer.OnStatusUpdatedListener() {

                    @Override
                    public void onStatusUpdated() {
                        Log.d(TAG, "MediaControlChannel.onStatusUpdated");
                        mStatusChangeListener.onMediaStatusUpdated();
                    }
                });

        mMediaPlayer
                .setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
                    @Override
                    public void onMetadataUpdated() {
                        Log.d(TAG, "MediaControlChannel.onMetadataUpdated");
                        String title = null;
                        String artist = null;
                        Uri imageUrl = null;

                        MediaInfo mediaInfo = mMediaPlayer.getMediaInfo();
                        if (mediaInfo != null) {
                            MediaMetadata metadata = mediaInfo.getMetadata();
                            if (metadata != null) {
                                title = metadata
                                        .getString(MediaMetadata.KEY_TITLE);

                                artist = metadata
                                        .getString(MediaMetadata.KEY_ARTIST);
                                if (artist == null) {
                                    artist = metadata
                                            .getString(MediaMetadata.KEY_STUDIO);
                                }

                                List<WebImage> images = metadata.getImages();
                                if ((images != null) && !images.isEmpty()) {
                                    WebImage image = images.get(0);
                                    imageUrl = image.getUrl();
                                }
                            }
                            mStatusChangeListener.onMediaMetadataUpdated(title,
                                    artist, imageUrl);
                        }
                    }
                });

        try {
            Flint.FlintApi.setMessageReceivedCallbacks(mApiClient,
                    mMediaPlayer.getNamespace(), mMediaPlayer);
        } catch (IOException e) {
            Log.w(TAG, "Exception while launching application", e);
        }
    }

    private void detachMediaPlayer() {
        if ((mMediaPlayer != null) && (mApiClient != null)) {
            try {
                Flint.FlintApi.removeMessageReceivedCallbacks(mApiClient,
                        mMediaPlayer.getNamespace());
            } catch (IOException e) {
                Log.w(TAG, "Exception while detaching media player", e);
            }
        }
        mMediaPlayer = null;
    }

    /**
     * Flint the media to receiver
     * 
     * @param autoPlay
     */
    public void loadMedia(boolean autoPlay) {
        if (mAppMetadata == null) {
            return;
        }

        if (mMediaInfo == null) {
            MediaMetadata metadata = new MediaMetadata(
                    MediaMetadata.MEDIA_TYPE_MOVIE);
            metadata.putString(MediaMetadata.KEY_TITLE, "Tears Of Steel");

            mMediaInfo = new MediaInfo.Builder(
                    "http://fling.matchstick.tv/droidream/samples/TearsOfSteel.mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType("video/mp4").setMetadata(metadata).build();
        }
        Log.d(TAG, "playMedia: " + mMediaInfo);

        if (mMediaPlayer == null) {
            Log.e(TAG, "Trying to play a video with no active media session");
            return;
        }

        mMediaPlayer.load(mApiClient, mMediaInfo, autoPlay).setResultCallback(
                new MediaResultCallback(mContext
                        .getString(R.string.mediaop_load)));
    }

    public void playMedia() {
        if (mMediaPlayer == null) {
            return;
        }
        mMediaPlayer.play(mApiClient).setResultCallback(
                new MediaResultCallback(mContext
                        .getString(R.string.mediaop_play)));
    }

    public void pauseMedia() {
        if (mMediaPlayer == null) {
            return;
        }
        mMediaPlayer.pause(mApiClient).setResultCallback(
                new MediaResultCallback(mContext
                        .getString(R.string.mediaop_pause)));
    }

    public void stopMedia() {
        if (mMediaPlayer == null) {
            return;
        }
        mMediaPlayer.stop(mApiClient).setResultCallback(
                new MediaResultCallback(mContext
                        .getString(R.string.mediaop_stop)));

    }

    public void seekMedia(long position, int resumeState) {
        if (mMediaPlayer == null) {
            return;
        }
        mMediaPlayer.seek(mApiClient, position, resumeState).setResultCallback(
                new MediaResultCallback(mContext
                        .getString(R.string.mediaop_seek)) {
                    @Override
                    protected void onFinished() {
                        mStatusChangeListener.onMediaSeekEnd();
                    }
                });
    }

    public void setDeviceVolume(int volume) {
        if (!mApiClient.isConnected()) {
            return;
        }
        try {
            Flint.FlintApi.setVolume(mApiClient, volume / MAX_VOLUME_LEVEL);
        } catch (IOException e) {
            Log.w(TAG, "Unable to change volume");
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void setDeviceMute(boolean on) {
        if (!mApiClient.isConnected()) {
            return;
        }
        try {
            Flint.FlintApi.setMute(mApiClient, on);
        } catch (IOException e) {
            Log.w(TAG, "Unable to toggle mute");
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void setMediaVolume(int volume) {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            mMediaPlayer
                    .setStreamVolume(mApiClient, volume / MAX_VOLUME_LEVEL)
                    .setResultCallback(
                            new MediaResultCallback(
                                    mContext.getString(R.string.mediaop_set_stream_volume)));
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void setMediaMute(boolean on) {
        if (mMediaPlayer == null) {
            return;
        }
        try {
            mMediaPlayer.setStreamMute(mApiClient, on).setResultCallback(
                    new MediaResultCallback(mContext
                            .getString(R.string.mediaop_toggle_stream_mute)) {
                        @Override
                        protected void onFinished() {
                            mStatusChangeListener.onMediaVolumeEnd();
                        }
                    });
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    private void requestMediaStatus() {
        if (mMediaPlayer == null) {
            return;
        }

        Log.d(TAG, "requesting current media status");
        mMediaPlayer.requestStatus(mApiClient).setResultCallback(
                new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {
                    @Override
                    public void onResult(MediaChannelResult result) {
                        Status status = result.getStatus();
                        if (!status.isSuccess()) {
                            Log.w(TAG,
                                    "Unable to request status: "
                                            + status.getStatusCode());
                        }
                    }
                });
    }

    private final class ApplicationConnectionResultCallback implements
            ResultCallback<Flint.ApplicationConnectionResult> {
        private final String mClassTag;

        public ApplicationConnectionResultCallback(String suffix) {
            mClassTag = TAG + "_" + suffix;
        }

        @Override
        public void onResult(ApplicationConnectionResult result) {
            Status status = result.getStatus();
            Log.d(mClassTag,
                    "ApplicationConnectionResultCallback.onResult: statusCode"
                            + status.getStatusCode());
            if (status.isSuccess()) {
                ApplicationMetadata applicationMetadata = result
                        .getApplicationMetadata();
                String applicationStatus = result.getApplicationStatus();

                mStatusChangeListener
                        .onApplicationConnectionResult(applicationStatus);
                // setApplicationStatus(applicationStatus);
                attachMediaPlayer();
                mAppMetadata = applicationMetadata;
                requestMediaStatus();
            }
        }
    }

    private class MediaResultCallback implements
            ResultCallback<MediaChannelResult> {
        private final String mOperationName;

        public MediaResultCallback(String operationName) {
            mOperationName = operationName;
        }

        @Override
        public void onResult(MediaChannelResult result) {
            Status status = result.getStatus();
            if (!status.isSuccess()) {
                Log.w(TAG,
                        mOperationName + " failed: " + status.getStatusCode());
            }
            onFinished();
        }

        protected void onFinished() {
        }
    }

    public boolean isDeviceConnected() {
        return (mApiClient != null) && mApiClient.isConnected()
                && !mWaitingForReconnect;
    }

    public boolean isAppConnected() {
        return (mAppMetadata != null) && !mWaitingForReconnect;
    }

    public boolean isMediaConnected() {
        return (mMediaPlayer != null) && !mWaitingForReconnect;
    }

    public long getMediaCurrentTime() {
        if (mMediaPlayer != null)
            return mMediaPlayer.getApproximateStreamPosition();
        return 0;
    }

    public long getMediaDuration() {
        if (mMediaPlayer != null)
            return mMediaPlayer.getStreamDuration();
        return 0;
    }

    public MediaStatus getMediaStatus() {
        if (this.mMediaPlayer != null)
            return this.mMediaPlayer.getMediaStatus();
        return null;
    }
}
