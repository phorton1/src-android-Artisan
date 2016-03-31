package prh.artisan;

import prh.server.HTTPServer;
import prh.server.http.OpenPlaylist;
import prh.types.intTrackHash;
import prh.types.recordList;
import prh.types.trackList;
import prh.utils.Base64;
import prh.utils.Utils;
import prh.utils.httpUtils;

/**
 * @class CurrentPlaylist
 *
 * Is the list of tracks available to the system.
 * Is a Playlist that can be copied to another Playlist
 * It is the only Playlist to implement insert, delete,
 *     and all the open home ID Array stuff.
 */

public class CurrentPlaylist implements
    Playlist,
    Fetcher.FetcherSource
{
    private static int dbg_cp = 1;     // basics
    private static int dbg_fetch = 0;  // fetcher
    private static int dbg_ida = 0;    // id arrays

    // state

    private Artisan artisan;
    private Playlist associated_playlist;
    private prh.device.service.OpenPlaylist open_playlist_device;     // prh.device.s

    private boolean is_dirty;
    private int playlist_count_id;
    private int content_change_id;
    private  int next_open_id;

    // contents

    private  String name;
    private  int playlist_num;
    private  int num_tracks;
    private  int track_index;      // one based
    private  int my_shuffle;
    private  String pl_query;

    private  trackList tracks_by_position;
    private  intTrackHash tracks_by_open_id;

    // Basic Playlist Interface, except getTrack()

    @Override public void startPlaylist() {}
    @Override public void stopPlaylist(boolean wait_for_stop)  {}

    @Override public String getName()         { return name; }
    @Override public int getPlaylistNum()     { return playlist_num; }
    @Override public int getNumTracks()       { return num_tracks; }
    @Override public int getCurrentIndex()    { return track_index; }
    @Override public Track getCurrentTrack()  { return getTrack(track_index); }
    @Override public int getMyShuffle()       { return my_shuffle; }
    @Override public String getQuery()        { return pl_query; }

    @Override public void saveIndex(int index) {}
    @Override public boolean isDirty() { return is_dirty; }
    @Override public void setDirty(boolean b) { is_dirty = b; }

    // Current Playlist Accessors

    public int getContentChangeId() { return content_change_id; }
    public int getPlaylistCountId() { return playlist_count_id; }

    // Fetcher Interface

    private int num_virtual_folders = 0;
    private boolean fetcher_valid = true;
    private Folder last_virtual_folder = null;

    @Override public boolean isDynamicFetcherSource() { return true;  }


    public OpenPlaylist getHttpOpenPlaylist()
    // OpenHome support from CurrentPlaylist
    {
        HTTPServer http_server = artisan.getHTTPServer();
        OpenPlaylist open_playlist = http_server == null ? null :
            (OpenPlaylist) http_server.getHandler("Playlist");
        return open_playlist;
    }


    //----------------------------------------------------------------
    // Constructor
    //----------------------------------------------------------------

    public CurrentPlaylist(Artisan ma)
        // default constructor creates an un-named playlist ""
        // with no associated parent playListSource
    {
        artisan = ma;
        playlist_count_id = 0;
        content_change_id = 0;
        next_open_id = 1;

        open_playlist_device = null;

        clean_init();
    }


    private void clean_init()
    {
        name = "";
        my_shuffle = 0;
        num_tracks = 0;
        track_index = 0;
        playlist_num = 0;
        pl_query = "";

        is_dirty = false;

        tracks_by_position =  new trackList();
        tracks_by_open_id = new intTrackHash();
    }


    // start and stop the whole thing

    public boolean startCurrentPlaylist()
    {
        return true;
    }

    public void stopCurrentPlaylist(boolean wait_for_stop)
    {
    }


    public void setOpenPlaylist(prh.device.service.OpenPlaylist op)
    {
        open_playlist_device = op;
    }


    //----------------------------------------------------------------
    // associated_playlist
    //----------------------------------------------------------------

    public void setAssociatedPlaylist(Playlist other)
    {
        // clean_init() is the equivalent of start()
        // for the CurrentPlaylist

        playlist_count_id++;
        this.clean_init();

        // notify the http playlist if one is active

        prh.server.http.OpenPlaylist open_playlist = getHttpOpenPlaylist();
        if (open_playlist != null)
            open_playlist.clearAllExposers();

        // stop the old playlist

        if (associated_playlist != null)
            associated_playlist.stopPlaylist(false);
        associated_playlist = other;

        // start the new one

        if (associated_playlist != null)
        {
            associated_playlist.startPlaylist();
            name = associated_playlist.getName();
            num_tracks = associated_playlist.getNumTracks();
            track_index = associated_playlist.getCurrentIndex();
            my_shuffle = associated_playlist.getMyShuffle();

            // build the sparse array

            for (int i = 0; i < num_tracks; i++)
                tracks_by_position.add(i,null);

            // expose the first track
            // but not if open_playlist_device
            //
            // this really should only be when the renderer
            // is the local renderer ...

            if (num_tracks > 0 &&
                open_playlist != null &&
                open_playlist_device == null)
            {
                Track track = getCurrentTrack();
                if (track != null)
                    open_playlist.exposeTrack(track,true);
            }
        }
    }


    @Override
    public Track getTrack(int index)
        // Just happens to be nearly the same as
        // the implementation in LocalPlaylist
    {
        // try to get it in memory

        if (index <= 0 || index > num_tracks)
            return null;

        // get from in-memory cache

        if (index - 1 > tracks_by_position.size())
        {
            Utils.error("Attempt to get Track() at 1-based index(" + index + ") when there are only " + tracks_by_position.size() + " slots available");
            return null;
        }
        Track track = tracks_by_position.get(index - 1);

        // however, if the track is not found,
        // defer to the associated_playlist

        if (track == null)
        {
            if (associated_playlist == null)
                Utils.error("No associated playlist for null track in CurrentPlaylist.getTrck(" + index + ")");
            else
            {
                track = associated_playlist.getTrack(index);
                track.setPosition(index);   // in case it was mucked up
                track.setOpenId(next_open_id);
                tracks_by_position.set(index - 1,track);
                tracks_by_open_id.put(next_open_id++,track);
            }
        }

        return track;
    }



    //-----------------------------------------------------
    // Playlist Manipulations (Current Playlist Only)
    //-----------------------------------------------------


    public Track insertTrack(int position, Track track)
        // ONE-BASED POSITION SIGNATURE
        // This IS the lowest level insertTrack method
        // PlaylistBase assigns next_open_id for insertTrack()
    {
        int new_id = next_open_id++;
        track.setOpenId(new_id);
        track.setPosition(position);
        int old_track_index = track_index;

        Utils.log(dbg_cp,0,"insertTrack(" + track.getTitle() + " to CurrentPlaylist(" + name + ") at position=" + position);
        tracks_by_open_id.put(new_id,track);
        tracks_by_position.add(position-1,track);
        num_tracks++;

        if (track_index > position)
            track_index++;
        else if (track_index == 0)
            track_index = 1;

        if (old_track_index != track_index)
            saveIndex(track_index);

        // This should really only be when the
        // current renderer is the local renderer

        if (track != null && open_playlist_device == null)
        {
            prh.server.http.OpenPlaylist open_playlist = getHttpOpenPlaylist();
            if (open_playlist != null)
                open_playlist.exposeTrack(track,true);
        }

        is_dirty = true;
        content_change_id++;
        artisan.handleArtisanEvent(EventHandler.EVENT_PLAYLIST_CONTENT_CHANGED,this);
        return track;
    }


    public boolean removeTrack(Track track)
        // Assumes that track is in the playlist
        // Takes track and not integer position
    {
        int position = tracks_by_position.indexOf(track) + 1;
        Utils.log(dbg_cp,0,"removeTrack(" + track.getTitle() + ") from CurrentPlaylist(" + name + ") at position=" + position);

        num_tracks--;
        tracks_by_open_id.remove(track);
        tracks_by_position.remove(track);

        int old_track_index = track_index;
        if (position < track_index)
            track_index--;
        else if (track_index > num_tracks)
            track_index = num_tracks;

        if (old_track_index != track_index)
            saveIndex(track_index);

        is_dirty = true;
        content_change_id++;
        artisan.handleArtisanEvent(EventHandler.EVENT_PLAYLIST_CONTENT_CHANGED,this);
        return true;
    }


    public Track incGetTrack(int inc)
        // loop thru tracks till we find a playable
        // return null on errors or none found
    {
        int start_index = track_index;
        incIndex(inc);

        Track track = getTrack(track_index);
        if (track == null)
            return null;

        while (!Utils.supportedType(track.getType()))
        {
            if (inc != 0 && track_index == start_index)
            {
                Utils.error("No playable tracks found");
                track_index = 0;
                saveIndex(track_index);
                return null;
            }
            if (inc == 0) inc = 1;
            incIndex(inc);
            track = getTrack(track_index);
            if (track == null)
                return null;
        }

        saveIndex(track_index);
        return track;
    }


    private void incIndex(int inc)
    {
        track_index = track_index + inc;    // one based
        if (track_index > num_tracks)
            track_index = 1;
        if (track_index <= 0)
            track_index = num_tracks;
        if (track_index > num_tracks)
            track_index = num_tracks;
    }


    // support for schemes

    public Track getTrackLow(int position)
        // One based access to the sparse array
        // ONLY called by CurrentPlaylistExposer
    {
        Track track = null;
        if (position > 0 &&
            position <= tracks_by_position.size())
            track = tracks_by_position.get(position - 1);
        return track;
    }


    public void setName(String new_name)
        // Called by the PlaylistSource in saveAs()
    {
        if (!name.equals(new_name))
        {
            name = new_name;
            artisan.handleArtisanEvent(EventHandler.EVENT_PLAYLIST_CHANGED,this);
        }
    }


    //------------------------------------------------------
    // OpenHome Support
    //------------------------------------------------------

    public Track getByOpenId(int open_id)
    {
        return tracks_by_open_id.get(open_id);
    }


    public Track insertTrack(int after_id, String uri, String metadata)
    {
        Track track = new Track(uri,metadata);
        return insertTrack(track,after_id);
    }


    public Track seekByIndex(int index)
    {
        Track track = getTrack(index);
        if (track != null)
        {
            track_index = index;
            saveIndex(track_index);
        }
        return track;
    }


    public Track seekByOpenId(int open_id)
    {
        Track track = getByOpenId(open_id);
        if (track != null)
        {
            track_index = tracks_by_position.indexOf(track) + 1;
            saveIndex(track_index);
        }
        return track;
    }


    public Track insertTrack(Track track, int after_id)
        // OPEN HOME SIGNATURE
        // insert the item after the given open id
        // where 0 means front of the list.
        // returns the item on success
    {
        int insert_idx = 0; // zero base
        if (after_id > 0)
        {
            Track after_track = tracks_by_open_id.get(after_id);
            if (track == null)
            {
                Utils.error("Could not find item(after_id=" + after_id + ") for insertion");
                return null;    // should result in 800 error
            }
            insert_idx = tracks_by_position.indexOf(after_track) + 1;
            // insert_pos is zero based
        }

        int position = insert_idx + 1;
        return insertTrack(position,track);
    }



    public boolean removeTrack(int open_id, boolean dummy_for_open_id)
        // OPEN_ID SIGNATURE
    {
        Track track = tracks_by_open_id.get(open_id);
        if (track == null)
        {
            Utils.error("Could not remove(" + open_id + ") from playlist(" + name + ")");
            return false;
        }
        return removeTrack(track);
    }


    //------------------------------------------------------------------
    // IdArrays
    //------------------------------------------------------------------


    public int[] string_to_id_array(String id_string)
        // gets a string of space delimited ascii integers!
    {
        Utils.log(0,0,"string_to_id_array(" + id_string + ")");
        String parts[] = id_string.split("\\s");
        int ids[] = new int[parts.length];
        for (int i=0; i<parts.length; i++)
            ids[i] = Utils.parseInt(parts[i]);
        return ids;
    }


    public String getIdArrayString(CurrentPlaylistExposer exposer)
        // return a base64 encoded array of integers
        // May be called with a CurrentPlaylistExposer or not
    {
        int num = 0;
        int num_tracks = getNumTracks();
        byte data[] = new byte[num_tracks * 4];
        Utils.log(dbg_ida,0,"getIdArrayString() num_tracks="+num_tracks);

        // show debugging for the exposer, if any

        if (exposer != null)
            Utils.log(dbg_ida,1,"exposer(" + exposer.getUserAgent() + ") num_exposed=" + exposer.getNumExposed());

        for (int index=1; index<=num_tracks; index++)
        {
            // If there is an exposer, then a null record
            // indicates it has not yet been exposed.
            // For requests from non-exposer control points,
            // we get the Track
            //
            // Since this could take a long time if a non-exposer
            // tried to get all the tracks on a single go, it might
            // be better to just read all the records into memory from
            // a single cursor in Start() if there are any non-exposers

            Track track = (exposer == null) ?
                getTrack(index) :
                tracks_by_position.get(index - 1);

            if (track != null &&
                (exposer == null ||
                    exposer.isExposed(track)))
            {
                int id = track.getOpenId();
                data[num * 4 + 0] = (byte) ((id >> 24) & 0xFF);
                data[num * 4 + 1] = (byte) ((id >> 16) & 0xFF);
                data[num * 4 + 2] = (byte) ((id >> 8) & 0xFF);
                data[num * 4 + 3] = (byte) (id & 0xFF);
                num++;
            }
        }

        byte data2[] = new byte[num * 4];
        for (int i=0; i<num*4; i++)
            data2[i] = data[i];

        String retval = Base64.encode(data2);
        Utils.log(dbg_ida +1,1,"id_array Length=" + num);
        Utils.log(dbg_ida +1,1,"id_array='" + retval + "'");
        return retval;
    }


    public String id_array_to_tracklist(int ids[])
    {
        String rslt = "";
        for (int i=0; i<ids.length; i++)
        {
            int id = ids[i];
            Track track = getByOpenId(id);
            if (track == null)
            {
                Utils.error("id_array_to_tracklist: index("+i+")  id("+id+") not found");
                rslt += "</TrackList>";
                return rslt;
            }

            rslt += "<Entry>" + // \n" +
                "<Id>" + id + "</Id>" + // "\n" +
                "<Uri>" + track.getPublicUri() + "</Uri>" + // "\n" +
                "<Metadata>" + track.getDidl() + "</Metadata>" + // "\n" +
                "</Entry>"; // \n";
        }

        // took a while to figure this ...
        //
        // This is an INNER <TrackList> that is Didl encoded.
        // It is the VALUE of a regular XML <Tracklist> element
        // in the ReadList result.

        rslt = "<TrackList>" + rslt + "</TrackList>";
        rslt = httpUtils.encode_xml(rslt);
        return rslt;
    }



    //------------------------------
    // FetcherSource Interface
   //------------------------------



    private void initVirtualFolders()
    {
        num_virtual_folders = 0;
        last_virtual_folder = null;
    }


    private Folder addVirtualFolder(Track track)
    // Given the track, if the virtual folder does
    // not already exist, create it an initialize
    // from the track, and return.  Otherwise add
    // the track's statistics (existence, duration)
    // to the virtual folder, but return null.
    {
        String title = track.getAlbumTitle();
        String artist = track.getAlbumArtist();
        int duration = track.getDuration();
        String art_uri = track.getLocalArtUri();
        String year_str = track.getYearString();
        String genre = track.getGenre();
        String id = track.getParentId();
        if (artist.isEmpty())
            artist = track.getArtist();

        if (last_virtual_folder == null ||
            !last_virtual_folder.getTitle().equals(title))
        {
            Folder folder = new Folder();
            folder.setId(id);
            folder.setTitle(title);
            folder.setNumElements(0);   // implicit
            folder.setDuration(0);      // implicit
            folder.setArtist(artist);
            folder.setArtUri(art_uri);    // requires internal knowledge
            folder.setYearString(year_str);
            folder.setGenre(genre);
            folder.setType("album");
            last_virtual_folder = folder;
            return folder;
        }

        Folder folder = last_virtual_folder;
        String folder_title = folder.getTitle();
        String folder_artist = folder.getArtist();
        String folder_art_uri = folder.getLocalArtUri();
        String folder_year_str = folder.getYearString();
        String folder_genre = folder.getGenre();
        String folder_id = folder.getId();

        folder.incNumElements();
        folder.addDuration(duration);

        if (folder_title.isEmpty())
            folder.setTitle(title);
        if (folder_art_uri.isEmpty())
            folder.setArtUri(art_uri);        // requires special knowledge
        if (folder_year_str.isEmpty())
            folder.setYearString(year_str);
        if (folder_genre.isEmpty())
            folder.setGenre(genre);
        if (folder_id.isEmpty())
            folder.setId(id);

        String sep = folder_genre.isEmpty() ? "" : "|";
        if (!genre.isEmpty() &&
            !folder.getGenre().contains(genre))
            folder.setGenre(folder_genre + sep + genre);
        if (!artist.isEmpty() &&
            !folder.getArtist().contains(artist))
            folder.setArtist("Various");

        return null;
    }


    @Override public Fetcher.fetchResult getFetchRecords(Fetcher fetcher, boolean initial_fetch, int num)
    {
        // have the open playlist fetch the next bunch of records
        Fetcher.fetchResult result = Fetcher.fetchResult.FETCH_NONE;

        if (open_playlist_device != null)
            result = open_playlist_device.getFetchRecords(
                fetcher,initial_fetch,num);

        if (result != Fetcher.fetchResult.FETCH_ERROR)  // synchronized (this)
        {
            recordList records = fetcher.getRecordsRef();
            int num_records = records.size();

            String dbg_title = "CurrentPlaylist(" + getName() + ").getFetchRecords(" + initial_fetch + "," + num + "," + fetcher.getAlbumMode() + ") ";
            Utils.log(dbg_fetch,0, dbg_title +
                num_records + "/" + num_tracks + " tracks already gotten, and " +
                num_virtual_folders + " existing virtual folders");

            // cannot have more virtual folders than records and
            // we treat special case of num_records == 0 as restarting
            // the virtual folders

            if (num_records == 0 ||
                num_virtual_folders >= num_records)
                initVirtualFolders();

            // starting at the number of records in the fetcher
            // subtract the number of virtual folders if fetching that way
            // to get the actual 0 based track to get ...

            int num_added = 0;
            int next_index = num_records;
            if (fetcher.getAlbumMode())
                next_index -= num_virtual_folders;

            // if fetcher was invalidated start over
            // but go all the way up to the previous size + num

            if (!fetcher_valid)
            {
                Utils.log(dbg_fetch,1,dbg_title + " invalidated fetcher resetting next=0 and num=" + (num + next_index));
                num = num + next_index;
                next_index = 0;
                records.clear();
            }

            while (next_index < num_tracks && num_added < num)
            {
                Track track = getTrack(next_index + 1);    // one based
                if (track == null)
                {
                    Utils.error("Null track from getTrack(" + (next_index + 1) + " in " + dbg_title);
                    return Fetcher.fetchResult.FETCH_ERROR;
                }

                if (fetcher.getAlbumMode())
                {
                    Folder folder = addVirtualFolder(track);
                    if (folder != null)
                    {
                        track = null;
                        records.add(folder);
                        num_virtual_folders++;
                        num_added++;

                        Utils.log(dbg_fetch+1,1,"added virtual_folder[" + num_virtual_folders + "] " + folder.getTitle());
                        Utils.log(dbg_fetch+1,2,"at record[" + records.size() + "] as the " + num_added + " record in the fetch");
                    }
                }

                if (track != null)
                {
                    records.add(track);
                    num_added++;
                    next_index++;
                }
            }

            result =
                next_index >= num_tracks ? Fetcher.fetchResult.FETCH_DONE :
                    num_added > 0 ? Fetcher.fetchResult.FETCH_RECS :
                        Fetcher.fetchResult.FETCH_NONE;

            Utils.log(dbg_fetch,1,dbg_title + " returning " + result + " with " + records.size() + " records");
        }
        return result;
    }




}   // class CurrentPlaylist

