/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}

import kernel.actor._
import kernel.stm.TransactionManagement
import kernel.util.Logging

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.serialization.ObjectDecoder
import org.jboss.netty.handler.codec.serialization.ObjectEncoder

class RemoteServer extends Logging {
  def start = RemoteServer.start
}

object RemoteServer extends Logging {
  val HOSTNAME = kernel.Kernel.config.getString("akka.remote.hostname", "localhost")
  val PORT = kernel.Kernel.config.getInt("akka.remote.port", 9999)
  val CONNECTION_TIMEOUT_MILLIS = kernel.Kernel.config.getInt("akka.remote.connection-timeout", 1000)  

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

  def start = synchronized {
    if (!isRunning) {
      log.info("Starting remote server at [%s:%s]", HOSTNAME, PORT)
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

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val message = event.getMessage
    if (message == null) throw new IllegalStateException("Message in remote MessageEvent is null: " + event)
    if (message.isInstanceOf[RemoteRequest]) handleRemoteRequest(message.asInstanceOf[RemoteRequest], event.getChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    event.getCause.printStackTrace
    log.error("Unexpected exception from remote downstream: %s", event.getCause)
    event.getChannel.close
  }

  private def handleRemoteRequest(request: RemoteRequest, channel: Channel) = {
    log.debug(request.toString)
    if (request.isActor) dispatchToActor(request, channel)
    else dispatchToActiveObject(request, channel)
  }

  private def dispatchToActor(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to remote actor [%s]", request.target)
    val actor = createActor(request.target, request.timeout)
    actor.start
    if (request.isOneWay) actor ! request.message
    else {
      try {
        val resultOrNone = actor !! request.message
        val result: AnyRef = if (resultOrNone.isDefined) resultOrNone.get else null
        log.debug("Returning result from actor invocation [%s]", result)
        //channel.write(request.newReplyWithMessage(result, TransactionManagement.threadBoundTx.get))
        channel.write(request.newReplyWithMessage(result, null))
      } catch {
        case e: Throwable =>
          log.error("Could not invoke remote actor [%s] due to: %s", request.target, e)
          e.printStackTrace
          channel.write(request.newReplyWithException(e))
      }
    }    
  }

  private def dispatchToActiveObject(request: RemoteRequest, channel: Channel) = {
    log.debug("Dispatching to remote active object [%s :: %s]", request.method, request.target)
    val activeObject = createActiveObject(request.target, request.timeout)

    val args = request.message.asInstanceOf[scala.List[AnyRef]]
    val argClasses = args.map(_.getClass)
    val (unescapedArgs, unescapedArgClasses) = unescapeArgs(args, argClasses, request.timeout)

    //continueTransaction(request)
    try {
      val messageReceiver = activeObject.getClass.getDeclaredMethod(request.method, unescapedArgClasses: _*)
      if (request.isOneWay) messageReceiver.invoke(activeObject, unescapedArgs: _*)
      else {
        val result = messageReceiver.invoke(activeObject, unescapedArgs: _*)
        log.debug("Returning result from remote active object invocation [%s]", result)
        //channel.write(request.newReplyWithMessage(result, TransactionManagement.threadBoundTx.get))
        channel.write(request.newReplyWithMessage(result, null))
      }
    } catch {
      case e: InvocationTargetException =>
        log.error("Could not invoke remote active object [%s :: %s] due to: %s", request.method, request.target, e.getCause)
        e.getCause.printStackTrace
        channel.write(request.newReplyWithException(e.getCause))
      case e: Throwable =>
        log.error("Could not invoke remote active object [%s :: %s] due to: %s", request.method, request.target, e)
        e.printStackTrace
        channel.write(request.newReplyWithException(e))
    }
  }

  private def continueTransaction(request: RemoteRequest) = {
    val tx = request.tx
    if (tx.isDefined) {
      tx.get.reinit
      TransactionManagement.threadBoundTx.set(tx)
    } else TransactionManagement.threadBoundTx.set(None)     
  }
  
  private def unescapeArgs(args: scala.List[AnyRef], argClasses: scala.List[Class[_]], timeout: Long) = {
    val unescapedArgs = new Array[AnyRef](args.size)
    val unescapedArgClasses = new Array[Class[_]](args.size)

    val escapedArgs = for (i <- 0 until args.size) {
      if (args(i).isInstanceOf[ProxyWrapper]) {
        val proxyName = args(i).asInstanceOf[ProxyWrapper].proxyName
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
      val clazz = Class.forName(name)
      try {
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
      val clazz = Class.forName(name)
      try {
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
