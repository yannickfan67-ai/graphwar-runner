package Graphwar;

import java.awt.Frame;
import java.lang.instrument.Instrumentation;
import java.util.*;
import GraphServer.Constants;

public class OnlineAgentExtra {
  static final String NAME = "GPT-2";
  static final long END = System.currentTimeMillis() + 45L*60_000L;
  static void log(String s){System.out.println("[GPT-EXTRA] "+s);System.out.flush();}
  static void nap(long n){try{Thread.sleep(n);}catch(Exception e){}}

  public static void premain(String a, Instrumentation i){
    Thread t=new Thread(()->{run();log("Agent finished; closing Graphwar.");nap(2500);System.exit(0);},"GPT-Extra-Agent");
    t.setDaemon(false);t.start();
  }

  static Graphwar find(){
    for(int n=0;n<400;n++){
      for(Frame f:Frame.getFrames())if(f instanceof Graphwar)return (Graphwar)f;
      nap(100);
    }
    return null;
  }

  static int alive(Player p){
    int n=0;if(p==null)return 0;
    for(Soldier s:p.getSoldiers())if(s!=null&&s.isAlive())n++;
    return n;
  }

  static int playersOnTeam(GameData gd,int team){
    int n=0;for(Player p:new ArrayList<>(gd.getPlayers()))if(p.getTeam()==team)n++;return n;
  }

  static int aliveOnTeam(GameData gd,int team){
    int n=0;for(Player p:new ArrayList<>(gd.getPlayers()))if(p.getTeam()==team)n+=alive(p);return n;
  }

  static int enemyPlayers(GameData gd,int team){
    int n=0;for(Player p:new ArrayList<>(gd.getPlayers()))if(p.getTeam()!=team)n++;return n;
  }

  static void leave(GameData gd){
    try{if(gd.getGameState()==0)gd.stopGame();else gd.disconnect();}
    catch(Throwable x){try{gd.stopGame();}catch(Throwable y){}}
    nap(800);
  }

  static List<Room> rooms(List<Room> all,Map<String,Long> cd){
    long now=System.currentTimeMillis();List<Room> out=new ArrayList<>();
    for(Room r:new ArrayList<>(all)){
      if(r.getNumPlayers()<2||r.getNumPlayers()>=10)continue;
      if(r.getGameMode()<0||r.getGameMode()>2)continue;
      String roomName=r.getName();
      if(!(roomName.startsWith("Public Room")||roomName.startsWith("Cool Room")))continue;
      String k=r.getIp()+":"+r.getPort();
      if(cd.getOrDefault(k,0L)<=now)out.add(r);
    }
    out.sort(Comparator.comparingInt(Room::getNumPlayers).reversed().thenComparingInt(Room::getGameMode));
    log("CANDIDATES="+out.size()+" roomTypes=Public+Cool prefer=most-populated minPlayers=2");
    for(Room r:out)log("CAND "+r.getName()+" p="+r.getNumPlayers()+" mode="+r.getGameMode()+" "+r.getIp()+":"+r.getPort());
    return out;
  }

  static long waitForStart(Room r){
    int p=r.getNumPlayers();
    if(p>=8)return 90_000L;
    if(p>=6)return 75_000L;
    if(p>=4)return 60_000L;
    return 45_000L;
  }

  static void balanceSide(GameData gd,Player me){
    int mine=playersOnTeam(gd,me.getTeam());
    int other=0;
    for(Player p:new ArrayList<>(gd.getPlayers()))if(p.getTeam()!=me.getTeam())other++;
    if(mine>other+1){
      int before=me.getTeam();
      gd.switchSide(me);nap(700);
      log("BALANCE_SIDE "+before+"->"+me.getTeam()+" countsBefore="+mine+"-"+other);
    }
  }

  static int play(GameData gd,Player me){
    int myTeam=me.getTeam();
    if(enemyPlayers(gd,myTeam)==0){log("ABORT_NO_ENEMY_AT_START");return 0;}
    boolean sawEnemy=true;int lp=-1,ls=-1;long last=0;
    log("GAME_STARTED mode="+gd.getGameMode()+" players="+gd.getPlayers().size()+" myTeam="+myTeam+" enemies="+enemyPlayers(gd,myTeam));
    AutoAgent.dump(gd.getPlayers());

    while(System.currentTimeMillis()<END){
      int st=gd.getGameState();
      if(st!=2){
        int ours=aliveOnTeam(gd,myTeam),theirs=0;
        for(Player p:new ArrayList<>(gd.getPlayers()))if(p.getTeam()!=myTeam)theirs+=alive(p);
        log("GAME_ENDED state="+st+" teamAlive="+ours+" enemyAlive="+theirs+" sawEnemy="+sawEnemy);
        AutoAgent.dump(gd.getPlayers());
        if(sawEnemy&&ours>0&&theirs==0)return 1;
        if(sawEnemy&&ours==0&&theirs>0)return -1;
        return 0;
      }

      if(enemyPlayers(gd,myTeam)>0)sawEnemy=true;
      Player cur=gd.getCurrentTurnPlayer();
      if(cur!=null&&cur.isLocalPlayer()&&!gd.isDrawingFunction()&&!gd.isExploding()){
        int s=cur.getCurrentTurnSoldierIndex();
        if(cur.getID()!=lp||s!=ls||System.currentTimeMillis()-last>9000){
          String f=AutoAgent.aim(gd,cur);
          if(AutoAgent.LAST_HAS_ANGLE){gd.setAngle(AutoAgent.LAST_ANGLE);nap(120);log("ANGLE "+AutoAgent.fmt(AutoAgent.LAST_ANGLE));}
          log("FIRE "+f);gd.sendFunction(f);
          lp=cur.getID();ls=s;last=System.currentTimeMillis();nap(1000);
        }
      }
      nap(100);
    }
    log("SESSION_LIMIT_DURING_GAME");return 0;
  }

