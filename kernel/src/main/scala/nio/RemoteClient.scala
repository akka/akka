/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ConcurrentMap, ConcurrentHashMap}

import kernel.actor.{Exit, Actor}
import kernel.reactor.{DefaultCompletableFutureResult, CompletableFutureResult}
import kernel.util.Logging

import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.serialization.{ObjectEncoder, ObjectDecoder}
import org.jboss.netty.bootstrap.ClientBootstrap

import scala.collection.mutable.HashMap

object RemoteClient extends Logging {
  private val clients = new HashMap[String, RemoteClient]
  def clientFor(address: InetSocketAddress): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ":" + port
    if (clients.contains(hash)) clients(hash)
    else {
      val client = new RemoteClient(hostname, port)
      client.connect
      clients += hash -> client
      client
    }
  }
}

class RemoteClient(hostname: String, port: Int) extends Logging {
  @volatile private var isRunning = false 
  private val futures = new ConcurrentHashMap[Long, CompletableFutureResult]
  private val supervisors = new ConcurrentHashMap[String, Actor]

  // TODO is this Netty channelFactory and other options always the best or should it be configurable?
  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)
  private val handler = new ObjectClientHandler(futures, supervisors)

  bootstrap.getPipeline.addLast("handler", handler)
  bootstrap.setOption("tcpNoDelay", true)
  bootstrap.setOption("keepAlive", true)

  private var connection: ChannelFuture = _

  def connect = synchronized {
    if (!isRunning) {
      connection = bootstrap.connect(new InetSocketAddress(hostname, port))
      log.info("Starting remote client connection to [%s:%s]", hostname, port)

      // Wait until the connection attempt succeeds or fails.
      connection.awaitUninterruptibly
      if (!connection.isSuccess) {
        log.error("Remote connection to [%s:%s] has failed due to [%s]", hostname, port, connection.getCause)
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

  def send(request: RemoteRequest): Option[CompletableFutureResult] = if (isRunning) {
    val escapedRequest = escapeRequest(request)
    if (escapedRequest.isOneWay) {
      connection.getChannel.write(escapedRequest)
      None
    } else {
      futures.synchronized {
        val futureResult = new DefaultCompletableFutureResult(request.timeout)
        futures.put(request.id, futureResult)
        connection.getChannel.write(escapedRequest)
        Some(futureResult)
      }      
    }
  } else throw new IllegalStateException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.")

  def registerSupervisorForActor(actor: Actor) =
    if (!actor.supervisor.isDefined) throw new IllegalStateException("Can't register supervisor for " + actor + " since it is not under supervision")
    else supervisors.putIfAbsent(actor.supervisor.get.uuid, actor)

  def deregisterSupervisorForActor(actor: Actor) =
    if (!actor.supervisor.isDefined) throw new IllegalStateException("Can't unregister supervisor for " + actor + " since it is not under supervision")
    else supervisors.remove(actor.supervisor.get.uuid)
  
  def deregisterSupervisorWithUuid(uuid: String) = supervisors.remove(uuid)

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
class ObjectClientHandler(val futures: ConcurrentMap[Long, CompletableFutureResult],
                          val supervisors: ConcurrentMap[String, Actor])
 extends SimpleChannelUpstreamHandler with Logging {

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
        //val tx = reply.tx
        //if (reply.successful) future.completeWithResult((reply.message, tx))
        if (reply.successful) future.completeWithResult(reply.message)
        else {
          if (reply.supervisorUuid.isDefined) {
            val supervisorUuid = reply.supervisorUuid.get
            if (!supervisors.containsKey(supervisorUuid)) throw new IllegalStateException("Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor.supervisor.isDefined) throw new IllegalStateException("Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor.supervisor.get ! Exit(supervisedActor, reply.exception)
          }
          future.completeWithException(null, reply.exception)
        }
	      futures.remove(reply.id)
      } else throw new IllegalArgumentException("Unknown message received in remote client handler: " + result)
    } catch {
      case e: Exception =>
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }                 

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) {
    log.error("Unexpected exception from downstream in remote client: %s", event.getCause)
    event.getChannel.close
  }
}
