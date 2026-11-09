package org.spektracam;

import android.content.res.AssetManager;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.util.function.Consumer;

/** Camera OES -> LUT -> TextureView. All GL work and resource ownership stay on one thread. */
final class LutPreviewRenderer {
    // A single GL queue makes old EGL teardown finish before a replacement
    // connects to the same TextureView during rotation or lens switching.
    private static final Handler GL_HANDLER = createGlHandler();
    private final Handler handler = GL_HANDLER;
    private static Handler createGlHandler() {
        HandlerThread thread = new HandlerThread("lut-preview-gl");
        thread.start();
        return new Handler(thread.getLooper());
    }
    private final AssetManager assets;
    private final SurfaceTexture output;
    private final int width, height;
    private volatile boolean closed;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private Surface outputSurface, cameraSurface;
    private SurfaceTexture cameraTexture;
    private int program, oesTexture, lutTexture, vao;
    private int shaderEncoding = -1;
    private float zoomRatio = 1f;
    private volatile float lastRenderedZoom = 1f;
    private volatile FocusGeometry focusGeometry;

    private static final class FocusGeometry {
        final float[] transform;
        final float zoom;

        FocusGeometry(float[] transform, float zoom) {
            this.transform = transform;
            this.zoom = zoom;
        }
    }
    private boolean firstFrame = true;
    private final Consumer<String> onError;
    private final Runnable onFirstFrame;

    LutPreviewRenderer(AssetManager assets, SurfaceTexture output, int width, int height,
                       Consumer<String> onError, Runnable onFirstFrame) {
        this.assets=assets; this.output=output; this.width=width; this.height=height; this.onError=onError; this.onFirstFrame=onFirstFrame;
    }

