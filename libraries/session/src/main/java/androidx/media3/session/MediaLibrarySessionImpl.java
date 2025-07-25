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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.session.LibraryResult.RESULT_SUCCESS;
import static androidx.media3.session.SessionError.ERROR_INVALID_STATE;
import static androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED;
import static androidx.media3.session.SessionError.ERROR_UNKNOWN;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession.ControllerCb;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.legacy.MediaSessionCompat;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/* package */ class MediaLibrarySessionImpl extends MediaSessionImpl {

  private static final String RECENT_LIBRARY_ROOT_MEDIA_ID = "androidx.media3.session.recent.root";
  private final MediaLibrarySession instance;
  private final MediaLibrarySession.Callback callback;
  private final HashMultimap<String, ControllerInfo> parentIdToSubscribedControllers;
  private final HashMultimap<ControllerCb, String> controllerToSubscribedParentIds;

  private final @MediaLibrarySession.LibraryErrorReplicationMode int libraryErrorReplicationMode;

  /** Creates an instance. */
  public MediaLibrarySessionImpl(
      MediaLibrarySession instance,
      Context context,
      String id,
      Player player,
      @Nullable PendingIntent sessionActivity,
      ImmutableList<CommandButton> customLayout,
      ImmutableList<CommandButton> mediaButtonPreferences,
      ImmutableList<CommandButton> commandButtonsForMediaItems,
      MediaLibrarySession.Callback callback,
      Bundle tokenExtras,
      Bundle sessionExtras,
      BitmapLoader bitmapLoader,
      boolean playIfSuppressed,
      boolean isPeriodicPositionUpdateEnabled,
      @MediaLibrarySession.LibraryErrorReplicationMode int libraryErrorReplicationMode) {
    super(
        instance,
        context,
        id,
        player,
        sessionActivity,
        customLayout,
        mediaButtonPreferences,
        commandButtonsForMediaItems,
        callback,
        tokenExtras,
        sessionExtras,
        bitmapLoader,
        playIfSuppressed,
        isPeriodicPositionUpdateEnabled);
    this.instance = instance;
    this.callback = callback;
    this.libraryErrorReplicationMode = libraryErrorReplicationMode;
    parentIdToSubscribedControllers = HashMultimap.create();
    controllerToSubscribedParentIds = HashMultimap.create();
  }

  @Override
  public List<ControllerInfo> getConnectedControllers() {
    List<ControllerInfo> list = super.getConnectedControllers();
    @Nullable MediaLibraryServiceLegacyStub legacyStub = getLegacyBrowserService();
    if (legacyStub == null) {
      return list;
    }
    ImmutableList<ControllerInfo> legacyControllers =
        legacyStub.getConnectedControllersManager().getConnectedControllers();
    ImmutableList.Builder<ControllerInfo> combinedList =
        ImmutableList.builderWithExpectedSize(list.size() + legacyControllers.size());
    return combinedList.addAll(list).addAll(legacyControllers).build();
  }

  @Override
  public boolean isConnected(ControllerInfo controller) {
    if (super.isConnected(controller)) {
      return true;
    }
    @Nullable MediaLibraryServiceLegacyStub legacyStub = getLegacyBrowserService();
    return legacyStub != null
        && legacyStub.getConnectedControllersManager().isConnected(controller);
  }

  public void clearReplicatedLibraryError() {
    getMediaSessionLegacyStub().clearLegacyErrorStatus();
  }

  public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRootOnHandler(
      ControllerInfo browser, @Nullable LibraryParams params) {
    if (params != null && params.isRecent && isSystemUiController(browser)) {
      // Advertise support for playback resumption, if enabled.
      return !canResumePlaybackOnStart()
          ? Futures.immediateFuture(LibraryResult.ofError(ERROR_NOT_SUPPORTED))
          : Futures.immediateFuture(
              LibraryResult.ofItem(
                  new MediaItem.Builder()
                      .setMediaId(RECENT_LIBRARY_ROOT_MEDIA_ID)
                      .setMediaMetadata(
                          new MediaMetadata.Builder()
                              .setIsBrowsable(true)
                              .setIsPlayable(false)
                              .build())
                      .build(),
                  params));
    }
    return callback.onGetLibraryRoot(instance, resolveControllerInfoForCallback(browser), params);
  }

  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildrenOnHandler(
      ControllerInfo browser,
      String parentId,
      int page,
      int pageSize,
      @Nullable LibraryParams params) {
    if (Objects.equals(parentId, RECENT_LIBRARY_ROOT_MEDIA_ID)) {
      if (!canResumePlaybackOnStart()) {
        return Futures.immediateFuture(LibraryResult.ofError(ERROR_NOT_SUPPORTED));
      }
      // Advertise support for playback resumption. If STATE_IDLE, the request arrives at boot time
      // to get the full item data to build a notification. If not STATE_IDLE we don't need to
      // deliver the full media item, so we do the minimal viable effort.
      return getPlayerWrapper().getPlaybackState() == Player.STATE_IDLE
          ? getRecentMediaItemAtDeviceBootTime(browser, params)
          : Futures.immediateFuture(
              LibraryResult.ofItemList(
                  ImmutableList.of(
                      new MediaItem.Builder()
                          .setMediaId("androidx.media3.session.recent.item")
                          .setMediaMetadata(
                              new MediaMetadata.Builder()
                                  .setIsBrowsable(false)
                                  .setIsPlayable(true)
                                  .build())
                          .build()),
                  params));
    }
    ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> future =
        callback.onGetChildren(
            instance, resolveControllerInfoForCallback(browser), parentId, page, pageSize, params);
    future.addListener(
        () -> {
          @Nullable LibraryResult<ImmutableList<MediaItem>> result = tryGetFutureResult(future);
          if (result != null) {
            maybeUpdateLegacyErrorState(browser, result);
            verifyResultItems(result, pageSize);
          }
        },
        this::postOrRunOnApplicationHandler);
    return future;
  }

  public ListenableFuture<LibraryResult<MediaItem>> onGetItemOnHandler(
      ControllerInfo browser, String mediaId) {
    ListenableFuture<LibraryResult<MediaItem>> future =
        callback.onGetItem(instance, resolveControllerInfoForCallback(browser), mediaId);
    future.addListener(
        () -> {
          @Nullable LibraryResult<MediaItem> result = tryGetFutureResult(future);
          if (result != null) {
            maybeUpdateLegacyErrorState(browser, result);
          }
        },
        this::postOrRunOnApplicationHandler);
    return future;
  }

  public ListenableFuture<LibraryResult<Void>> onSubscribeOnHandler(
      ControllerInfo browser, String parentId, @Nullable LibraryParams params) {

    ControllerCb controllerCb = checkNotNull(browser.getControllerCb());
    controllerToSubscribedParentIds.put(controllerCb, parentId);
    parentIdToSubscribedControllers.put(parentId, browser);

    // Call callbacks after adding it to the subscription list because library session may want
    // to call notifyChildrenChanged() in the callback.
    //
    // onSubscribe is defined to return a non-null result but it's implemented by applications,
    // so we explicitly null-check the result to fail early if an app accidentally returns null.
    ListenableFuture<LibraryResult<Void>> future =
        checkNotNull(
            callback.onSubscribe(
                instance, resolveControllerInfoForCallback(browser), parentId, params),
            "onSubscribe must return non-null future");

    future.addListener(
        () -> {
          @Nullable LibraryResult<Void> result = tryGetFutureResult(future);
          if (result == null || result.resultCode != RESULT_SUCCESS) {
            // Remove subscription in case of an error.
            removeSubscription(browser, parentId);
          }
        },
        this::postOrRunOnApplicationHandler);

    return future;
  }

  public ImmutableList<ControllerInfo> getSubscribedControllers(String mediaId) {
    return ImmutableList.copyOf(parentIdToSubscribedControllers.get(mediaId));
  }

  private boolean isSubscribed(ControllerCb controllerCb, String parentId) {
    return controllerToSubscribedParentIds.containsEntry(controllerCb, parentId);
  }

  public ListenableFuture<LibraryResult<Void>> onUnsubscribeOnHandler(
      ControllerInfo browser, String parentId) {
    ListenableFuture<LibraryResult<Void>> future =
        callback.onUnsubscribe(instance, resolveControllerInfoForCallback(browser), parentId);
    future.addListener(
        () -> removeSubscription(browser, parentId), this::postOrRunOnApplicationHandler);
    return future;
  }

  public void notifyChildrenChanged(
      String parentId, int itemCount, @Nullable LibraryParams params) {
    List<ControllerInfo> connectedControllers = instance.getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      notifyChildrenChanged(connectedControllers.get(i), parentId, itemCount, params);
    }
  }

  public void notifyChildrenChanged(
      ControllerInfo browser, String parentId, int itemCount, @Nullable LibraryParams params) {
    if (isMediaNotificationControllerConnected() && isMediaNotificationController(browser)) {
      ControllerInfo systemUiBrowser = getSystemUiControllerInfo();
      if (systemUiBrowser == null) {
        return;
      }
      browser = systemUiBrowser;
    }
    dispatchRemoteControllerTaskWithoutReturn(
        browser,
        (callback, seq) -> {
          if (!isSubscribed(callback, parentId)) {
            return;
          }
          callback.onChildrenChanged(seq, parentId, itemCount, params);
        });
  }

  public ListenableFuture<LibraryResult<Void>> onSearchOnHandler(
      ControllerInfo browser, String query, @Nullable LibraryParams params) {
    ListenableFuture<LibraryResult<Void>> future =
        callback.onSearch(instance, resolveControllerInfoForCallback(browser), query, params);
    future.addListener(
        () -> {
          @Nullable LibraryResult<Void> result = tryGetFutureResult(future);
          if (result != null) {
            maybeUpdateLegacyErrorState(browser, result);
          }
        },
        this::postOrRunOnApplicationHandler);
    return future;
  }

  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResultOnHandler(
      ControllerInfo browser,
      String query,
      int page,
      int pageSize,
      @Nullable LibraryParams params) {
    ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> future =
        callback.onGetSearchResult(
            instance, resolveControllerInfoForCallback(browser), query, page, pageSize, params);
    future.addListener(
        () -> {
          @Nullable LibraryResult<ImmutableList<MediaItem>> result = tryGetFutureResult(future);
          if (result != null) {
            maybeUpdateLegacyErrorState(browser, result);
            verifyResultItems(result, pageSize);
          }
        },
        this::postOrRunOnApplicationHandler);
    return future;
  }

  public void notifySearchResultChanged(
      ControllerInfo browser, String query, int itemCount, @Nullable LibraryParams params) {
    if (isMediaNotificationControllerConnected() && isMediaNotificationController(browser)) {
      ControllerInfo systemUiBrowser = getSystemUiControllerInfo();
      if (systemUiBrowser == null) {
        return;
      }
      browser = systemUiBrowser;
    }
    dispatchRemoteControllerTaskWithoutReturn(
        browser, (callback, seq) -> callback.onSearchResultChanged(seq, query, itemCount, params));
  }

  @Override
  public void onDisconnectedOnHandler(ControllerInfo controller) {
    ControllerCb controllerCb = checkNotNull(controller.getControllerCb());
    Set<String> subscriptions = controllerToSubscribedParentIds.get(controllerCb);
    for (String parentId : ImmutableSet.copyOf(subscriptions)) {
      removeSubscription(controller, parentId);
    }
    super.onDisconnectedOnHandler(controller);
  }

  @Override
  @Nullable
  protected MediaLibraryServiceLegacyStub getLegacyBrowserService() {
    return (MediaLibraryServiceLegacyStub) super.getLegacyBrowserService();
  }

  @Override
  protected MediaSessionServiceLegacyStub createLegacyBrowserService(
      MediaSessionCompat.Token compatToken) {
    MediaLibraryServiceLegacyStub stub = new MediaLibraryServiceLegacyStub(this);
    stub.initialize(compatToken);
    return stub;
  }

  @Override
  protected void dispatchRemoteControllerTaskWithoutReturn(RemoteControllerTask task) {
    super.dispatchRemoteControllerTaskWithoutReturn(task);
    @Nullable MediaLibraryServiceLegacyStub legacyStub = getLegacyBrowserService();
    if (legacyStub != null) {
      try {
        task.run(legacyStub.getBrowserLegacyCbForBroadcast(), /* seq= */ 0);
      } catch (RemoteException e) {
        Log.e(TAG, "Exception in using media1 API", e);
      }
    }
  }

  private void maybeUpdateLegacyErrorState(ControllerInfo browser, LibraryResult<?> result) {
    if (libraryErrorReplicationMode == MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_NONE
        || browser.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION) {
      return;
    }
    if (isReplicationErrorCode(result.resultCode)) {
      getMediaSessionLegacyStub()
          .setLegacyError(
              result,
              /* isFatal= */ libraryErrorReplicationMode
                  == MediaLibraryService.MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL);
    }
    if (result.resultCode == RESULT_SUCCESS) {
      getMediaSessionLegacyStub().clearLegacyErrorStatus();
    }
  }

  private boolean isReplicationErrorCode(@LibraryResult.Code int resultCode) {
    return resultCode == LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED
        || resultCode == LibraryResult.RESULT_ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED;
  }

  @Nullable
  private static <T> T tryGetFutureResult(Future<T> future) {
    checkState(future.isDone());
    try {
      return future.get();
    } catch (CancellationException | ExecutionException | InterruptedException e) {
      Log.w(TAG, "Library operation failed", e);
      return null;
    }
  }

  private static void verifyResultItems(
      LibraryResult<ImmutableList<MediaItem>> result, int pageSize) {
    if (result.resultCode == RESULT_SUCCESS) {
      List<MediaItem> items = checkNotNull(result.value);
      if (items.size() > pageSize) {
        throw new IllegalStateException("Invalid size=" + items.size() + ", pageSize=" + pageSize);
      }
    }
  }

  private void removeSubscription(ControllerInfo controllerInfo, String parentId) {
    ControllerCb controllerCb = checkNotNull(controllerInfo.getControllerCb());
    parentIdToSubscribedControllers.remove(parentId, controllerInfo);
    controllerToSubscribedParentIds.remove(controllerCb, parentId);
  }

  private void postOrRunOnApplicationHandler(Runnable runnable) {
    Util.postOrRun(getApplicationHandler(), runnable);
  }

  private ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
      getRecentMediaItemAtDeviceBootTime(
          ControllerInfo controller, @Nullable LibraryParams params) {
    SettableFuture<LibraryResult<ImmutableList<MediaItem>>> settableFuture =
        SettableFuture.create();
    controller =
        isMediaNotificationControllerConnected()
            ? checkNotNull(getMediaNotificationControllerInfo())
            : controller;
    ListenableFuture<MediaSession.MediaItemsWithStartPosition> future =
        callback.onPlaybackResumption(instance, controller, /* isForPlayback= */ false);
    Futures.addCallback(
        future,
        new FutureCallback<MediaSession.MediaItemsWithStartPosition>() {
          @Override
          public void onSuccess(MediaSession.MediaItemsWithStartPosition playlist) {
            if (playlist.mediaItems.isEmpty()) {
              settableFuture.set(LibraryResult.ofError(ERROR_INVALID_STATE, params));
              return;
            }
            int sanitizedStartIndex =
                max(0, min(playlist.startIndex, playlist.mediaItems.size() - 1));
            settableFuture.set(
                LibraryResult.ofItemList(
                    ImmutableList.of(playlist.mediaItems.get(sanitizedStartIndex)), params));
          }

          @Override
          public void onFailure(Throwable t) {
            settableFuture.set(LibraryResult.ofError(ERROR_UNKNOWN, params));
            Log.e(TAG, "Failed fetching recent media item at boot time: " + t.getMessage(), t);
          }
        },
        MoreExecutors.directExecutor());
    return settableFuture;
  }
}
