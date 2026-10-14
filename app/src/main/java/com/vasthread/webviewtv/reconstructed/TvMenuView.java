package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.animation.ValueAnimator;
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
        void onSourceDetails();
        void onClose();
    }
    private static final int INK = TvTheme.INK, MUTED = TvTheme.MUTED, ACCENT = TvTheme.ACCENT;
    private static final String FAVORITES = "收藏";
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
    private final TextView sectionLabel, emptyLabel;
    private Function<JSONObject,String> programmeProvider;
    private final TextView[] tools = new TextView[4];
    private android.widget.PopupWindow morePopup;
    private final int groupWidth, rowHeight, categoryRowHeight, touchHeight, toolBarHeight;
    private String selectedCategory = FAVORITES;
    private int currentIndex = -1, currentSource, currentSourceCount, favoriteCount;
    private boolean opened, positioningCategories, toolsFromCategories;
    private int focusRequest;
    private ValueAnimator categoryScroll;
    private int pendingCategoryPosition = -1;

    public TvMenuView(Context context, List<JSONObject> channels, Listener listener) {
        super(context);
        this.listener = listener;
        this.channels = new ArrayList<>(channels);
        scale = UiScale.landscape(context, 960);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        int panelWidth = Math.round(width * .48f);
        groupWidth = Math.round((panelWidth - px(35)) * .25f);
        touchHeight = UiScale.isTelevision(context) ? px(50) : Math.max(px(64), Math.round(48 * getResources().getDisplayMetrics().density));
        rowHeight = UiScale.isTelevision(context) ? px(56) : touchHeight;
        int previousCategoryRowHeight = UiScale.isTelevision(context) ? px(40)
                : Math.max(Math.round(rowHeight * .7f), Math.round(40 * getResources().getDisplayMetrics().density));
        categoryRowHeight = Math.max(1, Math.round(previousCategoryRowHeight * .7f));
        toolBarHeight = Math.max(1, Math.round(touchHeight * .5f));
        categories.add(FAVORITES);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JSONObject channel : channels) {
            String group = groupOf(channel);
            unique.add(group);
            categoryCounts.put(group, categoryCounts.containsKey(group) ? categoryCounts.get(group) + 1 : 1);
        }
        categories.addAll(unique);
        if (!unique.isEmpty()) selectedCategory = unique.iterator().next();
        setBackgroundColor(0x16000000);
        setClickable(true);
        setFocusable(false);
        setOnClickListener(v -> requestClose());
        setVisibility(GONE);

        panel = vertical();
        panel.setPadding(px(12), 0, px(12), 0);
        panel.setBackground(shape(TvTheme.PANEL, 22, TvTheme.BORDER, 1));
        panel.setElevation(px(12));
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(panelWidth, MATCH_PARENT, Gravity.LEFT);
        panelParams.setMargins(px(24), px(24), 0, px(24));
        addView(panel, panelParams);

        LinearLayout section = horizontal();
        section.setPadding(px(10), 0, px(10), 0);
        section.addView(text("频道分组", 13, MUTED), new LinearLayout.LayoutParams(groupWidth, MATCH_PARENT));
        sectionLabel = text(selectedCategory, 13, MUTED);
        sectionLabel.setPadding(px(12), 0, 0, 0);
        section.addView(sectionLabel, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        panel.addView(section, new LinearLayout.LayoutParams(MATCH_PARENT, px(34)));

        LinearLayout lists = horizontal();
        lists.setGravity(Gravity.TOP);
        categoryList = createList();
        categoryList.setContentDescription("频道分组");
        categoryAdapter = new CategoryAdapter();
        categoryList.setAdapter(categoryAdapter);
        lists.addView(categoryList, new LinearLayout.LayoutParams(groupWidth, MATCH_PARENT));
        View columnDivider = new View(context);
        columnDivider.setBackgroundColor(TvTheme.BORDER);
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

        LinearLayout toolBar = horizontal();
        toolBar.setPadding(px(6), 0, px(6), 0);
        tools[0] = action("设置", 15, () -> { if (listener != null) listener.onSettings(); });
        tools[1] = action("☆ 收藏", 15, () -> { if (listener != null) listener.onFavorite(); });
        tools[2] = action("节目单", 15, () -> { if (listener != null) listener.onEpg(); });
        tools[3] = action("更多 ···", 15, this::showMore);
        for (TextView tool : tools) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
            params.setMargins(px(4), px(1), px(4), px(1));
            toolBar.addView(tool, params);
        }
        panel.addView(toolBar, new LinearLayout.LayoutParams(MATCH_PARENT, toolBarHeight));
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
        for (int i = 0; i < tools.length; i++) {
            final int position = i;
            tools[i].setOnKeyListener((view, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { if(toolsFromCategories)focusCategories();else focusChannels(); return true; }
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
            rebindCategories();
            if (FAVORITES.equals(selectedCategory)) filterChannels(true);
        }
        if (currentIndex >= 0) {
            JSONObject current = channels.get(currentIndex);
            tools[1].setText(favorites.contains(current.optString("name")) ? "★ 已收藏" : "☆ 收藏");
        } else {
            tools[1].setText("☆ 收藏");
        }
        // Rebind only the visible recycled rows, never rebuild a list on a directional key.
        for (int i = 0; i < channelList.getChildCount(); i++) {
            View row = channelList.getChildAt(i);
            int position = channelList.getFirstVisiblePosition() + i;
            if (row instanceof GuideRow && position < visibleChannels.size()) channelAdapter.bind((GuideRow)row, position);
        }
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

    /** Preserve a group containing the playing channel; otherwise select its own group. */
    public void focusCurrentChannel() {
        if (currentIndex >= 0 && !visibleChannels.contains(currentIndex)) {
            selectedCategory = groupOf(channels.get(currentIndex));
            filterChannels(false);
            categoryList.setSelection(categories.indexOf(selectedCategory));
            rebindCategories();
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
        if (morePopup != null) morePopup.dismiss();
        finishCategoryScroll();
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

    /** A selected channel replaces the menu with its information, without overlap. */
    public void closeImmediately() {
        close();
        animate().cancel();
        panel.animate().cancel();
        setVisibility(GONE);
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
        rebindCategories();
    }

    private void filterChannels(boolean preservePosition) {
        focusRequest++;
        positioningCategories = false;
        int previous = channelList.getSelectedItemPosition();
        visibleChannels.clear();
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.get(i);
            if (FAVORITES.equals(selectedCategory) ? favorites.contains(channel.optString("name"))
                    : selectedCategory.equals(groupOf(channel))) visibleChannels.add(i);
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
        if (visibleChannels.isEmpty()) {
            focusRequest++;
            positioningCategories = false;
            toolsFromCategories = true;
            tools[0].requestFocus();
            return;
        }
        int position = channelList.getSelectedItemPosition();
        if (position < 0) position = Math.max(0, visibleChannels.indexOf(currentIndex));
        focusListPreservingPosition(channelList, position);
    }

    private void focusCategories() {
        focusListPreservingPosition(categoryList, Math.max(0, categories.indexOf(selectedCategory)));
    }

    private void focusListPreservingPosition(ListView list, int position) {
        View selectedRow = list.getChildAt(position - list.getFirstVisiblePosition());
        final Integer top = selectedRow == null ? null : selectedRow.getTop();
        final int request = ++focusRequest;
        positioningCategories = list == categoryList;
        // Leaving touch mode may resurrect another row. Keyboard navigation should keep
        // the existing selection and viewport, not recenter the row on every transfer.
        if (list.isInTouchMode()) list.requestFocusFromTouch(); else list.requestFocus();
        restoreListPosition(list, position, top);
        postOnAnimation(() -> {
            if (request != focusRequest) return;
            if (opened && list.hasFocus()) restoreListPosition(list, position, top);
            positioningCategories = false;
            paintFocus(list);
        });
    }

    private void restoreListPosition(ListView list, int position, Integer top) {
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        if (list.getSelectedItemPosition() == position
                && (top == null || (row != null && row.getTop() == top))) return;
        if (top == null) list.setSelection(position);
        else list.setSelectionFromTop(position, top - list.getPaddingTop());
    }

    private void rebindCategories() {
        for (int i = 0; i < categoryList.getChildCount(); i++) {
            View row = categoryList.getChildAt(i);
            int position = categoryList.getFirstVisiblePosition() + i;
            if (row instanceof GuideRow && position < categories.size()) categoryAdapter.bind((GuideRow)row, position);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            finishCategoryScroll();
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
        if (inCategories && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int position = pendingCategoryPosition >= 0 ? pendingCategoryPosition : list.getSelectedItemPosition();
            int next = position + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (next >= 0 && next < list.getCount()) { scrollCategoryTo(next); return true; }
            finishCategoryScroll();
        } else finishCategoryScroll();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!inCategories) focusCategories();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (inCategories) focusChannels(); else { toolsFromCategories=false; tools[0].requestFocus(); }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP && list.getSelectedItemPosition() <= 0) {
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && list.getSelectedItemPosition() == list.getCount() - 1) {
            toolsFromCategories=inCategories;
            tools[0].requestFocus();
            return true;
        }
        return false;
    }

    private void scrollCategoryTo(int position) {
        stopCategoryAnimator();
        View first = categoryList.getChildAt(0);
        if (first == null) { categoryList.setSelection(position); return; }
        int rowHeight = first.getHeight() + categoryList.getDividerHeight();
        int top = first.getTop() + (position - categoryList.getFirstVisiblePosition()) * rowHeight;
        int desired = Math.max(categoryList.getPaddingTop(), Math.min(top,
                categoryList.getHeight() - categoryList.getPaddingBottom() - first.getHeight()));
        int distance = top - desired;
        if (distance == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            pendingCategoryPosition = -1;
            categoryList.setSelectionFromTop(position, desired - categoryList.getPaddingTop());
            return;
        }
        pendingCategoryPosition = position;
        final int[] previous = {0};
        categoryScroll = ValueAnimator.ofInt(0, distance);
        categoryScroll.setDuration(180);
        categoryScroll.setInterpolator(new DecelerateInterpolator());
        categoryScroll.addUpdateListener(animation -> {
            int offset = (int)animation.getAnimatedValue();
            categoryList.scrollListBy(offset - previous[0]);
            previous[0] = offset;
            if (animation.getAnimatedFraction() >= 1) {
                pendingCategoryPosition = -1;
                categoryList.setSelectionFromTop(position, desired - categoryList.getPaddingTop());
            }
        });
        categoryScroll.start();
    }

    private void stopCategoryAnimator() {
        if (categoryScroll != null) {
            categoryScroll.removeAllUpdateListeners();
            categoryScroll.cancel();
            categoryScroll = null;
        }
    }

    private void finishCategoryScroll() {
        stopCategoryAnimator();
        int pending = pendingCategoryPosition;
        pendingCategoryPosition = -1;
        if (pending >= 0) {
            View row = categoryList.getChildAt(pending - categoryList.getFirstVisiblePosition());
            categoryList.setSelectionFromTop(pending, row == null ? categoryList.getHeight()
                    - categoryList.getPaddingBottom() - categoryRowHeight - categoryList.getPaddingTop()
                    : row.getTop() - categoryList.getPaddingTop());
            selectCategory(pending);
        }
    }

    @Override protected void onDetachedFromWindow() { if (morePopup != null) morePopup.dismiss(); stopCategoryAnimator(); super.onDetachedFromWindow(); }

    private void paintFocus(ListView list) {
        int selected = list.getSelectedItemPosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof GuideRow) ((GuideRow)child).setFocusedRow(
                    list.hasFocus() && selected == list.getFirstVisiblePosition() + i);
        }
    }

    private void showMore() {
        if (morePopup != null && morePopup.isShowing()) return;
        LinearLayout content = vertical();
        content.setPadding(px(10), px(10), px(10), px(10));
        content.setBackground(shape(TvTheme.PANEL, 16, TvTheme.BORDER, 1));
        TextView first = action("切换直播源", 17, () -> { morePopup.dismiss(); if (listener != null) listener.onSource(); });
        content.addView(first, new LinearLayout.LayoutParams(-1, touchHeight));
        content.addView(action("刷新当前频道", 17, () -> { morePopup.dismiss(); if (listener != null) listener.onRefresh(); }), new LinearLayout.LayoutParams(-1, touchHeight));
        content.addView(action("画中画", 17, () -> { morePopup.dismiss(); if (listener != null) listener.onPip(); }), new LinearLayout.LayoutParams(-1, touchHeight));
        content.addView(action("来源详情", 17, () -> { morePopup.dismiss(); if (listener != null) listener.onSourceDetails(); }), new LinearLayout.LayoutParams(-1, touchHeight));
        morePopup = new android.widget.PopupWindow(content, px(290), touchHeight * 4 + px(20), true);
        morePopup.setBackgroundDrawable(shape(TvTheme.PANEL, 16, TvTheme.BORDER, 1));
        morePopup.setOutsideTouchable(true);
        morePopup.setElevation(px(12));
        morePopup.setOnDismissListener(() -> { if (opened) tools[3].requestFocus(); });
        int[] point = new int[2]; tools[3].getLocationOnScreen(point);
        morePopup.showAtLocation(this, Gravity.TOP | Gravity.LEFT, point[0] + tools[3].getWidth() - px(290), Math.max(px(12), point[1] - touchHeight * 4 - px(26)));
        first.requestFocus();
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiScale.text(getContext(), size, scale));
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
        view.setBackground(shape(TvTheme.CARD, 10, TvTheme.BORDER, 1));
        view.setOnClickListener(v -> runnable.run());
        view.setOnFocusChangeListener((v, focused) -> {
            view.setBackground(shape(focused ? TvTheme.FOCUS : TvTheme.CARD, 10,
                    focused ? TvTheme.ACCENT : TvTheme.BORDER, 1));
            view.animate().cancel();
            view.setScaleX(1); view.setScaleY(1);
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
        divider.setBackgroundColor(TvTheme.BORDER);
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
        final GradientDrawable active = shape(0x33274F7C, 10, 0x6635435C, 1);
        final GradientDrawable focused = shape(TvTheme.FOCUS, 10, TvTheme.ACCENT, 1);
        private boolean activeRow, focusedRow;
        private final boolean categoryRow;

        GuideRow(boolean category) {
            super(TvMenuView.this.getContext());
            categoryRow = category;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(category ? 10 : 8), 0, px(10), 0);
            setLayoutParams(new android.widget.AbsListView.LayoutParams(MATCH_PARENT, category ? categoryRowHeight : rowHeight));
            number = text("", 15, MUTED);
            if (!category) {
                number.setGravity(Gravity.CENTER);
                addView(number, new LinearLayout.LayoutParams(px(52), MATCH_PARENT));
            }
            LinearLayout labels = vertical();
            labels.setGravity(Gravity.CENTER_VERTICAL);
            name = text("", category ? 17 : 18, INK);
            labels.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, -2));
            detail = text("", 11, MUTED);
            if (!category) labels.addView(detail, new LinearLayout.LayoutParams(MATCH_PARENT, -2));
            addView(labels, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            badge = text("", 12, MUTED);
            badge.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            addView(badge, new LinearLayout.LayoutParams(px(category ? 0 : 22), MATCH_PARENT));
            if (category) badge.setVisibility(GONE);
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
            name.setEllipsize(focusedRow ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            name.setMarqueeRepeatLimit(-1);
            name.setSelected(focusedRow);
            number.setTextColor(focusedRow ? INK : activeRow ? ACCENT : MUTED);
            detail.setTextColor(MUTED);
            detail.setVisibility(categoryRow ? GONE : VISIBLE);
            detail.setEllipsize(focusedRow ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            detail.setMarqueeRepeatLimit(-1);
            detail.setSelected(focusedRow);
            badge.setTextColor(activeRow ? ACCENT : MUTED);
        }

        private void animateFocus() {
            animate().cancel();
            setScaleX(1); setScaleY(1);
        }
    }

    private final class CategoryAdapter extends BaseAdapter {
        @Override public int getCount() { return categories.size(); }
        @Override public String getItem(int position) { return categories.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            GuideRow row = recycled instanceof GuideRow ? (GuideRow)recycled : new GuideRow(true);
            bind(row, position);
            return row;
        }
        void bind(GuideRow row, int position) {
            String category = getItem(position);
            int count = FAVORITES.equals(category) ? favoriteCount
                    : categoryCounts.containsKey(category) ? categoryCounts.get(category) : 0;
            row.name.setText(category);
            row.badge.setText(String.valueOf(count));
            row.bindState(selectedCategory.equals(category),
                    categoryList.hasFocus() && categoryList.getSelectedItemPosition() == position);
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
            row.number.setText(String.format(Locale.ROOT, "%03d", index + 1));
            row.name.setText(channel.optString("name", "未命名频道"));
            String programme = programmeProvider == null ? "" : programmeProvider.apply(channel);
            row.detail.setText(programme != null && !programme.trim().isEmpty()
                    ? programme : "暂无节目单");
            row.badge.setText(index == currentIndex ? "▶" : favorites.contains(channel.optString("name")) ? "★" : "");
            row.bindState(index == currentIndex,
                    channelList.hasFocus() && channelList.getSelectedItemPosition() == position);
            row.setContentDescription((index + 1) + " " + channel.optString("name")
                    + (index == currentIndex ? " 正在播放" : ""));
        }
    }
}
