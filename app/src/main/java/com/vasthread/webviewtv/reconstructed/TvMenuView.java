package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Function;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/** Native television guide; all sizes scale from a 1280 x 720 reference canvas. */
public final class TvMenuView extends FrameLayout {
    public interface Listener {
        void onChannelSelected(int index);
        void onSource();
        void onFavorite();
        void onRefresh();
        void onPip();
        void onSettings();
        void onEpg();
        void onClose();
    }
    private static final int INK = 0xFFF4F7FF, MUTED = 0xFF9CAEC8, ACCENT = 0xFF78B9FF;
    private static final String ALL = "全部频道", FAVORITES = "收藏";
    private final float scale;
    private final Listener listener;
    private final List<JSONObject> channels;
    private final ArrayList<String> categories = new ArrayList<>();
    private final Map<String,Integer> categoryCounts = new HashMap<>();
    private final ArrayList<Integer> visibleChannels = new ArrayList<>();
    private Set<String> favorites = new HashSet<>();
    private final LinearLayout panel;
    private final ListView categoryList, channelList;
    private final CategoryAdapter categoryAdapter;
    private final ChannelAdapter channelAdapter;
    private final TextView sectionLabel, playingName, playingSource, emptyLabel, closeButton, settingsButton;
    private final TextView nowProgramme, nextProgramme;
    private final LinearLayout programmeInfo;
    private Function<JSONObject,String> programmeProvider;
    private final TextView[] tools = new TextView[5];
    private String selectedCategory = ALL;
    private int currentIndex = -1, currentSource, currentSourceCount, favoriteCount;
    private boolean opened, positioningCategories;
    private int focusRequest;

    public TvMenuView(Context context, List<JSONObject> channels, Listener listener) {
        super(context);
        this.listener = listener;
        this.channels = new ArrayList<>(channels);
        scale = Math.min(getResources().getDisplayMetrics().heightPixels / 720f,
                getResources().getDisplayMetrics().widthPixels / 960f);
        categories.add(ALL);
        categories.add(FAVORITES);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JSONObject channel : channels) {
            String group = groupOf(channel);
            unique.add(group);
            categoryCounts.put(group, categoryCounts.containsKey(group) ? categoryCounts.get(group) + 1 : 1);
        }
        categories.addAll(unique);
        setBackgroundColor(0x72040A14);
        setClickable(true);
        setFocusable(false);
        setOnClickListener(v -> requestClose());
        setVisibility(GONE);

        panel = vertical();
        panel.setPadding(px(12), 0, px(12), 0);
        panel.setBackground(shape(0xF5182438, 22, 0xFF35435C, 1));
        panel.setElevation(px(12));
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(px(620), MATCH_PARENT, Gravity.LEFT);
        panelParams.setMargins(px(24), px(24), 0, px(24));
        addView(panel, panelParams);

