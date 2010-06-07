package se.scalablesolutions.akka.camel

import org.apache.camel.impl.{DefaultProducerTemplate, DefaultCamelContext}
import org.junit.Test
import org.scalatest.junit.JUnitSuite

class CamelContextLifecycleTest extends JUnitSuite with CamelContextLifecycle {
  @Test def shouldManageCustomCamelContext {
    assert(context === null)
    assert(template === null)
    val ctx = new TestCamelContext
    assert(ctx.isStreamCaching === false)
    init(ctx)
    assert(context.isStreamCaching === true)
    assert(!context.asInstanceOf[TestCamelContext].isStarted)
    // In Camel 2.3 CamelComtext.createProducerTemplate starts
    // the template before returning it (wasn't started in 2.2)
    assert(template.asInstanceOf[DefaultProducerTemplate].isStarted)
    start
    assert(context.asInstanceOf[TestCamelContext].isStarted)
    assert(template.asInstanceOf[DefaultProducerTemplate].isStarted)
    stop
    assert(!context.asInstanceOf[TestCamelContext].isStarted)
    assert(!template.asInstanceOf[DefaultProducerTemplate].isStarted)
  }

  class TestCamelContext extends DefaultCamelContext
}
