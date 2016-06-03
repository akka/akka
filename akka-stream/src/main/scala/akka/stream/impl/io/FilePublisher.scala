/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path

import akka.Done
import akka.actor.{ Deploy, ActorLogging, DeadLetterSuppression, Props }
import akka.stream.actor.ActorPublisherMessage
import akka.stream.IOResult
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

/** INTERNAL API */
private[akka] object FilePublisher {
  def props(f: Path, completionPromise: Promise[IOResult], chunkSize: Int, initialBuffer: Int, maxBuffer: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(initialBuffer > 0, s"initialBuffer must be > 0 (was $initialBuffer)")
    require(maxBuffer >= initialBuffer, s"maxBuffer must be >= initialBuffer (was $maxBuffer)")

    Props(classOf[FilePublisher], f, completionPromise, chunkSize, initialBuffer, maxBuffer)
      .withDeploy(Deploy.local)
  }

  private case object Continue extends DeadLetterSuppression

  val Read = java.util.Collections.singleton(java.nio.file.StandardOpenOption.READ)
}

/** INTERNAL API */
private[akka] final class FilePublisher(f: Path, completionPromise: Promise[IOResult], chunkSize: Int, initialBuffer: Int, maxBuffer: Int)
  extends akka.stream.actor.ActorPublisher[ByteString] with ActorLogging {
  import FilePublisher._

  var eofReachedAtOffset = Long.MinValue

  val buf = ByteBuffer.allocate(chunkSize)
  var readBytesTotal = 0L
  var availableChunks: Vector[ByteString] = Vector.empty // TODO possibly resign read-ahead-ing and make fusable as Stage

  private var chan: FileChannel = _

  override def preStart() = {
    try {
      chan = FileChannel.open(f, FilePublisher.Read)
    } catch {
      case NonFatal(ex) ⇒
        completionPromise.trySuccess(IOResult(0L, Failure(ex)))
        onErrorThenStop(ex)
    }

    super.preStart()
  }

  def receive = {
    case ActorPublisherMessage.Request(elements) ⇒ readAndSignal(maxBuffer)
    case Continue                                ⇒ readAndSignal(maxBuffer)
    case ActorPublisherMessage.Cancel            ⇒ context.stop(self)
  }

  def readAndSignal(maxReadAhead: Int): Unit =
    if (isActive) {
      // Write previously buffered, then refill buffer
      availableChunks = readAhead(maxReadAhead, signalOnNexts(availableChunks))
      if (totalDemand > 0 && isActive) self ! Continue
    }

  @tailrec private def signalOnNexts(chunks: Vector[ByteString]): Vector[ByteString] =
    if (chunks.nonEmpty && totalDemand > 0) {
      onNext(chunks.head)
      signalOnNexts(chunks.tail)
    } else {
      if (chunks.isEmpty && eofEncountered)
        onCompleteThenStop()
      chunks
    }

  /** BLOCKING I/O READ */
  @tailrec def readAhead(maxChunks: Int, chunks: Vector[ByteString]): Vector[ByteString] =
    if (chunks.size <= maxChunks && isActive && !eofEncountered) {
      (try chan.read(buf) catch { case NonFatal(ex) ⇒ onErrorThenStop(ex); Int.MinValue }) match {
        case -1 ⇒ // EOF
          eofReachedAtOffset = chan.position
          log.debug("No more bytes available to read (got `-1` from `read`), marking final bytes of file @ " + eofReachedAtOffset)
          chunks
        case 0            ⇒ readAhead(maxChunks, chunks) // had nothing to read into this chunk
        case Int.MinValue ⇒ Vector.empty // read failed, we're done here
        case readBytes ⇒
          buf.flip()
          readBytesTotal += readBytes
          val newChunks = chunks :+ ByteString.fromByteBuffer(buf)
          buf.clear()
          readAhead(maxChunks, newChunks)
      }
    } else chunks

  private def eofEncountered: Boolean = eofReachedAtOffset != Long.MinValue

  override def postStop(): Unit = {
    super.postStop()

    try {
      if (chan ne null) chan.close()
    } catch {
      case NonFatal(ex) ⇒
        completionPromise.trySuccess(IOResult(readBytesTotal, Failure(ex)))
    }

    completionPromise.trySuccess(IOResult(readBytesTotal, Success(Done)))
  }
}
