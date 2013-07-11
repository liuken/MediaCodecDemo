package io.vec.demo.mediacodec;

import android.app.Activity;
import android.media.*;
import android.media.MediaCodec.BufferInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.MediaController;

import java.nio.ByteBuffer;

public class DecodeActivity extends Activity implements SurfaceHolder.Callback, MediaController.MediaPlayerControl {
    private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/video.mp4";
    private static final String TAG = "DecodeActivity";
    private PlayerThread mPlayer = null;
    private MediaController mediaController;
    private long lastPresentationTimeUs;
    private boolean seeked = false;
    private long startMs;
    private long diff = 0;
    private long lastSeekedTo = 0;
    private long lastCorrectPresentationTimeUs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SurfaceView sv = new SurfaceView(this);
        mediaController = new MediaController(this);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        linearLayout.addView(sv);
        sv.getHolder().addCallback(this);
        setContentView(linearLayout);
        mediaController.setAnchorView(sv);
        mediaController.setMediaPlayer(this);
    }

    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mPlayer == null) {
            final Handler h = new Handler();
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mediaController.show();
                        h.postDelayed(this, 2000);
                    } catch (Exception e) {

                    }
                }
            });


            mPlayer = new PlayerThread(this, holder.getSurface());
            mPlayer.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mPlayer != null) {
            mPlayer.interrupt();
        }
    }

    @Override
    public void start() {
        mPlayer.play();
    }

    @Override
    public void pause() {
        mPlayer.pause();
    }

    @Override
    public int getDuration() {
        return (int) mPlayer.duration;
    }

    @Override
    public int getCurrentPosition() {
        return mPlayer.getCurrentPosition();
    }

    @Override
    public void seekTo(int i) {
        mPlayer.seekTo(i);
        mediaController.show();
    }

    @Override
    public boolean isPlaying() {
        if (mPlayer != null)
            return mPlayer.isPlaying();
        else
            return false;
    }

    @Override
    public int getBufferPercentage() {
        return 0;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean canPause() {
        return true;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean canSeekBackward() {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean canSeekForward() {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    private class PlayerThread extends Thread {
        Handler handler = new Handler();
        MediaController.MediaPlayerControl mediaPlayerControl;
        Handler handler1 = new Handler();
        long lastOffset = 0;
        boolean isPlaying = false;
        private MediaExtractor extractor;
        private MediaCodec decoder;
        private Surface surface;
        private BufferInfo info;
        private long duration;
        private AudioTrack audioTrack;

        public PlayerThread(MediaController.MediaPlayerControl mediaPlayerControl, Surface surface) {
            this.surface = surface;
            this.mediaPlayerControl = mediaPlayerControl;
        }

        @Override
        public void run() {
            MediaCodecPlay();
        }

        private void MediaCodecPlay() {
            extractor = new MediaExtractor();
            extractor.setDataSource(SAMPLE);

            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                duration = format.getLong(MediaFormat.KEY_DURATION) / 1000;

                Log.d(TAG, "MIME: " + mime);

                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    decoder = MediaCodec.createDecoderByType(mime);
                    decoder.configure(format, surface, null, 0);
                    break;
                }
            }

            decoder.start();

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            info = new BufferInfo();
            startMs = System.currentTimeMillis();
            //play();

            boolean isEOS = false;
            while (!Thread.interrupted()) {
                if (!isEOS) {
                    int inIndex = decoder.dequeueInputBuffer(1000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = inputBuffers[inIndex];

                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            // We shouldn't stop the playback at this point, just pass the EOS
                            // flag to decoder, we will get it again from the
                            // dequeueOutputBuffer
                            Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            Log.d(TAG, "Queue Input Buffer at position: " + info.presentationTimeUs);
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 1000);

                if (info.presentationTimeUs < lastPresentationTimeUs) {      // correct timing playback issue for some videos
                    startMs = System.currentTimeMillis();
                    lastCorrectPresentationTimeUs = lastPresentationTimeUs;
                }


                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                        outputBuffers = decoder.getOutputBuffers();
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                        break;
                    default:
                        ByteBuffer buffer = outputBuffers[outIndex];

                        //Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);

//                        We use a very simple clock to keep the video FPS, or the video
//                        playback will be too fast

                        Log.d(TAG, "Original Presentation time: " + info.presentationTimeUs / 1000 + ", Diff PT: " + (info.presentationTimeUs / 1000 - lastOffset) + " : System Time: " + (System.currentTimeMillis() - startMs));

                        lastPresentationTimeUs = info.presentationTimeUs;

                        if (seeked && Math.abs(info.presentationTimeUs / 1000 - lastOffset) < 100)
                            seeked = false;

                        while (!seeked && (info.presentationTimeUs / 1000 - lastOffset) > System.currentTimeMillis() - startMs) {
                            try {
                                sleep(5);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                                break;
                            }
                        }

                        decoder.releaseOutputBuffer(outIndex, true);
                        break;
                }

                // All decoded frames have been rendered, we can stop playing now
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    break;
                }
            }

            decoder.stop();
            decoder.release();
            extractor.release();

        }

        public int getCurrentPosition() {
            if (info != null)
                return (int) (info.presentationTimeUs / 1000);
            else
                return 0;
        }

        public void seekTo(int i) {
            seeked = true;
            Log.d(TAG, "SeekTo Requested to : " + i);
            Log.d(TAG, "SampleTime Before SeekTo : " + extractor.getSampleTime() / 1000);
            extractor.seekTo(i * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            Log.d(TAG, "SampleTime After SeekTo : " + extractor.getSampleTime() / 1000);

            lastOffset = extractor.getSampleTime() / 1000;
            startMs = System.currentTimeMillis();
            diff = (lastOffset - lastPresentationTimeUs / 1000);

            Log.d(TAG, "SeekTo with diff : " + diff);
        }

        public void pause() {
            isPlaying = false;
        }

        public void play() {
                isPlaying = true;
        }

        public boolean isPlaying() {
            return isPlaying;
        }
    }
}