/*
The contents of this file are subject to the Mozilla Public License
Version 1.1 (the "License"); you may not use this file except in
compliance with the License. You may obtain a copy of the License at
http://www.mozilla.org/MPL/

Software distributed under the License is distributed on an "AS IS"
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
License for the specific language governing rights and limitations
under the License.

The Original Code is collection of files collectively known as Open Camera.

The Initial Developer of the Original Code is Almalence Inc.
Portions created by Initial Developer are Copyright (C) 2013 
by Almalence Inc. All Rights Reserved.
 */

package com.almalence.plugins.vf.focus;

import java.util.ArrayList;
import java.util.List;

import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera.Area;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

//<!-- -+-
import com.almalence.opencam.CameraParameters;
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginManager;
import com.almalence.opencam.PluginViewfinder;
import com.almalence.opencam.R;
import com.almalence.opencam.SoundPlayer;
import com.almalence.opencam.cameracontroller.CameraController;

//-+- -->

import com.almalence.util.Util;

/* <!-- +++
 import com.almalence.opencam_plus.cameracontroller.CameraController;
 import com.almalence.opencam_plus.CameraParameters;
 import com.almalence.opencam_plus.MainScreen;
 import com.almalence.opencam_plus.PluginManager;
 import com.almalence.opencam_plus.PluginViewfinder;
 import com.almalence.opencam_plus.R;
 import com.almalence.opencam_plus.SoundPlayer;
 +++ --> */

/***
 * Implements touch to focus functionality
 ***/

public class FocusVFPlugin extends PluginViewfinder
{
	private static final String	TAG								= "AlmalenceFocusPlugin";

	private static final int	RESET_TOUCH_FOCUS				= 0;
	private static final int	START_TOUCH_FOCUS				= 1;
	private static final int	RESET_TOUCH_FOCUS_DELAY			= 5000;
	private static final int	START_TOUCH_FOCUS_DELAY			= 1500;

	private int					mState							= STATE_INACTIVE;
	private static final int	STATE_INACTIVE					= -1;
	private static final int	STATE_IDLE						= 0;						// Focus
																							// is
																							// not
																							// active.
	private static final int	STATE_FOCUSING					= 1;						// Focus
																							// is
																							// in
																							// progress.
	// Focus is in progress and the camera should take a picture after focus
	// finishes.
	private static final int	STATE_FOCUSING_SNAP_ON_FINISH	= 2;
	private static final int	STATE_SUCCESS					= 3;						// Focus
																							// finishes
																							// and
																							// succeeds.
	private static final int	STATE_FAIL						= 4;						// Focus
																							// finishes
																							// and
																							// fails.

	private long				lastTouchTime1					= 0;
	private long				lastTouchTime2					= 0;
	private boolean				mFocusSupported					= true;
	private boolean				mInitialized;
	private boolean				mAeAwbLock;
	private Matrix				mMatrix;
	private SoundPlayer			mSoundPlayerOK;
	private SoundPlayer			mSoundPlayerFalse;
	private RelativeLayout		focusLayout;
	private RotateLayout		mFocusIndicatorRotateLayout;
	private RotateLayout		mMeteringIndicatorRotateLayout;
	private FocusIndicatorView	mFocusIndicator;
	private ImageView			mMeteringIndicator;
	private int					mPreviewWidth;
	private int					mPreviewHeight;
	private List<Area>			mFocusArea;												// focus
																							// area
																							// in
																							// driver
																							// format
	private List<Area>			mMeteringArea;												// metering
																							// area
																							// in
																							// driver
																							// format
	private int					mFocusMode;
	private int					mDefaultFocusMode;
	private int					mOverrideFocusMode				= -1;
	private SharedPreferences	mPreferences;
	private Handler				mHandler;

	// Camera capabilities
	private boolean				mFocusAreaSupported				= false;
	private boolean				mMeteringAreaSupported			= false;

	private boolean				mFocusDisabled					= false;
	private boolean				mFocusLocked					= false;
	private boolean				isDoubleClick					= false;

	private int					preferenceFocusMode				= -1;
	private boolean				splitMode						= false;

	private class MainHandler extends Handler
	{
		@Override
		public void handleMessage(Message msg)
		{
			if (msg.what == RESET_TOUCH_FOCUS && !currentTouch)
				cancelAutoFocus();
			else if (msg.what == START_TOUCH_FOCUS)
			{
				lastEvent.setAction(MotionEvent.ACTION_UP);
				delayedFocus = true;
				cancelAutoFocus();
				onTouchAreas(lastEvent);
				lastEvent.recycle();
			}
		}
	}

	public FocusVFPlugin()
	{
		super("com.almalence.plugins.focusvf", 0, 0, 0, null);

		mHandler = new MainHandler();
		mMatrix = new Matrix();
	}

	@Override
	public void onCreate()
	{
		View v = LayoutInflater.from(MainScreen.getMainContext()).inflate(R.layout.plugin_vf_focus_layout, null);
		focusLayout = (RelativeLayout) v.findViewById(R.id.focus_layout);

		RelativeLayout.LayoutParams viewLayoutParams = new RelativeLayout.LayoutParams(LayoutParams.FILL_PARENT,
				LayoutParams.FILL_PARENT);
		viewLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);

		mFocusIndicatorRotateLayout = (RotateLayout) v.findViewById(R.id.focus_indicator_rotate_layout);
		mMeteringIndicatorRotateLayout = (RotateLayout) v.findViewById(R.id.metering_indicator_rotate_layout);

		mFocusIndicatorRotateLayout.setOnTouchListener(new OnTouchListener()
		{
			@Override
			public boolean onTouch(View v, MotionEvent event)
			{
				updateCurrentTouch(event);

				if (splitMode)
				{
					onTouchFocusArea(event);
					return true;
				} else
				{
					// Check if it's double click
					if (event.getAction() == MotionEvent.ACTION_UP)
					{
						lastTouchTime1 = lastTouchTime2;
						lastTouchTime2 = System.currentTimeMillis();

						if (lastTouchTime2 - lastTouchTime1 < 1000)
						{
							isDoubleClick = true;
						} else
						{
							isDoubleClick = false;
						}
					}

					onTouchFocusAndMeteringArea(event);
					return true;
				}
			}
		});

