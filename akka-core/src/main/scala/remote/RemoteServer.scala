/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.remote

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.util._
import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteReply, RemoteRequest}
import se.scalablesolutions.akka.Config.config

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{DefaultChannelGroup, ChannelGroup}
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.codec.compression.{ZlibEncoder, ZlibDecoder}

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
 * This object holds configuration variables.
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
  private var port = RemoteServer.PORT

  @volatile private var isRunning = false
  @volatile private var isConfigured = false

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultChannelGroup("akka-server")

  def start: Unit = start(None)

  def start(loader: Option[ClassLoader]): Unit = start(hostname, port, loader)

  def start(_hostname: String, _port: Int): Unit = start(_hostname, _port, None)

  def start(_hostname: String, _port: Int, loader: Option[ClassLoader]): Unit = synchronized {
    try {
      if (!isRunning) {
        hostname = _hostname
        port = _port
        log.info("Starting remote server at [%s:%s]", hostname, port)
        bootstrap.setPipelineFactory(new RemoteServerPipelineFactory(name, openChannels, loader))
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.setOption("child.keepAlive", true)
        bootstrap.setOption("child.reuseAddress", true)
        bootstrap.setOption("child.connectTimeoutMillis", RemoteServer.CONNECTION_TIMEOUT_MILLIS)
        openChannels.add(bootstrap.bind(new InetSocketAddress(hostname, port)))
        isRunning = true
        Cluster.registerLocalNode(hostname,port)
      }      
    } catch {
      case e => log.error(e, "Could not start up remote server")
    }
  }

  def shutdown = {
    openChannels.close.awaitUninterruptibly()
    bootstrap.releaseExternalResources
    Cluster.deregisterLocalNode(hostname,port)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(name: String, openChannels: ChannelGroup, loader: Option[ClassLoader])
    extends ChannelPipelineFactory {
  import RemoteServer._

  def getPipeline: ChannelPipeline = {
    val pipeline = Channels.pipeline()
    RemoteServer.COMPRESSION_SCHEME match {
      case "zlib" => pipeline.addLast("zlibDecoder", new ZlibDecoder)
      //case "lzf" => pipeline.addLast("lzfDecoder", new LzfDecoder)
      case _ => {} // no compression
    }
    pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
    pipeline.addLast("protobufDecoder", new ProtobufDecoder(RemoteRequest.getDefaultInstance))
    RemoteServer.COMPRESSION_SCHEME match {
      case "zlib" => pipeline.addLast("zlibEncoder", new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL))
      //case "lzf" => pipeline.addLast("lzfEncoder", new LzfEncoder)
      case _ => {} // no compression
    }
    pipeline.addLast("frameEncoder", new LengthFieldPrepender(4))
    pipeline.addLast("protobufEncoder", new ProtobufEncoder)
    pipeline.addLast("handler", new RemoteServerHandler(name, openChannels, loader))
    pipeline
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelPipelineCoverage {val value = "all"}
class RemoteServerHandler(val name: String, openChannels: ChannelGroup, val applicationLoader: Option[ClassLoader])
    extends SimpleChannelUpstreamHandler with Logging {
  val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  private val activeObjects = new ConcurrentHashMap[String, AnyRef]
  private val actors = new ConcurrentHashMap[String, Actor]

  /**
   * ChannelOpen overridden to store open channels for a clean shutdown
   * of a RemoteServer. If a channel is closed before, it is
   * automatically removed from the open channels group.
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
    if (message eq null) throw new IllegalStateException(
      "Message in remote MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteRequest]) {
      handleRemoteRequest(message.asInstanceOf[RemoteRequest], event.getChannel)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.error(event.getCause, "Unexpected exception from remote downstream")
    event.getChannel.close
  }

  private def handleRemoteRequest(request: RemoteRequest, channel: Channel) = {
    log.debug("Received RemoteRequest[\n%s]", request.toString)
    if (request.getIsActor) dispatchToActor(request, channel)
    else dispatchToActiveObject(request, channel)
  }

  private def dispatchToActor(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to remote actor [%s]", request.getTarget)
    val actor = createActor(request.getTarget, request.getUuid, request.getTimeout)
    
    val message = RemoteProtocolBuilder.getMessage(request)
    if (request.getIsOneWay) {
      if (request.hasSourceHostname && request.hasSourcePort) {
        // re-create the sending actor
        val targetClass = if (request.hasSourceTarget) request.getSourceTarget
        else request.getTarget

        val remoteActor = createActor(targetClass, request.getSourceUuid, request.getTimeout)
        if (!remoteActor.isRunning) {
          remoteActor.makeRemote(request.getSourceHostname, request.getSourcePort)
          remoteActor.start
        }
        actor.!(message)(Some(remoteActor))
      } else {
        // couldn't find a way to reply, send the message without a source/sender
        actor.send(message)
      }
    } else {
      try {
        val resultOrNone = actor !! message
        val result: AnyRef = if (resultOrNone.isDefined) resultOrNone.get else null
        log.debug("Returning result from actor invocation [%s]", result)
        val replyBuilder = RemoteReply.newBuilder
            .setId(request.getId)
            .setIsSuccessful(true)
            .setIsActor(true)
        RemoteProtocolBuilder.setMessage(result, replyBuilder)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      } catch {
        case e: Throwable =>
          log.error(e, "Could not invoke remote actor [%s]", request.getTarget)
          val replyBuilder = RemoteReply.newBuilder
              .setId(request.getId)
              .setException(e.getClass.getName + "$" + e.getMessage)
              .setIsSuccessful(false)
              .setIsActor(true)
          if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
          val replyMessage = replyBuilder.build
          channel.write(replyMessage)
      }
    }
  }

  private def dispatchToActiveObject(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to remote active object [%s :: %s]", request.getMethod, request.getTarget)
    val activeObject = createActiveObject(request.getTarget, request.getTimeout)

    val args = RemoteProtocolBuilder.getMessage(request).asInstanceOf[Array[AnyRef]].toList
    val argClasses = args.map(_.getClass)
    val (unescapedArgs, unescapedArgClasses) = unescapeArgs(args, argClasses, request.getTimeout)

    //continueTransaction(request)
    try {
      val messageReceiver = activeObject.getClass.getDeclaredMethod(
        request.getMethod, unescapedArgClasses: _*)
      if (request.getIsOneWay) messageReceiver.invoke(activeObject, unescapedArgs: _*)
      else {
        val result = messageReceiver.invoke(activeObject, unescapedArgs: _*)
        log.debug("Returning result from remote active object invocation [%s]", result)
        val replyBuilder = RemoteReply.newBuilder
            .setId(request.getId)
            .setIsSuccessful(true)
            .setIsActor(false)
        RemoteProtocolBuilder.setMessage(result, replyBuilder)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      }
    } catch {
      case e: InvocationTargetException =>
        log.error(e.getCause, "Could not invoke remote active object [%s :: %s]", request.getMethod, request.getTarget)
        val replyBuilder = RemoteReply.newBuilder
            .setId(request.getId)
            .setException(e.getCause.getClass.getName + "$" + e.getCause.getMessage)
            .setIsSuccessful(false)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      case e: Throwable =>
        log.error(e.getCause, "Could not invoke remote active object [%s :: %s]", request.getMethod, request.getTarget)
        val replyBuilder = RemoteReply.newBuilder
            .setId(request.getId)
            .setException(e.getClass.getName + "$" + e.getMessage)
            .setIsSuccessful(false)
            .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
    }
  }

  /*
  private def continueTransaction(request: RemoteRequest) = {
    val tx = request.tx
    if (tx.isDefined) {
      tx.get.reinit
      TransactionManagement.threadBoundTx.set(tx)
      setThreadLocalTransaction(tx.transaction)
    } else {
      TransactionManagement.threadBoundTx.set(None)     
      setThreadLocalTransaction(null)
    }
  }
  */
  private def unescapeArgs(args: scala.List[AnyRef], argClasses: scala.List[Class[_]], timeout: Long) = {
    val unescapedArgs = new Array[AnyRef](args.size)
    val unescapedArgClasses = new Array[Class[_]](args.size)

    val escapedArgs = for (i <- 0 until args.size) {
      val arg = args(i)
      if (arg.isInstanceOf[String] && arg.asInstanceOf[String].startsWith(AW_PROXY_PREFIX)) {
        val argString = arg.asInstanceOf[String]
        val proxyName = argString.replace(AW_PROXY_PREFIX, "") //argString.substring(argString.indexOf("$$ProxiedByAW"), argString.length)
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

  private def createActor(name: String, uuid: String, timeout: Long): Actor = {
    val actorOrNull = actors.get(uuid)
    if (actorOrNull eq null) {
      try {
        log.info("Creating a new remote actor [%s:%s]", name, uuid)
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
        else Class.forName(name)
        val newInstance = clazz.newInstance.asInstanceOf[Actor]
        newInstance._uuid = uuid
        newInstance.timeout = timeout
        newInstance._remoteAddress = None
        actors.put(uuid, newInstance)
        newInstance.start
        newInstance
      } catch {
        case e =>
          log.error(e, "Could not create remote actor object instance")
          throw e
      }
    } else actorOrNull
  }
}
