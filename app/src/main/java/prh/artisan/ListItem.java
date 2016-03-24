package prh.artisan;

// Implements a do-all list item for use in the Library and Playlist.
//
// Takes a Track or a Folder and comes in two sizes for each.
//
// Track Small is a single line of text, and used when a
// in the Library album view and Playlist when albums are showing.
//
// Track Large is two lines of text, only used in the Playlist
// when albums are not showing.
//
// Folder Small is the same size as Track Large, two lines,
// and is used in the Library Browser while traversing folders,
// showing a small image or icon, and two lines of text.
//
// Folder Large is 5 lines of text and a larger image, shown
// at the top of the Library album view, or as Album breaks
// in the playlist.
//
// Usage: inflate, setFolder or Track, maybe setLargeView(),
// and then call doLayout() before handing off to adapter


import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;

import prh.utils.ImageLoader;
import prh.utils.Utils;


public class ListItem extends RelativeLayout implements
    View.OnClickListener,
    View.OnLongClickListener
{
    // types

    public interface ListItemListener extends
        View.OnClickListener,
        View.OnLongClickListener {}

    // member variables

    Artisan artisan;
    ListItemListener listener;
    private Track track = null;
    private Folder folder = null;
    private boolean is_album = false;
    private boolean is_track = false;
    private boolean large = false;
    private boolean selected = false;
    private boolean open = false;
    private boolean visited = false;

    // accessors

    public Folder getFolder()
    {
        return folder;
    }

    public Track getTrack()
    {
        return track;
    }

    public boolean getSelected()
    {
        return selected;
    }

    public void setSelected(boolean b)
    {
        selected = b;
    }


    //-------------------------------------------------
    // post-inflate configuration
    //-------------------------------------------------

    public void setLargeView()
        // called AFTER setTrack/setFolder
    {
        large = true;
    }

    public boolean setTrack(Track the_track)
    {
        folder = null;
        is_track = true;
        track = the_track;
        large = false;
        return true;
    }

    public boolean setFolder(Folder the_folder)
    {
        track = null;
        folder = the_folder;
        is_album = folder.getType().equals("album");
        large = false;
        return true;
    }


    //------------------------------------------
    // construction, onFinishInflate()
    //------------------------------------------
    // Constructor called by inflate()

    public ListItem(Context context,AttributeSet attrs)
        // called when inflated from the layout
    {
        super(context,attrs);

        // the alternate attempt to style the scroll thumb
        // that did not work:
        //    super(new ContextThemeWrapper(context,
        //       R.style.ListItemScrollBarStyle), attrs);

        artisan = (Artisan) context;
    }


    @Override public void onFinishInflate()
        // called when the inflate is finished
    {
    }



    //---------------------------------------------
    // doLayout()
    //---------------------------------------------

    public void doLayout(ListItemListener listener)
        // cannot assume that the layout is fresh from the xml
    {
        // set the main item click listener
        // these should be combined into a new interface
        // ArtisanListItemListener

        setOnClickListener(listener);
        setOnLongClickListener(listener);

        // get sub views

        ImageView image = (ImageView) findViewById(R.id.list_item_icon);
        TextView line1 = (TextView) findViewById(R.id.list_item_line1);
        TextView line2 = (TextView) findViewById(R.id.list_item_line2);
        TextView line3 = (TextView) findViewById(R.id.list_item_line3);
        TextView line4 = (TextView) findViewById(R.id.list_item_line4);
        TextView line5 = (TextView) findViewById(R.id.list_item_line5);
        TextView item_left = (TextView) findViewById(R.id.list_item_left);
        RelativeLayout item_right = (RelativeLayout) findViewById(R.id.list_item_right);
        TextView item_right_text = (TextView) findViewById(R.id.list_item_right_text);

        // set background color

        if (large && !is_track)
            setBackgroundColor(0xFF002222);


        // IMAGE
        // set icon_container size to one of three heights
        // the width is zero for small tracks

        float container_height = is_track ?
            large ? 52 : 36 :
            large ? 70 : 52;
        float container_width = is_track && !large ?
            0 : container_height;
        Utils.setViewSize(artisan,this,R.id.list_item_icon_container,container_height,container_width);

        // set the icon size itself based on what it is,
        // and whether there is an art_uri

        String art_uri = is_track ?
            track.getLocalArtUri() :
            folder.getLocalArtUri();

        float default_image_size = 24;

        float image_size = is_track ?
            large ?  art_uri.isEmpty() ?
                default_image_size : 52 :  0 :
            art_uri.isEmpty()? default_image_size :
                large? 70 : 52;

        Utils.setViewSize(artisan,image,image_size,image_size);

        if (image_size == 0)
            image.setImageBitmap(null);
        else if (!art_uri.isEmpty())
            ImageLoader.loadImage(artisan,image,art_uri);
        else if (is_track)
            ImageLoader.loadImage(artisan,image,R.drawable.icon_track);
        else if (is_album)
            ImageLoader.loadImage(artisan,image,R.drawable.icon_album);
        else
            ImageLoader.loadImage(artisan,image,R.drawable.icon_folder);


        // TITLE (First Line)

        String title = is_track ? track.getTitle() : folder.getTitle();
        line1.setText(title);


        // NUM_TRACKS OR TRACK_TIME
        // sets the right side onClickListener if displayed

        if (is_track || !large)
        {
            int n = is_track ? 0 : folder.getNumElements();
            String num = is_track ?
                track.getDurationString(Utils.how_precise.FOR_DISPLAY) :
                n > 0 ? "(" + n + ")" : "";

            item_right.setOnClickListener(listener);
            item_right_text.setOnClickListener(listener);
            Utils.setViewSize(artisan,item_right,container_height,null);
            item_right_text.setText(num);
        }
        else
        {
            Utils.setViewSize(artisan,item_right,0F,0F);
        }


        // TRACK_NUM
        // only in small track view, show the track num,
        // if it exists, to the left. disappear it otherwise

        if (!is_track || large)
            Utils.setViewSize(artisan,item_left,null,0F);
        else
        {
            String track_num = track.getTrackNum();
            if (!track_num.isEmpty())
                track_num += ".";
            item_left.setText(track_num);
        }


        // ARTIST (Second Line)
        // get the artist to the one from the record
        // except for small tracks or folders that are not albums
        // then set it if it is not empty, or even in the
        // large folder(album) view as it takes up an empty line

        String artist = is_track ?
            large ? track.getArtist() : "" :
            is_album ? folder.getArtist() : "";

        if (!artist.isEmpty() || (large && !is_track))
            line2.setText(artist);
        else
            Utils.setViewSize(artisan,line2,0F,null);


        // LINES 3-5 are only for large albums

        if (is_track || !large)
        {
            Utils.setViewSize(artisan,line3,0F,null);
            Utils.setViewSize(artisan,line4,0F,null);
            Utils.setViewSize(artisan,line5,0F,null);
        }
        else    // large => also implies is_album
        {

            String s3 = "";
            int num = folder.getNumElements();
            if (num > 0)
                s3 += "" + num + " tracks      ";
            s3 += folder.getGenre() + "      ";
            s3 += folder.getYearString();

            String s4 = "";
            if (folder.getFolderError() != 0)
                s4 += "error:" + folder.getFolderError();
            if (folder.getHighestFolderError() != 0)
            {
                if (!s4.isEmpty()) s4 += "    ";
                s4 += "high_error:" + folder.getHighestFolderError();
            }
            if (folder.getHighestTrackError() != 0)
            {
                if (!s4.isEmpty()) s4 += "    ";
                s4 += "high_track_error:" + folder.getHighestTrackError();
            }

            String s5 = "";
            s5 += "id:" + folder.getId();
            s5 += "   parent_id:" + folder.getParentId();

            line3.setText(s3);
            line4.setText(s4);
            line5.setText(s5);
        }


        // FINISHING UP
        // Shrink the text size of line1, line2, and right
        // when its a track.  There's probably a way to do this
        // with a style or class.

        if (is_track)
        {
            line1.setTextSize(TypedValue.COMPLEX_UNIT_DIP,12);
            line2.setTextSize(TypedValue.COMPLEX_UNIT_DIP,10);
            //item_left.setTextSize(TypedValue.COMPLEX_UNIT_DIP,10);
            item_right_text.setTextSize(TypedValue.COMPLEX_UNIT_DIP,10);
        }
    }   // listItem.doLayout()


    //--------------------------------------------------------------
    // onClick()
    //--------------------------------------------------------------

    @Override public void onClick(View v)
        // Handles context menu and artisan general behavior.
        // Passes unhandled events to underlying page.
    {
        int id = v.getId();

        // All highest click handlers should call back to Artisan
        // onBodyClicked() and eat the click if it returns true.

        if (artisan.onBodyClicked())
            return;

        // Popup the list_item context menu

        ListItem list_item;
        switch (id)
        {
            case R.id.list_item_right :
            case R.id.list_item_right_text :
                String msg = "ListItem ContextMenu ";
                View item = (View) v.getParent();
                if (id == R.id.list_item_right_text)
                    item = (View) item.getParent();
                list_item = (ListItem) item;
                if (list_item.getFolder() != null)
                    msg += "Folder:" + list_item.getFolder().getTitle();
                else
                    msg += "Track:" + list_item.getTrack().getTitle();
                Toast.makeText(artisan,msg,Toast.LENGTH_LONG).show();
                break;
        }

        // pass it off to the underlying Page

        listener.onClick(v);
    }


    @Override public boolean onLongClick(View v)
        // Handles multiple selection
    {
        if (artisan.onBodyClicked())
            return true;

        if (v.getId() == R.id.list_item_layout)
        {
            ListItem list_item = (ListItem) v;
            list_item.setSelected(!list_item.getSelected());
            if (list_item.getSelected())
                list_item.setBackgroundColor(0xFF332200);
            else
                list_item.setBackgroundColor(Color.BLACK);
            return true;
        }

        return listener.onLongClick(v);
    }



}   // class ListItem
