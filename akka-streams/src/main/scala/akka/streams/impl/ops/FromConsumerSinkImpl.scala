package akka.streams.impl.ops

import asyncrx.api.Consumer
import asyncrx.spi.Subscription

import akka.streams.impl._

class FromConsumerSinkImpl[I](upstream: Upstream, ctx: ContextEffects, consumer: Consumer[I]) extends SyncSink[I] {
  var downstream: Downstream[I] = _
  override def start(): Effect = {
    consumer.getSubscriber.onSubscribe(internalSubscription)
    Continue
  }

  val internalSubscription = new Subscription {
    def requestMore(elements: Int): Unit = ctx.runStrictInContext(upstream.requestMore(elements))
    def cancel(): Unit = ctx.runStrictInContext(upstream.cancel)
  }
  val subscriberEffects = BasicEffects.forSubscriber(consumer.getSubscriber)

  def handleNext(element: I): Effect = subscriberEffects.next(element)
  def handleComplete(): Effect = subscriberEffects.complete
  def handleError(cause: Throwable): Effect = subscriberEffects.error(cause)
}
