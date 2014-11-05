/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.io.Inet.SocketOption
import akka.stream.impl.{ ActorBasedFlowMaterializer, Ast }
import akka.stream.io.StreamTcp.{ IncomingTcpConnection, TcpServerBinding, OutgoingTcpConnection }
import akka.stream.scaladsl._
import akka.util.{ Timeout, ByteString }
import org.reactivestreams.{ Publisher, Processor, Subscriber, Subscription }
import akka.pattern.ask
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class DelayedInitProcessor[I, O](implFuture: Future[Processor[I, O]])(implicit ec: ExecutionContext) extends Processor[I, O] {
  @volatile private var impl: Processor[I, O] = _
  implFuture.onSuccess { case p ⇒ impl = p }

  override def onSubscribe(s: Subscription): Unit = implFuture.onComplete {
    case Success(impl) ⇒ impl.onSubscribe(s)
    case Failure(_)    ⇒ s.cancel()
  }

  override def onError(t: Throwable): Unit = {
    if (impl eq null) implFuture.onSuccess { case p ⇒ p.onError(t) }
    else impl.onError(t)
  }

  override def onComplete(): Unit = {
    if (impl eq null) implFuture.onSuccess { case p ⇒ p.onComplete() }
    else impl.onComplete()
  }

  override def onNext(t: I): Unit = impl.onNext(t)

  override def subscribe(s: Subscriber[_ >: O]): Unit = implFuture.onComplete {
    case Success(impl) ⇒ impl.subscribe(s)
    case Failure(e)    ⇒ s.onError(e)
  }
}

class DelayedInitPublisher[O](implFuture: Future[Publisher[O]])(implicit ec: ExecutionContext) extends Publisher[O] {
  override def subscribe(s: Subscriber[_ >: O]): Unit = implFuture.onComplete {
    case Success(impl) ⇒ impl.subscribe(s)
    case Failure(e)    ⇒ s.onError(e)
  }
}

object StreamTcp2 {

  def connect(remoteAddress: InetSocketAddress,
              localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[SocketOption] = Nil,
              connectTimeout: Duration = Duration.Inf,
              idleTimeout: Duration = Duration.Inf)(implicit system: ActorSystem): Flow[ByteString, ByteString] = {
    implicit val t = Timeout(3.seconds)
    import system.dispatcher
    new Pipe[ByteString, ByteString](List(Ast.DirectProcessor { () ⇒
      new DelayedInitProcessor[Any, Any](
        (StreamTcp(system).manager ? StreamTcp.Connect(remoteAddress, localAddress, None, options, connectTimeout, idleTimeout))
          .mapTo[OutgoingTcpConnection]
          .map(_.processor.asInstanceOf[Processor[Any, Any]]))
    }))
  }

  def bind(localAddress: InetSocketAddress,
           backlog: Int = 100,
           options: immutable.Traversable[SocketOption] = Nil,
           idleTimeout: Duration = Duration.Inf)(implicit system: ActorSystem): Source[IncomingTcpConnection] { type MaterializedType = Future[TcpServerBinding] } = {
    new KeyedActorFlowSource[IncomingTcpConnection] {
      implicit val t = Timeout(3.seconds)
      import system.dispatcher

      override def attach(flowSubscriber: Subscriber[IncomingTcpConnection], materializer: ActorBasedFlowMaterializer, flowName: String): MaterializedType = {
        val bindingFuture = (StreamTcp(system).manager ? StreamTcp.Bind(localAddress, None, backlog, options, idleTimeout))
          .mapTo[TcpServerBinding]

        bindingFuture.map(_.connectionStream).onComplete {
          case Success(impl) ⇒ impl.subscribe(flowSubscriber)
          case Failure(e)    ⇒ flowSubscriber.onError(e)
        }

        bindingFuture
      }

      override type MaterializedType = Future[TcpServerBinding]
    }
  }

}
