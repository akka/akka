/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.remote.protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}
import se.scalablesolutions.akka.actor.{Exit, Actor}
import se.scalablesolutions.akka.dispatch.{DefaultCompletableFuture, CompletableFuture}
import se.scalablesolutions.akka.util.{UUID, Logging}
import se.scalablesolutions.akka.config.Config.config

import org.jboss.netty.channel._
import group.DefaultChannelGroup
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.compression.{ZlibDecoder, ZlibEncoder}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.timeout.ReadTimeoutHandler
import org.jboss.netty.util.{TimerTask, Timeout, HashedWheelTimer}

import java.net.{SocketAddress, InetSocketAddress}
import java.util.concurrent.{TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap, ConcurrentSkipListSet}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable.{HashSet, HashMap}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteRequestIdFactory {
  private val nodeId = UUID.newUuid
  private val id = new AtomicLong

  def nextId: Long = id.getAndIncrement + nodeId
}

sealed trait RemoteClientLifeCycleEvent
case class RemoteClientError(cause: Throwable) extends RemoteClientLifeCycleEvent
case class RemoteClientDisconnected(host: String, port: Int) extends RemoteClientLifeCycleEvent
case class RemoteClientConnected(host: String, port: Int) extends RemoteClientLifeCycleEvent

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClient extends Logging {
  val READ_TIMEOUT = config.getInt("akka.remote.client.read-timeout", 10000)
  val RECONNECT_DELAY = config.getInt("akka.remote.client.reconnect-delay", 5000)

  private val remoteClients = new HashMap[String, RemoteClient]
  private val remoteActors = new HashMap[RemoteServer.Address, HashSet[String]]

  // FIXME: simplify overloaded methods when we have Scala 2.8

  def actorFor(className: String, hostname: String, port: Int): Actor =
    actorFor(className, className, 5000L, hostname, port)

  def actorFor(actorId: String, className: String, hostname: String, port: Int): Actor =
    actorFor(actorId, className, 5000L, hostname, port)

  def actorFor(className: String, timeout: Long, hostname: String, port: Int): Actor =
    actorFor(className, className, timeout, hostname, port)

  def actorFor(actorId: String, className: String, timeout: Long, hostname: String, port: Int): Actor = {
    new Actor {
      start
      val remoteClient = RemoteClient.clientFor(hostname, port)

      override def postMessageToMailbox(message: Any, sender: Option[Actor]): Unit = {
        val requestBuilder = RemoteRequest.newBuilder
            .setId(RemoteRequestIdFactory.nextId)
            .setTarget(className)
            .setTimeout(timeout)
            .setUuid(actorId)
            .setIsActor(true)
            .setIsOneWay(true)
            .setIsEscaped(false)
        if (sender.isDefined) {
          val s = sender.get
          requestBuilder.setSourceTarget(s.getClass.getName)
          requestBuilder.setSourceUuid(s.uuid)
          val (host, port) = s._replyToAddress.map(a => (a.getHostName, a.getPort)).getOrElse((Actor.HOSTNAME, Actor.PORT))
          requestBuilder.setSourceHostname(host)
          requestBuilder.setSourcePort(port)
        }
        RemoteProtocolBuilder.setMessage(message, requestBuilder)
        remoteClient.send(requestBuilder.build, None)
      }

      override def postMessageToMailboxAndCreateFutureResultWithTimeout(
          message: Any,
          timeout: Long,
          senderFuture: Option[CompletableFuture]): CompletableFuture = {
        val requestBuilder = RemoteRequest.newBuilder
            .setId(RemoteRequestIdFactory.nextId)
            .setTarget(className)
            .setTimeout(timeout)
            .setUuid(actorId)
            .setIsActor(true)
            .setIsOneWay(false)
            .setIsEscaped(false)
        RemoteProtocolBuilder.setMessage(message, requestBuilder)
        val future = remoteClient.send(requestBuilder.build, senderFuture)
        if (future.isDefined) future.get
        else throw new IllegalStateException("Expected a future from remote call to actor " + toString)
      }

      def receive = {case _ => {}}
    }
  }

  def clientFor(hostname: String, port: Int): RemoteClient = clientFor(new InetSocketAddress(hostname, port))

  def clientFor(address: InetSocketAddress): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    if (remoteClients.contains(hash)) remoteClients(hash)
    else {
      val client = new RemoteClient(hostname, port)
      client.connect
      remoteClients += hash -> client
      client
    }
  }

  def shutdownClientFor(address: InetSocketAddress) = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    if (remoteClients.contains(hash)) {
      val client = remoteClients(hash)
      client.shutdown
      remoteClients -= hash
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownAll = synchronized {
    remoteClients.foreach({case (addr, client) => client.shutdown})
    remoteClients.clear
  }

  private[akka] def register(hostname: String, port: Int, uuid: String) = synchronized {
    actorsFor(RemoteServer.Address(hostname, port)) += uuid
  }

  // TODO: add RemoteClient.unregister for ActiveObject, but first need a @shutdown callback
  private[akka] def unregister(hostname: String, port: Int, uuid: String) = synchronized {
    val set = actorsFor(RemoteServer.Address(hostname, port))
    set -= uuid
    if (set.isEmpty) shutdownClientFor(new InetSocketAddress(hostname, port))
  }

  private[akka] def actorsFor(remoteServerAddress: RemoteServer.Address): HashSet[String] = {
    val set = remoteActors.get(remoteServerAddress)
    if (set.isDefined && (set.get ne null)) set.get
    else {
      val remoteActorSet = new HashSet[String]
      remoteActors.put(remoteServerAddress, remoteActorSet)
      remoteActorSet
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClient(val hostname: String, val port: Int) extends Logging {
  val name = "RemoteClient@" + hostname + "::" + port

  @volatile private[remote] var isRunning = false
  private val futures = new ConcurrentHashMap[Long, CompletableFuture]
  private val supervisors = new ConcurrentHashMap[String, Actor]
  private[remote] val listeners = new ConcurrentSkipListSet[Actor]

  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)
  private val openChannels = new DefaultChannelGroup(classOf[RemoteClient].getName);

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
      val channel = connection.awaitUninterruptibly.getChannel
      openChannels.add(channel)
      if (!connection.isSuccess) {
        listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(connection.getCause))
        log.error(connection.getCause, "Remote client connection to [%s:%s] has failed", hostname, port)
      }
      isRunning = true
    }
  }

  def shutdown = synchronized {
    if (isRunning) {
      isRunning = false
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources
      timer.stop
      log.info("%s has been shut down", name)
    }
  }

  def send(request: RemoteRequest, senderFuture: Option[CompletableFuture]): Option[CompletableFuture] = if (isRunning) {
    if (request.getIsOneWay) {
      connection.getChannel.write(request)
      None
    } else {
      futures.synchronized {
        val futureResult = if (senderFuture.isDefined) senderFuture.get
        else new DefaultCompletableFuture(request.getTimeout)
        futures.put(request.getId, futureResult)
        connection.getChannel.write(request)
        Some(futureResult)
      }
    }
  } else {
    val exception = new IllegalStateException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.")
    listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(exception))
    throw exception
  }

  def registerSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't register supervisor for " + actor + " since it is not under supervision")
    else supervisors.putIfAbsent(actor._supervisor.get.uuid, actor)

  def deregisterSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't unregister supervisor for " + actor + " since it is not under supervision")
    else supervisors.remove(actor._supervisor.get.uuid)

  def registerListener(actor: Actor) = listeners.add(actor)

  def deregisterListener(actor: Actor) = listeners.remove(actor)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClientPipelineFactory(name: String,
                                  futures: ConcurrentMap[Long, CompletableFuture],
                                  supervisors: ConcurrentMap[String, Actor],
                                  bootstrap: ClientBootstrap,
                                  remoteAddress: SocketAddress,
                                  timer: HashedWheelTimer,
                                  client: RemoteClient) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(timer, RemoteClient.READ_TIMEOUT)
    val lenDec = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteReply.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val zipCodec = RemoteServer.COMPRESSION_SCHEME match {
      case "zlib" => Some(Codec(new ZlibEncoder(RemoteServer.ZLIB_COMPRESSION_LEVEL), new ZlibDecoder))
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
@ChannelHandler.Sharable
class RemoteClientHandler(val name: String,
                          val futures: ConcurrentMap[Long, CompletableFuture],
                          val supervisors: ConcurrentMap[String, Actor],
                          val bootstrap: ClientBootstrap,
                          val remoteAddress: SocketAddress,
                          val timer: HashedWheelTimer,
                          val client: RemoteClient)
    extends SimpleChannelUpstreamHandler with Logging {

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
            if (!supervisors.containsKey(supervisorUuid))
              throw new IllegalStateException("Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor._supervisor.isDefined)
              throw new IllegalStateException("Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor._supervisor.get ! Exit(supervisedActor, parseException(reply))
          }
          future.completeWithException(null, parseException(reply))
        }
        futures.remove(reply.getId)
      } else {
        val exception = new IllegalArgumentException("Unknown message received in remote client handler: " + result)
        client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(exception))
        throw exception
      }
    } catch {
      case e: Exception =>
       client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(e))
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = if (client.isRunning) {
    timer.newTimeout(new TimerTask() {
      def run(timeout: Timeout) = {
        log.debug("Remote client reconnecting to [%s]", remoteAddress)
        client.connection = bootstrap.connect(remoteAddress)

        // Wait until the connection attempt succeeds or fails.
        client.connection.awaitUninterruptibly
        if (!client.connection.isSuccess) {
          client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(client.connection.getCause))
          log.error(client.connection.getCause, "Reconnection to [%s] has failed", remoteAddress)
        }
      }
    }, RemoteClient.RECONNECT_DELAY, TimeUnit.MILLISECONDS)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
   client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientConnected(client.hostname, client.port))
    log.debug("Remote client connected to [%s]", ctx.getChannel.getRemoteAddress)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientDisconnected(client.hostname, client.port))
    log.debug("Remote client disconnected from [%s]", ctx.getChannel.getRemoteAddress)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    client.listeners.toArray.foreach(l => l.asInstanceOf[Actor] ! RemoteClientError(event.getCause))
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
