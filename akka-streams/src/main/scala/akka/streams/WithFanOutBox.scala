package akka.streams

import rx.async.spi.Subscriber

trait WithFanOutBox {
  def fanOutBox: FanOutBox

  def requestNextBatch(): Unit
  def allSubscriptionsCancelled(): Unit
  def fanOutBoxFinished(): Unit

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
