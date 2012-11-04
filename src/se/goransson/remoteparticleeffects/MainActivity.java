package se.goransson.remoteparticleeffects;

import org.json.JSONException;
import org.json.JSONObject;

import se.goransson.remoteparticleeffects.mqtt.MqttService;
import se.goransson.remoteparticleeffects.mqtt.MqttService.MqttBinder;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.Toast;

public class MainActivity extends Activity implements OnTouchListener {

	public static final int JSON = 10;

	protected static final int PARTICLES = 0;

	private static final String TAG = "MainActivity";

	MqttService mqtt;
	boolean isBound;

	ParticleView particleView;

	String to, from;

	private int canvasWidth;

	private int canvasHeight;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		particleView = (ParticleView) findViewById(R.id.particleView1);

		particleView.setOnTouchListener(this);

		from = "sender";
		to = "receiver";
	}

	@Override
	protected void onStart() {
		super.onStart();
		Intent service = new Intent(MainActivity.this, MqttService.class);
		startService(service);
		bindService(service, conn, BIND_AUTO_CREATE);
	}

	@Override
	protected void onResume() {
		super.onResume();
		canvasWidth = particleView.getWidth();
		canvasHeight = particleView.getHeight();
	}

	@Override
	protected void onStop() {
		super.onStop();
		unbindService(conn);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	ServiceConnection conn = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			MqttBinder binder = (MqttBinder) service;
			mqtt = binder.getService();
			isBound = true;

			mqtt.setHandler(mHandler);

			Toast.makeText(MainActivity.this, "mqtt connected",
					Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			isBound = false;

			Toast.makeText(MainActivity.this, "mqtt disconnected",
					Toast.LENGTH_SHORT).show();
		}
	};

	Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MqttService.CONNECTED:
				mqtt.subscribe(to);
				break;

			case JSON:
				JSONObject obj = (JSONObject) msg.obj;
				particleView.addParticle(obj);
				break;
			}
		}

	};

	long timer;

	@Override
	public boolean onTouch(View v, MotionEvent event) {

		switch (event.getAction()) {
		case MotionEvent.ACTION_DOWN:
			Log.i(TAG, "ACTION_DOWN");

			break;
		case MotionEvent.ACTION_MOVE:
			// Log.i(TAG, "ACTION_MOVE");
			break;
		case MotionEvent.ACTION_UP:
			// Log.i(TAG, "ACTION_UP");
			break;
		}

		if( canvasHeight == 0 || canvasWidth == 0 ){
			canvasHeight = particleView.getHeight();
			canvasWidth = particleView.getWidth();
		}
		
		if (System.currentTimeMillis() - timer > 500) {
			JSONObject obj = new JSONObject();

			float x = event.getX() / canvasWidth;
			float y = event.getY() / canvasHeight;

			Log.i(TAG, "eX: " + event.getX() + " eY:" + event.getY());
			Log.i(TAG, "cW: " + canvasWidth + " cH: " + canvasHeight);
			Log.i(TAG, " X: " + x + "  Y: " + y);
			try {
				obj.put("x", x);
				obj.put("y", y);

				mqtt.publish(from, obj.toString().getBytes());

			} catch (JSONException e) {
				e.printStackTrace();
			}

			timer = System.currentTimeMillis();
		}

		return true;
	}
}
