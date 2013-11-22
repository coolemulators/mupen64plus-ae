/**
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * Copyright (C) 2013 Paul Lamb
 * 
 * This file is part of Mupen64PlusAE.
 * 
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 * 
 * Authors: paulscode, lioncash, littleguy77
 */
package paulscode.android.mupen64plusae;

import java.util.ArrayList;

import com.bda.controller.Controller;

import paulscode.android.mupen64plusae.input.AbstractController;
import paulscode.android.mupen64plusae.input.PeripheralController;
import paulscode.android.mupen64plusae.input.TouchController;
import paulscode.android.mupen64plusae.input.map.TouchMap;
import paulscode.android.mupen64plusae.input.map.VisibleTouchMap;
import paulscode.android.mupen64plusae.input.provider.AbstractProvider;
import paulscode.android.mupen64plusae.input.provider.AxisProvider;
import paulscode.android.mupen64plusae.input.provider.KeyProvider;
import paulscode.android.mupen64plusae.input.provider.KeyProvider.ImeFormula;
import paulscode.android.mupen64plusae.input.provider.MogaProvider;
import paulscode.android.mupen64plusae.jni.NativeConstants;
import paulscode.android.mupen64plusae.jni.NativeExports;
import paulscode.android.mupen64plusae.jni.NativeXperiaTouchpad;
import paulscode.android.mupen64plusae.persistent.AppData;
import paulscode.android.mupen64plusae.persistent.UserPrefs;
import paulscode.android.mupen64plusae.util.Demultiplexer;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

//@formatter:off
/**
* (start)
*    |
* onCreate <-- (killed) <---------\
*    |                            |
* onStart  <-- onRestart <-----\  |
*    |                         |  |
* onResume <----------------\  |  |
*    |                      |  |  |
* [*onSurfaceCreated*]      |  |  |
*    |                      |  |  |
* [*onSurfaceChanged*]      |  |  |
*    |                      |  |  |
* [*onWindowFocusChanged*]  |  |  |
*    |                      |  |  |
* (running)                 |  |  |
*    |                      |  |  |
* [*onWindowFocusChanged*]  |  |  |
*    |                      |  |  |
* onPause ------------------/  |  |
*    |                         |  |
* [*onSurfaceDestroyed*]       |  |
*    |                         |  |
* onStop ----------------------/--/
*    |
* onDestroy
*    |
* (end)
* 
* 
* [*non-deterministic sequence*]
* 
* 
*/
//@formatter:on

public class GameLifecycleHandler implements View.OnKeyListener, SurfaceHolder.Callback
{
    // Activity and views
    private Activity mActivity;
    private GameSurface mSurface;
    private GameOverlay mOverlay;
    
    // Input resources
    private final ArrayList<AbstractController> mControllers;
    private VisibleTouchMap mTouchscreenMap;
    private KeyProvider mKeyProvider;
    private Controller mMogaController;
    
    // Internal flags
    private final boolean mIsXperiaPlay;
    
    // Lifecycle state tracking
    private boolean mIsFocused = false;     // true if the window is focused
    private boolean mIsResumed = false;     // true if the activity is resumed
    private boolean mIsSurface = false;     // true if the surface is available
    
    // App data and user preferences
    private AppData mAppData;
    private UserPrefs mUserPrefs;
    
    public GameLifecycleHandler( Activity activity )
    {
        mActivity = activity;
        mControllers = new ArrayList<AbstractController>();
        mIsXperiaPlay = !( activity instanceof GameActivity );
        mMogaController = Controller.getInstance( mActivity );
    }
    
