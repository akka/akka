/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.InputStream

import akka.Done
import akka.actor.{ Deploy, ActorLogging, DeadLetterSuppression, Props }
import akka.stream.actor.ActorPublisherMessage
import akka.stream.IOResult
import akka.util.ByteString

import scala.concurrent.Promise
import scala.util.{ Failure, Success }

/** INTERNAL API */
private[akka] object InputStreamPublisher {

  def props(is: InputStream, completionPromise: Promise[IOResult], chunkSize: Int): Props = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")

    Props(classOf[InputStreamPublisher], is, completionPromise, chunkSize).withDeploy(Deploy.local)
  }

  private final case object Continue extends DeadLetterSuppression
}

/** INTERNAL API */
private[akka] class InputStreamPublisher(is: InputStream, completionPromise: Promise[IOResult], chunkSize: Int)
  extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  // TODO possibly de-duplicate with FilePublisher?

  import InputStreamPublisher._

  val arr = Array.ofDim[Byte](chunkSize)
  var readBytesTotal = 0L

  def receive = {
    case ActorPublisherMessage.Request(elements) ⇒ readAndSignal()
    case Continue                                ⇒ readAndSignal()
    case ActorPublisherMessage.Cancel            ⇒ context.stop(self)
  }

  def readAndSignal(): Unit =
    if (isActive) {
      readAndEmit()
      if (totalDemand > 0 && isActive) self ! Continue
    }

  def readAndEmit(): Unit = if (totalDemand > 0) try {
    // blocking read
    val readBytes = is.read(arr)

    readBytes match {
      case -1 ⇒
        // had nothing to read into this chunk
        log.debug("No more bytes available to read (got `-1` from `read`)")
        onCompleteThenStop()

      case _ ⇒
        readBytesTotal += readBytes

        // emit immediately, as this is the only chance to do it before we might block again
        onNext(ByteString.fromArray(arr, 0, readBytes))
    }
  } catch {
    case ex: Exception ⇒
      onErrorThenStop(ex)
  }

  override def postStop(): Unit = {
    super.postStop()

    try {
      if (is ne null) is.close()
    } catch {
      case ex: Exception ⇒
        completionPromise.success(IOResult(readBytesTotal, Failure(ex)))
    }

    completionPromise.trySuccess(IOResult(readBytesTotal, Success(Done)))
  }
}
