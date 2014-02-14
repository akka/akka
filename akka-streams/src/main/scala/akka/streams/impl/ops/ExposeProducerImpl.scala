package akka.streams.impl.ops

import akka.streams.impl._
import rx.async.api.Producer
import akka.streams.Operation.Source
import rx.async.spi.{ Subscription, Subscriber, Publisher }

class ExposeProducerImpl[T](upstream: Upstream, downstream: Downstream[Producer[T]], ctx: ContextEffects) extends SyncOperation[Source[T]] {
  def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
  def handleCancel(): Effect = upstream.cancel

  def handleNext(element: Source[T]): Effect = downstream.next(ctx.expose(element))
  def handleComplete(): Effect = downstream.complete
  def handleError(cause: Throwable): Effect = downstream.error(cause)
}
