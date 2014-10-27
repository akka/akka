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

  implicit final class AddAsJavaSource[Out](val source: scaladsl.Source[Out]) extends AnyVal {
    def asJava: javadsl.Source[Out] = new javadsl.Source(source)
  }
  implicit final class AddAsJavaUndefinedSource[Out](val source: scaladsl.UndefinedSource[Out]) extends AnyVal {
    def asJava: javadsl.UndefinedSource[Out] = new javadsl.UndefinedSource(source)
  }
  implicit final class AddAsJavaFlow[In, Out](val flow: scaladsl.Flow[In, Out]) extends AnyVal {
    def asJava: javadsl.Flow[In, Out] = new javadsl.Flow[In, Out](flow)
  }
  implicit final class AddAsJavaSink[In](val sink: scaladsl.Sink[In]) extends AnyVal {
    def asJava: javadsl.Sink[In] = new javadsl.Sink[In](sink)
  }
  implicit final class AddAsJavaUndefinedSink[Out](val sink: scaladsl.UndefinedSink[Out]) extends AnyVal {
    def asJava: javadsl.UndefinedSink[Out] = new javadsl.UndefinedSink(sink)
  }
  implicit final class AsAsJavaFlowGraphBuilder[Out](val builder: scaladsl.FlowGraphBuilder) extends AnyVal {
    def asJava: javadsl.FlowGraphBuilder = new javadsl.FlowGraphBuilder(builder)
  }

  implicit final class AddAsScalaSource[Out](val source: javadsl.Source[Out]) extends AnyVal {
    def asScala: scaladsl.Source[Out] = source.asInstanceOf[javadsl.Source[Out]].asScala
  }
  implicit final class AsAsScalaUndefinedSource[Out](val source: javadsl.UndefinedSource[Out]) extends AnyVal {
    def asScala: scaladsl.UndefinedSource[Out] = source.asScala
  }
  implicit final class AddAsScalaFlow[In, Out](val flow: javadsl.Flow[In, Out]) extends AnyVal {
    def asScala: scaladsl.Flow[In, Out] = flow.asInstanceOf[javadsl.Flow[In, Out]].asScala
  }
  implicit final class AddAsScalaSink[In](val sink: javadsl.Sink[In]) extends AnyVal {
    def asScala: scaladsl.Sink[In] = sink.asInstanceOf[javadsl.Sink[In]].asScala
  }
  implicit final class AsAsScalaUndefinedSink[Out](val sink: javadsl.UndefinedSink[Out]) extends AnyVal {
    def asScala: scaladsl.UndefinedSink[Out] = sink.asScala
  }
  implicit final class AsAsScalaFlowGraphBuilder[Out](val builder: javadsl.FlowGraphBuilder) extends AnyVal {
    def asScala: FlowGraphBuilder = builder.asScala
  }
}
