//----------------------------------------------
// OpenHome Playlist Actions
//----------------------------------------------

package prh.server.http;

import android.os.Handler;

import org.w3c.dom.Document;

import java.util.HashMap;

import fi.iki.elonen.NanoHTTPD;
import prh.artisan.Artisan;

import prh.artisan.CurrentPlaylist;
import prh.artisan.EventHandler;
import prh.device.LocalPlaylist;
import prh.artisan.Playlist;
import prh.artisan.PlaylistSource;
import prh.artisan.Track;
import prh.device.LocalPlaylistSource;
import prh.device.LocalRenderer;
import prh.server.HTTPServer;
import prh.server.utils.PlaylistExposer;
import prh.server.utils.UpnpEventSubscriber;
import prh.server.utils.UpdateCounter;
import prh.server.utils.UpnpEventHandler;
import prh.server.utils.httpRequestHandler;
import prh.utils.httpUtils;
import prh.utils.Utils;


public class OpenPlaylist extends httpRequestHandler implements UpnpEventHandler
    //    has different response method
{
    private static int dbg_playlist = 0;

    private final static int MAX_TRACKS = 10000;
        // OpenPlaylists have a max
    private static final String DEBUG_ACTION = ""; //"ReadList";
        // set this string to an action name and the
        // response for the action will be display
        // at debug level 0

    // member variables

    private Artisan artisan;
    private HTTPServer http_server;
    private CurrentPlaylist current_playlist;
    private String urn;

    // BubbleUp incremental playlist exposure support.
    // Each UpNP OpenHome subscriber is given a unique
    // bit_mask from 1..2^32, as they subscribe, and the
    // UpnpEventManager intelligently re-uses the availabe
    // 32 bits (i.e. it tries 1, then 2, then 4, then 8,
    // until it finds a number for which there is no bitmask.


    private class exposerHash extends HashMap<String,PlaylistExposer> {}
    exposerHash exposers = null;


    //---------------------------
    // ctor, start
    //---------------------------

    public OpenPlaylist(Artisan ma, HTTPServer http, String the_urn)
    {
        artisan = ma;
        http_server = http;
        urn = the_urn;
    }


    @Override public void start()
    {
        exposers = new exposerHash();
        http_server.getEventManager().RegisterHandler(this);
    }

    @Override public void stop()
    {
        http_server.getEventManager().UnRegisterHandler(this);
        if (exposers != null)
            exposers.clear();
        exposers = null;
    }

    @Override public void notifySubscribed(UpnpEventSubscriber subscriber,boolean subscribe)
    {
        String ip = subscriber.getIp();
        String user_agent = subscriber.getUserAgent();
        String ipua = ip + ":" + user_agent;
        PlaylistExposer exposer = exposers.get(ipua);
        LocalRenderer local_renderer = artisan.getLocalRenderer();

        if (subscribe)
        {
            if (exposer != null)
            {
                Utils.log(dbg_playlist,0,"notifySubscribed() Already has an Exposer for " + ipua);
            }
            else
            {
                Utils.log(0,0,"notifySubscribed() Creating Exposer(" + ipua + ")");
                exposer = new PlaylistExposer(this,artisan,ipua);
                exposers.put(ipua,exposer);
            }

            // This will be evented to the client with the
            // initial event for subscribe ...

            Track track = current_playlist.getCurrentTrack();
            if (track != null)
                exposer.exposeTrack(track,true);
        }

        // unsubscribe

        else if (exposer == null)
        {
            Utils.warning(0,0,"notifySubscribed(false) could not find exposer for " + ip + ":" + user_agent);
        }
        else
        {
            exposer.clearExposedBits(current_playlist);
            exposers.remove(ipua);
        }
    }   // OpenPlaylist.notifySubscribed()


    public void exposeTrack(Track track,boolean set_it)
        // called from the playlist itself on start()
        // expose the inital track to all exposers
        // and send out the event
        // The call is presumably accompanied by a artisan
        // PLAYLIST_CHANGED event.
    {
        if (!exposers.isEmpty())
            for (PlaylistExposer exposer : exposers.values())
                exposer.exposeTrack(track,set_it);
    }


    public void exposeCurrentTrack(LocalPlaylist local_playlist)
    // called from the playlist itself on start()
    // expose the inital track to all exposers
    // and send out the event
    // The call is presumably accompanied by a artisan
    // PLAYLIST_CHANGED event.
    {
        if (!exposers.isEmpty())
        {
            Track track = local_playlist.getCurrentTrack();
            exposeTrack(track,true);
        }
    }


    public void clearAllExposers(LocalPlaylist local_playlist)
        // called from the playlist itself on stop()
        // clear the exposers (and the tracks in the playlist)
        // NOTE that this means you CANT START THE NEW ONE THEN
        // STOP THE OLD ONE .. you have to stop the old one first.
        // The call is presumably accompanied by a artisan
        // PLAYLIST_CHANGED event.
    {
        for (PlaylistExposer exposer : exposers.values())
        {
            exposer.clearExposedBits(local_playlist);
        }
    }


    private LocalPlaylist createLocalPlaylist()
    {
        LocalPlaylistSource lps = artisan.getLocalPlaylistSource();
        return (LocalPlaylist) lps.getPlaylist("");
    }


    @Override public NanoHTTPD.Response response(
        NanoHTTPD.IHTTPSession session,
        NanoHTTPD.Response response,
        String unused_uri,
        String service,
        String action,
        Document doc,
        UpnpEventSubscriber subscriber)
            // OpenPlaylist is the only service that currently gets a subscriber
    {
        boolean ok = true;
        HashMap<String,String> hash = new HashMap<>();
        LocalRenderer local_renderer = artisan.getLocalRenderer();

        String ipua =
            session.getHeaders().get("remote-addr") + ":" +
            session.getHeaders().get("user-agent");
        PlaylistExposer exposer = exposers.get(ipua);

        // get basic info

        if (action.equals("ProtocolInfo"))
        {
            hash.put("Value",getProtocolInfo());
        }
        else if (action.equals("TracksMax"))
        {
            hash.put("Value",Integer.toString(MAX_TRACKS));
        }
        else if (action.equals("TransportState"))
        {
            hash.put("Value",DLNAStateToOpenHomeState(local_renderer.getRendererState()));
        }
        else if (action.equals("Repeat"))
        {
            hash.put("Value",local_renderer.getRepeat() ? "1" : "0");
        }
        else if (action.equals("Shuffle"))
        {
            hash.put("Value",local_renderer.getShuffle() ? "1" : "0");
        }


        // get list of tracks/ids

        else if (action.equals("Id"))
        {
            Track track = local_renderer.getTrack();
            hash.put("Value",track == null ? "0" : Integer.toString(track.getOpenId()));
        }
        else if (action.equals("IdArray"))
        {
            hash.put("Token",Integer.toString(getUpdateCount()));
            hash.put("Array",current_playlist.getIdArrayString(exposer));
        }
        else if (action.equals("IdArrayChanged"))
        {
            int token = httpUtils.getXMLInt(doc,"Token",true);
            Utils.log(0,0,"token=" + token);
            hash.put("Value",token == getUpdateCount() ? "0" : "1");

        }
        else if (action.equals("Read"))
        {
            int open_id = httpUtils.getXMLInt(doc,"Id",true);
            Utils.log(0,0,"open_id=" + open_id);
            Track track = current_playlist.getByOpenId(open_id);
            if (track == null)
                return error_response(http_server,800,open_id);
            hash.put("Uri",track.getPublicUri());
            hash.put("Metadata",track.getDidl());
        }
        else if (action.equals("ReadList"))
        {
            int id_array[] = current_playlist.string_to_id_array(
                httpUtils.getXMLString(doc,"IdList",true));
            hash.put("TrackList",current_playlist.id_array_to_tracklist(
                id_array));
            if (exposer != null)
                exposer.ThreadedExposeMore(current_playlist);
        }


        // playlist modifications
        // We subvert a DeleteAll action from an OpenHome control point,
        // that doesn't know about our PlaylistSources, into creating
        // a new empty LocalPlaylist, so that selecting a song on the
        // control point doesn't wipe out the list. Otherwise the atomic
        // single-track Insert and Delete actions work on the currently
        // selected LocalPlaylist ...

        else if (action.equals("DeleteAll"))
        {
            artisan.setPlaylist("",true);
                // artisan will send out the PLAYLIST_CHANGED event
                // which will trigger the incUpdateCount() ..
        }
        else if (action.equals("DeleteId"))
        {
            // 800 if not in list
            int open_id = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"open_id=" + open_id);
            if (!current_playlist.removeTrack(open_id,true))
                return error_response(http_server,800,open_id);
            incUpdateCount();
            artisan.setDeferOpenHomeEvents();
                // defer the open home events
                // in case it is a group of deletes
        }
        else if (action.equals("Insert"))
        {
            // Reports a 800 fault code if AfterId is not 0 and doesn’t appear in the playlist.
            // Reports a 801 fault code if the playlist is full (i.e. already contains TracksMax tracks).
            int after_id = httpUtils.getXMLInt(doc,"AfterId",true);
            String content_uri = httpUtils.getXMLString(doc,"Uri",true);
            String content_data = httpUtils.getXMLString(doc,"Metadata",true);
            Utils.log(0,0,"after_id=" + after_id);
            Utils.log(0,0,"uri=" + content_uri);
            Utils.log(0,0,"data=" + content_data);

            // an attempt to "insert" a playlist will result from it being selected
            // from the "select_playlist" virtual folder, so we jam the playlist
            // into the renderer here, and let the return value be what it is

            String pattern = Utils.server_uri + "/dlna_server/select_playlist/";
            if (content_uri.startsWith(pattern))
            {
                String name = content_uri.replace(pattern,"");
                name = name.replace(".mp3","");
                Utils.log(0,0,"SELECTING PLAYLIST via Playlist Insert action");
                artisan.setPlaylist(name,true);
                hash.put("NewId","0");
            }
            else    // real playlist insert
            {
                int save_index = current_playlist.getCurrentIndex();
                Track track = current_playlist.insertTrack(after_id,content_uri,content_data);
                if (track == null)
                    return error_response(http_server,800,after_id);

                exposeTrack(track,true);
                if (save_index != current_playlist.getCurrentIndex() ||
                    save_index == track.getPosition())
                {
                    artisan.handleArtisanEvent(EventHandler.EVENT_TRACK_CHANGED,track);
                }
                hash.put("NewId",Integer.toString(track.getOpenId()));
                incUpdateCount();
                artisan.setDeferOpenHomeEvents();
            }
        }


        // state setters
        // not implemented yet

        else if (action.equals("SetRepeat"))
        {
            int value = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"value=" + value);
            local_renderer.setRepeat(value > 0);
            incUpdateCount();
        }
        else if (action.equals("SetShuffle"))
        {
            int value = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"value=" + value);
            local_renderer.setShuffle(value > 0);
            incUpdateCount();
        }


        // song seeks

        else if (action.equals("SeekId"))
        {
            int open_id = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"open_id=" + open_id);
            if (open_id > 0)
            {
                Track track = current_playlist.seekByOpenId(open_id);
                Utils.log(0,0,"after list.seekByOpenId() track_index=" + current_playlist.getCurrentIndex());
                artisan.handleArtisanEvent(EventHandler.EVENT_TRACK_CHANGED,track);
                if (track == null)
                    return error_response(http_server,800,open_id);
                local_renderer.play();
            }
        }
        else if (action.equals("SeekIndex"))
        {
            int index = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"index=" + index);
            if (index > 0)
            {
                Track track = current_playlist.seekByIndex(index);
                Utils.log(0,0,"after list.seekByIndex() track_index=" + current_playlist.getCurrentIndex());
                artisan.handleArtisanEvent(EventHandler.EVENT_TRACK_CHANGED,track);
                if (track == null)
                    return error_response(http_server,800,index);
                local_renderer.play();
            }
        }

        // time seeks

        else if (action.equals("SeekSecondAbsolute"))
        {
            int seconds = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"seconds=" + seconds);
            local_renderer.seekTo(seconds * 1000);
        }
        else if (action.equals("SeekSecondRelative"))
        {
            int seconds = httpUtils.getXMLInt(doc,"Value",true);
            Utils.log(0,0,"seconds=" + seconds);
            int position = local_renderer.getPosition();
            position += seconds * 1000;
            local_renderer.seekTo(position);
        }

        // transport controls

        else if (action.equals("Next"))
        {
            local_renderer.incAndPlay(1);
            incUpdateCount();
        }
        else if (action.equals("Previous"))
        {
            local_renderer.incAndPlay(-1);
            incUpdateCount();
        }
        else if (action.equals("Pause"))
        {
            local_renderer.pause();
            incUpdateCount();
        }
        else if (action.equals("Play"))
        {
            local_renderer.play();
            incUpdateCount();
        }
        else if (action.equals("Stop"))
        {
            local_renderer.stop();
            incUpdateCount();
        }


        // unknown action

        else
        {
            ok = false;
        }

        // finished

        if (ok)
        {
            if (action.equals(DEBUG_ACTION))
                httpUtils.dbg_hash_response = true;
            response = httpUtils.hash_response(http_server,urn,service,action,hash);
            httpUtils.dbg_hash_response = false;
        }

        return response;
    }



    //---------------------------------------
    // utilities
    //---------------------------------------

    private static String getProtocolInfo()
    {
        return "http-get:*:image/gif:*," +
            "http-get:*:image/jpeg:*," +
            "http-get:*:image/png:*," +
            "http-get:*:image/jpg:*," +
            "http-get:*:audio/mpeg:*," +
            "http-get:*:audio/m4a:*," +
            "http-get:*:audio/mp4:*," +
            "http-get:*:application/x-ms-wma:*," +
            "http-get:*:audio/wma:*," +
            "http-get:*:application/wma:*";
    }



    private NanoHTTPD.Response error_response(HTTPServer server, int error, int data)
    {
        String msg = "ERROR " + error;
        NanoHTTPD.Response.IStatus status = NanoHTTPD.Response.Status.BAD_REQUEST;

        if (error == 800)
        {
            msg += " - item(" + data + ") not found";
            status = NanoHTTPD.Response.Status.BAD_PARAMETER;
        }
        if (error == 801)
        {
            msg += " - Playlist Full";
            status = NanoHTTPD.Response.Status.RESOURCES_EXHAUSTED;
        }
        Utils.error(msg);
        NanoHTTPD.Response response = server.newFixedLengthResponse(
            status,NanoHTTPD.MIME_PLAINTEXT,msg);
        return response;
    }




    //----------------------------------------
    // Event Dispatching
    //----------------------------------------

    UpdateCounter update_counter = new UpdateCounter();
    @Override public int getUpdateCount()  { return update_counter.get_update_count(); }
    @Override public int incUpdateCount()  { return update_counter.inc_update_count(); }
    @Override public String getName() { return "Playlist"; }


    String DLNAStateToOpenHomeState(String dlna_state)
    {
        if (dlna_state.equals("STOPPED"))          return "Stopped";
        if (dlna_state.equals("PLAYING"))          return "Playing";
        if (dlna_state.equals("PAUSED_PLAYBACK"))  return "Paused";
        if (dlna_state.equals("TRANSITIONING"))    return "Buffering";
        return "";
    }


    @Override public String getEventContent(UpnpEventSubscriber subscriber)
    {
        HashMap<String,String> hash = new HashMap<>();
        LocalRenderer local_renderer = artisan.getLocalRenderer();
        Track track = local_renderer.getTrack();
            // Our track may possibly NOT be in the playlist ...

        // EXPOSE_SCHEME support

        String ipua = subscriber.getIp() + ":" + subscriber.getUserAgent();
        PlaylistExposer exposer = exposers.get(ipua);

        // build the event response

        hash.put("TransportState",DLNAStateToOpenHomeState(local_renderer.getRendererState()));
        hash.put("ProtocolInfo",getProtocolInfo());
        hash.put("TracksMax",Integer.toString(MAX_TRACKS));
        hash.put("Shuffle",local_renderer.getShuffle() ? "1" : "0");
        hash.put("Repeat",local_renderer.getRepeat() ? "1" : "0");
        hash.put("IdArray",current_playlist.getIdArrayString(exposer));
        hash.put("Id", track == null ? "0" : Integer.toString(track.getOpenId()));

        // state variables from XML that
        // are not in OH "state" variables list
        // are also not evented
        //
        // Absolute
        // Relative
        //
        // Index
        // Uri
        // Metadata
        //
        // IdList
        // IdArrayToken
        // IdArrayChanged
        // TrackList

        return httpUtils.hashToXMLString(hash,true);
    }



}   // class OpenPlaylist
