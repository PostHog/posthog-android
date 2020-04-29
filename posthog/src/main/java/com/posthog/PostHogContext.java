/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment.io, Inc.
 *
 * Copyright (c) 2020 Hiberly Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.posthog;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Context.TELEPHONY_SERVICE;
import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static com.posthog.internal.Utils.NullableConcurrentHashMap;
import static com.posthog.internal.Utils.createMap;
import static com.posthog.internal.Utils.getDeviceId;
import static com.posthog.internal.Utils.getSystemService;
import static com.posthog.internal.Utils.hasPermission;
import static com.posthog.internal.Utils.isNullOrEmpty;
import static com.posthog.internal.Utils.isOnClassPath;
import static java.util.Collections.unmodifiableMap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import com.posthog.core.BuildConfig;
import com.posthog.internal.Private;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;

/**
 * Context is a dictionary of free-form information about the state of the device. Context is
 * attached to every outgoing call. You can add any custom data to the context dictionary that you'd
 * like to have access to in the raw logs.
 *
 * <p>This is renamed to PostHogContext on Android to avoid confusion with {@link Context} in the
 * Android framework. Any documentation for Context on our website is referring to PostHogContext
 * on Android.
 *
 * <p>Some keys in the context dictionary have semantic meaning and will be collected for you
 * automatically, depending on the library you send data from. Some keys, such as IP address, and
 * speed need to be manually entered, such as IP Address, speed, etc.
 *
 * <p>PostHogContext is not persisted to disk, and is filled each time the app starts.
 *
 * <p>On the topic of the thread safety of traits, whilst this class utilises LinkedHashMap, writes
 * to this map only occur from within the handler thread. Meanwhile reads are served immutable
 * copies of traits mitigating the risk of data races with the exception of clients of this library
 * modifying nested data structures after passing them to this library. This concern could be
 * mitigated by deep rather than shallow copying (e.g. via de-serialiation and re-serialisation),
 * however this would contribute a performance penalty.
 */
public class PostHogContext extends ValueMap {

  private static final String LOCALE_KEY = "$locale";
  private static final String USER_AGENT_KEY = "$user_agent";
  private static final String TIMEZONE_KEY = "$timezone";
  // App
  private static final String APP_NAME_KEY = "$app_name";
  private static final String APP_VERSION_KEY = "$app_version";
  private static final String APP_NAMESPACE_KEY = "$app_namespace";
  private static final String APP_BUILD_KEY = "$app_build";
  // Device
  private static final String DEVICE_ID_KEY = "$device_id";
  private static final String DEVICE_MANUFACTURER_KEY = "$device_manufacturer";
  private static final String DEVICE_MODEL_KEY = "$device_model";
  private static final String DEVICE_NAME_KEY = "$device_name";
  private static final String DEVICE_TOKEN_KEY = "$device_token";
  private static final String DEVICE_ADVERTISING_ID_KEY = "$device_advertising_id";
  private static final String DEVICE_AD_CAPTURING_ENABLED_KEY = "$device_ad_capturing_enabled";
  // Library
  private static final String LIBRARY_NAME_KEY = "$lib";
  private static final String LIBRARY_VERSION_KEY = "$lib_version";
  // Network
  private static final String NETWORK_BLUETOOTH_KEY = "$network_bluetooth";
  private static final String NETWORK_CARRIER_KEY = "$network_carrier";
  private static final String NETWORK_CELLULAR_KEY = "$network_cellular";
  private static final String NETWORK_WIFI_KEY = "$network_wifi";
  // OS
  private static final String OS_NAME_KEY = "$os_name";
  private static final String OS_VERSION_KEY = "$os_version";
  // Screen
  private static final String SCREEN_DENSITY_KEY = "$screen_density";
  private static final String SCREEN_HEIGHT_KEY = "$screen_height";
  private static final String SCREEN_WIDTH_KEY = "$screen_width";

  /**
   * Create a new {@link PostHogContext} instance filled in with information from the given {@link
   * Context}. The {@link PostHog} client can be called from anywhere, so the returned instances
   * is thread safe.
   */
  static synchronized PostHogContext create(
      Context context, Traits traits, boolean collectDeviceId) {
    PostHogContext posthogContext = new PostHogContext(new NullableConcurrentHashMap<String, Object>());
    posthogContext.putApp(context);
    posthogContext.putDevice(context, traits, collectDeviceId);
    posthogContext.putLibrary();
    posthogContext.put(LOCALE_KEY, Locale.getDefault().getLanguage() + "-" + Locale.getDefault().getCountry());
    posthogContext.putNetwork(context);
    posthogContext.putOs();
    posthogContext.putScreen(context);
    putUndefinedIfNull(posthogContext, USER_AGENT_KEY, System.getProperty("http.agent"));
    putUndefinedIfNull(posthogContext, TIMEZONE_KEY, TimeZone.getDefault().getID());
    return posthogContext;
  }

  static void putUndefinedIfNull(Map<String, Object> target, String key, CharSequence value) {
    if (isNullOrEmpty(value)) {
      target.put(key, "undefined");
    } else {
      target.put(key, value);
    }
  }

