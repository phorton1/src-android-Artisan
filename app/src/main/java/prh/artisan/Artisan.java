package prh.artisan;

// ARCHITCTURAL NOTES
//
// Loops and Events
//
//      We studiously try to not introduce Timer Loops into the program.
//      There are currently four timer loops: two minor, one well behaved,
//      and simple, and one important and hard to manage.
//
//      LocalVolumeFixer has a timer loop once per second to reconcile MTC and
//      Android volumes only on the Car Stereo. The SSDPServer has a long timer,
//      like once every 30 seconds, to broadcast a Keep-Alive message.
//      The SSDP Search, which itself is a Thread Runnable, and called as such,
//      essentially has a blocking timer loop for the duration of the call to run(),
//      which entirely notifies Artisan of EVENT_NEW_DEVICE as it proceeds.
//
//      These are all low level "faceless" objects that do not make any direct
//      UI calls. There are NO TIMER LOOPS IN THE UI.  All low level objects that
//      may cause UI changes do so by dispatching ArtisanEvents to Artisan, who in
//      turn dispatches them to the appropriate UI activities. The above four cases
//      are relatively simple, and either don't need to dispatch events, or dispatch
//      a small set of well understood Artisan Events.
//
//      The main, and complex case of a Timer loop is in Renderers.
//
//      Otherwise, THE ENTIRE UI IS EVENT DRIVEN.
//
// Renderer Timer Loop, EVENT_IDLE, and UPnP Event Subscribers
//
//      Each Derived Renderer has a polling timer loop (to poll the state of the local
//      media_player or remote DLNA MediaServer) and dispatches a variety of Artisan
//      events as it notices changes.
//
//      This timer loop does what it needs to do, but also dispatches EVENT_IDLE
//      on each go-round.  The occurrence of the EVENT_IDLE artisan event is crucial
//      in the implementation, because it triggers the dispatching of UPnP Events
//      to subscribed clients.  Without a Renderer, no subscribed SSDP devices will
//      receive any SSDP Events. Yet not all subscribers are to a Renderer .. they
//      can also subscribe to the ContentServer which is logically independent
//      of the Renderer.  Yet, there MUST BE A RENDERER PRESENT for them to
//      get notfied.  This is just a fact of the implementation that should be known.
//
//      Although, logically, the dispatching of UPnP Events has nothing to do with
//      the UI thread, it is implemented in the Artisan.handleArtisanEvent() method,
//      as there is a significant correspondence between Artisan Events and UPnP
//      Events.  We use the Artisan Event to bump a count of changes on the UPnP
//      services (UpnpEventHandlers owned by the HTTPServer and registered with
//      the UpnpEventManager), and then call UpnpEventManager.send_events() on
//      each IDLE_EVENT.

// UI Thread and Network Requests
//
//      The UI Objects, like aPlaying, aPreferences, aPlaylist, etc, are free to
//      access each other and make directly method calls, as they all "live" in the
//      main UI thread.
//
//      The devices, services, servers, etc - all low level faceless objects - call
//      Artisan.handleArtisanEvent() to notify the UI of changes. The main dispatcher,
//      Artisan.handleArtisanEvent() uses the built in Android runOnUiThread() method
//      to block and wait for the UI thread to become available.   It should be the
//      only call to runOnUiThread() in the system.
//
//      The Android OS does not allow us to make network requests on the main UI Thread.
//
//      This results in the need to fire off threads, and have handlers, for anything that
//      hits the network ... like getting html or xml pages ... which in turn means for doing
//      anything interesting, particularly from the faceless objects, like issuing UPnP actions,
//      doing SSDP Searches and getting device descriptions, etc.
//
//      Therefore we generically implement a set Network Routines that can be used either
//      Asynchronously, by passing in a handler for the results, or emulating Synchronous
//      Network requests, by waiting for the reply ourselves, possibly on the UI thread.
//      This results in a lot of additional complexity.
//
//      This complexity resulting from the use of Asynchronous network requests should be
//      avoided in UI code.  It is nice if the UI code not only can be called safely from
//      anywhere in the main UI thread, but it is also nice if it does not set up it's own
//      asyncrhonous handlers.  If you find the need to setup an asynchronous handler in the
//      UI code, it probably means there should be a low level object and Event created.
//
//      Factoid: the image in Now Playing is loaded via an asynchronous network request
//      that is also RunOnUiThread().  This I think of as a local implementation of a
//      synchronous Network Request.
//
// Truly Asynchronous Network Events
//
//      Otherwise, Aynchronous Network requests are hidden from the system, and limited
//      to private usage in a single low level faceless object. That object then responds
//      to the completion and does what it needs to do, and usually sends out Artisan Events
//      as a result.


import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import prh.device.Device;
import prh.device.DeviceManager;
import prh.device.LocalLibrary;
import prh.device.LocalPlaylistSource;
import prh.device.LocalRenderer;
import prh.server.HTTPServer;
import prh.server.LocalVolumeFixer;
import prh.server.SSDPServer;
import prh.server.utils.UpnpEventManager;
import prh.utils.Utils;



