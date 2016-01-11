/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, RandomAccessFile }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import akka.actor.{ Deploy, ActorLogging, DeadLetterSuppression, Props }
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.util.control.NonFatal

/** INTERNAL API */
private[akka] object FilePublisher {
  def props(f: File, completionPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(initialBuffer > 0, s"initialBuffer must be > 0 (was $initialBuffer)")
    require(maxBuffer >= initialBuffer, s"maxBuffer must be >= initialBuffer (was $maxBuffer)")

    Props(classOf[FilePublisher], f, completionPromise, chunkSize, initialBuffer, maxBuffer)
      .withDeploy(Deploy.local)
  }

  private final case object Continue extends DeadLetterSuppression

}

/** INTERNAL API */
private[akka] final class FilePublisher(f: File, bytesReadPromise: Promise[Long], chunkSize: Int, initialBuffer: Int, maxBuffer: Int)
  extends akka.stream.actor.ActorPublisher[ByteString] with ActorLogging {
  import FilePublisher._

  var eofReachedAtOffset = Long.MinValue

  val buf = ByteBuffer.allocate(chunkSize)
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

  def readAndSignal(maxReadAhead: Int): Unit =
    if (isActive) {
      // Write previously buffered, read into buffer, write newly buffered
      availableChunks = signalOnNexts(readAhead(maxReadAhead, signalOnNexts(availableChunks)))
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
  @tailrec final def readAhead(maxChunks: Int, chunks: Vector[ByteString]): Vector[ByteString] =
    if (chunks.size <= maxChunks && isActive) {
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

  private final def eofEncountered: Boolean = eofReachedAtOffset != Long.MinValue

  override def postStop(): Unit = {
    super.postStop()
    bytesReadPromise.trySuccess(readBytesTotal)

    try if (chan ne null) chan.close()
    finally if (raf ne null) raf.close()
  }
}
