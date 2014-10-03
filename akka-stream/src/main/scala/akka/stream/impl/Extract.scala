/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.scaladsl2
import akka.stream.javadsl

/**
 * INTERNAL API
 *
 * Unapply methods aware of both DSLs.
 * Use these instead of manually casting to [[scaladsl2.Source]].
 */
private[akka] object Extract {

  object Source {
    def unapply(a: Any): Option[scaladsl2.Source[Any]] = a match {
      case s: scaladsl2.Source[Any]      ⇒ Some(s)
      case s: javadsl.SourceAdapter[Any] ⇒ Some(s.asScala)
      case _                             ⇒ None
    }
  }

  object Sink {
    def unapply(a: Any): Option[scaladsl2.Sink[Any]] = a match {
      case s: scaladsl2.Sink[Any]      ⇒ Some(s)
      case s: javadsl.SinkAdapter[Any] ⇒ Some(s.asScala)
      case _                           ⇒ None
    }
  }

}
