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
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/** Scrollable native television programme guide, backed only by real XMLTV records. */
public final class EpgView extends FrameLayout {
    private static final int INK = TvTheme.INK, MUTED = TvTheme.MUTED, ACCENT = TvTheme.ACCENT;
    private final float scale;
    private final Runnable onClose;
    private final TextView title, statusLabel, closeButton, emptyLabel;
    private final ListView list;
    private final ProgrammeAdapter adapter;
    private final ArrayList<EpgRepository.Program> programmes = new ArrayList<>();
    private final SimpleDateFormat date = new SimpleDateFormat("MM-dd EEEE", Locale.CHINA);
    private final SimpleDateFormat hour = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private long now;
    private int nextIndex = -1, currentIndex = -1, focusRequest;
    private boolean opened;
    private String channelName = "";

    public EpgView(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        scale = UiScale.landscape(context, 1024);
        setBackgroundColor(0xD9040A14);
        setClickable(true);
        setFocusable(false);
        setVisibility(GONE);
        setOnClickListener(v -> requestClose());

        LinearLayout panel = vertical();
        panel.setPadding(px(24), px(14), px(24), px(12));
        panel.setBackground(shape(0xFA202222, 22, TvTheme.BORDER, 1));
        panel.setClickable(true);
        panel.setElevation(px(16));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(960), MATCH_PARENT, Gravity.CENTER);
        pp.setMargins(px(24), px(24), px(24), px(24));
        addView(panel, pp);

