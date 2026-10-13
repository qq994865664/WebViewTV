package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Television settings with a draft committed only by the Save button. */
public final class SettingsView extends FrameLayout {
    public interface Listener {
        void onSave(JSONObject settings);
        void onImportChannels();
        void onExportConfig();
        void onRefreshChannels();
        void onRefreshEpg();
        void onEditChannels();
        void onResetDefaults();
        void onClose();
    }

    private static final int INK = 0xFFF1F6FF, MUTED = 0xFFA8B7CF;
    private static final int ACCENT = 0xFF79BDFF, CARD = 0xFF17253A;
    private static final int[] CHANNEL_HOURS = {2, 6, 24, 72, 120, 240, 720};
    private static final String[] CHANNEL_LABELS = {"2 小时", "6 小时", "24 小时", "3 天", "5 天", "10 天", "30 天"};
    private static final int[] EPG_HOURS = {2, 6, 24};
    private static final String[] EPG_LABELS = {"2 小时", "6 小时", "24 小时"};
    private final Listener listener;
    private final float scale;
    private final LinearLayout panel, content;
    private final ScrollView scroll;
    private final TextView closeButton, channelStatusLabel, epgStatusLabel, pathLabel;
    private final EditText channelUrl, epgUrl;
    private final Spinner channelInterval, epgInterval;
    private final CheckBox epgEnabled, fastSwitch, rememberSource;
    private final TextView saveButton, remoteHint;
    private boolean opened;

