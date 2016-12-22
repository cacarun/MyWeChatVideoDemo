package com.cjw.wechatvideo;

import android.content.Context;
import android.content.res.TypedArray;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class RecorderView extends LinearLayout implements SurfaceHolder.Callback, View.OnTouchListener,
        BothWayProgressBar.OnProgressEndListener {

    private static final String TAG = RecorderView.class.getName();

    private Context context;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Camera camera;
    private MediaRecorder mediaRecorder; // 录制视频

    private BothWayProgressBar progressBar; // 进度条

    private RelativeLayout rlStart; // 底部 "按住拍" 按钮

    private static int recordWidth; // 录制宽度
    private static int recordHeight; // 录制高度
    private static int recordMaxTimeMillis; // 录制最大时间
    private static int recordMinTimeMillis; // 录制最大时间
    private static int recordRefreshTimeMillis; // 录制最大时间

    private static int recordZoomValue = 20; // 放大倍数
    private static int recordCancelDistance = 10; // 上滑取消移动距离

    private float downY = 0; // 上滑取消的初始位置

    private int progressMillis; // 当前时间进度

    private boolean isRecording; // 判断是否正在录制
//    private boolean isRunning;
    private boolean isZoomIn = false; // 是否放大

//    private Thread progressThread; // 进度条线程
    private Timer progressTimer;

    private File targetFile; // 短视频保存的目录

    private GestureDetector detector; // 手势处理, 主要用于变焦 (双击放大缩小)

    private MyHandler handler;

    private class MyHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            int progress = getWidthByMillis(progressMillis);
            progressBar.setProgress(progress);
        }
    }

    public RecorderView(Context context) {
        this(context, null);
    }

    public RecorderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecorderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        this.context = context;

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ViewRecorder, defStyleAttr, 0);
        recordWidth = a.getInteger(R.styleable.ViewRecorder_record_width, 640);   // 1280
        recordHeight = a.getInteger(R.styleable.ViewRecorder_record_height, 480); // 720

        recordMaxTimeMillis = a.getInteger(R.styleable.ViewRecorder_record_max_time_millis, 10000);
        recordMinTimeMillis = a.getInteger(R.styleable.ViewRecorder_record_min_time_millis, 1000);
        recordRefreshTimeMillis = a.getInteger(R.styleable.ViewRecorder_record_refresh_time_millis, 20);

        Log.e(TAG, "recordWidth: " + recordWidth + " recordHeight: " + recordHeight
        + " recordMaxTimeMillis: " + recordMaxTimeMillis + " recordMinTimeMillis: " + recordMinTimeMillis
        + " recordRefreshTimeMillis: " + recordRefreshTimeMillis);

        a.recycle();

        initView();
    }

    private void initView() {

        LayoutInflater.from(context).inflate(R.layout.view_recorder, this);

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        progressBar = (BothWayProgressBar) findViewById(R.id.view_both_way_progress_bar);
        rlStart = (RelativeLayout) findViewById(R.id.rl_start);

        progressBar.setMaxTimeMillis(recordMaxTimeMillis); // 设置进度条最大量


        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setFixedSize(recordWidth, recordHeight);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
//        mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);
        surfaceHolder.addCallback(this);

        // 单独处理mSurfaceView的双击事件
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
//                return super.onDoubleTap(e);
                Log.e(TAG, "onDoubleTap: 双击事件");
                if (mediaRecorder != null) {
                    if (!isZoomIn) {
                        setZoom(recordZoomValue);
                        isZoomIn = true;
                    } else {
                        setZoom(0);
                        isZoomIn = false;
                    }
                }
                return true;
            }
        });

        // 单独处理 SurfaceView 的双击事件
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                detector.onTouchEvent(event);
                return true;
            }
        });

        // 监听开始录制按钮
        rlStart.setOnTouchListener(this);
        //自定义双向进度条
        progressBar.setOnProgressEndListener(this);

        handler = new MyHandler();

    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        this.surfaceHolder = surfaceHolder;
        startPreView();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

        if (camera != null) {
            Log.e(TAG, "surfaceDestroyed: ");

            //停止预览并释放摄像头资源
            camera.stopPreview();
            camera.release();
            camera = null;
        }

        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    /**
     * 根据时间得到对应的宽度
     * @param millis
     * @return
     */
    private int getWidthByMillis(int millis) {
        int width = progressBar.getWidth() / 2;
        return (int) ((width * 1.0 / recordMaxTimeMillis) * millis);
    }

    /**
     * 触摸事件的触发
     *
     * @param v
     * @param event
     * @return
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.e(TAG, "onTouch");
        boolean ret = false;

        int action = event.getAction();
        float ey = event.getY();
//        float ex = event.getX();

        // 只监听中间的按钮处
        int startPos = getWidthByMillis(recordMinTimeMillis);
        int endPos = progressBar.getWidth() / 2;
//        Log.e("POS", "startPos: " + startPos + " endPos: " + endPos);

//        int left = startPos;
//        int right = width;

        int currentProgressPos = getWidthByMillis(progressMillis);

        switch (v.getId()) {
            case R.id.rl_start: {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
//                        if (ex > left && ex < right) {
//
//                        }

                        Log.e("CJW", "ACTION_DOWN  开始录制");

                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setCancel(false);

                        //显示上滑取消
//                            mTvTip.setVisibility(View.VISIBLE);
//                            mTvTip.setText("↑ 上滑取消");

                        //记录按下的Y坐标
                        downY = ey;

                        Toast.makeText(context, "开始录制", Toast.LENGTH_SHORT).show();

                        // 开始录制视频, 进度条开始走
                       startRecord(); // TODO

                        // 重置起始时间
                        progressMillis = 0;
                        progressTimer = new Timer(true);
                        progressTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {

                                progressMillis += recordRefreshTimeMillis; // 每次步进

                                Log.e("Time", "进度条经过的毫秒时间：" + progressMillis);

                                handler.sendEmptyMessage(0);
                            }

                        }, 200, recordRefreshTimeMillis);


//                        progressThread = new Thread() {
//                            @Override
//                            public void run() {
//                                try {
//
//                                    progressMillis = 0;
//                                    isRunning = true;
//
//                                    while (isRunning) {
//                                        progressMillis += recordRefreshTimeMillis; // 每次步进
//
//                                        Log.e("CJW", "进度条经过的毫秒时间：" + progressMillis);
//
//                                        handler.obtainMessage(0).sendToTarget();
//                                        Thread.sleep(recordRefreshTimeMillis);
//                                    }
//                                } catch (InterruptedException e) {
//                                    e.printStackTrace();
//                                }
//                            }
//                        };
//
//                        progressThread.start();

                        ret = true;

                        break;

                    case MotionEvent.ACTION_UP:

                        if (isRecording) {

                            if (currentProgressPos > startPos && currentProgressPos < endPos) { // 录制有效
//                            mTvTip.setVisibility(View.INVISIBLE);

                                // 判断是否为录制结束或录制取消
                                if (!progressBar.isCancel()) {

                                    Log.e("CJW", "ACTION_UP 录制有效 停止录制并保存");

                                    // 停止录制并保存
                                    stopRecord(true);

                                } else {

                                    Log.e("CJW", "ACTION_UP 录制有效 但是取消录制并删除");

                                    // 现在是取消状态,不保存
                                    stopRecord(false);

                                    Toast.makeText(context, "取消录制", Toast.LENGTH_SHORT).show();
                                }

                                ret = false;
                            } else { // 录制无效

                                Log.e("CJW", "ACTION_UP 录制无效 时间太短并删除");

                                // 时间太短不保存
                                stopRecord(false);

                                Toast.makeText(context, "时间太短", Toast.LENGTH_SHORT).show();
                            }

                        }

                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (currentProgressPos > startPos && currentProgressPos < endPos) { // 录制有效
                            float currentY = event.getY();
                            if (downY - currentY > recordCancelDistance) {

                                Log.e("CJW", "ACTION_MOVE 上滑取消");

                                progressBar.setCancel(true);
                            }
                        }

                        break;
                }

                break;

            }

        }

        return ret;
    }

    @Override
    public void onProgressEndListener() {
        //视频停止录制
        stopRecord(true);
    }

    /**
     * 相机变焦 TODO 是否有效
     *
     * @param zoomValue
     */
    public void setZoom(int zoomValue) {
        if (camera != null) {
            Camera.Parameters parameters = camera.getParameters();
            if (parameters != null) {
                if (parameters.isZoomSupported()) {//判断是否支持
                    int maxZoom = parameters.getMaxZoom();
                    if (maxZoom == 0) {
                        return;
                    }
                    if (zoomValue > maxZoom) {
                        zoomValue = maxZoom;
                    }
                    parameters.setZoom(zoomValue);
                    camera.setParameters(parameters);
                }
            }
        }
    }

    /**
     * 开始录制
     */
    private void startRecord() {
        if (mediaRecorder != null) {
            //没有外置存储, 直接停止录制
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                return;
            }
            try {
                //mediaRecorder.reset();
                camera.unlock();
                mediaRecorder.setCamera(camera);

                //从相机采集视频
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                // 从麦克采集音频信息
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

                // 设置分辨率
                mediaRecorder.setVideoSize(recordWidth, recordHeight);

                //每秒的帧数
                mediaRecorder.setVideoFrameRate(30);

                //编码格式
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                // 设置帧频率，然后就清晰了
//                mediaRecorder.setVideoEncodingBitRate(1 * 1024 * 1024 * 100);
                mediaRecorder.setVideoEncodingBitRate(4000000);

                File targetDir = Environment.
                        getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                targetFile = new File(targetDir,
                        SystemClock.currentThreadTimeMillis() + ".mp4");

                mediaRecorder.setOutputFile(targetFile.getAbsolutePath());
                mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());

                //解决录制视频, 播放器横向问题
                mediaRecorder.setOrientationHint(90);

                mediaRecorder.prepare();
                //正式录制
                mediaRecorder.start();

                isRecording = true;

            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * 停止录制
     *
     * @param isSave  true 保存视频  false 删除文件
     */
    private void stopRecord(boolean isSave) {

        if (isRecording) {

            if (progressTimer != null) {
                progressTimer.cancel();
            }

            // 重置
            progressBar.setCancel(false);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.INVISIBLE);


            isRecording = false;

//            isRunning = false;

            if (mediaRecorder != null) {
//                mediaRecorder.setOnErrorListener(null);
//                mediaRecorder.setPreviewDisplay(null);

                try {
                    mediaRecorder.stop();
                } catch (Exception ex) {
                    Log.e("CJW", "stopRecord", ex);
                }
            }

            if (camera != null) {
                try {
                    camera.lock();
                } catch (Exception ex) {
                    Log.e("CJW", "stopRecord", ex);
                }
            }

            if (isSave) {

                Log.e("CJW", "stopRecord SAVE ：" + targetFile.getAbsolutePath());
                Toast.makeText(context, "视频已经放至" + targetFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();

            } else {

                if (targetFile.exists()) {

                    Log.e("CJW", "stopRecord UnSave 删除文件：" + targetFile.getAbsolutePath());

                    //不保存直接删掉
                    targetFile.delete();
                }

            }

        }
    }

    /**
     * 开启预览
     */
    private void startPreView() {
        Log.e(TAG, "startPreView: ");

        if (camera == null) {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
        }

        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();

            mediaRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override
                public void onError(MediaRecorder mediaRecorder, int i, int i1) {
                    Log.e(TAG, "MediaRecorder Error");
                }
            });
        }

        if (camera != null) {

            camera.setDisplayOrientation(90);

            try {
                camera.setPreviewDisplay(surfaceHolder);

                //实现 Camera 自动对焦
                Camera.Parameters parameters = camera.getParameters();
                List<String> focusModes = parameters.getSupportedFocusModes();
                if (focusModes != null) {
                    for (String mode : focusModes) {
                        mode.contains("continuous-video");
                        parameters.setFocusMode("continuous-video");
                    }
                }

                camera.setParameters(parameters);
                // 预览
                camera.startPreview();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
