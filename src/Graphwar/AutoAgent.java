package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class AutoAgent {
  static final String NAME="GPT";
  static final long END=System.currentTimeMillis()+7*60_000L;
  static final Random R=new Random(0x475054L);
  static void log(String s){System.out.println("[GPT-BOT] "+s);System.out.flush();}
  static void nap(long n){try{Thread.sleep(n);}catch(Exception e){}}

  public static void premain(String a, Instrumentation i){
    Thread t=new Thread(()->{run();log("Agent finished; closing Graphwar.");nap(3500);System.exit(0);},"GPT-Agent");
    t.setDaemon(false);t.start();
  }

  static Graphwar game(){
    for(int n=0;n<300;n++){for(Frame f:Frame.getFrames())if(f instanceof Graphwar)return (Graphwar)f;nap(100);}
    return null;
  }

  static void run(){
    try{
      Graphwar g=game(); if(g==null){log("ERROR no Graphwar frame");return;}
      log("Graphwar frame found");
      long d=System.currentTimeMillis()+15000;
      while(System.currentTimeMillis()<d&&(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null))nap(100);
      if(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null){log("ERROR init timeout");return;}
      log("Subsystems ready");

      g.joinGlobal(NAME); log("JOIN_GLOBAL "+NAME);
      GlobalClient gc=g.getGlobalClient();
      d=System.currentTimeMillis()+20000;
      while(System.currentTimeMillis()<d&&gc.getRooms().isEmpty())nap(100);
      log("ROOMS="+gc.getRooms().size());
      Room r=pick(gc.getRooms());
      while(r==null&&System.currentTimeMillis()<END){nap(2000);r=pick(gc.getRooms());}
      if(r==null){log("No occupied normal Public Room");return;}
      log("JOIN_ROOM "+r.getName()+" p="+r.getNumPlayers()+" "+r.getIp()+":"+r.getPort());

      g.joinGame(r.getIp(),r.getPort());
      GameData gd=g.getGameData();
      gd.addPlayer(gc.getLocalPlayerName()); g.getUI().setScreen(1);
      Player me=null; d=System.currentTimeMillis()+15000;
      while(System.currentTimeMillis()<d&&(me=gd.getFirstLocalPlayer())==null)nap(100);
      if(me==null){log("ERROR local player not added");return;}
      log("LOCAL id="+me.getID()+" team="+me.getTeam()+" soldiers="+me.getNumSoldiers());

      Player o=other(gd.getPlayers());
      if(o!=null&&o.getTeam()==me.getTeam()){gd.switchSide(me);log("SWITCH_SIDE vs "+o.getName());nap(800);}
      gd.setReady(me,true);log("READY");

      boolean started=false; int lastP=-1,lastS=-1; long last=0;
      while(System.currentTimeMillis()<END){
        int st=gd.getGameState();
        if(st==2){
          if(!started){started=true;g.getUI().setScreen(3);log("GAME_STARTED players="+gd.getPlayers().size());dump(gd.getPlayers());}
          Player cur=gd.getCurrentTurnPlayer();
          if(cur!=null&&cur.isLocalPlayer()&&!gd.isDrawingFunction()&&!gd.isExploding()){
            int s=cur.getCurrentTurnSoldierIndex();
            if(cur.getID()!=lastP||s!=lastS||System.currentTimeMillis()-last>8000){
              String f=aim(gd,cur);log("FIRE "+f);gd.sendFunction(f);
              lastP=cur.getID();lastS=s;last=System.currentTimeMillis();nap(1200);
            }
          }
        }else if(started){log("GAME_ENDED state="+st);dump(gd.getPlayers());nap(5000);return;}
        nap(100);
      }
      log("TIME_LIMIT");
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }

  static Room pick(List<Room> rs){
    Room b=null;int bs=-999;
    for(Room r:new ArrayList<>(rs)){
      if(r.getGameMode()!=0||r.getNumPlayers()<1||r.getNumPlayers()>=9||!r.getName().startsWith("Public Room"))continue;
      int s=100-Math.abs(r.getNumPlayers()-1)*10;
      if(s>bs){bs=s;b=r;}
    }
    return b;
  }
  static Player other(List<Player> ps){for(Player p:new ArrayList<>(ps))if(!p.isLocalPlayer())return p;return null;}
  static void dump(List<Player> ps){
    for(Player p:new ArrayList<>(ps)){int a=0;for(Soldier s:p.getSoldiers())if(s!=null&&s.isAlive())a++;
      log("PLAYER "+p.getName()+" id="+p.getID()+" local="+p.isLocalPlayer()+" team="+p.getTeam()+" alive="+a+"/"+p.getNumSoldiers());}
  }

  static String aim(GameData gd,Player me){
    if(gd.getGameMode()!=0)return "0";
    Player[] ps=gd.getPlayers().toArray(new Player[0]);int ci=-1;
    for(int i=0;i<ps.length;i++)if(ps[i]==me){ci=i;break;}
    Soldier sh=me.getCurrentTurnSoldier();if(ci<0||sh==null)return "0";
    Candidate best=null;
    for(int pi=0;pi<ps.length;pi++)if(ps[pi].getTeam()!=me.getTeam())
      for(int si=0;si<ps[pi].getNumSoldiers();si++){
        Soldier tg=ps[pi].getSoldiers()[si];if(tg==null||!tg.isAlive())continue;
        Candidate c=search(gd,ps,ci,me,sh,pi,si,tg);
        if(c!=null&&(best==null||c.score>best.score))best=c;
        if(best!=null&&best.score>9000)return best.f;
      }
    if(best!=null){log("BEST score="+best.score+" tested="+best.n);return best.f;}
    return fallback(ps,me,sh,gd.isTerrainReversed());
  }

  static Candidate search(GameData gd,Player[] ps,int ci,Player me,Soldier sh,int tpi,int tsi,Soldier tg){
    boolean inv=gd.isTerrainReversed();double sx=gx(sh.getX(),inv),sy=gy(sh.getY()),tx=gx(tg.getX(),inv),ty=gy(tg.getY());
    if(Math.abs(tx-sx)<.05)return null;Candidate best=null;int n=0;
    for(int qi=-100;qi<=100;qi++){double q=qi*.005,m0=solve(sx,sy,tx,ty,q,0,0);
      for(int di=-6;di<=6;di++){Candidate x=eval(gd,ps,ci,me,tpi,tsi,poly(m0+di*.025,q,0,0),++n);
        if(x!=null&&(best==null||x.score>best.score))best=x;if(x!=null&&x.hit&&x.friendly==0)return x;}}
    for(int i=0;i<3500;i++){double q=rr(-.65,.65),c=rr(-.045,.045),d=rr(-.0018,.0018);
      double m=solve(sx,sy,tx,ty,q,c,d)+rr(-.18,.18);if(!Double.isFinite(m)||Math.abs(m)>20)continue;
      Candidate x=eval(gd,ps,ci,me,tpi,tsi,poly(m,q,c,d),++n);
      if(x!=null&&(best==null||x.score>best.score))best=x;if(x!=null&&x.hit&&x.friendly==0)return x;}
    if(best!=null)best.n=n;return best;
  }

  static Candidate eval(GameData gd,Player[] ps,int ci,Player me,int tpi,int tsi,String s,int n){
    try{
      Function f=new Function(s);f.processFunctionRange(gd.getObstacle(),ps,ps.length,ci,gd.isTerrainReversed());
      int e=0,fr=0;boolean hit=false;
      for(int i=0;i<f.getNumPlayersHit();i++){int p=f.getPlayerHit(i),q=f.getSoldierHit(i);if(p<0||p>=ps.length)continue;
        if(ps[p].getTeam()==me.getTeam())fr++;else e++;if(p==tpi&&q==tsi)hit=true;}
      if(e==0&&!hit)return null;return new Candidate(s,e*1000-fr*1600+(hit?8000:0)-s.length(),hit,fr,n);
    }catch(Throwable x){return null;}
  }

  static String fallback(Player[] ps,Player me,Soldier sh,boolean inv){
    Soldier b=null;double bd=1e99;
    for(Player p:ps)if(p.getTeam()!=me.getTeam())for(Soldier s:p.getSoldiers())if(s!=null&&s.isAlive()){
      double x=s.getX()-sh.getX(),y=s.getY()-sh.getY(),d=x*x+y*y;if(d<bd){bd=d;b=s;}}
    if(b==null)return "0";double dx=gx(b.getX(),inv)-gx(sh.getX(),inv);
    return Math.abs(dx)<1e-6?"0":fmt((gy(b.getY())-gy(sh.getY()))/dx)+"*x";
  }
  static double gx(int x,boolean inv){double z=inv?770.0-x:x;return 50*(z-385)/770;}
  static double gy(int y){return 50*(-y+225)/770;}
  static double solve(double sx,double sy,double tx,double ty,double q,double c,double d){
    return ((ty-sy)-q*(tx*tx-sx*sx)-c*(tx*tx*tx-sx*sx*sx)-d*(Math.pow(tx,4)-Math.pow(sx,4)))/(tx-sx);
  }
  static String poly(double m,double q,double c,double d){
    String s=fmt(m)+"*x";if(Math.abs(q)>1e-12)s+=(q>=0?"+":"")+fmt(q)+"*x^2";
    if(Math.abs(c)>1e-12)s+=(c>=0?"+":"")+fmt(c)+"*x^3";if(Math.abs(d)>1e-12)s+=(d>=0?"+":"")+fmt(d)+"*x^4";return s;
  }
  static String fmt(double v){if(Math.abs(v)<5e-7)v=0;return String.format(Locale.US,"%.7f",v).replaceAll("0+$","").replaceAll("\\.$","");}
  static double rr(double a,double b){return a+R.nextDouble()*(b-a);}
  static class Candidate{String f;int score,n,friendly;boolean hit;Candidate(String f,int s,boolean h,int fr,int n){this.f=f;score=s;hit=h;friendly=fr;this.n=n;}}
}
