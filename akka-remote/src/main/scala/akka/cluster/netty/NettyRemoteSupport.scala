/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.netty

import akka.dispatch.{ ActorPromise, DefaultPromise, Promise }
import akka.cluster.{ MessageSerializer, RemoteClientSettings, RemoteServerSettings }
import akka.cluster.RemoteProtocol._
import akka.serialization.RemoteActorSerialization
import akka.serialization.RemoteActorSerialization._
import akka.cluster._
import akka.actor.{
  PoisonPill,
  Actor,
  RemoteActorRef,
  ActorRef,
  IllegalActorStateException,
  RemoteActorSystemMessage,
  uuidFrom,
  Uuid,
  LifeCycleMessage,
  Address
}
import akka.actor.Actor._
import akka.config.Config
import Config._
import akka.util._
import akka.event.EventHandler

import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroup, ChannelGroupFuture }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.bootstrap.{ ServerBootstrap, ClientBootstrap }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.compression.{ ZlibDecoder, ZlibEncoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import org.jboss.netty.handler.execution.{ OrderedMemoryAwareThreadPoolExecutor, ExecutionHandler }
import org.jboss.netty.util.{ TimerTask, Timeout, HashedWheelTimer }

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import java.util.concurrent._
import akka.AkkaException

class RemoteClientMessageBufferException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

object RemoteEncoder {
  def encode(rmp: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setMessage(rmp)
    arp.build
  }

  def encode(rcp: RemoteControlProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setInstruction(rcp)
    arp.build
  }
}

trait NettyRemoteClientModule extends RemoteClientModule {
  self: ListenerManagement ⇒
  private val remoteClients = new HashMap[RemoteAddress, RemoteClient]
  private val remoteActors = new Index[RemoteAddress, Uuid]
  private val lock = new ReadWriteGuard

  protected[akka] def send[T](message: Any,
                              senderOption: Option[ActorRef],
                              senderFuture: Option[Promise[T]],
                              remoteAddress: InetSocketAddress,
                              timeout: Long,
                              isOneWay: Boolean,
                              actorRef: ActorRef,
                              loader: Option[ClassLoader]): Option[Promise[T]] =
    withClientFor(remoteAddress, loader) { client ⇒
      client.send[T](message, senderOption, senderFuture, remoteAddress, timeout, isOneWay, actorRef)
    }

  private[akka] def withClientFor[T](
    address: InetSocketAddress, loader: Option[ClassLoader])(body: RemoteClient ⇒ T): T = {
    // loader.foreach(MessageSerializer.setClassLoader(_))
    val key = RemoteAddress(address)
    lock.readLock.lock
    try {
      val client = remoteClients.get(key) match {
        case Some(client) ⇒ client
        case None ⇒
          lock.readLock.unlock
          lock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(key) match {
                //Recheck for addition, race between upgrades
                case Some(client) ⇒ client //If already populated by other writer
                case None ⇒ //Populate map
                  val client = new ActiveRemoteClient(this, address, loader, self.notifyListeners _)
                  client.connect()
                  remoteClients += key -> client
                  client
              }
            } finally {
              lock.readLock.lock
            } //downgrade
          } finally {
            lock.writeLock.unlock
          }
      }
      body(client)
    } finally {
      lock.readLock.unlock
    }
  }

  def shutdownClientConnection(address: InetSocketAddress): Boolean = lock withWriteGuard {
    remoteClients.remove(RemoteAddress(address)) match {
      case Some(client) ⇒ client.shutdown()
      case None         ⇒ false
    }
  }

  def restartClientConnection(address: InetSocketAddress): Boolean = lock withReadGuard {
    remoteClients.get(RemoteAddress(address)) match {
      case Some(client) ⇒ client.connect(reconnectIfAlreadyConnected = true)
      case None         ⇒ false
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownClientModule() {
    shutdownRemoteClients()
    //TODO: Should we empty our remoteActors too?
    //remoteActors.clear
  }

  def shutdownRemoteClients() = lock withWriteGuard {
    remoteClients.foreach({
      case (addr, client) ⇒ client.shutdown()
    })
    remoteClients.clear()
  }
}

/**
 * This is the abstract baseclass for netty remote clients, currently there's only an
 * ActiveRemoteClient, but others could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
abstract class RemoteClient private[akka] (
  val module: NettyRemoteClientModule,
  val remoteAddress: InetSocketAddress) {

  val useTransactionLog = config.getBool("akka.cluster.client.buffering.retry-message-send-on-failure", false)
  val transactionLogCapacity = config.getInt("akka.cluster.client.buffering.capacity", -1)

  val name = this.getClass.getSimpleName + "@" +
    remoteAddress.getAddress.getHostAddress + "::" +
    remoteAddress.getPort

  protected val futures = new ConcurrentHashMap[Uuid, Promise[_]]
  protected val pendingRequests = {
    if (transactionLogCapacity < 0) new ConcurrentLinkedQueue[(Boolean, Uuid, RemoteMessageProtocol)]
    else new LinkedBlockingQueue[(Boolean, Uuid, RemoteMessageProtocol)](transactionLogCapacity)
  }

  private[cluster] val runSwitch = new Switch()

  private[cluster] def isRunning = runSwitch.isOn

  protected def notifyListeners(msg: ⇒ Any): Unit

  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean

  def shutdown(): Boolean

  /**
   * Returns an array with the current pending messages not yet delivered.
   */
  def pendingMessages: Array[Any] = {
    var messages = Vector[Any]()
    val iter = pendingRequests.iterator
    while (iter.hasNext) {
      val (_, _, message) = iter.next
      messages = messages :+ MessageSerializer.deserialize(message.getMessage)
    }
    messages.toArray
  }

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send[T](
    message: Any,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]],
    remoteAddress: InetSocketAddress,
    timeout: Long,
    isOneWay: Boolean,
    actorRef: ActorRef): Option[Promise[T]] = {
    val messageProtocol = createRemoteMessageProtocolBuilder(
      Some(actorRef), Left(actorRef.uuid), actorRef.address, timeout, Right(message), isOneWay, senderOption).build
    send(messageProtocol, senderFuture)
  }

  /**
   * Sends the message across the wire
   */
  def send[T](
    request: RemoteMessageProtocol,
    senderFuture: Option[Promise[T]]): Option[Promise[T]] = {

    if (isRunning) {
      EventHandler.debug(this, "Sending to connection [%s] message [\n%s]".format(remoteAddress, request))

      // tell
      if (request.getOneWay) {
        try {
          val future = currentChannel.write(RemoteEncoder.encode(request))
          future.awaitUninterruptibly()
          if (!future.isCancelled && !future.isSuccess) {
            notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
          }
        } catch {
          case e: Exception ⇒
            notifyListeners(RemoteClientError(e, module, remoteAddress))

            if (useTransactionLog && !pendingRequests.offer((true, null, request))) { // Add the request to the tx log after a failing send
              pendingRequests.clear()
              throw new RemoteClientMessageBufferException("Buffer limit [" + transactionLogCapacity + "] reached")
            }
        }
        None

        // ask
      } else {
        val futureResult =
          if (senderFuture.isDefined) senderFuture.get
          else new DefaultPromise[T](request.getActorInfo.getTimeout)

        val futureUuid = uuidFrom(request.getUuid.getHigh, request.getUuid.getLow)
        futures.put(futureUuid, futureResult) // Add future prematurely, remove it if write fails

        def handleRequestReplyError(future: ChannelFuture) = {
          if (useTransactionLog && !pendingRequests.offer((false, futureUuid, request))) { // Add the request to the tx log after a failing send
            pendingRequests.clear()
            throw new RemoteClientMessageBufferException("Buffer limit [" + transactionLogCapacity + "] reached")

          } else {
            val f = futures.remove(futureUuid) // Clean up future
            if (f ne null) f.completeWithException(future.getCause)
          }
        }

        var future: ChannelFuture = null
        try {
          // try to send the original one
          future = currentChannel.write(RemoteEncoder.encode(request))
          future.awaitUninterruptibly()

          if (future.isCancelled || !future.isSuccess) {
            notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
            handleRequestReplyError(future)
          }

        } catch {
          case e: Exception ⇒
            notifyListeners(RemoteClientWriteFailed(request, e, module, remoteAddress))
            handleRequestReplyError(future)
        }
        Some(futureResult)
      }

    } else {
      val exception = new RemoteClientException("RemoteModule client is not running, make sure you have invoked 'RemoteClient.connect()' before using it.", module, remoteAddress)
      notifyListeners(RemoteClientError(exception, module, remoteAddress))
      throw exception
    }
  }

  private[cluster] def sendPendingRequests() = pendingRequests synchronized {
    // ensure only one thread at a time can flush the log
    val nrOfMessages = pendingRequests.size
    if (nrOfMessages > 0) EventHandler.info(this, "Resending [%s] previously failed messages after remote client reconnect" format nrOfMessages)
    var pendingRequest = pendingRequests.peek

    while (pendingRequest ne null) {
      val (isOneWay, futureUuid, message) = pendingRequest

      if (isOneWay) {
        // tell
        val future = currentChannel.write(RemoteEncoder.encode(message))
        future.awaitUninterruptibly()

        if (future.isCancelled && !future.isSuccess) {
          notifyListeners(RemoteClientWriteFailed(message, future.getCause, module, remoteAddress))
        }

      } else {
        // ask
        val future = currentChannel.write(RemoteEncoder.encode(message))
        future.awaitUninterruptibly()

        if (future.isCancelled || !future.isSuccess) {
          val f = futures.remove(futureUuid) // Clean up future
          if (f ne null) f.completeWithException(future.getCause)
          notifyListeners(RemoteClientWriteFailed(message, future.getCause, module, remoteAddress))
        }
      }

      pendingRequests.remove(pendingRequest)
      pendingRequest = pendingRequests.peek // try to grab next message
    }
  }
}

