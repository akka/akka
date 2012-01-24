/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import akka.util.duration._
import akka.camel.{ CommunicationStyle, Blocking, NonBlocking }

class CommunicationStyleTypeConverterTest extends FlatSpec with ShouldMatchers {

  "TypeConverter" should "convert NonBlocking" in {
    convert("NonBlocking") should be(NonBlocking)
  }

  it should "convert Blocking with seconds" in {
    convert(CommunicationStyleTypeConverter.toString(Blocking(10 seconds))) should be(Blocking(10 seconds))
  }

  it should "convert Blocking with millis" in {
    convert(CommunicationStyleTypeConverter.toString(Blocking(10 millis))) should be(Blocking(10 millis))
  }

  def convert(value: String): CommunicationStyle = {
    CommunicationStyleTypeConverter.convertTo(classOf[CommunicationStyle], value)
  }

}

