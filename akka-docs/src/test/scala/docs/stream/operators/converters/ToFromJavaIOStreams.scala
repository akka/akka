/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.converters

// #import
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import akka.NotUsed
import akka.stream.IOResult
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
// #import
import akka.testkit.AkkaSpec
import org.scalatest.concurrent.Futures

import scala.concurrent.Future

class ToFromJavaIOStreams extends AkkaSpec with Futures {

  "demonstrate conversion from java.io.streams" in {

    //#tofromJavaIOStream
    val bytes = "Some random input".getBytes
    val inputStream = new ByteArrayInputStream(bytes)
    val outputStream = new ByteArrayOutputStream()

    val source: Source[ByteString, Future[IOResult]] = StreamConverters.fromInputStream(() => inputStream)

    val toUpperCase: Flow[ByteString, ByteString, NotUsed] = Flow[ByteString].map(_.map(_.toChar.toUpper.toByte))

    val sink: Sink[ByteString, Future[IOResult]] = StreamConverters.fromOutputStream(() => outputStream)

    val eventualResult = source.via(toUpperCase).runWith(sink)

    //#tofromJavaIOStream
    whenReady(eventualResult) { _ =>
      outputStream.toByteArray.map(_.toChar).mkString should be("SOME RANDOM INPUT")
    }

  }

}
