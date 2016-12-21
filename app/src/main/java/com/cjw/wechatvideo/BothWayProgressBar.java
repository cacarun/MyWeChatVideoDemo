package com.cjw.wechatvideo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class BothWayProgressBar extends View {

    private boolean isCancel = false;

    // 正在录制的画笔
    private Paint recordPaint;
    // 上滑取消时的画笔
    private Paint cancelPaint;
    private Paint defaultPaint;

    // 最长时间
    private static int maxTimeMillis = 10 * 1000;

    // 是否显示
    private int isVisible;

    // 当前时间进度
    private int progress;

    // 进度条结束的监听
    private OnProgressEndListener onProgressEndListener;

    public BothWayProgressBar(Context context) {
        super(context, null);
    }

    public BothWayProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);

        init();
    }

    private void init() {

        isVisible = INVISIBLE;

        // 取消状态为红色bar, 反之为绿色bar
        recordPaint = new Paint();
        recordPaint.setColor(Color.GREEN);

        cancelPaint = new Paint();
        cancelPaint.setColor(Color.RED);

        defaultPaint = new Paint();
        defaultPaint.setColor(Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {

//        super.onDraw(canvas);

        int height = getHeight();
        int width = getWidth();
        int mid = width / 2;

        if (isVisible == View.VISIBLE) {

            if (progress < mid){
                // 从两遍往中间画出进度条
                canvas.drawRect(progress, 0, width - progress, height, isCancel ? cancelPaint : recordPaint);
            } else {
                if (onProgressEndListener != null) {
                    // 结束
                    onProgressEndListener.onProgressEndListener();
                }
            }
        } else {
            canvas.drawColor(Color.BLACK); // TODO 没起作用？
        }
    }


    /**
     * 设置进度
     * @param progress
     */
    public void setProgress(int progress) {
        this.progress = progress;
        invalidate();
    }

    /**
     * 设置录制状态 是否为取消状态
     * @param isCancel
     */
    public void setCancel(boolean isCancel) {
        this.isCancel = isCancel;
//        invalidate();
    }

    public boolean isCancel() {
        return isCancel;
    }

    /**
     * 重写是否可见方法
     * @param visibility
     */
    @Override
    public void setVisibility(int visibility) {
        isVisible = visibility;
        //重新绘制
        invalidate();
    }

    public void setMaxTimeMillis(int maxTimeMillis) {
        this.maxTimeMillis = maxTimeMillis;
    }

    /**
     * 当进度条结束后的 监听
     * @param onProgressEndListener
     */
    public void setOnProgressEndListener(OnProgressEndListener onProgressEndListener) {
        this.onProgressEndListener = onProgressEndListener;
    }


    public interface OnProgressEndListener{
        void onProgressEndListener();
    }

}
