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
package com.posthog.android;

import static com.posthog.android.internal.Utils.assertNotNull;
import static com.posthog.android.internal.Utils.getResourceString;
import static com.posthog.android.internal.Utils.getPostHogSharedPreferences;
import static com.posthog.android.internal.Utils.hasPermission;
import static com.posthog.android.internal.Utils.isNullOrEmpty;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;

import com.posthog.android.payloads.AliasPayload;
import com.posthog.android.payloads.BasePayload;
import com.posthog.android.payloads.IdentifyPayload;
import com.posthog.android.payloads.ScreenPayload;
import com.posthog.android.payloads.CapturePayload;
import com.posthog.android.payloads.GroupPayload;
import com.posthog.android.internal.Private;
import com.posthog.android.internal.Utils;
import com.posthog.android.internal.Utils.PostHogNetworkExecutorService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The entry point into the PostHog for Android SDK.
 *
 * <p>PostHog for Android will automatically batch events, queue them to disk, and upload it
 * periodically to PostHog for you.
 *
 * <p>This class is the main entry point into the client API. Use {@link
 * #with(android.content.Context)} for the global singleton instance or construct your own instance
 * with {@link Builder}.
 */
public class PostHog {

  static final Handler HANDLER =
      new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
          throw new AssertionError("Unknown handler message received: " + msg.what);
        }
      };
  @Private static final String OPT_OUT_PREFERENCE_KEY = "opt-out";
  static final String API_KEY_RESOURCE_IDENTIFIER = "posthog_api_key";
  static final String HOST_RESOURCE_IDENTIFIER = "posthog_host";
  static final String DEFAULT_HOST = "https://app.posthog.com";
  static final List<String> INSTANCES = new ArrayList<>(1);
  /* This is intentional since we're only using the application context. */
  @SuppressLint("StaticFieldLeak")
  static volatile PostHog singleton = null;

  @Private static final Properties EMPTY_PROPERTIES = new Properties();
  private static final String VERSION_KEY = "version";
  private static final String BUILD_KEY = "build";
  private static final String PROPERTIES_KEY = "properties";
  private static final String SEND_FEATURE_FLAGS_KEY = "send_feature_flags";

  private final Application application;
  final ExecutorService networkExecutor;
  final Stats stats;
  private final @NonNull List<Middleware> middlewares;
  @Private final Options defaultOptions;
  @Private final Properties.Cache propertiesCache;
  @Private final Persistence.Cache persistenceCache;
  @Private final PostHogContext posthogContext;
  private final Logger logger;
  final String tag;
  final Client client;
  final Cartographer cartographer;
  final Crypto crypto;
  @Private final Application.ActivityLifecycleCallbacks activityLifecycleCallback;
  @Private final String apiKey;
  @Private final String host;
  final int flushQueueSize;
  final long flushIntervalInMillis;
  // Retrieving the advertising ID is asynchronous. This latch helps us wait to ensure the
  // advertising ID is ready.
  private final CountDownLatch advertisingIdLatch;
  private final ExecutorService posthogExecutor;
  private final BooleanPreference optOut;
  private final Integration integration;
  private final PostHogFeatureFlags featureFlags;

  volatile boolean shutdown;

  /**
   * Return a reference to the global default {@link PostHog} instance.
   *
   * <p>This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   *
   * <p>If these settings do not meet the requirements of your application, you can override
   * defaults in {@code posthog.xml}, or you can construct your own instance with full control
   * over the configuration by using {@link Builder}.
   *
   * <p>By default, events are uploaded every 30 seconds, or every 20 events (whichever occurs
   * first), and debugging is disabled.
   */
  public static PostHog with(Context context) {
    if (singleton == null) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      synchronized (PostHog.class) {
        if (singleton == null) {
          String apiKey = getResourceString(context, API_KEY_RESOURCE_IDENTIFIER);
          String host = getResourceString(context, HOST_RESOURCE_IDENTIFIER);
          Builder builder = new Builder(context, apiKey, host);

          try {
            String packageName = context.getPackageName();
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            boolean debugging = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debugging) {
              builder.logLevel(LogLevel.INFO);
            }
          } catch (PackageManager.NameNotFoundException ignored) {
          }

          singleton = builder.build();
        }
      }
    }
    return singleton;
  }

  /**
   * Set the global instance returned from {@link #with}.
   *
   * <p>This method must be called before any calls to {@link #with} and may only be called once.
   */
  public static void setSingletonInstance(PostHog posthog) {
    synchronized (PostHog.class) {
      if (singleton != null) {
        throw new IllegalStateException("Singleton instance already exists.");
      }
      singleton = posthog;
    }
  }

  PostHog(
      Application application,
      ExecutorService networkExecutor,
      Stats stats,
      Properties.Cache propertiesCache,
      Persistence.Cache persistenceCache,
      PostHogContext posthogContext,
      Options defaultOptions,
      @NonNull Logger logger,
      String tag,
      Client client,
      Cartographer cartographer,
      String apiKey,
      String host,
      int flushQueueSize,
      long flushIntervalInMillis,
      final ExecutorService posthogExecutor,
      final boolean shouldCaptureApplicationLifecycleEvents,
      CountDownLatch advertisingIdLatch,
      final boolean shouldRecordScreenViews,
      final boolean captureDeepLinks,
      BooleanPreference optOut,
      Crypto crypto,
      @NonNull List<Middleware> middlewares,
      Integration integration,
      PostHogFeatureFlags featureFlags
      ) {
    this.application = application;
    this.networkExecutor = networkExecutor;
    this.stats = stats;
    this.propertiesCache = propertiesCache;
    this.persistenceCache = persistenceCache;
    this.posthogContext = posthogContext;
    this.defaultOptions = defaultOptions;
    this.logger = logger;
    this.tag = tag;
    this.client = client;
    this.cartographer = cartographer;
    this.apiKey = apiKey;
    this.host = host;
    this.flushQueueSize = flushQueueSize;
    this.flushIntervalInMillis = flushIntervalInMillis;
    this.advertisingIdLatch = advertisingIdLatch;
    this.optOut = optOut;
    this.posthogExecutor = posthogExecutor;
    this.crypto = crypto;
    this.middlewares = middlewares;
    this.integration = integration != null ? integration : PostHogIntegration.FACTORY.create(this);

    if (featureFlags == null) {
      featureFlags = new PostHogFeatureFlags.Builder()
              .posthog(this)
              .logger(this.logger)
              .client(this.client)
              .build();
    }
    this.featureFlags = featureFlags;

    namespaceSharedPreferences();

    logger.debug("Created posthog client for project with tag:%s.", tag);

    activityLifecycleCallback =
        new PostHogActivityLifecycleCallbacks.Builder()
            .posthog(this)
            .posthogExecutor(posthogExecutor)
            .shouldCaptureApplicationLifecycleEvents(shouldCaptureApplicationLifecycleEvents)
            .captureDeepLinks(captureDeepLinks)
            .shouldRecordScreenViews(shouldRecordScreenViews)
            .packageInfo(getPackageInfo(application))
            .build();

    application.registerActivityLifecycleCallbacks(activityLifecycleCallback);
  }

  @Private
  void captureApplicationLifecycleEvents() {
    // Get the current version.
    PackageInfo packageInfo = getPackageInfo(application);
    String currentVersion = packageInfo.versionName;
    int currentBuild = packageInfo.versionCode;

    // Get the previous recorded version.
    SharedPreferences sharedPreferences = getPostHogSharedPreferences(application, tag);
    String previousVersion = sharedPreferences.getString(VERSION_KEY, null);
    int previousBuild = sharedPreferences.getInt(BUILD_KEY, -1);

    // Check and capture Application Installed or Application Updated.
    if (previousBuild == -1) {
      capture(
          "Application Installed",
          new Properties() //
              .putValue(VERSION_KEY, currentVersion)
              .putValue(BUILD_KEY, currentBuild));
    } else if (currentBuild != previousBuild) {
      capture(
          "Application Updated",
          new Properties() //
              .putValue(VERSION_KEY, currentVersion)
              .putValue(BUILD_KEY, currentBuild)
              .putValue("previous_" + VERSION_KEY, previousVersion)
              .putValue("previous_" + BUILD_KEY, previousBuild));
    }

    // Update the recorded version.
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(VERSION_KEY, currentVersion);
    editor.putInt(BUILD_KEY, currentBuild);
    editor.apply();
  }

  static PackageInfo getPackageInfo(Context context) {
    PackageManager packageManager = context.getPackageManager();
    try {
      return packageManager.getPackageInfo(context.getPackageName(), 0);
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Package not found: " + context.getPackageName());
    }
  }

  @Private
  void recordScreenViews(Activity activity) {
    PackageManager packageManager = activity.getPackageManager();
    try {
      ActivityInfo info =
          packageManager.getActivityInfo(activity.getComponentName(), PackageManager.GET_META_DATA);
      CharSequence activityLabel = info.loadLabel(packageManager);
      //noinspection deprecation
      screen(activityLabel.toString());
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Activity Not Found: " + e.toString());
    }
  }

  @Private
  void runOnMainThread(final IntegrationOperation operation) {
    if (shutdown) {
      return;
    }
    posthogExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            HANDLER.post(
                new Runnable() {
                  @Override
                  public void run() {
                    performRun(operation);
                  }
                });
          }
        });
  }

  // PostHog API

  /** @see #identify(String, Properties, Options) */
  public void identify(@NonNull String distinctId) {
    identify(distinctId, null, null);
  }

  /** @see #identify(String, Properties, Options) */
  public void identify(@NonNull Properties userProperties) {
    identify(null, userProperties, null);
  }

  /** @see #identify(String, Properties, Options) */
  public void identify(@NonNull String distinctId, @NonNull Properties userProperties) {
    identify(distinctId, userProperties, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code distinctId}. It
   * also lets you record {@code properties} about the user, like their email, name, account type, etc.
   *
   * <p>Properties and distinctId will be automatically cached and available on future sessions for the same
   * user. To update a trait on the server, call identify with the same user id (or null). You can
   * also use {@link #identify(Properties)} for this purpose.
   *
   * @param distinctId Unique identifier which you recognize a user by in your own database. If this is
   *     null or empty, any previous id we have (could be the anonymous id) will be used.
   * @param userProperties Properties about the user.
   * @param options To configure the call.
   * @throws IllegalArgumentException if both {@code distinctId} and {@code newProperties} are not provided
   */
  public void identify(
      final @Nullable String distinctId,
      final @Nullable Properties userProperties,
      final @Nullable Options options) {
    assertNotShutdown();
    if (isNullOrEmpty(distinctId) && isNullOrEmpty(userProperties)) {
      throw new IllegalArgumentException("Either distinctId or some properties must be provided.");
    }

    posthogExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            Properties properties = propertiesCache.get();
            if (!isNullOrEmpty(distinctId)) {
              properties.putDistinctId(distinctId);
            }
            if (!isNullOrEmpty(userProperties)) {
              properties.putAll(userProperties);
            }
            propertiesCache.set(properties); // Save the new properties

            final Options finalOptions;
            if (options == null) {
              finalOptions = defaultOptions;
            } else {
              finalOptions = options;
            }

            final Properties finalUserProperties;
            if (userProperties == null) {
              finalUserProperties = EMPTY_PROPERTIES;
            } else {
              finalUserProperties = userProperties;
            }

            IdentifyPayload.Builder builder =
                new IdentifyPayload.Builder().userProperties(finalUserProperties);
            fillAndEnqueue(builder, finalOptions);
          }
        });

    // Reload feature flags whenever identity changes
    Properties properties = propertiesCache.get();
    if (properties.distinctId() != distinctId) {
      this.reloadFeatureFlags();
    }
  }

  /** @see #capture(String, Properties, Options) */
  public void capture(@NonNull String event) {
    capture(event, null, null);
  }

  /** @see #capture(String, Properties, Options) */
  public void capture(@NonNull String event, @Nullable Properties properties) {
    capture(event, properties, null);
  }

  /**
   * The capture method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions. For
   * example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @param event Name of the event. Must not be null or empty.
   * @param properties {@link Properties} to add extra information to this call.
   * @param options To configure the call.
   * @throws IllegalArgumentException if event name is null or an empty string.
   */
  public void capture(
      final @NonNull String event,
      final @Nullable Properties properties,
      @Nullable final Options options) {
    assertNotShutdown();
    if (isNullOrEmpty(event)) {
      throw new IllegalArgumentException("event must not be null or empty.");
    }

    posthogExecutor.submit(
        new Runnable() {
          @RequiresApi(api = Build.VERSION_CODES.N)
          @Override
          public void run() {
            final Options finalOptions;
            if (options == null) {
              finalOptions = defaultOptions;
            } else {
              finalOptions = options;
            }

            final Properties finalProperties;
            if (properties == null) {
              finalProperties = EMPTY_PROPERTIES;
            } else {
              finalProperties = properties;
            }

            // Send feature flags with capture call
            boolean shouldSendFeatureFlags = false;
            if (
                    options != null &&
                            !options.context().isEmpty() &&
                            options.context().get(SEND_FEATURE_FLAGS_KEY) instanceof Boolean &&
                            (Boolean) options.context().get(SEND_FEATURE_FLAGS_KEY)
            ) {
              shouldSendFeatureFlags = true;
            }
            if (shouldSendFeatureFlags) {
              ValueMap flags = featureFlags.getFlagVariants();
              List<String> activeFlags = featureFlags.getFlags();

              // Add all feature variants to event
              for (Map.Entry<String, Object> entry : flags.entrySet()) {
                finalProperties.putFeatureFlag(entry.getKey(), entry.getValue());
              }

              // Add all feature flag keys to $active_feature_flags key
              finalProperties.putActiveFeatureFlags(activeFlags);
            }

            CapturePayload.Builder builder =
                new CapturePayload.Builder().event(event).properties(finalProperties);
            fillAndEnqueue(builder, finalOptions);
          }
        });
  }

  /** @see #screen(String, Properties, Options) */
  public void screen(@Nullable String name) {
    screen(name, null, null);
  }

  /** @see #screen(String, Properties, Options) */
  public void screen(@Nullable String name, @Nullable Properties properties) {
    screen(name, properties, null);
  }

  /**
   * The screen methods let your record whenever a user sees a screen of your mobile app, and attach
   * a name, category or properties to the screen. Either category or name must be provided.
   *
   * @param name A name for the screen.
   * @param properties {@link Properties} to add extra information to this call.
   * @param options To configure the call.
   */
  public void screen(
      @Nullable final String name,
      @Nullable final Properties properties,
      @Nullable final Options options) {
    assertNotShutdown();
    if (isNullOrEmpty(name)) {
      throw new IllegalArgumentException("name must be provided.");
    }

    posthogExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Options finalOptions;
            if (options == null) {
              finalOptions = defaultOptions;
            } else {
              finalOptions = options;
            }

            final Properties finalProperties;
            if (properties == null) {
              finalProperties = EMPTY_PROPERTIES;
            } else {
              finalProperties = properties;
            }

            //noinspection deprecation
            ScreenPayload.Builder builder =
                new ScreenPayload.Builder()
                    .name(name)
                    .properties(finalProperties);
            fillAndEnqueue(builder, finalOptions);
          }
        });
  }

  /** @see #alias(String, Options) */
  public void alias(@NonNull String alias) {
    alias(alias, null);
  }

  /**
   * The alias method is used to merge two user identities, effectively connecting two sets of user
   * data as one. This is an advanced method, but it is required to manage user identities
   * successfully sometimes.
   *
   * Note that when calling identify, the anonymous ID of the user is automatically
   * aliased to the new distinct ID. You do not need to call alias manually then.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   posthog.capture("user did something");
   *   posthog.identify(distinctId);
   *   posthog.alias(anotherOldId);
   * </code> </pre>
   *
   * @param alias The existing ID you want to alias the last identified distinctId to. The distinct ID will be either
   *     the alias if you have called identify, or the anonymous ID.
   * @param options To configure the call
   * @throws IllegalArgumentException if newId is null or empty
   */
  public void alias(final @NonNull String alias, final @Nullable Options options) {
    assertNotShutdown();
    if (isNullOrEmpty(alias)) {
      throw new IllegalArgumentException("newId must not be null or empty.");
    }

    posthogExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Options finalOptions;
            if (options == null) {
              finalOptions = defaultOptions;
            } else {
              finalOptions = options;
            }

            AliasPayload.Builder builder =
                new AliasPayload.Builder()
                    .alias(alias);
            fillAndEnqueue(builder, finalOptions);
          }
        });
  }

  /** @see #group(String, String, Properties, Options) */
  public void group(@NonNull String groupType, @NonNull String groupKey) {
    group(groupType, groupKey, null, null);
  }

  /**
   * Alpha feature: don't use unless you know what you're doing!
   *
   * Sets group analytics information for subsequent events and reloads feature flags.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   posthog.group("organization", "org::5");
   * </code> </pre>
   *
   * @param groupType Group type
   * @param groupKey Group key
   * @param groupProperties Optional properties to set for group
   * @param options To configure the call
   * @throws IllegalArgumentException if groupType or groupKey is null or empty
   */
  public void group(final @NonNull String groupType, final @NonNull String groupKey, final @Nullable Properties groupProperties, final @Nullable Options options) {
    assertNotShutdown();
    if (isNullOrEmpty(groupType) || isNullOrEmpty(groupKey)) {
      throw new IllegalArgumentException("groupType and groupKey must not be null or empty.");
    }

    final ValueMap existingGroups = this.getGroups();
    ValueMap newGroups = existingGroups;
    newGroups.putValue(groupType, groupKey);
    Persistence persistence = this.persistenceCache.get();
    persistence.putGroups(newGroups);

    posthogExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Options finalOptions;
            if (options == null) {
              finalOptions = defaultOptions;
            } else {
              finalOptions = options;
            }

            final Properties finalGroupProperties;
            if (groupProperties == null) {
              finalGroupProperties = EMPTY_PROPERTIES;
            } else {
              finalGroupProperties = groupProperties;
            }

            GroupPayload.Builder builder =
                new GroupPayload.Builder().groupType(groupType).groupKey(groupKey).groupProperties(finalGroupProperties);
            fillAndEnqueue(builder, finalOptions);
          }
        });

    // If groups change, reload feature flags.
    if (existingGroups.get(groupType) != groupKey) {
        this.reloadFeatureFlags();
    }
  }

  public ValueMap getGroups() {
    ValueMap groups = this.persistenceCache.get().groups();
    if (groups != null) {
      return groups;
    }
    return new ValueMap();
  }

  // Feature Flags
  /** @see #isFeatureEnabled(String, Boolean, Map) */
  public Boolean isFeatureEnabled(@NonNull String key) {
    return isFeatureEnabled(key, false, null);
  }

  /** @see #isFeatureEnabled(String, Boolean, Map) */
  public Boolean isFeatureEnabled(@NonNull String key, @Nullable Boolean defaultValue) {
    return isFeatureEnabled(key, defaultValue, null);
  }

  /**
   * See if feature flag is enabled for user.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   if(posthog.isFeatureEnabled('beta-feature')) { // do something }
   * </code> </pre>
   *
   * @param key flag key
   * @param defaultValue default flag value
   * @param options options (optional) If {send_event: false}, we won't send an $feature_flag_call event to PostHog.
   * @throws IllegalArgumentException if key is empty
   */
  public Boolean isFeatureEnabled(final @NonNull String key, final @Nullable Boolean defaultValue, final @Nullable Map<String, Object> options) {
    assertNotShutdown();
    if (isNullOrEmpty(key)) {
      throw new IllegalArgumentException("key must not be null or empty.");
    }
    return this.featureFlags.isFeatureEnabled(key, defaultValue, options);
  }

  /** @see #getFeatureFlag(String, Object, Map) */
  public Object getFeatureFlag(@NonNull String key) {
    return getFeatureFlag(key, false, null);
  }

  /** @see #getFeatureFlag(String, Object, Map) */
  public Object getFeatureFlag(@NonNull String key, @Nullable Object defaultValue) {
    return getFeatureFlag(key, defaultValue, null);
  }


  /**
   * Get feature flag's value for user.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   if(posthog.getFeatureFlag('my-flag') === 'some-variant') { // do something }
   * </code> </pre>
   *
   * @param key flag key
   * @param defaultValue default flag value
   * @param options options (optional) If {send_event: false}, we won't send an $feature_flag_call event to PostHog.
   * @throws IllegalArgumentException if key is empty
   */
  public Object getFeatureFlag(final @NonNull String key, final @Nullable Object defaultValue, final @Nullable Map<String, Object> options) {
    assertNotShutdown();
    if (isNullOrEmpty(key)) {
      throw new IllegalArgumentException("key must not be null or empty.");
    }
    return this.featureFlags.getFeatureFlag(key, defaultValue, options);
  }

  /**
   * Reload feature flags cached in PostHog instance
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   posthog.reloadFeatureFlags()
   * </code> </pre>
   */
  public void reloadFeatureFlags() {
    this.featureFlags.reloadFeatureFlags();
  }

  private void waitForAdvertisingId() {
    try {
      advertisingIdLatch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      logger.error(e, "Thread interrupted while waiting for advertising ID.");
    }
    if (advertisingIdLatch.getCount() == 1) {
      logger.debug(
          "Advertising ID may not be collected because the API did not respond within 15 seconds.");
    }
  }

  @Private
  void fillAndEnqueue(BasePayload.Builder<?, ?> builder, Options options) {
    waitForAdvertisingId();

    PostHogContext contextCopy = new PostHogContext(posthogContext);

    for (Map.Entry<String, Object> pair : options.context().entrySet()) {
      contextCopy.put(pair.getKey(), pair.getValue());
    }

    contextCopy = contextCopy.unmodifiableCopy();

    Properties properties = propertiesCache.get();
    builder.context(contextCopy);
    builder.anonymousId(properties.anonymousId());
    String distinctId = properties.distinctId();
    if (!isNullOrEmpty(distinctId)) {
      builder.distinctId(distinctId);
    }
    enqueue(builder.build());
  }

  void enqueue(BasePayload payload) {
    if (optOut.get()) {
      return;
    }
    logger.verbose("Created payload %s.", payload);
    Middleware.Chain chain = new RealMiddlewareChain(0, payload, middlewares, this);
    chain.proceed(payload);
  }

  void run(BasePayload payload) {
    logger.verbose("Running payload %s.", payload);
    final IntegrationOperation operation;
    switch (payload.type()) {
      case identify:
        operation = IntegrationOperation.identify((IdentifyPayload) payload);
        break;
      case alias:
        operation = IntegrationOperation.alias((AliasPayload) payload);
        break;
      case capture:
        operation = IntegrationOperation.capture((CapturePayload) payload);
        break;
      case screen:
        operation = IntegrationOperation.screen((ScreenPayload) payload);
        break;
      case group:
        operation = IntegrationOperation.group((GroupPayload) payload);
        break;
      default:
        throw new AssertionError("unknown type " + payload.type());
    }
    HANDLER.post(
        new Runnable() {
          @Override
          public void run() {
            performRun(operation);
          }
        });
  }

  /**
   * Asynchronously flushes all messages in the queue to the server, and tells bundled integrations
   * to do the same.
   */
  public void flush() {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    runOnMainThread(IntegrationOperation.FLUSH);
  }

  /** Get the {@link PostHogContext} used by this instance. */
  @SuppressWarnings("UnusedDeclaration")
  public PostHogContext getPostHogContext() {
    return posthogContext;
  }

  public String getAnonymousId() {
    Properties properties = propertiesCache.get();
    return properties.anonymousId();
  }

  public String getDistinctId() {
    Properties properties = propertiesCache.get();
    return properties.distinctId();
  }

  /** Creates a {@link StatsSnapshot} of the current stats for this instance. */
  public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Return the {@link Application} used to create this instance. */
  public Application getApplication() {
    return application;
  }

  /**
   * Return the {@link LogLevel} for this instance.
   *
   * @deprecated This will be removed in a future release.
   */
  @Deprecated
  public LogLevel getLogLevel() {
    return logger.logLevel;
  }

  /**
   * Return the {@link Logger} instance used by this client.
   *
   * @deprecated This will be removed in a future release.
   */
  public Logger getLogger() {
    return logger;
  }

  /** Return a new {@link Logger} with the given sub-tag. */
  public Logger logger(String tag) {
    return logger.subLog(tag);
  }

  /**
   * Resets the posthog client by clearing any stored information about the user. Events queued on
   * disk are not cleared, and will be uploaded at a later time. Preserves BUILD and VERSION values.
   */
  public void reset() {
    SharedPreferences sharedPreferences = Utils.getPostHogSharedPreferences(application, tag);
    // LIB-1578: only remove properties, preserve BUILD and VERSION keys in order to to fix over-sending
    // of 'Application Installed' events and under-sending of 'Application Updated' events
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.remove(PROPERTIES_KEY + "-" + tag);
    editor.apply();

    propertiesCache.delete();
    propertiesCache.set(Properties.create());
    runOnMainThread(IntegrationOperation.RESET);
  }

  /**
   * Set the opt-out status for the current device and posthog client combination. This flag is
   * persisted across device reboots, so you can simply call this once during your application (such
   * as in a screen where a user can opt out of posthog capturing).
   */
  public void optOut(boolean optOut) {
    this.optOut.set(optOut);
  }

  /**
   * Stops this instance from accepting further requests. In-flight events may not be uploaded right
   * away.
   */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    application.unregisterActivityLifecycleCallbacks(activityLifecycleCallback);
    // Only supplied by us for testing, so it's ok to shut it down. If we were to make this public,
    // we'll have to add a check similar to that of PostHogNetworkExecutorService below.
    posthogExecutor.shutdown();
    if (networkExecutor instanceof PostHogNetworkExecutorService) {
      networkExecutor.shutdown();
    }
    stats.shutdown();
    shutdown = true;
    synchronized (INSTANCES) {
      INSTANCES.remove(tag);
    }
  }

  private void assertNotShutdown() {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
  }

  /** Controls the level of logging. */
  public enum LogLevel {
    /** No logging. */
    NONE,
    /** Log exceptions only. */
    INFO,
    /** Log exceptions and print debug output. */
    DEBUG,
    /** Same as {@link LogLevel#DEBUG}, and log transformations in bundled integrations. */
    VERBOSE;

    public boolean log() {
      return this != NONE;
    }
  }

  /** Fluent API for creating {@link PostHog} instances. */
  public static class Builder {
    private final Application application;
    private String apiKey;
    private String host;
    private boolean collectDeviceID = Utils.DEFAULT_COLLECT_DEVICE_ID;
    private int flushQueueSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    private long flushIntervalInMillis = Utils.DEFAULT_FLUSH_INTERVAL;
    private Options defaultOptions;
    private String tag;
    private LogLevel logLevel;
    private ExecutorService networkExecutor;
    private ExecutorService executor;
    private ConnectionFactory connectionFactory;
    private List<Middleware> middlewares;
    private boolean captureApplicationLifecycleEvents = false;
    private boolean recordScreenViews = false;
    private boolean captureDeepLinks = false;
    private Crypto crypto;
    private Integration integration;

    /** Start building a new {@link PostHog} instance. */
    public Builder(Context context, String apiKey) {
      this(context, apiKey, DEFAULT_HOST);
    }

    /** Start building a new {@link PostHog} instance. */
    public Builder(Context context, String apiKey, String host) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      if (!hasPermission(context, Manifest.permission.INTERNET)) {
        throw new IllegalArgumentException("INTERNET permission is required.");
      }
      application = (Application) context.getApplicationContext();
      if (application == null) {
        throw new IllegalArgumentException("Application context must not be null.");
      }
      if (isNullOrEmpty(apiKey)) {
        throw new IllegalArgumentException("apiKey must not be null or empty.");
      }
      this.apiKey = apiKey;
      this.host = host;
    }

    /**
     * Set the queue size at which the client should flush events. The client will automatically
     * flush events to PostHog when the queue reaches {@code flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushQueueSize is less than or equal to zero.
     */
    public Builder flushQueueSize(int flushQueueSize) {
      if (flushQueueSize <= 0) {
        throw new IllegalArgumentException("flushQueueSize must be greater than zero.");
      }
      // 250 is a reasonably high number to trigger queue size flushes.
      // The queue may go over this size (as much as 1000), but you should flush much before then.
      if (flushQueueSize > 250) {
        throw new IllegalArgumentException("flushQueueSize must be less than or equal to 250.");
      }
      this.flushQueueSize = flushQueueSize;
      return this;
    }

    /**
     * Set the interval at which the client should flush events. The client will automatically flush
     * events to PostHog every {@code flushInterval} duration, regardless of {@code flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushInterval is less than or equal to zero.
     */
    public Builder flushInterval(long flushInterval, TimeUnit timeUnit) {
      if (timeUnit == null) {
        throw new IllegalArgumentException("timeUnit must not be null.");
      }
      if (flushInterval <= 0) {
        throw new IllegalArgumentException("flushInterval must be greater than zero.");
      }
      this.flushIntervalInMillis = timeUnit.toMillis(flushInterval);
      return this;
    }

    /**
     * Enable or disable collection of {@link android.provider.Settings.Secure#ANDROID_ID}, {@link
     * android.os.Build#SERIAL} or the Telephony Identifier retrieved via TelephonyManager as
     * available. Collection of the device identifier is enabled by default.
     */
    public Builder collectDeviceId(boolean collect) {
      this.collectDeviceID = collect;
      return this;
    }

    /**
     * Set some default options for all calls. This will only be used to figure out which
     * integrations should be enabled or not for actions by default.
     *
     * @see Options
     */
    public Builder defaultOptions(Options defaultOptions) {
      if (defaultOptions == null) {
        throw new IllegalArgumentException("defaultOptions must not be null.");
      }
      // Make a defensive copy
      this.defaultOptions = new Options();
      for (Map.Entry<String, Object> entry : defaultOptions.context().entrySet()) {
        this.defaultOptions.putContext(entry.getKey(), entry.getValue());
      }
      return this;
    }

    /**
     * Set a tag for this instance. The tag is used to generate keys for caching. By default the
     * apiKey is used. You may want to specify an alternative one, if you want the instances with
     * the same apiKey to share different caches (you probably do).
     *
     * @throws IllegalArgumentException if the tag is null or empty.
     */
    public Builder tag(String tag) {
      if (isNullOrEmpty(tag)) {
        throw new IllegalArgumentException("tag must not be null or empty.");
      }
      this.tag = tag;
      return this;
    }

    /** Set a {@link LogLevel} for this instance. */
    public Builder logLevel(LogLevel logLevel) {
      if (logLevel == null) {
        throw new IllegalArgumentException("LogLevel must not be null.");
      }
      this.logLevel = logLevel;
      return this;
    }

    /**
     * Specify the executor service for making network calls in the background.
     *
     * <p>Note: Calling {@link PostHog#shutdown()} will not shutdown supplied executors.
     *
     * <p>Use it with care! http://bit.ly/1JVlA2e
     */
    public Builder networkExecutor(ExecutorService networkExecutor) {
      if (networkExecutor == null) {
        throw new IllegalArgumentException("Executor service must not be null.");
      }
      this.networkExecutor = networkExecutor;
      return this;
    }

    /**
     * Specify the connection factory for customizing how connections are created.
     *
     * <p>This is a beta API, and might be changed in the future. Use it with care!
     * http://bit.ly/1JVlA2e
     */
    public Builder connectionFactory(ConnectionFactory connectionFactory) {
      if (connectionFactory == null) {
        throw new IllegalArgumentException("ConnectionFactory must not be null.");
      }
      this.connectionFactory = connectionFactory;
      return this;
    }

    /** Specify the crypto interface for customizing how data is stored at rest. */
    public Builder crypto(Crypto crypto) {
      if (crypto == null) {
        throw new IllegalArgumentException("Crypto must not be null.");
      }
      this.crypto = crypto;
      return this;
    }

    /**
     * Automatically capture application lifecycle events, including "Application Installed",
     * "Application Updated" and "Application Opened".
     */
    public Builder captureApplicationLifecycleEvents() {
      this.captureApplicationLifecycleEvents = true;
      return this;
    }

    /** Automatically record screen calls when activities are created. */
    public Builder recordScreenViews() {
      this.recordScreenViews = true;
      return this;
    }

    /** Automatically capture deep links as part of the screen call. */
    public Builder captureDeepLinks() {
      this.captureDeepLinks = true;
      return this;
    }

    /** Add a {@link Middleware} for intercepting messages. */
    public Builder middleware(Middleware middleware) {
      assertNotNull(middleware, "middleware");
      if (middlewares == null) {
        middlewares = new ArrayList<>();
      }
      if (middlewares.contains(middleware)) {
        throw new IllegalStateException("Middleware is already registered.");
      }
      middlewares.add(middleware);
      return this;
    }

    /**
     * The executor on which payloads are dispatched asynchronously. This is not exposed publicly.
     */
    Builder executor(ExecutorService executor) {
      this.executor = assertNotNull(executor, "executor");
      return this;
    }

    /**
     * Allows custom integration
     */
    public Builder integration(Integration integration) {
      this.integration = assertNotNull(integration, "integration");
      return this;
    }

    /** Create a {@link PostHog} client. */
    public PostHog build() {
      if (isNullOrEmpty(tag)) {
        tag = apiKey;
      }
      synchronized (INSTANCES) {
        if (INSTANCES.contains(tag)) {
          throw new IllegalStateException(
              "Duplicate posthog client created with tag: "
                  + tag
                  + ". If you want to use multiple PostHog clients, use a different apiKey "
                  + "or set a tag via the builder during construction.");
        }
        INSTANCES.add(tag);
      }

      if (defaultOptions == null) {
        defaultOptions = new Options();
      }
      if (logLevel == null) {
        logLevel = LogLevel.NONE;
      }
      if (networkExecutor == null) {
        networkExecutor = new PostHogNetworkExecutorService();
      }
      if (connectionFactory == null) {
        connectionFactory = new ConnectionFactory();
      }
      if (crypto == null) {
        crypto = Crypto.none();
      }

      final Stats stats = new Stats();
      final Cartographer cartographer = Cartographer.INSTANCE;
      final Client client = new Client(apiKey, host, connectionFactory);

      BooleanPreference optOut =
          new BooleanPreference(
              getPostHogSharedPreferences(application, tag), OPT_OUT_PREFERENCE_KEY, false);

      Properties.Cache propertiesCache = new Properties.Cache(application, cartographer, tag);
      if (!propertiesCache.isSet() || propertiesCache.get() == null) {
        Properties properties = Properties.create();
        propertiesCache.set(properties);
      }

      Persistence.Cache persistenceCache = new Persistence.Cache(application, cartographer, tag);
      if (!persistenceCache.isSet() || persistenceCache.get() == null) {
        Persistence persistence = Persistence.create();
        persistenceCache.set(persistence);
      }

      Logger logger = Logger.with(logLevel);
      PostHogContext posthogContext =
          PostHogContext.create(application, propertiesCache.get(), collectDeviceID);
      CountDownLatch advertisingIdLatch = new CountDownLatch(1);
      posthogContext.attachAdvertisingId(application, advertisingIdLatch, logger);

      List<Middleware> middlewares = Utils.immutableCopyOf(this.middlewares);

      ExecutorService executor = this.executor;
      if (executor == null) {
        executor = Executors.newSingleThreadExecutor();
      }

      return new PostHog(
          application,
          networkExecutor,
          stats,
          propertiesCache,
          persistenceCache,
          posthogContext,
          defaultOptions,
          logger,
          tag,
          client,
          cartographer,
          apiKey,
          host,
          flushQueueSize,
          flushIntervalInMillis,
          executor,
          captureApplicationLifecycleEvents,
          advertisingIdLatch,
          recordScreenViews,
          captureDeepLinks,
          optOut,
          crypto,
          middlewares,
              integration,
              null);
    }
  }

  /** Runs the given operation on all integrations. */
  void performRun(IntegrationOperation operation) {
    long startTime = System.nanoTime();
    operation.run(this.integration);
    long endTime = System.nanoTime();
    long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    stats.dispatchIntegrationOperation(durationInMillis);
    logger.debug("Ran %s in %d ns.", operation, endTime - startTime);
  }

  /**
   * Previously (until version 4.1.7) shared preferences were not namespaced by a tag. This meant
   * that all posthog instances shared the same shared preferences. This migration checks if the
   * namespaced shared preferences instance contains {@code namespaceSharedPreferences: true}. If it
   * does, the migration is already run and does not need to be run again. If it doesn't, it copies
   * the legacy shared preferences mapping into the namespaced shared preferences, and sets
   * namespaceSharedPreferences to false.
   */
  private void namespaceSharedPreferences() {
    SharedPreferences newSharedPreferences = Utils.getPostHogSharedPreferences(application, tag);
    BooleanPreference namespaceSharedPreferences =
        new BooleanPreference(newSharedPreferences, "namespaceSharedPreferences", true);

    if (namespaceSharedPreferences.get()) {
      SharedPreferences legacySharedPreferences =
          application.getSharedPreferences("posthog-android", Context.MODE_PRIVATE);
      Utils.copySharedPreferences(legacySharedPreferences, newSharedPreferences);
      namespaceSharedPreferences.set(false);
    }
  }
}
