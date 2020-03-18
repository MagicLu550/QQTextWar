package cn.qqtextwar

import cn.qqtextwar.dsl.ServerConfigParser
import cn.qqtextwar.entity.Entity
import cn.qqtextwar.entity.Freak
import cn.qqtextwar.entity.Freak.FreakEnum
import cn.qqtextwar.entity.GameMap
import cn.qqtextwar.entity.Player
import cn.qqtextwar.math.Vector
import cn.qqtextwar.log.ServerLogger
import groovy.transform.CompileStatic

@CompileStatic
class Server {

    static final int NO = -1

    static final int START = 0

    static final int GAMEING = 1

    static final int END = 2

    static final String UPDATE_MAP = "update_map"

    static final String GET_MAP = "get_map"



    private ServerLogger logger = new ServerLogger()

    private File baseFile

    private ServerConfigParser parser

    private RPCRunner rpcRunner



    private int round //记录回合

    private int state

    //qq - 玩家 qq唯一
    private Map<Long, Player> players = new HashMap<>()

    //怪物 - uuid唯一
    private Map<UUID, Entity> freaksMap = new HashMap<>()

    private FileRegister register

    Server(){
        this.baseFile = new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()).getParentFile()
        this.register = new FileRegister(this)
        this.register.register()
        this.parser = new ServerConfigParser(register.getConfig(FileRegister.MAIN_CONFIG))
        this.round = 0
        this.state = NO
    }

    //启动的方法组合顺序
    // 1. 开启数据库链接，RPC
    // 2. 获得开局信息
    // 3. 开局结束则重新获得地图，如果还在开局则从数据库获得地图
    // 4. 创建玩家对象
    // 5. 从数据库获得信息初始化玩家，玩家坐标如果是重新开局则重置坐标
    // 6. 创建怪物对象
    // 7. 如果重新开局直接重新创建对象，如果没有结束则从数据库读取对象
    // 8. 游戏开始
    // 9. 游戏结束，初始化全部对象。
    void start(){
        this.logger.info("server is starting...")
        String ip = this.parser.getHeadValue("server.ip")
        String port = this.parser.getHeadValue("server.port")
        this.rpcRunner = new RPCRunner()
        rpcRunner.start(ip,port)
    }

    Player createPlayer(long qq,GameMap map){
        if(!players.containsKey(qq)){
            Vector vector = map.randomVector()
            Player player = new Player(qq,vector)
            players[qq] = player
            return player
        }else{
            return players[qq]
        }
    }

    //这里通过数据库初始化信息
    void initPlayers(){

    }
    //同上
    void initFreaks(){

    }

    Freak createFreaks(GameMap map, FreakEnum freaksId){
        Freak freaks = new Freak(map.randomVector(),freaksId.mapValue)
        freaksMap.put(freaks.uuid,freaks)
        return freaks
    }


    String updateMap(String image,List<List<Integer>> map){
        if(rpcRunner){
            return rpcRunner.execute(UPDATE_MAP,String.class,image,map)
        }
        return ""
    }

    GameMap getMap(){
        if(rpcRunner){
            return new GameMap("",(List<List<Integer>>)rpcRunner.execute(GET_MAP,List.class))
        }
        return null
    }

    File getBaseFile() {
        return baseFile
    }

    ServerConfigParser getParser() {
        return parser
    }

}
