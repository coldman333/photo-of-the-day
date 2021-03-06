package hram.android.PhotoOfTheDay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import android.content.Context;
import android.os.Handler;
//import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.widget.Scroller;

public class ZTouchMove {
	//private static final String TAG = "ZMOVE"; 
	
	/*
	 * Эффект при достижении края изображения
	 * True - отпружинивание
	 * False - перелистывание на другой край изображения
	 */
	private boolean springMode = true;
	
    public interface ZTouchMoveListener {
        public void onTouchOffsetChanged(float xOffset);
    }
    private List<ZTouchMoveListener> mListeners = new ArrayList<ZTouchMoveListener>();
    
    public class ZInterpolator implements Interpolator {
        public float getInterpolation(float input) {
            // f(x) = ax^3 + bx^2 + cx + d
            // a = x - 2
            // b = 3 - 2x
            // c = x
            // d = 0
            // where x = derivative in point 0
            //input = (float)(-Math.cos(10*((double)input/Math.PI)) + 1) / 2;
            input = (mVelocity - 2) * (float) Math.pow(input, 3) + (3 - 2 * mVelocity) * (float) Math.pow(input, 2) + mVelocity * input;
            return input;
        }
    }
    
    Handler mHandler = new Handler();
    
    final Runnable mRunnable = new Runnable()
    {
        public void run() 
        {
        	if(onMovingToPosition())
        		mHandler.postDelayed(this, 20);
        }
    };
    
    private float mPosition = 0.5f;
    private float mPositionDelta = 0;
    private float mOffset = -1;
    private float mTouchDownX;
    private int xDiff;
    private VelocityTracker mVelocityTracker;
    private float mVelocity = 0;
    private Scroller mScroller;
    
    private int mTouchSlop;
    private int mMaximumVelocity;	
    private int mTouchState = ZTouchMoveConstants.TOUCH_STATE_REST;
    
    private int mWidth;
    private int mNumVirtualScreens = 7;
    
    public void init(Context ctx, int numVirtualScreens, int width) {
        mScroller = new Scroller(ctx, new ZInterpolator());
        
        final ViewConfiguration configuration = ViewConfiguration.get(ctx);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mNumVirtualScreens = Math.max(1, numVirtualScreens);

        initWidth(width);
    }
    
    public void initWidth(int width)
    {
    	mWidth = width;
    }
    /*
     * Возвращает новое смещение картинки в зависимости от режима springMode
     */
    private float getdX(float dX)
    {
    	if (springMode)
    	{
    		return dX;
    	}
    	
    	if (dX < 0)
    		return 1 + dX;
    	if (dX > 1)
    		return dX - 1; // брать остаток от деления
		return dX;
    }
    
    public void onTouchEvent(MotionEvent e) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(e);
        
        final float x = e.getX();
        final int action = e.getAction();
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchState = mScroller.isFinished() ? ZTouchMoveConstants.TOUCH_STATE_REST : ZTouchMoveConstants.TOUCH_STATE_SCROLLING;
                if (!mScroller.isFinished()) {
                    mScroller.abortAnimation();
                }
                
                mTouchDownX = x;
                break;
                
            case MotionEvent.ACTION_MOVE:
                xDiff = (int) (x - mTouchDownX);
                
                if (Math.abs(xDiff) > mTouchSlop && mTouchState != ZTouchMoveConstants.TOUCH_STATE_SCROLLING) {
                    mTouchState = ZTouchMoveConstants.TOUCH_STATE_SCROLLING;
                    if(xDiff < 0)
                        mTouchDownX = mTouchDownX - mTouchSlop;
                    else
                        mTouchDownX = mTouchDownX + mTouchSlop;
                    xDiff = (int) (x - mTouchDownX);
                }
                
                if (mTouchState == ZTouchMoveConstants.TOUCH_STATE_SCROLLING) {
                    mPositionDelta = -(float)xDiff / (mWidth * mNumVirtualScreens);
                    
                }
                break;
                
            case MotionEvent.ACTION_UP:
                if (mTouchState == ZTouchMoveConstants.TOUCH_STATE_SCROLLING) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    float velocityX = velocityTracker.getXVelocity() / (float)(mNumVirtualScreens * mWidth);
                    
                    //Log.d(TAG, String.format("mPositon = %f; mPositionDelta = %f", mPosition, mPositionDelta));
                    
                    mPosition =  mPosition + mPositionDelta;
                    mPositionDelta = 0;
                    
