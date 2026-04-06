/*
 * Spartan Time Lapse Recorder - Minimalistic android time lapse recording app
 * Copyright (C) 2014  Andreas Rohner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.andreasrohner.spartantimelapserec.recorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.ErrorCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import at.andreasrohner.spartantimelapserec.data.RecSettings;
import at.andreasrohner.spartantimelapserec.rest.LogBuffer;

public class ImageRecorder extends Recorder implements Runnable,
		Camera.PictureCallback, ErrorCallback, AutoFocusCallback {
	private static final int CONTINUOUS_CAPTURE_THRESHOLD = 3000;
	private static final int RELEASE_CAMERA_THRESHOLD = 2000;
	private static final int MAX_RETRIES = 3;
	private static final int MAX_RECOVERY_ATTEMPTS = 3;
	private int mRetryCount = 0;
	private int mRecoveryCount = 0;
	protected long mEndTime;
	protected long mStartPreviewTime;
	protected boolean mUseAutoFocus;
	protected Camera.PictureCallback pictureCallback;
	protected AutoFocusCallback autoFocusCallback;
	protected boolean mWaitCamReady;

	/**
	 * Current / last recorded image
	 */
	private static File currentRecordedImage;

	/**
	 * Count of recorded images within the whole app session
	 */
	private static int recordedImagesCount = 0;

	public ImageRecorder(RecSettings settings,
			Context context, Handler handler) {
		super(settings, context, handler);

		if (settings.getStopRecAfter() > 0)
			mEndTime = System.currentTimeMillis() + settings.getInitDelay()
					+ settings.getStopRecAfter();

		if (mCanDisableShutterSound)
			mMute = null;

		pictureCallback=this;
		autoFocusCallback=this;
	}

	/**
	 * @return Current / last recorded image
	 */
	public static File getCurrentRecordedImage() {
		return currentRecordedImage;
	}

	/**
	 * @return Count of recorded images within the whole app session
	 */
	public static int getRecordedImagesCount() {
		return recordedImagesCount;
	}

	@Override
	public void stop() {
		if (mHandler != null)
			mHandler.removeCallbacks(this);

		muteShutter();

		super.stop();
	}

	protected void scheduleNextPicture() {
		if (mHandler == null) {
			return;
		}

		long diffTime = SystemClock.elapsedRealtime() - mStartPreviewTime;
		long delay = mSettings.getCaptureRate() - diffTime;
		if (delay >= RELEASE_CAMERA_THRESHOLD && mSettings.getCaptureRate() >= CONTINUOUS_CAPTURE_THRESHOLD){
			releaseCamera();
		}

		if (delay <= 0)
			mHandler.post(this);
		else
			mHandler.postDelayed(this, delay);
	}

	@Override
	public void onPictureTaken(byte[] data, Camera camera) {
		try {
			File file = getOutputFile("jpg");
			currentRecordedImage = file;
			FileOutputStream out = new FileOutputStream(file);
			out.write(data);
			out.close();
			mWaitCamReady = false;
			recordedImagesCount++;
			mRetryCount = 0;
			mRecoveryCount = 0;
			scheduleNextPicture();
		} catch (Exception e) {
			Log.e(getClass().getSimpleName(), "Error saving picture: " + e.getMessage());
			LogBuffer.add("E", getClass().getSimpleName(), "Error saving picture: " + e.getMessage());
			if (mRetryCount < MAX_RETRIES) {
				mRetryCount++;
				Log.w(getClass().getSimpleName(), "Retry " + mRetryCount + "/" + MAX_RETRIES);
				LogBuffer.add("W", getClass().getSimpleName(), "Retry " + mRetryCount + "/" + MAX_RETRIES);
				if (mHandler != null) {
					mHandler.postDelayed(this, 1000);
				}
			} else {
				mRetryCount = 0;
				attemptRecovery();
			}
		}
	}

	@Override
	public void onAutoFocus(boolean success, Camera camera) {
		try {
			muteShutter();
			new Handler(Looper.getMainLooper()).postDelayed(() -> {
				if (mCamera!=null && mHandler != null) {
					camera.takePicture(null, null, pictureCallback);
				}
			}, mWaitCamReady ? mSettings.getCameraTriggerDelay() : 0);

		} catch (Exception e) {
			e.printStackTrace();
			LogBuffer.add("E", getClass().getSimpleName(), "AutoFocus error: " + e.getMessage());
			if (mRetryCount < MAX_RETRIES) {
				mRetryCount++;
				Log.w(getClass().getSimpleName(), "AutoFocus retry " + mRetryCount + "/" + MAX_RETRIES);
				LogBuffer.add("W", getClass().getSimpleName(), "AutoFocus retry " + mRetryCount + "/" + MAX_RETRIES);
				if (mHandler != null) {
					mHandler.postDelayed(this, 1000);
				}
			} else {
				mRetryCount = 0;
				attemptRecovery();
			}
		}
	}

	@Override
	public void run() {
		try {
			if (mEndTime > 0 && mEndTime < System.currentTimeMillis()) {
				success();  //tell service to stop
				return;
			}

			mStartPreviewTime = SystemClock.elapsedRealtime();

			if (mCamera == null)
				prepareRecord();

			Log.d("Camera","Wait:"+ mWaitCamReady);

			new Handler(Looper.getMainLooper()).postDelayed(() -> {
				if (mCamera!=null) {
					try {
						mCamera.startPreview();
						if (mUseAutoFocus) {
							mCamera.autoFocus(autoFocusCallback);
						}
						else {
							onAutoFocus(true, mCamera);
						}
					} catch (RuntimeException e) {
						Log.e("ImageRecorder", "startPreview failed: " + e.getMessage());
						LogBuffer.add("E", getClass().getSimpleName(), "startPreview failed: " + e.getMessage());
						releaseCamera();
						mCamera = null;
						if (mRetryCount < MAX_RETRIES) {
							mRetryCount++;
							Log.w(getClass().getSimpleName(), "startPreview retry " + mRetryCount + "/" + MAX_RETRIES);
							LogBuffer.add("W", getClass().getSimpleName(), "startPreview retry " + mRetryCount + "/" + MAX_RETRIES);
							if (mHandler != null) {
								mHandler.postDelayed(this, 2000);
							}
						} else {
							mRetryCount = 0;
							attemptRecovery();
						}
					}
				}
			}, mWaitCamReady ? mSettings.getCameraInitDelay() : 0);

		} catch (Exception e) {
			Log.e("Error","startPreview");
			e.printStackTrace();
			LogBuffer.add("E", getClass().getSimpleName(), "Run error: " + e.getMessage());
			if (mRetryCount < MAX_RETRIES) {
				mRetryCount++;
				Log.w(getClass().getSimpleName(), "Run retry " + mRetryCount + "/" + MAX_RETRIES);
				LogBuffer.add("W", getClass().getSimpleName(), "Run retry " + mRetryCount + "/" + MAX_RETRIES);
				releaseCamera();
				if (mHandler != null) {
					mHandler.postDelayed(this, 2000);
				}
			} else {
				mRetryCount = 0;
				attemptRecovery();
			}
		}
	}

	protected void setWhiteBalance(Camera.Parameters params,
			Set<String> suppModes) {
		if (suppModes.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
			params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
		}
	}

	protected void setFocusMode(Camera.Parameters params, Set<String> suppModes) {
		if (mSettings.getCaptureRate() < CONTINUOUS_CAPTURE_THRESHOLD && suppModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
			params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
		} else if (suppModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)
				&& mSettings.getCaptureRate() < CONTINUOUS_CAPTURE_THRESHOLD) {
			params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
		} else if (suppModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
			params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
			mUseAutoFocus = true;
		}
	}

	protected void setCameraParams() throws IOException {
		Camera.Parameters params = mCamera.getParameters();

		/*
		 * params.set("cam_mode", 1); hack is not necessary for pictures
		 */

		List<String> suppList = params.getSupportedWhiteBalance();
		if (suppList != null) {
			Set<String> suppModes = new HashSet<String>();
			suppModes.addAll(suppList);

			setWhiteBalance(params, suppModes);
		}

		suppList = params.getSupportedFocusModes();
		if (suppList != null) {
			Set<String> suppModes = new HashSet<String>();
			suppModes.addAll(suppList);

			setFocusMode(params, suppModes);
		}

		params.setPictureFormat(ImageFormat.JPEG);

		params.setPictureSize(mSettings.getFrameWidth(),
				mSettings.getFrameHeight());

		params.setJpegQuality(mSettings.getJpegQuality());
		params.setRotation(getCameraRotation(mSettings.getCameraId()));
		params.setExposureCompensation(mSettings.getExposureCompensation());
		params.setZoom(mSettings.getZoom());
		params.setFlashMode(mSettings.getCameraFlash() ? Camera.Parameters.FLASH_MODE_ON : Camera.Parameters.FLASH_MODE_OFF);
		mCamera.setParameters(params);

		mCamera.setErrorCallback(this);
	}

	protected void prepareRecord() throws IOException {
		mWaitCamReady = mCamera == null;
		releaseCamera();

		mCamera = Camera.open(mSettings.getCameraId());

		setCameraParams();

		SurfaceTexture surfaceTexture = new SurfaceTexture(10);
		mCamera.setPreviewTexture(surfaceTexture);

	}

	protected void doRecord() {
		run();
	}

	@Override
	public void onError(int error, Camera camera) {
		switch (error) {
		case Camera.CAMERA_ERROR_SERVER_DIED:
			Log.w(getClass().getSimpleName(), "Camera server died, retrying...");
			LogBuffer.add("W", getClass().getSimpleName(), "Camera server died, retrying...");
			if (mRetryCount < MAX_RETRIES) {
				mRetryCount++;
				Log.w(getClass().getSimpleName(), "Camera retry " + mRetryCount + "/" + MAX_RETRIES);
				LogBuffer.add("W", getClass().getSimpleName(), "Camera retry " + mRetryCount + "/" + MAX_RETRIES);
				releaseCamera();
				if (mHandler != null) {
					mHandler.postDelayed(this, 2000);
				}
			} else {
				mRetryCount = 0;
				attemptRecovery();
			}
			break;
		default:
			Log.w(getClass().getSimpleName(), "Unknown camera error: " + error);
			LogBuffer.add("W", getClass().getSimpleName(), "Unknown camera error: " + error);
			if (mRetryCount < MAX_RETRIES) {
				mRetryCount++;
				Log.w(getClass().getSimpleName(), "Camera retry " + mRetryCount + "/" + MAX_RETRIES);
				LogBuffer.add("W", getClass().getSimpleName(), "Camera retry " + mRetryCount + "/" + MAX_RETRIES);
				releaseCamera();
				if (mHandler != null) {
					mHandler.postDelayed(this, 2000);
				}
			} else {
				mRetryCount = 0;
				attemptRecovery();
			}
			break;
		}
	}

	private void attemptRecovery() {
		if (mRecoveryCount >= MAX_RECOVERY_ATTEMPTS) {
			LogBuffer.add("E", getClass().getSimpleName(), 
				"Recovery failed after " + MAX_RECOVERY_ATTEMPTS + " attempts - stopping");
			mRecoveryCount = 0;
			handleError(getClass().getSimpleName(), "Recovery failed");
			return;
		}
		
		mRecoveryCount++;
		mRetryCount = 0;
		
		LogBuffer.add("W", getClass().getSimpleName(), 
			"Recovery " + mRecoveryCount + "/" + MAX_RECOVERY_ATTEMPTS);
		
		releaseCamera();
		mCamera = null;
		
		if (mHandler != null) {
			mHandler.postDelayed(() -> {
				try {
					prepareRecord();
					LogBuffer.add("I", getClass().getSimpleName(), 
						"Recovery " + mRecoveryCount + " successful");
					mRecoveryCount = 0;
					scheduleNextPicture();
				} catch (Exception e) {
					LogBuffer.add("E", getClass().getSimpleName(), 
						"Recovery " + mRecoveryCount + " failed: " + e.getMessage());
					scheduleNextPicture();
				}
			}, 3000);
		}
	}
}
