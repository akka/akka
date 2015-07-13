/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, IOException, InputStream, OutputStream }

import akka.actor.ActorRef
import akka.stream.impl.SinkModule
import akka.stream.impl.StreamLayout.Module
import akka.stream._
import akka.util.{ ByteString, Timeout }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Promise, Await, Future }
import scala.util.control.NonFatal
import akka.japi

import java.lang.{ Long ⇒ JLong }

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class SynchronousFileSink(f: File, append: Boolean, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesWrittenPromise = Promise[Long]()
    val props = SynchronousFileSubscriber.props(f, bytesWrittenPromise, settings.maxInputBufferSize, append)
    val dispatcher = IOSettings.blockingIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new SynchronousFileSink(f, append, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new SynchronousFileSink(f, append, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class OutputStreamSink(createOutput: () ⇒ OutputStream, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)
    val bytesWrittenPromise = Promise[Long]()

    val os = createOutput() // if it fails, we fail the materialization

    val props = OutputStreamSubscriber.props(os, bytesWrittenPromise, settings.maxInputBufferSize)

    val ref = mat.actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new OutputStreamSink(createOutput, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new OutputStreamSink(createOutput, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which provide all incoming element on read request via
 * InputStream.
 */
private[akka] class InputStreamSink(val timeout: FiniteDuration,
                                    val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, (InputStream, Future[Long])](shape) {

  protected def getSubscriber(bytesWrittenPromise: Promise[Long], settings: ActorMaterializerSettings) =
    InputStreamSubscriber.props(bytesWrittenPromise, settings.maxInputBufferSize)

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesWrittenPromise = Promise[Long]()
    val props = getSubscriber(bytesWrittenPromise, settings)
    val dispatcher = IOSettings.blockingIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorSubscriber[ByteString](ref),
      (new InputStreamAdapter(ref, timeout), bytesWrittenPromise.future))
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, (InputStream, Future[Long])] =
    new InputStreamSink(timeout, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new InputStreamSink(timeout, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * For Java API usages (returns Java API Pair)
 */
private[akka] final class JavaInputStreamSink(timeout: FiniteDuration, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, japi.Pair[InputStream, Future[JLong]]](shape) {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ ec }

  val sink = new InputStreamSink(timeout, attributes, shape)

  override def create(context: MaterializationContext) = {
    val result = sink.create(context)
    (result._1, new japi.Pair(result._2._1, result._2._2.map(long2Long)(ec)))
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, japi.Pair[InputStream, Future[JLong]]] =
    new JavaInputStreamSink(timeout, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new JavaInputStreamSink(timeout, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * InputStreamAdapter that interacts with InputStreamSubscriber
 */
private[akka] final class InputStreamAdapter(subscriber: ActorRef, val timeout: FiniteDuration) extends InputStream {
  import akka.pattern.ask

  var isActive = true
  var isSubscriberAlive = true
  val subscriberClosedException = new IOException("Reactive stream is terminated, no reads are possible")

  @scala.throws(classOf[IOException])
  private[this] def executeIfNotClosed[T](f: () ⇒ T): T =
    if (isActive) f()
    else throw subscriberClosedException

  @scala.throws(classOf[IOException])
  override def read(): Int =
    executeIfNotClosed(() ⇒
      if (isSubscriberAlive) sendMessage(InputStreamSubscriber.Read(1))(0)
      else -1)

  @scala.throws(classOf[IOException])
  override def read(a: Array[Byte]): Int = read(a, 0, a.length)

  @scala.throws(classOf[IOException])
  override def read(a: Array[Byte], begin: Int, length: Int): Int = {
    executeIfNotClosed(() ⇒
      if (isSubscriberAlive) {
        val bytes = sendMessage(InputStreamSubscriber.Read(length)).toArray
        System.arraycopy(bytes, 0, a, begin, bytes.length)
        if (bytes.length != 0) bytes.length else -1
      } else -1)
  }

  @scala.throws(classOf[IOException])
  override def close(): Unit =
    executeIfNotClosed(() ⇒ {
      // at this point Subscriber may be already terminated
      if (isSubscriberAlive) subscriber.tell(InputStreamSubscriber.Close, ActorRef.noSender)
      isActive = false
    })

  private[this] def sendMessage(message: InputStreamSubscriber.InputStreamSubscriberInMessage): ByteString =
    try {
      Await.result(subscriber.ask(message)(Timeout(timeout)), timeout) match {
        case InputStreamSubscriber.Bytes(bytes, terminated) ⇒
          //Subscriber considered to be terminated at earliest convenience to minimize messages sending back and forth
          isSubscriberAlive = !terminated
          bytes
        case InputStreamSubscriber.Failed(cause) ⇒ throw new IOException(cause)
      }
    } catch {
      case e: IOException ⇒ throw e
      case NonFatal(e)    ⇒ throw new IOException(e)
    }
}