/**
 *  RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClient private[akka] (
  module: NettyRemoteClientModule, remoteAddress: InetSocketAddress,
  val loader: Option[ClassLoader] = None, notifyListenersFun: (⇒ Any) ⇒ Unit) extends RemoteClient(module, remoteAddress) {

  import RemoteClientSettings._

  //FIXME rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile
  private var bootstrap: ClientBootstrap = _
  @volatile
  private[cluster] var connection: ChannelFuture = _
  @volatile
  private[cluster] var openChannels: DefaultChannelGroup = _
  @volatile
  private var timer: HashedWheelTimer = _
  @volatile
  private var reconnectionTimeWindowStart = 0L

  def notifyListeners(msg: ⇒ Any): Unit = notifyListenersFun(msg)

  def currentChannel = connection.getChannel

  /**
   * Connect to remote server.
   */
  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = {

    def sendSecureCookie(connection: ChannelFuture) {
      val handshake = RemoteControlProtocol.newBuilder.setCommandType(CommandType.CONNECT)
      if (SECURE_COOKIE.nonEmpty) handshake.setCookie(SECURE_COOKIE.get)
      connection.getChannel.write(RemoteEncoder.encode(handshake.build))
    }

    def closeChannel(connection: ChannelFuture) = {
      val channel = connection.getChannel
      openChannels.remove(channel)
      channel.close
    }

    def attemptReconnect(): Boolean = {
      EventHandler.debug(this, "Remote client reconnecting to [%s]".format(remoteAddress))

      val connection = bootstrap.connect(remoteAddress)
      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        EventHandler.error(connection.getCause, this, "Reconnection to [%s] has failed".format(remoteAddress))
        false

      } else {
        sendSecureCookie(connection)
        true
      }
    }

    runSwitch switchOn {
      openChannels = new DefaultDisposableChannelGroup(classOf[RemoteClient].getName)
      timer = new HashedWheelTimer

      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, futures, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      EventHandler.debug(this, "Starting remote client connection to [%s]".format(remoteAddress))

      connection = bootstrap.connect(remoteAddress)

      val channel = connection.awaitUninterruptibly.getChannel
      openChannels.add(channel)

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        EventHandler.error(connection.getCause, this, "Remote client connection to [%s] has failed".format(remoteAddress))
        false

      } else {
        sendSecureCookie(connection)

        //Add a task that does GCing of expired Futures
        timer.newTimeout(new TimerTask() {
          def run(timeout: Timeout) = {
            if (isRunning) {
              val i = futures.entrySet.iterator
              while (i.hasNext) {
                val e = i.next
                if (e.getValue.isExpired)
                  futures.remove(e.getKey)
              }
            }
          }
        }, RemoteClientSettings.REAP_FUTURES_DELAY.length, RemoteClientSettings.REAP_FUTURES_DELAY.unit)
        notifyListeners(RemoteClientStarted(module, remoteAddress))
        true
      }
    } match {
      case true ⇒ true
      case false if reconnectIfAlreadyConnected ⇒
        closeChannel(connection)

        EventHandler.debug(this, "Remote client reconnecting to [%s]".format(remoteAddress))
        attemptReconnect()

      case false ⇒ false
    }
  }

  // Please note that this method does _not_ remove the ARC from the NettyRemoteClientModule's map of clients
  def shutdown() = runSwitch switchOff {
    EventHandler.info(this, "Shutting down remote client [%s]".format(name))

    notifyListeners(RemoteClientShutdown(module, remoteAddress))
    timer.stop()
    timer = null
    openChannels.close.awaitUninterruptibly
    openChannels = null
    bootstrap.releaseExternalResources()
    bootstrap = null
    connection = null
    pendingRequests.clear()

    EventHandler.info(this, "[%s] has been shut down".format(name))
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = (RECONNECTION_TIME_WINDOW - (System.currentTimeMillis - reconnectionTimeWindowStart)) > 0
      if (timeLeft) {
        EventHandler.info(this, "Will try to reconnect to remote server for another [%s] milliseconds".format(timeLeft))
      }
      timeLeft
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClientPipelineFactory(
  name: String,
  futures: ConcurrentMap[Uuid, Promise[_]],
  bootstrap: ClientBootstrap,
  remoteAddress: InetSocketAddress,
  timer: HashedWheelTimer,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(timer, RemoteClientSettings.READ_TIMEOUT.length, RemoteClientSettings.READ_TIMEOUT.unit)
    val lenDec = new LengthFieldBasedFrameDecoder(RemoteClientSettings.MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec) = RemoteServerSettings.COMPRESSION_SCHEME match {
      case "zlib" ⇒ (new ZlibEncoder(RemoteServerSettings.ZLIB_COMPRESSION_LEVEL) :: Nil, new ZlibDecoder :: Nil)
      case _      ⇒ (Nil, Nil)
    }

    val remoteClient = new ActiveRemoteClientHandler(name, futures, bootstrap, remoteAddress, timer, client)
    val stages: List[ChannelHandler] = timeout :: dec ::: lenDec :: protobufDec :: enc ::: lenPrep :: protobufEnc :: remoteClient :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val name: String,
  val futures: ConcurrentMap[Uuid, Promise[_]],
  val bootstrap: ClientBootstrap,
  val remoteAddress: InetSocketAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler {

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction ⇒
          val rcp = arp.getInstruction
          rcp.getCommandType match {
            case CommandType.SHUTDOWN ⇒ spawn {
              client.module.shutdownClientConnection(remoteAddress)
            }
          }

        case arp: AkkaRemoteProtocol if arp.hasMessage ⇒
          val reply = arp.getMessage
          val replyUuid = uuidFrom(reply.getActorInfo.getUuid.getHigh, reply.getActorInfo.getUuid.getLow)
          EventHandler.debug(this, "Remote client received RemoteMessageProtocol[\n%s]\nTrying to map back to future: %s".format(reply, replyUuid))

          futures.remove(replyUuid).asInstanceOf[Promise[Any]] match {
            case null ⇒
              client.notifyListeners(RemoteClientError(new IllegalActorStateException("Future mapped to UUID " + replyUuid + " does not exist"), client.module, client.remoteAddress))

            case future ⇒
              if (reply.hasMessage) {
                val message = MessageSerializer.deserialize(reply.getMessage)
                future.completeWithResult(message)
              } else {
                future.completeWithException(parseException(reply, client.loader))
              }
          }

        case other ⇒
          throw new RemoteClientException("Unknown message received in remote client handler: " + other, client.module, client.remoteAddress)
      }
    } catch {
      case e: Exception ⇒
        EventHandler.error(e, this, e.getMessage)
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = client.runSwitch ifOn {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) = {
          if (client.isRunning) {
            client.openChannels.remove(event.getChannel)
            client.connect(reconnectIfAlreadyConnected = true)
          }
        }
      }, RemoteClientSettings.RECONNECT_DELAY.toMillis, TimeUnit.MILLISECONDS)
    } else spawn {
      client.module.shutdownClientConnection(remoteAddress) // spawn in another thread
    }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    try {
      if (client.useTransactionLog) client.sendPendingRequests() // try to send pending requests (still there after client/server crash ard reconnect
      client.notifyListeners(RemoteClientConnected(client.module, client.remoteAddress))
      EventHandler.debug(this, "Remote client connected to [%s]".format(ctx.getChannel.getRemoteAddress))
      client.resetReconnectionTimeWindow
    } catch {
      case e: Exception ⇒
        EventHandler.error(e, this, e.getMessage)
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.module, client.remoteAddress))
    EventHandler.debug(this, "Remote client disconnected from [%s]".format(ctx.getChannel.getRemoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    val cause = event.getCause
    if (cause ne null) {
      EventHandler.error(event.getCause, this, "Unexpected exception [%s] from downstream in remote client [%s]".format(event.getCause, event))

      cause match {
        case e: ReadTimeoutException ⇒
          spawn {
            client.module.shutdownClientConnection(remoteAddress) // spawn in another thread
          }
        case e: Exception ⇒
          client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
          event.getChannel.close //FIXME Is this the correct behavior?
      }

    } else EventHandler.error(this, "Unexpected exception from downstream in remote client [%s]".format(event))
  }

  private def parseException(reply: RemoteMessageProtocol, loader: Option[ClassLoader]): Throwable = {
    val exception = reply.getException
    val classname = exception.getClassname
    try {
      val exceptionClass =
        if (loader.isDefined) loader.get.loadClass(classname)
        else Class.forName(classname)
      exceptionClass
        .getConstructor(Array[Class[_]](classOf[String]): _*)
        .newInstance(exception.getMessage).asInstanceOf[Throwable]
    } catch {
      case problem: Exception ⇒
        EventHandler.error(problem, this, problem.getMessage)
        CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException(problem, classname, exception.getMessage)
    }
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport extends RemoteSupport with NettyRemoteServerModule with NettyRemoteClientModule {

  // Needed for remote testing and switching on/off under run
  val optimizeLocal = new AtomicBoolean(true)

  def optimizeLocalScoped_?() = optimizeLocal.get

  protected[akka] def actorFor(
    actorAddress: String,
    timeout: Long,
    host: String,
    port: Int,
    loader: Option[ClassLoader]): ActorRef = {

    val homeInetSocketAddress = this.address
    if (optimizeLocalScoped_?) {
      if ((host == homeInetSocketAddress.getAddress.getHostAddress ||
        host == homeInetSocketAddress.getHostName) &&
        port == homeInetSocketAddress.getPort) {
        //TODO: switch to InetSocketAddress.equals?
        val localRef = findActorByAddressOrUuid(actorAddress, actorAddress)
        if (localRef ne null) return localRef //Code significantly simpler with the return statement
      }
    }

    val remoteInetSocketAddress = new InetSocketAddress(host, port)
    EventHandler.debug(this,
      "Creating RemoteActorRef with address [%s] connected to [%s]"
        .format(actorAddress, remoteInetSocketAddress))
    RemoteActorRef(remoteInetSocketAddress, actorAddress, timeout, loader)
  }
}

class NettyRemoteServer(serverModule: NettyRemoteServerModule, val host: String, val port: Int, val loader: Option[ClassLoader]) {

  import RemoteServerSettings._

  val name = "NettyRemoteServer@" + host + ":" + port
  val address = new InetSocketAddress(host, port)

  private val factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)
  private val executor = new ExecutionHandler(
    new OrderedMemoryAwareThreadPoolExecutor(
      EXECUTION_POOL_SIZE,
      MAX_CHANNEL_MEMORY_SIZE,
      MAX_TOTAL_MEMORY_SIZE,
      EXECUTION_POOL_KEEPALIVE.length,
      EXECUTION_POOL_KEEPALIVE.unit))

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, executor, loader, serverModule)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", RemoteServerSettings.BACKLOG)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", RemoteServerSettings.CONNECTION_TIMEOUT.toMillis)

  openChannels.add(bootstrap.bind(address))
  serverModule.notifyListeners(RemoteServerStarted(serverModule))

  def shutdown() {
    EventHandler.info(this, "Shutting down remote server [%s]".format(name))
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        if (RemoteClientSettings.SECURE_COOKIE.nonEmpty)
          b.setCookie(RemoteClientSettings.SECURE_COOKIE.get)
        b.build
      }
      openChannels.write(RemoteEncoder.encode(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      executor.releaseExternalResources()
      serverModule.notifyListeners(RemoteServerShutdown(serverModule))
    } catch {
      case e: Exception ⇒
        EventHandler.error(e, this, e.getMessage)
    }
  }
}

