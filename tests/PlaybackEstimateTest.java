import com.vasthread.webviewtv.reconstructed.PlaybackEstimate;

public final class PlaybackEstimateTest {
    static int checks;
    static void check(String label,boolean ok){if(!ok)throw new AssertionError(label);checks++;}
    public static void main(String[] args){
        PlaybackEstimate p=new PlaybackEstimate();
        String profile="P1|1000,500,2000,500";
        p.start(0,profile);
        check("elapsed alone cannot create readiness",p.value(1000)==0);
        p.page(50,1000);check("actual page progress is weighted",p.value(1000)==15);
        p.page(100,1200);check("page completion is not video",p.value(1200)==30);
        p.media(1,1500);check("player creates media stage",p.value(1500)==30);
        check("phase duration interpolates within bounds",p.value(2000)>30&&p.value(2000)<60);
        p.media(1,4000);
        check("unchanged stage cannot keep estimate alive",p.uncertain(4500));
        p.bytes(100,4600);check("actual new bytes restore observability",!p.uncertain(4600));
        check("bytes cannot imply metadata",p.value(4600)<60);
        p.bytes(100,7000);check("repeated cumulative bytes do not imply activity",p.uncertain(7600));
        p.bytes(200,8000);check("new bytes restore estimate",!p.uncertain(8000));
        p.buffer(1000,8100);check("increased media buffer is activity",!p.uncertain(8100));
        p.buffer(1000,11000);check("duplicate buffer does not imply activity",p.uncertain(11100));
        p.buffer(500,11500);check("buffer decrease does not imply activity",p.uncertain(11500));
        p.media(3,12000);check("decoded data advances evidence",p.value(12000)==75);
        p.media(4,13000);check("buffer never finishes without real frame",p.value(13250)==95);
        p.media(2,13400);check("estimate stays monotonic",p.value(13400)==95);
        check("frame waiting is distinguished",p.waitingLabel().contains("直播画面"));
        p.start(0,"");p.page(100,1000);p.media(1,1500);p.media(3,3500);
        String history=p.rememberPhases("",4000);
        check("four real phase timings stored",profile.equals(history));
        p.start(0,history);p.media(1,1000);
        check("new estimate uses source data phase",p.value(1500)==44);
        p.start(0,"P1|1000,500,2000,500|2000,2000,50000,500|500,100,1500,500");p.media(1,1000);
        check("phase median resists outlier",p.value(1500)==44);
        p.start(0,"3000,4000,50000");p.media(2,1000);
        check("old overall history cannot masquerade as phase data",p.value(2000)==60);
        p.start(0,"P1|bad,500,2000,500|1000,-5,2000,500|1000,500,80000,500");p.media(1,1000);
        check("invalid phase histories ignored",p.value(2000)==30);
        p.start(10000,profile);p.page(100,11000);p.pause(12000);
        check("idle excludes paused time",!p.uncertain(40000));
        check("elapsed excludes paused time",p.elapsed(40000)==2000);
        p.bytes(999,40000);p.media(4,40000);p.resume(42000);
        check("paused signals cannot advance evidence",p.value(42000)==30);
        check("resume preserves idle remainder",!p.uncertain(43999)&&p.uncertain(44000));
        String h="";
        for(int i=0;i<12;i++){p.start(0,"");p.page(100,1000+i);p.media(1,2000+i);p.media(3,3000+i);h=p.rememberPhases(h,4000+i);}
        check("history retains eight successful samples",h.split("\\|").length==9);
        p.start(0,h);check("attempt without milestones cannot be learned",p.rememberPhases(h,1000).equals(h));
        p.page(100,1000);p.pause(1500);check("paused completion cannot pollute samples",p.rememberPhases(h,60000).equals(h));
        System.out.println("PASS "+checks+" startup estimate assertions");
    }
}
