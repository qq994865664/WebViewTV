import com.vasthread.webviewtv.reconstructed.PlaybackEstimate;
public final class PlaybackEstimateTest {
    static int checks;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks++;}
    public static void main(String[] args){
        PlaybackEstimate p=new PlaybackEstimate();p.start(0,8000);
        check("time alone is not progress",p.value(50000)==0);
        p.page(50);check("page weight",p.value(50000)==15);
        p.page(100);check("page is not video",p.value(50000)==30);
        p.media(1,50000);check("element gate",p.value(90000)==59);
        p.media(2,90000);check("metadata gate",p.value(150000)==74);
        p.media(3,150000);check("decoded data gate",p.value(180000)==89);
        p.media(4,180000);check("never finishes without visible frame",p.value(190000)==95);
        p.media(1,190000);check("monotonic",p.value(190001)==95);
        p.start(200000,0);p.media(2,200000);check("cold history uses evidence",p.value(250000)==60);
        p.start(0,8000);p.media(2,1000);p.pause(1200);int frozen=p.value(1200);
        check("pause freezes",p.value(50000)==frozen);p.resume(50000);
        check("elapsed excludes pause",p.elapsed(50000)==1200);check("resume preserves estimate",p.value(50000)==frozen);
        check("invalid history",PlaybackEstimate.median("bad,-3,90000,0")==0);
        check("robust median",PlaybackEstimate.median("3000,4000,50000")==4000);
        String h="";for(int i=1;i<=12;i++)h=PlaybackEstimate.remember(h,i*1000);
        check("eight samples",h.split(",").length==8&&h.startsWith("5000,"));
        check("invalid attempt excluded",PlaybackEstimate.remember(h,0).equals(h));
        System.out.println("PASS "+checks+" startup estimate assertions");
    }
}
