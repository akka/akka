/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote.netty

import akka.dispatch.{DefaultCompletableFuture, CompletableFuture, Future}
import akka.remote.protocol.RemoteProtocol._
import akka.remote.protocol.RemoteProtocol.ActorType._
import akka.config.ConfigurationException
import akka.serialization.RemoteActorSerialization
import akka.serialization.RemoteActorSerialization._
import akka.japi.Creator
import akka.config.Config._
import akka.remoteinterface._
import akka.actor.{EventHandler, Index, ActorInitializationException, LocalActorRef, newUuid, ActorRegistry, Actor, RemoteActorRef, TypedActor, ActorRef, IllegalActorStateException, RemoteActorSystemMessage, uuidFrom, Uuid, Exit, LifeCycleMessage, ActorType => AkkaActorType}
import akka.AkkaException
import akka.actor.Actor._
import akka.util._
import akka.remote.{MessageSerializer, RemoteClientSettings, RemoteServerSettings}

import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{DefaultChannelGroup,ChannelGroup,ChannelGroupFuture}
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.bootstrap.{ServerBootstrap,ClientBootstrap}
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.compression.{ ZlibDecoder, ZlibEncoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import org.jboss.netty.util.{ TimerTask, Timeout, HashedWheelTimer }
import org.jboss.netty.handler.ssl.SslHandler

import java.net.{ SocketAddress, InetSocketAddress }
import java.util.concurrent.{ TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap, ConcurrentSkipListSet }
import scala.collection.mutable.{ HashMap }
import scala.reflect.BeanProperty
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic. {AtomicReference, AtomicLong, AtomicBoolean}

object RemoteEncoder {
  def encode(rmp: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setMessage(rmp)
    arp.build
  }

  def encode(rcp: RemoteControlProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setInstruction(rcp)
    arp.build
  }
}

trait NettyRemoteClientModule extends RemoteClientModule { self: ListenerManagement =>
  private val remoteClients = new HashMap[Address, RemoteClient]
  private val remoteActors  = new Index[Address, Uuid]
  private val lock          = new ReadWriteGuard

  protected[akka] def typedActorFor[T](intfClass: Class[T], serviceId: String, implClassName: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]): T =
    TypedActor.createProxyForRemoteActorRef(intfClass, RemoteActorRef(serviceId, implClassName, hostname, port, timeout, loader, AkkaActorType.TypedActor))

  protected[akka] def send[T](message: Any,
                              senderOption: Option[ActorRef],
                              senderFuture: Option[CompletableFuture[T]],
                              remoteAddress: InetSocketAddress,
                              timeout: Long,
                              isOneWay: Boolean,
                              actorRef: ActorRef,
                              typedActorInfo: Option[Tuple2[String, String]],
                              actorType: AkkaActorType,
                              loader: Option[ClassLoader]): Option[CompletableFuture[T]] =
  withClientFor(remoteAddress, loader)(_.send[T](message, senderOption, senderFuture, remoteAddress, timeout, isOneWay, actorRef, typedActorInfo, actorType))

  private[akka] def withClientFor[T](
    address: InetSocketAddress, loader: Option[ClassLoader])(fun: RemoteClient => T): T = {
    loader.foreach(MessageSerializer.setClassLoader(_))//TODO: REVISIT: THIS SMELLS FUNNY

    val key = Address(address)
    lock.readLock.lock
    try {
      val c = remoteClients.get(key) match {
        case s: Some[RemoteClient] => s.get
        case None =>
          lock.readLock.unlock
          lock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(key) match { //Recheck for addition, race between upgrades
                case s: Some[RemoteClient] => s.get //If already populated by other writer
                case None => //Populate map
                  val client = new ActiveRemoteClient(this, address, loader, self.notifyListeners _)
                  client.connect()
                  remoteClients += key -> client
                  client
              }
            } finally { lock.readLock.lock } //downgrade
          } finally { lock.writeLock.unlock }
      }
      fun(c)
    } finally { lock.readLock.unlock }
  }

  def shutdownClientConnection(address: InetSocketAddress): Boolean = lock withWriteGuard {
    remoteClients.remove(Address(address)) match {
      case s: Some[RemoteClient] => s.get.shutdown
      case None => false
    }
  }

  def restartClientConnection(address: InetSocketAddress): Boolean = lock withReadGuard {
    remoteClients.get(Address(address)) match {
      case s: Some[RemoteClient] => s.get.connect(reconnectIfAlreadyConnected = true)
      case None => false
    }
  }

  private[akka] def registerSupervisorForActor(actorRef: ActorRef): ActorRef =
    withClientFor(actorRef.homeAddress.get, None)(_.registerSupervisorForActor(actorRef))

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef): ActorRef = lock withReadGuard {
    remoteClients.get(Address(actorRef.homeAddress.get)) match {
      case s: Some[RemoteClient] => s.get.deregisterSupervisorForActor(actorRef)
      case None => actorRef
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownClientModule = {
    shutdownRemoteClients
    //TODO: Should we empty our remoteActors too?
    //remoteActors.clear
  }

  def shutdownRemoteClients = lock withWriteGuard {
    remoteClients.foreach({ case (addr, client) => client.shutdown })
    remoteClients.clear
  }

  def registerClientManagedActor(hostname: String, port: Int, uuid: Uuid) = {
    remoteActors.put(Address(hostname, port), uuid)
  }

  private[akka] def unregisterClientManagedActor(hostname: String, port: Int, uuid: Uuid) = {
    remoteActors.remove(Address(hostname,port), uuid)
    //TODO: should the connection be closed when the last actor deregisters?
  }
}

/**
 * This is the abstract baseclass for netty remote clients,
 * currently there's only an ActiveRemoteClient, but otehrs could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
abstract class RemoteClient private[akka] (
  val module: NettyRemoteClientModule,
  val remoteAddress: InetSocketAddress) {

  val name = this.getClass.getSimpleName + "@" + remoteAddress.getAddress.getHostAddress + "::" + remoteAddress.getPort

  protected val futures = new ConcurrentHashMap[Uuid, CompletableFuture[_]]
  protected val supervisors = new ConcurrentHashMap[Uuid, ActorRef]
  private[remote] val runSwitch = new Switch()
  private[remote] val isAuthenticated = new AtomicBoolean(false)

  private[remote] def isRunning = runSwitch.isOn

  protected def notifyListeners(msg: => Any); Unit
  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean
  def shutdown: Boolean

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send[T](
    message: Any,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]],
    remoteAddress: InetSocketAddress,
    timeout: Long,
    isOneWay: Boolean,
    actorRef: ActorRef,
    typedActorInfo: Option[Tuple2[String, String]],
    actorType: AkkaActorType): Option[CompletableFuture[T]] = synchronized { //TODO: find better strategy to prevent race

    send(createRemoteMessageProtocolBuilder(
        Some(actorRef),
        Left(actorRef.uuid),
        actorRef.id,
        actorRef.actorClassName,
        actorRef.timeout,
        Left(message),
        isOneWay,
        senderOption,
        typedActorInfo,
        actorType,
        if (isAuthenticated.compareAndSet(false, true)) RemoteClientSettings.SECURE_COOKIE else None
      ).build, senderFuture)
  }

  /**
   * Sends the message across the wire
   */
  def send[T](
  request: RemoteMessageProtocol,
  senderFuture: Option[CompletableFuture[T]]): Option[CompletableFuture[T]] = {
    if (isRunning) {
      if (request.getOneWay) {
        currentChannel.write(RemoteEncoder.encode(request)).addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            if (future.isCancelled) {
                //We don't care about that right now
            } else if (!future.isSuccess) {
              notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
            }
          }
        })
        None
      } else {
          val futureResult = if (senderFuture.isDefined) senderFuture.get
                             else new DefaultCompletableFuture[T](request.getActorInfo.getTimeout)
          val futureUuid = uuidFrom(request.getUuid.getHigh, request.getUuid.getLow)
          futures.put(futureUuid, futureResult) //Add this prematurely, remove it if write fails
          currentChannel.write(RemoteEncoder.encode(request)).addListener(new ChannelFutureListener {
            def operationComplete(future: ChannelFuture) {
              if (future.isCancelled) {
                futures.remove(futureUuid) //Clean this up
                //We don't care about that right now
              } else if (!future.isSuccess) {
                futures.remove(futureUuid) //Clean this up
                notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
              }
            }
          })
          Some(futureResult)
      }
    } else {
      val exception = new RemoteClientException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.", module, remoteAddress)
      notifyListeners(RemoteClientError(exception, module, remoteAddress))
      throw exception
    }
  }

  private[akka] def registerSupervisorForActor(actorRef: ActorRef): ActorRef =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Can't register supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.putIfAbsent(actorRef.supervisor.get.uuid, actorRef)

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef): ActorRef =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException(
      "Can't unregister supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.remove(actorRef.supervisor.get.uuid)
}