                    if(!returnSpring()) {
                        mVelocity = Math.min(3, Math.abs(velocityX * mNumVirtualScreens)) ;
                        // deaccelerate();
                        // Inertion
                        if(Math.abs(velocityX) * (float)(mNumVirtualScreens * mWidth) > ZTouchMoveConstants.SNAP_VELOCITY)
                        {
                        	float dX = getdX((mPosition - (velocityX > 0 ? 1 : -1) * 1 / (float) mNumVirtualScreens));
                            moveToPosition(mPosition, dX);
                        }
                        else
                        {
                        	float dX = getdX(mPosition - 0.7f * velocityX * ((float)ZTouchMoveConstants.SCROLLING_TIME / 1000));
                            moveToPosition(mPosition, dX);
                        }
                    }					
                }				
                mTouchState = ZTouchMoveConstants.TOUCH_STATE_REST;
                break;
                
            case MotionEvent.ACTION_CANCEL:
                mTouchState = ZTouchMoveConstants.TOUCH_STATE_REST;
                mPositionDelta = 0;
                break;
        }
        dispatchMoving();
    }
    
    private boolean returnSpring() {
    	if (springMode == false)
    	{
        	return false;
    	}
    	
        mVelocity = 0;
        if(mPositionDelta + mPosition > 1 - 0.5 / (float) mNumVirtualScreens)
            moveToPosition(mPosition, (float) (1 - 0.5 / (float) mNumVirtualScreens));
        else if(mPositionDelta + mPosition < 0.5 / (float) mNumVirtualScreens)
            moveToPosition(mPosition, (float) 0.5 / (float) mNumVirtualScreens);
        else
            return false;
        return true;
    }
    
    private void moveToPosition(float current_position, float desired_position) {
        mScroller.startScroll((int)(current_position * 1000), 0, (int)((desired_position - current_position) * 1000), 0, ZTouchMoveConstants.SCROLLING_TIME);
        mHandler.postDelayed(mRunnable, 20);
    }
    
    private boolean onMovingToPosition() {
        if(mScroller.computeScrollOffset()) {
            mPosition = (float)mScroller.getCurrX() / 1000;
            dispatchMoving();
            return true;
        } else {
            returnSpring();
            return false;
        }
    }
    
    private float normalizePosition(float xOffset) {
        final float springZone = 1 / (float) mNumVirtualScreens;
        // Normalized offset is from 0 to 0.5
        float xOffsetNormalized = Math.abs(xOffset - 0.5f);
        if(xOffsetNormalized + springZone / 2 > 0.5f) {
            // Spring formula
            // (0.5 - 2 * (1 - (x / (2 * springZone) + 0.5))^2) * springZone
            // where x >=0 and <= springZone
            // delta y = springZone / 2, y >=0 and y <= springZone / 2
            xOffsetNormalized = 0.5f - springZone / 2 + 
                    (0.5f - 2 * (float)Math.pow( (double)(1 - ( (xOffsetNormalized - 0.5f + springZone / 2) / (2 * springZone) + 0.5)), 2 ) ) * springZone;
            
            if(xOffset < 0.5f)
                xOffset = 0.5f - xOffsetNormalized;  
            else
                xOffset = 0.5f + xOffsetNormalized;
        }		
        return xOffset;
    }
    
    public synchronized void addMovingListener(ZTouchMoveListener listener) {
    	if (mListeners.contains(listener))
    	{
    		return;
    	}
        mListeners.add(listener);
    }
    
    public synchronized void removeMovingListener(ZTouchMoveListener listener) {
    	if (!mListeners.contains(listener))
    	{
    		return;
    	}
        mListeners.remove(listener);
    }
    
    private synchronized void dispatchMoving() {
    	float newOffset = normalizePosition(mPosition + mPositionDelta);
    	if (mOffset == newOffset) {
    		return;
    	}
    	mOffset = newOffset;
        Iterator<ZTouchMoveListener> iterator = mListeners.iterator();
        while(iterator.hasNext())  {
            ((ZTouchMoveListener) iterator.next()).onTouchOffsetChanged(mOffset);
        }
    }
    
    public void recycle()
    {
    	if (mVelocityTracker != null)
    	{
    		mVelocityTracker.recycle();
    	}
    	mListeners.clear();
    }
    
    /*
     * Устанавливает текущий режим отрисовки обоев при достежении краев изображения.
     */
    public void setSpringMode(boolean value) {
    	springMode = value;
	}
    
    /*
     * Устанавливает количество виртуальных рабочих столов
     */
    public void setNumVirtualScreens(int value) {
    	mNumVirtualScreens = value;
	}    
}
