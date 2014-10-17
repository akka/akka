/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2

final case class SimpleSink[-In](override val delegate: scaladsl2.Sink[In]) extends javadsl.SinkAdapter[In] {
  override def asScala: scaladsl2.SimpleActorFlowSink[In] = super.asScala.asInstanceOf[scaladsl2.SimpleActorFlowSink[In]]
}

final case class KeyedSink[-In, M](override val delegate: scaladsl2.Sink[In]) extends javadsl.SinkAdapter[In] {
  override def asScala: scaladsl2.KeyedSink[In] = super.asScala.asInstanceOf[scaladsl2.KeyedSink[In]]
}
