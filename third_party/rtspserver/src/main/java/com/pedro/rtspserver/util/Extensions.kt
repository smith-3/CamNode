package com.pedro.rtspserver.util

import android.media.MediaCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.common.isKeyframe

/**
 * Created by pedro on 28/10/24.
 */

/** [timestampUs] is already on the client's timeline (see ClientTimeline). */
fun MediaCodec.BufferInfo.toMediaFrameInfo(timestampUs: Long) = MediaFrame.Info(offset, size, timestampUs, isKeyframe())