  // For deserialization and wrapping
  PostHogContext(Map<String, Object> delegate) {
    super(delegate);
  }

  void attachAdvertisingId(Context context, CountDownLatch latch, Logger logger) {
    // This is done as an extra step so we don't run into errors like this for testing
    // http://pastebin.com/gyWJKWiu.
    if (isOnClassPath("com.google.android.gms.ads.identifier.AdvertisingIdClient")) {
      new GetAdvertisingIdTask(this, latch, logger).execute(context);
    } else {
      logger.debug(
          "Not collecting advertising ID because "
              + "com.google.android.gms.ads.identifier.AdvertisingIdClient "
              + "was not found on the classpath.");
      latch.countDown();
    }
  }

  @Override
  public PostHogContext putValue(String key, Object value) {
    super.putValue(key, value);
    return this;
  }

  /** Returns an unmodifiable shallow copy of the values in this map. */
  public PostHogContext unmodifiableCopy() {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(this);
    return new PostHogContext(unmodifiableMap(map));
  }

  /**
   * Fill this instance with application info from the provided {@link Context}.
   */
  void putApp(Context context) {
    try {
      PackageManager packageManager = context.getPackageManager();
      PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
      putUndefinedIfNull(this, APP_NAME_KEY, packageInfo.applicationInfo.loadLabel(packageManager));
      putUndefinedIfNull(this, APP_VERSION_KEY, packageInfo.versionName);
      putUndefinedIfNull(this, APP_NAMESPACE_KEY, packageInfo.packageName);
      put(APP_BUILD_KEY, String.valueOf(packageInfo.versionCode));
    } catch (PackageManager.NameNotFoundException e) {
      // ignore
    }
  }

  /** Fill this instance with device info from the provided {@link Context}. */
  void putDevice(Context context, Traits traits, boolean collectDeviceID) {
    String identifier = collectDeviceID ? getDeviceId(context) : traits.anonymousId();
    put(DEVICE_ID_KEY, identifier);
    put(DEVICE_MANUFACTURER_KEY, Build.MANUFACTURER);
    put(DEVICE_MODEL_KEY, Build.MODEL);
    put(DEVICE_NAME_KEY, Build.DEVICE);
  }

  /** Set a device token.  */
  void putDeviceToken(String token) {
    put(DEVICE_TOKEN_KEY, token);
  }

  /** Fill this instance with library information. */
  void putLibrary() {
    put(LIBRARY_NAME_KEY, "posthog-android");
    put(LIBRARY_VERSION_KEY, BuildConfig.VERSION_NAME);
  }

  /**
   * Fill this instance with network information. No need to expose a getter for this for bundled
   * integrations (they'll automatically fill what they need themselves).
   */
  @SuppressLint("MissingPermission")
  void putNetwork(Context context) {
    if (hasPermission(context, ACCESS_NETWORK_STATE)) {
      ConnectivityManager connectivityManager = getSystemService(context, CONNECTIVITY_SERVICE);
      if (connectivityManager != null) {
        NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(TYPE_WIFI);
        put(NETWORK_WIFI_KEY, wifiInfo != null && wifiInfo.isConnected());
        NetworkInfo bluetoothInfo = connectivityManager.getNetworkInfo(TYPE_BLUETOOTH);
        put(NETWORK_BLUETOOTH_KEY, bluetoothInfo != null && bluetoothInfo.isConnected());
        NetworkInfo cellularInfo = connectivityManager.getNetworkInfo(TYPE_MOBILE);
        put(NETWORK_CELLULAR_KEY, cellularInfo != null && cellularInfo.isConnected());
      }
    }

    TelephonyManager telephonyManager = getSystemService(context, TELEPHONY_SERVICE);
    if (telephonyManager != null) {
      put(NETWORK_CARRIER_KEY, telephonyManager.getNetworkOperatorName());
    } else {
      put(NETWORK_CARRIER_KEY, "unknown");
    }
  }

  /** Fill this instance with operating system information. */
  void putOs() {
    put(OS_NAME_KEY, "Android");
    put(OS_VERSION_KEY, Build.VERSION.RELEASE);
  }

  /**
   * Fill this instance with application info from the provided {@link Context}. No need to expose a
   * getter for this for bundled integrations (they'll automatically fill what they need
   * themselves).
   */
  void putScreen(Context context) {
    WindowManager manager = getSystemService(context, Context.WINDOW_SERVICE);
    Display display = manager.getDefaultDisplay();
    DisplayMetrics displayMetrics = new DisplayMetrics();
    display.getMetrics(displayMetrics);
    put(SCREEN_DENSITY_KEY, displayMetrics.density);
    put(SCREEN_HEIGHT_KEY, displayMetrics.heightPixels);
    put(SCREEN_WIDTH_KEY, displayMetrics.widthPixels);
  }

  /** Set the advertising information for this device. */
  void putAdvertisingInfo(String advertisingId, boolean adCapturingEnabled) {
    if (adCapturingEnabled && !isNullOrEmpty(advertisingId)) {
      put(DEVICE_ADVERTISING_ID_KEY, advertisingId);
    }
    put(DEVICE_AD_CAPTURING_ENABLED_KEY, adCapturingEnabled);
  }
}
