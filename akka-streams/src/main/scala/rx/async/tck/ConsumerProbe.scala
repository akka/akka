package rx.async
package tck

import spi.Subscription
import api.Consumer
import scala.concurrent.duration.FiniteDuration

sealed trait ConsumerEvent
case class OnSubscribe(subscription: Subscription) extends ConsumerEvent
case class OnNext[I](element: I) extends ConsumerEvent
case object OnComplete extends ConsumerEvent
case class OnError(cause: Throwable) extends ConsumerEvent

trait ConsumerProbe[I] extends Consumer[I] {
  def expectSubscription(): Subscription
  def expectEvent(event: ConsumerEvent): Unit
  def expectNext(element: I): Unit
  def expectNext(): I
  def expectComplete(): Unit

  def expectNoMsg(): Unit
  def expectNoMsg(max: FiniteDuration): Unit
}
