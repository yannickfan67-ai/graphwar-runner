package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class AutoAgentV2 {
  static final String NAME="GPT";
  static final long END=System.currentTimeMillis()+5L*60L*60_000L;
  static void log(String s){System.out.println("[GPT-BOT] "+s);System.out.flush();}
  static void nap(long n){try{Thread.sleep(n);}catch(Exception e){}}

  public static void premain(String a, Instrumentation i){
    Thread t=new Thread(()->{run();log("Agent finished; closing Graphwar.");nap(3500);System.exit(0);},"GPT-Agent-V2");
    t.setDaemon(false);t.start();
  }

  static Graphwar find(){
    for(int n=0;n<300;n++){for(Frame f:Frame.getFrames())if(f instanceof Graphwar)return (Graphwar)f;nap(100);}
    return null;
  }

  static long startWait(Room r){
    int p=r.getNumPlayers();
    if(p>=8)return 120_000L;
    if(p>=6)return 90_000L;
    if(p>=4)return 60_000L;
    if(p>=2)return 45_000L;
    return 25_000L;
  }

  static long rejectCooldown(Room r){return r.getNumPlayers()>=6?90_000L:45_000L;}

  static List<Room> rooms(List<Room> all,Map<String,Long> cooldown){
    long now=System.currentTimeMillis();
    List<Room> out=new ArrayList<>();
    for(Room r:new ArrayList<>(all)){
      if(r.getGameMode()<0||r.getGameMode()>2||r.getNumPlayers()<1||r.getNumPlayers()>=10||!r.getName().startsWith("Public Room"))continue;
      String k=r.getIp()+":"+r.getPort();
      if(cooldown.getOrDefault(k,0L)<=now)out.add(r);
    }
    out.sort(Comparator.comparingInt(Room::getNumPlayers).reversed().thenComparingInt(Room::getGameMode));
    log("CANDIDATES="+out.size()+" modes=all prefer=near-full");
    for(Room r:out)log("CAND "+r.getName()+" mode="+r.getGameMode()+" p="+r.getNumPlayers()+" wait="+(startWait(r)/1000)+"s "+r.getIp()+":"+r.getPort());
    return out;
  }

  static void leave(GameData gd){
    try{if(gd.getGameState()==0)gd.stopGame();else gd.disconnect();}
    catch(Throwable x){try{gd.stopGame();}catch(Throwable y){}}
    nap(800);
  }

  static int alive(Player p){
    int n=0;if(p==null)return 0;
    for(Soldier s:p.getSoldiers())if(s!=null&&s.isAlive())n++;
    return n;
  }

  static int outcome(GameData gd){
    int ours=0,theirs=0;
    for(Player p:new ArrayList<>(gd.getPlayers())){
      if(p.isLocalPlayer())ours+=alive(p); else theirs+=alive(p);
    }
    if(ours>0&&theirs==0)return 1;
    if(ours==0&&theirs>0)return -1;
    return 0;
  }

  static boolean play(GameData gd){
    boolean started=false;int lp=-1,ls=-1;long last=0;
    while(System.currentTimeMillis()<END){
      int st=gd.getGameState();
      if(st==2){
        if(!started){
          started=true;
          log("GAME_STARTED mode="+gd.getGameMode()+" players="+gd.getPlayers().size());
          AutoAgent.dump(gd.getPlayers());
        }
        Player cur=gd.getCurrentTurnPlayer();
        if(cur!=null&&cur.isLocalPlayer()&&!gd.isDrawingFunction()&&!gd.isExploding()){
          int s=cur.getCurrentTurnSoldierIndex();
          if(cur.getID()!=lp||s!=ls||System.currentTimeMillis()-last>8000){
            String f=AutoAgent.aim(gd,cur);
            if(AutoAgent.LAST_HAS_ANGLE){
              gd.setAngle(AutoAgent.LAST_ANGLE);nap(120);
              log("ANGLE "+AutoAgent.fmt(AutoAgent.LAST_ANGLE));
            }
            log("FIRE "+f);gd.sendFunction(f);
            lp=cur.getID();ls=s;last=System.currentTimeMillis();nap(1200);
          }
        }
      }else if(started){
        log("GAME_ENDED state="+st);AutoAgent.dump(gd.getPlayers());nap(3500);return true;
      }else if(st==0){
        log("DISCONNECTED_IN_GAME_WAIT");return false;
      }
      nap(100);
    }
    log("TIME_LIMIT_DURING_GAME");return false;
  }

  static void run(){
    try{
      Graphwar g=find();if(g==null){log("ERROR no Graphwar frame");return;}
      log("Graphwar frame found");
      long d=System.currentTimeMillis()+15000;
      while(System.currentTimeMillis()<d&&(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null))nap(100);
      if(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null){log("ERROR init timeout");return;}

      g.joinGlobal(NAME);log("JOIN_GLOBAL "+NAME);
      GlobalClient gc=g.getGlobalClient();
      d=System.currentTimeMillis()+20000;
      while(System.currentTimeMillis()<d&&gc.getRooms().isEmpty())nap(100);
      log("ROOMS="+gc.getRooms().size());

      GameData gd=g.getGameData();Map<String,Long> cooldown=new HashMap<>();
      int attempt=0,matches=0,wins=0,losses=0;

      while(System.currentTimeMillis()<END){
        List<Room> list=rooms(gc.getRooms(),cooldown);
        if(list.isEmpty()){log("No usable occupied Public Room; refresh 5s");nap(5000);continue;}

        boolean triedOne=false,finishedMatch=false;
        for(Room r:list){
          if(System.currentTimeMillis()>=END)break;
          triedOne=true;attempt++;
          String key=r.getIp()+":"+r.getPort();
          log("TRY_ROOM #"+attempt+" "+r.getName()+" mode="+r.getGameMode()+" p="+r.getNumPlayers()+" "+key);

          Player me=null;
          try{
            g.joinGame(r.getIp(),r.getPort());
            gd.addPlayer(gc.getLocalPlayerName());
            g.getUI().setScreen(1);
            d=System.currentTimeMillis()+6500;
            while(System.currentTimeMillis()<d){
              me=gd.getFirstLocalPlayer();
              if(me!=null)break;
              if(gd.getGameState()==0)break;
              nap(100);
            }
          }catch(Throwable x){log("ROOM_CONNECT_ERROR "+key+" "+x);}

          if(me==null){
            log("ROOM_REJECTED "+key+" state="+gd.getGameState());
            cooldown.put(key,System.currentTimeMillis()+rejectCooldown(r));
            leave(gd);continue;
          }

          log("JOINED_ROOM "+r.getName()+" mode="+gd.getGameMode()+" id="+me.getID()+" team="+me.getTeam()+" soldiers="+me.getNumSoldiers());
          Player o=AutoAgent.other(gd.getPlayers());
          if(o!=null&&o.getTeam()==me.getTeam()){
            gd.switchSide(me);log("SWITCH_SIDE vs "+o.getName());nap(800);
          }
          gd.setReady(me,true);
          long wait=startWait(r);
          log("READY; waiting up to "+(wait/1000)+"s (mode="+gd.getGameMode()+", room p="+r.getNumPlayers()+")");

          long wd=Math.min(END,System.currentTimeMillis()+wait);
          while(System.currentTimeMillis()<wd&&gd.getGameState()!=2&&gd.getGameState()!=0)nap(100);

          if(gd.getGameState()==2){
            log("START_CONFIRMED in "+r.getName()+" mode="+gd.getGameMode());
            if(play(gd)){
              int result=outcome(gd);matches++;
              if(result>0)wins++;else if(result<0)losses++;
              log("MATCH_RESULT #"+matches+" "+(result>0?"WIN":result<0?"LOSS":"DRAW/UNKNOWN")+" score="+wins+"-"+losses);
              if(result>0){
                log("WIN_REACHED matches="+matches+" wins="+wins+" losses="+losses);
                log("SESSION_SUMMARY matches="+matches+" wins="+wins+" losses="+losses);
                return;
              }
              cooldown.put(key,System.currentTimeMillis()+30_000L);
              leave(gd);finishedMatch=true;break;
            }
            cooldown.put(key,System.currentTimeMillis()+rejectCooldown(r));leave(gd);continue;
          }

          if(gd.getGameState()==0){
            log("ROOM_DISCONNECTED_BEFORE_START "+key);
            cooldown.put(key,System.currentTimeMillis()+rejectCooldown(r));
          }else{
            log("IDLE_ROOM_TIMEOUT "+r.getName()+"; switching rooms");
            cooldown.put(key,System.currentTimeMillis()+(r.getNumPlayers()>=6?90_000L:60_000L));
          }
          leave(gd);
        }
        if(finishedMatch){
          log("BACK_TO_MATCHMAKING matches="+matches+" wins="+wins+" losses="+losses);nap(1000);continue;
        }
        if(!triedOne)nap(3000);
      }
      log("SESSION_SUMMARY matches="+matches+" wins="+wins+" losses="+losses);
      log("TIME_LIMIT_NO_WIN");
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }
}