trait NettyRemoteServerModule extends RemoteServerModule {
  self: RemoteModule ⇒

  private[akka] val currentServer = new AtomicReference[Option[NettyRemoteServer]](None)

  def address = currentServer.get match {
    case Some(server) ⇒ server.address
    case None         ⇒ ReflectiveAccess.RemoteModule.configDefaultAddress
  }

  def name = currentServer.get match {
    case Some(server) ⇒ server.name
    case None ⇒
      val a = ReflectiveAccess.RemoteModule.configDefaultAddress
      "NettyRemoteServer@" + a.getAddress.getHostAddress + ":" + a.getPort
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(_hostname: String, _port: Int, loader: Option[ClassLoader] = None): RemoteServerModule = guard withGuard {
    try {
      _isRunning switchOn {
        EventHandler.debug(this, "Starting up remote server on %s:s".format(_hostname, _port))

        currentServer.set(Some(new NettyRemoteServer(this, _hostname, _port, loader)))
      }
    } catch {
      case e: Exception ⇒
        EventHandler.error(e, this, e.getMessage)
        notifyListeners(RemoteServerError(e, this))
    }
    this
  }

  def shutdownServerModule() = guard withGuard {
    _isRunning switchOff {
      currentServer.getAndSet(None) foreach { instance ⇒
        EventHandler.debug(this, "Shutting down remote server on %s:%s".format(instance.host, instance.port))
        instance.shutdown()
      }
    }
  }

  /**
   * Register RemoteModule Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef): Unit = guard withGuard {
    if (id.startsWith(UUID_PREFIX)) register(id.substring(UUID_PREFIX.length), actorRef, actorsByUuid)
    else register(id, actorRef, actors)
  }

  def registerByUuid(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef.uuid.toString, actorRef, actorsByUuid)
  }

  private def register[Key](id: Key, actorRef: ActorRef, registry: ConcurrentHashMap[Key, ActorRef]) {
    if (_isRunning.isOn)
      registry.put(id, actorRef) //TODO change to putIfAbsent
  }

  /**
   * Register RemoteModule Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def registerPerSession(id: String, factory: ⇒ ActorRef): Unit = synchronized {
    registerPerSession(id, () ⇒ factory, actorsFactories)
  }

  private def registerPerSession[Key](id: Key, factory: () ⇒ ActorRef, registry: ConcurrentHashMap[Key, () ⇒ ActorRef]) {
    if (_isRunning.isOn)
      registry.put(id, factory) //TODO change to putIfAbsent
  }

  /**
   * Unregister RemoteModule Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef): Unit = guard withGuard {

    if (_isRunning.isOn) {
      EventHandler.debug(this, "Unregister server side remote actor with id [%s]".format(actorRef.uuid))

      actors.remove(actorRef.address, actorRef)
      actorsByUuid.remove(actorRef.uuid.toString, actorRef)
    }
  }

  /**
   * Unregister RemoteModule Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(id: String): Unit = guard withGuard {

    if (_isRunning.isOn) {
      EventHandler.debug(this, "Unregister server side remote actor with id [%s]".format(id))

      if (id.startsWith(UUID_PREFIX)) actorsByUuid.remove(id.substring(UUID_PREFIX.length))
      else {
        val actorRef = actors get id
        actorsByUuid.remove(actorRef.uuid.toString, actorRef)
        actors.remove(id, actorRef)
      }
    }
  }

  /**
   * Unregister RemoteModule Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterPerSession(id: String): Unit = {

    if (_isRunning.isOn) {
      EventHandler.info(this, "Unregistering server side remote actor with id [%s]".format(id))

      actorsFactories.remove(id)
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(
  val name: String,
  val openChannels: ChannelGroup,
  val executor: ExecutionHandler,
  val loader: Option[ClassLoader],
  val server: NettyRemoteServerModule) extends ChannelPipelineFactory {

  import RemoteServerSettings._

  def getPipeline: ChannelPipeline = {
    val lenDec = new LengthFieldBasedFrameDecoder(MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val (enc, dec) = COMPRESSION_SCHEME match {
      case "zlib" ⇒ (new ZlibEncoder(ZLIB_COMPRESSION_LEVEL) :: Nil, new ZlibDecoder :: Nil)
      case _      ⇒ (Nil, Nil)
    }
    val authenticator = if (REQUIRE_COOKIE) new RemoteServerAuthenticationHandler(SECURE_COOKIE) :: Nil else Nil
    val remoteServer = new RemoteServerHandler(name, openChannels, loader, server)
    val stages: List[ChannelHandler] = dec ::: lenDec :: protobufDec :: enc ::: lenPrep :: protobufEnc :: executor :: authenticator ::: remoteServer :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

@ChannelHandler.Sharable
class RemoteServerAuthenticationHandler(secureCookie: Option[String]) extends SimpleChannelUpstreamHandler {
  val authenticated = new AnyRef

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = secureCookie match {
    case None ⇒ ctx.sendUpstream(event)
    case Some(cookie) ⇒
      ctx.getAttachment match {
        case `authenticated` ⇒ ctx.sendUpstream(event)
        case null ⇒ event.getMessage match {
          case remoteProtocol: AkkaRemoteProtocol if remoteProtocol.hasInstruction ⇒
            remoteProtocol.getInstruction.getCookie match {
              case `cookie` ⇒
                ctx.setAttachment(authenticated)
                ctx.sendUpstream(event)
              case _ ⇒
                throw new SecurityException(
                  "The remote client [" + ctx.getChannel.getRemoteAddress + "] secure cookie is not the same as remote server secure cookie")
            }
          case _ ⇒
            throw new SecurityException("The remote client [" + ctx.getChannel.getRemoteAddress + "] is not authorized!")
        }
      }
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
  val server: NettyRemoteServerModule) extends SimpleChannelUpstreamHandler {

  import RemoteServerSettings._

  // applicationLoader.foreach(MessageSerializer.setClassLoader(_)) //TODO: REVISIT: THIS FEELS A BIT DODGY

  val sessionActors = new ChannelLocal[ConcurrentHashMap[String, ActorRef]]()

  //Writes the specified message to the specified channel and propagates write errors to listeners
  private def write(channel: Channel, payload: AkkaRemoteProtocol): Unit = {
    channel.write(payload).addListener(
      new ChannelFutureListener {
        def operationComplete(future: ChannelFuture): Unit = {
          if (future.isCancelled) {
            //Not interesting at the moment
          } else if (!future.isSuccess) {
            val socketAddress = future.getChannel.getRemoteAddress match {
              case i: InetSocketAddress ⇒ Some(i)
              case _                    ⇒ None
            }
            server.notifyListeners(RemoteServerWriteFailed(payload, future.getCause, server, socketAddress))
          }
        }
      })
  }

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    EventHandler.debug(this, "Remote client [%s] connected to [%s]".format(clientAddress, server.name))

    sessionActors.set(event.getChannel(), new ConcurrentHashMap[String, ActorRef]())
    server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)

    EventHandler.debug(this, "Remote client [%s] disconnected from [%s]".format(clientAddress, server.name))

    // stop all session actors
    for (
      map ← Option(sessionActors.remove(event.getChannel));
      actor ← collectionAsScalaIterable(map.values)
    ) {
      try {
        actor ! PoisonPill
      } catch {
        case e: Exception ⇒ EventHandler.error(e, this, "Couldn't stop %s".format(actor))
      }
    }

    server.notifyListeners(RemoteServerClientDisconnected(server, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    EventHandler.debug("Remote client [%s] channel closed from [%s]".format(clientAddress, server.name), this)

    server.notifyListeners(RemoteServerClientClosed(server, clientAddress))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    event.getMessage match {
      case null ⇒
        throw new IllegalActorStateException("Message in remote MessageEvent is null: " + event)

      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒
        handleRemoteMessageProtocol(remote.getMessage, event.getChannel)

      //case remote: AkkaRemoteProtocol if remote.hasInstruction => RemoteServer cannot receive control messages (yet)

      case _ ⇒ //ignore
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    EventHandler.error(event.getCause, this, "Unexpected exception from remote downstream")

    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def getClientAddress(ctx: ChannelHandlerContext): Option[InetSocketAddress] =
    ctx.getChannel.getRemoteAddress match {
      case inet: InetSocketAddress ⇒ Some(inet)
      case _                       ⇒ None
    }

  private def handleRemoteMessageProtocol(request: RemoteMessageProtocol, channel: Channel) = try {
    EventHandler.debug(this, "Received remote message [%s]".format(request))
    dispatchToActor(request, channel)
  } catch {
    case e: Exception ⇒
      server.notifyListeners(RemoteServerError(e, server))
      EventHandler.error(e, this, e.getMessage)
  }

  private def dispatchToActor(request: RemoteMessageProtocol, channel: Channel) {
    val actorInfo = request.getActorInfo

    EventHandler.debug(this, "Dispatching to remote actor [%s]".format(actorInfo.getUuid))

    val actorRef =
      try {
        createActor(actorInfo, channel)
      } catch {
        case e: SecurityException ⇒
          EventHandler.error(e, this, e.getMessage)
          write(channel, createErrorReplyMessage(e, request))
          server.notifyListeners(RemoteServerError(e, server))
          return
      }

    val message = MessageSerializer.deserialize(request.getMessage)
    val sender =
      if (request.hasSender) Some(RemoteActorSerialization.fromProtobufToRemoteActorRef(request.getSender, applicationLoader))
      else None

    message match {
      // first match on system messages
      case RemoteActorSystemMessage.Stop ⇒
        if (UNTRUSTED_MODE) throw new SecurityException("RemoteModule server is operating is untrusted mode, can not stop the actor")
        else actorRef.stop()

      case _: LifeCycleMessage if (UNTRUSTED_MODE) ⇒
        throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a LifeCycleMessage to the remote actor")

      case _ ⇒ // then match on user defined messages
        if (request.getOneWay) actorRef.!(message)(sender)
        else actorRef.postMessageToMailboxAndCreateFutureResultWithTimeout(
          message,
          request.getActorInfo.getTimeout,
          new ActorPromise(request.getActorInfo.getTimeout).
            onComplete(_.value.get match {
              case Left(exception) ⇒ write(channel, createErrorReplyMessage(exception, request))
              case r: Right[_, _] ⇒
                val messageBuilder = RemoteActorSerialization.createRemoteMessageProtocolBuilder(
                  Some(actorRef),
                  Right(request.getUuid),
                  actorInfo.getAddress,
                  actorInfo.getTimeout,
                  r.asInstanceOf[Either[Throwable, Any]],
                  isOneWay = true,
                  Some(actorRef))

                // FIXME lift in the supervisor uuid management into toh createRemoteMessageProtocolBuilder method
                if (request.hasSupervisorUuid) messageBuilder.setSupervisorUuid(request.getSupervisorUuid)

                write(channel, RemoteEncoder.encode(messageBuilder.build))
            }))
    }
  }

  /**
   * Creates a new instance of the actor with name, uuid and timeout specified as arguments.
   *
   * If actor already created then just return it from the registry.
   *
   * Does not start the actor.
   */
  private def createActor(actorInfo: ActorInfoProtocol, channel: Channel): ActorRef = {
    val uuid = actorInfo.getUuid
    val address = actorInfo.getAddress
    // val address = {
    //   // strip off clusterActorRefPrefix if needed
    //   val addr = actorInfo.getAddress
    //   if (addr.startsWith(Address.clusterActorRefPrefix)) addr.substring(addr.indexOf('.') + 1, addr.length)
    //   else addr
    // }

    EventHandler.debug(this,
      "Looking up a remotely available actor for address [%s] on node [%s]"
        .format(address, Config.nodename))

    val actorRef = Actor.createActor(address, () ⇒ createSessionActor(actorInfo, channel), false)

    if (actorRef eq null) throw new IllegalActorStateException("Could not find a remote actor with address [" + address + "] or uuid [" + uuid + "]")

    actorRef
  }

  /**
   * gets the actor from the session, or creates one if there is a factory for it
   */
  private def createSessionActor(actorInfo: ActorInfoProtocol, channel: Channel): ActorRef = {
    val uuid = actorInfo.getUuid
    val address = actorInfo.getAddress

    findSessionActor(address, channel) match {
      case null ⇒ // we dont have it in the session either, see if we have a factory for it
        server.findActorFactory(address) match {
          case null ⇒ null
          case factory ⇒
            val actorRef = factory()
            sessionActors.get(channel).put(address, actorRef)
            actorRef //Start it where's it's created
        }
      case sessionActor ⇒ sessionActor
    }
  }

  private def findSessionActor(id: String, channel: Channel): ActorRef =
    sessionActors.get(channel) match {
      case null ⇒ null
      case map  ⇒ map get id
    }

  private def createErrorReplyMessage(exception: Throwable, request: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val actorInfo = request.getActorInfo
    val messageBuilder = RemoteActorSerialization.createRemoteMessageProtocolBuilder(
      None,
      Right(request.getUuid),
      actorInfo.getAddress,
      actorInfo.getTimeout,
      Left(exception),
      true,
      None)
    if (request.hasSupervisorUuid) messageBuilder.setSupervisorUuid(request.getSupervisorUuid)
    RemoteEncoder.encode(messageBuilder.build)
  }

  protected def parseUuid(protocol: UuidProtocol): Uuid = uuidFrom(protocol.getHigh, protocol.getLow)
}

class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
  protected val guard = new ReadWriteGuard
  protected val open = new AtomicBoolean(true)

  override def add(channel: Channel): Boolean = guard withReadGuard {
    if (open.get) {
      super.add(channel)
    } else {
      channel.close
      false
    }
  }

  override def close(): ChannelGroupFuture = guard withWriteGuard {
    if (open.getAndSet(false)) {
      super.close
    } else {
      throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    }
  }
}
