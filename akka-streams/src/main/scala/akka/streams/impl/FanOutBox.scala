package akka.streams.impl

import scala.concurrent.duration.Deadline
import rx.async.spi.Subscriber

object FanOutBox {
  sealed trait State
  case object Empty extends State
  case object Ready extends State
  case object Blocked extends State
  case class TimeBlocked(until: Deadline) extends State
  case object Finishing extends State
  case object Finished extends State
}

trait FanOutBox {
  import FanOutBox._

  def state: State
  def onNext(element: Any): Unit
  def onError(cause: Throwable): Unit
  def onComplete(): Unit
  def addReceiver(sub: Subscriber[_]): Unit
  def removeReceiver(sub: Subscriber[_]): Unit
  def requestMore(sub: Subscriber[_], elements: Int): Unit
}
