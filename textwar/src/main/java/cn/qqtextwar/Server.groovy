package cn.qqtextwar

import cn.qqtextwar.Threads.MapThread
import cn.qqtextwar.annotations.Action
import cn.qqtextwar.annotations.EntityCreater
import cn.qqtextwar.annotations.InternalInit
import cn.qqtextwar.annotations.RPC
import cn.qqtextwar.annotations.Register
import cn.qqtextwar.annotations.Testing
import cn.qqtextwar.api.Application
import cn.qqtextwar.command.CommandExecutor
import cn.qqtextwar.dsl.ServerConfigParser
import cn.qqtextwar.entity.Registered
import cn.qqtextwar.entity.impl.SkeletonMan
import cn.qqtextwar.entity.Mob
import cn.qqtextwar.entity.impl.Slime
import cn.qqtextwar.entity.player.Player
import cn.textwar.plugin.event.EventExecutor
import cn.qqtextwar.ex.CloseException
import cn.qqtextwar.ex.ServerException
import cn.qqtextwar.math.Vector
import cn.qqtextwar.log.ServerLogger
import cn.qqtextwar.utils.Translate
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import groovy.transform.CompileStatic

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * TextWar游戏的服务端对象，为单例模式，只能通过
 * {@code Server.start()}和{@code Server.stop()}开启
 * 并且只能开启一次和关闭一次。
 *
 * @author MagicLu550 @ 卢昶存
 */
@CompileStatic
class Server {

    static{
        registerMobs()
    }

    public static final String GAME_DIFFICULTY = "server.game.difficulty"

    public static final String PYTHON_COMMAND = "server.python.command"

    public static final String BORN = "server.game.player.born"

    public static final String PLAYER_HP= "${BORN}.health"

    public static final String PLAYER_MONEY = "${BORN}.money"

    public static final String PLAYER_MANA = "${BORN}.mana"

    /** 第一次开启时的状态 */
    public static final int NO = -1

    /** 游戏开启的状态 */
    public static final int START = 0

    /** 游戏进行的状态 */
    public static final int GAMEING = 1

    /** 游戏一回合结束的状态 */
    public static final int END = 2

    /** 服务端即将关闭的状态 **/
    public static final int CLOSED = 3

    /** 从python端更新地图图片的pkey */
    static final String UPDATE_MAP = "update_map"

    /** 从python端获得最新地图图片的pkey */
    static final String GET_MAP = "get_map"

    /** 从python端初始化地图元素图片的pkey，如怪物的图片 */
    static final String UPDATE_PIC = "update_pic"

    static final String GET_AREA_MAP = "get_area_map"

    /** 游戏难度，目前还没有完成 **/
    private int difficulty //难度，后定

    /** 服务端的日志对象，用于输出日志 */
    private ServerLogger logger = new ServerLogger()

    /** 指向服务端的根目录 */
    private File baseFile

    /** server.cfg的解析器 */
    private ServerConfigParser parser

    /** 负责和rpc服务端交互的客户端，目标为python端 */
    private RPCRunner rpcRunner

    /** 记录回合数量 */
    private AtomicInteger round //记录回合

    private Executor threads

    /**
     * 记录游戏的状态，这里同时也是用于控制每个线程的进行
     * 如果为CLOSED，线程全部结束
     */
    private AtomicInteger state

    //qq - 玩家 qq唯一
    /** 存储全部玩家的对象，以qq为唯一键值对 */
    private Map<Long, Player> players = new HashMap<>()

    //怪物 - uuid唯一
    /** 记录全部怪物和生物 */
    private Map<UUID, Mob> freaksMap = new HashMap<>()

    /** 用于将指定的resources文件复制到服务端根目录下，方便玩家修改，并存储相应的文件对象 */
    private FileRegister register

    /** 随机数器，不要直接使用，请使用线程安全的random方法*/
    private Random random

    /** 单例服务端，会在start静态方法时创建  */
    private static Server server

    private volatile GameMap gameMap

    private MapThread mapThread

    private int playerHealth

    private int playerMana

    private int playerMoney

    private List<Application> applications

    private CommandExecutor executor

    private EventExecutor eventExecutor

    private boolean test

    private Translate translater

    private Queue<String> imageFiles = new LinkedBlockingQueue<>()

