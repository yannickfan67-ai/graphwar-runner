package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class AutoAgentV2 {
  static final String NAME="GPT";
  static final long END=System.currentTimeMillis()+7*60_000L;
  static final long WAIT_START=120_000L;
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

  static List<Room> rooms(List<Room> all,Map<String,Long> cooldown){
    long now=System.currentTimeMillis();
    List<Room> out=new ArrayList<>();
    for(Room r:new ArrayList<>(all)){
      if(r.getGameMode()!=0||r.getNumPlayers()<1||r.getNumPlayers()>=10||!r.getName().startsWith("Public Room"))continue;
      String k=r.getIp()+":"+r.getPort();
      if(cooldown.getOrDefault(k,0L)<=now)out.add(r);
    }
    out.sort(Comparator.comparingInt(Room::getNumPlayers).reversed());
    log("CANDIDATES="+out.size()+" prefer=near-full");
    for(Room r:out)log("CAND "+r.getName()+" p="+r.getNumPlayers()+" "+r.getIp()+":"+r.getPort());
    return out;
  }

  static void leave(GameData gd){
    try{
      if(gd.getGameState()==0)gd.stopGame();else gd.disconnect();
    }catch(Throwable x){try{gd.stopGame();}catch(Throwable y){}}
    nap(800);
  }

  static boolean play(GameData gd){
    boolean started=false;int lp=-1,ls=-1;long last=0;
    while(System.currentTimeMillis()<END){
      int st=gd.getGameState();
      if(st==2){
        if(!started){started=true;log("GAME_STARTED players="+gd.getPlayers().size());AutoAgent.dump(gd.getPlayers());}
        Player cur=gd.getCurrentTurnPlayer();
        if(cur!=null&&cur.isLocalPlayer()&&!gd.isDrawingFunction()&&!gd.isExploding()){
          int s=cur.getCurrentTurnSoldierIndex();
          if(cur.getID()!=lp||s!=ls||System.currentTimeMillis()-last>8000){
            String f=AutoAgent.aim(gd,cur);log("FIRE "+f);gd.sendFunction(f);
            lp=cur.getID();ls=s;last=System.currentTimeMillis();nap(1200);
          }
        }
      }else if(started){
        log("GAME_ENDED state="+st);AutoAgent.dump(gd.getPlayers());nap(5000);return true;
      }else if(st==0){
        log("DISCONNECTED_IN_GAME_WAIT");return false;
      }
      nap(100);
    }
    log("TIME_LIMIT_DURING_GAME");return started;
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

      GameData gd=g.getGameData();
      Map<String,Long> cooldown=new HashMap<>();
      int attempt=0;

      while(System.currentTimeMillis()<END){
        List<Room> list=rooms(gc.getRooms(),cooldown);
        if(list.isEmpty()){log("No usable occupied Public Room; refresh 5s");nap(5000);continue;}

        boolean triedOne=false;
        for(Room r:list){
          if(System.currentTimeMillis()>=END)break;
          triedOne=true;attempt++;
          String key=r.getIp()+":"+r.getPort();
          log("TRY_ROOM #"+attempt+" "+r.getName()+" p="+r.getNumPlayers()+" "+key);

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
            cooldown.put(key,System.currentTimeMillis()+60_000L);
            leave(gd);continue;
          }

          log("JOINED_ROOM "+r.getName()+" id="+me.getID()+" team="+me.getTeam()+" soldiers="+me.getNumSoldiers());
          Player o=AutoAgent.other(gd.getPlayers());
          if(o!=null&&o.getTeam()==me.getTeam()){gd.switchSide(me);log("SWITCH_SIDE vs "+o.getName());nap(800);}
          gd.setReady(me,true);log("READY; waiting up to "+(WAIT_START/1000)+"s");

          long wd=Math.min(END,System.currentTimeMillis()+WAIT_START);
          while(System.currentTimeMillis()<wd&&gd.getGameState()!=2&&gd.getGameState()!=0)nap(100);

          if(gd.getGameState()==2){
            log("START_CONFIRMED in "+r.getName());
            if(play(gd))return;
            cooldown.put(key,System.currentTimeMillis()+60_000L);
            leave(gd);continue;
          }

          if(gd.getGameState()==0){
            log("ROOM_DISCONNECTED_BEFORE_START "+key);
            cooldown.put(key,System.currentTimeMillis()+60_000L);
          }else{
            log("IDLE_ROOM_TIMEOUT "+r.getName()+"; switching rooms");
            cooldown.put(key,System.currentTimeMillis()+120_000L);
          }
          leave(gd);
        }
        if(!triedOne)nap(3000);
      }
      log("TIME_LIMIT_NO_MATCH");
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }
}
