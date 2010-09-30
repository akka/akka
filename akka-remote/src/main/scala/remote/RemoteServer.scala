/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.util.{Map => JMap}

import se.scalablesolutions.akka.actor.{
  Actor, TypedActor, ActorRef, IllegalActorStateException, RemoteActorSystemMessage, uuidFrom, Uuid, ActorRegistry}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.util._
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol.ActorType._
import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.dispatch.{DefaultCompletableFuture, CompletableFuture}
import se.scalablesolutions.akka.serialization.RemoteActorSerialization
import se.scalablesolutions.akka.serialization.RemoteActorSerialization._

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
 * For internal use only.
 * Holds configuration variables, remote actors, remote typed actors and remote servers.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteServer {
  val UUID_PREFIX = "uuid:"
  val HOSTNAME = config.getString("akka.remote.server.hostname", "localhost")
  val PORT     = config.getInt("akka.remote.server.port", 9999)

  val CONNECTION_TIMEOUT_MILLIS = Duration(config.getInt("akka.remote.server.connection-timeout", 1), TIME_UNIT)

  val COMPRESSION_SCHEME = config.getString("akka.remote.compression-scheme", "zlib")
  val ZLIB_COMPRESSION_LEVEL = {
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

  private[akka] def getOrCreateServer(address: InetSocketAddress): RemoteServer = guard.withWriteGuard {
    serverFor(address) match {
      case Some(server) => server
      case None         => (new RemoteServer).start(address)
    }
  }

  private[akka] def serverFor(address: InetSocketAddress): Option[RemoteServer] =
    serverFor(address.getHostName, address.getPort)

  private[akka] def serverFor(hostname: String, port: Int): Option[RemoteServer] = guard.withReadGuard {
    remoteServers.get(Address(hostname, port))
  }

  private[akka] def register(hostname: String, port: Int, server: RemoteServer) = guard.withWriteGuard {
    remoteServers.put(Address(hostname, port), server)
  }

  private[akka] def unregister(hostname: String, port: Int) = guard.withWriteGuard {
    remoteServers.remove(Address(hostname, port))
  }
  
}

/**
 * Life-cycle events for RemoteServer.
 */
sealed trait RemoteServerLifeCycleEvent
case class RemoteServerError(@BeanProperty val cause: Throwable, @BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerShutdown(@BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerStarted(@BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerClientConnected(@BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent
case class RemoteServerClientDisconnected(@BeanProperty val server: RemoteServer) extends RemoteServerLifeCycleEvent

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
        log.info("Starting remote server at [%s:%s]", hostname, port)
        RemoteServer.register(hostname, port, this)
        val pipelineFactory = new RemoteServerPipelineFactory(
          name, openChannels, loader, this)
        bootstrap.setPipelineFactory(pipelineFactory)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.setOption("child.reuseAddress", true)
        bootstrap.setOption("child.connectTimeoutMillis", RemoteServer.CONNECTION_TIMEOUT_MILLIS.toMillis)
        openChannels.add(bootstrap.bind(new InetSocketAddress(hostname, port)))
        _isRunning = true
        Cluster.registerLocalNode(hostname, port)
        notifyListeners(RemoteServerStarted(this))
      }
    } catch {
      case e =>
        log.error(e, "Could not start up remote server")
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
        Cluster.deregisterLocalNode(hostname, port)
        notifyListeners(RemoteServerShutdown(this))
      } catch {
        case e: java.nio.channels.ClosedChannelException =>  {}
        case e => log.warning("Could not close remote server channel in a graceful way")
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
    log.debug("Registering server side remote typed actor [%s] with id [%s]", typedActor.getClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) {
      registerTypedActor(id.substring(UUID_PREFIX.length), typedActor, typedActorsByUuid())
    } else {
      registerTypedActor(id, typedActor, typedActors())
    }
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
    log.debug("Registering server side remote actor [%s] with id [%s]", actorRef.actorClass.getName, id)
    if (id.startsWith(UUID_PREFIX)) {
      register(id.substring(UUID_PREFIX.length), actorRef, actorsByUuid())
    } else {
      register(id, actorRef, actors())
    }
  }

  private def register[Key](id: Key, actorRef: ActorRef, registry: ConcurrentHashMap[Key, ActorRef]) {
    if (_isRunning) {
      if (!registry.contains(id)) {
        if (!actorRef.isRunning) actorRef.start
        registry.put(id, actorRef)
      }
    }
  }

  private def registerTypedActor[Key](id: Key, typedActor: AnyRef, registry: ConcurrentHashMap[Key, AnyRef]) {
    if (_isRunning) {
      if (!registry.contains(id)) {
        registry.put(id, typedActor)
      }
    }
  }

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef):Unit = synchronized {
    if (_isRunning) {
      log.debug("Unregistering server side remote actor [%s] with id [%s:%s]", actorRef.actorClass.getName, actorRef.id, actorRef.uuid)
      actors().remove(actorRef.id,actorRef)
      actorsByUuid().remove(actorRef.uuid,actorRef)
    }
  }

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(id: String):Unit = synchronized {
    if (_isRunning) {
      log.info("Unregistering server side remote actor with id [%s]", id)
      if (id.startsWith(UUID_PREFIX)) {
        actorsByUuid().remove(id.substring(UUID_PREFIX.length)) 
      } else {
        val actorRef = actors() get id
        actorsByUuid().remove(actorRef.uuid,actorRef)
        actors().remove(id,actorRef)
      }
    }
  }

  /**
   * Unregister Remote Typed Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterTypedActor(id: String):Unit = synchronized {
    if (_isRunning) {
      log.info("Unregistering server side remote typed actor with id [%s]", id)
      if (id.startsWith(UUID_PREFIX)) {
        typedActorsByUuid().remove(id.substring(UUID_PREFIX.length))
      } else {
        typedActors().remove(id)
      }
    }
  }

  protected override def manageLifeCycleOfListeners = false

  protected[akka] override def notifyListeners(message: => Any): Unit = super.notifyListeners(message)

  private[akka] def actors()            = ActorRegistry.actors(address)
  private[akka] def actorsByUuid()      = ActorRegistry.actorsByUuid(address)
  private[akka] def typedActors()       = ActorRegistry.typedActors(address)
  private[akka] def typedActorsByUuid() = ActorRegistry.typedActorsByUuid(address)
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
    val lenDec      = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep     = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteRequestProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc,dec)   = RemoteServer.COMPRESSION_SCHEME match {
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

  applicationLoader.foreach(MessageSerializer.setClassLoader(_))

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a RemoteServer.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    log.debug("Remote client connected to [%s]", server.name)
    if (RemoteServer.SECURE) {
      val sslHandler: SslHandler = ctx.getPipeline.get(classOf[SslHandler])

      // Begin handshake.
      sslHandler.handshake().addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isSuccess) {
            openChannels.add(future.getChannel)
            server.notifyListeners(RemoteServerClientConnected(server))
          } else future.getChannel.close
        }
      })
    } else {
       server.notifyListeners(RemoteServerClientConnected(server))
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    log.debug("Remote client disconnected from [%s]", server.name)
    server.notifyListeners(RemoteServerClientDisconnected(server))
  }

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] &&
        event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val message = event.getMessage
    if (message eq null) throw new IllegalActorStateException("Message in remote MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteRequestProtocol]) {
      handleRemoteRequestProtocol(message.asInstanceOf[RemoteRequestProtocol], event.getChannel)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.error(event.getCause, "Unexpected exception from remote downstream")
    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def handleRemoteRequestProtocol(request: RemoteRequestProtocol, channel: Channel) = {
    log.debug("Received RemoteRequestProtocol[\n%s]", request.toString)
    request.getActorInfo.getActorType match {
      case SCALA_ACTOR => dispatchToActor(request, channel)
      case TYPED_ACTOR => dispatchToTypedActor(request, channel)
      case JAVA_ACTOR  => throw new IllegalActorStateException("ActorType JAVA_ACTOR is currently not supported")
      case other       => throw new IllegalActorStateException("Unknown ActorType [" + other + "]")
    }
  }

  private def dispatchToActor(request: RemoteRequestProtocol, channel: Channel) = {
    val actorInfo = request.getActorInfo
    log.debug("Dispatching to remote actor [%s:%s]", actorInfo.getTarget, actorInfo.getUuid)

    val actorRef = createActor(actorInfo).start

    val message = MessageSerializer.deserialize(request.getMessage)
    val sender =
      if (request.hasSender) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(request.getSender, applicationLoader))
      else None

    message match { // first match on system messages
      case RemoteActorSystemMessage.Stop => actorRef.stop
      case _ =>     // then match on user defined messages
        if (request.getIsOneWay) actorRef.!(message)(sender)
        else actorRef.postMessageToMailboxAndCreateFutureResultWithTimeout(message,request.getActorInfo.getTimeout,None,Some(
          new DefaultCompletableFuture[AnyRef](request.getActorInfo.getTimeout){
            override def onComplete(result: AnyRef) {
              log.debug("Returning result from actor invocation [%s]", result)
              val replyBuilder = RemoteReplyProtocol.newBuilder
                .setUuid(request.getUuid)
                .setMessage(MessageSerializer.serialize(result))
                .setIsSuccessful(true)
                .setIsActor(true)

              if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)

              try {
                channel.write(replyBuilder.build)
              } catch {
                case e: Throwable =>
                  server.notifyListeners(RemoteServerError(e, server))
              }
            }

            override def onCompleteException(exception: Throwable) {
              try {
                channel.write(createErrorReplyMessage(exception, request, true))
              } catch {
                case e: Throwable =>
                  server.notifyListeners(RemoteServerError(e, server))
              }
            }
        }
     ))
    }
  }

  private def dispatchToTypedActor(request: RemoteRequestProtocol, channel: Channel) = {
    val actorInfo = request.getActorInfo
    val typedActorInfo = actorInfo.getTypedActorInfo
    log.debug("Dispatching to remote typed actor [%s :: %s]", typedActorInfo.getMethod, typedActorInfo.getInterface)
    val typedActor = createTypedActor(actorInfo)

    val args = MessageSerializer.deserialize(request.getMessage).asInstanceOf[Array[AnyRef]].toList
    val argClasses = args.map(_.getClass)

    try {
      val messageReceiver = typedActor.getClass.getDeclaredMethod(typedActorInfo.getMethod, argClasses: _*)
      if (request.getIsOneWay) messageReceiver.invoke(typedActor, args: _*)
      else {
        val result = messageReceiver.invoke(typedActor, args: _*)
        log.debug("Returning result from remote typed actor invocation [%s]", result)
        val replyBuilder = RemoteReplyProtocol.newBuilder
            .setUuid(request.getUuid)
            .setMessage(MessageSerializer.serialize(result))
            .setIsSuccessful(true)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        channel.write(replyBuilder.build)
      }
    } catch {
      case e: InvocationTargetException =>
        channel.write(createErrorReplyMessage(e.getCause, request, false))
        server.notifyListeners(RemoteServerError(e, server))
      case e: Throwable                 =>
        channel.write(createErrorReplyMessage(e, request, false))
        server.notifyListeners(RemoteServerError(e, server))
    }
  }

  private def findActorById(id: String) : ActorRef = {
    server.actors().get(id)
  }

  private def findActorByUuid(uuid: String) : ActorRef = {
    server.actorsByUuid().get(uuid)
  }

  private def findTypedActorById(id: String) : AnyRef = {
    server.typedActors().get(id)
  }

  private def findTypedActorByUuid(uuid: String) : AnyRef = {
    server.typedActorsByUuid().get(uuid)
  }


  /**
   * Creates a new instance of the actor with name, uuid and timeout specified as arguments.
   *
   * If actor already created then just return it from the registry.
   *
   * Does not start the actor.
   */
  private def createActor(actorInfo: ActorInfoProtocol): ActorRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId

    val name = actorInfo.getTarget
    val timeout = actorInfo.getTimeout

    val actorRefOrNull = if (id.startsWith(UUID_PREFIX)) {
      findActorByUuid(id.substring(UUID_PREFIX.length))
    } else {
      findActorById(id)
    }
    
    if (actorRefOrNull eq null) {
      try {
        log.info("Creating a new remote actor [%s:%s]", name, uuid)
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                    else Class.forName(name)
        val actorRef = Actor.actorOf(clazz.newInstance.asInstanceOf[Actor])
        actorRef.uuid = uuidFrom(uuid.getHigh,uuid.getLow)
        actorRef.id = id
        actorRef.timeout = timeout
        actorRef.remoteAddress = None
        server.actors.put(id, actorRef) // register by id
        actorRef
      } catch {
        case e =>
          log.error(e, "Could not create remote actor instance")
          server.notifyListeners(RemoteServerError(e, server))
          throw e
      }
    } else actorRefOrNull
  }

  private def createTypedActor(actorInfo: ActorInfoProtocol): AnyRef = {
    val uuid = actorInfo.getUuid
    val id = actorInfo.getId

    val typedActorOrNull = if (id.startsWith(UUID_PREFIX)) {
      findTypedActorByUuid(id.substring(UUID_PREFIX.length))
    } else {
      findTypedActorById(id)
    }

    if (typedActorOrNull eq null) {
      val typedActorInfo = actorInfo.getTypedActorInfo
      val interfaceClassname = typedActorInfo.getInterface
      val targetClassname = actorInfo.getTarget

      try {
        log.info("Creating a new remote typed actor:\n\t[%s :: %s]", interfaceClassname, targetClassname)

        val (interfaceClass, targetClass) =
          if (applicationLoader.isDefined) (applicationLoader.get.loadClass(interfaceClassname),
                                            applicationLoader.get.loadClass(targetClassname))
          else (Class.forName(interfaceClassname), Class.forName(targetClassname))

        val newInstance = TypedActor.newInstance(
          interfaceClass, targetClass.asInstanceOf[Class[_ <: TypedActor]], actorInfo.getTimeout).asInstanceOf[AnyRef]
        server.typedActors.put(id, newInstance) // register by id
        newInstance
      } catch {
        case e =>
          log.error(e, "Could not create remote typed actor instance")
          server.notifyListeners(RemoteServerError(e, server))
          throw e
      }
    } else typedActorOrNull
  }

  private def createErrorReplyMessage(e: Throwable, request: RemoteRequestProtocol, isActor: Boolean): RemoteReplyProtocol = {
    val actorInfo = request.getActorInfo
    log.error(e, "Could not invoke remote typed actor [%s :: %s]", actorInfo.getTypedActorInfo.getMethod, actorInfo.getTarget)
    val replyBuilder = RemoteReplyProtocol.newBuilder
        .setUuid(request.getUuid)
        .setException(ExceptionProtocol.newBuilder.setClassname(e.getClass.getName).setMessage(e.getMessage).build)
        .setIsSuccessful(false)
        .setIsActor(isActor)
    if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
    replyBuilder.build
  }
}
