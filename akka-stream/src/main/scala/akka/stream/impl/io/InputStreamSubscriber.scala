/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import akka.actor._
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.stream.impl.io.InputStreamSubscriber.Failed
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
object InputStreamSubscriber {
  def props(completionPromise: Promise[Long], bufSize: Int): Props = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(new InputStreamSubscriber(completionPromise, bufSize)).withDeploy(Deploy.local)
  }

  trait InputStreamSubscriberInMessage
  case class Read(length: Int) extends InputStreamSubscriberInMessage
  case object Close extends InputStreamSubscriberInMessage

  trait InputStreamSubscriberOutMessage
  case class Bytes(bytes: ByteString, terminated: Boolean) extends InputStreamSubscriberOutMessage
  case object Finished extends InputStreamSubscriberOutMessage
  case class Failed(cause: Throwable) extends InputStreamSubscriberOutMessage
}

/** INTERNAL API */
private[akka] class InputStreamSubscriber(bytesWrittenPromise: Promise[Long], bufSize: Int)
  extends akka.stream.actor.ActorSubscriber with ActorLogging {

  var bytesRequestedDownstream: Option[(ActorRef, Int)] = None
  var bytesSentDownstream: Long = 0
  var availableChunks: Vector[ByteString] = Vector.empty[ByteString]
  var receivedError: Option[Throwable] = None

  var onCompleteReceived = false
  var sentCompleteNotification = false

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      availableChunks :+= bytes
      tryToReply()

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
      if (availableChunks.isEmpty && sentCompleteNotification) context.stop(self)
      else context.become(drainBufferThenStop)

    //Downstream messages from InputStreamAdapter
    case InputStreamSubscriber.Read(length) ⇒ onRead(length)
    case InputStreamSubscriber.Close        ⇒ cancel()
  }

  def notifyThenStop: Receive = {
    case InputStreamSubscriber.Close ⇒ context.stop(self)
    case InputStreamSubscriber.Read(length) ⇒
      receivedError match {
        case Some(err) ⇒
          sender ! Failed(err)
          context.stop(self)
        case None ⇒ //do nothing here
      }
  }

  def drainBufferThenStop: Receive = {
    case InputStreamSubscriber.Close ⇒ context.stop(self)
    case InputStreamSubscriber.Read(length) ⇒
      onRead(length)
      if (availableChunks.isEmpty) context.stop(self)
  }

  def availableBytes(): Int =
    availableChunks.foldLeft(0)((acc, byteString) ⇒ acc + byteString.size)

  def sendBytes(actor: ActorRef, length: Int) = {
    bytesSentDownstream += length
    bytesRequestedDownstream = None

    //send "finished" flag when drained buffer
    if (onCompleteReceived && length >= availableBytes())
      sentCompleteNotification = true

    actor ! InputStreamSubscriber.Bytes(getBytes(length), sentCompleteNotification)
  }

  def onRead(requestedBytes: Int) = {
    val availableData = availableBytes()
    if (onCompleteReceived)
      //send even empty result if Complete received
      sendBytes(sender, requestedBytes)
    else {
      if (availableData > 0)
        sendBytes(sender, Math.min(availableData, requestedBytes))
      else
        bytesRequestedDownstream = Some((sender, requestedBytes))
    }
  }

  override def postStop(): Unit = {
    bytesWrittenPromise.success(bytesSentDownstream)
    super.postStop()
  }

  def tryToReply() =
    bytesRequestedDownstream match {
      case Some((actor, length)) ⇒
        receivedError match {
          case Some(err) ⇒
            actor ! Failed(err)
            sentCompleteNotification = true
          case None ⇒ sendBytes(actor, Math.min(availableBytes, length))
        }
      case None ⇒ //do nothing
    }

  def getBytes(length: Int) = {
    val emptyVector = Vector.empty[ByteString]

    val (bytes, newBuff) = availableChunks.foldLeft((ByteString.empty, emptyVector))((acc, byteString) ⇒ {
      val bytesSize = acc._1.size
      if (bytesSize < length) {
        if (bytesSize + byteString.size <= length)
          (acc._1 ++ byteString, emptyVector)
        else
          (acc._1 ++ byteString.take(length - bytesSize), acc._2 :+ byteString.drop(length - bytesSize))
      } else (acc._1, acc._2 :+ byteString)
    })

    availableChunks = newBuff
    bytes
  }
}

