/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.javadsl
import akka.stream.scaladsl

/**
 * Implicit converters allowing to convert between Java and Scala DSL elements.
 */
private[akka] object JavaConverters {

  implicit final class AddAsJavaSource[Out, Mat](val source: scaladsl.Source[Out, Mat]) extends AnyVal {
    def asJava: javadsl.Source[Out, Mat] = new javadsl.Source(source)
  }
  implicit final class AddAsJavaFlow[In, Out, Mat](val flow: scaladsl.Flow[In, Out, Mat]) extends AnyVal {
    def asJava: javadsl.Flow[In, Out, Mat] = new javadsl.Flow(flow)
  }
  implicit final class AddAsJavaBidiFlow[I1, O1, I2, O2, Mat](val flow: scaladsl.BidiFlow[I1, O1, I2, O2, Mat]) extends AnyVal {
    def asJava: javadsl.BidiFlow[I1, O1, I2, O2, Mat] = new javadsl.BidiFlow(flow)
  }
  implicit final class AddAsJavaSink[In, Mat](val sink: scaladsl.Sink[In, Mat]) extends AnyVal {
    def asJava: javadsl.Sink[In, Mat] = new javadsl.Sink(sink)
  }
  implicit final class AsAsJavaFlowGraphBuilder[Out](val builder: scaladsl.FlowGraph.Builder) extends AnyVal {
    def asJava: javadsl.FlowGraph.Builder = new javadsl.FlowGraph.Builder()(builder)
  }

  implicit final class AddAsScalaSource[Out, Mat](val source: javadsl.Source[Out, Mat]) extends AnyVal {
    def asScala: scaladsl.Source[Out, Mat] = source.asScala
  }
  implicit final class AddAsScalaFlow[In, Out, Mat](val flow: javadsl.Flow[In, Out, Mat]) extends AnyVal {
    def asScala: scaladsl.Flow[In, Out, Mat] = flow.asScala
  }
  implicit final class AddAsScalaBidiFlow[I1, O1, I2, O2, Mat](val flow: javadsl.BidiFlow[I1, O1, I2, O2, Mat]) extends AnyVal {
    def asScala: scaladsl.BidiFlow[I1, O1, I2, O2, Mat] = flow.asScala
  }
  implicit final class AddAsScalaSink[In, Mat](val sink: javadsl.Sink[In, Mat]) extends AnyVal {
    def asScala: scaladsl.Sink[In, Mat] = sink.asScala
  }
  implicit final class AsAsScalaFlowGraphBuilder[Out](val builder: javadsl.FlowGraph.Builder) extends AnyVal {
    def asScala: FlowGraph.Builder = builder.asScala
  }
}
