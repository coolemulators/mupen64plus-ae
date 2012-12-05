/**
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * Copyright (C) 2012 Paul Lamb
 * 
 * This file is part of Mupen64PlusAE.
 * 
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * 
 * See the GNU General Public License for more details. You should have received a copy of the GNU
 * General Public License along with Mupen64PlusAE. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Authors: paulscode, lioncash, littleguy77
 */
package paulscode.android.mupen64plusae;

import java.util.ArrayList;

import paulscode.android.mupen64plusae.input.AbstractController;
import paulscode.android.mupen64plusae.input.PeripheralController;
import paulscode.android.mupen64plusae.input.TouchController;
import paulscode.android.mupen64plusae.input.map.TouchMap;
import paulscode.android.mupen64plusae.input.map.VisibleTouchMap;
import paulscode.android.mupen64plusae.input.provider.AbstractProvider;
import paulscode.android.mupen64plusae.input.provider.AxisProvider;
import paulscode.android.mupen64plusae.input.provider.KeyProvider;
import paulscode.android.mupen64plusae.input.provider.KeyProvider.ImeFormula;
import paulscode.android.mupen64plusae.input.provider.NativeInputSource;
import paulscode.android.mupen64plusae.util.Demultiplexer;
import paulscode.android.mupen64plusae.util.FileUtil;
import paulscode.android.mupen64plusae.util.Notifier;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;

//@formatter:off
/**
 * (start)
 *    |
 * onCreate <-- (killed) <-----\
 *    |                        |
 * onStart <-- onRestart <--\  |
 *    |                     |  |
 * onResume <------------\  |  |
 *    |                  |  |  |
 * [*onSurfaceCreated*]  |  |  |
 *    |                  |  |  |
 * [*onSurfaceChanged*]  |  |  |
 *    |                  |  |  |
 * (running)             |  |  |   
 *    |                  |  |  |
 * onPause --------------/  |  |
 *    |                     |  |
 * [*onSurfaceDestroyed*]   |  |
 *    |                     |  |
 * onStop ------------------/--/
 *    |
 * onDestroy
 *    |
 * (end)
 * 
 * 
 * [*doesn't always occur*]
 * 
 * 
 */
//@formatter:on

public class GameLifecycleHandler implements View.OnKeyListener, GameSurface.CoreLifecycleListener
{
    // Activity and views
    private Activity mActivity;
    private GameSurface mSurface;
    private GameOverlay mOverlay;
    
    // Input resources
    private final ArrayList<AbstractController> mControllers;
    private VisibleTouchMap mTouchscreenMap;
    private KeyProvider mKeyProvider;
    
    // Internal flags
    private boolean mCoreRunning = false;
    private final boolean mIsXperiaPlay;
    
    public GameLifecycleHandler( Activity activity )
    {
        mActivity = activity;
        mControllers = new ArrayList<AbstractController>();
        mIsXperiaPlay = !( activity instanceof GameActivity );
    }

