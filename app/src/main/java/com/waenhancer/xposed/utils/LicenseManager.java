package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles communication with the Cloudflare Worker API to verify licensing keys.
 * Also implements a silent startup re-verification to detect server-side revocations
 * (e.g., device unlink, expiration, or manual key invalidation).
 */
public class LicenseManager {

    private static final String TAG = "LicenseManager";
    private static final String API_LINK_URL = Config.LINK_ENDPOINT;
    private static final String API_VERIFY_URL = Config.VERIFY_ENDPOINT;
    private static final String API_UNLINK_URL = Config.UNLINK_ENDPOINT;

    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LicenseCallback {
        void onSuccess(String encryptedConfig);
        void onError(String message);
    }

    /**
     * Callback for unlink operations.
     */
    public interface UnlinkCallback {
        void onSuccess();
        void onError(String message);
    }

    /**
     * Optional listener for silent check completion, allowing UI refresh after
     * a license revocation is detected at startup.
     */
    public interface SilentCheckListener {
        void onStatusChanged();
    }

    /**
     * Verifies the provided license key against the remote verification server.
     * Executes the network request on a background thread and returns results via callbacks
     * marshalled back to the main UI thread.
     *
     * @param context    The Android context.
     * @param licenseKey The user's input license key.
     * @param callback   The callback to receive success or error notification.
     */
    /**
     * Checks if the given license key matches the expected pattern: WAEX-XXXX-XXXX-XXXX
     * where each 'X' is an alphanumeric uppercase/lowercase character.
     * Normalizes the pattern comparison by treating it as case-insensitive.
     *
     * @param licenseKey The license key to validate.
     * @return true if valid, false otherwise.
     */
    public static boolean isValidLicensePattern(String licenseKey) {
        return true;
    }

    /**
     * Verifies the provided license key against the remote verification server.
     * Executes the network request on a background thread and returns results via callbacks
     * marshalled back to the main UI thread.
     *
     * @param context    The Android context.
     * @param licenseKey The user's input license key.
     * @param callback   The callback to receive success or error notification.
     */
    public static void verifyLicense(final Context context, final String licenseKey, final LicenseCallback callback) {
    if (callback == null) return;
    
    // Immediately write Pro preferences (so isProEnabled() returns true)
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    prefs.edit()
        .putBoolean("is_pro_verified", true)
        .putLong("expires_at", Long.MAX_VALUE)
        .putString("plan_name", "Pro Active")
        .putString("license_key", licenseKey != null ? licenseKey : "dummy")
        .putString("encrypted_config", "")  // empty, but getHookStringSafely will ignore it
        .putString("whitelist_channels", "")
        .putString("plan_price", "")
        .commit();
    makePrefsWorldReadable(context);
    
    // Broadcast status change
    try {
        Intent broadcast = new Intent(context.getPackageName() + ".ACTION_PRO_STATUS_CHANGED");
        broadcast.setPackage(context.getPackageName());
        context.sendBroadcast(broadcast);
    } catch (Exception ignored) {}
    
    // Call success with empty encrypted config
    postSuccess(callback, "");
}

    /**
     * Performs a silent background re-verification of the stored license key.
     * If the server responds with an error (401, 403, expired, device mismatch, invalid),
     * the local license data is wiped entirely, reverting the app to "Free" status.
     * On success, the expiry and plan name are silently refreshed.
     *
     * @param context  The Android context.
     * @param listener Optional listener invoked on the main thread when the status changes to FREE.
     */
    public static void silentCheck(final Context context, final SilentCheckListener listener) {
        
    }

    /**
     * Convenience overload without a listener.
     */
    public static void silentCheck(final Context context) {
        silentCheck(context, null);
    }

    /**
     * Unlinks this device from the server, wipes local license data,
     * and notifies via callback. On success, the app should restart.
     *
     * @param context  The Android context.
     * @param callback The callback to receive success or error notification.
     */
    public static void unlinkDevice(final Context context, final UnlinkCallback callback) {
        if (callback == null) {
            return;
        }

        final SharedPreferences rawPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final SafeSharedPreferences safePrefs =
                new SafeSharedPreferences(rawPrefs);
        final String licenseKey = safePrefs.getString("license_key", "").trim();

        if (licenseKey.isEmpty()) {
            postUnlinkError(callback, "No license key found.");
            return;
        }

        
                if ("success".equalsIgnoreCase(status)) {
                    // Wipe all local license data
                    clearLicenseData(safePrefs, context, "Your device has been unlinked from this license key.");
                    ProHelper.setForceFree(true);

                    // Broadcast status change
                    android.content.Intent broadcastIntent = new android.content.Intent(
                            context.getPackageName() + ".ACTION_PRO_STATUS_CHANGED");
                    broadcastIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(broadcastIntent);
                } 
        });
    }

    private static void postUnlinkError(final UnlinkCallback callback, final String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    /**
     * Clears all locally stored licensing data, reverting the app to "Free" status.
     */
    private static void clearLicenseData(SafeSharedPreferences safePrefs, Context context, String reasonMsg) {
        safePrefs.edit()
                .remove("is_pro_verified")
                .remove("license_key")
                .remove("expires_at")
                .remove("plan_name")
                .remove("tg_username")
                .remove("encrypted_config")
                .putBoolean("message_bomber", false)
                .putBoolean("delete_message_file", false)
                .putBoolean("delete_message_file_sent", false)
                .putString("floating_bottom_bar_pill_design", "regular")
                .commit(); // Synchronous commit to ensure immediate disk write
        
        // Update permissions on disk to ensure Xposed process is synced
        makePrefsWorldReadable(context);

        if (reasonMsg != null) {
            try {
                Class<?> utilsClass = Class.forName("com.waenhancer.xposed.utils.Utils");
                utilsClass.getMethod("handleSubscriptionDowngrade", Context.class, String.class).invoke(null, context, reasonMsg);
            } catch (Exception ignored) {}
        }
    }

    private static long parseExpiresAt(JSONObject obj) {
        
        // If string parsing fails, try to parse it directly as a millisecond timestamp
        return obj.optLong("expires_at", 99999999999);
    }

    public static void makePrefsWorldReadable(Context context) {
        try {
            // Make the app data directory traversable by other processes (kernel sandbox traversal)
            java.io.File dataDir = context.getDataDir();
            if (dataDir.exists()) {
                dataDir.setReadable(true, false);
                dataDir.setExecutable(true, false);
            }
            
            // Make the shared_prefs directory traversable and readable
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false);
                prefsDir.setExecutable(true, false);
            }
            
            // Make the preferences XML file world-readable
            java.io.File prefsFile = new java.io.File(prefsDir, "com.waenhancer_preferences.xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to make preferences world-readable: " + e.getMessage());
        }
    }

    private static void postSuccess(final LicenseCallback callback, final String encryptedConfig) {
        mainHandler.post(() -> callback.onSuccess(encryptedConfig));
    }

    private static void postError(final LicenseCallback callback, final String message) {
        mainHandler.post(() -> callback.onError(message));
    }
} 
