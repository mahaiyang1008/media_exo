/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.media3.session;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession.Token;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.session.legacy.LegacyParcelableUtil;
import androidx.media3.session.legacy.MediaBrowserServiceCompat;
import androidx.media3.session.legacy.MediaControllerCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

/**
 * A token that represents an ongoing {@link MediaSession} or a service ({@link
 * MediaSessionService}, {@link MediaLibraryService}, or {@code
 * androix.media.MediaBrowserServiceCompat}). If it represents a service, it may not be ongoing.
 *
 * <p>This may be passed to apps by the session owner to allow them to create a {@link
 * MediaController} or a {@link MediaBrowser} to communicate with the session.
 *
 * <p>It can also be obtained by {@link #getAllServiceTokens(Context)}.
 */
// New version of MediaSession.Token for following reasons
//   - Stop implementing Parcelable for updatable support
//   - Represent session and library service (formerly browser service) in one class.
//     Previously MediaSession.Token was for session and ComponentName was for service.
//     This helps controller apps to keep target of dispatching media key events in uniform way.
//     For details about the reason, see following. (Android O+)
//         android.media.session.MediaSessionManager.Callback#onAddressedPlayerChanged
public final class SessionToken {

  static {
    MediaLibraryInfo.registerModule("media3.session");
  }

  private static final long WAIT_TIME_MS_FOR_SESSION3_TOKEN = 500;

  /** Types of {@link SessionToken}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(value = {TYPE_SESSION, TYPE_SESSION_SERVICE, TYPE_LIBRARY_SERVICE})
  public @interface TokenType {}

  /** Type for {@link MediaSession}. */
  public static final int TYPE_SESSION = 0;

  /** Type for {@link MediaSessionService}. */
  public static final int TYPE_SESSION_SERVICE = 1;

  /** Type for {@link MediaLibraryService}. */
  public static final int TYPE_LIBRARY_SERVICE = 2;

  /** Type for {@link MediaSessionCompat}. */
  /* package */ static final int TYPE_SESSION_LEGACY = 100;

  /** Type for {@code androidx.media.MediaBrowserServiceCompat}. */
  /* package */ static final int TYPE_BROWSER_SERVICE_LEGACY = 101;

  /**
   * {@linkplain #getSessionVersion() Session version} for a platform {@link
   * android.media.session.MediaSession} or legacy {@code
   * android.support.v4.media.session.MediaSessionCompat}.
   */
  public static final int PLATFORM_SESSION_VERSION = 0;

  /**
   * Unknown {@linkplain #getSessionVersion() session version} for a {@link MediaSession} that isn't
   * connected yet.
   *
   * <p>Note: Use {@link MediaController#getConnectedToken()} to obtain the version after connecting
   * a controller.
   */
  public static final int UNKNOWN_SESSION_VERSION = 1_000_000;

  /**
   * Unknown {@linkplain #getInterfaceVersion() interface version} for a {@link MediaSession} that
   * isn't connected yet, for an older session that didn't publish its interface version, for a
   * platform {@link android.media.session.MediaSession} or for a legacy {@code
   * android.support.v4.media.session.MediaSessionCompat}.
   *
   * <p>Note: Use {@link MediaController#getConnectedToken()} to obtain the version after connecting
   * a controller.
   */
  @UnstableApi public static final int UNKNOWN_INTERFACE_VERSION = 0;

  private final SessionTokenImpl impl;

