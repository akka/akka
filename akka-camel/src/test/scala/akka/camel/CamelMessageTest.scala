/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.apache.camel.impl.{ DefaultExchange, DefaultMessage }
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

//TODO merge it with MessageScalaTest
class CamelMessageTest extends Matchers with WordSpecLike with SharedCamelSystem {

  "CamelMessage" must {

    "overwrite body and add header" in {
      val msg = sampleMessage
      CamelMessage.copyContent(CamelMessage("blah", Map("key" -> "baz")), msg)
      assert(msg.getBody === "blah")
      assert(msg.getHeader("foo") === "bar")
      assert(msg.getHeader("key") === "baz")
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
