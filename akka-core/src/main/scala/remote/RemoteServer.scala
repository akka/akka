/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.util.{Map => JMap}

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.util._
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.config.Config.config

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{DefaultChannelGroup, ChannelGroup}
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.codec.compression.{ZlibEncoder, ZlibDecoder}

import scala.collection.mutable.Map

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
 * Holds configuration variables, remote actors, remote active objects and remote servers.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteServer {
  val HOSTNAME = config.getString("akka.remote.server.hostname", "localhost")
  val PORT = config.getInt("akka.remote.server.port", 9999)

  val CONNECTION_TIMEOUT_MILLIS = config.getInt("akka.remote.server.connection-timeout", 1000)

  val COMPRESSION_SCHEME = config.getString("akka.remote.compression-scheme", "zlib")
  val ZLIB_COMPRESSION_LEVEL = {
    val level = config.getInt("akka.remote.zlib-compression-level", 6)
    if (level < 1 && level > 9) throw new IllegalArgumentException(
      "zlib compression level has to be within 1-9, with 1 being fastest and 9 being the most compressed")
    level
  }

  object Address {
    def apply(hostname: String, port: Int) = new Address(hostname, port)
  }
  class Address(val hostname: String, val port: Int) {
    override def hashCode: Int = {
      var result = HashCode.SEED
      result = HashCode.hash(result, hostname)
      result = HashCode.hash(result, port)
      result
    }
    override def equals(that: Any): Boolean = {
      that != null &&
      that.isInstanceOf[Address] &&
      that.asInstanceOf[Address].hostname == hostname &&
      that.asInstanceOf[Address].port == port
    }
  }

  private class RemoteActorSet {
    private[RemoteServer] val actors =        new ConcurrentHashMap[String, ActorRef]
    private[RemoteServer] val activeObjects = new ConcurrentHashMap[String, AnyRef]
  }

  private val guard = new ReadWriteGuard
  private val remoteActorSets = Map[Address, RemoteActorSet]()
  private val remoteServers =   Map[Address, RemoteServer]()

  private[akka] def registerActor(address: InetSocketAddress, uuid: String, actor: ActorRef) = guard.withWriteGuard {
    actorsFor(RemoteServer.Address(address.getHostName, address.getPort)).actors.put(uuid, actor)
  }

  private[akka] def registerActiveObject(address: InetSocketAddress, name: String, activeObject: AnyRef) = guard.withWriteGuard {
    actorsFor(RemoteServer.Address(address.getHostName, address.getPort)).activeObjects.put(name, activeObject)
  }

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

  private def actorsFor(remoteServerAddress: RemoteServer.Address): RemoteActorSet = {
    remoteActorSets.getOrElseUpdate(remoteServerAddress,new RemoteActorSet)
  }
}

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
class RemoteServer extends Logging {
  val name = "RemoteServer@" + hostname + ":" + port

