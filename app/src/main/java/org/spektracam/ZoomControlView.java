package org.spektracam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/** A compact optical-node strip and a drag-operated rotating dial, sharing one total zoom value. */
final class ZoomControlView extends FrameLayout {
    interface Listener { void onZoom(float zoom); void onFinish(); void onHeightChanged(); }
    private final Listener listener;
    private final LinearLayout strip;
    private final Dial dial;
    private final Button current;
    private final ArrayList<Button> points = new ArrayList<>();
    private ZoomPolicy policy = new ZoomPolicy(new float[]{1f});
    private float zoom=1f, mainEquivalentMm=0;
    private int labelRotation;
    private boolean expanded, dragging;
    private float downX, downY, startZoom;
    private final int slop;
    private final Runnable autoCollapse = () -> setExpanded(false);
    ZoomControlView(Context context, Listener listener) {
        super(context); this.listener=listener; slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        strip=new LinearLayout(context);strip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xA0222222);bg.setCornerRadius(dp(24));strip.setBackground(bg);
        strip.setPadding(dp(5),0,dp(5),0);
        addView(strip,new LayoutParams(-2,dp(38),Gravity.CENTER));
        current=button();current.setOnClickListener(v->setExpanded(!expanded));
        dial=new Dial(context);dial.setVisibility(GONE);addView(dial,new LayoutParams(-1,dp(180)));
        setPolicy(policy,0);
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    static String format(float ratio) {
        String s=String.format(Locale.ROOT,ratio<1?"%.2f":"%.1f",ratio);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "")+"×";
    }
    private Button button(){
        Button b=new Button(getContext());b.setTextSize(12);b.setAllCaps(false);b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);
        b.setBackgroundColor(0x00000000);b.setStateListAnimator(null);
        b.setOnLongClickListener(v->{beginGesture();return true;});return b;
    }
    void setLabelRotation(int degrees) {
        labelRotation=degrees;
        current.setRotation(degrees);
        for(Button point:points) point.setRotation(degrees);
        dial.invalidate();
    }
    void setPolicy(ZoomPolicy policy,float mainEquivalentMm){
        this.policy=policy;this.mainEquivalentMm=mainEquivalentMm;points.clear();strip.removeAllViews();
        for(float ratio:policy.opticalRatios){
            Button b=button();b.setTag(ratio);b.setOnClickListener(v->{
                if(!isEnabled())return;
                if(Math.abs(zoom-ratio)<.005f)setExpanded(!expanded);
                else {listener.onZoom(ratio);finishGesture();}
            });points.add(b);
        }
        update(zoom);
        setLabelRotation(labelRotation);
    }
    void update(float value){
        zoom=policy.clamp(value);strip.removeAllViews();int selected=policy.lensIndex(zoom);
        String displayed = format(zoom);
        boolean matchesNode = false;
        for(float ratio:policy.opticalRatios) if(format(ratio).equals(displayed)) matchesNode = true;
        for(int i=0;i<points.size();i++){
            Button button=points.get(i);float ratio=policy.opticalRatios[i];
            boolean active=format(ratio).equals(displayed);
            stylePoint(button,active?displayed:format(ratio).replace("×",""),active);
            button.setContentDescription(mainEquivalentMm > 0
                    ? getContext().getString(R.string.optical_zoom_mm, format(ratio), Math.round(ratio * mainEquivalentMm))
                    : getContext().getString(R.string.optical_zoom, format(ratio)));
            strip.addView(button,new LinearLayout.LayoutParams(dp(52),dp(38)));
            if(i==selected && !matchesNode){
                stylePoint(current,displayed,true);
                current.setContentDescription(getContext().getString(R.string.current_zoom, displayed));
                strip.addView(current,new LinearLayout.LayoutParams(dp(52),dp(38)));
            }
        }
        dial.setContentDescription(getContext().getString(R.string.zoom_dial, format(zoom), format(policy.minimum), format(policy.maximum)));dial.invalidate();
    }
    private void stylePoint(Button button,String label,boolean active){
        button.setText(label);button.setTextColor(active?0xFFFFCE48:0xFFFFFFFF);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);
        circle.setColor(active?0x77000000:0x00000000);
        button.setBackground(new android.graphics.drawable.InsetDrawable(circle,dp(9),dp(2),dp(9),dp(2)));
        button.setPadding(0,0,0,0);button.setSingleLine(true);
        button.setSelected(active);button.setEnabled(isEnabled());
    }
    void setExpanded(boolean value){
        if(value && !isEnabled())return;removeCallbacks(autoCollapse);expanded=value;strip.setVisibility(value?GONE:VISIBLE);dial.setVisibility(value?VISIBLE:GONE);
        listener.onHeightChanged();
    }
    void beginGesture(){removeCallbacks(autoCollapse);setExpanded(true);}
    void finishGesture(){
        listener.onFinish();removeCallbacks(autoCollapse);
        if(expanded)postDelayed(autoCollapse,1000);
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(autoCollapse);super.onDetachedFromWindow();}
    int preferredHeight(){return dp(expanded?180:44);}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);dial.setEnabled(enabled);for(Button b:points)b.setEnabled(enabled);current.setEnabled(enabled);}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){removeCallbacks(autoCollapse);downX=event.getX();downY=event.getY();startZoom=zoom;dragging=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE && Math.abs(event.getX()-downX)>slop && Math.abs(event.getX()-downX)>Math.abs(event.getY()-downY)){
            dragging=true;setExpanded(true);return true;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE && dragging)listener.onZoom(policy.clamp((float)(startZoom*Math.exp((downX-event.getX())/dp(95)))));
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;finishGesture();performClick();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private final class Dial extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<float[]> hitPoints=new ArrayList<>();
        private float x,start;private boolean moved;
        Dial(Context context){super(context);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),r=w*.60f,cx=w/2,cy=r+dp(40);
            paint.setColor(0xA0111111);paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,r,paint);
            paint.setColor(0xBBFFCE48);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawCircle(cx,cy,r,paint);
            for(int i=0;i<=100;i++){
                float ratio=(float)(policy.minimum*Math.pow(policy.maximum/policy.minimum,i/100f));
                double angle=Math.log(ratio/zoom)/Math.log(2)*Math.PI/6;
                if(Math.abs(angle)>1.3)continue;
                boolean major=i%5==0;paint.setColor(major?0xB8FFFFFF:0x65FFFFFF);
                float a=r-dp(6),b=r-dp(major?20:13);
                c.drawLine(cx+a*(float)Math.sin(angle),cy-a*(float)Math.cos(angle),cx+b*(float)Math.sin(angle),cy-b*(float)Math.cos(angle),paint);
            }
            ArrayList<Float> labels=new ArrayList<>();for(float ratio:policy.opticalRatios)labels.add(ratio);
            for(float ratio:new float[]{2,7,policy.maximum}){
                boolean near=false;for(float v:labels)if(Math.abs(Math.log(ratio/v))<.25)near=true;
                if(!near && ratio>=policy.minimum && ratio<=policy.maximum)labels.add(ratio);
            }
            Collections.sort(labels);hitPoints.clear();paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);
            for(float ratio:labels){
                double angle=Math.log(ratio/zoom)/Math.log(2)*Math.PI/6;
                float px=cx+(r-dp(48))*(float)Math.sin(angle),py=cy-(r-dp(48))*(float)Math.cos(angle);
                if(Math.abs(angle)>1.26 || px<dp(22) || px>w-dp(22) || py>getHeight()-dp(20))continue;
                c.save();c.rotate((float)Math.toDegrees(angle)+labelRotation,px,py);paint.setTextSize(dp(13));paint.setColor(0xFFFFFFFF);
                c.drawText(format(ratio).replace("×",""),px,py,paint);
                if(mainEquivalentMm>0){paint.setTextSize(dp(11));paint.setColor(0x90FFFFFF);c.drawText(Math.round(mainEquivalentMm*ratio)+"mm",px,py+dp(16),paint);}
                c.restore();hitPoints.add(new float[]{px,py,ratio});
            }
            paint.setColor(0xFFFFCE48);paint.setStrokeWidth(dp(2));c.drawLine(cx,dp(39),cx,dp(62),paint);c.drawCircle(cx,dp(34),dp(3),paint);
            paint.setTextSize(dp(22));paint.setTextAlign(Paint.Align.CENTER);c.save();c.rotate(labelRotation,cx,dp(15));c.drawText(format(zoom),cx,dp(23),paint);c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!isEnabled())return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:removeCallbacks(autoCollapse);x=e.getX();start=zoom;moved=false;getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:
                    if(Math.abs(e.getX()-x)>slop)moved=true;
                    if(moved)listener.onZoom(policy.clamp((float)(start*Math.exp((x-e.getX())/dp(95)))));return true;
                case MotionEvent.ACTION_UP:
                    if(!moved){for(float[] hit:hitPoints)if(Math.hypot(e.getX()-hit[0],e.getY()-hit[1])<dp(24)){listener.onZoom(hit[2]);break;}}
                    finishGesture();performClick();return true;
                case MotionEvent.ACTION_CANCEL:finishGesture();return true;
            }return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,policy.minimum,policy.maximum,zoom));
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args){
            if(!isEnabled())return false;
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
                listener.onZoom(policy.clamp(zoom*(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1.05f:1/1.05f)));finishGesture();return true;}
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args!=null){
                listener.onZoom(policy.clamp(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,zoom)));finishGesture();return true;}
            return super.performAccessibilityAction(action,args);
        }
    }
}
