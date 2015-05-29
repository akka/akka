/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.InputStream

import akka.actor.{ Deploy, ActorLogging, DeadLetterSuppression, Props }
import akka.io.DirectByteBufferPool
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString
import akka.util.ByteString.ByteString1C

import scala.annotation.tailrec
import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object InputStreamPublisher {

  def props(is: InputStream, completionPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int): Props = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(initialBuffer > 0, s"initialBuffer must be > 0 (was $initialBuffer)")
    require(maxBuffer >= initialBuffer, s"maxBuffer must be >= initialBuffer (was $maxBuffer)")

    Props(classOf[InputStreamPublisher], is, completionPromise, chunkSize, initialBuffer, maxBuffer).withDeploy(Deploy.local)
  }

  private final case object Continue extends DeadLetterSuppression
}

/** INTERNAL API */
private[akka] class InputStreamPublisher(is: InputStream, bytesReadPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int)
  extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  // TODO possibly de-duplicate with SynchronousFilePublisher?

  import InputStreamPublisher._

  val buffs = new DirectByteBufferPool(chunkSize, maxBuffer)
  var eofReachedAtOffset = Long.MinValue

  var readBytesTotal = 0L
  var availableChunks: Vector[ByteString] = Vector.empty

  override def preStart() = {
    try {
      readAndSignal(initialBuffer)
    } catch {
      case ex: Exception ⇒
        onErrorThenStop(ex)
    }

    super.preStart()
  }

  def receive = {
    case ActorPublisherMessage.Request(elements) ⇒ readAndSignal(maxBuffer)
    case Continue                                ⇒ readAndSignal(maxBuffer)
    case ActorPublisherMessage.Cancel            ⇒ context.stop(self)
  }

  def readAndSignal(readAhead: Int): Unit =
    if (isActive) {
      // signal from available buffer right away
      signalOnNexts()

      // read chunks until readAhead is fulfilled
      while (availableChunks.length < readAhead && !eofEncountered && isActive)
        loadChunk()

      if (totalDemand > 0) self ! Continue
      else if (availableChunks.isEmpty) signalOnNexts()
    }

  @tailrec private def signalOnNexts(): Unit =
    if (availableChunks.nonEmpty) {
      if (totalDemand > 0) {
        val ready = availableChunks.head
        availableChunks = availableChunks.tail

        onNext(ready)

        if (totalDemand > 0) signalOnNexts()
      }
    } else if (eofEncountered) onCompleteThenStop()

  /** BLOCKING I/O READ */
  def loadChunk() = try {
    val arr = Array.ofDim[Byte](chunkSize)

    // blocking read
    val readBytes = is.read(arr)

    readBytes match {
      case -1 ⇒
        // had nothing to read into this chunk
        eofReachedAtOffset = readBytes
        log.debug("No more bytes available to read (got `-1` or `0` from `read`), marking final bytes of file @ " + eofReachedAtOffset)

      case _ ⇒
        readBytesTotal += readBytes
        if (readBytes == chunkSize) availableChunks :+= ByteString1C(arr)
        else availableChunks :+= ByteString1C(arr).take(readBytes)

      // valid read, continue
    }
  } catch {
    case ex: Exception ⇒
      onErrorThenStop(ex)
  }

  private final def eofEncountered: Boolean = eofReachedAtOffset != Long.MinValue

  override def postStop(): Unit = {
    super.postStop()
    bytesReadPromise.trySuccess(readBytesTotal)

    if (is ne null) is.close()
  }
}