    public SettingsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        scale = UiScale.landscape(context, 960);
        setBackgroundColor(0xDB040A14);
        setVisibility(GONE);
        setClickable(true);
        setFocusable(false);
        setOnClickListener(v -> listener.onClose());
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        panel = vertical();
        panel.setPadding(px(24), px(12), px(24), px(16));
        panel.setBackground(shape(0xFC102035, 22, 0xFF354A68, 1));
        panel.setClickable(true);
        panel.setElevation(px(16));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(900), MATCH_PARENT, Gravity.CENTER);
        pp.setMargins(px(24), px(24), px(24), px(24));
        addView(panel, pp);

        LinearLayout heading = horizontal();
        TextView title = text("设置", 27, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        TextView instruction = text("修改后请点击底部「保存并返回」", 14, MUTED);
        instruction.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(instruction, new LinearLayout.LayoutParams(px(340), MATCH_PARENT));
        closeButton = button("关闭", () -> listener.onClose());
        closeButton.setContentDescription("关闭设置，不保存更改");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(84), px(42));
        cp.leftMargin = px(20);
        cp.gravity = Gravity.CENTER_VERTICAL;
        heading.addView(closeButton, cp);
        panel.addView(heading, new LinearLayout.LayoutParams(MATCH_PARENT, px(58)));

        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(10), px(4), px(10));
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFocusable(false);
        scroll.setSmoothScrollingEnabled(true);
        content = vertical();
        scroll.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        LinearLayout channels = section("频道列表", "使用 JSON 配置管理频道，支持导入、导出和定时更新。");
        pathLabel = note("");
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        channels.addView(pathLabel, new LinearLayout.LayoutParams(MATCH_PARENT, px(30)));
        channels.addView(label("频道配置地址"));
        channelUrl = urlInput("https://raw.githubusercontent.com/qq994865664/WebViewTV/main/config/tv-config.json");
        channels.addView(channelUrl, fieldParams());
        remoteHint = note("默认读取 GitHub 仓库配置。清空地址可使用本机列表；更改后请先保存。");
        channels.addView(remoteHint, noteParams());
        channels.addView(label("频道缓存更新间隔"));
        channelInterval = intervalSpinner(CHANNEL_LABELS);
        channels.addView(channelInterval, fieldParams());
        channelStatusLabel = note("");
        channels.addView(channelStatusLabel, noteParams());
        LinearLayout channelActions = horizontal();
        addAction(channelActions, "导入配置", () -> listener.onImportChannels());
        addAction(channelActions, "导出配置", () -> listener.onExportConfig());
        addAction(channelActions, "编辑本机配置", () -> listener.onEditChannels());
        addAction(channelActions, "立即更新", () -> listener.onRefreshChannels());
        channels.addView(channelActions, actionRowParams());
        content.addView(channels, sectionParams());

        LinearLayout epg = section("节目单 · EPG", "连接 XMLTV 节目单后，可查看当前和接下来的节目。");
        epgEnabled = check("启用电视节目单");
        epg.addView(epgEnabled, new LinearLayout.LayoutParams(MATCH_PARENT, px(50)));
        epg.addView(label("XMLTV 节目单地址"));
        epgUrl = urlInput("https://example.com/epg.xml");
        epg.addView(epgUrl, fieldParams());
        epg.addView(note("支持 XML 和 XML.gz；频道名称需与节目单对应。地址更改后请先保存。"), noteParams());
        epg.addView(label("节目单缓存更新间隔"));
        epgInterval = intervalSpinner(EPG_LABELS);
        epg.addView(epgInterval, fieldParams());
        epgStatusLabel = note("");
        epg.addView(epgStatusLabel, noteParams());
        TextView refreshEpg = button("立即更新节目单", () -> listener.onRefreshEpg());
        epg.addView(refreshEpg, actionRowParams());
        content.addView(epg, sectionParams());

        LinearLayout playback = section("播放", "调整切台体验与播放源选择。");
        fastSwitch = check("快速切台");
        playback.addView(fastSwitch, new LinearLayout.LayoutParams(MATCH_PARENT, px(50)));
        playback.addView(note("保留网页缓存并提前准备相邻频道，减少切换等待。"), noteParams());
        rememberSource = check("记住已成功播放的源");
        playback.addView(rememberSource, new LinearLayout.LayoutParams(MATCH_PARENT, px(50)));
        playback.addView(note("下次打开该频道时，优先使用上次成功播放的源。"), noteParams());
        content.addView(playback, sectionParams());

        TextView reset = button("恢复默认设置", () -> listener.onResetDefaults());
        content.addView(reset, actionRowParams());
        TextView footer = note("节目单和频道数据来自配置的地址；首次获取速度取决于网络与源站。");
        content.addView(footer, noteParams());

        LinearLayout bottom = horizontal();
        bottom.setPadding(0, px(10), 0, 0);
        TextView draftHint = text("返回或关闭将放弃未保存的更改", 14, MUTED);
        bottom.addView(draftHint, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        saveButton = button("保存并返回", this::save);
        saveButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        saveButton.setBackground(shape(0xFF245B94, 12, 0xFF79BDFF, 1));
        bottom.addView(saveButton, new LinearLayout.LayoutParams(px(210), MATCH_PARENT));
        panel.addView(bottom, new LinearLayout.LayoutParams(MATCH_PARENT, px(65)));
    }

    public void open(JSONObject config, String configPath, String channelStatus, String epgStatus) {
        if (config == null) config = new JSONObject();
        JSONObject epg = config.optJSONObject("epg");
        JSONObject playback = config.optJSONObject("playback");
        if (epg == null) epg = new JSONObject();
        if (playback == null) playback = new JSONObject();
        channelUrl.setText(config.optString("channelSourceUrl", ""));
        chooseInterval(channelInterval, CHANNEL_HOURS, config.optInt("channelUpdateHours", 24));
        epgEnabled.setChecked(epg.optBoolean("enabled", false));
        epgUrl.setText(epg.optString("url", ""));
        chooseInterval(epgInterval, EPG_HOURS, epg.optInt("refreshHours", 6));
        fastSwitch.setChecked(playback.optBoolean("fastSwitch", true));
        rememberSource.setChecked(playback.optBoolean("rememberSource", true));
        pathLabel.setText("本机配置：" + (configPath == null ? "" : configPath));
        updateStatus(channelStatus, epgStatus);
        opened = true;
        setVisibility(VISIBLE);
        setAlpha(1f);
        bringToFront();
        scroll.scrollTo(0, 0);
        post(() -> { if (opened) closeButton.requestFocusFromTouch(); });
    }

    public void close() {
        opened = false;
        clearFocus();
        setVisibility(GONE);
    }

    public boolean isOpen() { return opened; }

    public void updateStatus(String channels, String epg) {
        if (channels != null) channelStatusLabel.setText(channels);
        if (epg != null) epgStatusLabel.setText(epg);
        remoteHint.setText(channelUrl.getText().toString().trim().isEmpty()
                ? "未填写地址，使用本机频道列表，不进行远程覆盖。"
                : "保存地址后，按缓存间隔下载远程频道配置；导入或手动编辑后可清空地址以保留本机列表。");
    }

    private void save() {
        try {
            JSONObject settings = new JSONObject();
            settings.put("channelSourceUrl", channelUrl.getText().toString().trim());
            settings.put("channelUpdateHours", CHANNEL_HOURS[channelInterval.getSelectedItemPosition()]);
            JSONObject epg = new JSONObject();
            epg.put("enabled", epgEnabled.isChecked());
            epg.put("url", epgUrl.getText().toString().trim());
            epg.put("refreshHours", EPG_HOURS[epgInterval.getSelectedItemPosition()]);
            settings.put("epg", epg);
            JSONObject playback = new JSONObject();
            playback.put("fastSwitch", fastSwitch.isChecked());
            playback.put("rememberSource", rememberSource.isChecked());
            settings.put("playback", playback);
            listener.onSave(settings);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void chooseInterval(Spinner spinner, int[] values, int requested) {
        int selected = 0;
        long difference = Long.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            long distance = Math.abs((long) values[i] - requested);
            if (distance < difference) { difference = distance; selected = i; }
        }
        spinner.setSelection(selected);
    }

    private LinearLayout section(String title, String summary) {
        LinearLayout card = vertical();
        card.setPadding(px(20), px(16), px(20), px(16));
        card.setBackground(shape(CARD, 14, 0xFF2A3E58, 1));
        TextView heading = text(title, 22, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading, new LinearLayout.LayoutParams(MATCH_PARENT, px(30)));
        card.addView(note(summary), noteParams());
        return card;
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

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(getContext());
        result.setText(value);
        result.setTextColor(color);
        result.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setIncludeFontPadding(false);
        return result;
    }

    private TextView note(String value) {
        TextView result = text(value, 14, MUTED);
        result.setLineSpacing(px(3), 1);
        return result;
    }

    private TextView label(String value) {
        TextView result = text(value, 16, INK);
        result.setPadding(0, px(10), 0, px(7));
        return result;
    }

    private EditText urlInput(String hint) {
        EditText input = new EditText(getContext());
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16));
        input.setTextColor(INK);
        input.setHintTextColor(0xFF788EAC);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(px(14), 0, px(14), 0);
        input.setSelectAllOnFocus(false);
        input.setBackground(shape(0xFF0D192B, 10, 0xFF3B506D, 1));
        prepareFocus(input, 10);
        return input;
    }

    private Spinner intervalSpinner(final String[] labels) {
        Spinner spinner = new Spinner(getContext(), Spinner.MODE_DROPDOWN);
        spinner.setPopupBackgroundDrawable(shape(0xFF1B2D45, 10, 0xFF6B8DB5, 1));
        spinner.setDropDownWidth(px(600));
        spinner.setPadding(px(14), 0, px(12), 0);
        spinner.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = recycled instanceof TextView ? (TextView) recycled : text("", 17, INK);
                item.setText(labels[position] + "   ▾");
                item.setSingleLine(true);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, px(48)));
                return item;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                TextView item = recycled instanceof TextView ? (TextView) recycled : text("", 17, INK);
                item.setText(labels[position]);
                item.setPadding(px(18), 0, px(18), 0);
                item.setBackground(optionBackground());
                item.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, px(52)));
                return item;
            }
        });
        prepareFocus(spinner, 10);
        return spinner;
    }

    private CheckBox check(String value) {
        CheckBox item = new CheckBox(getContext());
        item.setText(value);
        item.setTextColor(INK);
        item.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        item.setButtonTintList(ColorStateList.valueOf(ACCENT));
        item.setPadding(px(8), 0, px(12), 0);
        item.setIncludeFontPadding(false);
        prepareFocus(item, 10);
        return item;
    }

    private TextView button(String value, Runnable action) {
        TextView result = text(value, 17, INK);
        result.setGravity(Gravity.CENTER);
        result.setSingleLine(true);
        result.setEllipsize(TextUtils.TruncateAt.END);
        result.setPadding(px(8), 0, px(8), 0);
        prepareFocus(result, 10);
        result.setClickable(true);
        result.setOnClickListener(v -> action.run());
        return result;
    }

    private void addAction(LinearLayout row, String value, Runnable action) {
        TextView button = button(value, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1);
        if (row.getChildCount() > 0) params.leftMargin = px(10);
        row.addView(button, params);
    }

    private void prepareFocus(View view, int radius) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setBackground(shape(0xFF20334C, radius, 0xFF405574, 1));
        view.setOnTouchListener((target, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) target.requestFocusFromTouch();
            return false;
        });
        view.setOnFocusChangeListener((target, hasFocus) -> {
            target.setBackground(shape(hasFocus ? 0xFF285E94 : 0xFF20334C,
                    radius, hasFocus ? ACCENT : 0xFF405574, hasFocus ? 2 : 1));
            if (hasFocus) target.requestRectangleOnScreen(new android.graphics.Rect(0, -px(8), target.getWidth(), target.getHeight() + px(8)), false);
        });
    }

    private StateListDrawable optionBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0xFF285E94, 4, ACCENT, 1));
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xFF285E94, 4, ACCENT, 1));
        states.addState(new int[]{android.R.attr.state_selected}, shape(0xFF285E94, 4, ACCENT, 1));
        states.addState(new int[]{}, shape(0xFF1B2D45, 4, 0xFF1B2D45, 1));
        return states;
    }

    private GradientDrawable shape(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(px(radius));
        drawable.setStroke(px(width), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams fieldParams() {
        return new LinearLayout.LayoutParams(MATCH_PARENT, px(48));
    }

    private LinearLayout.LayoutParams noteParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.topMargin = px(7);
        params.bottomMargin = px(6);
        return params;
    }

    private LinearLayout.LayoutParams actionRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, px(48));
        params.topMargin = px(10);
        return params;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.bottomMargin = px(14);
        return params;
    }

    private int px(int value) { return Math.max(1, Math.round(value * scale)); }
}
