import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatImageView;

import com.scanera.scan.R;

public class CropImageView extends AppCompatImageView {

    private static final float TOUCH_POINT_CATCH_DISTANCE = 15; //dp
    private static final float POINT_RADIUS = 10; // dp
    private static final float MAGNIFIER_CROSS_LINE_WIDTH = 0.8f; //dp
    private static final float MAGNIFIER_CROSS_LINE_LENGTH = 3; //dp
    private static final float MAGNIFIER_BORDER_WIDTH = 1; //dp

    private static final int DEFAULT_LINE_COLOR = 0xFF00FFFF;
    private static final float DEFAULT_LINE_WIDTH = 1; //dp
    private static final int DEFAULT_MASK_ALPHA = 86; // 0 - 255
    private static final int DEFAULT_MAGNIFIER_CROSS_COLOR = 0xFFFF4081;
    private static final float DEFAULT_GUIDE_LINE_WIDTH = 0.3f;//dp
    private static final int DEFAULT_GUIDE_LINE_COLOR = Color.WHITE;
    private static final int DEFAULT_POINT_FILL_COLOR = Color.WHITE;
    private static final int DEFAULT_POINT_FILL_ALPHA = 175;

    private Paint mPointPaint;
    private Paint mPointFillPaint;
    private Paint mLinePaint;
    private Paint mMaskPaint;
    private Paint mGuideLinePaint;
    private Paint mMagnifierPaint;
    private Paint mMagnifierCrossPaint;
    private float mScaleX, mScaleY;
    private int mActWidth, mActHeight, mActLeft, mActTop;
    private Point mDraggingPoint = null;
    private float mDensity;
    private ShapeDrawable mMagnifierDrawable;

    private float[] mMatrixValue = new float[9];
    private Xfermode mMaskXfermode = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private Path mPointLinePath = new Path();
    private Matrix mMagnifierMatrix = new Matrix();

    Point[] mCropPoints; // 0->LeftTop, 1->RightTop， 2->RightBottom, 3->LeftBottom
    Point[] mEdgeMidPoints;
    float mLineWidth;
    int mPointColor;
    float mPointWidth;
    float mGuideLineWidth;
    int mPointFillColor = DEFAULT_POINT_FILL_COLOR;
    int mPointFillAlpha = DEFAULT_POINT_FILL_ALPHA;
    int mLineColor = DEFAULT_LINE_COLOR;
    int mMagnifierCrossColor = DEFAULT_MAGNIFIER_CROSS_COLOR;
    int mGuideLineColor = DEFAULT_GUIDE_LINE_COLOR;
    int mMaskAlpha = DEFAULT_MASK_ALPHA; //0 - 255
    boolean mShowGuideLine = true;
    boolean mShowMagnifier = true;
    boolean mShowEdgeMidPoint = true;

    boolean mDragLimit = true;

    enum DragPointType {
        LEFT_TOP,
        RIGHT_TOP,
        RIGHT_BOTTOM,
        LEFT_BOTTOM,
        TOP,
        RIGHT,
        BOTTOM,
        LEFT;

        public static boolean isEdgePoint(DragPointType type) {
            return type == TOP || type == RIGHT || type == BOTTOM || type == LEFT;
        }
    }

    private final static int P_LT = 0, P_RT = 1, P_RB = 2, P_LB = 3;

    public CropImageView(Context context) {
        this(context, null);
    }

