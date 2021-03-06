package fi.aalto.legroup.zop.playback.utilities;

import android.content.Context;
import android.graphics.Matrix;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;

import java.io.File;

import javax.annotation.Nullable;

import fi.aalto.legroup.zop.entities.Video;

import static android.media.MediaCodec.CryptoException;
import static com.google.android.exoplayer.MediaCodecTrackRenderer.DecoderInitializationException;

/**
 * Reads a video's rotation and transforms the given surface texture accordingly. This is an ugly
 * but working attempt to fix a bug in ExoPlayer: https://github.com/google/ExoPlayer/issues/91
 *
 * TODO: Get rid of this when the issue is fixed.
 *
 * On API 16 and below:
 *   - Video orientation must be read using a third-party library, e.g. mp4parser
 *
 * On API 17 and above:
 *   - Video orientation can be read using MediaMetadataRetriever
 *
 * On API 20 and below:
 *   - ExoPlayer does not rotate the video automatically
 *
 * On API 21 and above:
 *   - ExoPlayer rotates the video automatically
 *
 * On all API levels:
 *   - Reported video dimensions in onVideoSizeChanged must be swapped if the video is portrait
 */
public final class VideoOrientationPatcher implements MediaCodecVideoTrackRenderer.EventListener,
        View.OnLayoutChangeListener {

    private Context context;
    private MediaCodecVideoTrackRenderer.EventListener delegate;

    private int rotationDegrees = 0;
    private boolean isPortrait = false;

    @Nullable
    private TextureView view;

    public VideoOrientationPatcher(Context context,
                                   MediaCodecVideoTrackRenderer.EventListener delegate) {
        this.context = context.getApplicationContext();
        this.delegate = delegate;
    }

    public void updateOrientation(Video video) {

        Uri videoUri = video.getVideoUri();

        int videoRotation = video.getRotation();

        if (videoRotation != -1) {

            // Prefer Video manifest rotation data
            rotationDegrees = videoRotation;

        } else if (videoUri != null) {

            // Assuming that URIs without a scheme are local.
            String scheme = videoUri.getScheme();
            boolean isLocal = (scheme == null || scheme.equalsIgnoreCase("file"));

            if (isLocal) {
                File file = new File(videoUri.getPath());
                rotationDegrees = VideoOrientationReader.readOrientation(context, file);
            }
        }

        isPortrait = (rotationDegrees == 90 || rotationDegrees == 270);
    }

    public void setView(@Nullable TextureView view) {
        if (this.view != null) {
            this.view.removeOnLayoutChangeListener(this);
        }

        if (view != null) {
            view.addOnLayoutChangeListener(this);
        }

        this.view = view;
    }

    @Override
    public void onDroppedFrames(int count, long elapsed) {
        delegate.onDroppedFrames(count, elapsed);
    }

    @Override
    @SuppressWarnings("SuspiciousNameCombination")
    public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
        // Reverse the reported width and height if the video is portrait
        if (isPortrait) {
            delegate.onVideoSizeChanged(height, width, pixelWidthHeightRatio);
        } else {
            delegate.onVideoSizeChanged(width, height, pixelWidthHeightRatio);
        }
    }

    @Override
    public void onDrawnToSurface(Surface surface) {
        delegate.onDrawnToSurface(surface);
    }

    @Override
    public void onDecoderInitializationError(DecoderInitializationException exception) {
        delegate.onDecoderInitializationError(exception);
    }

    @Override
    public void onCryptoError(CryptoException exception) {
        delegate.onCryptoError(exception);
    }

    @Override
    public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {

    }

    /**
     * Rotates the video when the TextureView's layout bounds change.
     * Video dimensions are preserved.
     */
    @Override
    public void onLayoutChange(View changedView, int left, int top, int right, int bottom,
                               int oldLeft, int oldTop, int oldRight, int oldBottom) {

        if (rotationDegrees == -1 || view == null || changedView != view) {
            return;
        }

        Matrix transform = new Matrix();

        int width = right - left;
        int height = bottom - top;

        float pivotX = width / 2f;
        float pivotY = height / 2f;

        transform.postRotate(rotationDegrees, pivotX, pivotY);

        // If the video is portrait, preserve its dimensions by scaling them back
        if (isPortrait) {
            float aspectRatio = (float) width / height;
            transform.postScale(1 * aspectRatio, 1 / aspectRatio, pivotX, pivotY);
        }

        view.setTransform(transform);
        view.invalidate();
    }

}
