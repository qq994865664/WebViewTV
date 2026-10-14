package com.vasthread.webviewtv.reconstructed;

import java.util.ArrayList;
import java.util.Arrays;

/** Startup estimate bounded by observed media state; recovery deadlines are separate. */
public final class PlaybackEstimate {
    public static final long IDLE_MILLIS = 3000;
    private static final String HISTORY_VERSION = "P1";
    private static final int[] BASES = {0,30,60,75,90}, CAPS = {30,59,74,89,95};
    private long started, stageStarted, lastSignal, pausedAt = -1;
    private long webpage = -1, player = -1, data = -1, maxBuffer, maxBytes;
    private long[] typical = new long[4];
    private int page, stage, shown;

    public void start(long now, String history) {
        started = stageStarted = lastSignal = now; pausedAt = -1;
        webpage = player = data = -1; maxBuffer = maxBytes = 0;
        page = stage = shown = 0;
        ArrayList<long[]> records = read(history);
        typical = new long[4];
        for(int phase=0;phase<4;phase++) {
            long[] values = new long[records.size()]; int count=0;
            for(long[] record:records) if(record[phase]>=0) values[count++]=record[phase];
            if(count>0) {
                Arrays.sort(values,0,count);
                typical[phase]=count%2==0 ? (values[count/2-1]+values[count/2])/2 : values[count/2];
            }
        }
    }

    public void page(int progress,long now) {
        if(pausedAt>=0)return;
        int next=Math.max(0,Math.min(100,progress));
        if(next>page) { page=next;lastSignal=now; }
        if(page==100 && webpage<0)webpage=elapsed(now);
    }

    public void media(int evidence,long now) {
        if(pausedAt>=0 || evidence<1 || evidence>4)return;
        if(player<0)player=elapsed(now);
        if(evidence>=3 && data<0)data=elapsed(now);
        if(evidence>stage) { stage=evidence;stageStarted=lastSignal=now; }
    }

    /** Identical buffers and repeated completed-request callbacks are not new progress. */
    public void buffer(long aheadMillis,long now) {
        if(pausedAt<0 && aheadMillis>maxBuffer) { maxBuffer=aheadMillis;lastSignal=now; }
    }
    public void bytes(long cumulative,long now) {
        if(pausedAt<0 && cumulative>maxBytes) { maxBytes=cumulative;lastSignal=now; }
    }
    public boolean uncertain(long now) { return clock(now)-lastSignal>=IDLE_MILLIS; }
    public String waitingLabel() { return stage<3 ? "正在连接直播源…" : "正在等待直播画面…"; }

    public void pause(long now) { if(pausedAt<0)pausedAt=now; }
    public void resume(long now) {
        if(pausedAt>=0) {
            long delta=Math.max(0,now-pausedAt);
            started+=delta;stageStarted+=delta;lastSignal+=delta;pausedAt=-1;
        }
    }
    private long clock(long now) { return pausedAt<0 ? now : pausedAt; }
    public long elapsed(long now) { return Math.max(0,clock(now)-started); }

    public int value(long now) {
        int value=stage==0 ? page*30/100 : BASES[stage];
        // Use this source's data/frame phase, rather than a fraction of total startup time.
        long expected=stage<3 ? typical[2] : typical[3];
        if(stage>0 && expected>0 && !uncertain(now)) {
            long duration=Math.max(200,expected/2);
            value+=(int)Math.min(CAPS[stage]-BASES[stage],
                    Math.max(0,clock(now)-stageStarted)*(CAPS[stage]-BASES[stage])/duration);
        }
        shown=Math.max(shown,Math.min(95,value));
        return shown;
    }

    /** Caller records only after an actual frame has been exposed to the viewer. */
    public String rememberPhases(String history,long now) {
        long total=elapsed(now);
        if(pausedAt>=0 || total<=0 || total>60000)return history;
        long[] record={webpage,
                player<0 || webpage<0 ? -1 : Math.max(0,player-webpage),
                data<0 || player<0 ? -1 : Math.max(0,data-player),
                data<0 ? -1 : Math.max(0,total-data)};
        if(webpage<0 && player<0 && data<0)return history;
        ArrayList<long[]> records=read(history);
        if(records.size()==8)records.remove(0);
        records.add(record);
        StringBuilder result=new StringBuilder(HISTORY_VERSION);
        for(long[] sample:records) {
            result.append('|');
            for(int i=0;i<4;i++) { if(i>0)result.append(',');result.append(sample[i]); }
        }
        return result.toString();
    }

    private static ArrayList<long[]> read(String history) {
        ArrayList<long[]> records=new ArrayList<>();
        if(history==null || !history.startsWith(HISTORY_VERSION+"|"))return records;
        String[] rows=history.split("\\|");
        for(int i=Math.max(1,rows.length-8);i<rows.length;i++) {
            String[] items=rows[i].split(",");if(items.length!=4)continue;
            long[] record=new long[4];boolean valid=true;
            for(int j=0;j<4;j++)try {
                record[j]=Long.parseLong(items[j]);
                if(record[j]<-1 || record[j]>60000)valid=false;
            }catch(NumberFormatException ignored){valid=false;}
            if(valid)records.add(record);
        }
        return records;
    }
}
