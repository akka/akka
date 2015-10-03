/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, IOException, InputStream, OutputStream }
import java.lang.{ Long ⇒ JLong }
import java.util.concurrent.{ LinkedBlockingQueue, BlockingQueue }

import akka.actor.{ ActorRef, Deploy }
import akka.japi
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.{ ErrorPublisher, SourceModule }
import akka.util.{ ByteString, Timeout }
import org.reactivestreams._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Source backed by the given file.
 */
private[akka] final class SynchronousFileSource(f: File, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    // FIXME rewrite to be based on AsyncStage rather than dangerous downcasts
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesReadPromise = Promise[Long]()
    val props = SynchronousFilePublisher.props(f, bytesReadPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)
    val dispatcher = IOSettings.blockingIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))

    (akka.stream.actor.ActorPublisher[ByteString](ref), bytesReadPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[Long]] =
    new SynchronousFileSource(f, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new SynchronousFileSource(f, chunkSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Source backed by the given input stream.
 */
private[akka] final class InputStreamSource(createInputStream: () ⇒ InputStream, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)
    val bytesReadPromise = Promise[Long]()

    val pub = try {
      val is = createInputStream() // can throw, i.e. FileNotFound

      val props = InputStreamPublisher.props(is, bytesReadPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)

      val ref = mat.actorOf(context, props)
      akka.stream.actor.ActorPublisher[ByteString](ref)
    } catch {
      case ex: Exception ⇒
        bytesReadPromise.failure(ex)
        ErrorPublisher(ex, attributes.nameOrDefault("inputStreamSource")).asInstanceOf[Publisher[ByteString]]
    }

    (pub, bytesReadPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[Long]] =
    new InputStreamSource(createInputStream, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new InputStreamSource(createInputStream, chunkSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Source backed by OutputStream
 */
private[akka] class OutputStreamSource(val timeout: FiniteDuration,
                                       val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, (OutputStream, Future[Long])](shape) {

  protected def getPublisher(buffer: BlockingQueue[ByteString], bytesReadPromise: Promise[Long]) =
    OutputStreamPublisher.props(buffer, bytesReadPromise)

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesReadPromise = Promise[Long]()
    val sharedBuffer = new LinkedBlockingQueue[ByteString](settings.maxInputBufferSize)
    val props = getPublisher(sharedBuffer, bytesReadPromise)
    val dispatcher = IOSettings.blockingIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorPublisher[ByteString](ref),
      (new OutputStreamAdapter(sharedBuffer, ref, timeout), bytesReadPromise.future))
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, (OutputStream, Future[Long])] =
    new OutputStreamSource(timeout, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new OutputStreamSource(timeout, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Source backed by OutputStream. For Java API usages (returns Java API Pair)
 */
private[akka] final class JavaOutputStreamSource(val timeout: FiniteDuration,
                                                 val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, japi.Pair[OutputStream, Future[JLong]]](shape) {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  val sink = new OutputStreamSource(timeout, attributes, shape)

  override def create(context: MaterializationContext) = {
    val result = sink.create(context)
    (result._1, new japi.Pair(result._2._1, result._2._2.map(long2Long)(ec)))
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, japi.Pair[OutputStream, Future[JLong]]] =
    new JavaOutputStreamSource(timeout, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new JavaOutputStreamSource(timeout, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * OutputStreamAdapter that interacts with OutputStreamPublisher
 */
private[akka] final class OutputStreamAdapter(sharedBuffer: BlockingQueue[ByteString],
                                              val publisher: ActorRef,
                                              val timeout: FiniteDuration) extends OutputStream {
  import akka.pattern.ask

  var isActive = true
  var isPublisherAlive = true
  val publisherClosedException = new IOException("Reactive stream is terminated, no writes are possible")

  private[this] def sendMessage(message: OutputStreamPublisher.OutputStreamPublisherInMessage, handleCancelled: Boolean = true) =
    if (isActive) {
      if (isPublisherAlive) {
        try {
          Await.result(publisher.ask(message)(Timeout(timeout)), timeout) match {
            case OutputStreamPublisher.Canceled if handleCancelled ⇒
              //Publisher considered to be terminated at earliest convenience to minimize messages sending back and forth
              isPublisherAlive = false
              throw publisherClosedException
            case OutputStreamPublisher.Ok ⇒ ()
          }
        } catch {
          case e: IOException ⇒ throw e
          case NonFatal(e)    ⇒ throw new IOException(e)
        }
      } else throw publisherClosedException
    } else throw new IOException("OutputStream is closed")

  @scala.throws(classOf[IOException])
  override def write(b: Int) = {
    sharedBuffer.add(ByteString(b))
    sendMessage(OutputStreamPublisher.WriteNotification)
  }

  @scala.throws(classOf[IOException])
  override def write(b: Array[Byte], off: Int, len: Int) = {
    sharedBuffer.add(ByteString(b).drop(off))
    sendMessage(OutputStreamPublisher.WriteNotification)
  }

  @scala.throws(classOf[IOException])
  override def flush() = sendMessage(OutputStreamPublisher.Flush)

  @scala.throws(classOf[IOException])
  override def close() = {
    sendMessage(OutputStreamPublisher.Close, handleCancelled = false)
    isActive = false
  }
}

