/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.scaladsl2

final case class SimpleSource[+Out](override val delegate: scaladsl2.Source[Out]) extends SourceAdapter[Out] {
  override def asScala: scaladsl2.SimpleActorFlowSource[Out] = super.asScala.asInstanceOf[scaladsl2.SimpleActorFlowSource[Out]]
}

final case class KeyedSource[+Out, T](override val delegate: scaladsl2.Source[Out]) extends SourceAdapter[Out] {
  override def asScala: scaladsl2.KeyedActorFlowSource[Out] = super.asScala.asInstanceOf[scaladsl2.KeyedActorFlowSource[Out]]
}
