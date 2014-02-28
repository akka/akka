package akka.streams.impl.ops

import akka.streams.impl._
import rx.async.api.Producer
import akka.streams.Operation.Source
import rx.async.spi.{ Subscription, Subscriber, Publisher }

class ExposeProducerImpl[T](val upstream: Upstream, val downstream: Downstream[Producer[T]], ctx: ContextEffects)
  extends MapLikeImpl[Source[T], Producer[T]] {
  def map(element: Source[T]): Producer[T] = ctx.expose(element)
}
