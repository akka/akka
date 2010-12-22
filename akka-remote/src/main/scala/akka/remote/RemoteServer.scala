/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.util.{Map => JMap}

import akka.actor.Actor._
import akka.actor.{Actor, TypedActor, ActorRef, IllegalActorStateException, RemoteActorSystemMessage, uuidFrom, Uuid, ActorRegistry, LifeCycleMessage, ActorType => AkkaActorType}
import akka.util._
import akka.remote.protocol.RemoteProtocol._
import akka.remote.protocol.RemoteProtocol.ActorType._
import akka.config.Config._
import akka.config.ConfigurationException
import akka.serialization.RemoteActorSerialization
import akka.serialization.RemoteActorSerialization._

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{DefaultChannelGroup, ChannelGroup}
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.codec.compression.{ZlibEncoder, ZlibDecoder}
import org.jboss.netty.handler.ssl.SslHandler

import scala.collection.mutable.Map
import scala.reflect.BeanProperty
import akka.dispatch. {Future, DefaultCompletableFuture, CompletableFuture}
import akka.japi.Creator

/**
 * Use this object if you need a single remote server on a specific node.
 *
 * <pre>
 * // takes hostname and port from 'akka.conf'
 * RemoteNode.start
 * </pre>
 *
 * <pre>
 * RemoteNode.start(hostname, port)
 * </pre>
 *
 * You can specify the class loader to use to load the remote actors.
 * <pre>
 * RemoteNode.start(hostname, port, classLoader)
 * </pre>
 *
 * If you need to create more than one, then you can use the RemoteServer:
 *
 * <pre>
 * val server = new RemoteServer
 * server.start(hostname, port)
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteNode extends RemoteServer

/**
 * For internal use only. Holds configuration variables, remote actors, remote typed actors and remote servers.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteServer {
  val isRemotingEnabled = config.getList("akka.enabled-modules").exists(_ == "remote")

  val UUID_PREFIX        = "uuid:"
  val MESSAGE_FRAME_SIZE = config.getInt("akka.remote.server.message-frame-size", 1048576)
  val SECURE_COOKIE      = config.getString("akka.remote.secure-cookie")
  val REQUIRE_COOKIE     = {
    val requireCookie = config.getBool("akka.remote.server.require-cookie", true)
    if (isRemotingEnabled && requireCookie && RemoteServer.SECURE_COOKIE.isEmpty) throw new ConfigurationException(
      "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
    requireCookie
  }

  val UNTRUSTED_MODE            = config.getBool("akka.remote.server.untrusted-mode", false)
  val HOSTNAME                  = config.getString("akka.remote.server.hostname", "localhost")
  val PORT                      = config.getInt("akka.remote.server.port", 2552)
  val CONNECTION_TIMEOUT_MILLIS = Duration(config.getInt("akka.remote.server.connection-timeout", 1), TIME_UNIT)
  val COMPRESSION_SCHEME        = config.getString("akka.remote.compression-scheme", "zlib")
  val ZLIB_COMPRESSION_LEVEL    = {
    val level = config.getInt("akka.remote.zlib-compression-level", 6)
    if (level < 1 && level > 9) throw new IllegalArgumentException(
      "zlib compression level has to be within 1-9, with 1 being fastest and 9 being the most compressed")
    level
  }

  val SECURE = {
    /*if (config.getBool("akka.remote.ssl.service",false)) {
      val properties = List(
        ("key-store-type"  , "keyStoreType"),
        ("key-store"       , "keyStore"),
        ("key-store-pass"  , "keyStorePassword"),
        ("trust-store-type", "trustStoreType"),
        ("trust-store"     , "trustStore"),
        ("trust-store-pass", "trustStorePassword")
        ).map(x => ("akka.remote.ssl." + x._1, "javax.net.ssl." + x._2))

      // If property is not set, and we have a value from our akka.conf, use that value
      for {
        p <- properties if System.getProperty(p._2) eq null
        c <- config.getString(p._1)
      } System.setProperty(p._2, c)

      if (config.getBool("akka.remote.ssl.debug", false)) System.setProperty("javax.net.debug","ssl")
      true
    } else */false
  }

  private val guard = new ReadWriteGuard
  private val remoteServers =   Map[Address, RemoteServer]()

  def serverFor(address: InetSocketAddress): Option[RemoteServer] =
    serverFor(address.getHostName, address.getPort)

  def serverFor(hostname: String, port: Int): Option[RemoteServer] = guard.withReadGuard {
    remoteServers.get(Address(hostname, port))
  }

  private[akka] def getOrCreateServer(address: InetSocketAddress): RemoteServer = guard.withWriteGuard {
    serverFor(address) match {
      case Some(server) => server
      case None         => (new RemoteServer).start(address)
    }
  }

  private[akka] def register(hostname: String, port: Int, server: RemoteServer) = guard.withWriteGuard {
    remoteServers.put(Address(hostname, port), server)
  }

  private[akka] def unregister(hostname: String, port: Int) = guard.withWriteGuard {
    remoteServers.remove(Address(hostname, port))
  }

  /**
   * Used in ReflectiveAccess
   */
  private[akka] def registerActor(address: InetSocketAddress, actorRef: ActorRef) {
    serverFor(address) foreach { _.register(actorRef) }
  }

  /**
   * Used in Reflective
   */
  private[akka] def registerTypedActor(address: InetSocketAddress, implementationClassName: String, proxy: AnyRef) {
    serverFor(address) foreach { _.registerTypedActor(implementationClassName,proxy)}
  }
}