    @TargetApi( 11 )
    public void onCreateBegin( Bundle savedInstanceState )
    {
        // Load native libraries
        if( mIsXperiaPlay )
            FileUtil.loadNativeLibName( "xperia-touchpad" );
        FileUtil.loadNativeLibName( "SDL" );
        FileUtil.loadNativeLibName( "core" );
        FileUtil.loadNativeLibName( "front-end" );
        FileUtil.loadNativeLib( Globals.userPrefs.videoPlugin.path );
        FileUtil.loadNativeLib( Globals.userPrefs.audioPlugin.path );
        FileUtil.loadNativeLib( Globals.userPrefs.inputPlugin.path );
        FileUtil.loadNativeLib( Globals.userPrefs.rspPlugin.path );
        
        // For Honeycomb, let the action bar overlay the rendered view (rather than squeezing it)
        // For earlier APIs, remove the title bar to yield more space
        Window window = mActivity.getWindow();
        if( Globals.IS_HONEYCOMB )
            window.requestFeature( Window.FEATURE_ACTION_BAR_OVERLAY );
        else
            window.requestFeature( Window.FEATURE_NO_TITLE );
        
        // Enable full-screen mode
        window.setFlags( LayoutParams.FLAG_FULLSCREEN, LayoutParams.FLAG_FULLSCREEN );
        
        // Keep screen from going to sleep
        window.setFlags( LayoutParams.FLAG_KEEP_SCREEN_ON, LayoutParams.FLAG_KEEP_SCREEN_ON );
        
        // Set the screen orientation
        mActivity.setRequestedOrientation( Globals.userPrefs.screenOrientation );        
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
        
        // Hide the action bar introduced in higher Android versions
        if( Globals.IS_HONEYCOMB )
        {
            // SDK version at least HONEYCOMB, so there should be software buttons on this device:
            View view = mSurface.getRootView();
            if( view != null )
                view.setSystemUiVisibility( View.SYSTEM_UI_FLAG_LOW_PROFILE );
            mActivity.getActionBar().hide();
        }
        
        // Initialize the screen elements
        if( Globals.userPrefs.isTouchscreenEnabled || Globals.userPrefs.isFpsEnabled )
        {
            // The touch map and overlay are needed to display frame rate and/or controls
            mTouchscreenMap = new VisibleTouchMap( mActivity.getResources(),
                    Globals.userPrefs.isFpsEnabled, Globals.paths.fontsDir );
            mTouchscreenMap.load( Globals.userPrefs.touchscreenLayout );
            mOverlay.initialize( mTouchscreenMap );
        }
        
        // Initialize user interface devices
        View inputSource = mIsXperiaPlay ? new NativeInputSource( mActivity ) : mSurface;
        if( Globals.userPrefs.inputPlugin.enabled )
            initControllers( inputSource );
        Vibrator vibrator = (Vibrator) mActivity.getSystemService( Context.VIBRATOR_SERVICE );

        // Override the peripheral controllers' key provider, to add some extra functionality
        inputSource.setOnKeyListener( this );
        
        // Start listening to game surface events
        mSurface.setListeners( this, mOverlay, Globals.userPrefs.fpsRefresh );
        
        // Refresh the objects and data files interfacing to the emulator core
        CoreInterface.refresh( mActivity, mSurface, vibrator );
    }
    
    public void onResume()
    {
        if( mCoreRunning )
        {
            Notifier.showToast( mActivity, R.string.toast_loadingSession );
            NativeMethods.fileLoadEmulator( Globals.userPrefs.selectedGameAutoSavefile );
            NativeMethods.resumeEmulator();
        }
    }
    
    public void onPause()
    {
        if( mCoreRunning )
        {
            NativeMethods.pauseEmulator();
            Notifier.showToast( mActivity, R.string.toast_savingSession );
            NativeMethods.fileSaveEmulator( Globals.userPrefs.selectedGameAutoSavefile );
        }
    }
    
    @Override
    public void onCoreStartup()
    {
        mCoreRunning = true;
        Notifier.showToast( mActivity, R.string.toast_loadingSession );
        NativeMethods.fileLoadEmulator( Globals.userPrefs.selectedGameAutoSavefile );
        NativeMethods.resumeEmulator();
    }
    
    @Override
    public void onCoreShutdown()
    {
        mCoreRunning = false;
    }
    
    @Override
    public boolean onKey( View view, int keyCode, KeyEvent event )
    {
        boolean keyDown = event.getAction() == KeyEvent.ACTION_DOWN;
        
        if( keyCode == KeyEvent.KEYCODE_BACK )
        {
            // Absorb all back key presses, and toggle the ActionBar if applicable
            if( keyDown )
                toggleActionBar( view.getRootView() );
            return true;
        }
        
        // Let Android handle the menu key if available
        else if( keyCode == KeyEvent.KEYCODE_MENU )
        {
            return false;
        }
        
        // Let Android handle the volume keys if not used for control
        else if( !Globals.userPrefs.isVolKeysEnabled
                && ( keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ) )
            return false;
        
        // Let the PeripheralControllers handle everything else
        else if( mKeyProvider != null )
            return mKeyProvider.onKey( view, keyCode, event );
        
        // Let Android handle everything else if no PeripheralControllers
        else
            return false;
    }
    