        LinearLayout header = horizontal();
        header.setPadding(px(12), 0, px(8), 0);
        ImageView appIcon = new ImageView(context);
        appIcon.setImageDrawable(context.getApplicationInfo().loadIcon(context.getPackageManager()));
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(px(38), px(38));
        iconParams.setMargins(0, 0, px(8), 0);
        header.addView(appIcon, iconParams);
        LinearLayout branding = vertical();
        branding.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("WebView 电视", 25, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        branding.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, px(32)));
        branding.addView(text("频道指南", 13, MUTED), new LinearLayout.LayoutParams(MATCH_PARENT, px(20)));
        header.addView(branding, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        TextView count = text(channels.size() + " 个频道", 14, MUTED);
        count.setGravity(Gravity.CENTER);
        header.addView(count, new LinearLayout.LayoutParams(px(100), MATCH_PARENT));
        settingsButton = action("设置", 15, () -> { if (listener != null) listener.onSettings(); });
        settingsButton.setContentDescription("打开设置");
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(px(64), px(42));
        settingsParams.setMargins(px(6), 0, px(8), 0);
        header.addView(settingsButton, settingsParams);
        closeButton = action("×", 26, this::requestClose);
        closeButton.setContentDescription("关闭频道菜单");
        header.addView(closeButton, new LinearLayout.LayoutParams(px(42), px(42)));
        panel.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, px(76)));
        addDivider(panel);

        LinearLayout section = horizontal();
        section.setPadding(px(10), 0, px(10), 0);
        section.addView(text("频道分组", 13, MUTED), new LinearLayout.LayoutParams(px(160), MATCH_PARENT));
        sectionLabel = text(ALL + " · " + channels.size(), 13, MUTED);
        sectionLabel.setPadding(px(12), 0, 0, 0);
        section.addView(sectionLabel, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        panel.addView(section, new LinearLayout.LayoutParams(MATCH_PARENT, px(34)));

        LinearLayout lists = horizontal();
        lists.setGravity(Gravity.TOP);
        categoryList = createList();
        categoryList.setContentDescription("频道分组");
        categoryAdapter = new CategoryAdapter();
        categoryList.setAdapter(categoryAdapter);
        lists.addView(categoryList, new LinearLayout.LayoutParams(px(160), MATCH_PARENT));
        View columnDivider = new View(context);
        columnDivider.setBackgroundColor(0xFF2D3A50);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(px(1), MATCH_PARENT);
        dividerParams.setMargins(px(5), px(4), px(5), px(4));
        lists.addView(columnDivider, dividerParams);
        FrameLayout channelArea = new FrameLayout(context);
        channelList = createList();
        channelList.setContentDescription("频道列表");
        channelAdapter = new ChannelAdapter();
        channelList.setAdapter(channelAdapter);
        channelArea.addView(channelList, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        emptyLabel = text("还没有收藏\n播放频道后，点「收藏」即可添加", 17, MUTED);
        emptyLabel.setSingleLine(false);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setLineSpacing(px(8), 1);
        emptyLabel.setPadding(px(16), 0, px(16), 0);
        channelArea.addView(emptyLabel, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        channelList.setEmptyView(emptyLabel);
        lists.addView(channelArea, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        panel.addView(lists, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        addDivider(panel);

        LinearLayout nowPlaying = horizontal();
        nowPlaying.setPadding(px(12), 0, px(12), 0);
        LinearLayout currentInfo = vertical();
        currentInfo.setGravity(Gravity.CENTER_VERTICAL);
        currentInfo.addView(text("正在播放", 11, ACCENT), new LinearLayout.LayoutParams(MATCH_PARENT, px(18)));
        playingName = text("选择一个频道开始观看", 20, INK);
        playingName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentInfo.addView(playingName, new LinearLayout.LayoutParams(MATCH_PARENT, px(28)));
        nowPlaying.addView(currentInfo, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        playingSource = text("", 14, MUTED);
        playingSource.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        nowPlaying.addView(playingSource, new LinearLayout.LayoutParams(px(88), MATCH_PARENT));
        panel.addView(nowPlaying, new LinearLayout.LayoutParams(MATCH_PARENT, px(54)));
        programmeInfo = vertical();
        programmeInfo.setPadding(px(12), 0, px(12), px(4));
        nowProgramme = text("", 13, 0xFFC7DCF3);
        nextProgramme = text("", 12, MUTED);
        programmeInfo.addView(nowProgramme, new LinearLayout.LayoutParams(MATCH_PARENT, px(18)));
        programmeInfo.addView(nextProgramme, new LinearLayout.LayoutParams(MATCH_PARENT, px(18)));
        programmeInfo.setVisibility(GONE);
        panel.addView(programmeInfo, new LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout toolBar = horizontal();
        toolBar.setPadding(px(6), 0, px(6), 0);
        tools[0] = action("☆ 收藏", 15, () -> { if (listener != null) listener.onFavorite(); });
        tools[1] = action("⇄ 切源", 15, () -> { if (listener != null) listener.onSource(); });
        tools[2] = action("↻ 刷新", 15, () -> { if (listener != null) listener.onRefresh(); });
        tools[3] = action("▣ 画中画", 15, () -> { if (listener != null) listener.onPip(); });
        tools[4] = action("节目单", 15, () -> { if (listener != null) listener.onEpg(); });
        for (TextView tool : tools) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
            params.setMargins(px(4), px(2), px(4), px(2));
            toolBar.addView(tool, params);
        }
        panel.addView(toolBar, new LinearLayout.LayoutParams(MATCH_PARENT, px(50)));
        TextView hints = text("↑↓ 浏览    ←→ 切换区域    确定播放", 12, MUTED);
        hints.setGravity(Gravity.CENTER);
        panel.addView(hints, new LinearLayout.LayoutParams(MATCH_PARENT, px(32)));

        categoryList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { paintFocus(categoryList); }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (categoryList.hasFocus() && !positioningCategories) selectCategory(position);
                paintFocus(categoryList);
            }
        });
        categoryList.setOnItemClickListener((parent, view, position, id) -> {
            selectCategory(position);
            focusChannels();
        });
        channelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { paintFocus(channelList); }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                paintFocus(channelList);
            }
        });
        channelList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleChannels.size()) return;
            int index = visibleChannels.get(position);
            close();
            if (listener != null) listener.onChannelSelected(index);
        });
        categoryList.setOnFocusChangeListener((view, focused) -> paintFocus(categoryList));
        channelList.setOnFocusChangeListener((view, focused) -> paintFocus(channelList));
        categoryList.setOnKeyListener((view, key, event) -> handleListKey(categoryList, key, event));
        channelList.setOnKeyListener((view, key, event) -> handleListKey(channelList, key, event));
        closeButton.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusChannels(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { settingsButton.requestFocus(); return true; }
            return false;
        });
        settingsButton.setOnKeyListener((view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusChannels(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { focusCategories(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { closeButton.requestFocus(); return true; }
            return false;
        });
        for (int i = 0; i < tools.length; i++) {
            final int position = i;
            tools[i].setOnKeyListener((view, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { focusChannels(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (position > 0) tools[position - 1].requestFocus(); else focusCategories();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (position < tools.length - 1) tools[position + 1].requestFocus();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_DOWN;
            });
        }
        filterChannels(false);
    }

    /** Favorites use channel names, matching the Activity's persisted favorite keys. */
    public void setCurrentChannel(int index, int sourceIndex, int sourceCount, Set<String> favoriteNames) {
        Set<String> incoming = favoriteNames == null ? new HashSet<>() : new HashSet<>(favoriteNames);
        boolean favoritesChanged = !favorites.equals(incoming);
        favorites = incoming;
        currentIndex = index >= 0 && index < channels.size() ? index : -1;
        currentSource = Math.max(0, sourceIndex);
        currentSourceCount = Math.max(0, sourceCount);
        if (favoritesChanged) {
            favoriteCount = 0;
            for (JSONObject channel : channels) if (favorites.contains(channel.optString("name"))) favoriteCount++;
            categoryAdapter.notifyDataSetChanged();
            if (FAVORITES.equals(selectedCategory)) filterChannels(true);
        }
        if (currentIndex >= 0) {
            JSONObject current = channels.get(currentIndex);
            playingName.setText(current.optString("name", "未命名频道"));
            playingSource.setText(currentSourceCount > 0
                    ? "源 " + (currentSource + 1) + " / " + currentSourceCount : "暂无播放源");
            tools[0].setText(favorites.contains(current.optString("name")) ? "★ 已收藏" : "☆ 收藏");
        } else {
            playingName.setText("选择一个频道开始观看");
            playingSource.setText("");
            tools[0].setText("☆ 收藏");
        }
        // Rebind only the visible recycled rows, never rebuild a list on a directional key.
        for (int i = 0; i < channelList.getChildCount(); i++) {
            View row = channelList.getChildAt(i);
            int position = channelList.getFirstVisiblePosition() + i;
            if (row instanceof GuideRow && position < visibleChannels.size()) channelAdapter.bind((GuideRow)row, position);
        }
    }

    /** Current programme hints; empty next text keeps the footer compact. */
    public void setProgramme(String now, String next) {
        String current = now == null ? "" : now.trim();
        String upcoming = next == null ? "" : next.trim();
        nowProgramme.setText(current.isEmpty() ? "" : "现在 · " + current);
        nextProgramme.setText(upcoming.isEmpty() ? "" : "接下来 · " + upcoming);
        nowProgramme.setVisibility(current.isEmpty() ? GONE : VISIBLE);
        nextProgramme.setVisibility(upcoming.isEmpty() ? GONE : VISIBLE);
        programmeInfo.setVisibility(current.isEmpty() && upcoming.isEmpty() ? GONE : VISIBLE);
    }

    /** The provider must use an in-memory guide; never perform network work while binding rows. */
    public void setProgrammeProvider(Function<JSONObject,String> provider) {
        programmeProvider = provider;
        for (int i = 0; i < channelList.getChildCount(); i++) {
            View row = channelList.getChildAt(i);
            int position = channelList.getFirstVisiblePosition() + i;
            if (row instanceof GuideRow && position < visibleChannels.size()) channelAdapter.bind((GuideRow)row, position);
        }
    }

    /** Preserve a chosen group containing the playing channel; otherwise show all channels. */
    public void focusCurrentChannel() {
        if (currentIndex >= 0 && !visibleChannels.contains(currentIndex)) {
            selectedCategory = ALL;
            filterChannels(false);
            categoryList.setSelection(0);
            categoryAdapter.notifyDataSetChanged();
        }
        focusChannels();
        final int position = visibleChannels.indexOf(currentIndex);
        final int request = ++focusRequest;
        if (position >= 0) {
            // Focus-from-touch may choose a different visible row. Position after acquiring focus.
            channelList.setSelectionFromTop(position, px(104));
            postOnAnimation(() -> {
                if (opened && request == focusRequest && channelList.hasFocus()) {
                    channelList.setSelectionFromTop(position, px(104));
                    channelList.post(() -> paintFocus(channelList));
                }
            });
        }
    }

    public void open() {
        if (opened) return;
        opened = true;
        focusRequest++;
        animate().cancel();
        panel.animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0);
        panel.setTranslationX(-px(18));
        animate().alpha(1).setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
        panel.animate().translationX(0).setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
        post(() -> { if (opened) focusCurrentChannel(); });
    }

    public void close() {
        if (!opened) { setVisibility(GONE); return; }
        opened = false;
        focusRequest++;
        positioningCategories = false;
        clearFocus();
        animate().cancel();
        panel.animate().cancel();
        animate().alpha(0).setDuration(120).withEndAction(() -> { if (!opened) setVisibility(GONE); }).start();
        panel.animate().translationX(-px(18)).setDuration(120).start();
    }

    public boolean isOpen() { return opened; }

    private void requestClose() {
        close();
        if (listener != null) listener.onClose();
    }

    private void selectCategory(int position) {
        if (position < 0 || position >= categories.size()) return;
        String category = categories.get(position);
        if (selectedCategory.equals(category)) return;
        selectedCategory = category;
        filterChannels(false);
        categoryAdapter.notifyDataSetChanged();
    }

    private void filterChannels(boolean preservePosition) {
        int previous = channelList.getSelectedItemPosition();
        visibleChannels.clear();
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.get(i);
            if (ALL.equals(selectedCategory)
                    || (FAVORITES.equals(selectedCategory) && favorites.contains(channel.optString("name")))
                    || selectedCategory.equals(groupOf(channel))) visibleChannels.add(i);
        }
        sectionLabel.setText(selectedCategory + " · " + visibleChannels.size());
        emptyLabel.setText(FAVORITES.equals(selectedCategory)
                ? "还没有收藏\n播放频道后，点「收藏」即可添加" : "这个分组暂时没有频道");
        channelAdapter.notifyDataSetChanged();
        channelList.setFocusable(!visibleChannels.isEmpty());
        int currentPosition = visibleChannels.indexOf(currentIndex);
        int selection = preservePosition ? Math.min(Math.max(0, previous), visibleChannels.size() - 1)
                : Math.max(0, currentPosition);
        if (!visibleChannels.isEmpty()) channelList.setSelection(selection);
    }

    private void focusChannels() {
        if (visibleChannels.isEmpty()) { tools[0].requestFocus(); return; }
        channelList.requestFocusFromTouch();
        if (channelList.getSelectedItemPosition() < 0) {
            channelList.setSelection(Math.max(0, visibleChannels.indexOf(currentIndex)));
        }
        post(() -> paintFocus(channelList));
    }

    private void focusCategories() {
        final int position = Math.max(0, categories.indexOf(selectedCategory));
        final int request = ++focusRequest;
        positioningCategories = true;
        categoryList.requestFocusFromTouch();
        categoryList.setSelectionFromTop(position, px(104));
        postOnAnimation(() -> {
            if (request != focusRequest) return;
            if (opened && categoryList.hasFocus()) categoryList.setSelectionFromTop(position, px(104));
            positioningCategories = false;
            paintFocus(categoryList);
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            focusRequest++;
            positioningCategories = false;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean handleListKey(ListView list, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        focusRequest++;
        positioningCategories = false;
        boolean inCategories = list == categoryList;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!inCategories) focusCategories();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (inCategories) focusChannels(); else tools[0].requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP && list.getSelectedItemPosition() <= 0) {
            closeButton.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && list.getSelectedItemPosition() == list.getCount() - 1) {
            tools[0].requestFocus();
            return true;
        }
        return false;
    }

    private void paintFocus(ListView list) {
        int selected = list.getSelectedItemPosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof GuideRow) ((GuideRow)child).setFocusedRow(
                    list.hasFocus() && selected == list.getFirstVisiblePosition() + i);
        }
    }

    private ListView createList() {
        ListView list = new ListView(getContext());
        list.setId(View.generateViewId());
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(android.R.color.transparent);
        list.setDivider(null);
        list.setDividerHeight(px(3));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setScrollingCacheEnabled(false);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setPadding(px(4), px(4), px(4), px(4));
        list.setClipToPadding(false);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(false);
        return list;
    }

    private int px(float reference) { return Math.max(1, Math.round(reference * scale)); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView action(String label, int size, Runnable runnable) {
        TextView view = text(label, size, INK);
        view.setId(View.generateViewId());
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(shape(0xFF25354D, 10, 0xFF3B4D67, 1));
        view.setOnClickListener(v -> runnable.run());
        view.setOnFocusChangeListener((v, focused) -> {
            view.setBackground(shape(focused ? 0xFF2864B8 : 0xFF25354D, 10,
                    focused ? 0xFF9AD0FF : 0xFF3B4D67, focused ? 2 : 1));
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.035f : 1).scaleY(focused ? 1.035f : 1).setDuration(100).start();
        });
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private GradientDrawable shape(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(px(radius));
        if (width > 0) drawable.setStroke(px(width), stroke);
        return drawable;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(getContext());
        divider.setBackgroundColor(0xFF2D3A50);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, px(1));
        params.setMargins(px(10), 0, px(10), 0);
        parent.addView(divider, params);
    }

    private String groupOf(JSONObject channel) {
        String group = channel.optString("group", "其他");
        return group.isEmpty() ? "其他" : group;
    }

    private final class GuideRow extends LinearLayout {
        final TextView number, name, detail, badge;
        final GradientDrawable idle = shape(0x00000000, 10, 0, 0);
        final GradientDrawable active = shape(0xFF223D5A, 10, 0xFF456A91, 1);
        final GradientDrawable focused = shape(0xFF2864B8, 10, 0xFF9AD0FF, 2);
        private boolean activeRow, focusedRow;

        GuideRow(boolean category) {
            super(TvMenuView.this.getContext());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(category ? 10 : 8), 0, px(10), 0);
            setLayoutParams(new android.widget.AbsListView.LayoutParams(MATCH_PARENT, px(49)));
            number = text("", 15, MUTED);
            if (!category) {
                number.setGravity(Gravity.CENTER);
                addView(number, new LinearLayout.LayoutParams(px(38), MATCH_PARENT));
            }
            LinearLayout labels = vertical();
            labels.setGravity(Gravity.CENTER_VERTICAL);
            name = text("", category ? 17 : 18, INK);
            labels.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, px(category ? 32 : 25)));
            detail = text("", 11, MUTED);
            if (!category) labels.addView(detail, new LinearLayout.LayoutParams(MATCH_PARENT, px(17)));
            addView(labels, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            badge = text("", 12, MUTED);
            badge.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            addView(badge, new LinearLayout.LayoutParams(px(category ? 30 : 52), MATCH_PARENT));
            setBackground(idle);
        }

        void bindState(boolean active, boolean focus) {
            boolean changed = focusedRow != focus;
            activeRow = active;
            focusedRow = focus;
            updateStyle();
            if (changed) animateFocus();
        }

        void setFocusedRow(boolean focus) {
            if (focusedRow == focus) return;
            focusedRow = focus;
            updateStyle();
            animateFocus();
        }

        private void updateStyle() {
            setBackground(focusedRow ? focused : activeRow ? active : idle);
            number.setTextColor(focusedRow ? INK : activeRow ? ACCENT : MUTED);
            detail.setTextColor(focusedRow ? 0xFFD3E7FF : MUTED);
            badge.setTextColor(focusedRow ? INK : activeRow ? ACCENT : MUTED);
        }

        private void animateFocus() {
            animate().cancel();
            animate().scaleX(focusedRow ? 1.015f : 1).scaleY(focusedRow ? 1.015f : 1).setDuration(100).start();
        }
    }

    private final class CategoryAdapter extends BaseAdapter {
        @Override public int getCount() { return categories.size(); }
        @Override public String getItem(int position) { return categories.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            GuideRow row = recycled instanceof GuideRow ? (GuideRow)recycled : new GuideRow(true);
            String category = getItem(position);
            int count = ALL.equals(category) ? channels.size() : FAVORITES.equals(category) ? favoriteCount
                    : categoryCounts.containsKey(category) ? categoryCounts.get(category) : 0;
            row.name.setText(category);
            row.badge.setText(String.valueOf(count));
            row.bindState(selectedCategory.equals(category),
                    categoryList.hasFocus() && categoryList.getSelectedItemPosition() == position);
            return row;
        }
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleChannels.size(); }
        @Override public Integer getItem(int position) { return visibleChannels.get(position); }
        @Override public long getItemId(int position) { return getItem(position); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            GuideRow row = recycled instanceof GuideRow ? (GuideRow)recycled : new GuideRow(false);
            bind(row, position);
            return row;
        }
        void bind(GuideRow row, int position) {
            int index = getItem(position);
            JSONObject channel = channels.get(index);
            JSONArray sources = channel.optJSONArray("urls");
            int sourceCount = sources == null ? 0 : sources.length();
            row.number.setText(String.format(Locale.ROOT, "%03d", index + 1));
            row.name.setText(channel.optString("name", "未命名频道"));
            String programme = programmeProvider == null ? "" : programmeProvider.apply(channel);
            row.detail.setText(programme != null && !programme.trim().isEmpty()
                    ? programme : groupOf(channel) + " · " + sourceCount + " 个播放源");
            row.badge.setText(index == currentIndex ? "播放中" : favorites.contains(channel.optString("name")) ? "★" : "");
            row.bindState(index == currentIndex,
                    channelList.hasFocus() && channelList.getSelectedItemPosition() == position);
            row.setContentDescription((index + 1) + " " + channel.optString("name")
                    + (index == currentIndex ? " 正在播放" : ""));
        }
    }
}
