package akka.camel

import org.apache.camel.impl.{ DefaultProducerTemplate, DefaultCamelContext }
import org.junit.Test
import org.scalatest.junit.JUnitSuite

class CamelContextLifecycleTest extends JUnitSuite with CamelContextLifecycle {
  @Test
  def shouldManageCustomCamelContext {
    assert(context === None)
    assert(template === None)

    intercept[IllegalStateException] { mandatoryContext }
    intercept[IllegalStateException] { mandatoryTemplate }

    val ctx = new TestCamelContext
    assert(ctx.isStreamCaching === false)

    init(ctx)

    assert(mandatoryContext.isStreamCaching === true)
    assert(!mandatoryContext.asInstanceOf[TestCamelContext].isStarted)
    assert(mandatoryTemplate.asInstanceOf[DefaultProducerTemplate].isStarted)

    start

    assert(mandatoryContext.asInstanceOf[TestCamelContext].isStarted)
    assert(mandatoryTemplate.asInstanceOf[DefaultProducerTemplate].isStarted)

    stop

    assert(!mandatoryContext.asInstanceOf[TestCamelContext].isStarted)
    assert(!mandatoryTemplate.asInstanceOf[DefaultProducerTemplate].isStarted)
  }

  class TestCamelContext extends DefaultCamelContext
}
