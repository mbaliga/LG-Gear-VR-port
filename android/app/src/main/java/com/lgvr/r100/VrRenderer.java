package com.lgvr.r100;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Phase 2 renderer: shows a flat "big screen" image in the R100, side-by-side,
 * with per-eye 90° rotation (the panels are mounted rotated, mirrored) and
 * barrel distortion to counter the lenses.
 *
 * It's a Cardboard-style distortion viewer: for each eye we draw a full-viewport
 * quad and, in the fragment shader, map each output pixel back through
 * (rotation + radial distortion) into the source image. A flat screen "at
 * infinity" needs no per-eye parallax, so both eyes sample the same image.
 *
 * The distortion / rotation / zoom constants are deliberately tunable — we'll
 * dial them in on real hardware.
 */
public class VrRenderer implements GLSurfaceView.Renderer {

    // ---- tunables (adjust on-device) ----
    public volatile float k1 = 0.22f;   // barrel distortion coefficients
    public volatile float k2 = 0.24f;
    public volatile float zoom = 1.0f;  // <1 zooms in, >1 shows more
    public volatile boolean swapEyes = false;
    // rotation sign per eye: left = +90°, right = -90° by default (mirror).
    public volatile boolean flipRotation = false;

    private int program;
    private int aPos, uTex, uK1, uK2, uZoom, uEye, uViewAspect, uImgAspect;
    private int texId;
    private float imgAspect = 16f / 9f;

    private FloatBuffer quad;

    private int surfaceW = 1440, surfaceH = 960;

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vPos;\n" +
            "void main(){ vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String FS =
            "precision mediump float;\n" +
            "varying vec2 vPos;\n" +              // -1..1 across the eye viewport
            "uniform sampler2D uTex;\n" +
            "uniform float uK1, uK2, uZoom;\n" +
            "uniform float uViewAspect;\n" +      // eyeW/eyeH
            "uniform float uImgAspect;\n" +       // image w/h
            "uniform int uEye;\n" +               // 0 = left, 1 = right
            "void main(){\n" +
            "  vec2 p = vPos;\n" +
            "  p.x *= uViewAspect;\n" +           // make distortion radius circular
            "  float r2 = dot(p, p);\n" +
            "  float f = 1.0 + uK1*r2 + uK2*r2*r2;\n" +
            "  vec2 d = p * f * uZoom;\n" +
            "  // per-eye 90° rotation (mirror): left +90°, right -90°\n" +
            "  vec2 rot = (uEye == 0) ? vec2(-d.y, d.x) : vec2(d.y, -d.x);\n" +
            "  // rot is now in 'landscape' space; fit image aspect\n" +
            "  rot.x /= uImgAspect;\n" +
            "  vec2 uv = rot * 0.5 + 0.5;\n" +
            "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n" +
            "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;\n" +
            "  }\n" +
            "  gl_FragColor = texture2D(uTex, vec2(uv.x, 1.0 - uv.y));\n" +
            "}\n";

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uK1 = GLES20.glGetUniformLocation(program, "uK1");
        uK2 = GLES20.glGetUniformLocation(program, "uK2");
        uZoom = GLES20.glGetUniformLocation(program, "uZoom");
        uEye = GLES20.glGetUniformLocation(program, "uEye");
        uViewAspect = GLES20.glGetUniformLocation(program, "uViewAspect");
        uImgAspect = GLES20.glGetUniformLocation(program, "uImgAspect");

        // full-viewport quad (two triangles), positions -1..1
        float[] verts = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(verts).position(0);

        texId = makeTexture(buildTestImage());
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceW = width;
        surfaceH = height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1f(uK1, k1);
        GLES20.glUniform1f(uK2, k2);
        GLES20.glUniform1f(uZoom, zoom);
        GLES20.glUniform1f(uImgAspect, imgAspect);

        int halfW = surfaceW / 2;
        float viewAspect = (float) halfW / (float) surfaceH;
        GLES20.glUniform1f(uViewAspect, viewAspect);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);

        int leftEye = swapEyes ? 1 : 0;
        int rightEye = swapEyes ? 0 : 1;
        if (flipRotation) { leftEye ^= 1; rightEye ^= 1; }

        // left half
        GLES20.glViewport(0, 0, halfW, surfaceH);
        GLES20.glUniform1i(uEye, leftEye);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // right half
        GLES20.glViewport(halfW, 0, halfW, surfaceH);
        GLES20.glUniform1i(uEye, rightEye);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPos);
    }

    /** A landscape test card so we can judge orientation, distortion and centering. */
    private Bitmap buildTestImage() {
        int w = 1280, h = 720;
        imgAspect = (float) w / (float) h;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(20, 24, 40));
        Paint grid = new Paint();
        grid.setColor(Color.rgb(60, 80, 140));
        grid.setStrokeWidth(2);
        for (int x = 0; x <= w; x += 80) c.drawLine(x, 0, x, h, grid);
        for (int y = 0; y <= h; y += 80) c.drawLine(0, y, w, y, grid);
        Paint axis = new Paint();
        axis.setColor(Color.rgb(120, 160, 255));
        axis.setStrokeWidth(5);
        c.drawLine(w / 2f, 0, w / 2f, h, axis);
        c.drawLine(0, h / 2f, w, h / 2f, axis);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(64);
        c.drawText("R100 VR — test card", w / 2f, 90, t);
        t.setTextSize(48);
        c.drawText("TOP", w / 2f, 150, t);
        c.drawText("BOTTOM", w / 2f, h - 110, t);
        c.save(); c.rotate(-90, 90, h / 2f);
        c.drawText("LEFT", 90, h / 2f, t); c.restore();
        c.save(); c.rotate(90, w - 90, h / 2f);
        c.drawText("RIGHT", w - 90, h / 2f, t); c.restore();
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.rgb(255, 90, 90));
        c.drawCircle(w / 2f, h / 2f, 16, dot);
        return bmp;
    }

    private int makeTexture(Bitmap bmp) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return ids[0];
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return s;
    }
}
