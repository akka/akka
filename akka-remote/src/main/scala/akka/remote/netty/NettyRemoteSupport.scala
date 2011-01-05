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
import akka.actor. {Index, ActorInitializationException, LocalActorRef, newUuid, ActorRegistry, Actor, RemoteActorRef, TypedActor, ActorRef, IllegalActorStateException, RemoteActorSystemMessage, uuidFrom, Uuid, Exit, LifeCycleMessage, ActorType => AkkaActorType}
import akka.AkkaException
import akka.actor.Actor._
import akka.util._
import akka.remote.{MessageSerializer, RemoteClientSettings, RemoteServerSettings}

import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{DefaultChannelGroup,ChannelGroup}
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.bootstrap.{ServerBootstrap,ClientBootstrap}
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.compression.{ ZlibDecoder, ZlibEncoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.ReadTimeoutHandler
import org.jboss.netty.util.{ TimerTask, Timeout, HashedWheelTimer }
import org.jboss.netty.handler.ssl.SslHandler

import java.net.{ SocketAddress, InetSocketAddress }
import java.util.concurrent.{ TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap, ConcurrentSkipListSet }
import scala.collection.mutable.{ HashMap }
import scala.reflect.BeanProperty
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic. {AtomicReference, AtomicLong, AtomicBoolean}

trait NettyRemoteClientModule extends RemoteClientModule { self: ListenerManagement with Logging =>
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
        case Some(client) => client
        case None =>
          lock.readLock.unlock
          lock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(key) match { //Recheck for addition, race between upgrades
                case Some(client) => client //If already populated by other writer
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
      case Some(client) => client.shutdown
      case None => false
    }
  }

  def restartClientConnection(address: InetSocketAddress): Boolean = lock withReadGuard {
    remoteClients.get(Address(address)) match {
      case Some(client) => client.connect(reconnectIfAlreadyConnected = true)
      case None => false
    }
  }

  private[akka] def registerSupervisorForActor(actorRef: ActorRef): ActorRef =
    withClientFor(actorRef.homeAddress.get, None)(_.registerSupervisorForActor(actorRef))

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef): ActorRef = lock withReadGuard {
    remoteClients.get(Address(actorRef.homeAddress.get)) match {
      case Some(client) => client.deregisterSupervisorForActor(actorRef)
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
  val remoteAddress: InetSocketAddress) extends Logging {

  val name = this.getClass.getSimpleName + "@" + remoteAddress.getHostName + "::" + remoteAddress.getPort

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
    log.slf4j.debug("sending message: {} has future {}", request, senderFuture)
    if (isRunning) {
      if (request.getOneWay) {
        currentChannel.write(request).addListener(new ChannelFutureListener {
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

          currentChannel.write(request).addListener(new ChannelFutureListener {
            def operationComplete(future: ChannelFuture) {
              if (future.isCancelled) {
                //We don't care about that right now
              } else if (!future.isSuccess) {
                notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
              } else {
                val futureUuid = uuidFrom(request.getUuid.getHigh, request.getUuid.getLow)
                futures.put(futureUuid, futureResult)
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
      openChannels = new DefaultChannelGroup(classOf[RemoteClient].getName)
      timer = new HashedWheelTimer

      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, futures, supervisors, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      log.slf4j.info("Starting remote client connection to [{}]", remoteAddress)

      // Wait until the connection attempt succeeds or fails.
      connection = bootstrap.connect(remoteAddress)
      openChannels.add(connection.awaitUninterruptibly.getChannel)

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        log.slf4j.error("Remote client connection to [{}] has failed", remoteAddress)
        log.slf4j.debug("Remote client connection failed", connection.getCause)
        false
      } else {
        notifyListeners(RemoteClientStarted(module, remoteAddress))
        true
      }
    } match {
      case true => true
      case false if reconnectIfAlreadyConnected =>
        isAuthenticated.set(false)
        log.slf4j.debug("Remote client reconnecting to [{}]", remoteAddress)
        openChannels.remove(connection.getChannel)
        connection.getChannel.close
        connection = bootstrap.connect(remoteAddress)
        openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.
        if (!connection.isSuccess) {
          notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
          log.slf4j.error("Reconnection to [{}] has failed", remoteAddress)
          log.slf4j.debug("Reconnection failed", connection.getCause)
          false
        } else true
      case false => false
    }
  }

  def shutdown = runSwitch switchOff {
    log.slf4j.info("Shutting down {}", name)
    notifyListeners(RemoteClientShutdown(module, remoteAddress))
    timer.stop
    timer = null
    openChannels.close.awaitUninterruptibly
    openChannels = null
    bootstrap.releaseExternalResources
    bootstrap = null
    connection = null
    log.slf4j.info("{} has been shut down", name)
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = RECONNECTION_TIME_WINDOW - (System.currentTimeMillis - reconnectionTimeWindowStart)
      if (timeLeft > 0) {
        log.slf4j.info("Will try to reconnect to remote server for another [{}] milliseconds", timeLeft)
        true
      } else false
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
  remoteAddress: SocketAddress,
  timer: HashedWheelTimer,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    def join(ch: ChannelHandler*) = Array[ChannelHandler](ch: _*)

    lazy val engine = {
      val e = RemoteServerSslContext.client.createSSLEngine()
      e.setEnabledCipherSuites(e.getSupportedCipherSuites) //TODO is this sensible?
      e.setUseClientMode(true)
      e
    }

    val ssl = if (RemoteServerSettings.SECURE) join(new SslHandler(engine)) else join()
    val timeout = new ReadTimeoutHandler(timer, RemoteClientSettings.READ_TIMEOUT.toMillis.toInt)
    val lenDec = new LengthFieldBasedFrameDecoder(RemoteClientSettings.MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteMessageProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec) = RemoteServerSettings.COMPRESSION_SCHEME match {
      case "zlib" => (join(new ZlibEncoder(RemoteServerSettings.ZLIB_COMPRESSION_LEVEL)), join(new ZlibDecoder))
      case _ => (join(), join())
    }

    val remoteClient = new ActiveRemoteClientHandler(name, futures, supervisors, bootstrap, remoteAddress, timer, client)
    val stages = ssl ++ join(timeout) ++ dec ++ join(lenDec, protobufDec) ++ enc ++ join(lenPrep, protobufEnc, remoteClient)
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
  val remoteAddress: SocketAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler with Logging {

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] &&
      event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.slf4j.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case reply: RemoteMessageProtocol =>
          val replyUuid = uuidFrom(reply.getActorInfo.getUuid.getHigh, reply.getActorInfo.getUuid.getLow)
          log.slf4j.debug("Remote client received RemoteMessageProtocol[\n{}]",reply)
          log.slf4j.debug("Trying to map back to future: {}",replyUuid)
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
      case e: Exception =>
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
        log.slf4j.error("Unexpected exception in remote client handler", e)
        throw e
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = client.runSwitch ifOn {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) = {
          client.openChannels.remove(event.getChannel)
          client.connect(reconnectIfAlreadyConnected = true)
        }
      }, RemoteClientSettings.RECONNECT_DELAY.toMillis, TimeUnit.MILLISECONDS)
    } else spawn { client.shutdown }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    def connect = {
      client.notifyListeners(RemoteClientConnected(client.module, client.remoteAddress))
      log.slf4j.debug("Remote client connected to [{}]", ctx.getChannel.getRemoteAddress)
      client.resetReconnectionTimeWindow
    }

    if (RemoteServerSettings.SECURE) {
      val sslHandler: SslHandler = ctx.getPipeline.get(classOf[SslHandler])
      sslHandler.handshake.addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isSuccess) connect
          else throw new RemoteClientException("Could not establish SSL handshake", client.module, client.remoteAddress)
        }
      })
    } else connect
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.module, client.remoteAddress))
    log.slf4j.debug("Remote client disconnected from [{}]", ctx.getChannel.getRemoteAddress)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    client.notifyListeners(RemoteClientError(event.getCause, client.module, client.remoteAddress))
    if (event.getCause ne null)
      log.slf4j.error("Unexpected exception from downstream in remote client", event.getCause)
    else
      log.slf4j.error("Unexpected exception from downstream in remote client: {}", event)

    event.getChannel.close
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
      case problem =>
        log.debug("Couldn't parse exception returned from RemoteServer",problem)
        log.warn("Couldn't create instance of {} with message {}, returning UnparsableException",classname, exception.getMessage)
        UnparsableException(classname, exception.getMessage)
    }
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport extends RemoteSupport with NettyRemoteServerModule with NettyRemoteClientModule {
  //Needed for remote testing and switching on/off under run
  private[akka] val optimizeLocal = new AtomicBoolean(true)

  def optimizeLocalScoped_?() = optimizeLocal.get

  protected[akka] def actorFor(serviceId: String, className: String, timeout: Long, host: String, port: Int, loader: Option[ClassLoader]): ActorRef = {
    if (optimizeLocalScoped_?) {
      val home = this.address
      if (host == home.getHostName && port == home.getPort) {//TODO: switch to InetSocketAddress.equals?
        val localRef = findActorByIdOrUuid(serviceId,serviceId)
        if (localRef ne null) return localRef //Code significantly simpler with the return statement
      }
    }

    RemoteActorRef(serviceId, className, host, port, timeout, loader)
  }

  def clientManagedActorOf(factory: () => Actor, host: String, port: Int): ActorRef = {

    if (optimizeLocalScoped_?) {
      val home = this.address
      if (host == home.getHostName && port == home.getPort)//TODO: switch to InetSocketAddress.equals?
        return new LocalActorRef(factory, None) // Code is much simpler with return
    }

    val ref = new LocalActorRef(factory, Some(new InetSocketAddress(host, port)))
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
  private val openChannels: ChannelGroup = new DefaultChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, loader, serverModule)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", RemoteServerSettings.CONNECTION_TIMEOUT_MILLIS.toMillis)

  openChannels.add(bootstrap.bind(address))
  serverModule.notifyListeners(RemoteServerStarted(serverModule))

  def shutdown {
    try {
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources
      serverModule.notifyListeners(RemoteServerShutdown(serverModule))
    } catch {
      case e: java.nio.channels.ClosedChannelException =>  {}
      case e => serverModule.log.slf4j.warn("Could not close remote server channel in a graceful way")
    }
  }
}

