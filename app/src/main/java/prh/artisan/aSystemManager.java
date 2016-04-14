package prh.artisan;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import prh.base.ArtisanPage;
import prh.types.intList;
import prh.utils.Utils;


public class aSystemManager extends Fragment implements ArtisanPage
{
    private static int dbg_sm = 0;
    private Artisan artisan = null;
    private TextView page_title = null;


    //----------------------------------------------
    // life cycle
    //----------------------------------------------

    public void setArtisan(Artisan ma)
    {
        artisan = ma;
    }


    @Override
    public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle savedInstanceState)
    {
        Utils.log(dbg_sm,0,"aSystemManager.onCreateView() called");
        return inflater.inflate(R.layout.activity_system_manager, container, false);
    }


    @Override
    public void onAttach(Activity activity)
    {
        Utils.log(dbg_sm,0,"aSystemManager.onAttach() called");
        super.onAttach(activity);
    }


    @Override
    public void onDetach()
    {
        Utils.log(dbg_sm,0,"aSystemManager.onDetach() called");
        super.onDetach();
    }


    //------------------------------------------
    // utilities
    //------------------------------------------

    @Override public void onSetPageCurrent(boolean current)
    {
        page_title = null;
        if (current)
        {
            page_title = new TextView(artisan);
            page_title.setText("System Manager");
            artisan.setArtisanPageTitle(page_title);
        }
    }


    @Override public boolean onMenuItemClick(MenuItem item)
    {
        return true;
    }

    @Override public intList getContextMenuIds()
    {
        return new intList();
    }

}   // class aSystemManager
