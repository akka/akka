package akka.camel

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.apache.camel.impl.{DefaultExchange, DefaultMessage}
import akka.camel.TestSupport.{SharedCamelSystem, MessageSugar}

class CamelMessageAdapterTest extends JUnitSuite with SharedCamelSystem with MessageSugar{
  import CamelMessageConversion.toMessageAdapter

  @Test def shouldOverwriteBodyAndAddHeader = {
    val cm = sampleMessage.copyContentFrom(Message("blah", Map("key" -> "baz")))
    assert(cm.getBody === "blah")
    assert(cm.getHeader("foo") === "bar")
    assert(cm.getHeader("key") === "baz")
  }

  @Test def shouldCreateMessageWithBodyAndHeader = {
    val m = sampleMessage.toMessage
    assert(m.body === "test")
    assert(m.headers("foo") === "bar")
  }

  @Test def shouldCreateMessageWithBodyAndHeaderAndCustomHeader = {
    val m = sampleMessage.toMessage(Map("key" -> "baz"))
    assert(m.body === "test")
    assert(m.headers("foo") === "bar")
    assert(m.headers("key") === "baz")
  }

  private[camel] def sampleMessage = {
    val message = new DefaultMessage
    message.setBody("test")
    message.setHeader("foo", "bar")
    message.setExchange(new DefaultExchange(camel.context))
    message
  }
}
