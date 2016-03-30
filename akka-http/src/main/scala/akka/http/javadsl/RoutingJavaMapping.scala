/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.concurrent.CompletionStage

import akka.http.impl.util.JavaMapping
import akka.http.impl.util.JavaMapping.Inherited
import akka.http.javadsl
import akka.http.scaladsl
import javadsl.server.{ directives ⇒ jdirectives }
import scaladsl.server.{ directives ⇒ sdirectives }

/** INTERNAL API */
private[http] object RoutingJavaMapping {
  implicit object Rejection extends Inherited[javadsl.server.Rejection, scaladsl.server.Rejection]

  implicit object DirectoryRenderer extends Inherited[jdirectives.DirectoryRenderer, sdirectives.FileAndResourceDirectives.DirectoryRenderer]
  implicit object ContentTypeResolver extends Inherited[jdirectives.ContentTypeResolver, sdirectives.ContentTypeResolver]
  implicit object DirectoryListing extends Inherited[jdirectives.DirectoryListing, sdirectives.DirectoryListing]

  implicit final class ConvertCompletionStage[T](val stage: CompletionStage[T]) extends AnyVal {
    import scala.compat.java8.FutureConverters._
    def asScala = stage.toScala
  }
}

