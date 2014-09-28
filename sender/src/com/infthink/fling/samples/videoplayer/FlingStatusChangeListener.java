package com.infthink.fling.samples.videoplayer;

import android.net.Uri;

public interface FlingStatusChangeListener {
    void onDeviceSelected(String name);
    void onDeviceUnselected();
    void onVolumeChanged(double percent, boolean muted);
    void onApplicationStatusChanged(String status);
    void onApplicationDisconnected();
    void onConnectionFailed();
    void onConnected();
    void onNoLongerRunning(boolean isRunning);
    void onConnectionSuspended();
    void onMeidaStatusUpdated();
    void onMeidaMetadataUpdated(String title, String artist, Uri imageUrl);
    void onApplicationConnectionResult(String applicationStatus);
    void onLeaveApplication();
    void onStopApplication();
    void onMediaSeekEnd();
}
