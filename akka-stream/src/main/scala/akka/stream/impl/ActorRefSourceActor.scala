/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Status
import akka.stream.OverflowStrategy

/**
 * INTERNAL API
 */
private[akka] object ActorRefSourceActor {
  def props(bufferSize: Int, overflowStrategy: OverflowStrategy) = {
    require(overflowStrategy != OverflowStrategy.Backpressure, "Backpressure overflowStrategy not supported")
    Props(new ActorRefSourceActor(bufferSize, overflowStrategy))
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorRefSourceActor(bufferSize: Int, overflowStrategy: OverflowStrategy)
  extends akka.stream.actor.ActorPublisher[Any] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  import akka.stream.OverflowStrategy._

  // when bufferSize is 0 there the buffer is not used
  private val buffer = if (bufferSize == 0) null else FixedSizeBuffer[Any](bufferSize)

  def receive = {
    case _: Request ⇒
      // totalDemand is tracked by super
      if (bufferSize != 0)
        while (totalDemand > 0L && !buffer.isEmpty)
          onNext(buffer.dequeue())

    case Cancel ⇒
      context.stop(self)

    case _: Status.Success ⇒
      if (bufferSize == 0 || buffer.isEmpty) context.stop(self) // will complete the stream successfully
      else context.become(drainBufferThenComplete)

    case Status.Failure(cause) if isActive ⇒
      onErrorThenStop(cause)

    case elem if isActive ⇒
      if (totalDemand > 0L)
        onNext(elem)
      else if (bufferSize == 0)
        log.debug("Dropping element because there is no downstream demand: [{}]", elem)
      else if (!buffer.isFull)
        buffer.enqueue(elem)
      else overflowStrategy match {
        case DropHead ⇒
          buffer.dropHead()
          buffer.enqueue(elem)
        case DropTail ⇒
          buffer.dropTail()
          buffer.enqueue(elem)
        case DropBuffer ⇒
          buffer.clear()
          buffer.enqueue(elem)
        case Fail ⇒
          onErrorThenStop(new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $bufferSize)!"))
        case Backpressure ⇒
        // there is a precondition check in Source.actorRefSource factory method
      }
  }

  def drainBufferThenComplete: Receive = {
    case Cancel ⇒
      context.stop(self)

    case Status.Failure(cause) if isActive ⇒
      // errors must be signalled as soon as possible,
      // even if previously valid completion was requested via Status.Success
      onErrorThenStop(cause)

    case _: Request ⇒
      // totalDemand is tracked by super
      while (totalDemand > 0L && !buffer.isEmpty)
        onNext(buffer.dequeue())

      if (buffer.isEmpty) context.stop(self) // will complete the stream successfully

    case elem if isActive ⇒
      log.debug("Dropping element because Status.Success received already, " +
        "only draining already buffered elements: [{}] (pending: [{}])", elem, buffer.used)
  }

}
