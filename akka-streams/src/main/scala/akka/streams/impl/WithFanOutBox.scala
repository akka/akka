package akka.streams.impl

import rx.async.spi.Subscriber

/**
 * A more natural interface for FanOutBox that doesn't rely on users of FanOutBox to
 * know anything about its internal state.
 *
 * TODO: 1.) provide the final interface here with glue layer for previous one
 *       2.) rewrite FanOutBox with the better interface
 */
trait WithFanOutBox {
  def fanOutBox: FanOutBox

  def requestNextBatch(): Unit
  def allSubscriptionsCancelled(): Unit
  def fanOutBoxFinished(): Unit

  def hasSubscribers: Boolean = fanOutBox.state != FanOutBox.Empty
  def handleOnNext(next: Any): Unit = {
    fanOutBox.onNext(next)
    if (fanOutBox.state == FanOutBox.Ready) requestNextBatch()
  }
  def handleOnError(cause: Throwable): Unit = fanOutBox.onError(cause)
  def handleOnComplete(): Unit = fanOutBox.onComplete()

  def handleRequestMore(sub: Subscriber[_], elements: Int): Unit = {
    fanOutBox.requestMore(sub, elements)
    fanOutBox.state match {
      case FanOutBox.Ready    ⇒ requestNextBatch()
      case FanOutBox.Finished ⇒ fanOutBoxFinished()
      case _                  ⇒
    }
  }
  def handleNewSubscription(sub: Subscriber[_]): Unit = fanOutBox.addReceiver(sub)
  def handleSubscriptionCancelled(sub: Subscriber[_]): Unit = {
    val previousState = fanOutBox.state
    fanOutBox.removeReceiver(sub)
    fanOutBox.state match {
      case FanOutBox.Empty ⇒ allSubscriptionsCancelled()
      case FanOutBox.Finished ⇒ fanOutBoxFinished()
      case FanOutBox.Ready if previousState == FanOutBox.Blocked ⇒ requestNextBatch()
      case _ ⇒
    }
  }
}
