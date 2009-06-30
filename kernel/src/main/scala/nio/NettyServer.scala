/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}
import kernel.actor._
import kernel.reactor.{MessageHandle, DefaultCompletableFutureResult, CompletableFutureResult}
import kernel.stm.TransactionManagement
import kernel.util.Logging
import java.util.ArrayList
import java.util.List
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

import org.codehaus.aspectwerkz.intercept.Advisable
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.serialization.ObjectDecoder
import org.jboss.netty.handler.codec.serialization.ObjectEncoder

class NettyServer extends Logging {
  def connect = NettyServer.connect
}

object NettyServer extends Logging {
  private val HOSTNAME = "localhost"
  private val PORT = 9999
  private val CONNECTION_TIMEOUT_MILLIS = 100  

  @volatile private var isRunning = false

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val activeObjectFactory = new ActiveObjectFactory

  private val bootstrap = new ServerBootstrap(factory)
  // FIXME provide different codecs (Thrift, Avro, Protobuf, JSON)

  private val handler = new ObjectServerHandler
  bootstrap.getPipeline.addLast("handler", handler)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", CONNECTION_TIMEOUT_MILLIS)

  def connect = synchronized {
    if (!isRunning) {
      log.info("Starting NIO server at [%s:%s]", HOSTNAME, PORT)
      bootstrap.bind(new InetSocketAddress(HOSTNAME, PORT))
      isRunning = true
    }
  }
}

@ChannelPipelineCoverage { val value = "all" }
class ObjectServerHandler extends SimpleChannelUpstreamHandler with Logging {
  private val activeObjectFactory = new ActiveObjectFactory
  private val activeObjects = new ConcurrentHashMap[String, AnyRef]
  private val actors = new ConcurrentHashMap[String, Actor]

  private val MESSAGE_HANDLE = classOf[Actor].getDeclaredMethod(
    "handle", Array[Class[_]](classOf[MessageHandle]))

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] && event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    event.getChannel.getPipeline.addFirst("encoder", new ObjectEncoder)
    event.getChannel.getPipeline.addFirst("decoder", new ObjectDecoder)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    //e.getChannel.write(firstMessage)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) ={
    val message = event.getMessage
    if (message == null) throw new IllegalStateException("Message in MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteRequest]) handleRemoteRequest(message.asInstanceOf[RemoteRequest], event.getChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    event.getCause.printStackTrace
    log.error("Unexpected exception from downstream: %s", event.getCause)
    event.getChannel.close
  }

  private def handleRemoteRequest(request: RemoteRequest, channel: Channel) = {
    try {
      log.debug(request.toString)
      if (request.isActor) dispatchToActor(request, channel)
      else dispatchToActiveObject(request, channel)
     } catch {
      case e: Exception =>
        log.error("Could not invoke remote active object or actor [%s :: %s] due to: %s", request.method, request.target, e)
        e.printStackTrace
    }    
  }

  private def dispatchToActor(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to actor [%s]", request.target)
    val actor = createActor(request.target)
    actor.start
    if (request.isOneWay) actor ! request.message
    else {
      try {
        val resultOrNone = actor !! request.message
        val result = if (resultOrNone.isDefined) resultOrNone else null
        log.debug("Returning result from actor invocation [%s]", result)
        //channel.write(request.newReplyWithMessage(result, TransactionManagement.threadBoundTx.get))
        channel.write(request.newReplyWithMessage(result, null))
      } catch {
        case e: InvocationTargetException =>
          log.error("Could not invoke remote actor [%s] due to: %s", request.target, e.getCause)
          e.getCause.printStackTrace
          channel.write(request.newReplyWithException(e.getCause))
      }
    }    
  }

  private def dispatchToActiveObject(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to [%s :: %s]", request.method, request.target)
    val activeObject = createActiveObject(request.target)

    val args = request.message.asInstanceOf[scala.List[AnyRef]]
    val argClazzes = args.map(_.getClass)
    val (unescapedArgs, unescapedArgClasses) = unescapeArgs(args, argClazzes)

    continueTransaction(request)
    try {
      val messageReceiver = activeObject.getClass.getDeclaredMethod(request.method, unescapedArgClasses)
      if (request.isOneWay) messageReceiver.invoke(activeObject, unescapedArgs)
      else {
        val result = messageReceiver.invoke(activeObject, unescapedArgs)
        log.debug("Returning result from active object invocation [%s]", result)
        //channel.write(request.newReplyWithMessage(result, TransactionManagement.threadBoundTx.get))
        channel.write(request.newReplyWithMessage(result, null))
      }
    } catch {
      case e: InvocationTargetException =>
        log.error("Could not invoke remote active object [%s :: %s] due to: %s", request.method, request.target, e.getCause)
        e.getCause.printStackTrace
        channel.write(request.newReplyWithException(e.getCause))
    }
  }

  private def continueTransaction(request: RemoteRequest) = {
    val tx = request.tx
    if (tx.isDefined) {
      tx.get.reinit
      TransactionManagement.threadBoundTx.set(tx)
    } else TransactionManagement.threadBoundTx.set(None)     
  }
  
  private def unescapeArgs(args: scala.List[AnyRef], argClasses: scala.List[Class[_]]) = {
    val unescapedArgs = new Array[AnyRef](args.size)
    val unescapedArgClasses = new Array[Class[_]](args.size)

    val escapedArgs = for (i <- 0 until args.size) {
      if (args(i).isInstanceOf[ProxyWrapper]) {
        val proxyName = args(i).asInstanceOf[ProxyWrapper].proxyName
        val activeObject = createActiveObject(proxyName)
        unescapedArgs(i) = activeObject
        unescapedArgClasses(i) = Class.forName(proxyName)       
      } else {
        unescapedArgs(i) = args(i)
        unescapedArgClasses(i) = argClasses(i)        
      }
    }
    (unescapedArgs, unescapedArgClasses)
  }

  private def createActiveObject(name: String): AnyRef = {
    val activeObjectOrNull = activeObjects.get(name)
    if (activeObjectOrNull == null) {
      val clazz = Class.forName(name)
      try {
        val actor = new Dispatcher(clazz.getName)
        val newInstance = activeObjectFactory.newInstance(clazz, actor, false).asInstanceOf[AnyRef]
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

  private def createActor(name: String): Actor = {
    val actorOrNull = actors.get(name)
    if (actorOrNull == null) {
      val clazz = Class.forName(name)
      try {
        val newInstance = clazz.newInstance.asInstanceOf[Actor]
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
