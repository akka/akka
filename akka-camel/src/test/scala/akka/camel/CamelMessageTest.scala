/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel

import java.net.URL
import javax.activation.DataHandler

import org.apache.camel.impl.{ DefaultExchange, DefaultMessage }
import akka.camel.TestSupport.SharedCamelSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

//TODO merge it with MessageScalaTest
class CamelMessageTest extends Matchers with WordSpecLike with SharedCamelSystem {

  "CamelMessage copyContent" must {
    "create a new CamelMessage with additional headers, attachments and new body" in {
      val attachment = new DataHandler(new URL("https://foo.bar"))
      val message = new DefaultMessage
      message.setBody("test")
      message.setHeader("foo", "bar")
      message.addAttachment("foo", attachment)
      message.setExchange(new DefaultExchange(camel.context))

      val attachmentToAdd = new DataHandler(new URL("https://another.url"))
      CamelMessage.copyContent(new CamelMessage("body", Map("key" → "baz"), Map("key" → attachmentToAdd)), message)

      assert(message.getBody === "body")
      assert(message.getHeader("foo") === "bar")
      assert(message.getHeader("key") === "baz")
      assert(message.getAttachment("key") === attachmentToAdd)
      assert(message.getAttachment("foo") === attachment)
    }
  }
}