  private var hostname = RemoteServer.HOSTNAME
  private var port =     RemoteServer.PORT

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
        hostname = _hostname
        port = _port
        log.info("Starting remote server at [%s:%s]", hostname, port)
        RemoteServer.register(hostname, port, this)
        val remoteActorSet = RemoteServer.actorsFor(RemoteServer.Address(hostname, port))
        val pipelineFactory = new RemoteServerPipelineFactory(
          name, openChannels, loader, remoteActorSet.actors, remoteActorSet.activeObjects)
        bootstrap.setPipelineFactory(pipelineFactory)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.setOption("child.reuseAddress", true)
        bootstrap.setOption("child.connectTimeoutMillis", RemoteServer.CONNECTION_TIMEOUT_MILLIS)
        openChannels.add(bootstrap.bind(new InetSocketAddress(hostname, port)))
        _isRunning = true
        Cluster.registerLocalNode(hostname, port)
      }
    } catch {
      case e => log.error(e, "Could not start up remote server")
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
      } catch {
        case e: java.nio.channels.ClosedChannelException => log.warning("Could not close remote server channel in a graceful way")
      }
    }
  }

  // TODO: register active object in RemoteServer as well

  /**
   * Register Remote Actor by the Actor's 'id' field. It starts the Actor if it is not started already.
   */
  def register(actorRef: ActorRef) = synchronized {
    if (_isRunning) {
      if (!actorRef.isRunning) actorRef.start
      log.info("Registering server side remote actor [%s] with id [%s]", actorRef.actorClass.getName, actorRef.id)
      RemoteServer.actorsFor(RemoteServer.Address(hostname, port)).actors.put(actorRef.id, actorRef)
    }
  }

  /**
   * Register Remote Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef) = synchronized {
    if (_isRunning) {
      if (!actorRef.isRunning) actorRef.start
      log.info("Registering server side remote actor [%s] with id [%s]", actorRef.actorClass.getName, id)
      RemoteServer.actorsFor(RemoteServer.Address(hostname, port)).actors.put(id, actorRef)
    }
  }

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef) = synchronized {
    if (_isRunning) {
      log.info("Unregistering server side remote actor [%s] with id [%s]", actorRef.actorClass.getName, actorRef.id)
      val server = RemoteServer.actorsFor(RemoteServer.Address(hostname, port))
      server.actors.remove(actorRef.id)
      if (actorRef.registeredInRemoteNodeDuringSerialization) server.actors.remove(actorRef.uuid)
    }
  }

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(id: String) = synchronized {
    if (_isRunning) {
      log.info("Unregistering server side remote actor with id [%s]", id)
      val server = RemoteServer.actorsFor(RemoteServer.Address(hostname, port))
      val actorRef = server.actors.get(id)
      server.actors.remove(id)
      if (actorRef.registeredInRemoteNodeDuringSerialization) server.actors.remove(actorRef.uuid)
    }
  }
}

case class Codec(encoder: ChannelHandler, decoder: ChannelHandler)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(
    val name: String,
    val openChannels: ChannelGroup,
    val loader: Option[ClassLoader],
    val actors: JMap[String, ActorRef],
    val activeObjects: JMap[String, AnyRef]) extends ChannelPipelineFactory {
  import RemoteServer._

  def getPipeline: ChannelPipeline = {
    val lenDec       = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep      = new LengthFieldPrepender(4)
    val protobufDec  = new ProtobufDecoder(RemoteRequestProtocol.getDefaultInstance)
    val protobufEnc  = new ProtobufEncoder
    val zipCodec = RemoteServer.COMPRESSION_SCHEME match {
      case "zlib"  => Some(Codec(new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL), new ZlibDecoder))
      //case "lzf" => Some(Codec(new LzfEncoder, new LzfDecoder))
      case _ => None
    }
    val remoteServer = new RemoteServerHandler(name, openChannels, loader, actors, activeObjects)

    val stages: Array[ChannelHandler] =
      zipCodec.map(codec => Array(codec.decoder, lenDec, protobufDec, codec.encoder, lenPrep, protobufEnc, remoteServer))
              .getOrElse(Array(lenDec, protobufDec, lenPrep, protobufEnc, remoteServer))
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
    val actors: JMap[String, ActorRef],
    val activeObjects: JMap[String, AnyRef]) extends SimpleChannelUpstreamHandler with Logging {
  val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  applicationLoader.foreach(MessageSerializer.setClassLoader(_))

  /**
   * ChannelOpen overridden to store open channels for a clean shutdown of a RemoteServer.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) {
    openChannels.add(ctx.getChannel)
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
    event.getCause.printStackTrace
    log.error(event.getCause, "Unexpected exception from remote downstream")
    event.getChannel.close
  }

  private def handleRemoteRequestProtocol(request: RemoteRequestProtocol, channel: Channel) = {
    log.debug("Received RemoteRequestProtocol[\n%s]", request.toString)
    if (request.getIsActor) dispatchToActor(request, channel)
    else dispatchToActiveObject(request, channel)
  }

  private def dispatchToActor(request: RemoteRequestProtocol, channel: Channel) = {
    log.debug("Dispatching to remote actor [%s:%s]", request.getTarget, request.getUuid)
    val actorRef = createActor(request.getTarget, request.getUuid, request.getTimeout)
    actorRef.start
    val message = MessageSerializer.deserialize(request.getMessage)
    val sender =
      if (request.hasSender) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(request.getSender, applicationLoader))
      else None
    if (request.getIsOneWay) actorRef.!(message)(sender)
    else {
      try {
        val resultOrNone = (actorRef.!!(message)(sender)).as[AnyRef]
        val result = if (resultOrNone.isDefined) resultOrNone.get else null
        log.debug("Returning result from actor invocation [%s]", result)
        val replyBuilder = RemoteReplyProtocol.newBuilder
            .setId(request.getId)
            .setMessage(MessageSerializer.serialize(result))
            .setIsSuccessful(true)
            .setIsActor(true)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      } catch {
        case e: Throwable =>
          log.error(e, "Could not invoke remote actor [%s]", request.getTarget)
          val replyBuilder = RemoteReplyProtocol.newBuilder
              .setId(request.getId)
              .setException(ExceptionProtocol.newBuilder.setClassname(e.getClass.getName).setMessage(e.getMessage).build)
              .setIsSuccessful(false)
              .setIsActor(true)
          if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
          val replyMessage = replyBuilder.build
          channel.write(replyMessage)
      }
    }
  }

  private def dispatchToActiveObject(request: RemoteRequestProtocol, channel: Channel) = {
    log.debug("Dispatching to remote active object [%s :: %s]", request.getMethod, request.getTarget)
    val activeObject = createActiveObject(request.getTarget, request.getTimeout)

    val args = MessageSerializer.deserialize(request.getMessage).asInstanceOf[Array[AnyRef]].toList
    val argClasses = args.map(_.getClass)
    val (unescapedArgs, unescapedArgClasses) = unescapeArgs(args, argClasses, request.getTimeout)

    try {
      val messageReceiver = activeObject.getClass.getDeclaredMethod(
        request.getMethod, unescapedArgClasses: _*)
      if (request.getIsOneWay) messageReceiver.invoke(activeObject, unescapedArgs: _*)
      else {
        val result = messageReceiver.invoke(activeObject, unescapedArgs: _*)
        log.debug("Returning result from remote active object invocation [%s]", result)
        val replyBuilder = RemoteReplyProtocol.newBuilder
            .setId(request.getId)
            .setMessage(MessageSerializer.serialize(result))
            .setIsSuccessful(true)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      }
    } catch {
      case e: InvocationTargetException =>
        log.error(e.getCause, "Could not invoke remote active object [%s :: %s]", request.getMethod, request.getTarget)
        val replyBuilder = RemoteReplyProtocol.newBuilder
            .setId(request.getId)
            .setException(ExceptionProtocol.newBuilder.setClassname(e.getCause.getClass.getName).setMessage(e.getCause.getMessage).build)
            .setIsSuccessful(false)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      case e: Throwable =>
        log.error(e, "Could not invoke remote active object [%s :: %s]", request.getMethod, request.getTarget)
        val replyBuilder = RemoteReplyProtocol.newBuilder
            .setId(request.getId)
            .setException(ExceptionProtocol.newBuilder.setClassname(e.getClass.getName).setMessage(e.getMessage).build)
            .setIsSuccessful(false)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
    }
  }

  private def unescapeArgs(args: scala.List[AnyRef], argClasses: scala.List[Class[_]], timeout: Long) = {
    val unescapedArgs = new Array[AnyRef](args.size)
    val unescapedArgClasses = new Array[Class[_]](args.size)

    val escapedArgs = for (i <- 0 until args.size) {
      val arg = args(i)
      if (arg.isInstanceOf[String] && arg.asInstanceOf[String].startsWith(AW_PROXY_PREFIX)) {
        val argString = arg.asInstanceOf[String]
        val proxyName = argString.replace(AW_PROXY_PREFIX, "")
        val activeObject = createActiveObject(proxyName, timeout)
        unescapedArgs(i) = activeObject
        unescapedArgClasses(i) = Class.forName(proxyName)
      } else {
        unescapedArgs(i) = args(i)
        unescapedArgClasses(i) = argClasses(i)
      }
    }
    (unescapedArgs, unescapedArgClasses)
  }

  private def createActiveObject(name: String, timeout: Long): AnyRef = {
    val activeObjectOrNull = activeObjects.get(name)
    if (activeObjectOrNull eq null) {
      try {
        log.info("Creating a new remote active object [%s]", name)
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
        else Class.forName(name)
        val newInstance = ActiveObject.newInstance(clazz, timeout).asInstanceOf[AnyRef]
        activeObjects.put(name, newInstance)
        newInstance
      } catch {
        case e =>
          log.error(e, "Could not create remote active object instance")
          throw e
      }
    } else activeObjectOrNull
  }

  /**
   * Creates a new instance of the actor with name, uuid and timeout specified as arguments.
   * If actor already created then just return it from the registry.
   * Does not start the actor.
   */
  private def createActor(name: String, uuid: String, timeout: Long): ActorRef = {
    val actorRefOrNull = actors.get(uuid)
    if (actorRefOrNull eq null) {
      try {
        log.info("Creating a new remote actor [%s:%s]", name, uuid)
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                    else Class.forName(name)
        val actorRef = Actor.actorOf(clazz.newInstance.asInstanceOf[Actor])
        actorRef.uuid = uuid
        actorRef.timeout = timeout
        actorRef.remoteAddress = None
        actors.put(uuid, actorRef)
        actorRef
      } catch {
        case e =>
          log.error(e, "Could not create remote actor instance")
          throw e
      }
    } else actorRefOrNull
  }
}
