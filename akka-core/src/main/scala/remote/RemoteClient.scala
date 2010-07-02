/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.actor.{Exit, Actor, ActorRef, RemoteActorRef, IllegalActorStateException}
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
 * Atomic remote request/reply message id generator.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteRequestProtocolIdFactory {
  private val nodeId = UUID.newUuid
  private val id = new AtomicLong

  def nextId: Long = id.getAndIncrement + nodeId
}

/**
 * Life-cycle events for RemoteClient.
 */
sealed trait RemoteClientLifeCycleEvent
case class RemoteClientError(cause: Throwable, host: String, port: Int) extends RemoteClientLifeCycleEvent
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

  def actorFor(className: String, hostname: String, port: Int): ActorRef =
    actorFor(className, className, 5000L, hostname, port, None)

  def actorFor(className: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(className, className, 5000L, hostname, port, Some(loader))

  def actorFor(uuid: String, className: String, hostname: String, port: Int): ActorRef =
    actorFor(uuid, className, 5000L, hostname, port, None)

  def actorFor(uuid: String, className: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(uuid, className, 5000L, hostname, port, Some(loader))

  def actorFor(className: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(className, className, timeout, hostname, port, None)

  def actorFor(className: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(className, className, timeout, hostname, port, Some(loader))

  def actorFor(uuid: String, className: String, timeout: Long, hostname: String, port: Int): ActorRef =
    RemoteActorRef(uuid, className, hostname, port, timeout, None)

  private[akka] def actorFor(uuid: String, className: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    RemoteActorRef(uuid, className, hostname, port, timeout, Some(loader))

  private[akka] def actorFor(uuid: String, className: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]): ActorRef =
    RemoteActorRef(uuid, className, hostname, port, timeout, loader)

  def clientFor(hostname: String, port: Int): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), None)

  def clientFor(hostname: String, port: Int, loader: ClassLoader): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), Some(loader))

  def clientFor(address: InetSocketAddress): RemoteClient =
    clientFor(address, None)

  def clientFor(address: InetSocketAddress, loader: ClassLoader): RemoteClient =
    clientFor(address, Some(loader))

  private[akka] def clientFor(hostname: String, port: Int, loader: Option[ClassLoader]): RemoteClient =
    clientFor(new InetSocketAddress(hostname, port), loader)

  private[akka] def clientFor(address: InetSocketAddress, loader: Option[ClassLoader]): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    loader.foreach(MessageSerializer.setClassLoader(_))
    if (remoteClients.contains(hash)) remoteClients(hash)
    else {
      val client = new RemoteClient(hostname, port, loader)
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
class RemoteClient private[akka] (val hostname: String, val port: Int, loader: Option[ClassLoader]) extends Logging {
  val name = "RemoteClient@" + hostname + "::" + port

  @volatile private[remote] var isRunning = false
  private val futures = new ConcurrentHashMap[Long, CompletableFuture[_]]
  private val supervisors = new ConcurrentHashMap[String, ActorRef]
  private[remote] val listeners = new ConcurrentSkipListSet[ActorRef]

  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)
  private val timer = new HashedWheelTimer
  private val remoteAddress = new InetSocketAddress(hostname, port)

  private[remote] var connection: ChannelFuture = _
  private[remote] val openChannels = new DefaultChannelGroup(classOf[RemoteClient].getName);

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
        listeners.toArray.foreach(l => l.asInstanceOf[ActorRef] ! RemoteClientError(connection.getCause, hostname, port))
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

  def registerListener(actorRef: ActorRef) = listeners.add(actorRef)

  def deregisterListener(actorRef: ActorRef) = listeners.remove(actorRef)

  def send[T](request: RemoteRequestProtocol, senderFuture: Option[CompletableFuture[T]]): Option[CompletableFuture[T]] = if (isRunning) {
    if (request.getIsOneWay) {
      connection.getChannel.write(request)
      None
    } else {
      futures.synchronized {
        val futureResult = if (senderFuture.isDefined) senderFuture.get
        else new DefaultCompletableFuture[T](request.getTimeout)
        futures.put(request.getId, futureResult)
        connection.getChannel.write(request)
        Some(futureResult)
      }
    }
  } else {
    val exception = new IllegalStateException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.")
    listeners.toArray.foreach(l => l.asInstanceOf[ActorRef] ! RemoteClientError(exception, hostname, port))
    throw exception
  }

  private[akka] def registerSupervisorForActor(actorRef: ActorRef) =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException("Can't register supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.putIfAbsent(actorRef.supervisor.get.uuid, actorRef)

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef) =
    if (!actorRef.supervisor.isDefined) throw new IllegalActorStateException("Can't unregister supervisor for " + actorRef + " since it is not under supervision")
    else supervisors.remove(actorRef.supervisor.get.uuid)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClientPipelineFactory(name: String,
                                  futures: ConcurrentMap[Long, CompletableFuture[_]],
                                  supervisors: ConcurrentMap[String, ActorRef],
                                  bootstrap: ClientBootstrap,
                                  remoteAddress: SocketAddress,
                                  timer: HashedWheelTimer,
                                  client: RemoteClient) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(timer, RemoteClient.READ_TIMEOUT)
    val lenDec = new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(RemoteReplyProtocol.getDefaultInstance)
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
                          val futures: ConcurrentMap[Long, CompletableFuture[_]],
                          val supervisors: ConcurrentMap[String, ActorRef],
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
      if (result.isInstanceOf[RemoteReplyProtocol]) {
        val reply = result.asInstanceOf[RemoteReplyProtocol]
        log.debug("Remote client received RemoteReplyProtocol[\n%s]", reply.toString)
        val future = futures.get(reply.getId).asInstanceOf[CompletableFuture[Any]]
        if (reply.getIsSuccessful) {
          val message = MessageSerializer.deserialize(reply.getMessage)
          future.completeWithResult(message)
        } else {
          if (reply.hasSupervisorUuid()) {
            val supervisorUuid = reply.getSupervisorUuid
            if (!supervisors.containsKey(supervisorUuid)) throw new IllegalActorStateException(
              "Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor.supervisor.isDefined) throw new IllegalActorStateException(
              "Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor.supervisor.get ! Exit(supervisedActor, parseException(reply))
          }
          future.completeWithException(null, parseException(reply))
        }
        futures.remove(reply.getId)
      } else {
        val exception = new IllegalArgumentException("Unknown message received in remote client handler: " + result)
        client.listeners.toArray.foreach(l => l.asInstanceOf[ActorRef] ! RemoteClientError(exception, client.hostname, client.port))
        throw exception
      }
    } catch {
      case e: Exception =>
        client.listeners.toArray.foreach(l => l.asInstanceOf[ActorRef] ! RemoteClientError(e, client.hostname, client.port))
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = if (client.isRunning) {
    timer.newTimeout(new TimerTask() {
      def run(timeout: Timeout) = {
        client.openChannels.remove(event.getChannel)
        log.debug("Remote client reconnecting to [%s]", remoteAddress)
        client.connection = bootstrap.connect(remoteAddress)
        client.connection.awaitUninterruptibly // Wait until the connection attempt succeeds or fails.
        if (!client.connection.isSuccess) {
          client.listeners.toArray.foreach(l =>
            l.asInstanceOf[ActorRef] ! RemoteClientError(client.connection.getCause, client.hostname, client.port))
          log.error(client.connection.getCause, "Reconnection to [%s] has failed", remoteAddress)
        }
      }
    }, RemoteClient.RECONNECT_DELAY, TimeUnit.MILLISECONDS)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.listeners.toArray.foreach(l =>
      l.asInstanceOf[ActorRef] ! RemoteClientConnected(client.hostname, client.port))
    log.debug("Remote client connected to [%s]", ctx.getChannel.getRemoteAddress)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.listeners.toArray.foreach(l =>
      l.asInstanceOf[ActorRef] ! RemoteClientDisconnected(client.hostname, client.port))
    log.debug("Remote client disconnected from [%s]", ctx.getChannel.getRemoteAddress)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    client.listeners.toArray.foreach(l => l.asInstanceOf[ActorRef] ! RemoteClientError(event.getCause, client.hostname, client.port))
    log.error(event.getCause, "Unexpected exception from downstream in remote client")
    event.getChannel.close
  }

  private def parseException(reply: RemoteReplyProtocol): Throwable = {
    val exception = reply.getException
    val exceptionClass = Class.forName(exception.getClassname)
    exceptionClass
        .getConstructor(Array[Class[_]](classOf[String]): _*)
        .newInstance(exception.getMessage).asInstanceOf[Throwable]
  }
}
