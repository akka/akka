/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io.impl

import java.io.{ File, RandomAccessFile }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import akka.actor.{ ActorLogging, DeadLetterSuppression, Props }
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object SynchronousFilePublisher {
  def props(f: File, completionPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(initialBuffer > 0, s"initialBuffer must be > 0 (was $initialBuffer)")
    require(maxBuffer >= initialBuffer, s"maxBuffer must be >= initialBuffer (was $maxBuffer)")

    Props(classOf[SynchronousFilePublisher], f, completionPromise, chunkSize, initialBuffer, maxBuffer)
  }

  private final case object Continue extends DeadLetterSuppression

}

/** INTERNAL API */
private[akka] class SynchronousFilePublisher(f: File, bytesReadPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int)
  extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  import SynchronousFilePublisher._

  var eofReachedAtOffset = Long.MinValue

  var readBytesTotal = 0L
  var availableChunks: Vector[ByteString] = Vector.empty // TODO possibly resign read-ahead-ing and make fusable as Stage

  private var raf: RandomAccessFile = _
  private var chan: FileChannel = _

  override def preStart() = {
    try {
      raf = new RandomAccessFile(f, "r") // best way to express this in JDK6, OpenOption are available since JDK7
      chan = raf.getChannel
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
    val buf = ByteBuffer.allocate(chunkSize)

    // blocking read
    val readBytes = chan.read(buf)

    readBytes match {
      case -1 ⇒
        // had nothing to read into this chunk
        eofReachedAtOffset = chan.position
        log.debug("No more bytes available to read (got `-1` or `0` from `read`), marking final bytes of file @ " + eofReachedAtOffset)

      case _ ⇒
        readBytesTotal += readBytes
        availableChunks :+= ByteString(buf.array).take(readBytes)
    }
  } catch {
    case ex: Exception ⇒
      onErrorThenStop(ex)
  }

  private final def eofEncountered: Boolean = eofReachedAtOffset != Long.MinValue

  override def postStop(): Unit = {
    super.postStop()
    bytesReadPromise.trySuccess(readBytesTotal)

    try if (chan ne null) chan.close()
    finally if (raf ne null) raf.close()
  }
}
