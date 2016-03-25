package prh.artisan;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.lang.reflect.Field;

import prh.types.recordList;
import prh.utils.Utils;


class ListItemAdapter extends ArrayAdapter<Record>
{
    private static int USE_SCROLL_BARS = 30;
        // Show a fixed vertical scroll bar if records.size()
        // is larger than this. We override Androids default
        // behavior by disabling fast scroll under this, and
        // always showing the scroll bar (and scrunching the
        // list) when at or above this.  Set to zero to return
        // to default Android behavior.

    private Artisan artisan;
    private ListView list_view;
    private int num_items = 0;
    private Folder folder;
    private recordList records = new recordList();
    private boolean large_folders = false;
    private boolean large_tracks = false;
    private ListItem.ListItemListener listener;

    boolean fast_scroll_setup = false;
    boolean fast_scroll_enabled = false;
    boolean fast_scroll_visible = false;


    public Folder getFolder() { return folder; }

    // ctor

    public ListItemAdapter(
        Artisan artisan,
        ListItem.ListItemListener listener,
        ListView list_view,
        Folder folder,
        recordList records,
        boolean large_folders,
        boolean large_tracks)
    {
        super(artisan,-1,records);
        this.artisan = artisan;
        this.listener = listener;
        this.list_view = list_view;
        this.folder = folder;

        this.records = records;
        this.num_items = records.size();

        this.large_folders = large_folders;
        this.large_tracks = large_tracks;

        setFastScrollVisible();

    }


     // build the views

    public View getView(int position, View re_use, ViewGroup view_group)
    {
        Record rec = records.get(position);
        ListItem list_item;

        if (re_use != null)
            list_item = (ListItem) re_use;
        else
        {
            LayoutInflater inflater = LayoutInflater.from(artisan);
            list_item = (ListItem) inflater.inflate(R.layout.list_item_layout,view_group,false);
        }
        if (rec instanceof Track)
        {
            list_item.setTrack((Track) rec);
            if (large_tracks)
                list_item.setLargeView();
        }
        else
        {
            list_item.setFolder((Folder) rec);
            if (large_folders)
                list_item.setLargeView();
        }

        list_item.doLayout(listener);
        return list_item;
    }


    // add items

    public void setItems(recordList new_records)
    // called by ARTISAN_EVENT when MediaServer adds more
    // subitems to this adapter's folder ...
    {
        Utils.log(0,4,"libraryListAdapter.setItems() num_items=" + new_records.size());
        records.clear();
        records.addAll(new_records);
        num_items = records.size();
        notifyDataSetChanged();
        setFastScrollVisible();
    }


    //----------------------------------
    // SCROLL BARS
    //----------------------------------

    private void setFastScrollVisible()
    {
        // we are forcing it to show if the USE_SCROLL_BARS
        // and the number of records exceeds it

        boolean force_visible =
            USE_SCROLL_BARS != 0 &&
            records.size() >= USE_SCROLL_BARS;

        // enabled if !USE_SCROLL_BARS (default)
        // or if we are forcing the scroll bar to show.

        boolean enabled =
            USE_SCROLL_BARS == 0 ||
            force_visible;

        // set the scrolling enabled and visible

        if (enabled != fast_scroll_enabled)
        {
            fast_scroll_enabled = enabled;
            list_view.setFastScrollEnabled(enabled);
            Utils.log(0,0,"FastScroll enabled=" + enabled);
        }
        if (fast_scroll_visible != force_visible)
        {
            fast_scroll_visible = force_visible;
            list_view.setFastScrollAlwaysVisible(force_visible);
            Utils.log(0,0," FastScroll visible=" + force_visible);
        }

        // use our thumb if enabled

        if (enabled && !fast_scroll_setup)
        {
            Utils.log(0,0,"Setting up FastScroll thumb");
            fast_scroll_setup = true;
            setFastScrollThumb();
        }


        // if USE_SCROLL_BARS
        // shrink the list_view if the scroll bar visible
        // should be gotten from the scroll bar button
        // it overlaps a bit with my button on purpose

        if (USE_SCROLL_BARS != 0)
        {
            int margin_width = force_visible ? 16 : 0;
            list_view.setPadding(0,0,margin_width,0);
        }

    }   // setFastScrollVisible()



    private void setFastScrollThumb()
        // Set the fast scroll bar thumbe to my drawable.
        // Tried to set fade duration, but could not get it working.
    {
        // this was the only way I could figure out how to set
        // the scrollbar thumb image or style it in any way.
        // it is not only klunky, but uses a deprecated api

        try
        {
            Field fs_field = AbsListView.class.getDeclaredField("mFastScroller");
            fs_field.setAccessible(true);
            Object fast_scroller = fs_field.get(list_view);

            Field thumb_field = fs_field.getType().getDeclaredField("mThumbDrawable");
            thumb_field.setAccessible(true);
            Drawable drawable = artisan.getResources().getDrawable(R.drawable.my_scroll_thumb);
            thumb_field.set(fast_scroller,drawable);

            // following attempt to set fade duration did not work
            // although it appears to traverse the private member
            // object hierarchy, and actually set the desired field value
            /*
            Field fader_field = fs_field.getType().getDeclaredField("mScrollFade");
            fader_field.setAccessible(true);
            Object fade_object = fader_field.get(fast_scroller);

            Field fader_duration = fader_field.getType().getDeclaredField("mFadeDuration");
            fader_duration.setAccessible(true);
            Object fade_duration = fader_duration.get(fade_object);

            fader_duration.set(fade_object,new Integer(200000));
            Utils.log(0,0,"fade duration = " + fader_duration.get(fade_object));
            */

        }
        catch (Exception e)
        {
            Utils.error("Could not set scroll thumb style" + e.toString());
        }

        // these do not seem to work or do anything for me

        // list_view.setScrollBarSize(400);
        // list_view.setScrollBarFadeDuration(100000);
        // list_view.setScrollBarDefaultDelayBeforeFade(2000000);
        // list_view.setFastScrollStyle(R.style.ListItemScrollBarStyle);
        // list_view.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

    }   // setFastScrollThumb()




}   // class libraryListAdapter



