/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Status
import akka.stream.OverflowStrategies._
import akka.stream.{ BufferOverflowException, OverflowStrategy, OverflowStrategies }
import akka.stream.ActorMaterializerSettings

/**
 * INTERNAL API
 */
private[akka] object ActorRefSourceActor {
  def props(bufferSize: Int, overflowStrategy: OverflowStrategy, settings: ActorMaterializerSettings) = {
    require(overflowStrategy != OverflowStrategies.Backpressure, "Backpressure overflowStrategy not supported")
    val maxFixedBufferSize = settings.maxFixedBufferSize
    Props(new ActorRefSourceActor(bufferSize, overflowStrategy, maxFixedBufferSize))
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorRefSourceActor(bufferSize: Int, overflowStrategy: OverflowStrategy, maxFixedBufferSize: Int)
  extends akka.stream.actor.ActorPublisher[Any] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._

  // when bufferSize is 0 there the buffer is not used
  protected val buffer = if (bufferSize == 0) null else Buffer[Any](bufferSize, maxFixedBufferSize)

  def receive = ({
    case Cancel ⇒
      context.stop(self)

    case _: Status.Success ⇒
      if (bufferSize == 0 || buffer.isEmpty) context.stop(self) // will complete the stream successfully
      else context.become(drainBufferThenComplete)

    case Status.Failure(cause) if isActive ⇒
      onErrorThenStop(cause)

  }: Receive).orElse(requestElem).orElse(receiveElem)

  def requestElem: Receive = {
    case _: Request ⇒
      // totalDemand is tracked by super
      if (bufferSize != 0)
        while (totalDemand > 0L && !buffer.isEmpty)
          onNext(buffer.dequeue())
  }

  def receiveElem: Receive = {
    case elem if isActive ⇒
      if (totalDemand > 0L)
        onNext(elem)
      else if (bufferSize == 0)
        log.debug("Dropping element because there is no downstream demand: [{}]", elem)
      else if (!buffer.isFull)
        buffer.enqueue(elem)
      else overflowStrategy match {
        case DropHead ⇒
          log.debug("Dropping the head element because buffer is full and overflowStrategy is: [DropHead]")
          buffer.dropHead()
          buffer.enqueue(elem)
        case DropTail ⇒
          log.debug("Dropping the tail element because buffer is full and overflowStrategy is: [DropTail]")
          buffer.dropTail()
          buffer.enqueue(elem)
        case DropBuffer ⇒
          log.debug("Dropping all the buffered elements because buffer is full and overflowStrategy is: [DropBuffer]")
          buffer.clear()
          buffer.enqueue(elem)
        case DropNew ⇒
          // do not enqueue new element if the buffer is full
          log.debug("Dropping the new element because buffer is full and overflowStrategy is: [DropNew]")
        case Fail ⇒
          log.error("Failing because buffer is full and overflowStrategy is: [Fail]")
          onErrorThenStop(new BufferOverflowException(s"Buffer overflow (max capacity was: $bufferSize)!"))
        case Backpressure ⇒
          // there is a precondition check in Source.actorRefSource factory method
          log.debug("Backpressuring because buffer is full and overflowStrategy is: [Backpressure]")
      }
  }

  def drainBufferThenComplete: Receive = {
    case Cancel ⇒
      context.stop(self)

    case Status.Failure(cause) if isActive ⇒
      // errors must be signaled as soon as possible,
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
