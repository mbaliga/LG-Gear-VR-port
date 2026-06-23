package com.lgvr.r100;

import android.app.Presentation;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Display;

/**
 * Hosts the stereo GL view on the headset's external display (the R100 shows up
 * as a DisplayPort secondary display). Anything we render here goes to the
 * panels, not the phone screen.
 */
public class VrPresentation extends Presentation {

    private GLSurfaceView glView;
    public final VrRenderer renderer = new VrRenderer();

    public VrPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        glView = new GLSurfaceView(getContext());
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(glView);
    }

    @Override
    public void onDisplayRemoved() {
        super.onDisplayRemoved();
        dismiss();
    }
}
