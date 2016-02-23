/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.OutputStream

import akka.Done
import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.stream.IOResult
import akka.util.ByteString

import scala.concurrent.Promise
import scala.util.{ Failure, Success }

/** INTERNAL API */
private[akka] object OutputStreamSubscriber {
  def props(os: OutputStream, completionPromise: Promise[IOResult], bufSize: Int, autoFlush: Boolean) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[OutputStreamSubscriber], os, completionPromise, bufSize, autoFlush).withDeploy(Deploy.local)
  }

}

/** INTERNAL API */
private[akka] class OutputStreamSubscriber(os: OutputStream, completionPromise: Promise[IOResult], bufSize: Int, autoFlush: Boolean)
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var bytesWritten: Long = 0

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        // blocking write
        os.write(bytes.toArray)
        bytesWritten += bytes.length
        if (autoFlush) os.flush()
      } catch {
        case ex: Exception ⇒
          completionPromise.success(IOResult(bytesWritten, Failure(ex)))
          cancel()
      }

    case ActorSubscriberMessage.OnError(ex) ⇒
      log.error(ex, "Tearing down OutputStreamSink due to upstream error, wrote bytes: {}", bytesWritten)
      completionPromise.success(IOResult(bytesWritten, Failure(ex)))
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      context.stop(self)
      os.flush()
  }

  override def postStop(): Unit = {
    try {
      if (os ne null) os.close()
    } catch {
      case ex: Exception ⇒
        completionPromise.success(IOResult(bytesWritten, Failure(ex)))
    }

    completionPromise.trySuccess(IOResult(bytesWritten, Success(Done)))
    super.postStop()
  }
}
