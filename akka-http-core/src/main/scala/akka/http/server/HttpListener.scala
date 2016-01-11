/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.duration._
import akka.io.{ Tcp, IO }
import akka.stream.io.{ TcpListenStreamActor, StreamTcp }
import akka.stream.{ Transformer, FlowMaterializer }
import akka.stream.scaladsl.Flow
import akka.http.util.Timestamp
import akka.http.{ Http, HttpExt }
import akka.actor._

/**
 * INTERNAL API
 */
private[http] class HttpListener(bindCommander: ActorRef,
                                 bind: Http.Bind,
                                 httpSettings: HttpExt#Settings) extends Actor with ActorLogging {
  import HttpListener._
  import bind._

  private val settings = bind.serverSettings getOrElse ServerSettings(context.system)

  log.debug("Binding to {}", endpoint)

  IO(StreamTcp)(context.system) ! StreamTcp.Bind(materializerSettings, endpoint, backlog, options)

  context.setReceiveTimeout(settings.bindTimeout)

  val httpServerPipeline = new HttpServerPipeline(settings, FlowMaterializer(materializerSettings), log)

  // we cannot sensibly recover from crashes
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = binding

  def binding: Receive = {
    case StreamTcp.TcpServerBinding(localAddress, connectionStream) ⇒
      log.info("Bound to {}", endpoint)
      val materializer = FlowMaterializer(materializerSettings)
      val httpConnectionStream = Flow(connectionStream)
        .map(httpServerPipeline)
        .transform {
          new Transformer[Http.IncomingConnection, Http.IncomingConnection] {
            def onNext(element: Http.IncomingConnection) = element :: Nil
            override def cleanup() = shutdown(gracePeriod = Duration.Zero)
          }
        }.toProducer(materializer)
      bindCommander ! Http.ServerBinding(localAddress, httpConnectionStream)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(sender()))

    case Status.Failure(_: TcpListenStreamActor.TcpListenStreamException) ⇒
      log.warning("Bind to {} failed", endpoint)
      bindCommander ! Status.Failure(Http.BindFailedException)
      context.stop(self)

    case ReceiveTimeout ⇒
      log.warning("Bind to {} failed, timeout {} expired", endpoint, settings.bindTimeout)
      bindCommander ! Status.Failure(Http.BindFailedException)
      context.stop(self)

    case Http.Unbind(_) ⇒ // no children possible, so no reason to note the timeout
      log.info("Bind to {} aborted", endpoint)
      bindCommander ! Status.Failure(Http.BindFailedException)
      context.become(bindingAborted(Set(sender())))
  }

  /** Waiting for the bind to execute to close it down instantly afterwards */
  def bindingAborted(unbindCommanders: Set[ActorRef]): Receive = {
    case _: StreamTcp.TcpServerBinding ⇒
      unbind(sender(), unbindCommanders, Duration.Zero)

    case Status.Failure(_: TcpListenStreamActor.TcpListenStreamException) ⇒
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
      commanders foreach (_ ! Status.Failure(Http.UnbindFailedException))
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

private[http] object HttpListener {
  def props(bindCommander: ActorRef, bind: Http.Bind, httpSettings: HttpExt#Settings) =
    Props(new HttpListener(bindCommander, bind, httpSettings)) withDispatcher httpSettings.ListenerDispatcher

  private case object Tick
}