    public CropImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ScaleType scaleType = getScaleType();
        if (scaleType == ScaleType.FIT_END || scaleType == ScaleType.FIT_START || scaleType == ScaleType.MATRIX) {
            throw new RuntimeException("Image in CropImageView must be in center");
        }
        mDensity = getResources().getDisplayMetrics().density;
        initAttrs(context, attrs);
        initPaints();
    }

    private void initAttrs(Context context, AttributeSet attrs) {
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.CropImageView);
        mMaskAlpha = Math.min(Math.max(0, ta.getInt(R.styleable.CropImageView_civMaskAlpha, DEFAULT_MASK_ALPHA)), 255);
        mShowGuideLine = ta.getBoolean(R.styleable.CropImageView_civShowGuideLine, true);
        mLineColor = ta.getColor(R.styleable.CropImageView_civLineColor, DEFAULT_LINE_COLOR);
        mLineWidth = ta.getDimension(R.styleable.CropImageView_civLineWidth, dp2px(DEFAULT_LINE_WIDTH));
        mPointColor = ta.getColor(R.styleable.CropImageView_civPointColor, DEFAULT_LINE_COLOR);
        mPointWidth = ta.getDimension(R.styleable.CropImageView_civPointWidth, dp2px(DEFAULT_LINE_WIDTH));
        mMagnifierCrossColor = ta.getColor(R.styleable.CropImageView_civMagnifierCrossColor, DEFAULT_MAGNIFIER_CROSS_COLOR);
        mShowMagnifier = ta.getBoolean(R.styleable.CropImageView_civShowMagnifier, true);
        mGuideLineWidth = ta.getDimension(R.styleable.CropImageView_civGuideLineWidth, dp2px(DEFAULT_GUIDE_LINE_WIDTH));
        mGuideLineColor = ta.getColor(R.styleable.CropImageView_civGuideLineColor, DEFAULT_GUIDE_LINE_COLOR);
        mPointFillColor = ta.getColor(R.styleable.CropImageView_civPointFillColor, DEFAULT_POINT_FILL_COLOR);
        mShowEdgeMidPoint = ta.getBoolean(R.styleable.CropImageView_civShowEdgeMidPoint, true);
        mPointFillAlpha = Math.min(Math.max(0, ta.getInt(R.styleable.CropImageView_civPointFillAlpha, DEFAULT_POINT_FILL_ALPHA)), 255);
        ta.recycle();
    }


    public void setCropPoints(Point[] cropPoints) {
        if (getDrawable() == null) {
            Log.w("TAG", "should call after set drawable");
            return;
        }
        if (!checkPoints(cropPoints)) {
            setFullImgCrop();
        } else {
            this.mCropPoints = cropPoints;
            invalidate();
        }
    }

    public void setEdgeMidPoints() {
        if (mEdgeMidPoints == null) {
            mEdgeMidPoints = new Point[4];
            for (int i = 0; i < mEdgeMidPoints.length; i++) {
                mEdgeMidPoints[i] = new Point();
            }
        }
        int len = mCropPoints.length;
        for (int i = 0; i < len; i++) {
            mEdgeMidPoints[i].set(mCropPoints[i].x + (mCropPoints[(i + 1) % len].x - mCropPoints[i].x) / 2,
                    mCropPoints[i].y + (mCropPoints[(i + 1) % len].y - mCropPoints[i].y) / 2);
        }
    }


    public void setFullImgCrop() {
        if (getDrawable() == null) {
            Log.w("TAG", "should call after set drawable");
            return;
        }
        this.mCropPoints = getFullImgCropPoints();
        invalidate();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        mMagnifierDrawable = null;
    }

    public void setImageToCrop(Bitmap bmp) {
        setImageBitmap(bmp);
        setCropPoints(new Point[4]);
    }


    public Point[] getCropPoints() {
        return mCropPoints;
    }

    public void setPointFillColor(int pointFillColor) {
        this.mPointFillColor = pointFillColor;
    }

    public void setPointFillAlpha(int pointFillAlpha) {
        this.mPointFillAlpha = pointFillAlpha;
    }

    public void setMaskAlpha(int maskAlpha) {
        maskAlpha = Math.min(Math.max(0, maskAlpha), 255);
        this.mMaskAlpha = maskAlpha;
        invalidate();
    }

    public void setShowGuideLine(boolean showGuideLine) {
        this.mShowGuideLine = showGuideLine;
        invalidate();
    }

    public void setGuideLineColor(int guideLineColor) {
        this.mGuideLineColor = guideLineColor;
    }

    public void setGuideLineWidth(float guideLineWidth) {
        this.mGuideLineWidth = guideLineWidth;
    }

    public void setLineColor(int lineColor) {
        this.mLineColor = lineColor;
        invalidate();
    }

    public void setMagnifierCrossColor(int magnifierCrossColor) {
        this.mMagnifierCrossColor = magnifierCrossColor;
    }

    public void setLineWidth(int lineWidth) {
        this.mLineWidth = lineWidth;
        invalidate();
    }

    public void setPointColor(int pointColor) {
        this.mPointColor = pointColor;
        invalidate();
    }

    public void setPointWidth(float pointWidth) {
        this.mPointWidth = pointWidth;
        invalidate();
    }


    public void setShowMagnifier(boolean showMagnifier) {
        this.mShowMagnifier = showMagnifier;
    }


    public void setDragLimit(boolean dragLimit) {
        this.mDragLimit = dragLimit;
    }


    public Bitmap crop() {
        return crop(mCropPoints);
    }


    public Bitmap crop(Point[] points) {
        if (!checkPoints(points)) {
            return null;
        }
        Bitmap bmp = getBitmap();
//        return bmp == null ? null : SmartCropper.crop(bmp, points);
        return bmp;
    }


    public boolean canRightCrop() {
        if (!checkPoints(mCropPoints)) {
            return false;
        }
        Point lt = mCropPoints[0];
        Point rt = mCropPoints[1];
        Point rb = mCropPoints[2];
        Point lb = mCropPoints[3];
        return (pointSideLine(lt, rb, lb) * pointSideLine(lt, rb, rt) < 0) && (pointSideLine(lb, rt, lt) * pointSideLine(lb, rt, rb) < 0);
    }

    public boolean checkPoints(Point[] points) {
        return points != null && points.length == 4
                && points[0] != null && points[1] != null && points[2] != null && points[3] != null;
    }

    private long pointSideLine(Point lineP1, Point lineP2, Point point) {
        return pointSideLine(lineP1, lineP2, point.x, point.y);
    }

    private long pointSideLine(Point lineP1, Point lineP2, int x, int y) {
        long x1 = lineP1.x;
        long y1 = lineP1.y;
        long x2 = lineP2.x;
        long y2 = lineP2.y;
        return (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1);
    }

    public Bitmap getBitmap() {
        Bitmap bmp = null;
        Drawable drawable = getDrawable();
        if (drawable instanceof BitmapDrawable) {
            bmp = ((BitmapDrawable) drawable).getBitmap();
        }
        return bmp;
    }

    private void initPaints() {
        mPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPointPaint.setColor(mPointColor);
        mPointPaint.setStrokeWidth(mPointWidth);
        mPointPaint.setStyle(Paint.Style.STROKE);

        mPointFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPointFillPaint.setColor(mPointFillColor);
        mPointFillPaint.setStyle(Paint.Style.FILL);
        mPointFillPaint.setAlpha(mPointFillAlpha);

        mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLinePaint.setColor(mLineColor);
        mLinePaint.setStrokeWidth(mLineWidth);
        mLinePaint.setStyle(Paint.Style.STROKE);

        mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMaskPaint.setColor(Color.BLACK);
        mMaskPaint.setStyle(Paint.Style.FILL);

        mGuideLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGuideLinePaint.setColor(mGuideLineColor);
        mGuideLinePaint.setStyle(Paint.Style.FILL);
        mGuideLinePaint.setStrokeWidth(mGuideLineWidth);

        mMagnifierPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMagnifierPaint.setColor(Color.WHITE);
        mMagnifierPaint.setStyle(Paint.Style.FILL);

        mMagnifierCrossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMagnifierCrossPaint.setColor(mMagnifierCrossColor);
        mMagnifierCrossPaint.setStyle(Paint.Style.FILL);
        mMagnifierCrossPaint.setStrokeWidth(dp2px(MAGNIFIER_CROSS_LINE_WIDTH));
    }

    private void initMagnifier() {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(getBitmap(), null, new Rect(mActLeft, mActTop, mActWidth + mActLeft, mActHeight + mActTop), null);
        canvas.save();
        BitmapShader magnifierShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        mMagnifierDrawable = new ShapeDrawable(new OvalShape());
        mMagnifierDrawable.getPaint().setShader(magnifierShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        getDrawablePosition();
        onDrawCropPoint(canvas);
    }

    protected void onDrawCropPoint(Canvas canvas) {
        onDrawMask(canvas);
        onDrawGuideLine(canvas);
        onDrawLines(canvas);
        onDrawPoints(canvas);
        onDrawMagnifier(canvas);
        onDrawCusMagnifier(canvas);
    }

    protected void onDrawCusMagnifier(Canvas canvas) {
        DragPointType pointType = getPointType(mDraggingPoint);
        if (pointType == null || DragPointType.isEdgePoint(pointType)) {
            return;
        }

        if (mShowMagnifier && mDraggingPoint != null) {
            if (mMagnifierDrawable == null) {
                initMagnifier();
            }
            float draggingX = getViewPointX(mDraggingPoint);
            float draggingY = getViewPointY(mDraggingPoint);
            float radius = getWidth() / 8;
            float cx = radius; //圆心x坐标
            int lineOffset = (int) dp2px(MAGNIFIER_BORDER_WIDTH);
            if (0 <= mDraggingPoint.x && mDraggingPoint.x < getDrawable().getIntrinsicWidth() / 2) {
                mMagnifierDrawable.setBounds(getWidth() - (int) radius * 2 + lineOffset, lineOffset, getWidth() - lineOffset, (int) radius * 2 - lineOffset);
                cx = getWidth() - radius;
            } else {
                mMagnifierDrawable.setBounds(lineOffset, lineOffset, (int) radius * 2 - lineOffset, (int) radius * 2 - lineOffset);
            }
            canvas.drawCircle(cx, radius, radius, mMagnifierPaint);
            mMagnifierMatrix.setTranslate(radius - draggingX, radius - draggingY);
            mMagnifierDrawable.getPaint().getShader().setLocalMatrix(mMagnifierMatrix);
            mMagnifierDrawable.draw(canvas);
            canvas.drawCircle(cx, radius, dp2px(POINT_RADIUS), mPointFillPaint);
            canvas.drawCircle(cx, radius, dp2px(POINT_RADIUS), mPointPaint);
        }
    }

    protected void onDrawMagnifier(Canvas canvas) {
        if (mShowMagnifier && mDraggingPoint != null) {
            if (mMagnifierDrawable == null) {
                initMagnifier();
            }
            float draggingX = getViewPointX(mDraggingPoint);
            float draggingY = getViewPointY(mDraggingPoint);

            float radius = getWidth() / 8;
            float cx = radius;
            int lineOffset = (int) dp2px(MAGNIFIER_BORDER_WIDTH);
            mMagnifierDrawable.setBounds(lineOffset, lineOffset, (int) radius * 2 - lineOffset, (int) radius * 2 - lineOffset);
            double pointsDistance = getPointsDistance(draggingX, draggingY, 0, 0);
            if (pointsDistance < (radius * 2.5)) {
                mMagnifierDrawable.setBounds(getWidth() - (int) radius * 2 + lineOffset, lineOffset, getWidth() - lineOffset, (int) radius * 2 - lineOffset);
                cx = getWidth() - radius;
            }
            canvas.drawCircle(cx, radius, radius, mMagnifierPaint);
            mMagnifierMatrix.setTranslate(radius - draggingX, radius - draggingY);
            mMagnifierDrawable.getPaint().getShader().setLocalMatrix(mMagnifierMatrix);
            mMagnifierDrawable.draw(canvas);
            float crossLength = dp2px(MAGNIFIER_CROSS_LINE_LENGTH);
            canvas.drawLine(cx, radius - crossLength, cx, radius + crossLength, mMagnifierCrossPaint);
            canvas.drawLine(cx - crossLength, radius, cx + crossLength, radius, mMagnifierCrossPaint);
        }
    }

    protected void onDrawGuideLine(Canvas canvas) {
        if (!mShowGuideLine) {
            return;
        }
        int widthStep = mActWidth / 3;
        int heightStep = mActHeight / 3;
        canvas.drawLine(mActLeft + widthStep, mActTop, mActLeft + widthStep, mActTop + mActHeight, mGuideLinePaint);
        canvas.drawLine(mActLeft + widthStep * 2, mActTop, mActLeft + widthStep * 2, mActTop + mActHeight, mGuideLinePaint);
        canvas.drawLine(mActLeft, mActTop + heightStep, mActLeft + mActWidth, mActTop + heightStep, mGuideLinePaint);
        canvas.drawLine(mActLeft, mActTop + heightStep * 2, mActLeft + mActWidth, mActTop + heightStep * 2, mGuideLinePaint);
    }

    protected void onDrawMask(Canvas canvas) {
        if (mMaskAlpha <= 0) {
            return;
        }
        Path path = resetPointPath();
        if (path != null) {
            int sc = canvas.saveLayer(mActLeft, mActTop, mActLeft + mActWidth, mActTop + mActHeight, mMaskPaint, Canvas.ALL_SAVE_FLAG);
            mMaskPaint.setAlpha(mMaskAlpha);
            canvas.drawRect(mActLeft, mActTop, mActLeft + mActWidth, mActTop + mActHeight, mMaskPaint);
            mMaskPaint.setXfermode(mMaskXfermode);
            mMaskPaint.setAlpha(255);
            canvas.drawPath(path, mMaskPaint);
            mMaskPaint.setXfermode(null);
            canvas.restoreToCount(sc);
        }
    }

    private Path resetPointPath() {
        if (!checkPoints(mCropPoints)) {
            return null;
        }
        mPointLinePath.reset();
        Point lt = mCropPoints[0];
        Point rt = mCropPoints[1];
        Point rb = mCropPoints[2];
        Point lb = mCropPoints[3];
        mPointLinePath.moveTo(getViewPointX(lt), getViewPointY(lt));
        mPointLinePath.lineTo(getViewPointX(rt), getViewPointY(rt));
        mPointLinePath.lineTo(getViewPointX(rb), getViewPointY(rb));
        mPointLinePath.lineTo(getViewPointX(lb), getViewPointY(lb));
        mPointLinePath.close();
        return mPointLinePath;
    }

    private void getDrawablePosition() {
        Drawable drawable = getDrawable();
        if (drawable != null) {
            getImageMatrix().getValues(mMatrixValue);
            mScaleX = mMatrixValue[Matrix.MSCALE_X];
            mScaleY = mMatrixValue[Matrix.MSCALE_Y];
            int origW = drawable.getIntrinsicWidth();
            int origH = drawable.getIntrinsicHeight();
            mActWidth = Math.round(origW * mScaleX);
            mActHeight = Math.round(origH * mScaleY);
            mActLeft = (getWidth() - mActWidth) / 2;
            mActTop = (getHeight() - mActHeight) / 2;
        }
    }

    protected void onDrawLines(Canvas canvas) {
        Path path = resetPointPath();
        if (path != null) {
            canvas.drawPath(path, mLinePaint);
        }
    }

    protected void onDrawPoints(Canvas canvas) {
        if (!checkPoints(mCropPoints)) {
            return;
        }
        for (Point point : mCropPoints) {
            canvas.drawCircle(getViewPointX(point), getViewPointY(point), dp2px(POINT_RADIUS), mPointFillPaint);
            canvas.drawCircle(getViewPointX(point), getViewPointY(point), dp2px(POINT_RADIUS), mPointPaint);
        }
        if (mShowEdgeMidPoint) {
            setEdgeMidPoints();
            for (Point point : mEdgeMidPoints) {
                canvas.drawCircle(getViewPointX(point), getViewPointY(point), dp2px(POINT_RADIUS), mPointFillPaint);
                canvas.drawCircle(getViewPointX(point), getViewPointY(point), dp2px(POINT_RADIUS), mPointPaint);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        boolean handle = true;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDraggingPoint = getNearbyPoint(event);
                if (mDraggingPoint == null) {
                    handle = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                toImagePointSize(mDraggingPoint, event);
                break;
            case MotionEvent.ACTION_UP:
                mDraggingPoint = null;
                break;
        }
        invalidate();
        return handle || super.onTouchEvent(event);
    }

    private Point getNearbyPoint(MotionEvent event) {
        if (checkPoints(mCropPoints)) {
            for (Point p : mCropPoints) {
                if (isTouchPoint(p, event)) return p;
            }
        }
        if (checkPoints(mEdgeMidPoints)) {
            for (Point p : mEdgeMidPoints) {
                if (isTouchPoint(p, event)) return p;
            }
        }
        return null;
    }

    private boolean isTouchPoint(Point p, MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float px = getViewPointX(p);
        float py = getViewPointY(p);
        double distance = Math.sqrt(Math.pow(x - px, 2) + Math.pow(y - py, 2));
        return distance < dp2px(TOUCH_POINT_CATCH_DISTANCE);
    }

    private void toImagePointSize(Point dragPoint, MotionEvent event) {
        if (dragPoint == null) {
            return;
        }

        DragPointType pointType = getPointType(dragPoint);

        int x = (int) ((Math.min(Math.max(event.getX(), mActLeft), mActLeft + mActWidth) - mActLeft) / mScaleX);
        int y = (int) ((Math.min(Math.max(event.getY(), mActTop), mActTop + mActHeight) - mActTop) / mScaleY);

        if (mDragLimit && pointType != null) {
            switch (pointType) {
                case LEFT_TOP:
                    if (!canMoveLeftTop(x, y)) return;
                    break;
                case RIGHT_TOP:
                    if (!canMoveRightTop(x, y)) return;
                    break;
                case RIGHT_BOTTOM:
                    if (!canMoveRightBottom(x, y)) return;
                    break;
                case LEFT_BOTTOM:
                    if (!canMoveLeftBottom(x, y)) return;
                    break;
                case TOP:
                    if (!canMoveLeftTop(x, y) || !canMoveRightTop(x, y)) return;
                    break;
                case RIGHT:
                    if (!canMoveRightTop(x, y) || !canMoveRightBottom(x, y)) return;
                    break;
                case BOTTOM:
                    if (!canMoveLeftBottom(x, y) || !canMoveRightBottom(x, y)) return;
                    break;
                case LEFT:
                    if (!canMoveLeftBottom(x, y) || !canMoveLeftTop(x, y)) return;
                    break;
                default:
                    break;
            }
        }

        if (DragPointType.isEdgePoint(pointType)) {
            int xoff = x - dragPoint.x;
            int yoff = y - dragPoint.y;
            moveEdge(pointType, xoff, yoff);
        } else {
            dragPoint.y = y;
            dragPoint.x = x;
        }
    }

    private void moveEdge(DragPointType type, int xoff, int yoff) {
        switch (type) {
            case TOP:
                movePoint(mCropPoints[P_LT], 0, yoff);
                movePoint(mCropPoints[P_RT], 0, yoff);
                break;
            case RIGHT:
                movePoint(mCropPoints[P_RT], xoff, 0);
                movePoint(mCropPoints[P_RB], xoff, 0);
                break;
            case BOTTOM:
                movePoint(mCropPoints[P_LB], 0, yoff);
                movePoint(mCropPoints[P_RB], 0, yoff);
                break;
            case LEFT:
                movePoint(mCropPoints[P_LT], xoff, 0);
                movePoint(mCropPoints[P_LB], xoff, 0);
                break;
            default:
                break;
        }
    }

    private void movePoint(Point point, int xoff, int yoff) {
        if (point == null) return;
        int x = point.x + xoff;
        int y = point.y + yoff;
        if (x < 0 || x > getDrawable().getIntrinsicWidth()) return;
        if (y < 0 || y > getDrawable().getIntrinsicHeight()) return;
        point.x = x;
        point.y = y;
    }

    private boolean canMoveLeftTop(int x, int y) {
        if (pointSideLine(mCropPoints[P_RT], mCropPoints[P_LB], x, y)
                * pointSideLine(mCropPoints[P_RT], mCropPoints[P_LB], mCropPoints[P_RB]) > 0) {
            return false;
        }
        if (pointSideLine(mCropPoints[P_RT], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_RT], mCropPoints[P_RB], mCropPoints[P_LB]) < 0) {
            return false;
        }
        return pointSideLine(mCropPoints[P_LB], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_LB], mCropPoints[P_RB], mCropPoints[P_RT]) >= 0;
    }

    private boolean canMoveRightTop(int x, int y) {
        if (pointSideLine(mCropPoints[P_LT], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_RB], mCropPoints[P_LB]) > 0) {
            return false;
        }
        if (pointSideLine(mCropPoints[P_LT], mCropPoints[P_LB], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_LB], mCropPoints[P_RB]) < 0) {
            return false;
        }
        return pointSideLine(mCropPoints[P_LB], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_LB], mCropPoints[P_RB], mCropPoints[P_LT]) >= 0;
    }

    private boolean canMoveRightBottom(int x, int y) {
        if (pointSideLine(mCropPoints[P_RT], mCropPoints[P_LB], x, y)
                * pointSideLine(mCropPoints[P_RT], mCropPoints[P_LB], mCropPoints[P_LT]) > 0) {
            return false;
        }
        if (pointSideLine(mCropPoints[P_LT], mCropPoints[P_RT], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_RT], mCropPoints[P_LB]) < 0) {
            return false;
        }
        return pointSideLine(mCropPoints[P_LT], mCropPoints[P_LB], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_LB], mCropPoints[P_RT]) >= 0;
    }

    private boolean canMoveLeftBottom(int x, int y) {
        if (pointSideLine(mCropPoints[P_LT], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_RB], mCropPoints[P_RT]) > 0) {
            return false;
        }
        if (pointSideLine(mCropPoints[P_LT], mCropPoints[P_RT], x, y)
                * pointSideLine(mCropPoints[P_LT], mCropPoints[P_RT], mCropPoints[P_RB]) < 0) {
            return false;
        }
        return pointSideLine(mCropPoints[P_RT], mCropPoints[P_RB], x, y)
                * pointSideLine(mCropPoints[P_RT], mCropPoints[P_RB], mCropPoints[P_LT]) >= 0;
    }

    private DragPointType getPointType(Point dragPoint) {
        if (dragPoint == null) return null;

        DragPointType type;
        if (checkPoints(mCropPoints)) {
            for (int i = 0; i < mCropPoints.length; i++) {
                if (dragPoint == mCropPoints[i]) {
                    type = DragPointType.values()[i];
                    return type;
                }
            }
        }
        if (checkPoints(mEdgeMidPoints)) {
            for (int i = 0; i < mEdgeMidPoints.length; i++) {
                if (dragPoint == mEdgeMidPoints[i]) {
                    type = DragPointType.values()[4 + i];
                    return type;
                }
            }
        }
        return null;
    }

    private float getViewPointX(Point point) {
        return getViewPointX(point.x);
    }

    private float getViewPointX(float x) {
        return x * mScaleX + mActLeft;
    }

    private float getViewPointY(Point point) {
        return getViewPointY(point.y);
    }

    private float getViewPointY(float y) {
        return y * mScaleY + mActTop;
    }

    private float dp2px(float dp) {
        return dp * mDensity;
    }

    private Point[] getFullImgCropPoints() {
        Point[] points = new Point[4];
        Drawable drawable = getDrawable();
        if (drawable != null) {
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            points[0] = new Point(0, 0);
            points[1] = new Point(width, 0);
            points[2] = new Point(width, height);
            points[3] = new Point(0, height);
        }
        return points;
    }

    public double getPointsDistance(float x1, float y1, float x2, float y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
}
