package akka.streams

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
  def onNext(elem: Any): Unit
  def onError(cause: Throwable): Unit
  def onComplete(): Unit
  def addReceiver(obs: Subscriber[_]): Unit
  def removeReceiver(obs: Subscriber[_]): Unit
  def requestMore(obs: Subscriber[_], elems: Int): Unit
}