    @TargetApi( 11 )
    public void onCreateBegin( Bundle savedInstanceState )
    {
        Log.i( "GameLifecycleHandler", "onCreate" );
        
        // Initialize MOGA controller API
        mMogaController.init();
        
        // Get app data and user preferences
        mAppData = new AppData( mActivity );
        mUserPrefs = new UserPrefs( mActivity );
        mUserPrefs.enforceLocale( mActivity );
        
        // For Honeycomb, let the action bar overlay the rendered view (rather than squeezing it)
        // For earlier APIs, remove the title bar to yield more space
        Window window = mActivity.getWindow();
        if( mUserPrefs.isActionBarAvailable )
            window.requestFeature( Window.FEATURE_ACTION_BAR_OVERLAY );
        else
            window.requestFeature( Window.FEATURE_NO_TITLE );
        
        // Enable full-screen mode
        window.setFlags( LayoutParams.FLAG_FULLSCREEN, LayoutParams.FLAG_FULLSCREEN );
        
        // Keep screen from going to sleep
        window.setFlags( LayoutParams.FLAG_KEEP_SCREEN_ON, LayoutParams.FLAG_KEEP_SCREEN_ON );
        
        // Set the screen orientation
        mActivity.setRequestedOrientation( mUserPrefs.videoOrientation );
        
        // If the orientation changes, the screensize info changes, so we must refresh dependencies
        mUserPrefs = new UserPrefs( mActivity );
    }
    
    @TargetApi( 11 )
    public void onCreateEnd( Bundle savedInstanceState )
    {
        // Take control of the GameSurface if necessary
        if( mIsXperiaPlay )
            mActivity.getWindow().takeSurface( null );
        
        // Lay out content and get the views
        mActivity.setContentView( R.layout.game_activity );
        mSurface = (GameSurface) mActivity.findViewById( R.id.gameSurface );
        mOverlay = (GameOverlay) mActivity.findViewById( R.id.gameOverlay );
        
        // Initialize the objects and data files interfacing to the emulator core
        Bundle extras = mActivity.getIntent().getExtras();
        String cheatArgs = extras.getString( GameActivity.EXTRA_CHEAT_ARGS );
        boolean doRestart = extras.getBoolean( GameActivity.EXTRA_DO_RESTART, false );
        CoreInterface.initialize( mActivity, mSurface, cheatArgs, doRestart );
        
        // Listen to game surface events (created, changed, destroyed)
        mSurface.getHolder().addCallback( this );
        
        // Update the GameSurface size
        mSurface.getHolder().setFixedSize( mUserPrefs.videoRenderWidth, mUserPrefs.videoRenderHeight );
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSurface.getLayoutParams();
        params.width = mUserPrefs.videoSurfaceWidth;
        params.height = mUserPrefs.videoSurfaceHeight;
        params.gravity = mUserPrefs.videoPosition | Gravity.CENTER_HORIZONTAL;
        mSurface.setLayoutParams( params );
        
        // Configure the action bar introduced in higher Android versions
        if( mUserPrefs.isActionBarAvailable )
        {
            mActivity.getActionBar().hide();
            ColorDrawable color = new ColorDrawable( Color.parseColor( "#303030" ) );
            color.setAlpha( mUserPrefs.videoActionBarTransparency );
            mActivity.getActionBar().setBackgroundDrawable( color );
        }
        
        // Initialize the screen elements
        if( mUserPrefs.isTouchscreenEnabled || mUserPrefs.isFpsEnabled )
        {
            // The touch map and overlay are needed to display frame rate and/or controls
            mTouchscreenMap = new VisibleTouchMap( mActivity.getResources(),
                    mUserPrefs.isFpsEnabled, mAppData.fontsDir, mUserPrefs.touchscreenStyle, mUserPrefs.touchscreenTransparency );
            mTouchscreenMap.load( mUserPrefs.touchscreenLayout );
            mOverlay.initialize( mTouchscreenMap, !mUserPrefs.isTouchscreenHidden, mUserPrefs.touchscreenScale,
                    mUserPrefs.videoFpsRefresh, mUserPrefs.touchscreenRefresh );
        }
        
        // Initialize user interface devices
        View inputSource = mIsXperiaPlay ? new NativeXperiaTouchpad( mActivity ) : mOverlay;
        initControllers( inputSource );
        
        // Override the peripheral controllers' key provider, to add some extra functionality
        inputSource.setOnKeyListener( this );
    }
    
    public void onStart()
    {
        Log.i( "GameLifecycleHandler", "onStart" );
    }
    
    public void onResume()
    {
        Log.i( "GameLifecycleHandler", "onResume" );
        mIsResumed = true;
        tryRunning();
        
        mMogaController.onResume();
    }
    
    @Override
    public void surfaceCreated( SurfaceHolder holder )
    {
        Log.i( "GameLifecycleHandler", "surfaceCreated" );
    }
    
