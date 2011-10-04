/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import ScalaDom._

import org.w3c.dom.Element
import org.springframework.beans.factory.support.BeanDefinitionBuilder

/**
 * Test for SupervisionBeanDefinitionParser
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class SupervisionBeanDefinitionParserTest extends Spec with ShouldMatchers {
  private class Parser extends SupervisionBeanDefinitionParser

  describe("A SupervisionBeanDefinitionParser") {
    val parser = new Parser()
    val builder = BeanDefinitionBuilder.genericBeanDefinition("foo.bar.Foo")

    it("should be able to parse typed actor configuration") {
      val props = parser.parseActor(createTypedActorElement);
      assert(props ne null)
      assert(props.timeout == 1000)
      assert(props.target == "foo.bar.MyPojo")
    }

    it("should parse the supervisor restart strategy") {
      parser.parseSupervisor(createSupervisorElement, builder);
      val strategy = builder.getBeanDefinition.getPropertyValues.getPropertyValue("restartStrategy").getValue.asInstanceOf[FaultHandlingStrategy]
      assert(strategy ne null)
      assert(strategy.isInstanceOf[AllForOneStrategy])
      expect(3) { strategy.asInstanceOf[AllForOneStrategy].maxNrOfRetries.get }
      expect(1000) { strategy.asInstanceOf[AllForOneStrategy].withinTimeRange.get }
    }

    it("should parse the supervised typed actors") {
      parser.parseSupervisor(createSupervisorElement, builder);
      val supervised = builder.getBeanDefinition.getPropertyValues.getPropertyValue("supervised").getValue.asInstanceOf[List[ActorProperties]]
      assert(supervised ne null)
      expect(4) { supervised.length }
      val iterator = supervised.iterator
      val prop1 = iterator.next
      val prop2 = iterator.next
      val prop3 = iterator.next
      val prop4 = iterator.next
      expect("foo.bar.Foo") { prop1.target }
      expect("foo.bar.Bar") { prop2.target }
      expect("foo.bar.MyPojo") { prop3.target }
      expect("foo.bar.MyPojo") { prop4.target }
      expect("permanent") { prop1.lifecycle }
      expect("temporary") { prop4.lifecycle }
    }

    it("should throw IllegalArgumentException on missing mandatory attributes") {
      evaluating { parser.parseSupervisor(createSupervisorMissingAttribute, builder) } should produce[IllegalArgumentException]
    }

    it("should throw IllegalArgumentException on missing mandatory elements") {
      evaluating { parser.parseSupervisor(createSupervisorMissingElement, builder) } should produce[IllegalArgumentException]
    }
  }

  private def createTypedActorElement: Element = {
    val xml = <akka:typed-actor id="typed-actor1" implementation="foo.bar.MyPojo" timeout="1000"/>
    dom(xml).getDocumentElement
  }

  private def createSupervisorElement: Element = {
    val xml = <akka:supervision id="supervision1">
                <akka:restart-strategy failover="AllForOne" retries="3" timerange="1000">
                  <akka:trap-exits>
                    <akka:trap-exit>java.io.IOException</akka:trap-exit>
                    <akka:trap-exit>java.lang.NullPointerException</akka:trap-exit>
                  </akka:trap-exits>
                </akka:restart-strategy>
                <akka:typed-actors>
                  <akka:typed-actor implementation="foo.bar.Foo" lifecycle="permanent" timeout="1000"/>
                  <akka:typed-actor interface="foo.bar.IBar" implementation="foo.bar.Bar" lifecycle="permanent" timeout="1000"/>
                  <akka:typed-actor implementation="foo.bar.MyPojo" lifecycle="temporary" timeout="1000">
                    <akka:restart-callbacks pre="preRestart" post="postRestart"/>
                  </akka:typed-actor>
                  <akka:typed-actor implementation="foo.bar.MyPojo" lifecycle="temporary" timeout="1000">
                    <akka:shutdown-callback method="shutdown"/>
                  </akka:typed-actor>
                </akka:typed-actors>
              </akka:supervision>
    dom(xml).getDocumentElement
  }

  private def createSupervisorMissingAttribute: Element = {
    val xml = <akka:supervision id="supervision1">
                <akka:restart-strategy failover="AllForOne" retries="3">
                  <akka:trap-exits>
                    <akka:trap-exit>java.io.IOException</akka:trap-exit>
                  </akka:trap-exits>
                </akka:restart-strategy>
                <akka:typed-actors>
                  <akka:typed-actor implementation="foo.bar.Foo" lifecycle="permanent" timeout="1000"/>
                </akka:typed-actors>
              </akka:supervision>
    dom(xml).getDocumentElement
  }

  private def createSupervisorMissingElement: Element = {
    val xml = <akka:supervision id="supervision1">
                <akka:restart-strategy failover="AllForOne" retries="3" timerange="1000">
                </akka:restart-strategy>
                <akka:typed-actors>
                  <akka:typed-actor implementation="foo.bar.Foo" lifecycle="permanent" timeout="1000"/>
                  <akka:typed-actor interface="foo.bar.IBar" implementation="foo.bar.Bar" lifecycle="permanent" timeout="1000"/>
                </akka:typed-actors>
              </akka:supervision>
    dom(xml).getDocumentElement
  }
}