        LinearLayout header = horizontal();
        title = text("电视节目单", 27, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        closeButton = text("关闭", 17, INK);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setFocusable(true);
        closeButton.setClickable(true);
        closeButton.setBackground(shape(TvTheme.CARD, 10, TvTheme.BORDER, 1));
        closeButton.setOnClickListener(v -> requestClose());
        closeButton.setOnFocusChangeListener((v, focused) -> closeButton.setBackground(
                shape(focused ? TvTheme.FOCUS : TvTheme.CARD, 10, focused ? ACCENT : TvTheme.BORDER, focused ? 2 : 1)));
        header.addView(closeButton, new LinearLayout.LayoutParams(px(88), px(42)));
        panel.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, px(54)));

        statusLabel = text("", 14, MUTED);
        panel.addView(statusLabel, new LinearLayout.LayoutParams(MATCH_PARENT, px(30)));
        View separator = new View(context);
        separator.setBackgroundColor(TvTheme.BORDER);
        panel.addView(separator, new LinearLayout.LayoutParams(MATCH_PARENT, px(1)));

        FrameLayout body = new FrameLayout(context);
        list = new ListView(context);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(android.R.color.transparent);
        list.setDivider(null);
        list.setDividerHeight(px(5));
        list.setScrollingCacheEnabled(false);
        list.setVerticalScrollBarEnabled(true);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(px(2), px(10), px(2), px(10));
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(false);
        adapter = new ProgrammeAdapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        emptyLabel = text("暂无节目数据\n\n可在设置中更换 XMLTV 节目单地址，\n或在频道配置中填写对应的 epgId。", 19, MUTED);
        emptyLabel.setSingleLine(false);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setLineSpacing(px(8), 1);
        emptyLabel.setPadding(px(25), 0, px(25), 0);
        body.addView(emptyLabel, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        list.setEmptyView(emptyLabel);
        panel.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        TextView hint = text("↑↓ 浏览节目    返回关闭    节目时间按设备时区显示", 13, MUTED);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new LinearLayout.LayoutParams(MATCH_PARENT, px(30)));

        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { paintFocus(); }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { paintFocus(); }
        });
        list.setOnFocusChangeListener((v, focused) -> paintFocus());
        list.setOnItemClickListener((parent, view, position, id) -> {
            list.requestFocusFromTouch();
            list.setSelection(position);
            post(this::paintFocus);
        });
        list.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            focusRequest++;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (key == KeyEvent.KEYCODE_DPAD_UP && list.getSelectedItemPosition() <= 0)) {
                closeButton.requestFocus();
                return true;
            }
            return false;
        });
        closeButton.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusList(false);
                return true;
            }
            return false;
        });
    }

    public void open(String channelName, List<EpgRepository.Program> incoming, long now, String status) {
        String name = channelName == null ? "" : channelName;
        boolean preservePosition = opened && name.equals(this.channelName);
        int first = list.getFirstVisiblePosition();
        int oldSelection = list.getSelectedItemPosition();
        long selectedStart = oldSelection >= 0 && oldSelection < programmes.size() ? programmes.get(oldSelection).start : -1;
        View selectedView = list.getSelectedView();
        int selectedTop = selectedView == null ? px(85) : selectedView.getTop();
        boolean preserveFocus = preservePosition && list.hasFocus();
        View firstView = list.getChildAt(0);
        int top = firstView == null ? px(10) : firstView.getTop();
        this.channelName = name;
        this.now = now;
        programmes.clear();
        if (incoming != null) for (EpgRepository.Program programme : incoming) if (programme != null) programmes.add(programme);
        Collections.sort(programmes, Comparator.comparingLong(p -> p.start));
        currentIndex = -1;
        nextIndex = -1;
        for (int i = 0; i < programmes.size(); i++) {
            EpgRepository.Program programme = programmes.get(i);
            if (currentIndex < 0 && programme.start <= now && programme.end > now) currentIndex = i;
            if (nextIndex < 0 && programme.start > now) nextIndex = i;
        }
        title.setText(name.isEmpty() ? "电视节目单" : name + " · 节目单");
        statusLabel.setText(status == null ? "" : status);
        adapter.notifyDataSetChanged();
        list.setFocusable(!programmes.isEmpty());
        opened = true;
        setVisibility(VISIBLE);
        bringToFront();
        if (preservePosition && !programmes.isEmpty()) {
            int selection = -1;
            if (preserveFocus) {
                for (int i = 0; i < programmes.size(); i++) if (programmes.get(i).start == selectedStart) { selection = i; break; }
            }
            list.setSelectionFromTop(selection >= 0 ? selection : Math.min(first, programmes.size() - 1),
                    selection >= 0 ? selectedTop : top);
            post(this::paintFocus);
        } else if (programmes.isEmpty()) {
            post(() -> { if (opened) closeButton.requestFocusFromTouch(); });
        } else {
            focusList(true);
        }
    }

    public void close() {
        opened = false;
        focusRequest++;
        clearFocus();
        setVisibility(GONE);
    }

    public boolean isOpen() { return opened; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) focusRequest++;
        return super.dispatchTouchEvent(event);
    }

    private void focusList(boolean locateCurrent) {
        if (programmes.isEmpty()) { closeButton.requestFocus(); return; }
        int initial = currentIndex >= 0 ? currentIndex : nextIndex >= 0 ? nextIndex : programmes.size() - 1;
        int selected = list.getSelectedItemPosition();
        final int position = locateCurrent || selected < 0 ? initial : selected;
        final int request = ++focusRequest;
        list.requestFocusFromTouch();
        list.setSelectionFromTop(position, px(85));
        postOnAnimation(() -> {
            if (opened && request == focusRequest && list.hasFocus()) {
                list.setSelectionFromTop(position, px(85));
                list.post(this::paintFocus);
            }
        });
    }

    private void requestClose() {
        close();
        if (onClose != null) onClose.run();
    }

    private void paintFocus() {
        int selected = list.getSelectedItemPosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof ProgrammeRow) {
                int position = list.getFirstVisiblePosition() + i;
                ((ProgrammeRow) child).style(position == currentIndex, list.hasFocus() && position == selected);
            }
        }
    }

    private final class ProgrammeAdapter extends BaseAdapter {
        @Override public int getCount() { return programmes.size(); }
        @Override public EpgRepository.Program getItem(int position) { return programmes.get(position); }
        @Override public long getItemId(int position) { return getItem(position).start; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ProgrammeRow row = recycled instanceof ProgrammeRow ? (ProgrammeRow) recycled : new ProgrammeRow();
            EpgRepository.Program programme = getItem(position);
            row.dateLabel.setText(date.format(new Date(programme.start)));
            String endDate = date.format(new Date(programme.end));
            String startDate = date.format(new Date(programme.start));
            row.timeLabel.setText(hour.format(new Date(programme.start)) + " — " + hour.format(new Date(programme.end)));
            row.nameLabel.setText(programme.title.isEmpty() ? "未提供节目名称" : programme.title);
            row.badge.setText(position == currentIndex ? "正在播" : position == nextIndex ? "接下来"
                    : programme.end <= now ? "已播出" : "未开始");
            row.description.setText(!startDate.equals(endDate) ? "结束于 " + endDate : programme.description);
            row.description.setVisibility(row.description.getText().length() == 0 ? GONE : VISIBLE);
            row.style(position == currentIndex, list.hasFocus() && position == list.getSelectedItemPosition());
            row.setContentDescription(startDate + " " + row.timeLabel.getText() + " " + programme.title + " " + row.badge.getText());
            return row;
        }
    }

    private final class ProgrammeRow extends LinearLayout {
        final TextView dateLabel, timeLabel, nameLabel, description, badge;
        private final GradientDrawable idle = shape(TvTheme.CARD, 12, TvTheme.BORDER, 1);
        private final GradientDrawable current = shape(0xFF2D4942, 12, TvTheme.ACCENT, 1);
        private final GradientDrawable focused = shape(TvTheme.FOCUS, 12, ACCENT, 2);
        ProgrammeRow() {
            super(EpgView.this.getContext());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(px(18), px(8), px(16), px(8));
            setLayoutParams(new android.widget.AbsListView.LayoutParams(MATCH_PARENT, px(84)));
            LinearLayout time = vertical();
            time.setGravity(Gravity.CENTER_VERTICAL);
            dateLabel = text("", 13, MUTED);
            timeLabel = text("", 18, INK);
            time.addView(dateLabel, new LinearLayout.LayoutParams(MATCH_PARENT, px(20)));
            time.addView(timeLabel, new LinearLayout.LayoutParams(MATCH_PARENT, px(28)));
            addView(time, new LinearLayout.LayoutParams(px(200), MATCH_PARENT));
            LinearLayout info = vertical();
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(px(16), 0, px(18), 0);
            nameLabel = text("", 20, INK);
            nameLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            description = text("", 13, MUTED);
            info.addView(nameLabel, new LinearLayout.LayoutParams(MATCH_PARENT, px(30)));
            info.addView(description, new LinearLayout.LayoutParams(MATCH_PARENT, px(20)));
            addView(info, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            badge = text("", 14, MUTED);
            badge.setGravity(Gravity.CENTER);
            addView(badge, new LinearLayout.LayoutParams(px(82), px(32)));
        }
        void style(boolean playing, boolean hasFocus) {
            setBackground(hasFocus ? focused : playing ? current : idle);
            dateLabel.setTextColor(hasFocus ? TvTheme.INK : MUTED);
            description.setTextColor(hasFocus ? TvTheme.INK : MUTED);
            badge.setTextColor(hasFocus ? INK : playing ? ACCENT : MUTED);
        }
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(getContext());
        result.setText(value);
        result.setTextColor(color);
        result.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setSingleLine(true);
        result.setEllipsize(TextUtils.TruncateAt.END);
        result.setIncludeFontPadding(false);
        return result;
    }

    private LinearLayout vertical() {
        LinearLayout result = new LinearLayout(getContext());
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout horizontal() {
        LinearLayout result = new LinearLayout(getContext());
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private GradientDrawable shape(int fill, int radius, int stroke, int width) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(px(radius));
        result.setStroke(px(width), stroke);
        return result;
    }

    private int px(int value) { return Math.max(1, Math.round(value * scale)); }
}