/**
 *  RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClient private[akka] (
  module: NettyRemoteClientModule, remoteAddress: InetSocketAddress,
  val loader: Option[ClassLoader] = None, notifyListenersFun: (=> Any) => Unit) extends RemoteClient(module, remoteAddress) {
  import RemoteClientSettings._
  //FIXME rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile private var bootstrap: ClientBootstrap = _
  @volatile private[remote] var connection: ChannelFuture = _
  @volatile private[remote] var openChannels: DefaultChannelGroup = _
  @volatile private var timer: HashedWheelTimer = _
  @volatile private var reconnectionTimeWindowStart = 0L

  def notifyListeners(msg: => Any): Unit = notifyListenersFun(msg)
  def currentChannel = connection.getChannel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = {
    runSwitch switchOn {
      openChannels = new DefaultDisposableChannelGroup(classOf[RemoteClient].getName)
      timer = new HashedWheelTimer

      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, futures, supervisors, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      // Wait until the connection attempt succeeds or fails.
      connection = bootstrap.connect(remoteAddress)
      openChannels.add(connection.awaitUninterruptibly.getChannel)

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        false
      } else {
        //Add a task that does GCing of expired Futures
        timer.newTimeout(new TimerTask() {
          def run(timeout: Timeout) = {
            if(isRunning) {
              val i = futures.entrySet.iterator
              while(i.hasNext) {
                val e = i.next
                if (e.getValue.isExpired)
                  futures.remove(e.getKey)
              }
            }
          }
        }, RemoteClientSettings.REAP_FUTURES_DELAY.length, RemoteClientSettings.REAP_FUTURES_DELAY.unit)
        notifyListeners(RemoteClientStarted(module, remoteAddress))
        true
      }
    } match {
      case true => true
      case false if reconnectIfAlreadyConnected =>
        isAuthenticated.set(false)
        openChannels.remove(connection.getChannel)
        connection.getChannel.close
        connection = bootstrap.connect(remoteAddress)
        openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.
        if (!connection.isSuccess) {
          notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
          false
        } else true
      case false => false
    }
  }

  //Please note that this method does _not_ remove the ARC from the NettyRemoteClientModule's map of clients
  def shutdown = runSwitch switchOff {
    notifyListeners(RemoteClientShutdown(module, remoteAddress))
    timer.stop
    timer = null
    openChannels.close.awaitUninterruptibly
    openChannels = null
    bootstrap.releaseExternalResources
    bootstrap = null
    connection = null
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      /*Time left > 0*/ (RECONNECTION_TIME_WINDOW - (System.currentTimeMillis - reconnectionTimeWindowStart)) > 0
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClientPipelineFactory(
  name: String,
  futures: ConcurrentMap[Uuid, CompletableFuture[_]],
  supervisors: ConcurrentMap[Uuid, ActorRef],
  bootstrap: ClientBootstrap,
  remoteAddress: InetSocketAddress,
  timer: HashedWheelTimer,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    val timeout     = new ReadTimeoutHandler(timer, RemoteClientSettings.READ_TIMEOUT.length, RemoteClientSettings.READ_TIMEOUT.unit)
    val lenDec      = new LengthFieldBasedFrameDecoder(RemoteClientSettings.MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec)  = RemoteServerSettings.COMPRESSION_SCHEME match {
      case   "zlib" => (new ZlibEncoder(RemoteServerSettings.ZLIB_COMPRESSION_LEVEL) :: Nil, new ZlibDecoder :: Nil)
      case        _ => (Nil,Nil)
    }

    val remoteClient = new ActiveRemoteClientHandler(name, futures, supervisors, bootstrap, remoteAddress, timer, client)
    val stages: List[ChannelHandler] = timeout :: dec ::: lenDec :: protobufDec :: enc ::: lenPrep :: protobufEnc :: remoteClient :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val name: String,
  val futures: ConcurrentMap[Uuid, CompletableFuture[_]],
  val supervisors: ConcurrentMap[Uuid, ActorRef],
  val bootstrap: ClientBootstrap,
  val remoteAddress: InetSocketAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler {

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction =>
          val rcp = arp.getInstruction
          rcp.getCommandType match {
            case CommandType.SHUTDOWN => spawn { client.module.shutdownClientConnection(remoteAddress) }
          }
        case arp: AkkaRemoteProtocol if arp.hasMessage =>
          val reply = arp.getMessage
          val replyUuid = uuidFrom(reply.getActorInfo.getUuid.getHigh, reply.getActorInfo.getUuid.getLow)
          val future = futures.remove(replyUuid).asInstanceOf[CompletableFuture[Any]]

          if (reply.hasMessage) {
            if (future eq null) throw new IllegalActorStateException("Future mapped to UUID " + replyUuid + " does not exist")
            val message = MessageSerializer.deserialize(reply.getMessage)
            future.completeWithResult(message)
          } else {
            val exception = parseException(reply, client.loader)

            if (reply.hasSupervisorUuid()) {
              val supervisorUuid = uuidFrom(reply.getSupervisorUuid.getHigh, reply.getSupervisorUuid.getLow)
              if (!supervisors.containsKey(supervisorUuid)) throw new IllegalActorStateException(
                "Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
              val supervisedActor = supervisors.get(supervisorUuid)
              if (!supervisedActor.supervisor.isDefined) throw new IllegalActorStateException(
                "Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
              else supervisedActor.supervisor.get ! Exit(supervisedActor, exception)
            }

            future.completeWithException(exception)
          }
        case other =>
          throw new RemoteClientException("Unknown message received in remote client handler: " + other, client.module, client.remoteAddress)
      }
    } catch {
      case e: Throwable =>
        EventHandler notify EventHandler.Error(e, this)
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
        throw e
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = client.runSwitch ifOn {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) = {
          if (client.isRunning) {
            client.openChannels.remove(event.getChannel)
            client.connect(reconnectIfAlreadyConnected = true)
          }
        }
      }, RemoteClientSettings.RECONNECT_DELAY.toMillis, TimeUnit.MILLISECONDS)
    } else spawn { client.module.shutdownClientConnection(remoteAddress) }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientConnected(client.module, client.remoteAddress))
    client.resetReconnectionTimeWindow
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.module, client.remoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    event.getCause match {
      case e: ReadTimeoutException =>
        spawn { client.module.shutdownClientConnection(remoteAddress) }
      case e =>
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
        event.getChannel.close //FIXME Is this the correct behavior?
    }
  }

  private def parseException(reply: RemoteMessageProtocol, loader: Option[ClassLoader]): Throwable = {
    val exception = reply.getException
    val classname = exception.getClassname
    try {
      val exceptionClass = if (loader.isDefined) loader.get.loadClass(classname)
                           else Class.forName(classname)
      exceptionClass
        .getConstructor(Array[Class[_]](classOf[String]): _*)
        .newInstance(exception.getMessage).asInstanceOf[Throwable]
    } catch {
      case problem: Throwable =>
        EventHandler notify EventHandler.Error(problem, this)
        UnparsableException(classname, exception.getMessage)
    }
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport extends RemoteSupport with NettyRemoteServerModule with NettyRemoteClientModule {
  //Needed for remote testing and switching on/off under run
  val optimizeLocal = new AtomicBoolean(true)

  def optimizeLocalScoped_?() = optimizeLocal.get

  protected[akka] def actorFor(serviceId: String, className: String, timeout: Long, host: String, port: Int, loader: Option[ClassLoader]): ActorRef = {
    if (optimizeLocalScoped_?) {
      val home = this.address
      if ((host == home.getAddress.getHostAddress || host == home.getHostName) && port == home.getPort) {//TODO: switch to InetSocketAddress.equals?
        val localRef = findActorByIdOrUuid(serviceId,serviceId)
        if (localRef ne null) return localRef //Code significantly simpler with the return statement
      }
    }

    RemoteActorRef(serviceId, className, host, port, timeout, loader)
  }

  def clientManagedActorOf(factory: () => Actor, host: String, port: Int): ActorRef = {

    if (optimizeLocalScoped_?) {
      val home = this.address
      if ((host == home.getAddress.getHostAddress || host == home.getHostName) && port == home.getPort)//TODO: switch to InetSocketAddress.equals?
        return new LocalActorRef(factory, None) // Code is much simpler with return
    }

    val ref = new LocalActorRef(factory, Some(new InetSocketAddress(host, port)), clientManaged = true)
    //ref.timeout = timeout //removed because setting default timeout should be done after construction
    ref
  }
}

class NettyRemoteServer(serverModule: NettyRemoteServerModule, val host: String, val port: Int, val loader: Option[ClassLoader]) {

  val name = "NettyRemoteServer@" + host + ":" + port
  val address = new InetSocketAddress(host,port)

  private val factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool,Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, loader, serverModule)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", RemoteServerSettings.BACKLOG)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", RemoteServerSettings.CONNECTION_TIMEOUT_MILLIS.toMillis)

  openChannels.add(bootstrap.bind(address))
  serverModule.notifyListeners(RemoteServerStarted(serverModule))

  def shutdown {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder
        if (RemoteClientSettings.SECURE_COOKIE.nonEmpty)
          b.setCookie(RemoteClientSettings.SECURE_COOKIE.get)
        b.setCommandType(CommandType.SHUTDOWN)
        b.build
      }
      openChannels.write(RemoteEncoder.encode(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources
      serverModule.notifyListeners(RemoteServerShutdown(serverModule))
    } catch {
      case e: Exception => 
        EventHandler notify EventHandler.Error(e, this)
    }
  }
}