  static void run(){
    try{
      Graphwar g=find();if(g==null){log("ERROR no Graphwar frame");return;}
      long d=System.currentTimeMillis()+20_000L;
      while(System.currentTimeMillis()<d&&(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null))nap(100);
      if(g.getGlobalClient()==null||g.getGameData()==null||g.getUI()==null){log("ERROR init timeout");return;}

      g.joinGlobal(NAME);log("JOIN_GLOBAL "+NAME);
      GlobalClient gc=g.getGlobalClient();
      d=System.currentTimeMillis()+25_000L;
      while(System.currentTimeMillis()<d&&gc.getRooms().isEmpty())nap(100);
      log("ROOMS="+gc.getRooms().size());

      GameData gd=g.getGameData();Map<String,Long> cd=new HashMap<>();
      int attempts=0,matches=0,wins=0,losses=0;
      while(System.currentTimeMillis()<END){
        List<Room> list=rooms(gc.getRooms(),cd);
        if(list.isEmpty()){log("NO_MULTI_ROOM; refresh 4s");nap(4000);continue;}

        for(Room r:list){
          if(System.currentTimeMillis()>=END)break;
          attempts++;String key=r.getIp()+":"+r.getPort();
          log("TRY_ROOM #"+attempts+" "+r.getName()+" p="+r.getNumPlayers()+" mode="+r.getGameMode());
          Player me=null;
          try{
            g.joinGame(r.getIp(),r.getPort());gd.addPlayer(gc.getLocalPlayerName());g.getUI().setScreen(1);
            d=System.currentTimeMillis()+8000L;
            while(System.currentTimeMillis()<d){
              me=gd.getFirstLocalPlayer();if(me!=null)break;if(gd.getGameState()==0)break;nap(100);
            }
          }catch(Throwable x){log("ROOM_CONNECT_ERROR "+key+" "+x);}

          if(me==null){log("ROOM_REJECTED "+key);cd.put(key,System.currentTimeMillis()+45_000L);leave(gd);continue;}
          d=System.currentTimeMillis()+5000L;
          while(System.currentTimeMillis()<d&&gd.getPlayers().size()<2&&gd.getGameState()!=0)nap(100);
          if(gd.getPlayers().size()<2){log("NO_REMOTE_AFTER_JOIN; leave");cd.put(key,System.currentTimeMillis()+60_000L);leave(gd);continue;}

          balanceSide(gd,me);
          int myTeam=me.getTeam();
          if(enemyPlayers(gd,myTeam)==0){
            log("NO_ENEMY_TEAM_AFTER_JOIN team="+myTeam+"; leave");cd.put(key,System.currentTimeMillis()+60_000L);leave(gd);continue;
          }

          log("JOINED_ROOM "+r.getName()+" players="+gd.getPlayers().size()+" myTeam="+myTeam+" enemies="+enemyPlayers(gd,myTeam));
          gd.setReady(me,true);log("READY wait="+(waitForStart(r)/1000)+"s");
          long wd=Math.min(END,System.currentTimeMillis()+waitForStart(r));
          while(System.currentTimeMillis()<wd&&gd.getGameState()!=2&&gd.getGameState()!=0){
            if(enemyPlayers(gd,myTeam)==0)break;nap(100);
          }

          if(gd.getGameState()==2&&enemyPlayers(gd,myTeam)>0){
            int result=play(gd,me);matches++;
            if(result>0){wins++;log("MATCH_RESULT #"+matches+" WIN score="+wins+"-"+losses);log("WIN_REACHED");return;}
            if(result<0)losses++;
            log("MATCH_RESULT #"+matches+" "+(result<0?"LOSS":"DRAW/UNKNOWN")+" score="+wins+"-"+losses);
            cd.put(key,System.currentTimeMillis()+30_000L);leave(gd);break;
          }

          log("NO_START_OR_ENEMY_LEFT; switching room");cd.put(key,System.currentTimeMillis()+60_000L);leave(gd);
        }
      }
      log("SESSION_SUMMARY matches="+matches+" wins="+wins+" losses="+losses);
      log("TIME_LIMIT_NO_WIN");
    }catch(Throwable t){log("FATAL "+t);t.printStackTrace(System.out);}
  }
}
