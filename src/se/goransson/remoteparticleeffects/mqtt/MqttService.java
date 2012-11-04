/**
 * ##library.name##
 * ##library.sentence##
 * ##library.url##
 *
 * Copyright ##copyright## ##author##
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General
 * Public License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA  02111-1307  USA
 * 
 * @author      ##author##
 * @modified    ##date##
 * @version     ##library.prettyVersion## (##library.version##)
 */

package se.goransson.remoteparticleeffects.mqtt;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;

import se.goransson.remoteparticleeffects.MainActivity;
import se.goransson.remoteparticleeffects.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * A basic implementation of a MQTT client for Processing.
 * 
 * @author Andreas Göransson
 */

public class MqttService extends Service {

	private NotificationManager mNM;

	// Unique Identification Number for the Notification.
	// We use it on Notification start, and to cancel it.
	private int NOTIFICATION = R.string.local_service_started;

	/** Print debug messages */
	public boolean DEBUG = true;

	public static final int DISCONNECTED = 0;
	public static final int CONNECTING = 1;
	public static final int CONNECTED = 2;

	/** MQTT Protocol version (modeled after 3.1) */
	protected static final byte MQTT_VERSION = (byte) 0x03;

	/** MQTT Protocol name (modeled after standard 3.1, which is called MQIsdp) */
	protected static final String MQTT_PROTOCOL = "MQIsdp";

	private static final String TAG = "MqttService";

	private Connection mConnection;

	private MonitoringThread mMonitoringThread;
	private KeepaliveThread mKeepaliveThread;

	private int state = DISCONNECTED;

	private int message_id = 0;

	// Ping Related variables
	/**
	 * Keep alive defines the interval (in seconds) at which the client should
	 * send pings to the broker to avoid disconnects.
	 */
	private long keepalive = 10;

	/**
	 * Defines at what time the last action was taken by the client (this is
	 * used to determine if a ping should be sent or not)
	 */
	private long last_action = 0;

	/**
	 * Defines the number of seconds that the client will wait for a ping
	 * response before disconnecting.
	 */
	private long ping_grace = 5;

	/** Storage for the last sent ping request message. */
	private long last_ping_request = 0;

	/** Defines if a ping request has been sent. */
	private boolean ping_sent = false;

	private HashMap<String, Integer> subscriptions = new HashMap<String, Integer>();

	private Handler mHandler;

	/**
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class MqttBinder extends Binder {
		public MqttService getService() {
			Log.i(TAG, "getService");
			return MqttService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(TAG, "onCreate");

		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		attemptConnection();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i(TAG, "onStartCommand");
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.i(TAG, "onDestroy");
		disconnect();
		mNM.cancel(NOTIFICATION);
	}

	@Override
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "onBind");
		return mBinder;
	}

	// This is the object that receives interactions from clients. See
	// RemoteService for a more complete example.
	private final IBinder mBinder = new MqttBinder();

	/**
	 * Set the keep alive time out for the client in seconds; this defines at
	 * what interval the client should send pings to the broker so that it
	 * doesn't disconnect.
	 * 
	 * Default is set at 10 seconds.
	 * 
	 * @param seconds
	 */
	public void setKeepalive(int seconds) {
		keepalive = seconds;
	}

	private void attemptConnection() {
		TelephonyManager manager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		String unique_id = manager.getDeviceId();

		new ConnectThread().execute("195.178.234.111", "1883", unique_id);
	}

