/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, OutputStream }
import akka.stream.io.IOResult
import akka.stream.impl.SinkModule
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.Stages.DefaultAttributes.IODispatcher
import akka.stream.{ ActorMaterializer, MaterializationContext, Attributes, SinkShape }
import akka.stream.ActorAttributes.Dispatcher
import akka.util.ByteString
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class FileSink(f: File, append: Boolean, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[IOResult]](shape) {

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializer.downcast(context.materializer)
    val settings = materializer.effectiveSettings(context.effectiveAttributes)

    val ioResultPromise = Promise[IOResult]()
    val props = FileSubscriber.props(f, ioResultPromise, settings.maxInputBufferSize, append)
    val dispatcher = context.effectiveAttributes.get[Dispatcher](IODispatcher).dispatcher

    val ref = materializer.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorSubscriber[ByteString](ref), ioResultPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[IOResult]] =
    new FileSink(f, append, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new FileSink(f, append, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class OutputStreamSink(createOutput: () ⇒ OutputStream, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[IOResult]](shape) {

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializer.downcast(context.materializer)
    val settings = materializer.effectiveSettings(context.effectiveAttributes)
    val ioResultPromise = Promise[IOResult]()

    val os = createOutput() // if it fails, we fail the materialization

    val props = OutputStreamSubscriber.props(os, ioResultPromise, settings.maxInputBufferSize)

    val ref = materializer.actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[ByteString](ref), ioResultPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[IOResult]] =
    new OutputStreamSink(createOutput, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new OutputStreamSink(createOutput, attr, amendShape(attr))
}

