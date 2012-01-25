/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.MustMatchers
import org.scalatest.FlatSpec
import akka.util.duration._
import akka.camel.{ CommunicationStyle, Blocking, NonBlocking }

class CommunicationStyleTypeConverterTest extends FlatSpec with MustMatchers {

  "TypeConverter" must "convert NonBlocking" in {
    convert("NonBlocking") must be(NonBlocking)
  }

  it must "convert Blocking with seconds" in {
    convert(CommunicationStyleTypeConverter.toString(Blocking(10 seconds))) must be(Blocking(10 seconds))
  }

  it must "convert Blocking with millis" in {
    convert(CommunicationStyleTypeConverter.toString(Blocking(10 millis))) must be(Blocking(10 millis))
  }

  def convert(value: String): CommunicationStyle = {
    CommunicationStyleTypeConverter.convertTo(classOf[CommunicationStyle], value)
  }

}

