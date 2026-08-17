package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class AutoAgentV2 {
  static final String NAME="GPT";
  static final long END=System.currentTimeMillis()+7*60_000L;
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

  static List<Room> rooms(List<Room> all,Set<String> tried){
    List<Room> out=new ArrayList<>();
    for(Room r:new ArrayList<>(all)){
      if(r.getGameMode()!=0||r.getNumPlayers()<1||r.getNumPlayers()>=10||!r.getName().startsWith("Public Room"))continue;
      if(!tried.contains(r.getIp()+":"+r.getPort()))out.add(r);
    }
    out.sort(Comparator.comparingInt(Room::getNumPlayers));
    log("CANDIDATES="+out.size());
    for(Room r:out)log("CAND "+r.getName()+" p="+r.getNumPlayers()+" "+r.getIp()+":"+r.getPort());
    return out;
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

      GameData gd=g.getGameData();Player me=null;Room joined=null;
      Set<String> tried=new HashSet<>();
      while(me==null&&System.currentTimeMillis()<END){
        List<Room> list=rooms(gc.getRooms(),tried);
        if(list.isEmpty()){tried.clear();log("Refresh lobby 5s");nap(5000);continue;}
        for(Room r:list){
          if(System.currentTimeMillis()>=END)break;
          String key=r.getIp()+":"+r.getPort();tried.add(key);
          log("TRY_ROOM "+r.getName()+" p="+r.getNumPlayers()+" "+key);
          try{
            g.joinGame(r.getIp(),r.getPort());
            gd.addPlayer(gc.getLocalPlayerName());
            g.getUI().setScreen(1);
            d=System.currentTimeMillis()+6000;
            while(System.currentTimeMillis()<d){
              me=gd.getFirstLocalPlayer();
              if(me!=null){joined=r;break;}
              if(gd.getGameState()==0)break;
              nap(100);
            }
          }catch(Throwable x){log("ROOM_CONNECT_ERROR "+key+" "+x);}
          if(me!=null)break;
          log("ROOM_REJECTED "+key+" state="+gd.getGameState());
          try{if(gd.getGameState()==0)gd.stopGame();else gd.disconnect();}catch(Throwable x){try{gd.stopGame();}catch(Throwable y){}}
          nap(700);
        }
      }
      if(me==null){log("Could not enter an occupied Public Room");return;}
      log("JOINED_ROOM "+joined.getName()+" id="+me.getID()+" team="+me.getTeam()+" soldiers="+me.getNumSoldiers());

      Player o=AutoAgent.other(gd.getPlayers());
      if(o!=null&&o.getTeam()==me.getTeam()){gd.switchSide(me);log("SWITCH_SIDE vs "+o.getName());nap(800);}
      gd.setReady(me,true);log("READY");

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
          log("GAME_ENDED state="+st);AutoAgent.dump(gd.getPlayers());nap(5000);return;
        }else if(st==0){
          log("DISCONNECTED_BEFORE_START");return;
        }
        nap(100);
      }
      log("TIME_LIMIT");
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }
}
