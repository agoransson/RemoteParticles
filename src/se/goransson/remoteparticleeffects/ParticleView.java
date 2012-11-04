package se.goransson.remoteparticleeffects;

import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;

public class ParticleView extends SurfaceView implements Callback {

	private static final String TAG = "ParticleView";

	private ParticleDrawingThread mDrawingThread;

	private ArrayList<Particle> mParticleList;
	private ArrayList<Particle> mRecycleList;

	private Context mContext;

	public ParticleView(Context context) {
		this(context, null);

	}

	public ParticleView(Context context, AttributeSet attrs) {
		super(context, attrs);

		SurfaceHolder holder = getHolder();
		holder.addCallback(this);
		this.mContext = context;
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		mDrawingThread.setSurfaceSize(width, height);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		mDrawingThread = new ParticleDrawingThread(holder, mContext);
		mParticleList = mDrawingThread.getParticleList();
		mRecycleList = mDrawingThread.getRecycleList();
		mDrawingThread.start();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		boolean retry = true;
		mDrawingThread.stopDrawing();
		while (retry) {
			try {
				mDrawingThread.join();
				retry = false;
			} catch (InterruptedException e) {
			}
		}
	}

	public void addParticle(JSONObject obj) {
		synchronized (mDrawingThread) {

			Log.i(TAG, "addParticle");

			try {
				int x = (int) (obj.getDouble("x") * getWidth());
				int y = (int) (obj.getDouble("y") * getHeight());

				Particle p;
				int recycleCount = 0;

				if (mRecycleList.size() > 19)
					recycleCount = 20;
				else
					recycleCount = mRecycleList.size();

				for (int i = 0; i < recycleCount; i++) {
					p = mRecycleList.remove(0);
					p.init(x, y);
					mParticleList.add(p);
				}

				for (int i = 0; i < 20 - recycleCount; i++)
					mParticleList.add(new Particle(x, y));

			} catch (JSONException e) {
				e.printStackTrace();
			}

		}
	}

}