	/**
	 * Used to send the connect message, shouldn't be used outside the MQTT
	 * class.
	 * 
	 * @param id
	 */
	private void connect(String id) {
		if (state == DISCONNECTED) {
			try {
				new MqttHelper().execute(Messages.connect(id));

				last_action = System.currentTimeMillis();

				Log.i(TAG, "Sent connect message");
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		} else {
			Log.e(TAG, "You need to be connected first!");
		}
	}

	/**
	 * Send the disconnect message.
	 */
	public void disconnect() {
		if (state == CONNECTED) {
			try {
				new MqttHelper().execute(Messages.disconnect());
				// last_action = System.currentTimeMillis();
			} catch (IOException e) {
				e.printStackTrace();
			}

			try {
				mConnection.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			mMonitoringThread.stop();
			mKeepaliveThread.stop();

			state = DISCONNECTED;

			attemptConnection();
		} else {
			Log.e(TAG, "You need to be connected first!");
		}
	}

	public void publish(String topic, byte[] message) {
		if (state == CONNECTED) {
			try {
				new MqttHelper().execute(Messages.publish(topic, message));
				last_action = System.currentTimeMillis();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			Log.e(TAG, "You need to be connected first!");
		}
	}

	int callbacks = 1;

	public void subscribe(String topic) {
		if (state == CONNECTED) {
			try {
				new MqttHelper().execute(Messages.subscribe(getMessageId(),
						topic, Messages.EXACTLY_ONCE));
				last_action = System.currentTimeMillis();

				registerSubscription(topic, callbacks++);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			Log.e(TAG, "You need to be connected first!");
		}
	}

	/**
	 * 
	 * @param topic
	 * @return
	 */
	private boolean registerSubscription(String topic, int callback) {
		subscriptions.put(topic, callback);
		return true;
	}

	private int getMessageId() {
		message_id++;

		if (message_id == 65536) {
			message_id = 0;
		}

		return message_id;
	}

	/**
	 * Show a notification while this service is running.
	 */
	private void showNotification2() {
		// In this sample, we'll use the same text for the ticker and the
		// expanded notification
		CharSequence text = getText(R.string.local_service_started);

		// Set the icon, scrolling text and timestamp
		Notification notification = new Notification(R.drawable.ic_launcher,
				text, System.currentTimeMillis());

		// The PendingIntent to launch our activity if the user selects this
		// notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), 0);

		// Set the info for the views that show in the notification panel.
		notification.setLatestEventInfo(this,
				getText(R.string.local_service_label), text, contentIntent);

		// Send the notification.
		mNM.notify(NOTIFICATION, notification);
	}

	private class KeepaliveThread implements Runnable {

		private static final String TAG = "KeepaliveThread";
		private volatile boolean finished = false;

		@Override
		public void run() {
			while (!finished) {

				if (ping_sent
						&& System.currentTimeMillis() - last_ping_request > (ping_grace * 1000)) {
					Log.e(TAG, "Didn't get a ping response...");
//					disconnect();
					ping_sent = false;
				}

				if (System.currentTimeMillis() - last_action > (keepalive * 1000)) {
					if (state == CONNECTED) {
						synchronized (mConnection) {
							try {
								new MqttHelper().execute(Messages.ping());
								Log.i(TAG, "Sent PINGREQ");
								ping_sent = true;
								last_action = System.currentTimeMillis();
								last_ping_request = System.currentTimeMillis();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}

				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
				}
			}
		}

		public void stop() {
			finished = true;
		}
	}

	private class MonitoringThread implements Runnable {

		private static final String TAG = "MonitoringThread";

		Connection mConnection;

		private volatile boolean finished;

		public MonitoringThread(Connection connection) {
			mConnection = connection;
		}

		public void stop() {
			finished = true;
		}

		public void run() {
			int ret = 0;
			byte[] buffer = new byte[1024];

			while (!finished || ret >= 0) {
				try {
					ret = mConnection.getInputStream().read(buffer);
				} catch (IOException e) {
					e.printStackTrace();
					break;
				}

				if (ret > 0) {
					MQTTMessage msg = Messages.decode(buffer);

					switch (msg.type) {
					case Messages.CONNECT:
						if (DEBUG)
							Log.i(TAG, "CONNECT");
						state = CONNECTING;
						break;
					case Messages.CONNACK:
						if (DEBUG)
							Log.i(TAG, "CONNACK");

						byte return_code = (Byte) msg.variableHeader
								.get("return_code");

						Log.i(TAG, "return code: " + return_code);

						state = CONNECTED;

						if (mHandler != null)
							mHandler.obtainMessage(CONNECTED).sendToTarget();
						break;
					case Messages.PUBLISH:
						if (DEBUG)
							Log.i(TAG, "PUBLISH");

						String s = new String(msg.payload);

						Log.i(TAG, s);
						
						try {
							final JSONObject obj = new JSONObject(s);
							mHandler.obtainMessage(MainActivity.JSON, obj)
									.sendToTarget();
						} catch (JSONException e) {
							e.printStackTrace();
						}

						break;
					case Messages.PUBACK:
						if (DEBUG)
							Log.i(TAG, "PUBACK");
						break;
					case Messages.PUBREC:
						if (DEBUG)
							Log.i(TAG, "PUBREC");
						break;
					case Messages.PUBREL:
						if (DEBUG)
							Log.i(TAG, "PUBREL");
						break;
					case Messages.PUBCOMP:
						if (DEBUG)
							Log.i(TAG, "PUBCOMP");
						break;
					case Messages.SUBSCRIBE:
						if (DEBUG)
							Log.i(TAG, "SUBSCRIBE");
						break;
					case Messages.SUBACK:
						if (DEBUG)
							Log.i(TAG, "SUBACK");
						break;
					case Messages.UNSUBSCRIBE:
						if (DEBUG)
							Log.i(TAG, "UNSUBSCRIBE");
						break;
					case Messages.UNSUBACK:
						if (DEBUG)
							Log.i(TAG, "UNSUBACK");
						break;
					case Messages.PINGREQ:
						if (DEBUG)
							Log.i(TAG, "PINGREQ");
						last_ping_request = System.currentTimeMillis();
						break;
					case Messages.PINGRESP:
						if (DEBUG)
							Log.i(TAG, "PINGRESP");
						ping_sent = false;
						break;
					}
				} else {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	/**
	 * Set the UI handler for this Service.
	 * 
	 * @param mHandler
	 */
	public void setHandler(Handler mHandler) {
		this.mHandler = mHandler;
	}

	public void showNotification() {
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
				this).setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle("You just got lovebuzzed!")
				.setContentText("Hello World!");
		// Creates an explicit intent for an Activity in your app
		Intent resultIntent = new Intent(this, MainActivity.class);

		// The stack builder object will contain an artificial back stack for
		// the started Activity.
		// This ensures that navigating backward from the Activity leads out of
		// your application to the Home screen.
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
		// Adds the back stack for the Intent (but not the Intent itself)
		stackBuilder.addParentStack(MainActivity.class);
		// Adds the Intent that starts the Activity to the top of the stack
		stackBuilder.addNextIntent(resultIntent);
		PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
				PendingIntent.FLAG_UPDATE_CURRENT);
		mBuilder.setContentIntent(resultPendingIntent);
		mBuilder.setAutoCancel(true);

		// mId allows you to update the notification later on.
		mNM.notify(NOTIFICATION, mBuilder.build());
	}

	/**
	 * Simple helper thread, used only to set up the network connection.
	 * 
	 * @author ksango
	 * 
	 */
	private class ConnectThread extends AsyncTask<String, Void, Boolean> {

		private static final String TAG = "ConnectThread";
		String id;

		@Override
		protected Boolean doInBackground(String... params) {
			id = params[2];

			InetAddress addr = null;
			try {
				addr = InetAddress.getByName(params[0]);
			} catch (UnknownHostException e) {
				e.printStackTrace();
				Log.e(TAG, "Failed to create IP");
				return false;
			}

			int port = -1;
			try {
				port = Integer.parseInt(params[1]);
			} catch (NumberFormatException e) {
				e.printStackTrace();
				Log.e(TAG, "failed to format PORT");
				return false;
			}

			try {
				mConnection = new TCPConnection(addr, port);
			} catch (IOException e) {
				e.printStackTrace();
				Log.e(TAG, "failed to create connection");
				return false;
			}

			mMonitoringThread = new MonitoringThread(mConnection);
			Thread thread1 = new Thread(null, mMonitoringThread,
					"MonitoringThread");
			thread1.start();

			mKeepaliveThread = new KeepaliveThread();
			Thread thread2 = new Thread(null, mKeepaliveThread,
					"KeepaliveThread");
			thread2.start();

			return true;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (result) {
				if (DEBUG)
					Log.i(TAG, "Connected!");
				connect(id);
			} else {
				if (DEBUG)
					Log.i(TAG, "Not connected!");
			}
		}

	}

	/**
	 * Simple helper thread, used to send messages by the service.
	 * 
	 * @author ksango
	 * 
	 */
	private class MqttHelper extends AsyncTask<byte[], Void, Void> {

		@Override
		protected Void doInBackground(byte[]... params) {

			try {
				mConnection.getOutputStream().write(params[0]);
			} catch (IOException e) {
				e.printStackTrace();
				stopSelf();
			}

			return null;
		}
	}
}
