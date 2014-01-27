package akka.streams

import scala.collection.mutable
import scala.annotation.tailrec
import rx.async.spi.Subscriber

/**
 * This is the implementation of the race track multiple-consumer back-pressure
 * model for the case of matching the pace to the slowest consumer.
 */
class RaceTrack(bufferSize: Int) extends FanOutBox {
  import FanOutBox._

  private trait Final
  private case class Error(cause: Throwable) extends Final
  private case object Complete extends Final

  private var _state: State = Empty

  // consumer -> elements asked for (i.e. “last sent” for slow consumers)
  private val outputs = mutable.Map[Subscriber[_], Int]()

  private var buffer = Vector.empty[Any]
  // when in state Finishing this holds the final element
  private var theFinal: Final = _

  /*
   * The semantics are:
   * 
   * - baseline is the last element which has been completely disseminated,
   *   i.e. one before where the buffer starts
   * - the entries of the `outputs` map refer to where a consumer wants to be
   */
  private var baseline = 0

  override def toString = s"RaceTrack(state=$state, buffered=${buffer.size}, baseline=$baseline, outputs=$outputs)"

  private def checkState(): Unit = _state match {
    case Finished  ⇒
    case Finishing ⇒ if (buffer.size == 0) { _state = Finished; theFinal = null }
    case _ ⇒
      _state =
        if (outputs.isEmpty) Empty
        else if (buffer.size == bufferSize) {
          if (bufferSize > 0) Blocked
          else {
            val min = outputs.valuesIterator.map(_ - baseline).reduce(Math.min)
            if (min > 0) Ready else Blocked
          }
        } else Ready
  }

  private def enqueue(elem: Any): Unit = {
    buffer :+= elem
    checkState()
  }

  @tailrec private def send(pos: Int, count: Int, obs: Subscriber[_], sent: Int = 0): Int = {
    if (pos < buffer.size && count > 0) {
      obs.asInstanceOf[Subscriber[Any]].onNext(buffer(pos))
      send(pos + 1, count - 1, obs, sent + 1)
    } else if (_state == Finishing && pos == buffer.size) {
      theFinal match {
        case Complete ⇒
          obs.onComplete()
          removeReceiver(obs)
          sent + 1
        case Error(thr) ⇒
          obs.onError(thr)
          removeReceiver(obs)
          sent + 1
      }
    } else sent
  }

  private def prune(max: Int): Unit =
    if (outputs.nonEmpty) {
      if (max > 0) {
        val min = outputs.valuesIterator.map(_ - baseline).reduce(Math.min)
        if (min > 0) {
          val toDrop = Math.min(min, max)
          baseline += toDrop
          buffer = buffer.drop(toDrop)
          checkState()
        }
      }
    } else {
      buffer = Vector.empty
      baseline = 0
      checkState()
    }

  private def finishing(): Unit = {
    _state = Finishing
    outputs foreach {
      case (obs, req) ⇒ if (req - baseline >= buffer.size) send(buffer.size, 0, obs)
    }
    prune(0)
  }

  override def addReceiver(consumer: Subscriber[_]): Unit = state match {
    case s @ Finished ⇒
      throw new IllegalStateException("cannot subscribe while " + s)
    case Finishing ⇒
      outputs(consumer) = baseline
    case s @ (Empty | Ready | Blocked | TimeBlocked(_)) ⇒
      outputs(consumer) = baseline
      if (bufferSize == 0) _state = Blocked
      else if (s == Empty) _state = Ready
  }

  override def removeReceiver(consumer: Subscriber[_]): Unit = {
    outputs -= consumer
    if (state != Finishing) checkState()
  }

  override def state = _state

  override def onNext(elem: Any): Unit = state match {
    case s @ (Blocked | TimeBlocked(_) | Finishing | Finished) ⇒
      throw new IllegalStateException("cannot push element while " + s)
    case Empty ⇒ // drop
    case Ready ⇒
      enqueue(elem)
      outputs foreach {
        case (obs, req) ⇒ if (req - baseline >= buffer.size) send(buffer.size - 1, 1, obs)
      }
      prune(1)
  }

  override def onError(cause: Throwable): Unit = state match {
    case s @ (Finishing | Finished) ⇒
      throw new IllegalStateException("cannot signal failure while " + s)
    case Empty | Blocked | TimeBlocked(_) | Ready ⇒
      theFinal = Error(cause)
      finishing()
  }

  override def onComplete(): Unit = state match {
    case s @ (Finishing | Finished) ⇒
      throw new IllegalStateException("cannot signal completion while " + s)
    case Empty | Blocked | TimeBlocked(_) | Ready ⇒
      theFinal = Complete
      finishing()
  }

  override def requestMore(consumer: Subscriber[_], elems: Int): Unit = {
    val lastSent = outputs(consumer)
    outputs(consumer) = lastSent + elems
    send(lastSent - baseline, elems, consumer) match {
      case 0    ⇒ checkState()
      case sent ⇒ prune(sent)
    }
  }

}
