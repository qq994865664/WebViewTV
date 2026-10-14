package com.vasthread.webviewtv.reconstructed;

/** Estimated startup readiness. Evidence gates every range; timeout is independent. */
public final class PlaybackEstimate {
    private long started, stageStarted, pausedAt = -1, typical;
    private int page, stage, shown;
    public void start(long now, long median) {
        started = stageStarted = now; pausedAt = -1;
        typical = median; page = stage = shown = 0;
    }
    public void page(int progress) { page = Math.max(page, Math.max(0, Math.min(100, progress))); }
    public void media(int evidence, long now) {
        if (evidence > stage && evidence <= 4) { stage = evidence; stageStarted = clock(now); }
    }
    public void pause(long now) { if (pausedAt < 0) pausedAt = now; }
    public void resume(long now) {
        if (pausedAt >= 0) { long delta=Math.max(0,now-pausedAt); started+=delta; stageStarted+=delta; pausedAt=-1; }
    }
    private long clock(long now) { return pausedAt < 0 ? now : pausedAt; }
    public long elapsed(long now) { return Math.max(0,clock(now)-started); }
    public int value(long now) {
        int[] bases={0,30,60,75,90}, caps={30,59,74,89,95};
        int value=stage==0 ? page*30/100 : bases[stage];
        if(stage>0 && typical>0) {
            long duration=Math.max(500,typical*(caps[stage]-bases[stage])/100);
            value+=(int)Math.min(caps[stage]-bases[stage],Math.max(0,clock(now)-stageStarted)*(caps[stage]-bases[stage])/duration);
        }
        shown=Math.max(shown,Math.min(95,value));
        return shown;
    }
    public static long median(String history) {
        long[] values=read(history); if(values.length==0)return 0;
        java.util.Arrays.sort(values); return values[values.length/2];
    }
    public static String remember(String history,long millis) {
        if(millis<=0 || millis>60000)return history;
        long[] values=read(history); StringBuilder result=new StringBuilder();
        for(int i=Math.max(0,values.length-7);i<values.length;i++)result.append(values[i]).append(',');
        return result.append(millis).toString();
    }
    private static long[] read(String history) {
        java.util.ArrayList<Long> valid=new java.util.ArrayList<>();
        for(String item:history.split(","))try{long n=Long.parseLong(item);if(n>0&&n<=60000)valid.add(n);}catch(NumberFormatException ignored){}
        int count=Math.min(8,valid.size());long[] result=new long[count];
        for(int i=0;i<count;i++)result[i]=valid.get(valid.size()-count+i);
        return result;
    }
}