trait NettyRemoteServerModule extends RemoteServerModule { self: RemoteModule =>
  import RemoteServerSettings._

  private[akka] val currentServer = new AtomicReference[Option[NettyRemoteServer]](None)

  def address = currentServer.get match {
    case s: Some[NettyRemoteServer] => s.get.address
    case None    => ReflectiveAccess.Remote.configDefaultAddress
  }

  def name = currentServer.get match {
    case s: Some[NettyRemoteServer] => s.get.name
    case None    =>
       val a = ReflectiveAccess.Remote.configDefaultAddress
      "NettyRemoteServer@" + a.getAddress.getHostAddress + ":" + a.getPort
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(_hostname: String, _port: Int, loader: Option[ClassLoader] = None): RemoteServerModule = guard withGuard {
    try {
      _isRunning switchOn {
        currentServer.set(Some(new NettyRemoteServer(this, _hostname, _port, loader)))
      }
    } catch {
      case e: Exception => 
        EventHandler notify EventHandler.Error(e, this)
        notifyListeners(RemoteServerError(e, this))
    }
    this
  }

  def shutdownServerModule = guard withGuard {
    _isRunning switchOff {
      currentServer.getAndSet(None) foreach {
        instance =>
        instance.shutdown
      }
    }
  }

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedActor(id: String, typedActor: AnyRef): Unit = guard withGuard {
    if (id.startsWith(UUID_PREFIX)) registerTypedActor(id.substring(UUID_PREFIX.length), typedActor, typedActorsByUuid)
    else registerTypedActor(id, typedActor, typedActors)
  }

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedPerSessionActor(id: String, factory: => AnyRef): Unit = guard withGuard {
    registerTypedPerSessionActor(id, () => factory, typedActorsFactories)
  }

  /**
   * Register Remote Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef): Unit = guard withGuard {
    if (id.startsWith(UUID_PREFIX)) register(id.substring(UUID_PREFIX.length), actorRef, actorsByUuid)
    else register(id, actorRef, actors)
  }

  def registerByUuid(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef.uuid.toString, actorRef, actorsByUuid)
  }

  private def register[Key](id: Key, actorRef: ActorRef, registry: ConcurrentHashMap[Key, ActorRef]) {
    if (_isRunning.isOn) {
      registry.put(id, actorRef) //TODO change to putIfAbsent
      if (!actorRef.isRunning) actorRef.start
    }
  }

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def registerPerSession(id: String, factory: => ActorRef): Unit = synchronized {
    registerPerSession(id, () => factory, actorsFactories)
  }

  private def registerPerSession[Key](id: Key, factory: () => ActorRef, registry: ConcurrentHashMap[Key,() => ActorRef]) {
    if (_isRunning.isOn)
      registry.put(id, factory) //TODO change to putIfAbsent
  }

  private def registerTypedActor[Key](id: Key, typedActor: AnyRef, registry: ConcurrentHashMap[Key, AnyRef]) {
    if (_isRunning.isOn)
      registry.put(id, typedActor) //TODO change to putIfAbsent
  }

  private def registerTypedPerSessionActor[Key](id: Key, factory: () => AnyRef, registry: ConcurrentHashMap[Key,() => AnyRef]) {
    if (_isRunning.isOn)
      registry.put(id, factory) //TODO change to putIfAbsent
  }

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef): Unit = guard withGuard {
    if (_isRunning.isOn) {
      actors.remove(actorRef.id, actorRef)
      actorsByUuid.remove(actorRef.uuid, actorRef)
    }
  }

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(id: String): Unit = guard withGuard {
    if (_isRunning.isOn) {
      if (id.startsWith(UUID_PREFIX)) actorsByUuid.remove(id.substring(UUID_PREFIX.length))
      else {
        val actorRef = actors get id
        actorsByUuid.remove(actorRef.uuid, actorRef)
        actors.remove(id,actorRef)
      }
    }
  }

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterPerSession(id: String): Unit = {
    if (_isRunning.isOn) {
      actorsFactories.remove(id)
    }
  }

  /**
   * Unregister Remote Typed Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterTypedActor(id: String):Unit = guard withGuard {
    if (_isRunning.isOn) {
      if (id.startsWith(UUID_PREFIX)) typedActorsByUuid.remove(id.substring(UUID_PREFIX.length))
      else typedActors.remove(id)
    }
  }

  /**
  * Unregister Remote Typed Actor by specific 'id'.
  * <p/>
  * NOTE: You need to call this method if you have registered an actor by a custom ID.
  */
  def unregisterTypedPerSessionActor(id: String): Unit =
    if (_isRunning.isOn) typedActorsFactories.remove(id)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(
    val name: String,
    val openChannels: ChannelGroup,
    val loader: Option[ClassLoader],
    val server: NettyRemoteServerModule) extends ChannelPipelineFactory {
  import RemoteServerSettings._

  def getPipeline: ChannelPipeline = {
    val lenDec      = new LengthFieldBasedFrameDecoder(MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec)  = COMPRESSION_SCHEME match {
      case "zlib"  => (new ZlibEncoder(ZLIB_COMPRESSION_LEVEL) :: Nil, new ZlibDecoder :: Nil)
      case       _ => (Nil, Nil)
    }

    val remoteServer = new RemoteServerHandler(name, openChannels, loader, server)
    val stages: List[ChannelHandler] = dec ::: lenDec :: protobufDec :: enc ::: lenPrep :: protobufEnc :: remoteServer :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class RemoteServerHandler(
    val name: String,
    val openChannels: ChannelGroup,
    val applicationLoader: Option[ClassLoader],
    val server: NettyRemoteServerModule) extends SimpleChannelUpstreamHandler {
  import RemoteServerSettings._
  val CHANNEL_INIT    = "channel-init".intern

  applicationLoader.foreach(MessageSerializer.setClassLoader(_)) //TODO: REVISIT: THIS FEELS A BIT DODGY

  val sessionActors = new ChannelLocal[ConcurrentHashMap[String, ActorRef]]()
  val typedSessionActors = new ChannelLocal[ConcurrentHashMap[String, AnyRef]]()

  //Writes the specified message to the specified channel and propagates write errors to listeners
  private def write(channel: Channel, payload: AkkaRemoteProtocol): Unit = {
    channel.write(payload).addListener(
      new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isCancelled) {
            //Not interesting at the moment
          } else if (!future.isSuccess) {
            val socketAddress = future.getChannel.getRemoteAddress match {
              case i: InetSocketAddress => Some(i)
              case _ => None
            }
            server.notifyListeners(RemoteServerWriteFailed(payload, future.getCause, server, socketAddress))
          }
        }
      })
  }

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    sessionActors.set(event.getChannel(), new ConcurrentHashMap[String, ActorRef]())
    typedSessionActors.set(event.getChannel(), new ConcurrentHashMap[String, AnyRef]())
    server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
    if (REQUIRE_COOKIE) ctx.setAttachment(CHANNEL_INIT) // signal that this is channel initialization, which will need authentication
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    import scala.collection.JavaConversions.asScalaIterable
    val clientAddress = getClientAddress(ctx)

    // stop all session actors
    for (map <- Option(sessionActors.remove(event.getChannel));
         actor <- asScalaIterable(map.values)) {
         try { actor.stop } catch { case e: Exception => }
    }
    // stop all typed session actors
    for (map <- Option(typedSessionActors.remove(event.getChannel));
         actor <- asScalaIterable(map.values)) {
         try { TypedActor.stop(actor) } catch { case e: Exception => }
    }

    server.notifyListeners(RemoteServerClientDisconnected(server, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    server.notifyListeners(RemoteServerClientClosed(server, clientAddress))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = event.getMessage match {
    case null => throw new IllegalActorStateException("Message in remote MessageEvent is null: " + event)
    //case remoteProtocol: AkkaRemoteProtocol if remoteProtocol.hasInstruction => RemoteServer cannot receive control messages (yet)
    case remoteProtocol: AkkaRemoteProtocol if remoteProtocol.hasMessage =>
      val requestProtocol = remoteProtocol.getMessage
      if (REQUIRE_COOKIE) authenticateRemoteClient(requestProtocol, ctx)
      handleRemoteMessageProtocol(requestProtocol, event.getChannel)
    case _ => //ignore
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def getClientAddress(ctx: ChannelHandlerContext): Option[InetSocketAddress] =
    ctx.getChannel.getRemoteAddress match {
      case inet: InetSocketAddress => Some(inet)
      case _ => None
    }

  private def handleRemoteMessageProtocol(request: RemoteMessageProtocol, channel: Channel) = {
    request.getActorInfo.getActorType match {
      case SCALA_ACTOR => dispatchToActor(request, channel)
      case TYPED_ACTOR => dispatchToTypedActor(request, channel)
      case JAVA_ACTOR  => throw new IllegalActorStateException("ActorType JAVA_ACTOR is currently not supported")
      case other       => throw new IllegalActorStateException("Unknown ActorType [" + other + "]")
    }
  }

  private def dispatchToActor(request: RemoteMessageProtocol, channel: Channel) {
    val actorInfo = request.getActorInfo

    val actorRef =
      try { createActor(actorInfo, channel).start } catch {
        case e: SecurityException =>
          EventHandler notify EventHandler.Error(e, this)
          write(channel, createErrorReplyMessage(e, request, AkkaActorType.ScalaActor))
          server.notifyListeners(RemoteServerError(e, server))
          return
      }

    val message = MessageSerializer.deserialize(request.getMessage)
    val sender =
      if (request.hasSender) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(request.getSender, applicationLoader))
      else None

    message match { // first match on system messages
      case RemoteActorSystemMessage.Stop =>
        if (UNTRUSTED_MODE) throw new SecurityException("Remote server is operating is untrusted mode, can not stop the actor")
        else actorRef.stop
      case _: LifeCycleMessage if (UNTRUSTED_MODE) =>
        throw new SecurityException("Remote server is operating is untrusted mode, can not pass on a LifeCycleMessage to the remote actor")

      case _ =>     // then match on user defined messages
        if (request.getOneWay) actorRef.!(message)(sender)
        else actorRef.postMessageToMailboxAndCreateFutureResultWithTimeout(
          message,
          request.getActorInfo.getTimeout,
          None,
          Some(new DefaultCompletableFuture[AnyRef](request.getActorInfo.getTimeout).
            onComplete(f => {
              val result = f.result
              val exception = f.exception

              if (exception.isDefined) {
                write(channel, createErrorReplyMessage(exception.get, request, AkkaActorType.ScalaActor))
              }
              else if (result.isDefined) {
                val messageBuilder = RemoteActorSerialization.createRemoteMessageProtocolBuilder(
                  Some(actorRef),
                  Right(request.getUuid),
                  actorInfo.getId,
                  actorInfo.getTarget,
                  actorInfo.getTimeout,
                  Left(result.get),
                  true,
                  Some(actorRef),
                  None,
                  AkkaActorType.ScalaActor,
                  None)

                // FIXME lift in the supervisor uuid management into toh createRemoteMessageProtocolBuilder method
                if (request.hasSupervisorUuid) messageBuilder.setSupervisorUuid(request.getSupervisorUuid)

                write(channel, RemoteEncoder.encode(messageBuilder.build))
              }
            }
          )
       ))
    }
  }

  private def dispatchToTypedActor(request: RemoteMessageProtocol, channel: Channel) = {
    val actorInfo = request.getActorInfo
    val typedActorInfo = actorInfo.getTypedActorInfo

    val typedActor = createTypedActor(actorInfo, channel)
    //FIXME: Add ownerTypeHint and parameter types to the TypedActorInfo?
    val (ownerTypeHint, argClasses, args) = MessageSerializer.deserialize(request.getMessage).asInstanceOf[Tuple3[String,Array[Class[_]],Array[AnyRef]]]

    def resolveMethod(bottomType: Class[_], typeHint: String, methodName: String, methodSignature: Array[Class[_]]): java.lang.reflect.Method = {
      var typeToResolve = bottomType
      var targetMethod: java.lang.reflect.Method = null
      var firstException: NoSuchMethodException = null
      while((typeToResolve ne null) && (targetMethod eq null)) {

        if ((typeHint eq null) || typeToResolve.getName.startsWith(typeHint)) {
          try {
            targetMethod = typeToResolve.getDeclaredMethod(methodName, methodSignature:_*)
            targetMethod.setAccessible(true)
          } catch {
            case e: NoSuchMethodException =>
              if (firstException eq null)
                firstException = e

          }
        }

        typeToResolve = typeToResolve.getSuperclass
      }

      if((targetMethod eq null) && (firstException ne null))
        throw firstException

      targetMethod
  }

    try {

      println("%s(%s) = %s for %s" format(ownerTypeHint, argClasses.map(_.getName).mkString(", "), args.mkString(", "), typedActor.getClass.getName))

      val messageReceiver = resolveMethod(typedActor.getClass, ownerTypeHint, typedActorInfo.getMethod, argClasses)

      if (request.getOneWay) messageReceiver.invoke(typedActor, args: _*)
      else {
        //Sends the response
        def sendResponse(result: Either[Any,Throwable]): Unit = try {
          val messageBuilder = RemoteActorSerialization.createRemoteMessageProtocolBuilder(
            None,
            Right(request.getUuid),
            actorInfo.getId,
            actorInfo.getTarget,
            actorInfo.getTimeout,
            result,
            true,
            None,
            None,
            AkkaActorType.TypedActor,
            None)
          if (request.hasSupervisorUuid) messageBuilder.setSupervisorUuid(request.getSupervisorUuid)

          write(channel, RemoteEncoder.encode(messageBuilder.build))
        } catch {
          case e: Exception => 
            EventHandler notify EventHandler.Error(e, this)
            server.notifyListeners(RemoteServerError(e, server))
        }

        messageReceiver.invoke(typedActor, args: _*) match {
          case f: Future[_] => //If it's a future, we can lift on that to defer the send to when the future is completed
            f.onComplete( future => {
              val result: Either[Any,Throwable] =
                if (future.exception.isDefined) Right(future.exception.get) else Left(future.result.get)
              sendResponse(result)
            })
          case other => sendResponse(Left(other))
        }
      }
    } catch {
      case e: InvocationTargetException =>
        EventHandler notify EventHandler.Error(e, this)
        write(channel, createErrorReplyMessage(e.getCause, request, AkkaActorType.TypedActor))
        server.notifyListeners(RemoteServerError(e, server))
      case e: Exception =>
        EventHandler notify EventHandler.Error(e, this)
        write(channel, createErrorReplyMessage(e, request, AkkaActorType.TypedActor))
        server.notifyListeners(RemoteServerError(e, server))
    }
  }

  private def findSessionActor(id: String, channel: Channel) : ActorRef =
    sessionActors.get(channel) match {
      case null => null
      case map => map get id
    }

  private def findTypedSessionActor(id: String, channel: Channel) : AnyRef =
    typedSessionActors.get(channel) match {
      case null => null
      case map => map get id
    }

  /**
   * gets the actor from the session, or creates one if there is a factory for it
   */
  private def createSessionActor(actorInfo: ActorInfoProtocol, channel: Channel): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId

    findSessionActor(id, channel) match {
      case null => // we dont have it in the session either, see if we have a factory for it
        server.findActorFactory(id) match {
          case null => null
          case factory =>
            val actorRef = factory()
            actorRef.uuid = parseUuid(uuid) //FIXME is this sensible?
            sessionActors.get(channel).put(id, actorRef)
            actorRef
        }
      case sessionActor => sessionActor
    }
  }


  private def createClientManagedActor(actorInfo: ActorInfoProtocol): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId
    val timeout = actorInfo.getTimeout
    val name = actorInfo.getTarget

    try {
      if (UNTRUSTED_MODE) throw new SecurityException(
        "Remote server is operating is untrusted mode, can not create remote actors on behalf of the remote client")

      val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                  else Class.forName(name)
      val actorRef = Actor.actorOf(clazz.asInstanceOf[Class[_ <: Actor]])
      actorRef.uuid = parseUuid(uuid)
      actorRef.id = id
      actorRef.timeout = timeout
      server.actorsByUuid.put(actorRef.uuid.toString, actorRef) // register by uuid
      actorRef
    } catch {
      case e: Throwable =>
        EventHandler notify EventHandler.Error(e, this)
        server.notifyListeners(RemoteServerError(e, server))
        throw e
    }

  }

  /**
   * Creates a new instance of the actor with name, uuid and timeout specified as arguments.
   *
   * If actor already created then just return it from the registry.
   *
   * Does not start the actor.
   */
  private def createActor(actorInfo: ActorInfoProtocol, channel: Channel): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId

    server.findActorByIdOrUuid(id, parseUuid(uuid).toString) match {
      case null => // the actor has not been registered globally. See if we have it in the session
        createSessionActor(actorInfo, channel) match {
          case null => createClientManagedActor(actorInfo) // maybe it is a client managed actor
          case sessionActor => sessionActor
        }
      case actorRef => actorRef
    }
  }

