package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.os.Bundle;
import com.vasthread.webviewtv.reconstructed.TvMenuView;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashSet;

public final class NavigationActivity extends Activity {
    TvMenuView menu;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        ArrayList<JSONObject> channels = new ArrayList<>();
        try {
            for (int group = 0; group < 40; group++) {
                for (int channel = 0; channel < 30; channel++) {
                    channels.add(new JSONObject().put("name", "Channel " + group + ":" + channel)
                        .put("group", "Group " + group).put("urls", new JSONArray().put("https://example.invalid/")));
                }
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        android.content.Context menuContext=this;
        if (getIntent().getBooleanExtra("tvUi",false)) {
            android.content.res.Configuration c=new android.content.res.Configuration(getResources().getConfiguration());
            c.uiMode=(c.uiMode & ~android.content.res.Configuration.UI_MODE_TYPE_MASK)|android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
            menuContext=new android.view.ContextThemeWrapper(createConfigurationContext(c),android.R.style.Theme_Material_NoActionBar);
        }
        menu = new TvMenuView(menuContext, channels, null);
        menu.setCurrentChannel(0, 0, 1, new HashSet<>());
        setContentView(menu);
        menu.open();
    }
}