  /**
   * Creates a token for {@link MediaController} or {@link MediaBrowser} to connect to one of {@link
   * MediaSessionService}, {@link MediaLibraryService}, or {@code
   * androidx.media.MediaBrowserServiceCompat}.
   *
   * @param context The context.
   * @param serviceComponent The component name of the service.
   */
  public SessionToken(Context context, ComponentName serviceComponent) {
    checkNotNull(context, "context must not be null");
    checkNotNull(serviceComponent, "serviceComponent must not be null");
    PackageManager manager = context.getPackageManager();
    int uid = getUid(manager, serviceComponent.getPackageName());

    int type;
    if (isInterfaceDeclared(manager, MediaLibraryService.SERVICE_INTERFACE, serviceComponent)) {
      type = TYPE_LIBRARY_SERVICE;
    } else if (isInterfaceDeclared(
        manager, MediaSessionService.SERVICE_INTERFACE, serviceComponent)) {
      type = TYPE_SESSION_SERVICE;
    } else if (isInterfaceDeclared(
        manager, MediaBrowserServiceCompat.SERVICE_INTERFACE, serviceComponent)) {
      type = TYPE_BROWSER_SERVICE_LEGACY;
    } else {
      throw new IllegalArgumentException(
          "Failed to resolve SessionToken for "
              + serviceComponent
              + ". Manifest doesn't declare one of either MediaSessionService, MediaLibraryService,"
              + " MediaBrowserService or MediaBrowserServiceCompat. Use service's full name.");
    }
    if (type != TYPE_BROWSER_SERVICE_LEGACY) {
      impl = new SessionTokenImplBase(serviceComponent, uid, type);
    } else {
      impl = new SessionTokenImplLegacy(serviceComponent, uid);
    }
  }

  /** Creates a session token connected to a Media3 session. */
  /* package */ SessionToken(
      int uid,
      int type,
      int libraryVersion,
      int interfaceVersion,
      String packageName,
      IMediaSession iSession,
      Bundle tokenExtras,
      @Nullable Token platformToken) {
    impl =
        new SessionTokenImplBase(
            uid,
            type,
            libraryVersion,
            interfaceVersion,
            packageName,
            iSession,
            tokenExtras,
            platformToken);
  }

  /** Creates a session token connected to a legacy media session. */
  private SessionToken(MediaSessionCompat.Token token, String packageName, int uid, Bundle extras) {
    this.impl = new SessionTokenImplLegacy(token, packageName, uid, extras);
  }

  private SessionToken(Bundle bundle, @Nullable Token platformToken) {
    checkArgument(bundle.containsKey(FIELD_IMPL_TYPE), "Impl type needs to be set.");
    @SessionTokenImplType int implType = bundle.getInt(FIELD_IMPL_TYPE);
    Bundle implBundle = checkNotNull(bundle.getBundle(FIELD_IMPL));
    if (implType == IMPL_TYPE_BASE) {
      impl = SessionTokenImplBase.fromBundle(implBundle, platformToken);
    } else {
      impl = SessionTokenImplLegacy.fromBundle(implBundle);
    }
  }

