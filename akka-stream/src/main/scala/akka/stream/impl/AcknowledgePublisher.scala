/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ ActorRef, Props }
import akka.stream.OverflowStrategy
import akka.stream.OverflowStrategy._
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.impl.AcknowledgePublisher.{ Rejected, Ok }

/**
 * INTERNAL API
 */
private[akka] object AcknowledgePublisher {
  def props(bufferSize: Int, overflowStrategy: OverflowStrategy) =
    Props(new AcknowledgePublisher(bufferSize, overflowStrategy))

  case class Ok()
  case class Rejected()
}

/**
 * INTERNAL API
 */
private[akka] class AcknowledgePublisher(bufferSize: Int, overflowStrategy: OverflowStrategy)
  extends ActorRefSourceActor(bufferSize, overflowStrategy) {

  var backpressedElem: Option[ActorRef] = None

  override def requestElem: Receive = {
    case _: Request ⇒
      // totalDemand is tracked by super
      if (bufferSize != 0)
        while (totalDemand > 0L && !buffer.isEmpty) {
          //if buffer is full - sent ack message to sender in case of Backpressure mode
          if (buffer.isFull) backpressedElem match {
            case Some(ref) ⇒
              ref ! Ok(); backpressedElem = None
            case None ⇒ //do nothing
          }
          onNext(buffer.dequeue())
        }
  }

  override def receiveElem: Receive = {
    case elem if isActive ⇒
      if (totalDemand > 0L) {
        onNext(elem)
        sendAck(true)
      } else if (bufferSize == 0) {
        log.debug("Dropping element because there is no downstream demand: [{}]", elem)
        sendAck(false)
      } else if (!buffer.isFull)
        enqueueAndSendAck(elem)
      else (overflowStrategy: @unchecked) match {
        case DropHead ⇒
          log.debug("Dropping the head element because buffer is full and overflowStrategy is: [DropHead]")
          buffer.dropHead()
          enqueueAndSendAck(elem)
        case DropTail ⇒
          log.debug("Dropping the tail element because buffer is full and overflowStrategy is: [DropTail]")
          buffer.dropTail()
          enqueueAndSendAck(elem)
        case DropBuffer ⇒
          log.debug("Dropping all the buffered elements because buffer is full and overflowStrategy is: [DropBuffer]")
          buffer.clear()
          enqueueAndSendAck(elem)
        case DropNew ⇒
          log.debug("Dropping the new element because buffer is full and overflowStrategy is: [DropNew]")
          sendAck(false)
        case Fail ⇒
          log.error("Failing because buffer is full and overflowStrategy is: [Fail]")
          onErrorThenStop(new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $bufferSize)!"))
        case Backpressure ⇒
          log.debug("Backpressuring because buffer is full and overflowStrategy is: [Backpressure]")
          sendAck(false) //does not allow to send more than buffer size
      }
  }

  def enqueueAndSendAck(elem: Any): Unit = {
    buffer.enqueue(elem)
    if (buffer.isFull && overflowStrategy == Backpressure) backpressedElem = Some(sender)
    else sendAck(true)
  }

  def sendAck(isOk: Boolean): Unit = {
    val msg = if (isOk) Ok() else Rejected()
    context.sender() ! msg
  }

}