  /**
   * gets the actor from the session, or creates one if there is a factory for it
   */
  private def createTypedSessionActor(actorInfo: ActorInfoProtocol, channel: Channel):AnyRef ={
    val id = actorInfo.getId
    findTypedSessionActor(id, channel) match {
      case null =>
        server.findTypedActorFactory(id) match {
          case null => null
          case factory =>
            val newInstance = factory()
            typedSessionActors.get(channel).put(id, newInstance)
            newInstance
        }
      case sessionActor => sessionActor
    }
  }

  private def createClientManagedTypedActor(actorInfo: ActorInfoProtocol) = {
    val typedActorInfo = actorInfo.getTypedActorInfo
    val interfaceClassname = typedActorInfo.getInterface
    val targetClassname = actorInfo.getTarget
    val uuid = actorInfo.getUuid

    try {
      if (UNTRUSTED_MODE) throw new SecurityException(
        "Remote server is operating is untrusted mode, can not create remote actors on behalf of the remote client")

      val (interfaceClass, targetClass) =
        if (applicationLoader.isDefined) (applicationLoader.get.loadClass(interfaceClassname),
                                          applicationLoader.get.loadClass(targetClassname))
        else (Class.forName(interfaceClassname), Class.forName(targetClassname))

      val newInstance = TypedActor.newInstance(
        interfaceClass, targetClass.asInstanceOf[Class[_ <: TypedActor]], actorInfo.getTimeout).asInstanceOf[AnyRef]
      server.typedActors.put(parseUuid(uuid).toString, newInstance) // register by uuid
      newInstance
    } catch {
      case e: Throwable =>
        EventHandler notify EventHandler.Error(e, this)
        server.notifyListeners(RemoteServerError(e, server))
        throw e
    }
  }