  @Override
  public int hashCode() {
    return impl.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof SessionToken)) {
      return false;
    }
    SessionToken other = (SessionToken) obj;
    return impl.equals(other.impl);
  }

  @Override
  public String toString() {
    return impl.toString();
  }

  /**
   * Returns the UID of the session process, or {@link C#INDEX_UNSET} if the UID can't be determined
   * due to missing <a href="https://developer.android.com/training/package-visibility">package
   * visibility</a>.
   */
  public int getUid() {
    return impl.getUid();
  }

  /** Returns the package name of the session */
  public String getPackageName() {
    return impl.getPackageName();
  }

  /**
   * Returns the service name of the session. It will be an empty string if the {@link #getType()
   * type} is {@link #TYPE_SESSION}.
   */
  public String getServiceName() {
    return impl.getServiceName();
  }

  /**
   * Returns the component name of the session. It will be {@code null} if the {@link #getType()
   * type} is {@link #TYPE_SESSION}.
   */
  @Nullable
  /* package */ ComponentName getComponentName() {
    return impl.getComponentName();
  }

  /**
   * Returns the type of this token. One of {@link #TYPE_SESSION}, {@link #TYPE_SESSION_SERVICE}, or
   * {@link #TYPE_LIBRARY_SERVICE}.
   */
  public @TokenType int getType() {
    return impl.getType();
  }

  /**
   * Returns the library version of the session, {@link #UNKNOWN_SESSION_VERSION}, or {@link
   * #PLATFORM_SESSION_VERSION}.
   *
   * <ul>
   *   <li>If the session is a platform {@link android.media.session.MediaSession} or legacy {@code
   *       android.support.v4.media.session.MediaSessionCompat}, this will be {@link
   *       #PLATFORM_SESSION_VERSION}.
   *   <li>If the token's {@link #getType() type} is {@link #TYPE_SESSION}, this will be the same as
   *       {@link MediaLibraryInfo#VERSION_INT} of the session.
   *   <li>If the token's {@link #getType() type} is {@link #TYPE_SESSION_SERVICE} or {@link
   *       #TYPE_LIBRARY_SERVICE}, this will be {@link #UNKNOWN_SESSION_VERSION}. You can obtain the
   *       actual session version after a connecting a controller via the {@linkplain
   *       MediaController#getConnectedToken() connected token} of type {@link #TYPE_SESSION}.
   *   <li>
   * </ul>
   */
  public int getSessionVersion() {
    return impl.getLibraryVersion();
  }

  /** Returns the interface version of the session or {@link #UNKNOWN_INTERFACE_VERSION}. */
  @UnstableApi
  public int getInterfaceVersion() {
    return impl.getInterfaceVersion();
  }

  /**
   * Returns the extra {@link Bundle} of this token.
   *
   * @see MediaSession.Builder#setExtras(Bundle)
   */
  public Bundle getExtras() {
    return impl.getExtras();
  }

  /* package */ boolean isLegacySession() {
    return impl.isLegacySession();
  }

  @Nullable
  /* package */ Object getBinder() {
    return impl.getBinder();
  }

  @Nullable /* package */
  Token getPlatformToken() {
    return impl.getPlatformToken();
  }

  /**
   * Creates a token from a platform {@link Token}.
   *
   * @param context A {@link Context}.
   * @param token The platform {@link Token}.
   * @return A {@link ListenableFuture} for the {@link SessionToken}.
   */
  public static ListenableFuture<SessionToken> createSessionToken(Context context, Token token) {
    return createSessionToken(context, MediaSessionCompat.Token.fromToken(token));
  }

  /**
   * Creates a token from a platform {@link Token} or {@code
   * android.support.v4.media.session.MediaSessionCompat.Token}.
   *
   * @param context A {@link Context}.
   * @param token The {@link Token} or {@code
   *     android.support.v4.media.session.MediaSessionCompat.Token}.
   * @return A {@link ListenableFuture} for the {@link SessionToken}.
   */
  @UnstableApi
  public static ListenableFuture<SessionToken> createSessionToken(
      Context context, Parcelable token) {
    return createSessionToken(context, createCompatToken(token));
  }

  /**
   * Creates a token from a platform {@link Token}.
   *
   * @param context A {@link Context}.
   * @param token The platform {@link Token}.
   * @param completionLooper The {@link Looper} on which the returned {@link ListenableFuture}
   *     completes. This {@link Looper} can't be used to call {@code future.get()} on the returned
   *     {@link ListenableFuture}.
   * @return A {@link ListenableFuture} for the {@link SessionToken}.
   */
  @UnstableApi
  public static ListenableFuture<SessionToken> createSessionToken(
      Context context, Token token, Looper completionLooper) {
    return createSessionToken(context, MediaSessionCompat.Token.fromToken(token), completionLooper);
  }

  /**
   * Creates a token from a platform {@link Token} or {@code
   * android.support.v4.media.session.MediaSessionCompat.Token}.
   *
   * @param context A {@link Context}.
   * @param token The {@link Token} or {@code
   *     android.support.v4.media.session.MediaSessionCompat.Token}.
   * @param completionLooper The {@link Looper} on which the returned {@link ListenableFuture}
   *     completes. This {@link Looper} can't be used to call {@code future.get()} on the returned
   *     {@link ListenableFuture}.
   * @return A {@link ListenableFuture} for the {@link SessionToken}.
   */
  @UnstableApi
  public static ListenableFuture<SessionToken> createSessionToken(
      Context context, Parcelable token, Looper completionLooper) {
    return createSessionToken(context, createCompatToken(token), completionLooper);
  }

  private static ListenableFuture<SessionToken> createSessionToken(
      Context context, MediaSessionCompat.Token compatToken) {
    HandlerThread thread = new HandlerThread("SessionTokenThread");
    thread.start();
    ListenableFuture<SessionToken> tokenFuture =
        createSessionToken(context, compatToken, thread.getLooper());
    tokenFuture.addListener(thread::quit, MoreExecutors.directExecutor());
    return tokenFuture;
  }

  private static ListenableFuture<SessionToken> createSessionToken(
      Context context, MediaSessionCompat.Token compatToken, Looper completionLooper) {
    checkNotNull(context, "context must not be null");
    checkNotNull(compatToken, "compatToken must not be null");

    SettableFuture<SessionToken> future = SettableFuture.create();
    // Try retrieving media3 token by connecting to the session.
    MediaControllerCompat controller = new MediaControllerCompat(context, compatToken);
    String packageName = checkNotNull(controller.getPackageName());
    Handler handler = new Handler(completionLooper);
    Runnable createFallbackLegacyToken =
        () -> {
          int uid = getUid(context.getPackageManager(), packageName);
          SessionToken resultToken =
              new SessionToken(compatToken, packageName, uid, controller.getSessionInfo());
          future.set(resultToken);
        };
    // Post creating a fallback token if the command receives no result after a timeout.
    handler.postDelayed(createFallbackLegacyToken, WAIT_TIME_MS_FOR_SESSION3_TOKEN);
    controller.sendCommand(
        MediaConstants.SESSION_COMMAND_REQUEST_SESSION3_TOKEN,
        /* params= */ null,
        new ResultReceiver(handler) {
          @Override
          protected void onReceiveResult(int resultCode, Bundle resultData) {
            // Remove timeout callback.
            handler.removeCallbacksAndMessages(null);
            try {
              future.set(SessionToken.fromBundle(resultData, compatToken.getToken()));
            } catch (RuntimeException e) {
              // Fallback to a legacy token if we receive an unexpected result, e.g. a legacy
              // session acknowledging commands by a success callback.
              createFallbackLegacyToken.run();
            }
          }
        });
    return future;
  }

  /**
   * Returns an {@link ImmutableSet} of {@linkplain SessionToken session tokens} for media session
   * services; {@link MediaSessionService}, {@link MediaLibraryService}, and {@link
   * androidx.media.MediaBrowserServiceCompat} regardless of their activeness.
   *
   * <p>The app targeting API level 30 or higher must include a {@code <queries>} element in their
   * manifest to get service tokens of other apps. See the following example and <a
   * href="//developer.android.com/training/package-visibility">this guide</a> for more information.
   *
   * <pre>{@code
   * <intent>
   *   <action android:name="androidx.media3.session.MediaSessionService" />
   * </intent>
   * <intent>
   *   <action android:name="androidx.media3.session.MediaLibraryService" />
   * </intent>
   * <intent>
   *   <action android:name="android.media.browse.MediaBrowserService" />
   * </intent>
   * }</pre>
   */
  // We ask the app to declare the <queries> tags, so it's expected that they are missing.
  @SuppressWarnings("QueryPermissionsNeeded")
  public static ImmutableSet<SessionToken> getAllServiceTokens(Context context) {
    PackageManager pm = context.getPackageManager();
    List<ResolveInfo> services = new ArrayList<>();
    // If multiple actions are declared for a service, browser gets higher priority.
    List<ResolveInfo> libraryServices =
        pm.queryIntentServices(
            new Intent(MediaLibraryService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
    if (libraryServices != null) {
      services.addAll(libraryServices);
    }
    List<ResolveInfo> sessionServices =
        pm.queryIntentServices(
            new Intent(MediaSessionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
    if (sessionServices != null) {
      services.addAll(sessionServices);
    }
    List<ResolveInfo> browserServices =
        pm.queryIntentServices(
            new Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
    if (browserServices != null) {
      services.addAll(browserServices);
    }

    ImmutableSet.Builder<SessionToken> sessionServiceTokens = ImmutableSet.builder();
    for (ResolveInfo service : services) {
      if (service == null || service.serviceInfo == null) {
        continue;
      }
      ServiceInfo serviceInfo = service.serviceInfo;
      SessionToken token =
          new SessionToken(context, new ComponentName(serviceInfo.packageName, serviceInfo.name));
      sessionServiceTokens.add(token);
    }
    return sessionServiceTokens.build();
  }

  private static MediaSessionCompat.Token createCompatToken(
      Parcelable platformOrLegacyCompatToken) {
    if (platformOrLegacyCompatToken instanceof Token) {
      return MediaSessionCompat.Token.fromToken((Token) platformOrLegacyCompatToken);
    }
    // Assume this is an android.support.v4.media.session.MediaSessionCompat.Token.
    return LegacyParcelableUtil.convert(
        platformOrLegacyCompatToken, MediaSessionCompat.Token.CREATOR);
  }

  // We ask the app to declare the <queries> tags, so it's expected that they are missing.
  @SuppressWarnings("QueryPermissionsNeeded")
  private static boolean isInterfaceDeclared(
      PackageManager manager, String serviceInterface, ComponentName serviceComponent) {
    Intent serviceIntent = new Intent(serviceInterface);
    // Use queryIntentServices to find services with MediaLibraryService.SERVICE_INTERFACE.
    // We cannot use resolveService with intent specified class name, because resolveService
    // ignores actions if Intent.setClassName() is specified.
    serviceIntent.setPackage(serviceComponent.getPackageName());

    List<ResolveInfo> list =
        manager.queryIntentServices(serviceIntent, PackageManager.GET_META_DATA);
    if (list != null) {
      for (int i = 0; i < list.size(); i++) {
        ResolveInfo resolveInfo = list.get(i);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
          continue;
        }
        if (TextUtils.equals(resolveInfo.serviceInfo.name, serviceComponent.getClassName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static int getUid(PackageManager manager, String packageName) {
    try {
      return manager.getApplicationInfo(packageName, 0).uid;
    } catch (PackageManager.NameNotFoundException e) {
      return C.INDEX_UNSET;
    }
  }

  /* package */ interface SessionTokenImpl {

    boolean isLegacySession();

    int getUid();

    String getPackageName();

    String getServiceName();

    @Nullable
    ComponentName getComponentName();

    @TokenType
    int getType();

    int getLibraryVersion();

    int getInterfaceVersion();

    Bundle getExtras();

    @Nullable
    Object getBinder();

    Bundle toBundle();

    @Nullable
    Token getPlatformToken();
  }

  private static final String FIELD_IMPL_TYPE = Util.intToStringMaxRadix(0);
  private static final String FIELD_IMPL = Util.intToStringMaxRadix(1);

  /** Types of {@link SessionTokenImpl} */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({IMPL_TYPE_BASE, IMPL_TYPE_LEGACY})
  private @interface SessionTokenImplType {}

  private static final int IMPL_TYPE_BASE = 0;
  private static final int IMPL_TYPE_LEGACY = 1;

  @UnstableApi
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    if (impl instanceof SessionTokenImplBase) {
      bundle.putInt(FIELD_IMPL_TYPE, IMPL_TYPE_BASE);
    } else {
      bundle.putInt(FIELD_IMPL_TYPE, IMPL_TYPE_LEGACY);
    }
    bundle.putBundle(FIELD_IMPL, impl.toBundle());
    return bundle;
  }

  /** Restores a {@code SessionToken} from a {@link Bundle}. */
  @UnstableApi
  public static SessionToken fromBundle(Bundle bundle) {
    return new SessionToken(bundle, /* platformToken= */ null);
  }

  /**
   * Restores a {@code SessionToken} from a {@link Bundle}, setting the provided {@code
   * platformToken} if not already set.
   */
  private static SessionToken fromBundle(Bundle bundle, Token platformToken) {
    return new SessionToken(bundle, platformToken);
  }
}
