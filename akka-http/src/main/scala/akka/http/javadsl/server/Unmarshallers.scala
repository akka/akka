/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.impl.server.UnmarshallerImpl
import akka.http.scaladsl.unmarshalling.{ FromMessageUnmarshaller, PredefinedFromEntityUnmarshallers }

object Unmarshallers {
  def String: Unmarshaller[String] =
    new UnmarshallerImpl[String]({ (ec, mat) ⇒
      implicit val _ = mat
      implicitly[FromMessageUnmarshaller[String]]
    })
}
