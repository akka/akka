/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.duration._
import akka.io.{ Tcp, IO }
import akka.stream.io.StreamTcp
import akka.stream2.Flow
import akka.http.util.Timestamp
import akka.http.{ Http, HttpExt }
import akka.actor._

private[http] class HttpListener(bindCommander: ActorRef,
                                 bind: Http.Bind,
                                 httpSettings: HttpExt#Settings) extends Actor with ActorLogging {
  import HttpListener._
  import bind._

  private val settings = bind.serverSettings getOrElse ServerSettings(context.system)

  log.debug("Binding to {}", endpoint)

  {
    import context.system
    IO(Tcp) ! StreamTcp.Bind(endpoint, backlog, options, materializerSettings)
  }

  context.setReceiveTimeout(settings.bindTimeout)

  val httpServerPipeline = new HttpServerPipeline(settings, log)

  // we cannot sensibly recover from crashes
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = binding

  def binding: Receive = {
    case StreamTcp.TcpServerBinding(localAddress, connectionStream) ⇒
      log.info("Bound to {}", endpoint)
      val httpConnectionStream = Flow(connectionStream)
        .map(httpServerPipeline)
        .onTerminate(_ ⇒ shutdown(gracePeriod = Duration.Zero))
        .toProducer(context)
      bindCommander ! Http.ServerBinding(localAddress, httpConnectionStream)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(sender()))

    case x @ Status.Failure(StreamTcp.BindFailedException) ⇒
      log.warning("Bind to {} failed", endpoint)
      bindCommander ! x
      context.stop(self)

    case ReceiveTimeout ⇒
      log.warning("Bind to {} failed, timeout {} expired", endpoint, settings.bindTimeout)
      bindCommander ! Status.Failure(StreamTcp.BindFailedException)
      context.stop(self)

    case Http.Unbind(_) ⇒ // no children possible, so no reason to note the timeout
      log.info("Bind to {} aborted", endpoint)
      bindCommander ! Status.Failure(StreamTcp.BindFailedException)
      context.become(bindingAborted(Set(sender())))
  }

  /** Waiting for the bind to execute to close it down instantly afterwards */
  def bindingAborted(unbindCommanders: Set[ActorRef]): Receive = {
    case _: StreamTcp.TcpServerBinding ⇒
      unbind(sender(), unbindCommanders, Duration.Zero)

    case Status.Failure(StreamTcp.BindFailedException) ⇒
      unbindCommanders foreach (_ ! Http.Unbound)
      context.stop(self)

    case ReceiveTimeout ⇒
      unbindCommanders foreach (_ ! Http.Unbound)
      context.stop(self)

    case Http.Unbind(_) ⇒ context.become(bindingAborted(unbindCommanders + sender()))
  }

  def connected(tcpListener: ActorRef): Receive = {
    case Http.Unbind(timeout) ⇒ unbind(tcpListener, Set(sender()), timeout)
  }

  def unbind(tcpListener: ActorRef, unbindCommanders: Set[ActorRef], timeout: Duration): Unit = {
    tcpListener ! Tcp.Unbind
    context.setReceiveTimeout(settings.unbindTimeout)
    context.become(unbinding(unbindCommanders, timeout))
  }

  def unbinding(commanders: Set[ActorRef], gracePeriod: Duration): Receive = {
    case Tcp.Unbound ⇒
      log.info("Unbound from {}", endpoint)
      commanders foreach (_ ! Http.Unbound)
      shutdown(gracePeriod)

    case ReceiveTimeout ⇒
      log.warning("Unbinding from {} failed, timeout {} expired, stopping", endpoint, settings.unbindTimeout)
      commanders foreach (_ ! Status.Failure(StreamTcp.UnbindFailedException))
      context.stop(self)

    case Http.Unbind(_) ⇒
      // the first Unbind we received has precedence over ones potentially sent later
      context.become(unbinding(commanders + sender(), gracePeriod))
  }

  def shutdown(gracePeriod: Duration): Unit =
    if (gracePeriod == Duration.Zero || context.children.nonEmpty) {
      context.setReceiveTimeout(Duration.Undefined)
      self ! Tick
      context.become(inShutdownGracePeriod(Timestamp.now + gracePeriod))
    } else context.stop(self)

  /** Wait for a last grace period to expire before shutting us (and our children down) */
  def inShutdownGracePeriod(timeout: Timestamp): Receive = {
    case Tick ⇒
      if (timeout.isPast || context.children.isEmpty) context.stop(self)
      else context.system.scheduler.scheduleOnce(1.second, self, Tick)(context.dispatcher)
  }
}

object HttpListener {
  private case object Tick
}