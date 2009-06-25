/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ConcurrentMap, ConcurrentHashMap}

import kernel.reactor.{NullFutureResult, DefaultCompletableFutureResult, CompletableFutureResult}
import kernel.util.{HashCode, Logging};

import org.jboss.netty.handler.codec.serialization.{ObjectEncoder, ObjectDecoder}
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel._

object NettyClient extends Logging {
  private val HOSTNAME = "localhost"
  private val PORT = 9999

  @volatile private var isRunning = false 
  private val futures = new ConcurrentHashMap[Long, CompletableFutureResult]

  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)
  private val handler = new ObjectClientHandler(futures)

  bootstrap.getPipeline.addLast("handler", handler)
  bootstrap.setOption("tcpNoDelay", true)
  bootstrap.setOption("keepAlive", true)

  private var connection: ChannelFuture = _

  def connect = synchronized {
    if (!isRunning) {
      connection = bootstrap.connect(new InetSocketAddress(HOSTNAME, PORT))
      log.info("Starting NIO client at [%s:%s]", HOSTNAME, PORT)

      // Wait until the connection attempt succeeds or fails.
      connection.awaitUninterruptibly
      if (!connection.isSuccess) {
        log.error("Connection has failed due to [%s]", connection.getCause)
        connection.getCause.printStackTrace
      }
      isRunning = true
    }
  }

  def shutdown = synchronized {
    if (!isRunning) {
      connection.getChannel.getCloseFuture.awaitUninterruptibly
      channelFactory.releaseExternalResources
    }
  }

  def send(request: RemoteRequest): CompletableFutureResult = if (isRunning) {
    val escapedRequest = escapeRequest(request)
    if (escapedRequest.isOneWay) {
      connection.getChannel.write(escapedRequest)
      new NullFutureResult
    } else {
      futures.synchronized {
        val futureResult = new DefaultCompletableFutureResult(100000)
        futures.put(request.id, futureResult)
        connection.getChannel.write(escapedRequest)
        futureResult
      }      
    }
  } else throw new IllegalStateException("Netty client is not running, make sure you have invoked 'connect' before using the client")

  private def escapeRequest(request: RemoteRequest) = {
    if (request.message.isInstanceOf[Array[Object]]) {
      val args = request.message.asInstanceOf[Array[Object]].toList.asInstanceOf[scala.List[Object]]
      var isEscaped = false
      val escapedArgs = for (arg <- args) yield {
        val clazz = arg.getClass
        if (clazz.getName.contains("$$ProxiedByAW")) {
          isEscaped = true
          new ProxyWrapper(clazz.getSuperclass.getName)
        } else arg
      }
      request.cloneWithNewMessage(escapedArgs, isEscaped)
    } else request
  }
}

@ChannelPipelineCoverage { val value = "all" }
class ObjectClientHandler(val futures: ConcurrentMap[Long, CompletableFutureResult]) extends SimpleChannelUpstreamHandler with Logging {

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] && event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    // Add encoder and decoder as soon as a new channel is created so that
    // a Java object is serialized and deserialized.
    event.getChannel.getPipeline.addFirst("encoder", new ObjectEncoder)
    event.getChannel.getPipeline.addFirst("decoder", new ObjectDecoder)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) {
    // Send the first message if this handler is a client-side handler.
    //    if (!firstMessage.isEmpty) e.getChannel.write(firstMessage)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      val result = event.getMessage
      if (result.isInstanceOf[RemoteReply]) {
        val reply = result.asInstanceOf[RemoteReply]
        val future = futures.get(reply.id)
        if (reply.successful) future.completeWithResult(reply.message)
        else future.completeWithException(null, reply.exception)
      } else throw new IllegalArgumentException("Unknown message received in NIO client handler: " + result)
    } catch {
      case e: Exception => log.error("Unexpected exception in NIO client handler: %s", e); throw e
    }
  }                 

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) {
    log.error("Unexpected exception from downstream: %s", event.getCause)
    event.getChannel.close
  }
}
