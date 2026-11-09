package org.spektracam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.function.IntPredicate;

final class GalleryZoomView extends View {
    private static final float MAX_ZOOM = 5f;
    private final Matrix transform = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF pageBounds = new RectF();
    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;
    private final Runnable dismiss;
    private final IntPredicate stepPhoto;
    private final Runnable requestFullSize;
    private final float density;
    private final int touchSlop;
    private VelocityTracker velocity;
    private enum Swipe { NONE, HORIZONTAL, DOWN, IGNORED }
    private Swipe swipe = Swipe.NONE;
    private Bitmap photo, previous, next;
    private boolean previousReady, nextReady;
    private float pageOffset;
    private ValueAnimator settleAnimation;
    private float fitted = 1f, zoom = 1f, x, y;
    private float startX, startY, lastX, lastY;
    private boolean moved, pinched, acceptingTouch;

    GalleryZoomView(Context context, Runnable toggleTools, Runnable dismiss, IntPredicate stepPhoto,
                    Runnable requestFullSize) {
        super(context);
        this.dismiss = dismiss;
        this.stepPhoto = stepPhoto;
        this.requestFullSize = requestFullSize;
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription(context.getString(R.string.gallery_zoom_help));
        setOnClickListener(view -> toggleTools.run());
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!moved && !pinched) performClick();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                scaleTo(zoom > 1.01f ? 1f : 2.5f, event.getX(), event.getY());
                return true;
            }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinched = true;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                scaleTo(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    void setPhoto(Bitmap bitmap) {
        pageOffset = 0;
        setPreview(bitmap);
    }

    void setPreview(Bitmap bitmap) {
        photo = bitmap;
        zoom = 1f;
        fit();
        invalidate();
    }

    void setNeighbors(Bitmap previous, boolean previousReady, Bitmap next, boolean nextReady) {
        this.previous = previous;
        this.previousReady = previousReady;
        this.next = next;
        this.nextReady = nextReady;
        invalidate();
    }

    void setFullSizePhoto(Bitmap bitmap) {
        if (photo == null || getWidth() == 0 || getHeight() == 0) return;
        photo = bitmap;
        fitted = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        update(true);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pageOffset > 0) drawNeighbor(canvas, previous, pageOffset - getWidth());
        if (pageOffset < 0) drawNeighbor(canvas, next, pageOffset + getWidth());
        if (photo != null) canvas.drawBitmap(photo, transform, paint);
    }

    private void drawNeighbor(Canvas canvas, Bitmap image, float offset) {
        if (image == null) return;
        float scale = Math.min((float) getWidth() / image.getWidth(), (float) getHeight() / image.getHeight());
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        float left = (getWidth() - width) / 2 + offset;
        float top = (getHeight() - height) / 2;
        pageBounds.set(left, top, left + width, top + height);
        canvas.drawBitmap(image, null, pageBounds, paint);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cancelSettle();
        pageOffset = 0;
        fit();
    }

    private void fit() {
        if (photo == null || getWidth() == 0 || getHeight() == 0) return;
        fitted = Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
        zoom = 1;
        x = y = 0;
        update(true);
    }

    private void scaleTo(float proposed, float focusX, float focusY) {
        if (photo == null) return;
        float next = Math.max(1f, Math.min(MAX_ZOOM, proposed));
        float ratio = next / zoom;
        x = focusX - (focusX - x) * ratio;
        y = focusY - (focusY - y) * ratio;
        zoom = next;
        update(true);
        if (zoom > 1.05f) requestFullSize.run();
    }

    private void update(boolean constrain) {
        if (photo == null) {
            invalidate();
            return;
        }
        float scale = fitted * zoom;
        if (constrain) {
            x = constrain(x, getWidth(), photo.getWidth() * scale);
            y = constrain(y, getHeight(), photo.getHeight() * scale);
        }
        transform.setScale(scale, scale);
        transform.postTranslate(x + pageOffset, y);
        invalidate();
    }

    private float constrain(float offset, int area, float size) {
        return size <= area ? (area - size) / 2 : Math.max(area - size, Math.min(0, offset));
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) acceptingTouch = settleAnimation == null;
        if (!acceptingTouch) return true;
        if (action == MotionEvent.ACTION_DOWN) {
            startX = lastX = event.getX();
            startY = lastY = event.getY();
            swipe = Swipe.NONE;
            moved = pinched = false;
            recycleVelocity();
            velocity = VelocityTracker.obtain();
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            pinched = true;
            if (swipe == Swipe.HORIZONTAL || swipe == Swipe.DOWN) {
                pageOffset = 0;
                update(true);
            }
            swipe = Swipe.IGNORED;
        }
        pinch.onTouchEvent(event);
        if (!pinched) taps.onTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() == 2) {
            int remaining = 1 - event.getActionIndex();
            lastX = event.getX(remaining);
            lastY = event.getY(remaining);
        }
        if (action == MotionEvent.ACTION_MOVE && !pinch.isInProgress() && event.getPointerCount() == 1) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
            if (zoom > 1.01f && (moved || pinched)) {
                x += event.getX() - lastX;
                y += event.getY() - lastY;
                update(true);
            } else if (!pinched && moved) {
                if (swipe == Swipe.NONE) {
                    swipe = Math.abs(dx) > Math.abs(dy) ? Swipe.HORIZONTAL : dy > 0 ? Swipe.DOWN : Swipe.IGNORED;
                }
                if (swipe == Swipe.HORIZONTAL) {
                    pageOffset = canShowPage(dx < 0 ? 1 : -1) ? Math.max(-getWidth(), Math.min(getWidth(), dx)) : 0;
                    update(true);
                } else if (swipe == Swipe.DOWN && photo != null) {
                    y = (getHeight() - photo.getHeight() * fitted) / 2 + Math.max(0, dy);
                    update(false);
                }
            }
            lastX = event.getX();
            lastY = event.getY();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            acceptingTouch = false;
            Swipe direction = swipe;
            swipe = Swipe.NONE;
            float speedX = 0f;
            if (action == MotionEvent.ACTION_UP && velocity != null && direction == Swipe.HORIZONTAL) {
                velocity.computeCurrentVelocity(1000);
                speedX = velocity.getXVelocity();
            }
            recycleVelocity();
            if (action == MotionEvent.ACTION_UP && direction == Swipe.HORIZONTAL) {
                float dx = event.getX() - startX;
                float distance = Math.abs(dx);
                boolean flick = distance > 24 * density && Math.abs(speedX) > 650 * density && dx * speedX > 0;
                int page = dx < 0 ? 1 : -1;
                if (canShowPage(page) && (distance > Math.min(72 * density, getWidth() * .2f) || flick)) {
                    settleOn(page);
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP && direction == Swipe.DOWN
                    && event.getY() - startY > 96 * density) {
                dismiss.run();
                return true;
            }
            if (direction == Swipe.HORIZONTAL && pageOffset != 0) settleOn(0);
            else if (direction == Swipe.DOWN) update(true);
        }
        return true;
    }

    private boolean canShowPage(int offset) { return offset < 0 ? previousReady : nextReady; }

    private void settleOn(int page) {
        ValueAnimator animation = ValueAnimator.ofFloat(pageOffset, -page * getWidth());
        settleAnimation = animation;
        animation.setDuration(220);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> {
            pageOffset = (float) value.getAnimatedValue();
            update(true);
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (settleAnimation != ended) return;
                settleAnimation = null;
                if (page != 0 && !stepPhoto.test(page)) settleOn(0);
            }
        });
        animation.start();
    }

    private void cancelSettle() {
        if (settleAnimation == null) return;
        ValueAnimator animation = settleAnimation;
        settleAnimation = null;
        animation.cancel();
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        recycleVelocity();
        cancelSettle();
        super.onDetachedFromWindow();
    }
}