    @Override
    public void surfaceChanged( SurfaceHolder holder, int format, int width, int height )
    {
        Log.i( "GameLifecycleHandler", "surfaceChanged" );
        mIsSurface = true;
        tryRunning();
    }
    
    public void onWindowFocusChanged( boolean hasFocus )
    {
        // Only try to run; don't try to pause. User may just be touching the in-game menu.
        Log.i( "GameLifecycleHandler", "onWindowFocusChanged: " + hasFocus );
        mIsFocused = hasFocus;
        if( hasFocus )
            hideSystemBars();
        tryRunning();
    }
    
    public void onPause()
    {
        Log.i( "GameLifecycleHandler", "onPause" );
        mIsResumed = false;
        tryPausing();
        
        mMogaController.onPause();
    }
    
    @Override
    public void surfaceDestroyed( SurfaceHolder holder )
    {
        Log.i( "GameLifecycleHandler", "surfaceDestroyed" );
        mIsSurface = false;
        tryStopping();
    }
    
    public void onStop()
    {
        Log.i( "GameLifecycleHandler", "onStop" );
    }
    
    public void onDestroy()
    {
        Log.i( "GameLifecycleHandler", "onDestroy" );
        mMogaController.exit();
    }
    
    @Override
    public boolean onKey( View view, int keyCode, KeyEvent event )
    {
        boolean keyDown = event.getAction() == KeyEvent.ACTION_DOWN;
        
        // For devices with an action bar, absorb all back key presses
        // and toggle the action bar
        if( keyCode == KeyEvent.KEYCODE_BACK && mUserPrefs.isActionBarAvailable )
        {
            if( keyDown )
                toggleActionBar();
            return true;
        }
        
        // Let the PeripheralControllers and Android handle everything else
        else
        {
            // If PeripheralControllers exist and handle the event,
            // they return true. Else they return false, signaling
            // Android to handle the event (menu button, vol keys).
            if( mKeyProvider != null )
                return mKeyProvider.onKey( view, keyCode, event );
            
            return false;
        }
    }
    
    @SuppressLint( "InlinedApi" )
    private void initControllers( View inputSource )
    {
        Vibrator vibrator = null;
        // Vibrator object MUST be null if permission not granted, to meet our code contracts
        if( mAppData.hasVibratePermission )
        {
            // By default, send Player 1 rumbles through phone vibrator
            vibrator = (Vibrator) mActivity.getSystemService( Context.VIBRATOR_SERVICE );
            CoreInterface.registerVibrator( 1, vibrator );
        }
        
        // Create the touchpad controls, if applicable
        TouchController touchpadController = null;
        if( mIsXperiaPlay )
        {
            // Create the map for the touchpad
            TouchMap touchpadMap = new TouchMap( mActivity.getResources() );
            touchpadMap.load( mUserPrefs.touchpadLayout );
            touchpadMap.resize( NativeXperiaTouchpad.PAD_WIDTH, NativeXperiaTouchpad.PAD_HEIGHT );
            
            // Create the touchpad controller
            touchpadController = new TouchController( touchpadMap, inputSource, null, vibrator,
                    TouchController.AUTOHOLD_METHOD_DISABLED, mUserPrefs.isTouchpadFeedbackEnabled,
                    null );
            mControllers.add( touchpadController );
            
            // Filter by source identifier
            touchpadController.setSourceFilter( InputDevice.SOURCE_TOUCHPAD );
        }
        
        // Create the touchscreen controls
        if( mUserPrefs.isTouchscreenEnabled )
        {
            // Create the touchscreen controller
            TouchController touchscreenController = new TouchController( mTouchscreenMap,
                    inputSource, mOverlay, vibrator, mUserPrefs.touchscreenAutoHold,
                    mUserPrefs.isTouchscreenFeedbackEnabled, mUserPrefs.touchscreenAutoHoldables );
            mControllers.add( touchscreenController );
            
            // If using touchpad & touchscreen together...
            if( touchpadController != null )
            {
                // filter by source identifier...
                touchscreenController.setSourceFilter( InputDevice.SOURCE_TOUCHSCREEN );
                
                // and demux the input source to both touch listeners
                Demultiplexer.OnTouchListener demux = new Demultiplexer.OnTouchListener();
                demux.addListener( touchpadController );
                demux.addListener( touchscreenController );
                inputSource.setOnTouchListener( demux );
            }
        }
        
        // Create the input providers shared among all peripheral controllers
        mKeyProvider = new KeyProvider( inputSource, ImeFormula.DEFAULT,
                mUserPrefs.unmappableKeyCodes );
        MogaProvider mogaProvider = new MogaProvider( mMogaController );
        AbstractProvider axisProvider = AppData.IS_HONEYCOMB_MR1
                ? new AxisProvider( inputSource )
                : null;
        
        // Create the peripheral controls to handle key/stick presses
        if( mUserPrefs.isInputEnabled1 )
        {
            mControllers.add( new PeripheralController( 1, mUserPrefs.playerMap,
                    mUserPrefs.inputMap1, mUserPrefs.inputDeadzone1, mUserPrefs.inputSensitivity1,
                    mKeyProvider, axisProvider, mogaProvider ) );
        }
        if( mUserPrefs.isInputEnabled2 )
        {
            mControllers.add( new PeripheralController( 2, mUserPrefs.playerMap,
                    mUserPrefs.inputMap2, mUserPrefs.inputDeadzone2, mUserPrefs.inputSensitivity2,
                    mKeyProvider, axisProvider, mogaProvider ) );
        }
        if( mUserPrefs.isInputEnabled3 )
        {
            mControllers.add( new PeripheralController( 3, mUserPrefs.playerMap,
                    mUserPrefs.inputMap3, mUserPrefs.inputDeadzone3, mUserPrefs.inputSensitivity3,
                    mKeyProvider, axisProvider, mogaProvider ) );
        }
        if( mUserPrefs.isInputEnabled4 )
        {
            mControllers.add( new PeripheralController( 4, mUserPrefs.playerMap,
                    mUserPrefs.inputMap4, mUserPrefs.inputDeadzone4, mUserPrefs.inputSensitivity4,
                    mKeyProvider, axisProvider, mogaProvider ) );
        }
    }
    
