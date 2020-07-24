/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.telegram.ormessenger.AndroidUtilities;
import org.telegram.ormessenger.AnimatedFileDrawableStream;
import org.telegram.ormessenger.DispatchQueue;
import org.telegram.ormessenger.FileLoader;
import org.telegram.ormessenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AnimatedFileDrawable extends BitmapDrawable implements Animatable {

    private static native long createDecoder(String src, int[] params, int account, long streamFileSize, Object readCallback, boolean preview);
    private static native void destroyDecoder(long ptr);
    private static native void stopDecoder(long ptr);
    private static native int getVideoFrame(long ptr, Bitmap bitmap, int[] params, int stride, boolean preview);
    private static native void seekToMs(long ptr, long ms, boolean precise);
    private static native void prepareToSeek(long ptr);
    private static native void getVideoInfo(int sdkVersion, String src, int[] params);

    public final static int PARAM_NUM_SUPPORTED_VIDEO_CODEC = 0;
    public final static int PARAM_NUM_WIDTH = 1;
    public final static int PARAM_NUM_HEIGHT = 2;
    public final static int PARAM_NUM_BITRATE = 3;
    public final static int PARAM_NUM_DURATION = 4;
    public final static int PARAM_NUM_AUDIO_FRAME_SIZE = 5;
    public final static int PARAM_NUM_VIDEO_FRAME_SIZE = 6;
    public final static int PARAM_NUM_FRAMERATE = 7;
    public final static int PARAM_NUM_ROTATION = 8;
    public final static int PARAM_NUM_SUPPORTED_AUDIO_CODEC = 9;
    public final static int PARAM_NUM_HAS_AUDIO = 10;
    public final static int PARAM_NUM_COUNT = 11;

    private long lastFrameTime;
    private int lastTimeStamp;
    private int invalidateAfter = 50;
    private final int[] metaData = new int[5];
    private Runnable loadFrameTask;
    private Bitmap renderingBitmap;
    private int renderingBitmapTime;
    private Bitmap nextRenderingBitmap;
    private int nextRenderingBitmapTime;
    private Bitmap backgroundBitmap;
    private int backgroundBitmapTime;
    private boolean destroyWhenDone;
    private boolean decoderCreated;
    private boolean decodeSingleFrame;
    private boolean singleFrameDecoded;
    private File path;
    private long streamFileSize;
    private int currentAccount;
    private boolean recycleWithSecond;
    private volatile long pendingSeekTo = -1;
    private volatile long pendingSeekToUI = -1;
    private boolean pendingRemoveLoading;
    private int pendingRemoveLoadingFramesReset;
    private final Object sync = new Object();

    private long lastFrameDecodeTime;

    private RectF actualDrawRect = new RectF();

    private BitmapShader renderingShader;
    private BitmapShader nextRenderingShader;
    private BitmapShader backgroundShader;

    private int[] roundRadius = new int[4];
    private int[] roundRadiusBackup;
    private Matrix shaderMatrix = new Matrix();
    private Path roundPath = new Path();
    private static float[] radii = new float[8];

    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private boolean applyTransformation;
    private final android.graphics.Rect dstRect = new android.graphics.Rect();
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isRunning;
    private volatile boolean isRecycled;
    public volatile long nativePtr;
    private DispatchQueue decodeQueue;

    private View parentView;
    private View secondParentView;

    private AnimatedFileDrawableStream stream;

    private boolean useSharedQueue;

    private static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, new ThreadPoolExecutor.DiscardPolicy());

    protected final Runnable mInvalidateTask = () -> {
        if (secondParentView != null) {
            secondParentView.invalidate();
        } else if (parentView != null) {
            parentView.invalidate();
        }
    };

    private Runnable uiRunnableNoFrame = new Runnable() {
        @Override
        public void run() {
            if (destroyWhenDone && nativePtr != 0) {
                destroyDecoder(nativePtr);
                nativePtr = 0;
            }
            if (nativePtr == 0) {
                if (renderingBitmap != null) {
                    renderingBitmap.recycle();
                    renderingBitmap = null;
                }
                if (backgroundBitmap != null) {
                    backgroundBitmap.recycle();
                    backgroundBitmap = null;
                }
                if (decodeQueue != null) {
                    decodeQueue.recycle();
                    decodeQueue = null;
                }
                return;
            }
            loadFrameTask = null;
            scheduleNextGetFrame();
        }
    };

    private Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyWhenDone && nativePtr != 0) {
                destroyDecoder(nativePtr);
                nativePtr = 0;
            }
            if (nativePtr == 0) {
                if (renderingBitmap != null) {
                    renderingBitmap.recycle();
                    renderingBitmap = null;
                }
                if (backgroundBitmap != null) {
                    backgroundBitmap.recycle();
                    backgroundBitmap = null;
                }
                if (decodeQueue != null) {
                    decodeQueue.recycle();
                    decodeQueue = null;
                }
                return;
            }
            if (stream != null && pendingRemoveLoading) {
                FileLoader.getInstance(currentAccount).removeLoadingVideo(stream.getDocument(), false, false);
            }
            if (pendingRemoveLoadingFramesReset <= 0) {
                pendingRemoveLoading = true;
            } else {
                pendingRemoveLoadingFramesReset--;
            }
            singleFrameDecoded = true;
            loadFrameTask = null;
            nextRenderingBitmap = backgroundBitmap;
            nextRenderingBitmapTime = backgroundBitmapTime;
            nextRenderingShader = backgroundShader;
            if (metaData[3] < lastTimeStamp) {
                lastTimeStamp = 0;
            }
            if (metaData[3] - lastTimeStamp != 0) {
                invalidateAfter = metaData[3] - lastTimeStamp;
            }
            if (pendingSeekToUI >= 0 && pendingSeekTo == -1) {
                pendingSeekToUI = -1;
                invalidateAfter = 0;
            }
            lastTimeStamp = metaData[3];
            if (secondParentView != null) {
                secondParentView.invalidate();
            } else if (parentView != null) {
                parentView.invalidate();
            }
            scheduleNextGetFrame();
        }
    };

    private Runnable loadFrameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecycled) {
                if (!decoderCreated && nativePtr == 0) {
                    nativePtr = createDecoder(path.getAbsolutePath(), metaData, currentAccount, streamFileSize, stream, false);
                    if (nativePtr != 0 && (metaData[0] > 3840 || metaData[1] > 3840)) {
                        destroyDecoder(nativePtr);
                        nativePtr = 0;
                    }
                    decoderCreated = true;
                }
                try {
                    if (nativePtr != 0 || metaData[0] == 0 || metaData[1] == 0) {
                        if (backgroundBitmap == null && metaData[0] > 0 && metaData[1] > 0) {
                            try {
                                backgroundBitmap = Bitmap.createBitmap(metaData[0], metaData[1], Bitmap.Config.ARGB_8888);
                            } catch (Throwable e) {
                                FileLog.e(e);
                            }
                            if (backgroundShader == null && backgroundBitmap != null && hasRoundRadius()) {
                                backgroundShader = new BitmapShader(backgroundBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                            }
                        }
                        boolean seekWas = false;
                        if (pendingSeekTo >= 0) {
                            metaData[3] = (int) pendingSeekTo;
                            long seekTo = pendingSeekTo;
                            synchronized(sync) {
                                pendingSeekTo = -1;
                            }
                            seekWas = true;
                            if (stream != null) {
                                stream.reset();
                            }
                            seekToMs(nativePtr, seekTo, true);
                        }
                        if (backgroundBitmap != null) {
                            lastFrameDecodeTime = System.currentTimeMillis();
                            if (getVideoFrame(nativePtr, backgroundBitmap, metaData, backgroundBitmap.getRowBytes(), false) == 0) {
                                AndroidUtilities.runOnUIThread(uiRunnableNoFrame);
                                return;
                            }
                            if (seekWas) {
                                lastTimeStamp = metaData[3];
                            }
                            backgroundBitmapTime = metaData[3];
                        }
                    } else {
                        AndroidUtilities.runOnUIThread(uiRunnableNoFrame);
                        return;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(uiRunnable);
        }
    };

    private final Runnable mStartTask = () -> {
        if (secondParentView != null) {
            secondParentView.invalidate();
        } else if (parentView != null) {
            parentView.invalidate();
        }
    };

    public AnimatedFileDrawable(File file, boolean createDecoder, long streamSize, TLRPC.Document document, Object parentObject, int account, boolean preview) {
        path = file;
        streamFileSize = streamSize;
        currentAccount = account;
        getPaint().setFlags(Paint.FILTER_BITMAP_FLAG);
        if (streamSize != 0 && document != null) {
            stream = new AnimatedFileDrawableStream(document, parentObject, account, preview);
        }
        if (createDecoder) {
            nativePtr = createDecoder(file.getAbsolutePath(), metaData, currentAccount, streamFileSize, stream, preview);
            if (nativePtr != 0 && (metaData[0] > 3840 || metaData[1] > 3840)) {
                destroyDecoder(nativePtr);
                nativePtr = 0;
            }
            decoderCreated = true;
        }
    }

    public Bitmap getFrameAtTime(long ms) {
        if (!decoderCreated || nativePtr == 0) {
            return null;
        }
        if (stream != null) {
            stream.cancel(false);
            stream.reset();
        }
        seekToMs(nativePtr, ms, false);
        if (backgroundBitmap == null) {
            backgroundBitmap = Bitmap.createBitmap(metaData[0], metaData[1], Bitmap.Config.ARGB_8888);
        }
        int result = getVideoFrame(nativePtr, backgroundBitmap, metaData, backgroundBitmap.getRowBytes(), true);
        return result != 0 ? backgroundBitmap : null;
    }

    public void setParentView(View view) {
        if (parentView != null) {
            return;
        }
        parentView = view;
    }

    public void setSecondParentView(View view) {
        boolean hadSecond = secondParentView != null;
        secondParentView = view;
        if (hadSecond && view == null && roundRadiusBackup != null) {
            setRoundRadius(roundRadiusBackup);
        }
        if (view == null && recycleWithSecond) {
            recycle();
        }
    }

    public void setAllowDecodeSingleFrame(boolean value) {
        decodeSingleFrame = value;
        if (decodeSingleFrame) {
            scheduleNextGetFrame();
        }
    }

    public void seekTo(long ms, boolean removeLoading) {
        synchronized (sync) {
            pendingSeekTo = ms;
            pendingSeekToUI = ms;
            prepareToSeek(nativePtr);
            if (decoderCreated && stream != null) {
                stream.cancel(removeLoading);
                pendingRemoveLoading = removeLoading;
                pendingRemoveLoadingFramesReset = pendingRemoveLoading ? 0 : 10;
            }
        }
    }

    public void recycle() {
        if (secondParentView != null) {
            recycleWithSecond = true;
            return;
        }
        isRunning = false;
        isRecycled = true;
        if (loadFrameTask == null) {
            if (nativePtr != 0) {
                destroyDecoder(nativePtr);
                nativePtr = 0;
            }
            if (renderingBitmap != null) {
                renderingBitmap.recycle();
                renderingBitmap = null;
            }
            if (nextRenderingBitmap != null) {
                nextRenderingBitmap.recycle();
                nextRenderingBitmap = null;
            }
            if (decodeQueue != null) {
                decodeQueue.recycle();
                decodeQueue = null;
            }
        } else {
            destroyWhenDone = true;
        }
        if (stream != null) {
            stream.cancel(true);
        }
    }

    public void resetStream(boolean stop) {
        if (stream != null) {
            stream.cancel(true);
        }
        if (nativePtr != 0) {
            if (stop) {
                stopDecoder(nativePtr);
            } else {
                prepareToSeek(nativePtr);
            }
        }
    }

    protected static void runOnUiThread(Runnable task) {
        if (Looper.myLooper() == uiHandler.getLooper()) {
            task.run();
        } else {
            uiHandler.post(task);
        }
    }

    public void setUseSharedQueue(boolean value) {
        useSharedQueue = value;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            recycle();
        } finally {
            super.finalize();
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public void start() {
        if (isRunning) {
            return;
        }
        isRunning = true;
        scheduleNextGetFrame();
        runOnUiThread(mStartTask);
    }

    public float getCurrentProgress() {
        if (metaData[4] == 0) {
            return 0;
        }
        if (pendingSeekToUI >= 0) {
            return pendingSeekToUI / (float) metaData[4];
        }
        return metaData[3] / (float) metaData[4];
    }

    public int getCurrentProgressMs() {
        if (pendingSeekToUI >= 0) {
            return (int) pendingSeekToUI;
        }
        return nextRenderingBitmapTime != 0 ? nextRenderingBitmapTime : renderingBitmapTime;
    }

    public int getDurationMs() {
        return metaData[4];
    }

    private void scheduleNextGetFrame() {
        if (loadFrameTask != null || nativePtr == 0 && decoderCreated || destroyWhenDone || !isRunning && (!decodeSingleFrame || decodeSingleFrame && singleFrameDecoded)) {
            return;
        }
        long ms = 0;
        if (lastFrameDecodeTime != 0) {
            ms = Math.min(invalidateAfter, Math.max(0, invalidateAfter - (System.currentTimeMillis() - lastFrameDecodeTime)));
        }
        if (useSharedQueue) {
            executor.schedule(loadFrameTask = loadFrameRunnable, ms, TimeUnit.MILLISECONDS);
        } else {
            if (decodeQueue == null) {
                decodeQueue = new DispatchQueue("decodeQueue" + this);
            }
            decodeQueue.postRunnable(loadFrameTask = loadFrameRunnable, ms);
        }
    }

    public boolean isLoadingStream() {
        return stream != null && stream.isWaitingForLoad();
    }

    @Override
    public void stop() {
        isRunning = false;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public int getIntrinsicHeight() {
        int height = decoderCreated ? (metaData[2] == 90 || metaData[2] == 270 ? metaData[0] : metaData[1]) : 0;
        if (height == 0) {
            return AndroidUtilities.dp(100);
        }
        return height;
    }

    @Override
    public int getIntrinsicWidth() {
        int width = decoderCreated ? (metaData[2] == 90 || metaData[2] == 270 ? metaData[1] : metaData[0]) : 0;
        if (width == 0) {
            return AndroidUtilities.dp(100);
        }
        return width;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        applyTransformation = true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (nativePtr == 0 && decoderCreated || destroyWhenDone) {
            return;
        }
        long now = System.currentTimeMillis();
        if (isRunning) {
            if (renderingBitmap == null && nextRenderingBitmap == null) {
                scheduleNextGetFrame();
            } else if (nextRenderingBitmap != null && (renderingBitmap == null || Math.abs(now - lastFrameTime) >= invalidateAfter)) {
                renderingBitmap = nextRenderingBitmap;
                renderingBitmapTime = nextRenderingBitmapTime;
                renderingShader = nextRenderingShader;
                nextRenderingBitmap = null;
                nextRenderingBitmapTime = 0;
                nextRenderingShader = null;
                lastFrameTime = now;
            }
        } else if (!isRunning && decodeSingleFrame && Math.abs(now - lastFrameTime) >= invalidateAfter && nextRenderingBitmap != null) {
            renderingBitmap = nextRenderingBitmap;
            renderingBitmapTime = nextRenderingBitmapTime;
            renderingShader = nextRenderingShader;
            nextRenderingBitmap = null;
            nextRenderingBitmapTime = 0;
            nextRenderingShader = null;
            lastFrameTime = now;
        }

        if (renderingBitmap != null) {
            if (applyTransformation) {
                int bitmapW = renderingBitmap.getWidth();
                int bitmapH = renderingBitmap.getHeight();
                if (metaData[2] == 90 || metaData[2] == 270) {
                    int temp = bitmapW;
                    bitmapW = bitmapH;
                    bitmapH = temp;
                }
                dstRect.set(getBounds());
                scaleX = (float) dstRect.width() / bitmapW;
                scaleY = (float) dstRect.height() / bitmapH;
                applyTransformation = false;
            }
            if (hasRoundRadius()) {
                float scale = Math.max(scaleX, scaleY);

                if (renderingShader == null) {
                    renderingShader = new BitmapShader(backgroundBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                Paint paint = getPaint();
                paint.setShader(renderingShader);
                shaderMatrix.reset();
                shaderMatrix.setTranslate(dstRect.left, dstRect.top);
                if (metaData[2] == 90) {
                    shaderMatrix.preRotate(90);
                    shaderMatrix.preTranslate(0, -dstRect.width());
                } else if (metaData[2] == 180) {
                    shaderMatrix.preRotate(180);
                    shaderMatrix.preTranslate(-dstRect.width(), -dstRect.height());
                } else if (metaData[2] == 270) {
                    shaderMatrix.preRotate(270);
                    shaderMatrix.preTranslate(-dstRect.height(), 0);
                }
                shaderMatrix.preScale(scaleX, scaleY);

                renderingShader.setLocalMatrix(shaderMatrix);
                for (int a = 0; a < roundRadius.length; a++) {
                    radii[a * 2] = roundRadius[a];
                    radii[a * 2 + 1] = roundRadius[a];
                }
                roundPath.reset();
                roundPath.addRoundRect(actualDrawRect, radii, Path.Direction.CW);
                roundPath.close();
                canvas.drawPath(roundPath, paint);
            } else {
                canvas.translate(dstRect.left, dstRect.top);
                if (metaData[2] == 90) {
                    canvas.rotate(90);
                    canvas.translate(0, -dstRect.width());
                } else if (metaData[2] == 180) {
                    canvas.rotate(180);
                    canvas.translate(-dstRect.width(), -dstRect.height());
                } else if (metaData[2] == 270) {
                    canvas.rotate(270);
                    canvas.translate(-dstRect.height(), 0);
                }
                canvas.scale(scaleX, scaleY);
                canvas.drawBitmap(renderingBitmap, 0, 0, getPaint());
            }
            if (isRunning) {
                long timeToNextFrame = Math.max(1, invalidateAfter - (now - lastFrameTime) - 17);
                uiHandler.removeCallbacks(mInvalidateTask);
                uiHandler.postDelayed(mInvalidateTask, Math.min(timeToNextFrame, invalidateAfter));
            }
        }
    }

    @Override
    public int getMinimumHeight() {
        int height = decoderCreated ? (metaData[2] == 90 || metaData[2] == 270 ? metaData[0] : metaData[1]) : 0;
        if (height == 0) {
            return AndroidUtilities.dp(100);
        }
        return height;
    }

    @Override
    public int getMinimumWidth() {
        int width = decoderCreated ? (metaData[2] == 90 || metaData[2] == 270 ? metaData[1] : metaData[0]) : 0;
        if (width == 0) {
            return AndroidUtilities.dp(100);
        }
        return width;
    }

    public Bitmap getRenderingBitmap() {
        return renderingBitmap;
    }

    public Bitmap getNextRenderingBitmap() {
        return nextRenderingBitmap;
    }

    public Bitmap getBackgroundBitmap() {
        return backgroundBitmap;
    }

    public Bitmap getAnimatedBitmap() {
        if (renderingBitmap != null) {
            return renderingBitmap;
        } else if (nextRenderingBitmap != null) {
            return nextRenderingBitmap;
        }
        return null;
    }

    public void setActualDrawRect(float x, float y, float width, float height) {
        actualDrawRect.set(x, y, x + width, y + height);
    }

    public void setRoundRadius(int[] value) {
        if (secondParentView != null) {
            if (roundRadiusBackup == null) {
                roundRadiusBackup = new int[4];
            }
            System.arraycopy(roundRadius, 0, roundRadiusBackup, 0, roundRadiusBackup.length);
        }
        System.arraycopy(value, 0, roundRadius, 0, roundRadius.length);
        getPaint().setFlags(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    private boolean hasRoundRadius() {
        for (int a = 0; a < roundRadius.length; a++) {
            if (roundRadius[a] != 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasBitmap() {
        return nativePtr != 0 && (renderingBitmap != null || nextRenderingBitmap != null);
    }

    public int getOrientation() {
        return metaData[2];
    }

    public AnimatedFileDrawable makeCopy() {
        AnimatedFileDrawable drawable;
        if (stream != null) {
            drawable = new AnimatedFileDrawable(path, false, streamFileSize, stream.getDocument(), stream.getParentObject(), currentAccount, stream != null && stream.isPreview());
        } else {
            drawable = new AnimatedFileDrawable(path, false, streamFileSize, null, null, currentAccount, stream != null && stream.isPreview());
        }
        drawable.metaData[0] = metaData[0];
        drawable.metaData[1] = metaData[1];
        return drawable;
    }

    public static void getVideoInfo(String src, int[] params) {
        getVideoInfo(Build.VERSION.SDK_INT, src,  params);
    }
}
