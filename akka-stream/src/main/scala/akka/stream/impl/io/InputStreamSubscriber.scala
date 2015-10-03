/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.util.concurrent.BlockingQueue

import akka.actor._
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.stream.impl.io.InputStreamSubscriber.Failed
import akka.util.ByteString

/** INTERNAL API */
object InputStreamSubscriber {
  def props(sharedBuffer: BlockingQueue[ByteString]): Props = {
    Props(new InputStreamSubscriber(sharedBuffer)).withDeploy(Deploy.local)
  }

  trait InputStreamSubscriberInMessage

  case object Request extends InputStreamSubscriberInMessage
  case object ReadNotification extends InputStreamSubscriberInMessage
  case object Close extends InputStreamSubscriberInMessage

  trait InputStreamSubscriberOutMessage

  case class AcknowledgeMessage(terminated: Boolean) extends InputStreamSubscriberOutMessage
  case object Finished extends InputStreamSubscriberOutMessage
  case class Failed(cause: Throwable) extends InputStreamSubscriberOutMessage

}

/** INTERNAL API */
private[akka] class InputStreamSubscriber(sharedBuffer: BlockingQueue[ByteString])
  extends akka.stream.actor.ActorSubscriber with ActorLogging {

  val bufSize = sharedBuffer.remainingCapacity()

  var bytesRequestedDownstream: Option[ActorRef] = None
  var bytesSentDownstream: Long = 0
  var receivedError: Option[Throwable] = None

  var onCompleteReceived = false
  var sentCompleteNotification = false

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      if (bytes.nonEmpty) {
        // ignore empty ByteStrings
        val added = sharedBuffer.offer(bytes)
        assert(added, "Bytes must always be added to buffer as adapter consuming them")
        tryToReply()
      }

    case ActorSubscriberMessage.OnError(err) ⇒
      receivedError = Some(err)
      tryToReply()
      //block context.stop until InputStreamAdapter received failed notification
      //will not drain buffer in this case
      if (sentCompleteNotification) context.stop(self)
      else context.become(notifyThenStop)

    case ActorSubscriberMessage.OnComplete ⇒
      onCompleteReceived = true
      tryToReply()
      //block context.stop until all available data will be read or stream will be closed
      if (sentCompleteNotification) context.stop(self)
      else context.become(notifyThenStop)

    //Downstream messages from InputStreamAdapter
    case InputStreamSubscriber.Request ⇒ onRequestData()
    case InputStreamSubscriber.ReadNotification ⇒
      sendReadinessNotification(sender)
    case InputStreamSubscriber.Close ⇒ cancel()
  }

  def notifyThenStop: Receive = {
    case InputStreamSubscriber.Close ⇒ context.stop(self)
    case InputStreamSubscriber.Request | InputStreamSubscriber.ReadNotification ⇒
      receivedError match {
        case Some(err) ⇒
          sender ! Failed(err)
          context.stop(self)
        case None ⇒
          sendReadinessNotification(sender)
          if (sentCompleteNotification) context.stop(self)
      }
  }

  def sendReadinessNotification(actor: ActorRef) = {
    bytesRequestedDownstream = None
    //send "finished" flag when drained buffer
    if (onCompleteReceived && sharedBuffer.isEmpty)
      sentCompleteNotification = true

    actor ! InputStreamSubscriber.AcknowledgeMessage(sentCompleteNotification)
  }

  def onRequestData() = {
    if (!sharedBuffer.isEmpty)
      sendReadinessNotification(sender)
    else
      bytesRequestedDownstream = Some(sender)
  }

  def tryToReply() =
    bytesRequestedDownstream match {
      case Some(actor) ⇒
        receivedError match {
          case Some(err) ⇒
            actor ! Failed(err)
            sentCompleteNotification = true
          case None ⇒ sendReadinessNotification(actor)
        }
      case None ⇒ //do nothing
    }
}