    @SuppressLint( "InlinedApi" )
    @TargetApi( 11 )
    private void hideSystemBars()
    {
        // Only applies to Honeycomb devices
        if( !AppData.IS_HONEYCOMB )
            return;
        
        View view = mSurface.getRootView();
        if( view != null )
        {
            if( AppData.IS_KITKAT && mUserPrefs.isImmersiveModeEnabled )
                view.setSystemUiVisibility( View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN );
            else
                view.setSystemUiVisibility( View.SYSTEM_UI_FLAG_LOW_PROFILE ); // == STATUS_BAR_HIDDEN for Honeycomb
        }
    }
    
    @TargetApi( 11 )
    private void toggleActionBar()
    {
        // Only applies to Honeycomb devices
        if( !AppData.IS_HONEYCOMB )
            return;
        
        // Toggle the action bar
        ActionBar actionBar = mActivity.getActionBar();
        if( actionBar.isShowing() )
        {
            actionBar.hide();
            hideSystemBars();
        }
        else
        {
            actionBar.show();
        }
    }
    
    private boolean isSafeToRender()
    {
        return mIsFocused && mIsResumed && mIsSurface;
    }
    
    private void tryRunning()
    {
        int state = NativeExports.emuGetState();
        if( isSafeToRender() && ( state != NativeConstants.EMULATOR_STATE_RUNNING ) )
        {
            switch( state )
            {
                case NativeConstants.EMULATOR_STATE_UNKNOWN:
                    CoreInterface.startupEmulator();
                    break;
                case NativeConstants.EMULATOR_STATE_PAUSED:
                    CoreInterface.resumeEmulator();
                    break;
                default:
                    break;
            }
        }
    }
    
    private void tryPausing()
    {
        if( NativeExports.emuGetState() != NativeConstants.EMULATOR_STATE_PAUSED )
        {
            CoreInterface.pauseEmulator( true );
        }
    }
    
    private void tryStopping()
    {
        if( NativeExports.emuGetState() != NativeConstants.EMULATOR_STATE_STOPPED )
        {
            // Never go directly from running to stopped; always pause (and autosave) first
            tryPausing();
            CoreInterface.shutdownEmulator();
        }
    }
}