    private void initControllers( View inputSource )
    {
        // Create the touchpad controls, if applicable
        TouchController touchpadController = null;
        if( mIsXperiaPlay )
        {
            // Create the map for the touchpad
            TouchMap touchpadMap = new TouchMap( mActivity.getResources() );
            touchpadMap.load( Globals.userPrefs.xperiaLayout );
            touchpadMap.resize( NativeInputSource.PAD_WIDTH, NativeInputSource.PAD_HEIGHT );
            
            // Create the touchpad controller
            touchpadController = new TouchController( touchpadMap, inputSource,
                    null, Globals.userPrefs.isOctagonalJoystick );            
            mControllers.add( touchpadController );

            // Filter by source identifier
            touchpadController.setSourceFilter( NativeInputSource.SOURCE_TOUCHPAD );
        }
        
        // Create the touchscreen controls
        if( Globals.userPrefs.isTouchscreenEnabled )
        {
            // Create the touchscreen controller
            TouchController touchscreenController = new TouchController( mTouchscreenMap,
                    inputSource, mOverlay, Globals.userPrefs.isOctagonalJoystick );
            mControllers.add( touchscreenController );
            
            // If using touchpad & touchscreen together...
            if( touchpadController != null )
            {
                // filter by source identifier...
                touchscreenController.setSourceFilter( NativeInputSource.SOURCE_TOUCHSCREEN );
                
                // and demux the input source to both touch listeners
                Demultiplexer.OnTouchListener demux = new Demultiplexer.OnTouchListener();
                demux.addListener( touchpadController );
                demux.addListener( touchscreenController );
                inputSource.setOnTouchListener( demux );
            }            
        }

        // Create the input providers shared among all peripheral controllers
        mKeyProvider = new KeyProvider( inputSource, ImeFormula.DEFAULT );
        AbstractProvider axisProvider = Globals.IS_HONEYCOMB_MR1 ? new AxisProvider( inputSource ) : null;
        
        // Create the peripheral controls to handle key/stick presses
        if( Globals.userPrefs.inputMap1.isEnabled() )
        {
            mControllers.add( new PeripheralController( 1, Globals.userPrefs.inputMap1,
                    mKeyProvider, axisProvider ) );
        }
        if( Globals.userPrefs.inputMap2.isEnabled() )
        {
            mControllers.add( new PeripheralController( 2, Globals.userPrefs.inputMap2,
                    mKeyProvider, axisProvider ) );
        }
        if( Globals.userPrefs.inputMap3.isEnabled() )
        {
            mControllers.add( new PeripheralController( 3, Globals.userPrefs.inputMap3,
                    mKeyProvider, axisProvider ) );
        }
        if( Globals.userPrefs.inputMap4.isEnabled() )
        {
            mControllers.add( new PeripheralController( 4, Globals.userPrefs.inputMap4,
                    mKeyProvider, axisProvider ) );
        }
    }
    
    @TargetApi( 11 )
    private void toggleActionBar( View rootView )
    {
        // Only applies to Honeycomb devices
        if( !Globals.IS_HONEYCOMB )
            return;
        
        // Toggle the action bar
        ActionBar actionBar = mActivity.getActionBar();
        if( actionBar.isShowing() )
        {
            actionBar.hide();
            
            // Make the home buttons almost invisible again
            if( rootView != null )
                rootView.setSystemUiVisibility( View.SYSTEM_UI_FLAG_LOW_PROFILE );
        }
        else
        {
            actionBar.show();
        }
    }
}
