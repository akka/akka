package akka.streams.impl.ops

import akka.streams.impl._
import asyncrx.api.Producer
import akka.streams.Operation.Source
import asyncrx.spi.{ Subscription, Subscriber, Publisher }

class ExposeProducerImpl[T](val upstream: Upstream, val downstream: Downstream[Producer[T]], ctx: ContextEffects)
  extends MapLikeImpl[Source[T], Producer[T]] {
  def map(element: Source[T]): Producer[T] = ctx.expose(element)
}
