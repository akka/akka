/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.collection.immutable
import akka.http.model._

class EmptyValue[+T] private (val emptyValue: T)

object EmptyValue {
  implicit def emptyEntity = new EmptyValue[HttpEntity.Regular](HttpEntity.Empty)
  implicit val emptyHeadersAndEntity = new EmptyValue[(immutable.Seq[HttpHeader], HttpEntity.Regular)](Nil -> HttpEntity.Empty)
  implicit val emptyResponse = new EmptyValue[HttpResponse](HttpResponse(entity = emptyEntity.emptyValue))
}