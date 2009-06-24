/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, Executors}
import kernel.actor.{ActiveObjectFactory, Dispatcher, ActiveObject, Invocation}
import kernel.util.Logging
import java.util.ArrayList
import java.util.List
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.serialization.ObjectDecoder
import org.jboss.netty.handler.codec.serialization.ObjectEncoder

class NettyServer extends Logging {
  val HOSTNAME = "localhost"
  val PORT = 9999
  val CONNECTION_TIMEOUT_MILLIS = 100

  log.info("Starting NIO server at [%s:%s]", HOSTNAME, PORT)
  
  val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  val bootstrap = new ServerBootstrap(factory)

  // FIXME provide different codecs (Thrift, Avro, Protobuf, JSON)
  val handler = new ObjectServerHandler

  bootstrap.getPipeline.addLast("handler", handler)
  
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", CONNECTION_TIMEOUT_MILLIS)

  bootstrap.bind(new InetSocketAddress(HOSTNAME, PORT))
}

@ChannelPipelineCoverage {val value = "all"}
class ObjectServerHandler extends SimpleChannelUpstreamHandler with Logging {
  private val activeObjectFactory = new ActiveObjectFactory
  private val activeObjects = new ConcurrentHashMap[String, AnyRef]

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
    // Send the first message if this handler is a client-side handler.
    //    if (!firstMessage.isEmpty) e.getChannel.write(firstMessage)
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
      if (request.isActor) {
        log.debug("Dispatching to [receive :: %s]", request.target)
        throw new UnsupportedOperationException("TODO: remote actors")
      } else {
        log.debug("Dispatching to [%s :: %s]", request.method, request.target)
        val activeObject = createActiveObject(request.target)
//        val args = request.message.asInstanceOf[scala.List[Object]]
//        val argClassesList = args.map(_.getClass)
//        val argClasses = argClassesList.map(_.getClass).toArray
//        val method = activeObject.getClass.getDeclaredMethod(request.method, argClasses)

        val args = request.message.asInstanceOf[scala.List[AnyRef]]
        val argClazzes = args.map(_.getClass)//.toArray.asInstanceOf[Array[Class[_]]]
        val (unescapedArgs, unescapedArgClasses) = unescapeArgs(args, argClazzes)
        val method = activeObject.getClass.getDeclaredMethod(request.method, unescapedArgClasses)
        try {
          if (request.isOneWay) method.invoke(activeObject, unescapedArgs)
          else {
            val result = method.invoke(activeObject, unescapedArgs)
            log.debug("Returning result [%s]", result)
            channel.write(request.newReplyWithMessage(result))
          }
        } catch {
          case e: InvocationTargetException =>
            log.error("Could not invoke remote active object or actor [%s :: %s] due to: %s", request.method, request.target, e.getCause)
            e.getCause.printStackTrace
            channel.write(request.newReplyWithException(e.getCause))
        }
      }
    } catch {
      case e: Exception =>
        log.error("Could not invoke remote active object or actor [%s :: %s] due to: %s", request.method, request.target, e)
        e.printStackTrace
    }    
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

  private def createActiveObject(name: String) = {
    val activeObjectOrNull = activeObjects.get(name)
    if (activeObjectOrNull == null) {
      val clazz = Class.forName(name)
      val newInstance = clazz.newInstance.asInstanceOf[AnyRef] // activeObjectFactory.newInstance(clazz, new Dispatcher(invocation.target)).asInstanceOf[AnyRef]
      activeObjects.put(name, newInstance)
      newInstance
    } else activeObjectOrNull    
  }
}

object NettyServerRunner {
  def main(args: Array[String]) = {
    new NettyServer
  }
}
