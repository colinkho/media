/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.drm;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.os.Build;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import java.util.UUID;

/**
 * A {@link CryptoConfig} for {@link C#CRYPTO_TYPE_FRAMEWORK}. Contains the necessary information to
 * build or update a framework {@link MediaCrypto} that can be used to configure a {@link
 * MediaCodec}.
 */
@UnstableApi
public final class FrameworkCryptoConfig implements CryptoConfig {

  /**
   * Whether the device needs keys to have been loaded into the {@link DrmSession} before codec
   * configuration.
   */
  public static final boolean WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC =
      "Amazon".equals(Build.MANUFACTURER)
          && ("AFTM".equals(Build.MODEL) // Fire TV Stick Gen 1
              || "AFTB".equals(Build.MODEL)); // Fire TV Gen 1

  /** The DRM scheme UUID. */
  public final UUID uuid;

  /** The DRM session id. */
  public final byte[] sessionId;

  /**
   * @deprecated Use {@link ExoMediaDrm#requiresSecureDecoder} instead, which incorporates this
   *     logic.
   */
  @Deprecated public final boolean forceAllowInsecureDecoderComponents;

  /**
   * Constructs an instance.
   *
   * @param uuid The DRM scheme UUID.
   * @param sessionId The DRM session id.
   */
  @SuppressWarnings("deprecation") // Delegating to deprecated constructor
  public FrameworkCryptoConfig(UUID uuid, byte[] sessionId) {
    this(uuid, sessionId, /* forceAllowInsecureDecoderComponents= */ false);
  }

  /**
   * @deprecated Use {@link FrameworkCryptoConfig#FrameworkCryptoConfig(UUID, byte[])} instead, and
   *     {@link ExoMediaDrm#requiresSecureDecoder} for the secure decoder handling logic.
   */
  @SuppressWarnings("deprecation") // Setting deprecated field
  @Deprecated
  public FrameworkCryptoConfig(
      UUID uuid, byte[] sessionId, boolean forceAllowInsecureDecoderComponents) {
    this.uuid = uuid;
    this.sessionId = sessionId;
    this.forceAllowInsecureDecoderComponents = forceAllowInsecureDecoderComponents;
  }
}