    /** 服务端构造方法，请不要直接使用它 */
    @InternalInit
    private Server(boolean test,Application... app){
        if(!server){
            server = this
        }else{
            throw new ServerException(translate("start_exception"))
        }
        this.test = test
        this.baseFile = new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()).getParentFile()
        this.register = new FileRegister(this)
        this.register.register()
        this.parser = new ServerConfigParser(register.getConfig(FileRegister.MAIN_CONFIG))
        ((List<String>)this.parser.getValue(PYTHON_COMMAND,[])[0]).each { it.execute() }
        this.translater = new Translate(parser.getHeadValue("server.translate"))
        this.difficulty = (Integer)parser.getValue(GAME_DIFFICULTY,1)[0]
        this.round = new AtomicInteger()
        this.state = new AtomicInteger()
        this.random = new Random()
        this.eventExecutor = new EventExecutor()
        this.mapThread = new MapThread(this)
        this.executor = new CommandExecutor(this)
        this.playerHealth = (Integer)parser.getValue(PLAYER_HP,100)[0]
        this.playerMana = (Integer)parser.getValue(PLAYER_MANA,100)[0]
        this.playerMoney = (Integer)parser.getValue(PLAYER_MONEY,100)[0]
        this.applications = Arrays.asList(app)
        this.threads = Executors.newFixedThreadPool(applications.size())
        applications.each {
            threads.execute(new Threads.ApplicationRunThread(this,it))
        }
    }

    @Action
    void putImage(String image){
        imageFiles.offer(image)
    }

    @Action
    String consumeImage(){
        imageFiles.poll()
    }

    @Action
    void logOut(Player player){
        players.remove(player.id)
        gameMap.removeEntity(player)
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
    /** 服务端对象的启动方法，请不要直接调用，否则会出现不可预料的错误 */
    @Action
    private Server start0(){
        this.state.compareAndSet(state.get(),START)
        this
    }


    /** 如果没启动rpc，就默认获得服务端map文件夹的地图 */
    @Action
    Server initMap(){
        if(!rpcRunner){
            File file = register.getConfig(FileRegister.MAP)
            if(parser.getValue("server.map.random",true)[0]){
                File[] maps = file.listFiles()
                File map = maps[random(maps.length-1)]
                this.gameMap = new GameMap(map.text)
            }else{
                String name = parser.getHeadValue("server.map.name")
                File child = new File(file,name)
                this.gameMap = new GameMap(child.text)
            }
        }
        this
    }

    //游戏第下一回合，
    //需要重新获得地图，
    //玩家的坐标重新初始化，
    //怪物全部清除，
    //GameMap设置为空，
    //重新获取，
    //回合+1
    //所有怪物clear 一回合结束
    /** 服务端只能开启一次 */
    @Action
    static Server start(Application... app){
        Server server = new Server(false,app).start0()
        server.logger.info(server.translate("server_started"))
        server.logger.info(server.translate("copyright"))
        server
    }


    /** 服务端只能关闭一次，同时改变状态使所有线程关闭 */
    @Action
    static stop(){
        try{
            if(server){
                server.close0(null)
            }else{
                throw new CloseException("You could not stop the server before starting state: -1 - NO")
            }
        }catch(Exception ignore){
            System.exit(0)
        }
    }

    /** 服务端只能关闭一次*/
    @Action
    void close0(Throwable throwable){
        if(this.state.get() == CLOSED){
            throw new CloseException(translate("closed"))
        }
        if(this.logger != null){
            this.logger.info("the server is closing...")
            this.state.compareAndSet(this.state.get(),CLOSED)
            if(throwable!=null) {
                this.logger.error(throwable.toString())
                throwable.stackTrace.each {
                    this.logger.error("at "+it)
                }
                if(throwable.cause!=null){
                    this.logger.error(throwable.cause.toString())
                    throwable.cause.stackTrace.each {
                        this.logger.error("at "+it)
                    }
                }
            }
            this.logger.info(translate("closed_info"))
        }
        System.exit(0)
    }



    /** 这里通过数据库初始化信息 */
    @InternalInit
    void initPlayers(){

    }
    /** 同上 */
    @InternalInit
    void initFreaks(){

    }

    //负责创建实体

    /** 负责注册全部的怪物，会在createMobs里使用，会根据这个表随机创建怪物 */
    @Register
    static void registerMobs(){
        Mob.registerMob(1000,SkeletonMan.class,"")
        Mob.registerMob(1001,Slime.class,"")
    }

    /** 用于创建玩家对象，*/
    @EntityCreater
    Player createPlayer(Application app,String ip,long qq, GameMap map){
        if(!players.containsKey(qq)){
            Vector vector = map.randomVector()
            Player player = new Player(app,ip,vector,qq,100,100,100)
            players[qq] = player
            return player
        }else{
            return players[qq]
        }
    }

    /** 创建玩家，并注册到地图 */
    @EntityCreater
    Player registerPlayer(Application app,String ip,long qq,GameMap map){
        Player player = createPlayer(app,ip,qq,map).addInto(map) as Player
        if(test)logger.debug(map.toString())
        return player
    }


    /** 创建Mob，并注册到地图 */
    @EntityCreater
    List<Mob> registerMobs(GameMap map,int n){
        List<Mob> mobs = createRandomMobs(map,n)
        for(Mob mob : mobs){
            map.addEntity(mob)
        }
        return mobs
    }


    /** 通过地图创建单个怪物 */
    @EntityCreater
    Mob createMob(GameMap map,Class<? extends Registered> clz){
        Registered registered = clz.newInstance(map.randomVector(),difficulty)
        if(registered instanceof Mob){
            Mob mob = registered as Mob
            freaksMap.put(mob.uuid,mob)
            return mob
        }

        return null
    }

    /** n为创建的数量，创建多个怪物，根据怪物表 */
    @EntityCreater
    List<Mob> createRandomMobs(GameMap map,int n){
        List<Mob> mobs = new ArrayList<>()
        (1..n).each {
            int round = random(Mob.getMobs().size())
            mobs.add(createMob(map,Mob.mobs().get(round)))
        }
        return mobs
    }

    //RPC 如果不开启mapRPC，则无法使用

    /** 向rpc服务端发出指令，以更新图片，传入修改过的map对象 */
    @RPC
    String updateMap(String image,GameMap map){
        if(rpcRunner){
            String file = rpcRunner.execute(UPDATE_MAP,String.class,image,map.toJson())
            map.setFile(file)
            return file
        }
        return ""
    }

    // Todo Map
    /**获得最新的Map，只有开始第一回合或者下一回合调用  */
    @RPC
    GameMap getMap(int type){
        if(rpcRunner){
            return new GameMap(rpcRunner.execute(GET_MAP,String.class,type))
        }
        return null
    }

    /** 初始化全部怪物的图片，在开启时调用 */
    @RPC
    void updatePicture(int id,String file){
        if(rpcRunner){
            rpcRunner.execute(UPDATE_PIC,id,file)
        }
    }

    @RPC
    void preparePicture(){
        Mob.idMapping.each{
            int x,Class y->
                String image = Mob.mobImages.get(y)
                updatePicture(x,image)
        }
    }

    @RPC
    String getAreaMap(String picturePath,Player player,Vector[] entity,GameMap map){
        if(rpcRunner){
            int x1 = map.getXBound((int)(player.getX() - 5))
            int y1 = map.getYBound((int)(player.getY() - 5))
            int x2 = map.getXBound((int)(player.getX() + 5))
            int y2 = map.getYBound((int)(player.getY() + 5))
            JSONObject jsonObject = new JSONObject()
            jsonObject.put("picpath",picturePath)
            JSONArray area = new JSONArray(4)
            area.add(x1)
            area.add(y1)
            area.add(x2)
            area.add(y2)
            jsonObject.put("area",area)
            JSONArray es = new JSONArray()
            for(Vector vector : entity){
                JSONArray e = new JSONArray()
                e.add(vector.getX())
                e.add(vector.getY())
                es.add(e)
            }
            jsonObject.put("num",es)
            return rpcRunner.execute(GET_AREA_MAP,String.class,jsonObject.toJSONString())
        }
        return ""
    }

    /** 更新地图计数 */
    @RPC
    void wantUpdate(){
        mapThread.wantUpdate()
    }

    /** 开启图片获取端，用于qq-connector */
    @RPC
    Server rpcMap(){
        String ip = this.parser.getHeadValue("server.rpc.ip")
        String port = this.parser.getHeadValue("server.rpc.port")
        this.rpcRunner = new RPCRunner()
        rpcRunner.start(ip,port)
        this.logger.info(translate("map_starting"))
        mapThread.start()
        this.logger.info(translate("map_started"))
        this
    }

    @Testing
    static Server testServer(Application... app){
        Server server = new Server(true,app).start0()
        server.logger.debug("testing....")
        server.gameMap = new GameMap(Server.class.getResourceAsStream("test.json").text)
        server.logger.debug("Create a Map")
        server
    }

    File getBaseFile() {
        return baseFile
    }

    ServerConfigParser getParser() {
        return parser
    }

    GameMap getGameMap() {
        return gameMap
    }

    void setGameMap(GameMap gameMap) {
        this.gameMap = gameMap
    }

    AtomicInteger getState() {
        return state
    }

    List<Application> getApplications() {
        return applications
    }

    CommandExecutor getExecutor() {
        return executor
    }

    EventExecutor getEventExecutor() {
        return eventExecutor
    }

    FileRegister getRegister() {
        return register
    }

    ServerLogger getLogger(){
        return logger
    }

    boolean isTest(){
        return test
    }

    Translate getTranslater(){
        return translater
    }

    String translate(String key){
        translater.translate(key)
    }

    Player getPlayer(long qq){
        return players[qq]
    }

    static Server getServer(){
        return server
    }

    /** 线程安全的随机表，在创建怪物时使用 */
    synchronized int random(int round){
        random.nextInt(round)
    }
}
