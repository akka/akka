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
      while (totalDemand > 0L && !buffer.isEmpty)
        onNext(buffer.dequeue())

    case Cancel ⇒
      context.stop(self)

    case _: Status.Success ⇒
      context.stop(self) // will complete the stream successfully

    case Status.Failure(cause) if isActive ⇒
      onError(cause)
      context.stop(self)

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
          onError(new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $bufferSize)!"))
          context.stop(self)
        case Backpressure ⇒
        // there is a precondition check in Source.actorRefSource factory method
      }

  }

}
