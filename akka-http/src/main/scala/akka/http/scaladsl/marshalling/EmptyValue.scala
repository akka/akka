/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import scala.collection.immutable
import akka.http.scaladsl.model._

class EmptyValue[+T] private (val emptyValue: T)

object EmptyValue {
  implicit def emptyEntity: EmptyValue[UniversalEntity] =
    new EmptyValue[UniversalEntity](HttpEntity.Empty)

  implicit val emptyHeadersAndEntity: EmptyValue[(immutable.Seq[HttpHeader], UniversalEntity)] =
    new EmptyValue[(immutable.Seq[HttpHeader], UniversalEntity)](Nil -> HttpEntity.Empty)

  implicit val emptyResponse: EmptyValue[HttpResponse] =
    new EmptyValue[HttpResponse](HttpResponse(entity = emptyEntity.emptyValue))
}