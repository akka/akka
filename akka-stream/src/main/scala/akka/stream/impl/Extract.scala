/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.scaladsl
import akka.stream.javadsl

/**
 * INTERNAL API
 *
 * Unapply methods aware of both DSLs.
 * Use these instead of manually casting to [[scaladsl.Source]].
 */
private[akka] object Extract {

  object Source {
    def unapply(a: Any): Option[scaladsl.Source[Any]] = a match {
      case s: scaladsl.Source[Any] ⇒ Some(s)
      case s: javadsl.Source[Any]  ⇒ Some(s.asScala)
      case _                       ⇒ None
    }
  }

  object Sink {
    def unapply(a: Any): Option[scaladsl.Sink[Any]] = a match {
      case s: scaladsl.Sink[Any] ⇒ Some(s)
      case s: javadsl.Sink[Any]  ⇒ Some(s.asScala)
      case _                     ⇒ None
    }
  }

}
