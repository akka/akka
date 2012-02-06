/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.impl.{ DefaultExchange, DefaultMessage }
import org.apache.camel.{Message => CamelMessage}
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec

//TODO merge it with MessageScalaTest
class MessageTest extends MustMatchers with WordSpec with SharedCamelSystem{

  "Message" must {

    "overwrite body and add header" in {
      val msg = sampleMessage
      Message("blah", Map("key" -> "baz")).copyContentTo(msg)
      assert(msg.getBody === "blah")
      assert(msg.getHeader("foo") === "bar")
      assert(msg.getHeader("key") === "baz")
    }

    "create message with body and header" in {
      val m = Message.from(sampleMessage)
      assert(m.body === "test")
      assert(m.headers("foo") === "bar")
    }

    "create message with body and header and custom header" in {
      val m = Message.from(sampleMessage, Map("key" -> "baz"))
      assert(m.body === "test")
      assert(m.headers("foo") === "bar")
      assert(m.headers("key") === "baz")
    }
  }

  private[camel] def sampleMessage = {
    val message = new DefaultMessage
    message.setBody("test")
    message.setHeader("foo", "bar")
    message.setExchange(new DefaultExchange(camel.context))
    message
  }
}