/**
 *  Life-cycle events for RemoteServer.
 */
sealed trait RemoteServerLifeCycleEvent
case class RemoteServerStarted(
  @BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerShutdown(
  @BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerClientConnected(
  @BeanProperty val server: RemoteServer,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientDisconnected(
  @BeanProperty val server: RemoteServer,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientClosed(
  @BeanProperty val server: RemoteServer,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent

/**
 * Use this class if you need a more than one remote server on a specific node.
 *
 * <pre>
 * val server = new RemoteServer
 * server.start
 * </pre>
 *
 * If you need to create more than one, then you can use the RemoteServer:
 *
 * <pre>
 * RemoteNode.start
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServer extends Logging with ListenerManagement {
  import RemoteServer._
  def name = "RemoteServer@" + hostname + ":" + port

  private[akka] var address  = Address(RemoteServer.HOSTNAME,RemoteServer.PORT)

  def hostname = address.hostname
  def port     = address.port

  @volatile private var _isRunning = false

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultChannelGroup("akka-remote-server")

  def isRunning = _isRunning

  def start: RemoteServer =
    start(hostname, port, None)

  def start(loader: ClassLoader): RemoteServer =
    start(hostname, port, Some(loader))

  def start(address: InetSocketAddress): RemoteServer =
    start(address.getHostName, address.getPort, None)

  def start(address: InetSocketAddress, loader: ClassLoader): RemoteServer =
    start(address.getHostName, address.getPort, Some(loader))

  def start(_hostname: String, _port: Int): RemoteServer =
    start(_hostname, _port, None)

  private def start(_hostname: String, _port: Int, loader: ClassLoader): RemoteServer =
    start(_hostname, _port, Some(loader))

  private def start(_hostname: String, _port: Int, loader: Option[ClassLoader]): RemoteServer = synchronized {
    try {
      if (!_isRunning) {
        address = Address(_hostname,_port)
        log.slf4j.info("Starting remote server at [{}:{}]", hostname, port)
        RemoteServer.register(hostname, port, this)

        val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, loader, this)
        bootstrap.setPipelineFactory(pipelineFactory)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.setOption("child.reuseAddress", true)
        bootstrap.setOption("child.connectTimeoutMillis", RemoteServer.CONNECTION_TIMEOUT_MILLIS.toMillis)

        openChannels.add(bootstrap.bind(new InetSocketAddress(hostname, port)))
        _isRunning = true
        notifyListeners(RemoteServerStarted(this))
      }
    } catch {
      case e =>
        log.slf4j.error("Could not start up remote server", e)
        notifyListeners(RemoteServerError(e, this))
    }
    this
  }

  def shutdown = synchronized {
    if (_isRunning) {
      try {
        RemoteServer.unregister(hostname, port)
        openChannels.disconnect
        openChannels.close.awaitUninterruptibly
        bootstrap.releaseExternalResources
        notifyListeners(RemoteServerShutdown(this))
      } catch {
        case e: java.nio.channels.ClosedChannelException =>  {}
        case e => log.slf4j.warn("Could not close remote server channel in a graceful way")
      }
    }
  }

  /**
   * Register typed actor by interface name.
   */
  def registerTypedActor(intfClass: Class[_], typedActor: AnyRef) : Unit = registerTypedActor(intfClass.getName, typedActor)

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedActor(id: String, typedActor: AnyRef): Unit = synchronized {
    log.slf4j.debug("Registering server side remote typed actor [{}] with id [{}]", typedActor.getClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) registerTypedActor(id.substring(UUID_PREFIX.length), typedActor, typedActorsByUuid)
    else registerTypedActor(id, typedActor, typedActors)
  }

  /**
   * Register typed actor by interface name.
   */
  def registerTypedPerSessionActor(intfClass: Class[_], factory: => AnyRef) : Unit = registerTypedActor(intfClass.getName, factory)

  /**
   * Register typed actor by interface name.
   * Java API
   */
  def registerTypedPerSessionActor(intfClass: Class[_], factory: Creator[AnyRef]) : Unit = registerTypedActor(intfClass.getName, factory)

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedPerSessionActor(id: String, factory: => AnyRef): Unit = synchronized {
    log.slf4j.debug("Registering server side typed remote session actor with id [{}]", id)
    registerTypedPerSessionActor(id, () => factory, typedActorsFactories)
  }

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   * Java API
   */
  def registerTypedPerSessionActor(id: String, factory: Creator[AnyRef]): Unit = synchronized {
    log.slf4j.debug("Registering server side typed remote session actor with id [{}]", id)
    registerTypedPerSessionActor(id, factory.create _, typedActorsFactories)
  }

  /**
   * Register Remote Actor by the Actor's 'id' field. It starts the Actor if it is not started already.
   */
  def register(actorRef: ActorRef): Unit = register(actorRef.id, actorRef)

  /**
   * Register Remote Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef): Unit = synchronized {
    log.slf4j.debug("Registering server side remote actor [{}] with id [{}]", actorRef.actorClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) register(id.substring(UUID_PREFIX.length), actorRef, actorsByUuid)
    else register(id, actorRef, actors)
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

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   * Java API
   */
  def registerPerSession(id: String, factory: Creator[ActorRef]): Unit = synchronized {
    log.slf4j.debug("Registering server side remote session actor with id [{}]", id)
    registerPerSession(id, factory.create _, actorsFactories)
  }

  private def register[Key](id: Key, actorRef: ActorRef, registry: ConcurrentHashMap[Key, ActorRef]) {
    if (_isRunning) {
      registry.put(id, actorRef) //TODO change to putIfAbsent
      if (!actorRef.isRunning) actorRef.start
    }
  }

  private def registerPerSession[Key](id: Key, factory: () => ActorRef, registry: ConcurrentHashMap[Key,() => ActorRef]) {
    if (_isRunning)
      registry.put(id, factory) //TODO change to putIfAbsent
  }

  private def registerTypedActor[Key](id: Key, typedActor: AnyRef, registry: ConcurrentHashMap[Key, AnyRef]) {
    if (_isRunning)
      registry.put(id, typedActor) //TODO change to putIfAbsent
  }

  private def registerTypedPerSessionActor[Key](id: Key, factory: () => AnyRef, registry: ConcurrentHashMap[Key,() => AnyRef]) {
    if (_isRunning)
      registry.put(id, factory) //TODO change to putIfAbsent
  }

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef):Unit = synchronized {
    if (_isRunning) {
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
  def unregister(id: String):Unit = synchronized {
    if (_isRunning) {
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
  def unregisterPerSession(id: String):Unit = {
    if (_isRunning) {
      log.slf4j.info("Unregistering server side remote session actor with id [{}]", id)
      actorsFactories.remove(id)
    }
  }

  /**
   * Unregister Remote Typed Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterTypedActor(id: String):Unit = synchronized {
    if (_isRunning) {
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
 def unregisterTypedPerSessionActor(id: String):Unit = {
   if (_isRunning) {
     typedActorsFactories.remove(id)
   }
 }

  protected override def manageLifeCycleOfListeners = false

  protected[akka] override def notifyListeners(message: => Any): Unit = super.notifyListeners(message)


  private[akka] def actors            = ActorRegistry.actors(address)
  private[akka] def actorsByUuid      = ActorRegistry.actorsByUuid(address)
  private[akka] def actorsFactories   = ActorRegistry.actorsFactories(address)
  private[akka] def typedActors       = ActorRegistry.typedActors(address)
  private[akka] def typedActorsByUuid = ActorRegistry.typedActorsByUuid(address)
  private[akka] def typedActorsFactories = ActorRegistry.typedActorsFactories(address)
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
    val server: RemoteServer) extends ChannelPipelineFactory {
  import RemoteServer._

  def getPipeline: ChannelPipeline = {
    def join(ch: ChannelHandler*) = Array[ChannelHandler](ch:_*)

    lazy val engine = {
      val e = RemoteServerSslContext.server.createSSLEngine()
      e.setEnabledCipherSuites(e.getSupportedCipherSuites) //TODO is this sensible?
      e.setUseClientMode(false)
      e
    }

    val ssl         = if(RemoteServer.SECURE) join(new SslHandler(engine)) else join()
    val lenDec      = new LengthFieldBasedFrameDecoder(RemoteServer.MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteMessageProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec)  = RemoteServer.COMPRESSION_SCHEME match {
      case "zlib"  => (join(new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL)), join(new ZlibDecoder))
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
    val server: RemoteServer) extends SimpleChannelUpstreamHandler with Logging {
  import RemoteServer._

  val AW_PROXY_PREFIX = "$$ProxiedByAW".intern
  val CHANNEL_INIT    = "channel-init".intern

  val sessionActors = new ChannelLocal[ConcurrentHashMap[String, ActorRef]]()
  val typedSessionActors = new ChannelLocal[ConcurrentHashMap[String, AnyRef]]()

  applicationLoader.foreach(MessageSerializer.setClassLoader(_))

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a RemoteServer.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    sessionActors.set(event.getChannel(), new ConcurrentHashMap[String, ActorRef]())
    typedSessionActors.set(event.getChannel(), new ConcurrentHashMap[String, AnyRef]())
    log.slf4j.debug("Remote client [{}] connected to [{}]", clientAddress, server.name)
    if (RemoteServer.SECURE) {
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
    } else server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
    if (RemoteServer.REQUIRE_COOKIE) ctx.setAttachment(CHANNEL_INIT) // signal that this is channel initialization, which will need authentication
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    log.slf4j.debug("Remote client [{}] disconnected from [{}]", clientAddress, server.name)
    // stop all session actors
    val channelActors = sessionActors.remove(event.getChannel)
    if (channelActors ne null) {
      val channelActorsIterator = channelActors.elements
      while (channelActorsIterator.hasMoreElements) {
        channelActorsIterator.nextElement.stop
      }
    }

    val channelTypedActors = typedSessionActors.remove(event.getChannel)
    if (channelTypedActors ne null) {
      val channelTypedActorsIterator = channelTypedActors.elements
      while (channelTypedActorsIterator.hasMoreElements) {
        TypedActor.stop(channelTypedActorsIterator.nextElement)
      }
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

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val message = event.getMessage
    if (message eq null) throw new IllegalActorStateException("Message in remote MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteMessageProtocol]) {
      val requestProtocol = message.asInstanceOf[RemoteMessageProtocol]
       if (RemoteServer.REQUIRE_COOKIE) authenticateRemoteClient(requestProtocol, ctx)
      handleRemoteMessageProtocol(requestProtocol, event.getChannel)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.slf4j.error("Unexpected exception from remote downstream", event.getCause)
    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def getClientAddress(ctx: ChannelHandlerContext): Option[InetSocketAddress] = {
    val remoteAddress = ctx.getChannel.getRemoteAddress
    if (remoteAddress.isInstanceOf[InetSocketAddress]) Some(remoteAddress.asInstanceOf[InetSocketAddress])
    else None
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
      try {
        createActor(actorInfo, channel).start
      } catch {
        case e: SecurityException =>
          channel.write(createErrorReplyMessage(e, request, AkkaActorType.ScalaActor))
          server.notifyListeners(RemoteServerError(e, server))
          return
      }

    val message = MessageSerializer.deserialize(request.getMessage)
    val sender =
      if (request.hasSender) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(request.getSender, applicationLoader))
      else None

    message match { // first match on system messages
      case RemoteActorSystemMessage.Stop =>
        if (RemoteServer.UNTRUSTED_MODE) throw new SecurityException("Remote server is operating is untrusted mode, can not stop the actor")
        else actorRef.stop
      case _: LifeCycleMessage if (RemoteServer.UNTRUSTED_MODE) =>
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
                log.slf4j.debug("Returning exception from actor invocation [{}]",exception.get)
                try {
                  channel.write(createErrorReplyMessage(exception.get, request, AkkaActorType.ScalaActor))
                } catch {
                  case e: Throwable => server.notifyListeners(RemoteServerError(e, server))
                }
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

                try {
                  channel.write(messageBuilder.build)
                } catch {
                  case e: Throwable => server.notifyListeners(RemoteServerError(e, server))
                }
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
          channel.write(messageBuilder.build)
          log.slf4j.debug("Returning result from remote typed actor invocation [{}]", result)
        } catch {
          case e: Throwable => server.notifyListeners(RemoteServerError(e, server))
        }

        messageReceiver.invoke(typedActor, args: _*) match {
          case f: Future[_] => //If it's a future, we can lift on that to defer the send to when the future is completed
            f.onComplete( future => {
              val result: Either[Any,Throwable] = if (future.exception.isDefined) Right(future.exception.get) else Left(future.result.get)
              sendResponse(result)
            })
          case other => sendResponse(Left(other))
        }
      }
    } catch {
      case e: InvocationTargetException =>
        channel.write(createErrorReplyMessage(e.getCause, request, AkkaActorType.TypedActor))
        server.notifyListeners(RemoteServerError(e, server))
      case e: Throwable =>
        channel.write(createErrorReplyMessage(e, request, AkkaActorType.TypedActor))
        server.notifyListeners(RemoteServerError(e, server))
    }
  }

  private def findActorById(id: String) : ActorRef = {
    server.actors.get(id)
  }

  private def findActorByUuid(uuid: String) : ActorRef = {
    server.actorsByUuid.get(uuid)
  }

  private def findActorFactory(id: String) : () => ActorRef = {
    server.actorsFactories.get(id)
  }

  private def findSessionActor(id: String, channel: Channel) : ActorRef = {
    val map = sessionActors.get(channel)
    if (map ne null) map.get(id)
    else null
  }

  private def findTypedActorById(id: String) : AnyRef = {
    server.typedActors.get(id)
  }

  private def findTypedActorFactory(id: String) : () => AnyRef = {
    server.typedActorsFactories.get(id)
  }

  private def findTypedSessionActor(id: String, channel: Channel) : AnyRef = {
    val map = typedSessionActors.get(channel)
    if (map ne null) map.get(id)
    else null
  }

  private def findTypedActorByUuid(uuid: String) : AnyRef = {
    server.typedActorsByUuid.get(uuid)
  }

  private def findActorByIdOrUuid(id: String, uuid: String) : ActorRef = {
    var actorRefOrNull = if (id.startsWith(UUID_PREFIX)) findActorByUuid(id.substring(UUID_PREFIX.length))
                         else findActorById(id)
    if (actorRefOrNull eq null) actorRefOrNull = findActorByUuid(uuid)
    actorRefOrNull
  }

  private def findTypedActorByIdOrUuid(id: String, uuid: String) : AnyRef = {
    var actorRefOrNull = if (id.startsWith(UUID_PREFIX)) findTypedActorByUuid(id.substring(UUID_PREFIX.length))
                         else findTypedActorById(id)
    if (actorRefOrNull eq null) actorRefOrNull = findTypedActorByUuid(uuid)
    actorRefOrNull
  }

  /**
   * gets the actor from the session, or creates one if there is a factory for it
   */
  private def createSessionActor(actorInfo: ActorInfoProtocol, channel: Channel): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId
    val sessionActorRefOrNull = findSessionActor(id, channel)
    if (sessionActorRefOrNull ne null)
      sessionActorRefOrNull
    else
    {
      // we dont have it in the session either, see if we have a factory for it
      val actorFactoryOrNull = findActorFactory(id)
      if (actorFactoryOrNull ne null) {
        val actorRef = actorFactoryOrNull()
        actorRef.uuid = uuidFrom(uuid.getHigh,uuid.getLow)
        sessionActors.get(channel).put(id, actorRef)
        actorRef
      }
      else
        null
    }
  }


  private def createClientManagedActor(actorInfo: ActorInfoProtocol): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId
    val timeout = actorInfo.getTimeout
    val name = actorInfo.getTarget

    try {
      if (RemoteServer.UNTRUSTED_MODE) throw new SecurityException(
        "Remote server is operating is untrusted mode, can not create remote actors on behalf of the remote client")

      log.slf4j.info("Creating a new remote actor [{}:{}]", name, uuid)
      val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                  else Class.forName(name)
      val actorRef = Actor.actorOf(clazz.asInstanceOf[Class[_ <: Actor]])
      actorRef.uuid = uuidFrom(uuid.getHigh,uuid.getLow)
      actorRef.id = id
      actorRef.timeout = timeout
      actorRef.remoteAddress = None
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

    val actorRefOrNull = findActorByIdOrUuid(id, uuidFrom(uuid.getHigh,uuid.getLow).toString)

    if (actorRefOrNull ne null)
      actorRefOrNull
    else
    {
      // the actor has not been registered globally. See if we have it in the session
      val sessionActorRefOrNull = createSessionActor(actorInfo, channel)
      if (sessionActorRefOrNull ne null)
        sessionActorRefOrNull
      else  // maybe it is a client managed actor
        createClientManagedActor(actorInfo)
    }
  }

  /**
   * gets the actor from the session, or creates one if there is a factory for it
   */
  private def createTypedSessionActor(actorInfo: ActorInfoProtocol, channel: Channel):AnyRef ={
    val id = actorInfo.getId
    val sessionActorRefOrNull = findTypedSessionActor(id, channel)
    if (sessionActorRefOrNull ne null)
      sessionActorRefOrNull
    else {
      val actorFactoryOrNull = findTypedActorFactory(id)
      if (actorFactoryOrNull ne null) {
        val newInstance = actorFactoryOrNull()
        typedSessionActors.get(channel).put(id, newInstance)
        newInstance
      }
      else
        null
    }

  }

  private def createClientManagedTypedActor(actorInfo: ActorInfoProtocol) = {
    val typedActorInfo = actorInfo.getTypedActorInfo
    val interfaceClassname = typedActorInfo.getInterface
    val targetClassname = actorInfo.getTarget
    val uuid = actorInfo.getUuid

    try {
      if (RemoteServer.UNTRUSTED_MODE) throw new SecurityException(
        "Remote server is operating is untrusted mode, can not create remote actors on behalf of the remote client")

      log.slf4j.info("Creating a new remote typed actor:\n\t[{} :: {}]", interfaceClassname, targetClassname)

      val (interfaceClass, targetClass) =
        if (applicationLoader.isDefined) (applicationLoader.get.loadClass(interfaceClassname),
                                          applicationLoader.get.loadClass(targetClassname))
        else (Class.forName(interfaceClassname), Class.forName(targetClassname))

      val newInstance = TypedActor.newInstance(
        interfaceClass, targetClass.asInstanceOf[Class[_ <: TypedActor]], actorInfo.getTimeout).asInstanceOf[AnyRef]
      server.typedActors.put(uuidFrom(uuid.getHigh,uuid.getLow).toString, newInstance) // register by uuid
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
    val id = actorInfo.getId

    val typedActorOrNull = findTypedActorByIdOrUuid(id, uuidFrom(uuid.getHigh,uuid.getLow).toString)
    if (typedActorOrNull ne null)
      typedActorOrNull
    else
    {
      // the actor has not been registered globally. See if we have it in the session
      val sessionActorRefOrNull = createTypedSessionActor(actorInfo, channel)
      if (sessionActorRefOrNull ne null)
        sessionActorRefOrNull
      else // maybe it is a client managed actor
        createClientManagedTypedActor(actorInfo)
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
      if (!(request.getCookie == RemoteServer.SECURE_COOKIE.get)) throw new SecurityException(
        "The remote client [" + clientAddress + "] secure cookie is not the same as remote server secure cookie")
      log.slf4j.info("Remote client [{}] successfully authenticated using secure cookie", clientAddress)
    }
  }
}
