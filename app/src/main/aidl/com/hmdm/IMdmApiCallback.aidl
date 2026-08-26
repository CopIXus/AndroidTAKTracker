// IMdmApiCallback.aidl — Headwind MDM Plugin API (Apache-2.0)
// https://github.com/h-mdm/hmdm-android
package com.hmdm;

oneway interface IMdmApiCallback {
    void onConfigUpdateStart();
    void onConfigUpdateError(int type, String errorText);
    void onConfigLoaded();
    void onPoliciesUpdated();
    void onFileDownloading(String path);
    void onDownloadProgress(int progress, long total, long current);
    void onFileError(int type, String path);
    void onAppUpdateStart();
    void onAppRemoving(String pkg, String name);
    void onAppDownloading(String pkg, String name);
    void onAppInstalling(String pkg, String name);
    void onAppError(int type, String pkg);
    void onAppInstallComplete(String pkg);
    void onConfigUpdateComplete();
    void onAllAppInstallComplete();
}
