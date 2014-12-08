package com.infthink.flint.samples.videoplayer;

import android.net.Uri;

public interface FlintStatusChangeListener {
    void onDeviceSelected(String name);
    void onDeviceUnselected();
    void onVolumeChanged(double percent, boolean muted);
    void onApplicationStatusChanged(String status);
    void onApplicationDisconnected();
    void onConnectionFailed();
    void onConnected();
    void onNoLongerRunning(boolean isRunning);
    void onConnectionSuspended();
    void onMediaStatusUpdated();
    void onMediaMetadataUpdated(String title, String artist, Uri imageUrl);
    void onApplicationConnectionResult(String applicationStatus);
    void onLeaveApplication();
    void onStopApplication();
    void onMediaSeekEnd();
    void onMediaVolumeEnd();
}