  private def createTypedActor(actorInfo: ActorInfoProtocol, channel: Channel): AnyRef = {
    val uuid = actorInfo.getUuid

    server.findTypedActorByIdOrUuid(actorInfo.getId, parseUuid(uuid).toString) match {
      case null => // the actor has not been registered globally. See if we have it in the session
        createTypedSessionActor(actorInfo, channel) match {
          case null => createClientManagedTypedActor(actorInfo) //Maybe client managed actor?
          case sessionActor => sessionActor
        }
      case typedActor => typedActor
    }
  }

  private def createErrorReplyMessage(exception: Throwable, request: RemoteMessageProtocol, actorType: AkkaActorType): AkkaRemoteProtocol = {
    val actorInfo = request.getActorInfo
    val messageBuilder = RemoteActorSerialization.createRemoteMessageProtocolBuilder(
      None,
      Right(request.getUuid),
      actorInfo.getId,
      actorInfo.getTarget,
      actorInfo.getTimeout,
      Right(exception),
      true,
      None,
      None,
      actorType,
      None)
    if (request.hasSupervisorUuid) messageBuilder.setSupervisorUuid(request.getSupervisorUuid)
    RemoteEncoder.encode(messageBuilder.build)
  }

  private def authenticateRemoteClient(request: RemoteMessageProtocol, ctx: ChannelHandlerContext) = {
    val attachment = ctx.getAttachment
    if ((attachment ne null) &&
        attachment.isInstanceOf[String] &&
        attachment.asInstanceOf[String] == CHANNEL_INIT) { // is first time around, channel initialization
      ctx.setAttachment(null)
      val clientAddress = ctx.getChannel.getRemoteAddress.toString
      if (!request.hasCookie) throw new SecurityException(
        "The remote client [" + clientAddress + "] does not have a secure cookie.")
      if (!(request.getCookie == SECURE_COOKIE.get)) throw new SecurityException(
        "The remote client [" + clientAddress + "] secure cookie is not the same as remote server secure cookie")
    }
  }

  protected def parseUuid(protocol: UuidProtocol): Uuid = uuidFrom(protocol.getHigh,protocol.getLow)
}

class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
  protected val guard = new ReadWriteGuard
  protected val open  = new AtomicBoolean(true)

  override def add(channel: Channel): Boolean = guard withReadGuard {
    if(open.get) {
      super.add(channel)
    } else {
      channel.close
      false
    }
  }

  override def close(): ChannelGroupFuture = guard withWriteGuard {
    if (open.getAndSet(false)) {
      super.close
    } else {
      throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    }
  }
}