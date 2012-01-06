package akka.camel

import org.apache.camel.impl.DefaultMessage
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll

class CamelMessageAdapterTest extends JUnitSuite with BeforeAndAfterAll with CamelSupport with MessageSugar{
  import CamelMessageConversion.toMessageAdapter

  @Test def shouldOverwriteBodyAndAddHeader = {
    val cm = sampleMessage.fromMessage(Message("blah", Map("key" -> "baz")))
    assert(cm.getBody === "blah")
    assert(cm.getHeader("foo") === "bar")
    assert(cm.getHeader("key") === "baz")
  }

  @Test def shouldCreateMessageWithBodyAndHeader = {
    val m = sampleMessage.toMessage(null)
    assert(m.body === "test")
    assert(m.headers("foo") === "bar")
  }

  @Test def shouldCreateMessageWithBodyAndHeaderAndCustomHeader = {
    val m = sampleMessage.toMessage(Map("key" -> "baz"), null)
    assert(m.body === "test")
    assert(m.headers("foo") === "bar")
    assert(m.headers("key") === "baz")
  }

  private[camel] def sampleMessage = {
    val message = new DefaultMessage
    message.setBody("test")
    message.setHeader("foo", "bar")
    message
  }


}
