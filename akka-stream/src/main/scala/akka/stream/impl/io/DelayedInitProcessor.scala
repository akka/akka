/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import scala.concurrent.ExecutionContext
import org.reactivestreams.Subscription
import org.reactivestreams.Processor
import org.reactivestreams.Subscriber
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/**
 * INTERNAL API
 */
private[akka] class DelayedInitProcessor[I, O](val implFuture: Future[Processor[I, O]])(implicit ec: ExecutionContext) extends Processor[I, O] {
  @volatile private var impl: Processor[I, O] = _
  private val setVarFuture = implFuture.andThen { case Success(p) ⇒ impl = p }

  override def onSubscribe(s: Subscription): Unit = setVarFuture.onComplete {
    case Success(x) ⇒ x.onSubscribe(s)
    case Failure(_) ⇒ s.cancel()
  }

  override def onError(t: Throwable): Unit = {
    if (impl eq null) setVarFuture.onSuccess { case p ⇒ p.onError(t) }
    else impl.onError(t)
  }

  override def onComplete(): Unit = {
    if (impl eq null) setVarFuture.onSuccess { case p ⇒ p.onComplete() }
    else impl.onComplete()
  }

  override def onNext(t: I): Unit = impl.onNext(t)

  override def subscribe(s: Subscriber[_ >: O]): Unit = setVarFuture.onComplete {
    case Success(x) ⇒ x.subscribe(s)
    case Failure(e) ⇒ s.onError(e)
  }
}