		mMeteringIndicatorRotateLayout.setOnTouchListener(new OnTouchListener()
		{
			@Override
			public boolean onTouch(View v, MotionEvent event)
			{
				updateCurrentTouch(event);

				onTouchMeteringArea(event);
				return true;
			}
		});

		mFocusIndicator = (FocusIndicatorView) mFocusIndicatorRotateLayout.findViewById(R.id.focus_indicator);
		mMeteringIndicator = (ImageView) mMeteringIndicatorRotateLayout.findViewById(R.id.metering_indicator);

		resetTouchFocus();

		mPreferences = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
		{
			mDefaultFocusMode = CameraParameters.AF_MODE_CONTINUOUS_PICTURE;
		} else
		{
			mDefaultFocusMode = CameraParameters.AF_MODE_AUTO;
		}
	}

	private void updateCurrentTouch(MotionEvent event)
	{
		if (event.getAction() == MotionEvent.ACTION_DOWN)
		{
			currentTouch = true;
		}

		if (event.getAction() == MotionEvent.ACTION_UP)
		{
			currentTouch = false;
		}
	}

	@Override
	public void onStart()
	{
		mState = STATE_IDLE;
		updateFocusUI();

		CameraController.setFocusState(CameraController.FOCUS_STATE_IDLE);
	}

	@Override
	public void onStop()
	{
		// cancelAutoFocus();
		mState = STATE_INACTIVE;
		updateFocusUI();

		CameraController.setFocusState(CameraController.FOCUS_STATE_IDLE);

		MainScreen.getGUIManager().removeViews(focusLayout, R.id.specialPluginsLayout);
	}

	@Override
	public void onGUICreate()
	{
		MainScreen.getGUIManager().removeViews(focusLayout, R.id.specialPluginsLayout);

		RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(LayoutParams.FILL_PARENT,
				LayoutParams.FILL_PARENT);
		params.addRule(RelativeLayout.CENTER_IN_PARENT);

		((RelativeLayout) MainScreen.getInstance().findViewById(R.id.specialPluginsLayout)).addView(this.focusLayout,
				params);

		this.focusLayout.setLayoutParams(params);
		this.focusLayout.requestLayout();
	}

	@Override
	public void onResume()
	{
		// // replace here with [CF] mode as default.
		// // Also, check if [CF] is available, and if not - set [AF], if [AF]
		// is
		// // not available - set first available
		// preferenceFocusMode =
		// PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getInt(
		// CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref :
		// MainScreen.sFrontFocusModePref, MainScreen.sDefaultFocusValue);
		//
		// int[] supportedFocusModes =
		// CameraController.getSupportedFocusModes();
		// if(supportedFocusModes != null)
		// {
		// if (!CameraController.isModeAvailable(supportedFocusModes,
		// preferenceFocusMode))
		// if(CameraController.isModeAvailable(supportedFocusModes,
		// CameraParameters.AF_MODE_AUTO))
		// preferenceFocusMode = CameraParameters.AF_MODE_AUTO;
		// else
		// preferenceFocusMode = supportedFocusModes[0];
		// }
	}

	@Override
	public void onPause()
	{
		PreferenceManager
				.getDefaultSharedPreferences(MainScreen.getMainContext())
				.edit()
				.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
						: MainScreen.sFrontFocusModePref, preferenceFocusMode).commit();

		// Log.e(TAG, "onPause. PUT INT FOCUS = " + preferenceFocusMode);
		releaseSoundPlayer();
		removeMessages();
	}

	@Override
	public void onCameraParametersSetup()
	{
		// replace here with [CF] mode as default.
		// Also, check if [CF] is available, and if not - set [AF], if [AF] is
		// not available - set first available
		preferenceFocusMode = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getInt(
				CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref : MainScreen.sFrontFocusModePref,
				MainScreen.sDefaultFocusValue);

		int[] supportedFocusModes = CameraController.getSupportedFocusModes();
		if (supportedFocusModes != null)
		{
			if (!CameraController.isModeAvailable(supportedFocusModes, preferenceFocusMode)
					&& preferenceFocusMode != CameraParameters.MF_MODE)
			{
				if (CameraController.isModeAvailable(supportedFocusModes, CameraParameters.AF_MODE_AUTO))
					preferenceFocusMode = CameraParameters.AF_MODE_AUTO;
				else
					preferenceFocusMode = supportedFocusModes[0];
			}
		}

		// preferenceFocusMode = CameraController.getFocusMode();
		// Log.e(TAG, "onCameraParametersSetup. FOCUS = " +
		// preferenceFocusMode);

		initializeParameters();

		initialize(CameraController.isFrontCamera(), 90);
		initializeSoundPlayers(MainScreen.getAppResources().openRawResourceFd(R.raw.plugin_vf_focus_ok), MainScreen
				.getAppResources().openRawResourceFd(R.raw.plugin_vf_focus_false));

		cancelAutoFocus();

		// Set the length of focus indicator according to preview frame size.
		int len = Math.min(mPreviewWidth, mPreviewHeight) / 25;
		ViewGroup.LayoutParams layout = mFocusIndicator.getLayoutParams();
		layout.width = (int) (len * MainScreen.getAppResources().getInteger(R.integer.focusIndicator_cropFactor));
		layout.height = (int) (len * MainScreen.getAppResources().getInteger(R.integer.focusIndicator_cropFactor));
		mFocusIndicator.requestLayout();

		layout = mMeteringIndicator.getLayoutParams();
		layout.width = (int) (len * MainScreen.getAppResources().getInteger(R.integer.focusIndicator_cropFactor));
		layout.height = (int) (len * MainScreen.getAppResources().getInteger(R.integer.focusIndicator_cropFactor));
		mMeteringIndicator.requestLayout();
	}

	/*
	 * 
	 * 
	 * FOCUS MANAGER`S FUNCTIONALITY
	 */
	// This has to be initialized before initialize().
	public void initializeParameters()
	{
		mFocusAreaSupported = (CameraController.getMaxFocusAreasSupported() > 0 && isSupported(
				CameraParameters.AF_MODE_AUTO, CameraController.getSupportedFocusModes()));
		mMeteringAreaSupported = CameraController.getMaxMeteringAreasSupported() > 0;
	}

	public void initialize(boolean mirror, int displayOrientation)
	{
		mPreviewWidth = MainScreen.getPreviewSurfaceLayoutWidth();
		mPreviewHeight = MainScreen.getPreviewSurfaceLayoutHeight();

		Matrix matrix = new Matrix();
		Util.prepareMatrix(matrix, mirror, 90, mPreviewWidth, mPreviewHeight);
		// In face detection, the matrix converts the driver coordinates to UI
		// coordinates. In tap focus, the inverted matrix converts the UI
		// coordinates to driver coordinates.
		matrix.invert(mMatrix);

		mInitialized = true;
	}

	@Override
	public void onShutterClick()
	{
		if (needAutoFocusCall() && !focusOnShutterDisabled())
		{
			if (mState == STATE_IDLE
					&& !(preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE || preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
					&& !MainScreen.getAutoFocusLock())
			{
				if (preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
						|| preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
				{
					CameraController.setCameraFocusMode(CameraParameters.AF_MODE_AUTO);
				}
				setFocusParameters();
				setMeteringParameters();
				autoFocus();
			}
			// else if ((mState == STATE_SUCCESS || mState == STATE_FAIL)
			// && (preferenceFocusMode ==
			// CameraParameters.AF_MODE_CONTINUOUS_PICTURE ||
			// preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
			// && preferenceFocusMode != CameraController.getFocusMode())
			// {
			// // allow driver to choose whatever it wants for focusing /
			// // metering
			// // without these two lines Continuous focus is not re-enabled on
			// // HTC One
			// int focusMode = getFocusMode();
			// if ((focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
			// || focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO
			// || focusMode == CameraParameters.AF_MODE_AUTO || focusMode ==
			// CameraParameters.AF_MODE_MACRO)
			// && mFocusAreaSupported)
			// {
			// CameraController.setCameraFocusAreas(null);
			// }
			// CameraController.setCameraFocusMode(preferenceFocusMode);
			// }
			else if (mState == STATE_FAIL)
				MainScreen.getGUIManager().lockControls = false;
		}
	}

	@Override
	public void onFocusButtonClick()
	{
		if (needAutoFocusCall() && !focusOnShutterDisabled())
		{
			cancelAutoFocus();
			resetTouchFocus();
			autoFocus();
		}
	}

	public void setFocusParameters()
	{
		if (mFocusAreaSupported)
			CameraController.setCameraFocusAreas(getFocusAreas());

	}

	public void setMeteringParameters()
	{
		if (mMeteringAreaSupported)
		{
			// Use the same area for focus and metering.
			List<Area> area = getMeteringAreas();
			if (area != null)
			{
				// CameraController.setCameraMeteringAreas(null);
				CameraController.setCameraMeteringAreas(area);
			}
		}
	}

	@Override
	public void onAutoFocusMoving(boolean start)
	{
		if (!splitMode)
			return;

		if (start)
		{
			mState = STATE_FOCUSING;
		} else
		{
			mState = STATE_SUCCESS;
		}

		updateFocusUI();
	}

	@Override
	public void onAutoFocus(boolean focused)
	{
		if (mState == STATE_FOCUSING_SNAP_ON_FINISH)
		{
			// Take the picture no matter focus succeeds or fails. No need
			// to play the AF sound if we're about to play the shutter
			// sound.
			if (focused)
			{
				mState = STATE_SUCCESS;
			} else
			{
				mState = STATE_FAIL;
				MainScreen.getGUIManager().lockControls = false;
			}
			updateFocusUI();
		} else if (mState == STATE_FOCUSING)
		{
			// This happens when (1) user is half-pressing the focus key or
			// (2) touch focus is triggered. Play the focus tone. Do not
			// take the picture now.
			if (focused)
			{
				mState = STATE_SUCCESS;
				/*
				 * Note: we are always using full-focus scan, even in continuous
				 * modes
				 * 
				 * // Do not play the sound in continuous autofocus mode. It
				 * does // not do a full scan. The focus callback arrives before
				 * doSnap // so the state is always STATE_FOCUSING. if
				 * (!Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(mFocusMode)
				 * && mSoundPlayerOK != null)
				 */
				if (mSoundPlayerOK != null && !splitMode && !currentTouch)
					if (!MainScreen.isShutterSoundEnabled() && !PluginManager.getInstance().muteSounds())
						mSoundPlayerOK.play();

				// With enabled preference 'Shot on tap' perform shutter button
				// click after success focusing.
				String modeID = PluginManager.getInstance().getActiveMode().modeID;
				if (MainScreen.isShotOnTap() == 1 && !modeID.equals("video") && !splitMode && !currentTouch)
					MainScreen.getGUIManager().onHardwareShutterButtonPressed();

				if (MainScreen.isShotOnTap() == 2 && !modeID.equals("video") && !splitMode && !currentTouch)
				{
					if (isDoubleClick)
					{
						MainScreen.getGUIManager().onHardwareShutterButtonPressed();
						isDoubleClick = false;
					}
				}
			} else
			{
				if (mSoundPlayerFalse != null)
					if (!MainScreen.isShutterSoundEnabled() && !PluginManager.getInstance().muteSounds())
						mSoundPlayerFalse.play();
				mState = STATE_FAIL;
			}
			updateFocusUI();

			if (!splitMode)
				mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, RESET_TOUCH_FOCUS_DELAY);
			// If this is triggered by touch focus, cancel focus after a
			// while.
		} else if (mState == STATE_IDLE)
		{
			// User has released the focus key before focus completes.
			// Do nothing.
		}
	}

	private static float		X				= 0;
	private static float		Y				= 0;
	private static boolean		focusCanceled	= false;
	private static boolean		delayedFocus	= false;
	private static MotionEvent	lastEvent		= null;
	private static boolean		currentTouch	= false;

	@Override
	public boolean onTouch(View view, MotionEvent e)
	{
		updateCurrentTouch(e);

		if (e.getPointerCount() > 1)
		{
			splitMode = true;
			mHandler.removeMessages(START_TOUCH_FOCUS);

			onTouchFocusArea(e);
			onTouchMeteringArea(e);

			return true;
		}

		if (splitMode && e.getAction() == MotionEvent.ACTION_DOWN)
		{
			mMeteringIndicatorRotateLayout.setVisibility(View.GONE);
			MainScreen.getInstance().setCameraMeteringMode(MainScreen.getMeteringMode());
			splitMode = false;
		}

		if (splitMode)
		{
			return true;
		}

		// Check if it's double click
		if (e.getAction() == MotionEvent.ACTION_UP)
		{
			lastTouchTime1 = lastTouchTime2;
			lastTouchTime2 = System.currentTimeMillis();

			if (lastTouchTime2 - lastTouchTime1 < 1000)
			{
				isDoubleClick = true;

				// If shot on double click
				if (MainScreen.isShotOnTap() == 2)
				{
					// Cancel delayed focus, which was created by second click
					mHandler.removeMessages(START_TOUCH_FOCUS);

					// If state is Focused start capture
					if (mState == STATE_SUCCESS)
					{
						String modeID = PluginManager.getInstance().getActiveMode().modeID;
						if (!modeID.equals("video") && !currentTouch)
						{
							MainScreen.getGUIManager().onHardwareShutterButtonPressed();
							isDoubleClick = false;
						}
					}
					return true;
				}
			} else
			{
				isDoubleClick = false;
			}
		}

		// Not handle touch event if no need of autoFocus and refuse 'shot on
		// tap' in video mode.
		if (!mInitialized
				|| mState == STATE_FOCUSING_SNAP_ON_FINISH
				|| mState == STATE_INACTIVE
				|| mFocusDisabled
				|| !CameraController.isFocusModeSupported()
				|| (!(needAutoFocusCall() || isContinuousFocusMode()) && !(MainScreen.isShotOnTap() > 0 && !PluginManager
						.getInstance().getActiveMode().modeID.equals("video"))))
			return false;

		// Let users be able to cancel previous touch focus.
		if ((mState == STATE_FOCUSING) && !delayedFocus && MainScreen.isShotOnTap() != 2)
		{
			focusCanceled = true;
			cancelAutoFocus();
			int fm = CameraController.getFocusMode();
			if ((preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE || preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
					&& fm != -1
					&& preferenceFocusMode != CameraController.getFocusMode()
					&& preferenceFocusMode != CameraParameters.MF_MODE)
			{
				CameraController.setCameraFocusMode(preferenceFocusMode);
			} else if (Build.MODEL.contains("SM-N900") && preferenceFocusMode != CameraParameters.MF_MODE)
			// Kind of hack to
			// prevent Note 3 of
			// permanent 'auto focus
			// failed' state
			{
				CameraController.setCameraFocusMode(CameraParameters.AF_MODE_CONTINUOUS_PICTURE);
				CameraController.setCameraFocusMode(preferenceFocusMode);
			}
			return true;
		}

		switch (e.getAction())
		{
		case MotionEvent.ACTION_DOWN:
			focusCanceled = false;
			delayedFocus = false;
			X = e.getX();
			Y = e.getY();

			lastEvent = MotionEvent.obtain(e);
			mHandler.sendEmptyMessageDelayed(START_TOUCH_FOCUS, START_TOUCH_FOCUS_DELAY);

			return true;
		case MotionEvent.ACTION_MOVE:
			{
				float difX = e.getX();
				float difY = e.getY();

				if ((Math.abs(difX - X) > 50 || Math.abs(difY - Y) > 50) && !focusCanceled)
				{
					focusCanceled = true;
					cancelAutoFocus();
					mHandler.removeMessages(START_TOUCH_FOCUS);
					return true;
				} else
					return true;
			}
		case MotionEvent.ACTION_UP:
			mHandler.removeMessages(START_TOUCH_FOCUS);
			if (focusCanceled || delayedFocus)
				return true;
			break;
		default:
			break;
		}

		onTouchAreas(e);

		return true;
	}

	public void onTouchFocusArea(MotionEvent e)
	{
		if (!mFocusAreaSupported)
			return;

		int xRaw = (int) e.getRawX();
		int yRaw = (int) e.getRawY();

		if (e.getPointerCount() > 1)
		{
			final int location[] = { 0, 0 };
			focusLayout.getLocationOnScreen(location);
			xRaw = (int) e.getX(0) + location[0];
			yRaw = (int) e.getY(0) + location[1];
		}

		// Initialize variables.
		int focusWidth = mFocusIndicatorRotateLayout.getWidth();
		int focusHeight = mFocusIndicatorRotateLayout.getHeight();
		int previewWidth = mPreviewWidth;
		int previewHeight = mPreviewHeight;
		int displayWidth = MainScreen.getAppResources().getDisplayMetrics().widthPixels;
		int displayHeight = MainScreen.getAppResources().getDisplayMetrics().heightPixels;
		int diffWidth = displayWidth - previewWidth;
		int diffHeight = displayHeight - previewHeight;

		// Initialize variables.
		int paramsLayoutHeight = 0;

		int xOffset = (focusLayout.getWidth() - previewWidth) / 2;
		int yOffset = (focusLayout.getHeight() - previewHeight) / 2;

		if (mFocusArea == null)
		{
			mFocusArea = new ArrayList<Area>();
			mFocusArea.add(new Area(new Rect(), 1000));
		}

		// Use margin to set the metering indicator to the touched area.
		RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
		int left = Util.clamp(xRaw - focusWidth / 2 + xOffset, diffWidth / 2, (previewWidth - focusWidth + xOffset * 2)
				- diffWidth / 2);
		int top = Util.clamp(yRaw - focusHeight / 2 + yOffset - diffHeight / 2, 0, previewHeight - focusHeight
				+ yOffset * 2);

		p.leftMargin = left;
		p.topMargin = top;

		int[] rules = p.getRules();
		rules[RelativeLayout.CENTER_IN_PARENT] = 0;

		mFocusIndicatorRotateLayout.setLayoutParams(p);

		calculateTapAreaByTopLeft(focusWidth, focusHeight, 1f, top, left,
				MainScreen.getPreviewSurfaceView().getWidth(), MainScreen.getPreviewSurfaceView().getHeight(),
				mFocusArea.get(0).rect);

		// Set the focus area and metering area.
		if (mFocusAreaSupported
				&& (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_POINTER_UP))
		{
			setFocusParameters();

			int focusMode = CameraController.getFocusMode();
			if (focusMode == CameraParameters.AF_MODE_AUTO || focusMode == CameraParameters.AF_MODE_MACRO)
			{
				CameraController.cancelAutoFocus();
				autoFocus();
			} else
			{
				mState = STATE_FOCUSING;
				updateFocusUI();
			}
		}
		mFocusIndicatorRotateLayout.requestLayout();
	}

	public void onTouchMeteringArea(MotionEvent e)
	{
		if (!mMeteringAreaSupported || Build.MODEL.contains("SM-N900") || Build.MODEL.contains("SM-G900"))
		{
			if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
					|| e.getActionMasked() == MotionEvent.ACTION_DOWN)
				Toast.makeText(MainScreen.getInstance(), R.string.manual_exposure_unsupported, Toast.LENGTH_SHORT)
						.show();

			return;
		}

		int xRaw = (int) e.getRawX();
		int yRaw = (int) e.getRawY();

		if (e.getPointerCount() > 1)
		{
			final int location[] = { 0, 0 };
			focusLayout.getLocationOnScreen(location);
			xRaw = (int) e.getX(1) + location[0];
			yRaw = (int) e.getY(1) + location[1];
		}

		int meteringWidth = mMeteringIndicatorRotateLayout.getWidth();
		int meteringHeight = mMeteringIndicatorRotateLayout.getHeight();
		int previewWidth = mPreviewWidth;
		int previewHeight = mPreviewHeight;
		int displayWidth = MainScreen.getAppResources().getDisplayMetrics().widthPixels;
		int displayHeight = MainScreen.getAppResources().getDisplayMetrics().heightPixels;
		int diffWidth = displayWidth - previewWidth;
		int diffHeight = displayHeight - previewHeight;

		// Initialize variables.
		int paramsLayoutHeight = 0;

		int xOffset = (focusLayout.getWidth() - previewWidth) / 2;
		int yOffset = (focusLayout.getHeight() - previewHeight) / 2;

		if (mMeteringArea == null)
		{
			mMeteringArea = new ArrayList<Area>();
			mMeteringArea.add(new Area(new Rect(), 1000));
		}

		// Use margin to set the metering indicator to the touched area.
		RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mMeteringIndicatorRotateLayout.getLayoutParams();
		int left = Util.clamp(xRaw - meteringWidth / 2 + xOffset, diffWidth / 2,
				(previewWidth - meteringWidth + xOffset * 2) - diffWidth / 2);
		int top = Util.clamp(yRaw - meteringHeight / 2 + yOffset - diffHeight / 2, 0, previewHeight - meteringHeight
				+ yOffset * 2);

		p.leftMargin = left;
		p.topMargin = top;

		mMeteringIndicatorRotateLayout.setLayoutParams(p);

		// Convert the coordinates to driver format.
		calculateTapAreaByTopLeft(meteringWidth, meteringHeight, 1f, top, left, MainScreen.getPreviewSurfaceView()
				.getWidth(), MainScreen.getPreviewSurfaceView().getHeight(), mMeteringArea.get(0).rect);

		// Set the focus area and metering area.
		if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN || e.getActionMasked() == MotionEvent.ACTION_DOWN)
		{
			mMeteringIndicatorRotateLayout.setVisibility(View.VISIBLE);
		}

		if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP || e.getActionMasked() == MotionEvent.ACTION_UP)
		{
			setMeteringParameters();
		}

		mMeteringIndicatorRotateLayout.requestLayout();
	}

	public void onTouchFocusAndMeteringArea(MotionEvent e)
	{
		if (!mFocusAreaSupported)
			return;

		int xRaw = (int) e.getRawX();
		int yRaw = (int) e.getRawY();

		// Initialize variables.
		int focusWidth = mFocusIndicatorRotateLayout.getWidth();
		int focusHeight = mFocusIndicatorRotateLayout.getHeight();
		int previewWidth = mPreviewWidth;
		int previewHeight = mPreviewHeight;
		int displayWidth = MainScreen.getAppResources().getDisplayMetrics().widthPixels;
		int displayHeight = MainScreen.getAppResources().getDisplayMetrics().heightPixels;
		int diffWidth = displayWidth - previewWidth;
		int diffHeight = displayHeight - previewHeight;

		// Initialize variables.
		int xOffset = (focusLayout.getWidth() - previewWidth) / 2;
		int yOffset = (focusLayout.getHeight() - previewHeight) / 2;

		if (mFocusArea == null)
		{
			mFocusArea = new ArrayList<Area>();
			mFocusArea.add(new Area(new Rect(), 1000));
		}

		if (mMeteringArea == null)
		{
			mMeteringArea = new ArrayList<Area>();
			mMeteringArea.add(new Area(new Rect(), 1000));
		}

		// Use margin to set the metering indicator to the touched area.
		RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
		int left = Util.clamp(xRaw - focusWidth / 2 + xOffset, diffWidth / 2, (previewWidth - focusWidth + xOffset * 2)
				- diffWidth / 2);
		int top = Util.clamp(yRaw - focusHeight / 2 + yOffset - diffHeight / 2, 0, previewHeight - focusHeight
				+ yOffset * 2);

		p.leftMargin = left;
		p.topMargin = top;

		int[] rules = p.getRules();
		rules[RelativeLayout.CENTER_IN_PARENT] = 0;

		mFocusIndicatorRotateLayout.setLayoutParams(p);

		calculateTapAreaByTopLeft(focusWidth, focusHeight, 1f, top, left,
				MainScreen.getPreviewSurfaceView().getWidth(), MainScreen.getPreviewSurfaceView().getHeight(),
				mFocusArea.get(0).rect);

		if (MainScreen.getMeteringMode() != -1 && MainScreen.getMeteringMode() == CameraParameters.meteringModeSpot)
			calculateTapAreaByTopLeft(focusWidth, focusHeight, 1f, top, left, MainScreen.getPreviewSurfaceView()
					.getWidth(), MainScreen.getPreviewSurfaceView().getHeight(), mMeteringArea.get(0).rect);
		else
			mMeteringArea = null;

		// Set the focus area and metering area.
		if (mFocusAreaSupported && needAutoFocusCall() && (e.getAction() == MotionEvent.ACTION_UP))
		{
			CameraController.cancelAutoFocus();
			if (preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
					|| preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
			{
				CameraController.setCameraFocusMode(CameraParameters.AF_MODE_AUTO);
			}

			// This time is useful for Android L. Camera2 dosn't have time
			// between cancelAutoFocus and autoFocus calls
			// to reset current focus state and initiate new focusing procedure.
			// If autoFocus is called right after
			// cancelAutoFocus then success focus state (FOCUS_LOCKED) return
			// immediately and re-focusing occurs after
			// image capturing is called.
			// new CountDownTimer(100, 100)
			// {
			// public void onTick(long millisUntilFinished)
			// {
			// // Not used
			// }
			//
			// public void onFinish()
			// {
			// setFocusParameters();
			// autoFocus();
			// }
			// }.start();
			// Back to direct call to work on S2. TODO: Check on Android 5
			setFocusParameters();
			setMeteringParameters();
			autoFocus();

		} else if (e.getAction() == MotionEvent.ACTION_UP && MainScreen.isShotOnTap() == 1
				&& !PluginManager.getInstance().getActiveMode().modeID.equals("video") && !currentTouch)
		{

			MainScreen.getGUIManager().onHardwareShutterButtonPressed();
		} else if (e.getAction() == MotionEvent.ACTION_UP && MainScreen.isShotOnTap() == 2
				&& !PluginManager.getInstance().getActiveMode().modeID.equals("video") && !currentTouch)
		{
			if (isDoubleClick)
			{
				MainScreen.getGUIManager().onHardwareShutterButtonPressed();
				isDoubleClick = false;
			}
		} else if (e.getAction() == MotionEvent.ACTION_UP)
		{ // Just show the indicator in all other cases.
			autoFocus();
			// updateFocusUI();
			// Reset the metering area in 3 seconds.
			mHandler.removeMessages(RESET_TOUCH_FOCUS);
			mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, RESET_TOUCH_FOCUS_DELAY);
		}
		
		mFocusIndicatorRotateLayout.requestLayout();
	}

	public void onTouchAreas(MotionEvent e)
	{
		// Initialize variables.
		int x = Math.round(e.getX());
		int y = Math.round(e.getY());
		int focusWidth = mFocusIndicatorRotateLayout.getWidth();
		int focusHeight = mFocusIndicatorRotateLayout.getHeight();
		int previewWidth = mPreviewWidth;
		int previewHeight = mPreviewHeight;
		int displayWidth = MainScreen.getAppResources().getDisplayMetrics().widthPixels;
		int diffWidth = displayWidth - previewWidth;

		int paramsLayoutHeight = 0;

		int xOffset = (focusLayout.getWidth() - previewWidth) / 2;
		int yOffset = (focusLayout.getHeight() - previewHeight) / 2;

		if (mFocusArea == null)
		{
			mFocusArea = new ArrayList<Area>();
			mFocusArea.add(new Area(new Rect(), 1000));
		}

		if (mMeteringArea == null)
		{
			mMeteringArea = new ArrayList<Area>();
			mMeteringArea.add(new Area(new Rect(), 1000));
		}

		boolean isNexus6 = Build.MODEL.contains("Nexus 6");
		// Convert the coordinates to driver format.
		// AE area is bigger because exposure is sensitive and
		// easy to over- or underexposure if area is too small.
		calculateTapArea(focusWidth, focusHeight, 1f, x, y, MainScreen.getPreviewSurfaceView().getWidth(), MainScreen
				.getPreviewSurfaceView().getHeight(), mFocusArea.get(0).rect);
		if (MainScreen.getMeteringMode() != -1 && MainScreen.getMeteringMode() == CameraParameters.meteringModeSpot)
			calculateTapArea(20 + (isNexus6 ? focusWidth : 0), 20 + (isNexus6 ? focusHeight : 0), 1f, x, y, MainScreen
					.getPreviewSurfaceView().getWidth(), MainScreen.getPreviewSurfaceView().getHeight(),
					mMeteringArea.get(0).rect);
		else
			mMeteringArea = null;

		if (mFocusAreaSupported)
		{
			// Use margin to set the focus indicator to the touched area.
			RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
			int left = Util.clamp(x - focusWidth / 2 + xOffset, diffWidth / 2,
					(previewWidth - focusWidth + xOffset * 2) - diffWidth / 2);
			int top = Util.clamp(y - focusHeight / 2 + yOffset, paramsLayoutHeight / 2,
					(previewHeight - focusHeight + yOffset * 2) - paramsLayoutHeight / 2);
			p.setMargins(left, top, 0, 0);
			// Disable "center" rule because we no longer want to put it in the
			// center.
			int[] rules = p.getRules();
			rules[RelativeLayout.CENTER_IN_PARENT] = 0;
			mFocusIndicatorRotateLayout.requestLayout();
		}

		// Set the focus area and metering area.
		if (mFocusAreaSupported && needAutoFocusCall() && (e.getAction() == MotionEvent.ACTION_UP))
		{
			CameraController.cancelAutoFocus();
			if (preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
					|| preferenceFocusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO)
			{
				CameraController.setCameraFocusMode(CameraParameters.AF_MODE_AUTO);
			}

			// This time is useful for Android L. Camera2 dosn't have time
			// between cancelAutoFocus and autoFocus calls
			// to reset current focus state and initiate new focusing procedure.
			// If autoFocus is called right after
			// cancelAutoFocus then success focus state (FOCUS_LOCKED) return
			// immediately and re-focusing occurs after
			// image capturing is called.
			// new CountDownTimer(100, 100)
			// {
			// public void onTick(long millisUntilFinished)
			// {
			// // Not used
			// }
			//
			// public void onFinish()
			// {
			// setFocusParameters();
			// autoFocus();
			// }
			// }.start();
			// Back to direct call to work on S2. TODO: Check on Android 5
			setFocusParameters();
			setMeteringParameters();
			autoFocus();

		} else if (e.getAction() == MotionEvent.ACTION_UP && MainScreen.isShotOnTap() == 1
				&& !PluginManager.getInstance().getActiveMode().modeID.equals("video"))
		{

			MainScreen.getGUIManager().onHardwareShutterButtonPressed();
		} else if (e.getAction() == MotionEvent.ACTION_UP && MainScreen.isShotOnTap() == 2
				&& !PluginManager.getInstance().getActiveMode().modeID.equals("video"))
		{
			if (isDoubleClick)
			{
				MainScreen.getGUIManager().onHardwareShutterButtonPressed();
				isDoubleClick = false;
			}
		} else
		{ // Just show the indicator in all other cases.
			autoFocus();
			// updateFocusUI();
			// Reset the metering area in 3 seconds.
			mHandler.removeMessages(RESET_TOUCH_FOCUS);
			mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, RESET_TOUCH_FOCUS_DELAY);
		}
	}

	public void onPreviewStarted()
	{
		mState = STATE_IDLE;
		CameraController.setFocusState(CameraController.FOCUS_STATE_IDLE);
	}

	public void onPreviewStopped()
	{
		mState = STATE_IDLE;
		resetTouchFocus();
		// If auto focus was in progress, it would have been canceled.
		updateFocusUI();

		CameraController.setFocusState(CameraController.FOCUS_STATE_IDLE);
	}

	public void onCameraReleased()
	{
		onPreviewStopped();
	}

	private void manualFocusStart()
	{
		updateFocusUI();
	}

	private void autoFocus()
	{
		if (CameraController.autoFocus())
		{
			mState = STATE_FOCUSING;
			updateFocusUI();
			mHandler.removeMessages(RESET_TOUCH_FOCUS);
		}
	}

	private void cancelAutoFocus()
	{
		Log.e(TAG, "cancelAutofocus");
		// Note: CameraController.getFocusMode(); will return
		// 'FOCUS_MODE_AUTO' if actual
		// mode is in fact FOCUS_MODE_CONTINUOUS_PICTURE or
		// FOCUS_MODE_CONTINUOUS_VIDEO
		int fm = CameraController.getFocusMode();
		if (fm != -1)
		{
			// CameraController.cancelAutoFocus();
			if (fm != preferenceFocusMode && preferenceFocusMode != CameraParameters.MF_MODE)
			{
				// Log.e(TAG, "cancelAutoFocus. setFocusMode = " +
				// preferenceFocusMode);
				CameraController.cancelAutoFocus();
				CameraController.setCameraFocusMode(preferenceFocusMode);
			}
		}

		// Reset the tap area before calling mListener.cancelAutofocus.
		// Otherwise, focus mode stays at auto and the tap area passed to the
		// driver is not reset.
		CameraController.setCameraFocusAreas(null);
		MainScreen.getInstance().setCameraMeteringMode(MainScreen.getMeteringMode());
		resetTouchFocus();

		mState = STATE_IDLE;
		CameraController.setFocusState(CameraController.FOCUS_STATE_IDLE);

		updateFocusUI();
		mHandler.removeMessages(RESET_TOUCH_FOCUS);
	}

	public void initializeSoundPlayers(AssetFileDescriptor fd_ok, AssetFileDescriptor fd_false)
	{
		mSoundPlayerOK = new SoundPlayer(MainScreen.getMainContext(), fd_ok);
		mSoundPlayerFalse = new SoundPlayer(MainScreen.getMainContext(), fd_false);
	}

	public void releaseSoundPlayer()
	{
		if (mSoundPlayerOK != null)
		{
			mSoundPlayerOK.release();
			mSoundPlayerOK = null;
		}

		if (mSoundPlayerFalse != null)
		{
			mSoundPlayerFalse.release();
			mSoundPlayerFalse = null;
		}
	}

	// This can only be called after mParameters is initialized.
	public int getFocusMode()
	{
		if (mOverrideFocusMode != -1)
			return mOverrideFocusMode;

		if (mFocusAreaSupported && mFocusArea != null)
			mFocusMode = CameraParameters.AF_MODE_AUTO;
		else
			mFocusMode = mPreferences.getInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
					: MainScreen.sFrontFocusModePref, mDefaultFocusMode);

		if (!isSupported(mFocusMode, CameraController.getSupportedFocusModes()))
		{
			// For some reasons, the driver does not support the current
			// focus mode. Fall back to auto.
			if (isSupported(CameraParameters.AF_MODE_AUTO, CameraController.getSupportedFocusModes()))
				mFocusMode = CameraParameters.AF_MODE_AUTO;
			else
				mFocusMode = CameraController.getFocusMode();
		}
		return mFocusMode;
	}

	// public void setFocusMode(int focus_mode)
	// {
	// mPreferences
	// .edit()
	// .putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
	// : MainScreen.sFrontFocusModePref, focus_mode).commit();
	// preferenceFocusMode = focus_mode;
	// }

	public List<Area> getFocusAreas()
	{
		return mFocusArea;
	}

	public List<Area> getMeteringAreas()
	{
		return mMeteringArea;
	}

	public void updateFocusUI()
	{
		if (!mInitialized || !mFocusSupported)
			return;

		FocusIndicator focusIndicator = mFocusIndicator;

		if (mState == STATE_IDLE || mState == STATE_INACTIVE)
		{
			if (mFocusArea == null)
				focusIndicator.clear();
			else
			{
				// Users touch on the preview and the indicator represents the
				// metering area. Either focus area is not supported or
				// autoFocus call is not required.
				focusIndicator.showStart();
			}
		} else if (mState == STATE_FOCUSING || mState == STATE_FOCUSING_SNAP_ON_FINISH)
			focusIndicator.showStart();
		else
		{
			if (mState == STATE_SUCCESS)
				focusIndicator.showSuccess();
			else if (mState == STATE_FAIL)
				focusIndicator.showFail();
		}
	}

	public void resetTouchFocus()
	{
		if (!mInitialized)
			return;

		splitMode = false;
		mMeteringIndicatorRotateLayout.setVisibility(View.GONE);

		// Put focus indicator to the center.
		RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mFocusIndicatorRotateLayout.getLayoutParams();
		int[] rules = p.getRules();
		rules[RelativeLayout.CENTER_IN_PARENT] = RelativeLayout.TRUE;
		p.setMargins(0, 0, 0, 0);

		mFocusArea = null;
		mMeteringArea = null;

		// allow driver to choose whatever it wants for focusing / metering
		// without these two lines Continuous focus is not re-enabled on HTC One
		int focusMode = getFocusMode();
		if ((focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
				|| focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO || focusMode == CameraParameters.AF_MODE_AUTO || focusMode == CameraParameters.AF_MODE_MACRO)
				&& mFocusAreaSupported)
		{
			String modeName = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getString(
					"defaultModeName", null);
			boolean isVideoRecording = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext())
					.getBoolean("videorecording", false);
		}
	}

	public void calculateTapArea(int focusWidth, int focusHeight, float areaMultiple, int x, int y, int previewWidth,
			int previewHeight, Rect rect)
	{
		int areaWidth = (int) (focusWidth * areaMultiple);
		int areaHeight = (int) (focusHeight * areaMultiple);
		int left = Util.clamp(x - areaWidth / 2, 0, previewWidth - areaWidth);
		int top = Util.clamp(y - areaHeight / 2, 0, previewHeight - areaHeight);

		int right = Util.clamp(x + areaWidth / 2, areaWidth, previewWidth);
		int bottom = Util.clamp(y + areaHeight / 2, areaHeight, previewHeight);

		RectF rectF = new RectF(left, top, right, bottom);
		mMatrix.mapRect(rectF);
		Util.rectFToRect(rectF, rect);

		if (rect.left < -1000)
			rect.left = -1000;
		if (rect.left > 1000)
			rect.left = 1000;

		if (rect.right < -1000)
			rect.right = -1000;
		if (rect.right > 1000)
			rect.right = 1000;

		if (rect.top < -1000)
			rect.top = -1000;
		if (rect.top > 1000)
			rect.top = 1000;

		if (rect.bottom < -1000)
			rect.bottom = -1000;
		if (rect.bottom > 1000)
			rect.bottom = 1000;
	}

	public void calculateTapAreaByTopLeft(int focusWidth, int focusHeight, float areaMultiple, int top, int left,
			int previewWidth, int previewHeight, Rect rect)
	{
		int areaWidth = (int) (focusWidth * areaMultiple);
		int areaHeight = (int) (focusHeight * areaMultiple);

		int right = Util.clamp(left + areaWidth, areaWidth, previewWidth);
		int bottom = Util.clamp(top + areaHeight, areaHeight, previewHeight);

		RectF rectF = new RectF(left, top, right, bottom);
		mMatrix.mapRect(rectF);
		Util.rectFToRect(rectF, rect);

		if (rect.left < -1000)
			rect.left = -1000;
		if (rect.left > 1000)
			rect.left = 1000;

		if (rect.right < -1000)
			rect.right = -1000;
		if (rect.right > 1000)
			rect.right = 1000;

		if (rect.top < -1000)
			rect.top = -1000;
		if (rect.top > 1000)
			rect.top = 1000;

		if (rect.bottom < -1000)
			rect.bottom = -1000;
		if (rect.bottom > 1000)
			rect.bottom = 1000;
	}

	public boolean isFocusCompleted()
	{
		return mState == STATE_SUCCESS || mState == STATE_FAIL;
	}

	public void removeMessages()
	{
		mHandler.removeMessages(RESET_TOUCH_FOCUS);
	}

	public void overrideFocusMode(int focusMode)
	{
		mOverrideFocusMode = focusMode;
	}

	public void setAeAwbLock(boolean lock)
	{
		mAeAwbLock = lock;
	}

	public boolean getAeAwbLock()
	{
		return mAeAwbLock;
	}

	private static boolean isSupported(int value, int[] supported)
	{
		boolean isAvailable = false;
		if (supported != null)
			for (int currMode : supported)
			{
				if (currMode == value)
				{
					isAvailable = true;
					break;
				}
			}
		return isAvailable;
	}

	private boolean needAutoFocusCall()
	{
		int focusMode = getFocusMode();
		return !(focusMode == CameraParameters.AF_MODE_INFINITY
				|| focusMode == CameraParameters.AF_MODE_FIXED
				|| focusMode == CameraParameters.AF_MODE_EDOF // EDOF likely
																// needs
																// auto-focus
																// call
				|| focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
				|| focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO || focusMode == CameraParameters.MF_MODE
				|| mFocusDisabled || mFocusLocked || !CameraController.isFocusModeSupported());
	}

	private boolean isContinuousFocusMode()
	{
		int focusMode = getFocusMode();
		return (focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE || focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO);
	}

	private boolean focusOnShutterDisabled()
	{
		return PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).getBoolean(
				"ContinuousCapturing", false);
	}

	public boolean onBroadcast(int arg1, int arg2)
	{
		if (arg1 == PluginManager.MSG_CONTROL_LOCKED)
		{
			mFocusDisabled = true;
			// cancelAutoFocus();
		} else if (arg1 == PluginManager.MSG_CONTROL_UNLOCKED)
		{
			mFocusDisabled = false;
			// cancelAutoFocus();
		} else if (arg1 == PluginManager.MSG_FOCUS_LOCKED)
		{
			mFocusLocked = true;
		} else if (arg1 == PluginManager.MSG_FOCUS_UNLOCKED)
		{
			mFocusLocked = false;
		} else if (arg1 == PluginManager.MSG_CAPTURE_FINISHED)
		{
			mFocusDisabled = false;
			cancelAutoFocus();
		} else if (arg1 == PluginManager.MSG_FOCUS_CHANGED)
		{
			int fm = CameraController.getFocusMode();
			if (fm != -1)
				preferenceFocusMode = fm;
		} else if (arg1 == PluginManager.MSG_PREVIEW_CHANGED)
		{
			initialize(CameraController.isFrontCamera(), 90);
		}

		return false;
	}

	@Override
	public void onCaptureFinished()
	{
		mFocusDisabled = false;
		if (!mFocusLocked)
			cancelAutoFocus();
	}
}
