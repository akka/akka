/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.Executors
import scala.collection.mutable.HashMap
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroupFuture }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.{ ChannelHandlerContext, Channel }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufEncoder, ProtobufDecoder }
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor
import org.jboss.netty.util.HashedWheelTimer
import akka.actor.{ Address, ActorSystemImpl, ActorRef }
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.remote.RemoteProtocol.AkkaRemoteProtocol
import akka.remote.{ RemoteTransportException, RemoteTransport, RemoteSettings, RemoteMarshallingOps, RemoteActorRefProvider, RemoteActorRef, RemoteServerStarted }
import akka.util.NonFatal

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteTransport(val remoteSettings: RemoteSettings, val system: ActorSystemImpl, val provider: RemoteActorRefProvider)
  extends RemoteTransport with RemoteMarshallingOps {

  val settings = new NettySettings(remoteSettings.config.getConfig("akka.remote.netty"), remoteSettings.systemName)

  val timer: HashedWheelTimer = new HashedWheelTimer(system.threadFactory)

  val executor = new OrderedMemoryAwareThreadPoolExecutor(
    settings.ExecutionPoolSize,
    settings.MaxChannelMemorySize,
    settings.MaxTotalMemorySize,
    settings.ExecutionPoolKeepalive.length,
    settings.ExecutionPoolKeepalive.unit,
    system.threadFactory)

  val clientChannelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool(system.threadFactory),
    Executors.newCachedThreadPool(system.threadFactory))

  private val remoteClients = new HashMap[Address, RemoteClient]
  private val clientsLock = new ReentrantReadWriteLock

  override protected def useUntrustedMode = remoteSettings.UntrustedMode

  val server = try new NettyRemoteServer(this) catch {
    case ex ⇒ shutdown(); throw ex
  }

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

  val log = Logging(system.eventStream, "NettyRemoteTransport(" + address + ")")

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
          try {
            clientChannelFactory.releaseExternalResources()
          } finally {
            executor.shutdown()
          }
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
                  val client = new ActiveRemoteClient(this, recipientAddress, address)
                  client.connect()
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

class RemoteMessageEncoder(remoteSupport: NettyRemoteTransport) extends ProtobufEncoder {
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

class RemoteMessageDecoder extends ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)

class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
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
