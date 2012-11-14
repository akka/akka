/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.Executors
import scala.collection.mutable
import scala.collection.immutable
import scala.util.control.NonFatal
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroupFuture }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.{ ChannelHandlerContext, Channel, DefaultChannelPipeline, ChannelHandler, ChannelPipelineFactory, ChannelLocal }
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufEncoder, ProtobufDecoder }
import org.jboss.netty.handler.execution.{ ExecutionHandler, OrderedMemoryAwareThreadPoolExecutor }
import org.jboss.netty.handler.timeout.IdleStateHandler
import org.jboss.netty.util.{ DefaultObjectSizeEstimator, HashedWheelTimer }
import akka.event.Logging
import akka.remote.RemoteProtocol.AkkaRemoteProtocol
import akka.remote.{ RemoteTransportException, RemoteTransport, RemoteActorRefProvider, RemoteActorRef, RemoteServerStarted }
import akka.actor.{ ExtendedActorSystem, Address, ActorRef }
import com.google.protobuf.MessageLite

private[akka] object ChannelAddress extends ChannelLocal[Option[Address]] {
  override def initialValue(ch: Channel): Option[Address] = None
}

/**
 * Provides the implementation of the Netty remote support
 */
private[akka] class NettyRemoteTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  import provider.remoteSettings

  val settings = new NettySettings(remoteSettings.config.getConfig("akka.remote.netty"), remoteSettings.systemName)

  // TODO replace by system.scheduler
  val timer: HashedWheelTimer = new HashedWheelTimer(system.threadFactory)

  val clientChannelFactory = {
    val boss, worker = settings.UseDispatcherForIO.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
    new NioClientSocketChannelFactory(boss, worker, settings.ClientSocketWorkerPoolSize)
  }

  /**
   * Backing scaffolding for the default implementation of NettyRemoteSupport.createPipeline.
   */
  object PipelineFactory {
    /**
     * Construct a DefaultChannelPipeline from a sequence of handlers; to be used
     * in implementations of ChannelPipelineFactory.
     */
    def apply(handlers: immutable.Seq[ChannelHandler]): DefaultChannelPipeline =
      (new DefaultChannelPipeline /: handlers) { (p, h) ⇒ p.addLast(Logging.simpleName(h.getClass), h); p }

    /**
     * Constructs the NettyRemoteTransport default pipeline with the give “head” handler, which
     * is taken by-name to allow it not to be shared across pipelines.
     *
     * @param withTimeout determines whether an IdleStateHandler shall be included
     */
    def apply(endpoint: ⇒ Seq[ChannelHandler], withTimeout: Boolean, isClient: Boolean): ChannelPipelineFactory =
      new ChannelPipelineFactory { override def getPipeline = apply(defaultStack(withTimeout, isClient) ++ endpoint) }

    /**
     * Construct a default protocol stack, excluding the “head” handler (i.e. the one which
     * actually dispatches the received messages to the local target actors).
     */
    def defaultStack(withTimeout: Boolean, isClient: Boolean): immutable.Seq[ChannelHandler] =
      (if (settings.EnableSSL) List(NettySSLSupport(settings, NettyRemoteTransport.this.log, isClient)) else Nil) :::
        (if (withTimeout) List(timeout) else Nil) :::
        msgFormat :::
        authenticator :::
        executionHandler

    /**
     * Construct an IdleStateHandler which uses [[akka.remote.netty.NettyRemoteTransport]].timer.
     */
    def timeout = new IdleStateHandler(timer,
      settings.ReadTimeout.toSeconds.toInt,
      settings.WriteTimeout.toSeconds.toInt,
      settings.AllTimeout.toSeconds.toInt)

    /**
     * Construct frame&protobuf encoder/decoder.
     */
    def msgFormat = new LengthFieldBasedFrameDecoder(settings.MessageFrameSize, 0, 4, 0, 4) ::
      new LengthFieldPrepender(4) ::
      new RemoteMessageDecoder ::
      new RemoteMessageEncoder(NettyRemoteTransport.this) ::
      Nil

    /**
     * Construct an ExecutionHandler which is used to ensure that message dispatch does not
     * happen on a netty thread (that could be bad if re-sending over the network for
     * remote-deployed actors).
     */
    val executionHandler = if (settings.ExecutionPoolSize != 0)
      List(new ExecutionHandler(new OrderedMemoryAwareThreadPoolExecutor(
        settings.ExecutionPoolSize,
        settings.MaxChannelMemorySize,
        settings.MaxTotalMemorySize,
        settings.ExecutionPoolKeepalive.length,
        settings.ExecutionPoolKeepalive.unit,
        AkkaProtocolMessageSizeEstimator,
        system.threadFactory)))
    else Nil

    /**
     * Helps keep track of how many bytes are in flight
     */
    object AkkaProtocolMessageSizeEstimator extends DefaultObjectSizeEstimator {
      override final def estimateSize(o: AnyRef): Int =
        o match {
          case proto: MessageLite ⇒
            val msgSize = proto.getSerializedSize
            val misalignment = msgSize % 8
            if (misalignment != 0) msgSize + 8 - misalignment else msgSize
          case msg ⇒ super.estimateSize(msg)
        }
    }

    /**
     * Construct and authentication handler which uses the SecureCookie to somewhat
     * protect the TCP port from unauthorized use (don’t rely on it too much, though,
     * as this is NOT a cryptographic feature).
     */
    def authenticator = if (settings.RequireCookie) List(new RemoteServerAuthenticationHandler(settings.SecureCookie)) else Nil
  }

  /**
   * This method is factored out to provide an extension point in case the
   * pipeline shall be changed. It is recommended to use
   */
  def createPipeline(endpoint: ⇒ ChannelHandler, withTimeout: Boolean, isClient: Boolean): ChannelPipelineFactory =
    PipelineFactory(Seq(endpoint), withTimeout, isClient)

  private val remoteClients = new mutable.HashMap[Address, RemoteClient]
  private val clientsLock = new ReentrantReadWriteLock

  override protected def useUntrustedMode = remoteSettings.UntrustedMode

  override protected def logRemoteLifeCycleEvents = remoteSettings.LogRemoteLifeCycleEvents

  val server: NettyRemoteServer = try createServer() catch { case NonFatal(ex) ⇒ shutdown(); throw ex }

  /**
   * Override this method to inject a subclass of NettyRemoteServer instead of
   * the normal one, e.g. for inserting security hooks. If this method throws
   * an exception, the transport will shut itself down and re-throw.
   */
  protected def createServer(): NettyRemoteServer = new NettyRemoteServer(this)

  /**
   * Override this method to inject a subclass of RemoteClient instead of
   * the normal one, e.g. for inserting security hooks. Get this transport’s
   * address from `this.address`.
   */
  protected def createClient(recipient: Address): RemoteClient = new ActiveRemoteClient(this, recipient, address)

  // the address is set in start() or from the RemoteServerHandler, whichever comes first
  private val _address = new AtomicReference[Address]
  private[akka] def setAddressFromChannel(ch: Channel) = {
    val addr = ch.getLocalAddress match {
      case sa: InetSocketAddress ⇒ sa
      case x                     ⇒ throw new RemoteTransportException("unknown local address type " + x.getClass, null)
    }
    _address.compareAndSet(null, Address("akka", remoteSettings.systemName, settings.Hostname, addr.getPort))
  }

  def address = _address.get

  lazy val log = Logging(system.eventStream, "NettyRemoteTransport(" + address + ")")

  def start(): Unit = {
    server.start()
    setAddressFromChannel(server.channel)
    notifyListeners(RemoteServerStarted(this))
  }

  def shutdown(): Unit = {
    clientsLock.writeLock().lock()
    try {
      remoteClients foreach {
        case (_, client) ⇒ try client.shutdown() catch {
          case NonFatal(e) ⇒ log.error(e, "failure while shutting down [{}]", client)
        }
      }
      remoteClients.clear()
    } finally {
      clientsLock.writeLock().unlock()
      try {
        if (server != null) server.shutdown()
      } finally {
        try {
          timer.stop()
        } finally {
          clientChannelFactory.releaseExternalResources()
        }
      }
    }
  }

  def send(
    message: Any,
    senderOption: Option[ActorRef],
    recipient: RemoteActorRef): Unit = {

    val recipientAddress = recipient.path.address

    clientsLock.readLock.lock
    try {
      val client = remoteClients.get(recipientAddress) match {
        case Some(client) ⇒ client
        case None ⇒
          clientsLock.readLock.unlock
          clientsLock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(recipientAddress) match {
                //Recheck for addition, race between upgrades
                case Some(client) ⇒ client //If already populated by other writer
                case None ⇒ //Populate map
                  val client = createClient(recipientAddress)
                  remoteClients += recipientAddress -> client
                  client
              }
            } finally {
              clientsLock.readLock.lock
            } //downgrade
          } finally {
            clientsLock.writeLock.unlock
          }
      }
      client.connect() // this will literally do nothing after the first time
      client.send(message, senderOption, recipient)
    } finally {
      clientsLock.readLock.unlock
    }
  }

  def bindClient(remoteAddress: Address, client: RemoteClient, putIfAbsent: Boolean = false): Boolean = {
    clientsLock.writeLock().lock()
    try {
      if (putIfAbsent && remoteClients.contains(remoteAddress)) false
      else {
        client.connect()
        remoteClients.put(remoteAddress, client).foreach(_.shutdown())
        true
      }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def unbindClient(remoteAddress: Address): Unit = {
    clientsLock.writeLock().lock()
    try {
      remoteClients foreach {
        case (k, v) ⇒
          if (v.isBoundTo(remoteAddress)) { v.shutdown(); remoteClients.remove(k) }
      }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def shutdownClientConnection(remoteAddress: Address): Boolean = {
    clientsLock.writeLock().lock()
    try {
      remoteClients.remove(remoteAddress) match {
        case Some(client) ⇒ client.shutdown()
        case None         ⇒ false
      }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def restartClientConnection(remoteAddress: Address): Boolean = {
    clientsLock.readLock().lock()
    try {
      remoteClients.get(remoteAddress) match {
        case Some(client) ⇒ client.connect(reconnectIfAlreadyConnected = true)
        case None         ⇒ false
      }
    } finally {
      clientsLock.readLock().unlock()
    }
  }

}

private[akka] class RemoteMessageEncoder(remoteSupport: NettyRemoteTransport) extends ProtobufEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef): AnyRef = {
    msg match {
      case (message: Any, sender: Option[_], recipient: ActorRef) ⇒
        super.encode(ctx, channel,
          remoteSupport.createMessageSendEnvelope(
            remoteSupport.createRemoteMessageProtocolBuilder(
              recipient,
              message,
              sender.asInstanceOf[Option[ActorRef]]).build))
      case _ ⇒ super.encode(ctx, channel, msg)
    }
  }
}

private[akka] class RemoteMessageDecoder extends ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)

private[akka] class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
  protected val guard = new ReentrantReadWriteLock
  protected val open = new AtomicBoolean(true)

  override def add(channel: Channel): Boolean = {
    guard.readLock().lock()
    try {
      if (open.get) {
        super.add(channel)
      } else {
        channel.close()
        false
      }
    } finally {
      guard.readLock().unlock()
    }
  }

  override def close(): ChannelGroupFuture = {
    guard.writeLock().lock()
    try {
      if (open.getAndSet(false)) super.close()
      else throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    } finally {
      guard.writeLock().unlock()
    }
  }
}