trait NettyRemoteServerModule extends RemoteServerModule { self: RemoteModule =>
  import RemoteServerSettings._

  private[akka] val currentServer = new AtomicReference[Option[NettyRemoteServer]](None)
  def address = currentServer.get match {
    case Some(s) => s.address
    case None    => ReflectiveAccess.Remote.configDefaultAddress
  }

  def name = currentServer.get match {
    case Some(s) => s.name
    case None    =>
       val a = ReflectiveAccess.Remote.configDefaultAddress
      "NettyRemoteServer@" + a.getHostName + ":" + a.getPort
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(_hostname: String, _port: Int, loader: Option[ClassLoader] = None): RemoteServerModule = guard withGuard {
    try {
      _isRunning switchOn {
        log.slf4j.debug("Starting up remote server on {}:{}",_hostname, _port)
        currentServer.set(Some(new NettyRemoteServer(this, _hostname, _port, loader)))
      }
    } catch {
      case e =>
        log.slf4j.error("Could not start up remote server", e)
        notifyListeners(RemoteServerError(e, this))
    }
    this
  }

  def shutdownServerModule = guard withGuard {
    _isRunning switchOff {
      currentServer.getAndSet(None) foreach {
        instance =>
        log.slf4j.debug("Shutting down remote server on {}:{}",instance.host, instance.port)
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
    log.slf4j.debug("Registering server side remote typed actor [{}] with id [{}]", typedActor.getClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) registerTypedActor(id.substring(UUID_PREFIX.length), typedActor, typedActorsByUuid)
    else registerTypedActor(id, typedActor, typedActors)
  }

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedPerSessionActor(id: String, factory: => AnyRef): Unit = guard withGuard {
    log.slf4j.debug("Registering server side typed remote session actor with id [{}]", id)
    registerTypedPerSessionActor(id, () => factory, typedActorsFactories)
  }

  /**
   * Register Remote Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef): Unit = guard withGuard {
    log.slf4j.debug("Registering server side remote actor [{}] with id [{}]", actorRef.actorClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) register(id.substring(UUID_PREFIX.length), actorRef, actorsByUuid)
    else register(id, actorRef, actors)
  }

  def registerByUuid(actorRef: ActorRef): Unit = guard withGuard {
    log.slf4j.debug("Registering remote actor {} to it's uuid {}", actorRef, actorRef.uuid)
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
    log.slf4j.debug("Registering server side remote session actor with id [{}]", id)
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
      log.slf4j.debug("Unregistering server side remote actor [{}] with id [{}:{}]", Array[AnyRef](actorRef.actorClass.getName, actorRef.id, actorRef.uuid))
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
      log.slf4j.info("Unregistering server side remote actor with id [{}]", id)
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
      log.slf4j.info("Unregistering server side remote session actor with id [{}]", id)
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
      log.slf4j.info("Unregistering server side remote typed actor with id [{}]", id)
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

object RemoteServerSslContext {
  import javax.net.ssl.SSLContext

  val (client, server) = {
    val protocol  = "TLS"
    //val algorithm = Option(Security.getProperty("ssl.KeyManagerFactory.algorithm")).getOrElse("SunX509")
    //val store = KeyStore.getInstance("JKS")
    val s = SSLContext.getInstance(protocol)
    s.init(null, null, null)
    val c = SSLContext.getInstance(protocol)
    c.init(null, null, null)
    (c, s)
  }
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
    def join(ch: ChannelHandler*) = Array[ChannelHandler](ch:_*)

    lazy val engine = {
      val e = RemoteServerSslContext.server.createSSLEngine()
      e.setEnabledCipherSuites(e.getSupportedCipherSuites) //TODO is this sensible?
      e.setUseClientMode(false)
      e
    }

    val ssl         = if(SECURE) join(new SslHandler(engine)) else join()
    val lenDec      = new LengthFieldBasedFrameDecoder(MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteMessageProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec)  = COMPRESSION_SCHEME match {
      case "zlib"  => (join(new ZlibEncoder(ZLIB_COMPRESSION_LEVEL)), join(new ZlibDecoder))
      case       _ => (join(), join())
    }

    val remoteServer = new RemoteServerHandler(name, openChannels, loader, server)
    val stages = ssl ++ dec ++ join(lenDec, protobufDec) ++ enc ++ join(lenPrep, protobufEnc, remoteServer)
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
    val server: NettyRemoteServerModule) extends SimpleChannelUpstreamHandler with Logging {
  import RemoteServerSettings._
  val CHANNEL_INIT    = "channel-init".intern

  applicationLoader.foreach(MessageSerializer.setClassLoader(_)) //TODO: REVISIT: THIS FEELS A BIT DODGY

  val sessionActors = new ChannelLocal[ConcurrentHashMap[String, ActorRef]]()
  val typedSessionActors = new ChannelLocal[ConcurrentHashMap[String, AnyRef]]()

  //Writes the specified message to the specified channel and propagates write errors to listeners
  private def write(channel: Channel, message: AnyRef): Unit = {
    channel.write(message).addListener(
      new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isCancelled) {
            //Not interesting at the moment
          } else if (!future.isSuccess) {
            val socketAddress = future.getChannel.getRemoteAddress match {
              case i: InetSocketAddress => i
              case _ => null
            }
            server.notifyListeners(RemoteServerWriteFailed(message, future.getCause, server, socketAddress))
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
    log.slf4j.debug("Remote client [{}] connected to [{}]", clientAddress, server.name)
    if (SECURE) {
      val sslHandler: SslHandler = ctx.getPipeline.get(classOf[SslHandler])
      // Begin handshake.
      sslHandler.handshake().addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isSuccess) {
            openChannels.add(future.getChannel)
            server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
          } else future.getChannel.close
        }
      })
    } else {
      server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
    }
    if (REQUIRE_COOKIE) ctx.setAttachment(CHANNEL_INIT) // signal that this is channel initialization, which will need authentication
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    import scala.collection.JavaConversions.asScalaIterable
    val clientAddress = getClientAddress(ctx)
    log.slf4j.debug("Remote client [{}] disconnected from [{}]", clientAddress, server.name)

    // stop all session actors
    for (map <- Option(sessionActors.remove(event.getChannel));
         actor <- asScalaIterable(map.values)) {
         try { actor.stop } catch { case e: Exception => log.slf4j.warn("Couldn't stop {}",actor,e) }
    }
    // stop all typed session actors
    for (map <- Option(typedSessionActors.remove(event.getChannel));
         actor <- asScalaIterable(map.values)) {
         try { TypedActor.stop(actor) } catch { case e: Exception => log.slf4j.warn("Couldn't stop {}",actor,e) }
    }

    server.notifyListeners(RemoteServerClientDisconnected(server, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    log.slf4j.debug("Remote client [{}] channel closed from [{}]", clientAddress, server.name)
    server.notifyListeners(RemoteServerClientClosed(server, clientAddress))
  }

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] && event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.slf4j.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = event.getMessage match {
    case null => throw new IllegalActorStateException("Message in remote MessageEvent is null: " + event)
    case requestProtocol: RemoteMessageProtocol =>
      if (REQUIRE_COOKIE) authenticateRemoteClient(requestProtocol, ctx)
      handleRemoteMessageProtocol(requestProtocol, event.getChannel)
    case _ => //ignore
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.slf4j.error("Unexpected exception from remote downstream", event.getCause)
    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def getClientAddress(ctx: ChannelHandlerContext): Option[InetSocketAddress] =
    ctx.getChannel.getRemoteAddress match {
      case inet: InetSocketAddress => Some(inet)
      case _ => None
    }

  private def handleRemoteMessageProtocol(request: RemoteMessageProtocol, channel: Channel) = {
    log.slf4j.debug("Received RemoteMessageProtocol[\n{}]",request)
    request.getActorInfo.getActorType match {
      case SCALA_ACTOR => dispatchToActor(request, channel)
      case TYPED_ACTOR => dispatchToTypedActor(request, channel)
      case JAVA_ACTOR  => throw new IllegalActorStateException("ActorType JAVA_ACTOR is currently not supported")
      case other       => throw new IllegalActorStateException("Unknown ActorType [" + other + "]")
    }
  }

  private def dispatchToActor(request: RemoteMessageProtocol, channel: Channel) {
    val actorInfo = request.getActorInfo
    log.slf4j.debug("Dispatching to remote actor [{}:{}]", actorInfo.getTarget, actorInfo.getUuid)

    val actorRef =
      try { createActor(actorInfo, channel).start } catch {
        case e: SecurityException =>
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
              log.slf4j.debug("Future was completed, now flushing to remote!")
              val result = f.result
              val exception = f.exception

              if (exception.isDefined) {
                log.slf4j.debug("Returning exception from actor invocation [{}]",exception.get.getClass)
                write(channel, createErrorReplyMessage(exception.get, request, AkkaActorType.ScalaActor))
              }
              else if (result.isDefined) {
                log.slf4j.debug("Returning result from actor invocation [{}]",result.get)
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

                write(channel, messageBuilder.build)
              }
            }
          )
       ))
    }
  }

  private def dispatchToTypedActor(request: RemoteMessageProtocol, channel: Channel) = {
    val actorInfo = request.getActorInfo
    val typedActorInfo = actorInfo.getTypedActorInfo
    log.slf4j.debug("Dispatching to remote typed actor [{} :: {}]", typedActorInfo.getMethod, typedActorInfo.getInterface)

    val typedActor = createTypedActor(actorInfo, channel)
    val args = MessageSerializer.deserialize(request.getMessage).asInstanceOf[Array[AnyRef]].toList
    val argClasses = args.map(_.getClass)

    try {
      val messageReceiver = typedActor.getClass.getDeclaredMethod(typedActorInfo.getMethod, argClasses: _*)
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

          write(channel, messageBuilder.build)
          log.slf4j.debug("Returning result from remote typed actor invocation [{}]", result)
        } catch {
          case e: Throwable => server.notifyListeners(RemoteServerError(e, server))
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
        write(channel, createErrorReplyMessage(e.getCause, request, AkkaActorType.TypedActor))
        server.notifyListeners(RemoteServerError(e, server))
      case e: Throwable =>
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

      log.slf4j.info("Creating a new client-managed remote actor [{}:{}]", name, uuid)
      val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                  else Class.forName(name)
      val actorRef = Actor.actorOf(clazz.asInstanceOf[Class[_ <: Actor]])
      actorRef.uuid = parseUuid(uuid)
      actorRef.id = id
      actorRef.timeout = timeout
      server.actorsByUuid.put(actorRef.uuid.toString, actorRef) // register by uuid
      actorRef
    } catch {
      case e =>
        log.slf4j.error("Could not create remote actor instance", e)
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

      log.slf4j.info("Creating a new remote typed actor:\n\t[{} :: {}]", interfaceClassname, targetClassname)

      val (interfaceClass, targetClass) =
        if (applicationLoader.isDefined) (applicationLoader.get.loadClass(interfaceClassname),
                                          applicationLoader.get.loadClass(targetClassname))
        else (Class.forName(interfaceClassname), Class.forName(targetClassname))

      val newInstance = TypedActor.newInstance(
        interfaceClass, targetClass.asInstanceOf[Class[_ <: TypedActor]], actorInfo.getTimeout).asInstanceOf[AnyRef]
      server.typedActors.put(parseUuid(uuid).toString, newInstance) // register by uuid
      newInstance
    } catch {
      case e =>
        log.slf4j.error("Could not create remote typed actor instance", e)
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

  private def createErrorReplyMessage(exception: Throwable, request: RemoteMessageProtocol, actorType: AkkaActorType): RemoteMessageProtocol = {
    val actorInfo = request.getActorInfo
    log.slf4j.error("Could not invoke remote actor [{}]", actorInfo.getTarget)
    log.slf4j.debug("Could not invoke remote actor", exception)
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
    messageBuilder.build
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
      log.slf4j.info("Remote client [{}] successfully authenticated using secure cookie", clientAddress)
    }
  }

  protected def parseUuid(protocol: UuidProtocol): Uuid = uuidFrom(protocol.getHigh,protocol.getLow)
}
