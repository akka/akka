/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, RandomAccessFile }
import java.nio.channels.FileChannel

import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object SynchronousFileSubscriber {
  def props(f: File, completionPromise: Promise[Long], bufSize: Int, append: Boolean) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[SynchronousFileSubscriber], f, completionPromise, bufSize, append).withDeploy(Deploy.local)
  }

}

/** INTERNAL API */
private[akka] class SynchronousFileSubscriber(f: File, bytesWrittenPromise: Promise[Long], bufSize: Int, append: Boolean)
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var raf: RandomAccessFile = _
  private var chan: FileChannel = _

  private var bytesWritten: Long = 0

  override def preStart(): Unit = try {
    raf = new RandomAccessFile(f, "rw") // best way to express this in JDK6, OpenOption are available since JDK7
    chan = raf.getChannel

    // manually supporting appending to files - in Java 7 we could use OpenModes: FileChannel.open(f, openOptions.asJava)
    if (append) chan.position(chan.size())

    super.preStart()
  } catch {
    case ex: Exception ⇒
      bytesWrittenPromise.failure(ex)
      cancel()
  }

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        bytesWritten += chan.write(bytes.asByteBuffer)
      } catch {
        case ex: Exception ⇒
          bytesWrittenPromise.failure(ex)
          cancel()
      }

    case ActorSubscriberMessage.OnError(cause) ⇒
      log.error(cause, "Tearing down SynchronousFileSink({}) due to upstream error", f.getAbsolutePath)
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      try {
        chan.force(true)
      } catch {
        case ex: Exception ⇒
          bytesWrittenPromise.failure(ex)
      }
      context.stop(self)
  }

  override def postStop(): Unit = {
    bytesWrittenPromise.trySuccess(bytesWritten)

    if (chan ne null) chan.close()
    if (raf ne null) raf.close()
    super.postStop()
  }
}