public class Artisan extends FragmentActivity implements
    View.OnClickListener,
    EventHandler
{
    private static int NUM_PAGER_ACTIVITIES = 5;
    public final static int PAGE_PREFS = 0;
    public final static int PAGE_PLAYING = 1;
    public final static int PAGE_PLAYLIST = 2;
    public final static int PAGE_LIBRARY = 3;
    public final static int PAGE_EXPLORER = 4;


    public final static int START_PAGE = Build.ID.equals(Utils.ID_CAR_STEREO) ?
        PAGE_PLAYING : PAGE_LIBRARY;

    // system working variables

    private int current_page = -1;
    private boolean is_full_screen = false;
    private boolean artisan_created = false;

    private MainMenu main_menu = null;
    private MainMenuToolbar main_toolbar = null;
    public MainMenuToolbar getToolbar() { return main_toolbar; }

    private ViewPager view_pager = null;
    private pageChangeListener page_change_listener = null;
    private WifiManager.WifiLock wifi_lock = null;
    public ViewPager getViewPager() { return view_pager; };

    // child (fragment) activities

    private aPrefs    aPrefs    = new aPrefs();
    private aPlaying  aPlaying  = new aPlaying();
    private aPlaylist aPlaylist = new aPlaylist();
    private aLibrary  aLibrary  = new aLibrary();
    private aExplorer aExplorer = new aExplorer();

    // The volume control dialog (fragment activity)
    // The volume control itself is owned by the renderer

    private VolumeControl volume_control = null;
    public VolumeControl getVolumeControl() { return volume_control; }

    // Lifetime Local Objects

    private Database database = null;
    private LocalLibrary local_library = null;
    private LocalRenderer local_renderer = null;
    private LocalPlaylistSource local_playlist_source = null;

    public Database getDatabase() { return database; }
    public LocalLibrary getLocalLibrary() { return local_library; }
    public LocalRenderer getLocalRenderer() { return local_renderer; }
    public LocalPlaylistSource getLocalPlaylistSource() { return local_playlist_source; }

    // The currently selected renderer and library

    private Library library = null;
    private Renderer renderer = null;
    private PlaylistSource playlist_source = null;
    public Library getLibrary() { return library; }
    public Renderer getRenderer() { return renderer; }
    public PlaylistSource getPlaylistSource() { return playlist_source; }

    // servers (no public access)

    private HTTPServer http_server = null;
    private LocalVolumeFixer volume_fixer = null;
    private SSDPServer ssdp_server = null;
    public HTTPServer getHTTPServer() { return http_server; }

    // and finally, the device manager and default renderer and library names

    private DeviceManager device_manager = null;
    public DeviceManager getDeviceManager() { return device_manager; }

    private String default_renderer_name = "";
    private String default_library_name = "";
    private String default_playlist_source_name = "";
        // if these are non-blank, we have to keep trying until the
        // SSDPSearch finishes to set the correct renderer ...


    private String getDefaultDeviceName(String what, Prefs.id pref_id, Prefs.id selected_id)
    {
        String default_name = Prefs.getString(pref_id);
        if (default_name.equals(Prefs.LAST_SELECTED))
            default_name = Prefs.getString(selected_id);
        if (default_name.startsWith("Local"))
            default_name = "";
        if (!default_name.isEmpty())
            Utils.log(0,0,"DEFAULT_" + what + "(" + default_name +")");
        return default_name;
    }


    //--------------------------------------------------------
    // onCreate()
    //--------------------------------------------------------

/***
    <ImageView
    android:id="@+id/main_menu_context_button"
    android:src="@drawable/my_ic_menu_context"
    android:layout_alignParentRight="true"
    android:layout_width="38dip"
    android:layout_height="wrap_content"
    android:onClick="onClick"/>

    <ImageView
    android:id="@+id/main_menu_home_button"
    android:src="@drawable/my_ic_menu_home"
    android:layout_toLeftOf="@id/main_menu_context_button"
    android:layout_width="38dip"
    android:layout_height="wrap_content"
    android:onClick="onClick"/>
****/

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        Utils.log(0,0,"------ Artisan.onCreate() started ------");

        // 0 = static initialization

        Utils.static_init(this);
        Prefs.static_init(this);

        default_renderer_name = getDefaultDeviceName(
            "RENDERER",Prefs.id.DEFAULT_RENDERER,Prefs.id.SELECTED_RENDERER);
        default_library_name = getDefaultDeviceName(
            "LIBRARY",Prefs.id.DEFAULT_LIBRARY,Prefs.id.SELECTED_LIBRARY);
        default_playlist_source_name = getDefaultDeviceName(
            "PLAYLIST_SOURCE",Prefs.id.DEFAULT_PLAYLIST_SOURCE,Prefs.id.SELECTED_PLAYLIST_SOURCE);

        // 1 = low level initialization

        database = new Database();
        if (!database.start())
            database = null;

        if (Utils.server_ip != null &&
            Prefs.getBoolean(Prefs.id.KEEP_WIFI_ALIVE))
        {
            WifiManager wifiMan = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            wifi_lock = wifiMan.createWifiLock(getClass().toString());
            wifi_lock.acquire();
        }

        // 2. create views
        // create the main view and sub-views
        // set the main menu click handler

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_artisan);

        // setup for Full Page behavior

        initTitleBarHeight();
        findViewById(R.id.artisan_title_bar).setOnClickListener(this);

        // create the main "activity" fragments
        // and the pager, listener, and adapter

        aPrefs    = new aPrefs();
        aPlaying  = new aPlaying();
        aPlaylist = new aPlaylist();
        aLibrary  = new aLibrary();
        aExplorer = new aExplorer();

        aPrefs.setArtisan(this);
        aPlaying.setArtisan(this);
        aPlaylist.setArtisan(this);
        aLibrary.setArtisan(this);
        aExplorer.setArtisan(this);

        page_change_listener = new pageChangeListener(this);
        myPagerAdapter my_pager_adapter = new myPagerAdapter(getSupportFragmentManager());
        view_pager = (ViewPager) findViewById(R.id.artisan_content);
        view_pager.setAdapter(my_pager_adapter);
        view_pager.addOnPageChangeListener(page_change_listener);

        // get a pointer to the main menu and toolbar
        // disappear the main menu

        main_menu = (MainMenu) findViewById(R.id.artisan_main_menu);
        hideMainMenu();

        // main_menu.setVisibility(View.GONE);
        main_toolbar = (MainMenuToolbar) findViewById(R.id.artisan_main_toolbar);

        // start the volume control and set full page

        volume_control = new VolumeControl(this);
        showFullScreen(true);
            // start off in full screen mode
            // with the menu showing


        // 3 = Create the local library and renderer
        // and set the default to them.  The renderer
        // should always construct correctly, but we
        // only construct the library if the database
        // is present.

        if (Prefs.getBoolean(Prefs.id.START_LOCAL_RENDERER))
            local_renderer = new LocalRenderer(this);

        if (database != null &&
            Prefs.getBoolean(Prefs.id.START_LOCAL_LIBRARY))
        {
            local_library = new LocalLibrary(this);
            local_playlist_source = new LocalPlaylistSource(this);
        }

        library = local_library;
        renderer = local_renderer;
        playlist_source = local_playlist_source;

        // set the start page
        // we have to call setPageSelected() because
        // view_pager.setCurrentItem() does not trigger
        // the onPageChange listener ...

        view_pager.setCurrentItem(START_PAGE);
        setPageSelected(START_PAGE);

        // start the default, local, initial devices
        // the playlist source must be started before
        // the renderer, cuz the renderer gets it
        // in startRenderer()

        if (library != null)
        {
            Utils.log(0,0,"Local Library created ");
            library.start();
            // handleArtisanEvent(EventHandler.EVENT_LIBRARY_CHANGED,library);
        }
        else
            Utils.warning(0,0,"Local Library not created");

        if (playlist_source != null)
        {
            Utils.log(0,0,"Local PlaylistSource created ");
            playlist_source.start();
            // handleArtisanEvent(EventHandler.EVENT_PLAYLIST_SOURCE_CHANGED,playlist_source);
        }
        else
            Utils.warning(0,0,"Local PlaylistSource not created");


        if (renderer != null)
        {
            Utils.log(0,0,"Local Renderer created ");
            renderer.startRenderer();
            // handleArtisanEvent(EventHandler.EVENT_RENDERER_CHANGED,renderer);
        }
        else
            Utils.warning(0,0,"Local Renderer not created");



        // 4 = start servers

        if (Prefs.getBoolean(Prefs.id.START_VOLUME_FIXER) &&
            Utils.ID_CAR_STEREO.equals(Build.ID))
        {
            volume_fixer = new LocalVolumeFixer(this);
            volume_fixer.start();
        }

        if (Utils.server_ip  != null)
        {
            if (Prefs.getBoolean(Prefs.id.START_HTTP_MEDIA_SERVER) ||
                Prefs.getBoolean(Prefs.id.START_HTTP_MEDIA_RENDERER) ||
                Prefs.getBoolean(Prefs.id.START_HTTP_OPEN_HOME_SERVER))
            {
                // http_server

                try
                {
                    http_server = new HTTPServer(this);
                }
                catch (Exception e)
                {
                    Utils.error("Error starting http_server:" + e.toString());
                    http_server = null;
                }

                // ssdp_server

                if (http_server != null)
                {
                    http_server.start();

                    Utils.log(0,0,"starting ssdp_server ...");
                    SSDPServer ssdp_server = new SSDPServer(this,http_server);
                    Thread ssdp_thread = new Thread(ssdp_server);
                    ssdp_thread.start();
                    Utils.log(0,0,"ssdp_server started");
                }
            }
        }


        // 5 =  construct the DeviceManager and add the
        // local items to it, then see if we can find the
        // default items (if they're not local)

        device_manager = new DeviceManager(this);
        device_manager.addDevice(local_library);
        device_manager.addDevice(local_renderer);
        device_manager.addDevice(local_playlist_source);

        // see if we can find the default devices and start them
        // clear the names if found, even if it fails
        // to start, so we don't try again ... it won't be
        // the local device (cuz that's ""), so stop the local
        // devices if they exist before setting the new ones

        if (findAndStartDefaultDevice(Device.deviceGroup.DEVICE_GROUP_LIBRARY,default_library_name))
            default_library_name = "";
        if (findAndStartDefaultDevice(Device.deviceGroup.DEVICE_GROUP_RENDERER,default_renderer_name))
            default_renderer_name = "";
        if (findAndStartDefaultDevice(Device.deviceGroup.DEVICE_GROUP_PLAYLIST_SOURCE,default_playlist_source_name))
            default_playlist_source_name = "";

        // Do the initial SSDP Search.  Artisan.handleArtisanEvent()
        // EVENT_NEW_DEVICE will be called for any devices not in
        // the initial cache.

        device_manager.doDeviceSearch(false);
        Utils.log(0,0,"------ Artisan.onCreate() finished ------");
        artisan_created = true;

    }   // onCreate()


    @Override
    public void onResume()
    {
        Utils.log(0,0,"Artisan.onResume()");
        super.onResume();
    }

    @Override
    public void onPause()
    {
        Utils.log(0,0,"Artisan.onPause()");
        super.onPause();
    }


    @Override
    public void onDestroy()
    {
        Utils.log(0,0,"Artisan.onDestroy() started ------");
        super.onDestroy();

        // 5 = stop the device manager
        // it is up to it to wait until any pending SSDP Searches are finished

        if (device_manager != null)
            device_manager.stop();

        // 4 = stop servers

        if (ssdp_server != null)
            ssdp_server.shutdown();
        ssdp_server = null;

        if (http_server != null)
            http_server.stop();
        http_server = null;

        if (volume_fixer != null)
            volume_fixer.stop();
        volume_fixer = null;


        // 3 = stop the current and local devices

        if (library != null)
            library.stop();
        library = null;

        if (local_library != null)
            local_library.stop();
        local_library = null;

        if (renderer != null)
            renderer.stopRenderer();
        renderer = null;

        if (local_renderer != null)
            local_renderer.stopRenderer();
        local_renderer = null;

        if (playlist_source != null)
            playlist_source.stop();
        playlist_source = null;

        if (local_playlist_source != null)
            local_playlist_source.stop();
        local_playlist_source = null;


        // 2 = unwind view pager and fragments

        if (volume_control != null)
            volume_control.dismiss();
        volume_control = null;

        if (view_pager != null)
            view_pager.removeOnPageChangeListener(page_change_listener);
        page_change_listener = null;
        view_pager = null;

        aPrefs     = null;
        aPlaying   = null;
        aPlaylist  = null;
        aLibrary   = null;
        aExplorer  = null;

        // 1 = undo low level initialization

        if (wifi_lock != null)
            wifi_lock.release();
        wifi_lock = null;

        if (database != null)
            database.stop();
        database = null;

        // 0 = undo static initializations

        Prefs.static_init(null);
        Utils.static_init(null);
        Utils.log(0,0,"Artisan.onDestroy() finished ------");
    }


    //-------------------------------------------------------
    // Paging
    //-------------------------------------------------------


    public void setArtisanPageTitle(View page_title)
        // shall only be called by pages while they
        // are the current page
    {
        LinearLayout title_area = (LinearLayout) findViewById(
            R.id.artisan_title_bar_text_area);
        title_area.removeAllViews();
        title_area.addView(page_title);
    }


    private class myPagerAdapter extends FragmentPagerAdapter
    {
        public myPagerAdapter(FragmentManager fm)       { super(fm);  }
        @Override public int getCount()                 { return NUM_PAGER_ACTIVITIES; }
        @Override public Fragment getItem(int position)
        {
            switch (position)
            {
                case PAGE_PREFS : return aPrefs;
                case PAGE_PLAYING : return aPlaying;
                case PAGE_PLAYLIST : return aPlaylist;
                case PAGE_LIBRARY : return aLibrary;
                case PAGE_EXPLORER : return aExplorer;
            }
            return null;
        }
    }


    public class pageChangeListener extends ViewPager.SimpleOnPageChangeListener
    {
        private Artisan artisan;
        public pageChangeListener(Artisan a) {artisan = a;}

        @Override
        public void onPageSelected(int index)
        {
            setPageSelected(index);
        }
    }


    private void setPageSelected(int index)
    {
        myPagerAdapter adapter = (myPagerAdapter) view_pager.getAdapter();
        main_toolbar.initButtons();

        // unselect the old page

        setBodyClickListener(false);
        if (current_page >= 0)
        {
            ArtisanPage page = (ArtisanPage) adapter.getItem(current_page);
            page.onSetPageCurrent(false);
        }

        // select the new page

        current_page = index;
        setBodyClickListener(true);
        ArtisanPage page = (ArtisanPage) adapter.getItem(current_page);
        page.onSetPageCurrent(true);
    }



    //-------------------------------------------------------
    // FullScreen Behavior
    //-------------------------------------------------------
    // Our title bar gets a regular onClick() handler.
    //
    // All page fragments get a generic onBodyClicked() handler.
    // The click handlers have to be set on the paged activities
    // each time the come into view, and removed when they go out
    // of view, presumably because the View Pager removes
    // onClickHandlers when changing pages.
    //
    // Additionally, most fragment and other onClick() handlers
    // should call back to onBodyClicked().
    //
    // To begin with, we are full screen, our title bar shows at 0,0;
    //
    // When our title bar is clicked we stop being full screen,
    // disappear the title bar, and let the Android status bar show.
    //
    // When they click on the body of a fragment, or a properly
    // implemented control, it calls back to onBodyClicked(), which
    // changes us back to full screen, and shows our title bar.
    //
    // The onBodyClicked() handler also hides the main menu,
    // and returns TRUE if it did either, so that the event
    // can be eaten by the particular onClick() handler


    private void initTitleBarHeight()
        // The whole scheme hinges on having our title bar
        // exactly replace the status bar.  This code sets
        // our title bar's height to that of the Android
        // system StatusBar
    {
        View title_bar = findViewById(R.id.artisan_title_bar);
        ViewGroup.LayoutParams params = title_bar.getLayoutParams();
        int id = getResources().getIdentifier("status_bar_height","dimen","android");
        if (id > 0)
            params.height = getResources().getDimensionPixelSize(id);
        else
            params.height = 60; // bogus fallback
        title_bar.setLayoutParams(params);
    }


    private void showFullScreen(boolean full)
        // if fullscreen, then our title bar disappears and
        // the system StatusBar is allowed to show, otherwise
        // we show our title bar over the system StatusBar
    {
        // set Window Mode

        getWindow().setFlags(full ?
            WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // display or hide the title bar
        // title bar shows when full

        View title_bar = findViewById(R.id.artisan_title_bar);
        title_bar.setVisibility(full ? View.VISIBLE : View.GONE );
        is_full_screen = full;
    }


    private void setBodyClickListener(boolean setit)
        // The current_page is coming into view (setit),
        // or going out of view (!setit). Remove or
        // set the onBodyClicked() listener.
    {
        if (current_page >= 0)
        {
            myPagerAdapter adapter = (myPagerAdapter) view_pager.getAdapter();
            Fragment fragment = adapter.getItem(current_page);
            View view = fragment.getView();
            if (view != null)
                fragment.getView().setOnClickListener( !setit ? null :
                    createBodyClickListener());
        }
    }


    private View.OnClickListener createBodyClickListener()
        // Return a new instance of a fullPageClickListener,
    {
        View.OnClickListener show_listener = new View.OnClickListener()
        {
            public void onClick(View view)
            {
                onBodyClicked();
            }
        };
        return show_listener;
    }


    public boolean onBodyClicked()
        // Hide the main menu if it's showing, and/or
        // switch back to full screen mode if we're not.
        // Return true in either case so clients can skip
        // the event.
        //
        // called liberally throughout the code by controls
        // within pages (that don't call have a onBodyClickListener()
    {
        boolean retval = hideMainMenu();
        if (!is_full_screen)
        {
            retval = true;
            showFullScreen(true);
        }
        return retval;
    }


    //-------------------------------------------------------
    // Utilities
    //-------------------------------------------------------


    public void onUtilsError(final String msg)
    {
        runOnUiThread( new Runnable()
        {
            @Override public void run()
            {
                Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_LONG).show();
            }
        });
    }



    public void doVolumeControl()
    {
        Utils.log(0,0,"doVolumeControl()");
        if (volume_control != null &&
            renderer != null &&
            renderer.getVolume() != null)
        {
            volume_control.show();
        }
    }


    public void showArtisanProgressIndicator(final boolean show_it)
    {
        runOnUiThread(new Runnable() { public void run() {
        ProgressBar progress = (ProgressBar) findViewById(R.id.artisan_progress);
        progress.setVisibility(show_it ? View.VISIBLE : View.INVISIBLE); }});
    }


    //------------------------------------------------
    // Playlist
    //------------------------------------------------


    public Playlist createEmptyPlaylist()
    {
        return playlist_source.getPlaylist("");
    }


    public void setPlaylist(String name)
    // called from station button, we select the new
    // playlist and event it to any interested clients
    // PRH - this should not be here
    {
        Utils.log(0,0,"setPlaylist(" + name + ")");

        // get the playlist

        Playlist playlist = playlist_source.getPlaylist(name);
        if (playlist == null)
            Utils.error("Could not get Playlist named '" + name + "'");

            // tell the renderer, and it will send the event

        else if (renderer != null)
            renderer.setPlaylist(playlist);

            // or send the event ourselves if no renderer

        else
            aPlaying.handleArtisanEvent(EventHandler.EVENT_PLAYLIST_CHANGED,playlist);
    }




    //-------------------------------------------------------
    // Device Management
    //-------------------------------------------------------

    public Device getCurrentDevice(Device.deviceGroup group)
    {
        Device device = null;
        if (group.equals(Device.deviceGroup.DEVICE_GROUP_LIBRARY))
            device = (Device) library;
        if (group.equals(Device.deviceGroup.DEVICE_GROUP_RENDERER))
            device = (Device) renderer;
        if (group.equals(Device.deviceGroup.DEVICE_GROUP_PLAYLIST_SOURCE))
            device = (Device) playlist_source;
        return device;
    }


    private boolean findAndStartDefaultDevice(Device.deviceGroup group, String name)
    {
        // if the name is empty, we're already done

        if (name.isEmpty())
            return false;

        Device device = device_manager.getDevice(group,name);

        if (device != null)
        {
            Utils.log(0,0,"STARTING DEFAULT " + group +"(" + name + ") artisan_created=" + artisan_created);
            setArtisanDevice(group,name);
                // ignore false == could_not_start function value
            return true;
        }

        if (!artisan_created)
            Utils.log(0,0,"Could not find DEFAULT " + group + "(" + name + ") in onCreate() ... continuing to look ...");
        return false;
    }



    public boolean setArtisanDevice(Device.deviceGroup group, String name)
        // Called with a "Library", "Renderer", or "PlaylistSource",
        // and a name, sets the current Renderer, Library, or PlaylistSource,
        // stopping the old one if any, and starting the new one.
    {
        // Setup
        // the name of local items is "" in prefs

        Prefs.id prefs_id = null;
        String thing = "";
        String cur_name = "";
        String event_name = "";
        String prefs_name = name;

        if (library != null &&
            group == Device.deviceGroup.DEVICE_GROUP_LIBRARY)
        {
            thing = "Library";
            cur_name = library.getName();
            prefs_id = Prefs.id.SELECTED_LIBRARY;
            event_name = EventHandler.EVENT_LIBRARY_CHANGED;
            if (prefs_name.equals(Device.deviceType.LocalLibrary))
                prefs_name = "";
        }
        else if (renderer != null &&
            group == Device.deviceGroup.DEVICE_GROUP_RENDERER)
        {
            thing = "Renderer";
            cur_name = renderer.getName();
            prefs_id = Prefs.id.SELECTED_RENDERER;
            event_name = EventHandler.EVENT_RENDERER_CHANGED;
            if (prefs_name.equals(Device.deviceType.LocalRenderer))
                prefs_name = "";
        }
        else if (playlist_source != null &&
            group == Device.deviceGroup.DEVICE_GROUP_PLAYLIST_SOURCE )
        {
            thing = "PlaylistSource";
            cur_name = playlist_source.getName();
            prefs_id = Prefs.id.SELECTED_PLAYLIST_SOURCE;
            event_name = EventHandler.EVENT_PLAYLIST_SOURCE_CHANGED;
            if (prefs_name.equals(Device.deviceType.LocalPlaylistSource))
                prefs_name = "";
        }

        // bail if its the same device

        if (cur_name.equals(name))
        {
            Utils.log(0,0,"setArtisanDevice(" + thing + "," + name + ") ignoring attempt to set to same " + thing);
            return true;
        }
        Utils.log(0,0,"--- setArtisanDevice(" + thing + "," + name + ")");

        // find the new Device
        // bail if it's not found

        Device device = device_manager.getDevice(group,name);
        if (device == null)
        {
            Utils.error("Attempt to set " + thing + " to non-existing Device(" + name + ")");
            return false;
        }

        // try to start the new Device, stop the old one,
        // and assign the new object if it works
        // bail if it fails.

        boolean started = false;

        if (thing.equals("Library") && ((Library) device).start())
        {
            if (library != null)
                library.stop();
            library = (Library) device;
            started = true;
        }
        if (thing.equals("Renderer") && ((Renderer) device).startRenderer())
        {
            if (renderer != null)
                renderer.stopRenderer();
            renderer = (Renderer) device;
            started = true;
        }
        if (thing.equals("PlaylistSource") && ((PlaylistSource) device).start())
        {
            if (renderer != null)
                renderer.stopRenderer();
            renderer = (Renderer) device;
            started = true;
        }

        if (!started)
        {
            Utils.error("setArtisanDevice(" + name + ") " + thing + ".start() failed");
            return false;
        }

        // We started it, and if we are out of Artisan.onCreate(),
        // we set the selected item into the preferences, and send out
        // an artisan EVENT_XXX_CHANGED event

        if (artisan_created)
        {
            Prefs.putString(prefs_id,prefs_name);
            Utils.log(0,0,"--- set" + thing + "(" + name + ") finishing and dispatching " + event_name + "(" + name + ")");
            handleArtisanEvent(event_name,device);
        }

        return true;
    }


    //----------------------------------------------------
    // onClick
    //----------------------------------------------------
    // Handles MainMenu and MainToolBars

    private boolean hideMainMenu()
    {
        boolean retval = false;
        if (main_menu.getAlpha() != 0F)
        {
            main_menu.animate()
                .translationX(-main_menu.getWidth())
                .alpha(0.0f);
            findViewById(R.id.artisan_content).setAlpha(1.0F);
            retval = true;
        }
        return retval;
    }

    private void showMainMenu()
    {
        // first time
        if (main_menu.getVisibility() == View.GONE)
            main_menu.setVisibility(View.VISIBLE);

        findViewById(R.id.artisan_content).setAlpha(0.4F);
        main_menu.init();
        main_menu.animate()
            .translationX(0)
            .alpha(1.0f);
    }


    public void onClick(View v)
    {
        int id = v.getId();
        switch (id)
        {
            case R.id.artisan_title_bar:
                if (!onBodyClicked())
                    showFullScreen(false);
                break;

            case R.id.artisan_title_bar_icon:
                if (!onBodyClicked())
                    showMainMenu();
                break;


            // all other commands come in from MenuButtons
            // who already call onBodyClicked()

            case R.id.command_home :
                Utils.log(0,0,"onClickHome()");
                Intent i = new Intent();
                i.setAction(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                v.getContext().startActivity(i);
                break;

            case R.id.command_back :
                onBackPressed();
                break;

            case R.id.command_context :
                break;
        }
    }



    //--------------------------------------------------
    // Support (and disabling) of Hardware Back Button
    //--------------------------------------------------

    @Override
    public void onBackPressed()
    {
        // do whatever is needed here
        if (current_page == 3)
            aLibrary.doBack();
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (// Integer.parseInt(android.os.Build.VERSION.SDK) > 5 &&
            keyCode == KeyEvent.KEYCODE_BACK &&
            event.getRepeatCount() == 0)
        {
            Utils.log(0,0,"onKeyDown(KEYCODE_BACK) Called");
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }



    //---------------------------------------------------------------------
    // EVENT HANDLING
    //---------------------------------------------------------------------


    public void restartDeviceSearch()
    {
        if (library != null && !((Device)library).isLocal())
        {
            library.stop();
            library = local_library;
            if (library != null)
                library.start();
            handleArtisanEvent(EVENT_LIBRARY_CHANGED,library);
        }
        if (renderer != null && !((Device)renderer).isLocal())
        {
            Playlist playlist = renderer.getPlaylist();
            if (playlist != null)
                playlist.stop();
            renderer.stopRenderer();
            renderer = local_renderer;
            if (renderer != null)
                renderer.startRenderer();
            handleArtisanEvent(EVENT_RENDERER_CHANGED,renderer);
        }
        if (playlist_source != null && !((Device)playlist_source).isLocal())
        {
            playlist_source.stop();
            playlist_source = local_playlist_source;
            if (playlist_source != null)
                playlist_source.start();
            handleArtisanEvent(EVENT_PLAYLIST_SOURCE_CHANGED,playlist_source);
        }

        default_renderer_name = getDefaultDeviceName(
            "RENDERER",Prefs.id.DEFAULT_RENDERER,Prefs.id.SELECTED_RENDERER);
        default_library_name = getDefaultDeviceName(
            "LIBRARY",Prefs.id.DEFAULT_LIBRARY,Prefs.id.SELECTED_LIBRARY);
        default_playlist_source_name = getDefaultDeviceName(
            "PLAYLIST_SOURCE",Prefs.id.DEFAULT_PLAYLIST_SOURCE,Prefs.id.SELECTED_PLAYLIST_SOURCE);
    }


    @Override
    public void handleArtisanEvent(final String event_id,final Object data)
    // run on UI async task to pass events to UI clients
    {
        if (!event_id.equals(EVENT_POSITION_CHANGED))
            Utils.log(1,0,"artisan.handleRendererEvent(" + event_id + ")");

        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                // selective debugging

                if (!event_id.equals(EVENT_POSITION_CHANGED) &&
                    !event_id.equals(EVENT_IDLE))
                    Utils.log(0,0,"----> " + event_id);

                //----------------------------------------------
                // Command Events
                //----------------------------------------------

                if (event_id.equals(COMMAND_EVENT_PLAY_TRACK))
                {
                    Track track = (Track) data;
                    if (renderer != null)
                        renderer.setTrack(track,false);
                    return;
                }

                //----------------------------------------------------------
                // Device Events
                //----------------------------------------------------------
                // check NEW_DEVICES if still looking for a Default Devices

                if (event_id.equals(EVENT_NEW_DEVICE))
                {
                    Device device = (Device) data;
                    if (device.getDeviceGroup() == Device.deviceGroup.DEVICE_GROUP_LIBRARY &&
                        default_library_name.equals(device.getFriendlyName()) &&
                        findAndStartDefaultDevice(device.getDeviceGroup(),default_library_name))
                    {
                        default_library_name = "";
                    }
                    if (device.getDeviceGroup() == Device.deviceGroup.DEVICE_GROUP_RENDERER &&
                        default_renderer_name.equals(device.getFriendlyName()) &&
                        findAndStartDefaultDevice(device.getDeviceGroup(),default_renderer_name))
                    {
                        default_renderer_name = "";
                    }
                    if (device.getDeviceGroup() == Device.deviceGroup.DEVICE_GROUP_PLAYLIST_SOURCE &&
                        default_playlist_source_name.equals(device.getFriendlyName()) &&
                        findAndStartDefaultDevice(device.getDeviceGroup(),default_playlist_source_name))
                    {
                        default_playlist_source_name = "";
                    }
                }

                // give an error on SSDP_SEARCH_FINISHED if we have not
                // found the default devices ...

                if (event_id.equals(EVENT_SSDP_SEARCH_FINISHED) )
                {
                    if (!default_library_name.isEmpty())
                        Utils.error("SSDPSearch could not find DEFAULT LIBRARY(" + default_library_name + ")");
                    if (!default_renderer_name.isEmpty())
                        Utils.error("SSDPSearch could not find DEFAULT RENDERER(" + default_renderer_name + ")");
                    if (!default_playlist_source_name.isEmpty())
                        Utils.error("SSDPSearch could not find DEFAULT PLAYLIST_SOURCE(" + default_playlist_source_name + ")");

                    default_library_name = "";
                    default_renderer_name = "";
                    default_playlist_source_name = "";
                }



                //----------------------------------------------------------
                // Global Events to main Views
                //----------------------------------------------------------
                // Send all events non-IDLE to all known event handlers
                // prh = Could use this to get rid of other Timer Loops,
                // i.e. to hit LocalVolumeFixer and polling Volume objects.
                // Note the selective dispatch to the volumeControl only
                // if it's showing

                if (//unused_main_menu.getVisibility() == View.VISIBLE && (
                    event_id.equals(EVENT_NEW_DEVICE) ||
                    event_id.equals(EVENT_LIBRARY_CHANGED) ||
                    event_id.equals(EVENT_RENDERER_CHANGED) ||
                    event_id.equals(EVENT_SSDP_SEARCH_STARTED) ||
                    event_id.equals(EVENT_SSDP_SEARCH_FINISHED) ||
                    event_id.equals(EVENT_PLAYLIST_SOURCE_CHANGED)) //)
                {
                    main_menu.handleArtisanEvent(event_id,data);
                }


                if (!event_id.equals(EVENT_IDLE) &&
                    !event_id.equals(EVENT_VOLUME_CHANGED))
                {
                    if (aPlaying != null &&
                        aPlaying.getView() != null)
                        aPlaying.handleArtisanEvent(event_id,data);

                    if (aPlaylist != null &&
                        aPlaylist.getView() != null)
                        aPlaylist.handleArtisanEvent(event_id,data);

                    if (aLibrary != null &&
                        aLibrary.getView() != null)
                        aLibrary.handleArtisanEvent(event_id,data);

                    if (aPrefs != null && aPrefs.getView() != null)
                        aPrefs.handleArtisanEvent(event_id,data);

                }

                // only send volume changes if it's open,
                // but send config messages event if it's closed

                if (event_id.equals(EVENT_VOLUME_CHANGED))
                {
                    if (volume_control != null &&
                        volume_control.isShowing())
                    {
                        volume_control.handleArtisanEvent(event_id,data);
                    }
                }


                //-----------------------------------------------------
                // UPnP Event dispatching
                //------------------------------------------------
                // if there's an http_server it means therer are UPnP
                // Services ... we bump the use counts on the underlying
                // objects, and dispatch the UPnP events on EVENT_IDLE ..

                if (http_server != null)
                {
                    UpnpEventManager event_manager = http_server.getEventManager();

                    if (event_id.equals(EVENT_STATE_CHANGED) ||
                        event_id.equals(EVENT_PLAYLIST_CHANGED) ||
                        event_id.equals(EVENT_PLAYLIST_TRACKS_EXPOSED))
                        event_manager.incUpdateCount("Playlist");

                    else if (event_id.equals(EVENT_TRACK_CHANGED))
                        event_manager.incUpdateCount("Info");

                    else if (event_id.equals(EVENT_POSITION_CHANGED))
                        event_manager.incUpdateCount("Time");

                    else if (event_id.equals(EVENT_VOLUME_CHANGED))
                        event_manager.incUpdateCount("Volume");

                    else if (event_id.equals(EVENT_IDLE))
                        event_manager.send_events();
                }


            }   // run()
        }); // runOnUiThread()
    }   // handleArtisanEvent()



}   // class Artisan
