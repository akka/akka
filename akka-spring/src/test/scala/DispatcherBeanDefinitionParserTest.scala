/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import ScalaDom._

/**
 * Test for DispatcherBeanDefinitionParser
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class DispatcherBeanDefinitionParserTest extends Spec with ShouldMatchers {
  describe("A DispatcherBeanDefinitionParser") {
    val parser = new DispatcherBeanDefinitionParser()

    it("should be able to parse the dispatcher configuration") {
      // executor-based-event-driven
      val xml = <akka:dispatcher id="dispatcher" type="executor-based-event-driven" name="myDispatcher"/>
      var props = parser.parseDispatcher(dom(xml).getDocumentElement);
      assert(props ne null)
      assert(props.dispatcherType === "executor-based-event-driven")
      assert(props.name === "myDispatcher")

      // executor-based-event-driven-work-stealing
      val xml2 = <akka:dispatcher id="dispatcher" type="executor-based-event-driven-work-stealing" name="myDispatcher"/>
      props = parser.parseDispatcher(dom(xml2).getDocumentElement);
      assert(props.dispatcherType === "executor-based-event-driven-work-stealing")
    }

    it("should be able to parse the thread pool configuration") {
      val xml = <akka:thread-pool queue="bounded-array-blocking-queue" capacity="100" fairness="true" max-pool-size="40" core-pool-size="6" keep-alive="2000" rejection-policy="caller-runs-policy"/>
      val props = parser.parseThreadPool(dom(xml).getDocumentElement);
      assert(props ne null)
      assert(props.queue == "bounded-array-blocking-queue")
      assert(props.capacity == 100)
      assert(props.fairness)
      assert(props.corePoolSize == 6)
      assert(props.maxPoolSize == 40)
      assert(props.keepAlive == 2000L)
      assert(props.rejectionPolicy == "caller-runs-policy")
    }

    it("should be able to parse the dispatcher with a thread pool configuration") {
      val xml = <akka:dispatcher id="dispatcher" type="executor-based-event-driven" name="myDispatcher">
                  <akka:thread-pool queue="linked-blocking-queue" capacity="50" max-pool-size="10" core-pool-size="2" keep-alive="1000"/>
                </akka:dispatcher>
      val props = parser.parseDispatcher(dom(xml).getDocumentElement);
      assert(props ne null)
      assert(props.dispatcherType == "executor-based-event-driven")
      assert(props.name == "myDispatcher")
      assert(props.threadPool.corePoolSize == 2)
      assert(props.threadPool.maxPoolSize == 10)
      assert(props.threadPool.keepAlive == 1000)
      assert(props.threadPool.queue == "linked-blocking-queue")
    }

    it("should throw IllegalArgumentException on not existing reference") {
      val xml = <akka:dispatcher ref="dispatcher"/>
      evaluating { parser.parseDispatcher(dom(xml).getDocumentElement) } should produce[IllegalArgumentException]
    }

    it("should throw IllegalArgumentException on missing mandatory attributes") {
      val xml = <akka:dispatcher id="dispatcher" name="myDispatcher"/>
      evaluating { parser.parseDispatcher(dom(xml).getDocumentElement) } should produce[IllegalArgumentException]
    }

    it("should throw IllegalArgumentException when configuring a thread based dispatcher without TypedActor or UntypedActor") {
      val xml = <akka:dispatcher id="dispatcher" type="thread-based" name="myDispatcher"/>
      evaluating { parser.parseDispatcher(dom(xml).getDocumentElement) } should produce[IllegalArgumentException]
    }
  }
}

