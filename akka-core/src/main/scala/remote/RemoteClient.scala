/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}
import se.scalablesolutions.akka.actor.{Exit, Actor}
import se.scalablesolutions.akka.dispatch.{DefaultCompletableFutureResult, CompletableFutureResult}
import se.scalablesolutions.akka.util.{UUID, Logging}
import se.scalablesolutions.akka.Config.config

import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.compression.{ZlibDecoder, ZlibEncoder}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.timeout.ReadTimeoutHandler
import org.jboss.netty.util.{TimerTask, Timeout, HashedWheelTimer}

import java.net.{SocketAddress, InetSocketAddress}
import java.util.concurrent.{TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicLong

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteRequestIdFactory {
  private val nodeId = UUID.newUuid
  private val id = new AtomicLong
  def nextId: Long = id.getAndIncrement + nodeId
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClient extends Logging {
  val READ_TIMEOUT = config.getInt("akka.remote.client.read-timeout", 10000)
  val RECONNECT_DELAY = config.getInt("akka.remote.client.reconnect-delay", 5000)

  private val clients = new HashMap[String, RemoteClient]

  def clientFor(address: InetSocketAddress): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    if (clients.contains(hash)) clients(hash)
    else {
      val client = new RemoteClient(hostname, port)
      client.connect
      clients += hash -> client
      client
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownAll() = synchronized {
    clients.foreach({case (addr, client) => client.shutdown})
    clients.clear
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClient(hostname: String, port: Int) extends Logging {
  val name = "RemoteClient@" + hostname
  
  @volatile private var isRunning = false 
  private val futures = new ConcurrentHashMap[Long, CompletableFutureResult]
  private val supervisors = new ConcurrentHashMap[String, Actor]

  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)

  private val timer = new HashedWheelTimer
  private val remoteAddress = new InetSocketAddress(hostname, port)
  private[remote] var connection: ChannelFuture = _

  bootstrap.setPipelineFactory(new RemoteClientPipelineFactory(name, futures, supervisors, bootstrap, remoteAddress, timer, this))
  bootstrap.setOption("tcpNoDelay", true)
  bootstrap.setOption("keepAlive", true)

  def connect = synchronized {
    if (!isRunning) {
      connection = bootstrap.connect(remoteAddress)
      log.info("Starting remote client connection to [%s:%s]", hostname, port)

      // Wait until the connection attempt succeeds or fails.
      connection.awaitUninterruptibly
      if (!connection.isSuccess) log.error(connection.getCause, "Remote connection to [%s:%s] has failed", hostname, port)
      isRunning = true
    }
  }

  def shutdown = synchronized {
    if (!isRunning) {
      connection.getChannel.getCloseFuture.awaitUninterruptibly
      channelFactory.releaseExternalResources
    }
    timer.stop
  }

  def send(request: RemoteRequest, senderFuture: Option[CompletableFutureResult]): Option[CompletableFutureResult] = if (isRunning) {
    if (request.getIsOneWay) {
      connection.getChannel.write(request)
      None
    } else {
      futures.synchronized {
        val futureResult = if (senderFuture.isDefined) senderFuture.get
                           else new DefaultCompletableFutureResult(request.getTimeout)
        futures.put(request.getId, futureResult)
        connection.getChannel.write(request)
        Some(futureResult)
      }      
    }
  } else throw new IllegalStateException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.")

  def registerSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't register supervisor for " + actor + " since it is not under supervision")
    else supervisors.putIfAbsent(actor._supervisor.get.uuid, actor)

  def deregisterSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't unregister supervisor for " + actor + " since it is not under supervision")
    else supervisors.remove(actor._supervisor.get.uuid)
  
  def deregisterSupervisorWithUuid(uuid: String) = supervisors.remove(uuid)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClientPipelineFactory(name: String, 
                                  futures: ConcurrentMap[Long, CompletableFutureResult],
                                  supervisors: ConcurrentMap[String, Actor],
                                  bootstrap: ClientBootstrap,
                                  remoteAddress: SocketAddress,
                                  timer: HashedWheelTimer,
                                  client: RemoteClient) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val timeout      = new ReadTimeoutHandler(timer, RemoteClient.READ_TIMEOUT)
    val lenDec       = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep      = new LengthFieldPrepender(4)
    val protobufDec  = new ProtobufDecoder(RemoteReply.getDefaultInstance)
    val protobufEnc  = new ProtobufEncoder
    val zipCodec = RemoteServer.COMPRESSION_SCHEME match {
      case "zlib"  => Some(Codec(new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL), new ZlibDecoder))
      //case "lzf" => Some(Codec(new LzfEncoder, new LzfDecoder))
      case _ => None
    }
    val remoteClient = new RemoteClientHandler(name, futures, supervisors, bootstrap, remoteAddress, timer, client)

    val stages: Array[ChannelHandler] = 
      zipCodec.map(codec => Array(timeout, codec.decoder, lenDec, protobufDec, codec.encoder, lenPrep, protobufEnc, remoteClient))
              .getOrElse(Array(timeout, lenDec, protobufDec, lenPrep, protobufEnc, remoteClient))
    new StaticChannelPipeline(stages: _*)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelPipelineCoverage { val value = "all" }
class RemoteClientHandler(val name: String,
                          val futures: ConcurrentMap[Long, CompletableFutureResult],
                          val supervisors: ConcurrentMap[String, Actor],
                          val bootstrap: ClientBootstrap,
                          val remoteAddress: SocketAddress,
                          val timer: HashedWheelTimer,
                          val client: RemoteClient)
 extends SimpleChannelUpstreamHandler with Logging {
  import Actor.Sender.Self

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] &&
        event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      val result = event.getMessage
      if (result.isInstanceOf[RemoteReply]) {
        val reply = result.asInstanceOf[RemoteReply]
        log.debug("Remote client received RemoteReply[\n%s]", reply.toString)
        val future = futures.get(reply.getId)
        if (reply.getIsSuccessful) {
          val message = RemoteProtocolBuilder.getMessage(reply)
          future.completeWithResult(message)
        } else {
          if (reply.hasSupervisorUuid()) {
            val supervisorUuid = reply.getSupervisorUuid
            if (!supervisors.containsKey(supervisorUuid)) throw new IllegalStateException("Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor._supervisor.isDefined) throw new IllegalStateException("Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor._supervisor.get ! Exit(supervisedActor, parseException(reply))
          }
          future.completeWithException(null, parseException(reply))
        }
        futures.remove(reply.getId)
      } else throw new IllegalArgumentException("Unknown message received in remote client handler: " + result)
    } catch {
      case e: Exception =>
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }                 

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    timer.newTimeout(new TimerTask() {
      def run(timeout: Timeout) = {
        log.debug("Remote client reconnecting to [%s]", remoteAddress)
        client.connection = bootstrap.connect(remoteAddress)

        // Wait until the connection attempt succeeds or fails.
        client.connection.awaitUninterruptibly
        if (!client.connection.isSuccess) log.error(client.connection.getCause, "Reconnection to [%s] has failed", remoteAddress)
      }
    }, RemoteClient.RECONNECT_DELAY, TimeUnit.MILLISECONDS)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) =
    log.debug("Remote client connected to [%s]", ctx.getChannel.getRemoteAddress)

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) =
    log.debug("Remote client disconnected from [%s]", ctx.getChannel.getRemoteAddress);

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.error(event.getCause, "Unexpected exception from downstream in remote client")
    event.getChannel.close
  }

  private def parseException(reply: RemoteReply) = {
    val exception = reply.getException
    val exceptionType = Class.forName(exception.substring(0, exception.indexOf('$')))
    val exceptionMessage = exception.substring(exception.indexOf('$') + 1, exception.length)
    exceptionType
      .getConstructor(Array[Class[_]](classOf[String]): _*)
      .newInstance(exceptionMessage).asInstanceOf[Throwable]
  }
}
