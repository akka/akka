/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpEntity
import akka.http.javadsl.server.Route
import akka.http.javadsl.server.Unmarshaller

import akka.http.scaladsl.server.directives.{ MarshallingDirectives ⇒ D }

abstract class MarshallingDirectives extends HostDirectives {
  /**
   * Unmarshalls the request using the given unmarshaller, and passes the result to [inner].
   * If there is a problem with unmarshalling the request is rejected with the [[Rejection]]
   * produced by the unmarshaller.
   */
  def request[T](unmarshaller: Unmarshaller[_ >: HttpRequest, T],
                 inner: java.util.function.Function[T, Route]): Route = ScalaRoute {
    D.entity(unmarshaller.asScala) { value ⇒
      inner.apply(value).toScala
    }
  }

  /**
   * Unmarshalls the requests entity using the given unmarshaller, and passes the result to [inner].
   * If there is a problem with unmarshalling the request is rejected with the [[Rejection]]
   * produced by the unmarshaller.
   */
  def entity[T](unmarshaller: Unmarshaller[_ >: HttpEntity, T],
                inner: java.util.function.Function[T, Route]): Route = ScalaRoute {
    D.entity(Unmarshaller.requestToEntity.flatMap(unmarshaller).asScala) { value ⇒
      inner.apply(value).toScala
    }
  }

  // If you want the raw entity, use BasicDirectives.extractEntity
}