    void start(String lut, Consumer<Surface> ready) {
        handler.post(() -> {
            try {
                if (closed) return;
                display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] versions=new int[2];
                if (!EGL14.eglInitialize(display,versions,0,versions,1)) throw new IllegalStateException("EGL init 0x"+Integer.toHexString(EGL14.eglGetError()));
                EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
                int[] attrs={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE};
                if (!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) || count[0]==0)
                    throw new IllegalStateException("EGL config 0x"+Integer.toHexString(EGL14.eglGetError()));
                context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
                outputSurface=new Surface(output);
                window=EGL14.eglCreateWindowSurface(display,configs[0],outputSurface,new int[]{EGL14.EGL_NONE},0);
                if (context == EGL14.EGL_NO_CONTEXT || window == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL surface/context 0x"+Integer.toHexString(EGL14.eglGetError()));
                if (!EGL14.eglMakeCurrent(display,window,window,context)) throw new IllegalStateException("EGL current 0x"+Integer.toHexString(EGL14.eglGetError()));
                program=link(VERTEX,FRAGMENT);
                int[] ids=new int[2]; GLES30.glGenTextures(2,ids,0); oesTexture=ids[0]; lutTexture=ids[1];
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glGenVertexArrays(1,ids,0); vao=ids[0];
                upload(lut);
                cameraTexture=new SurfaceTexture(oesTexture);
                cameraTexture.setDefaultBufferSize(width,height);
                cameraTexture.setOnFrameAvailableListener(texture -> draw(),handler);
                cameraSurface=new Surface(cameraTexture);
                ready.accept(cameraSurface);
            } catch (Exception error) { onError.accept(error.toString()); close(); }
        });
    }

    void setLut(String path) {
        handler.post(() -> { if (!closed) try { upload(path); } catch(Exception error) { onError.accept(error.toString()); } });
    }
    float lastRenderedZoom() { return lastRenderedZoom; }

    PointF focusPoint(float viewX, float viewY) {
        FocusGeometry geometry = focusGeometry;
        if (geometry == null) return null;
        // Use the same camera-provided rotation and mirroring as the preview shader.
        float x = (viewX - 0.5f) / geometry.zoom + 0.5f;
        float y = (0.5f - viewY) / geometry.zoom + 0.5f;
        float[] transform = geometry.transform;
        return new PointF(transform[0] * x + transform[4] * y + transform[12],
                transform[1] * x + transform[5] * y + transform[13]);
    }

    void setZoom(float ratio) {
        handler.post(() -> {
            float clamped=DigitalZoom.clamp(ratio);
            if (Math.abs(zoomRatio-clamped)>0.0001f) { zoomRatio=clamped; }
        });
    }
    private void upload(String path) throws Exception {
        LutData data=LutData.load(assets,path);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,lutTexture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        for(int parameter:new int[]{GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_TEXTURE_WRAP_R})
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D,parameter,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,1);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGB16F,data.size,data.size,data.size,0,
                GLES30.GL_RGB,GLES30.GL_FLOAT,data.rgb);
        int error=GLES30.glGetError();
        if(error!=GLES30.GL_NO_ERROR) throw new IllegalStateException("LUT upload GL error "+error);
        GLES30.glUseProgram(program);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"lutSize"),data.size);
    }
    private float[] drawCamera(int w, int h) {
        float[] transform=new float[16]; cameraTexture.getTransformMatrix(transform);
        GLES30.glViewport(0,0,w,h); GLES30.glUseProgram(program); GLES30.glBindVertexArray(vao);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"camera"),0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,lutTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"lut"),1);
        int encoding = inputEncoding();
        if (encoding != shaderEncoding) {
            shaderEncoding = encoding;
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"transfer"),encoding&15);
            float[] rows=COLOR_MATRICES[encoding>>4],columns=new float[9];
            for(int r=0;r<3;r++)for(int c=0;c<3;c++)columns[c*3+r]=rows[r*3+c];
            GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(program,"toProphoto"),1,false,columns,0);
        }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"zoomRatio"),zoomRatio);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"cameraTransform"),1,false,transform,0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        return transform;
    }

    private int lastDataSpace=Integer.MIN_VALUE;
    // SurfaceTexture supplies the raw dataspace ID. Validate its standard and
    // transfer at runtime, including IDs outside lint's named SDR constants.
    @android.annotation.SuppressLint("WrongConstant")
    private int inputEncoding() {
        // OES sampling already converts YUV to RGB and expands its range. Decode only the
        // RGB transfer/primaries here; applying limited-range expansion again is wrong.
        int dataSpace=android.os.Build.VERSION.SDK_INT>=33?cameraTexture.getDataSpace():0;
        if (dataSpace!=lastDataSpace) {
            android.util.Log.i("Spektrafilm","camera dataspace="+dataSpace+
                    (dataSpace==0?"; assuming SDR sRGB":""));
            lastDataSpace=dataSpace;
        }
        if (dataSpace==0 || android.os.Build.VERSION.SDK_INT<33) return 0;
        int standard=android.hardware.DataSpace.getStandard(dataSpace),primaries;
        switch (standard) {
            case android.hardware.DataSpace.STANDARD_UNSPECIFIED:
            case android.hardware.DataSpace.STANDARD_BT709: primaries=0; break;
            case android.hardware.DataSpace.STANDARD_DCI_P3: primaries=1; break;
            case android.hardware.DataSpace.STANDARD_BT2020: primaries=2; break;
            case android.hardware.DataSpace.STANDARD_BT601_525: primaries=3; break;
            case android.hardware.DataSpace.STANDARD_BT601_625: primaries=4; break;
            case android.hardware.DataSpace.STANDARD_ADOBE_RGB: primaries=5; break;
            default: throw new IllegalStateException("Unsupported camera color standard: "+standard);
        }
        int transfer=android.hardware.DataSpace.getTransfer(dataSpace),curve;
        switch (transfer) {
            case android.hardware.DataSpace.TRANSFER_UNSPECIFIED:
            case android.hardware.DataSpace.TRANSFER_SRGB: curve=0; break;
            case android.hardware.DataSpace.TRANSFER_SMPTE_170M: curve=1; break;
            case android.hardware.DataSpace.TRANSFER_LINEAR: curve=2; break;
            case android.hardware.DataSpace.TRANSFER_GAMMA2_2: curve=3; break;
            case android.hardware.DataSpace.TRANSFER_GAMMA2_6: curve=4; break;
            case android.hardware.DataSpace.TRANSFER_GAMMA2_8: curve=5; break;
            default: throw new IllegalStateException("Spektrafilm requires an SDR camera stream: "+transfer);
        }
        return (primaries<<4)|curve;
    }

    private void present() {
        if (closed) return;
        int[] w=new int[1],h=new int[1];
        EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,w,0);
        EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,h,0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,w[0],h[0]);
        float[] transform = drawCamera(w[0],h[0]);
        if (!EGL14.eglSwapBuffers(display,window))
            throw new IllegalStateException("EGL swap 0x"+Integer.toHexString(EGL14.eglGetError()));
        lastRenderedZoom=zoomRatio;
        focusGeometry = new FocusGeometry(transform, zoomRatio);
    }

    private void draw() {
        if(closed || cameraTexture==null) return;
        try {
            cameraTexture.updateTexImage();
            present();
            if(firstFrame) {
                firstFrame=false;
                onFirstFrame.run();
            }
        } catch(Exception | OutOfMemoryError error) { onError.accept(error.toString()); close(); }
    }
    void close() {
        if(closed) return; closed=true;
        handler.post(() -> {
            if(cameraTexture!=null) { cameraTexture.setOnFrameAvailableListener(null); cameraTexture.release(); }
            if(cameraSurface!=null) cameraSurface.release();
            if(display!=EGL14.EGL_NO_DISPLAY) {
                if(program!=0) GLES30.glDeleteProgram(program);
                if(oesTexture!=0) GLES30.glDeleteTextures(2,new int[]{oesTexture,lutTexture},0);
                if(vao!=0) GLES30.glDeleteVertexArrays(1,new int[]{vao},0);
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display); EGL14.eglReleaseThread();
            }
            if(outputSurface!=null) outputSurface.release();
        });
    }
    private static int shader(int type,String source) {
        int id=GLES30.glCreateShader(type); GLES30.glShaderSource(id,source); GLES30.glCompileShader(id);
        int[] ok=new int[1]; GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) { String log=GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); throw new IllegalStateException(log); }
        return id;
    }
    private static int link(String vertex,String fragment) {
        int vs=shader(GLES30.GL_VERTEX_SHADER,vertex),fs=shader(GLES30.GL_FRAGMENT_SHADER,fragment),id=GLES30.glCreateProgram();
        GLES30.glAttachShader(id,vs); GLES30.glAttachShader(id,fs); GLES30.glLinkProgram(id);
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs);
        int[] ok=new int[1]; GLES30.glGetProgramiv(id,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) { String log=GLES30.glGetProgramInfoLog(id); GLES30.glDeleteProgram(id); throw new IllegalStateException(log); }
        return id;
    }
    private static final float[][] COLOR_MATRICES={
        {0.529388470712f,0.330231217730f,0.140627167763f,0.098305088514f,0.873483901238f,0.028146056090f,0.016851063808f,0.117696843742f,0.865673268566f},
        {0.631789972010f,0.213973374738f,0.154415788538f,0.083208045136f,0.885839904841f,0.030911268990f,-0.001273083688f,0.050769026950f,0.950778366361f},
        {0.835323078764f,0.048885367727f,0.115970688795f,0.054040655545f,0.928890431907f,0.017028131516f,-0.002342681415f,0.036342119421f,0.966274871617f},
        {0.503000179499f,0.344870794895f,0.152308160891f,0.107898533819f,0.848380836728f,0.043679848421f,0.016547261438f,0.110729949501f,0.872997098685f},
        {0.552678603969f,0.308560011659f,0.138940519658f,0.102700697482f,0.869445123533f,0.027813397953f,0.017623418032f,0.127157800160f,0.855493091432f},
        {0.740241399266f,0.113309068159f,0.146636385710f,0.137545901501f,0.833045651158f,0.029350745475f,0.023602860563f,0.073804677633f,0.902868593450f}
    };
    private static final String VERTEX="#version 300 es\n"+
        "out vec2 uv; void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}";
    private static final String FRAGMENT="#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n"+
        "precision highp float; precision highp sampler3D; uniform samplerExternalOES camera; uniform sampler3D lut;"+
        "uniform mat4 cameraTransform; uniform int transfer; uniform mat3 toProphoto; uniform float lutSize,zoomRatio; in vec2 uv; out vec4 color;"+
        "vec3 decode(vec3 v){if(transfer==1)return mix(pow((v+0.099)/1.099,vec3(1.0/0.45)),v/4.5,lessThan(v,vec3(0.081)));"+
        "if(transfer==2)return v;if(transfer==3)return pow(v,vec3(2.2));if(transfer==4)return pow(v,vec3(2.6));if(transfer==5)return pow(v,vec3(2.8));"+
        "return mix(pow((v+0.055)/1.055,vec3(2.4)),v/12.92,lessThanEqual(v,vec3(0.04045)));}"+
        "vec3 shape(vec3 v){v=max(v,vec3(0));return clamp(mix(1.055*pow(v,vec3(1.0/2.4))-0.055,12.92*v,lessThanEqual(v,vec3(0.0031308))),0.0,1.0);}"+
        "void main(){vec2 q=(uv-0.5)/zoomRatio+0.5;"+
        "vec3 c=clamp(texture(camera,(cameraTransform*vec4(q,0,1)).xy).rgb,0.0,1.0);"+
        "vec3 inputColor=shape(toProphoto*decode(c));"+
        "color=vec4(texture(lut,(inputColor*(lutSize-1.0)+0.5)/lutSize).rgb,1);}";
}
