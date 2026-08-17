package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class OnlineAgent {
  static final String NAME = "GPT";
  static final long END = System.currentTimeMillis() + 90L*60_000L;
  static void log(String s){System.out.println("[GPT-ONLINE] "+s);System.out.flush();}
  static void nap(long n){try{Thread.sleep(n);}catch(Exception e){}}

  public static void premain(String a, Instrumentation i){
    Thread t=new Thread(()->{run();log("Agent finished; closing Graphwar.");nap(3000);System.exit(0);},"GPT-Official-Agent");
    t.setDaemon(false); t.start();
  }

  static Graphwar find(){
    for(int n=0;n<400;n++){
      for(Frame f:Frame.getFrames()) if(f instanceof Graphwar) return (Graphwar)f;
      nap(100);
    }
    return null;
  }

  static int alive(Player p){
    int n=0; if(p==null)return 0;
    for(Soldier s:p.getSoldiers()) if(s!=null&&s.isAlive())n++;
    return n;
  }

  static int remotePlayers(GameData gd){
    int n=0;
    for(Player p:new ArrayList<>(gd.getPlayers())) if(!p.isLocalPlayer()) n++;
    return n;
  }

  static int remoteAlive(GameData gd){
    int n=0;
    for(Player p:new ArrayList<>(gd.getPlayers())) if(!p.isLocalPlayer()) n+=alive(p);
    return n;
  }

  static int localAlive(GameData gd){
    int n=0;
    for(Player p:new ArrayList<>(gd.getPlayers())) if(p.isLocalPlayer()) n+=alive(p);
    return n;
  }

  static void leave(GameData gd){
    try{ if(gd.getGameState()==0) gd.stopGame(); else gd.disconnect(); }
    catch(Throwable x){ try{gd.stopGame();}catch(Throwable y){} }
    nap(900);
  }

  static List<Room> rooms(List<Room> all, Map<String,Long> cd){
    long now=System.currentTimeMillis();
    List<Room> out=new ArrayList<>();
    for(Room r:new ArrayList<>(all)){
      // Require at least two occupants before we join; avoid host-only ghost rooms.
      if(r.getNumPlayers()<2 || r.getNumPlayers()>=10) continue;
      if(r.getGameMode()<0 || r.getGameMode()>2) continue;
      if(!r.getName().startsWith("Public Room")) continue;
      String k=r.getIp()+":"+r.getPort();
      if(cd.getOrDefault(k,0L)<=now) out.add(r);
    }
    out.sort(Comparator.comparingInt(Room::getNumPlayers).reversed().thenComparingInt(Room::getGameMode));
    log("CANDIDATES="+out.size()+" prefer=near-full");
    for(Room r:out) log("CAND "+r.getName()+" p="+r.getNumPlayers()+" mode="+r.getGameMode()+" "+r.getIp()+":"+r.getPort());
    return out;
  }

  static long waitForStart(Room r){
    int p=r.getNumPlayers();
    if(p>=8)return 150_000L;
    if(p>=6)return 120_000L;
    if(p>=4)return 90_000L;
    return 60_000L;
  }

  static boolean play(GameData gd){
    if(remotePlayers(gd)==0){ log("ABORT_SOLO_GAME"); return false; }
    boolean sawOpponent = true;
    int lp=-1,ls=-1; long last=0;
    log("GAME_STARTED mode="+gd.getGameMode()+" players="+gd.getPlayers().size()+" remote="+remotePlayers(gd));
    AutoAgent.dump(gd.getPlayers());

    while(System.currentTimeMillis()<END){
      int st=gd.getGameState();
      if(st!=2){
        int la=localAlive(gd), ra=remoteAlive(gd);
        log("GAME_ENDED state="+st+" localAlive="+la+" remoteAlive="+ra+" sawOpponent="+sawOpponent);
        AutoAgent.dump(gd.getPlayers());
        return sawOpponent && la>0 && ra==0;
      }

      if(remotePlayers(gd)>0) sawOpponent=true;
      Player cur=gd.getCurrentTurnPlayer();
      if(cur!=null && cur.isLocalPlayer() && !gd.isDrawingFunction() && !gd.isExploding()){
        int s=cur.getCurrentTurnSoldierIndex();
        if(cur.getID()!=lp || s!=ls || System.currentTimeMillis()-last>9000){
          String f=AutoAgent.aim(gd,cur);
          if(AutoAgent.LAST_HAS_ANGLE){
            gd.setAngle(AutoAgent.LAST_ANGLE); nap(120);
            log("ANGLE "+AutoAgent.fmt(AutoAgent.LAST_ANGLE));
          }
          log("FIRE "+f);
          gd.sendFunction(f);
          lp=cur.getID(); ls=s; last=System.currentTimeMillis();
          nap(1000);
        }
      }
      nap(100);
    }
    log("SESSION_LIMIT_DURING_GAME");
    return false;
  }

  static void run(){
    try{
      Graphwar g=find(); if(g==null){log("ERROR no Graphwar frame");return;}
      long d=System.currentTimeMillis()+20_000L;
      while(System.currentTimeMillis()<d && (g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null))nap(100);
      if(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null){log("ERROR init timeout");return;}

      g.joinGlobal(NAME); log("JOIN_GLOBAL "+NAME);
      GlobalClient gc=g.getGlobalClient();
      d=System.currentTimeMillis()+25_000L;
      while(System.currentTimeMillis()<d && gc.getRooms().isEmpty())nap(100);
      log("ROOMS="+gc.getRooms().size());

      GameData gd=g.getGameData();
      Map<String,Long> cooldown=new HashMap<>();
      int attempt=0,matches=0,wins=0,losses=0;

      while(System.currentTimeMillis()<END){
        List<Room> list=rooms(gc.getRooms(),cooldown);
        if(list.isEmpty()){nap(5000);continue;}

        for(Room r:list){
          if(System.currentTimeMillis()>=END)break;
          attempt++;
          String key=r.getIp()+":"+r.getPort();
          log("TRY_ROOM #"+attempt+" "+r.getName()+" p="+r.getNumPlayers()+" mode="+r.getGameMode());
          Player me=null;
          try{
            g.joinGame(r.getIp(),r.getPort());
            gd.addPlayer(gc.getLocalPlayerName());
            g.getUI().setScreen(1);
            d=System.currentTimeMillis()+8000L;
            while(System.currentTimeMillis()<d){
              me=gd.getFirstLocalPlayer();
              if(me!=null)break;
              if(gd.getGameState()==0)break;
              nap(100);
            }
          }catch(Throwable x){log("ROOM_CONNECT_ERROR "+x);}

          if(me==null){
            log("ROOM_REJECTED "+key);
            cooldown.put(key,System.currentTimeMillis()+60_000L);
            leave(gd); continue;
          }

          // Give room state a moment to settle; require a real opponent.
          d=System.currentTimeMillis()+5000L;
          while(System.currentTimeMillis()<d && remotePlayers(gd)==0 && gd.getGameState()!=0)nap(100);
          if(remotePlayers(gd)==0){
            log("NO_REMOTE_PLAYER_AFTER_JOIN; leave "+r.getName());
            cooldown.put(key,System.currentTimeMillis()+90_000L);
            leave(gd); continue;
          }

          log("JOINED_ROOM "+r.getName()+" remotePlayers="+remotePlayers(gd)+" team="+me.getTeam()+" soldiers="+me.getNumSoldiers());
          Player other=AutoAgent.other(gd.getPlayers());
          if(other!=null && other.getTeam()==me.getTeam()){
            gd.switchSide(me); log("SWITCH_SIDE vs "+other.getName()); nap(800);
          }
          gd.setReady(me,true); log("READY");

          long wd=Math.min(END,System.currentTimeMillis()+waitForStart(r));
          while(System.currentTimeMillis()<wd && gd.getGameState()!=2 && gd.getGameState()!=0){
            if(remotePlayers(gd)==0)break;
            nap(100);
          }

          if(gd.getGameState()==2 && remotePlayers(gd)>0){
            boolean win=play(gd);
            matches++;
            if(win){wins++; log("MATCH_RESULT #"+matches+" WIN score="+wins+"-"+losses); log("WIN_REACHED"); return;}
            losses++; log("MATCH_RESULT #"+matches+" LOSS/UNKNOWN score="+wins+"-"+losses);
            cooldown.put(key,System.currentTimeMillis()+45_000L);
            leave(gd); break;
          }

          log("NO_START_OR_OPPONENT_LEFT; switching room");
          cooldown.put(key,System.currentTimeMillis()+90_000L);
          leave(gd);
        }
      }
      log("SESSION_SUMMARY matches="+matches+" wins="+wins+" losses="+losses);
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }
}
