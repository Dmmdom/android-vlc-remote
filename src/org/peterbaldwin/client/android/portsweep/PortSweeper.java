/*-
 *  Copyright (C) 2009 Peter Baldwin   
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.peterbaldwin.client.android.portsweep;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

public class PortSweeper {

	public static interface Callback {
		void onHostFound(String hostname, int responseCode);

		void onProgress(int progress, int max);
	}

	private static final String LOG_NAME = "Scanner";

	private static final int HANDLE_SCAN = 1;

	private static final int HANDLE_START = 1;
	private static final int HANDLE_REACHABLE = 2;
	private static final int HANDLE_UNREACHABLE = 3;
	private static final int HANDLE_COMPLETE = 4;

	private final Queue<byte[]> mAddressQueue;

	private final Handler mScanHandler;

	private final HandlerThread mScanThread;

	private final Handler mCallbackHandler;

	private final Worker.Callback mWorkerCallback;

	private final int mPort;

	private final String mFile;

	private final int mWorkerCount;

	private Callback mCallback;

	private int mProgress;
	private int mMax;

	private boolean mComplete;

	public PortSweeper(int port, String file, int threadCount,
			Callback callback, Looper looper) {
		mPort = port;
		mFile = file;
		mWorkerCount = threadCount;
		mCallback = callback;
		
		mAddressQueue = new ConcurrentLinkedQueue<byte[]>();

		mWorkerCallback = new MyWorkerCallback();

		mScanThread = new HandlerThread("Scanner",
				Process.THREAD_PRIORITY_BACKGROUND);
		mScanThread.start();

		Handler.Callback callbackHandlerCallback = new MyCallbackHandlerCallback();
		mCallbackHandler = new Handler(looper, callbackHandlerCallback);

		Handler.Callback scanHandlerCallback = new MyScanHandlerCallback();
		mScanHandler = new Handler(mScanThread.getLooper(), scanHandlerCallback);
	}

	public void setCallback(Callback callback) {
		mCallback = callback;
	}

	public void sweep(byte[] ipAddress) {
		abort();
		
		// Schedule a new sweep. The new sweep will not start until all previous
		// sweeps have been fully aborted.
		mScanHandler.obtainMessage(HANDLE_SCAN, ipAddress).sendToTarget();
	}
	
	public void abort() {
		// Clear pending jobs
		mScanHandler.removeMessages(HANDLE_SCAN);

		// Abort the job in progress
		mAddressQueue.clear();
	}

	public void destory() {
		abort();
		Looper looper = mScanThread.getLooper();
		looper.quit();
	}

	private void handleScan(byte[] interfaceAddress) {
		Worker[] workers = new Worker[mWorkerCount];
		for (int i = 0; i < workers.length; i++) {
			Worker worker = workers[i] = new Worker(mPort, mFile);
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.setCallback(mWorkerCallback);
		}
		int count = 0;
		
		// Scan outwards from the interface IP address for best results
		// with DHCP servers that allocate addresses sequentially.
		byte start = interfaceAddress[interfaceAddress.length - 1];
		for (int delta = 1; delta < 128; delta++) {
			for (int sign = -1; sign <= 1; sign += 2) {
				int b = (256 + start + sign * delta) % 256;
				if (b != 0) {
					byte[] ipAddress = interfaceAddress.clone();
					ipAddress[ipAddress.length - 1] = (byte) b;
					mAddressQueue.add(ipAddress);
					count += 1;
				} else {
					// Skip broadcast address
				}
			}
		}
		mCallbackHandler.obtainMessage(HANDLE_START, 0, count).sendToTarget();
		for (int i = 0; i < workers.length; i++) {
			Worker worker = workers[i];
			worker.start();
		}
		try {
			for (int i = 0; i < workers.length; i++) {
				Worker worker = workers[i];
				worker.join();
			}
		} catch (InterruptedException e) {
			Log.e(LOG_NAME, "interrupted", e);
		} finally {
			mCallbackHandler.sendEmptyMessage(HANDLE_COMPLETE);
		}
	}

	private class MyScanHandlerCallback implements Handler.Callback {
		/** {@inheritDoc} */
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
			case HANDLE_SCAN:
				byte[] interfaceAddress = (byte[]) msg.obj;
				handleScan(interfaceAddress);
				return true;
			default:
				return false;
			}

		}

	}

	private class MyWorkerCallback implements Worker.Callback {

		/** {@inheritDoc} */
		public byte[] pollIpAddress() {
			return mAddressQueue.poll();
		}

		/** {@inheritDoc} */
		public void onReachable(InetAddress address, int port, String hostname, int responseCode) {
			Message m = mCallbackHandler.obtainMessage(HANDLE_REACHABLE);
			m.obj = hostname;
			m.arg1 = responseCode;
			m.sendToTarget();
		}

		/** {@inheritDoc} */
		public void onUnreachable(byte[] ipAddress, int port, IOException e) {
			Message m = mCallbackHandler.obtainMessage(HANDLE_UNREACHABLE);
			m.obj = e;
			m.sendToTarget();
		}
	}

	private class MyCallbackHandlerCallback implements Handler.Callback {
		/**
		 * {@inheritDoc}
		 */
		public boolean handleMessage(Message msg) {
			if (mComplete && msg.what != HANDLE_START) {
				Log.w(LOG_NAME, "unexpected callback");
				return true;
			}
			try {
				switch (msg.what) {
				case HANDLE_START:
					mComplete = false;
					mProgress = msg.arg1;
					mMax = msg.arg2;
					return true;
				case HANDLE_REACHABLE:
					String hostname = (String) msg.obj;
					int responseCode = msg.arg1;
					mCallback.onHostFound(hostname, responseCode);
					mProgress++;
					return true;
				case HANDLE_UNREACHABLE:
					IOException e = (IOException) msg.obj;
					Log.d(LOG_NAME, "unreachable", e);
					mProgress++;
					return true;
				case HANDLE_COMPLETE:
					mComplete = true;
					mProgress = mMax;
					return true;
				default:
					return false;
				}
			} finally {
				mCallback.onProgress(mProgress, mMax);
			}
		}
	}
}