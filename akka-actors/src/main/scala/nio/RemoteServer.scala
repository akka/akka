/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.nio

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}

import actor._
import util._
import protobuf.RemoteProtocol
import protobuf.RemoteProtocol.{RemoteReply, RemoteRequest}
import serialization.{Serializer, Serializable, SerializationProtocol}

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServer extends Logging {
  def start = RemoteServer.start(None)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteServer extends Logging {
  import Config.config
  val HOSTNAME = config.getString("akka.remote.hostname", "localhost")
  val PORT = config.getInt("akka.remote.port", 9999)
  val CONNECTION_TIMEOUT_MILLIS = config.getInt("akka.remote.connection-timeout", 1000)  

  val name = "RemoteServer@" + HOSTNAME

  @volatile private var isRunning = false
  @volatile private var isConfigured = false

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val activeObjectFactory = new ActiveObjectFactory

  private val bootstrap = new ServerBootstrap(factory)

  def start(loader: Option[ClassLoader]) = synchronized {
    if (!isRunning) {
      log.info("Starting remote server at [%s:%s]", HOSTNAME, PORT)
      bootstrap.setPipelineFactory(new RemoteServerPipelineFactory(name, loader))
      bootstrap.setOption("child.tcpNoDelay", true)
      bootstrap.setOption("child.keepAlive", true)
      bootstrap.setOption("child.reuseAddress", true)
      bootstrap.setOption("child.connectTimeoutMillis", CONNECTION_TIMEOUT_MILLIS)
      bootstrap.bind(new InetSocketAddress(HOSTNAME, PORT))
      isRunning = true
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(name: String, loader: Option[ClassLoader]) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline  = {
    val p = Channels.pipeline()
    p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
    p.addLast("protobufDecoder", new ProtobufDecoder(RemoteProtocol.RemoteRequest.getDefaultInstance))
    p.addLast("frameEncoder", new LengthFieldPrepender(4))
    p.addLast("protobufEncoder", new ProtobufEncoder)
    p.addLast("handler", new RemoteServerHandler(name, loader))
    p
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelPipelineCoverage { val value = "all" }
class RemoteServerHandler(val name: String, val applicationLoader: Option[ClassLoader]) extends SimpleChannelUpstreamHandler with Logging {
  private val activeObjectFactory = new ActiveObjectFactory
  private val activeObjects = new ConcurrentHashMap[String, AnyRef]
  private val actors = new ConcurrentHashMap[String, Actor]
 
  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] && event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val message = event.getMessage
    if (message == null) throw new IllegalStateException("Message in remote MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteRequest]) handleRemoteRequest(message.asInstanceOf[RemoteRequest], event.getChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.error("Unexpected exception from remote downstream: %s", event.getCause)
    event.getCause.printStackTrace
    event.getChannel.close
  }

  private def handleRemoteRequest(request: RemoteRequest, channel: Channel) = {
    log.debug("Received RemoteRequest[\n%s]", request.toString)
    if (request.getIsActor) dispatchToActor(request, channel)
    else dispatchToActiveObject(request, channel)
  }

  private def dispatchToActor(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to remote actor [%s]", request.getTarget)
    val actor = createActor(request.getTarget, request.getTimeout)
    actor.start
    val message = RemoteProtocolBuilder.getMessage(request)
    if (request.getIsOneWay) actor ! message
    else {
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
          log.error("Could not invoke remote actor [%s] due to: %s", request.getTarget, e)
          e.printStackTrace
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
      val messageReceiver = activeObject.getClass.getDeclaredMethod(request.getMethod, unescapedArgClasses: _*)
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
        log.error("Could not invoke remote active object [%s :: %s] due to: %s", request.getMethod, request.getTarget, e.getCause)
        e.getCause.printStackTrace
        val replyBuilder = RemoteReply.newBuilder
          .setId(request.getId)
          .setException(e.getCause.getClass.getName + "$" + e.getCause.getMessage)
          .setIsSuccessful(false)
          .setIsActor(false)
        if (request.hasSupervisorUuid) replyBuilder.setSupervisorUuid(request.getSupervisorUuid)
        val replyMessage = replyBuilder.build
        channel.write(replyMessage)
      case e: Throwable =>
        log.error("Could not invoke remote active object [%s :: %s] due to: %s", request.getMethod, request.getTarget, e)
        e.printStackTrace
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
    } else TransactionManagement.threadBoundTx.set(None)     
  }
  */
  private def unescapeArgs(args: scala.List[AnyRef], argClasses: scala.List[Class[_]], timeout: Long) = {
    val unescapedArgs = new Array[AnyRef](args.size)
    val unescapedArgClasses = new Array[Class[_]](args.size)

    val escapedArgs = for (i <- 0 until args.size) {
      val arg = args(i)
      if (arg.isInstanceOf[String] && arg.asInstanceOf[String].startsWith("$$ProxiedByAW")) {
        val argString = arg.asInstanceOf[String]
        val proxyName = argString.replace("$$ProxiedByAW", "") //argString.substring(argString.indexOf("$$ProxiedByAW"), argString.length)
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
    if (activeObjectOrNull == null) {
      try {
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                    else Class.forName(name)
        val newInstance = activeObjectFactory.newInstance(clazz, timeout).asInstanceOf[AnyRef]
        activeObjects.put(name, newInstance)
        newInstance
      } catch {
        case e =>
          log.debug("Could not create remote active object instance due to: %s", e)
          e.printStackTrace
          throw e
      }
    } else activeObjectOrNull
  }

  private def createActor(name: String, timeout: Long): Actor = {
    val actorOrNull = actors.get(name)
    if (actorOrNull == null) {
      try {
        val clazz = if (applicationLoader.isDefined) applicationLoader.get.loadClass(name)
                    else Class.forName(name)
        val newInstance = clazz.newInstance.asInstanceOf[Actor]
        newInstance.timeout = timeout
        actors.put(name, newInstance)
        newInstance
      } catch {
        case e =>
          log.debug("Could not create remote actor instance due to: %s", e)
          e.printStackTrace
          throw e
      }
    } else actorOrNull
  }
}
