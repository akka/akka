/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import ScalaDom._

import org.w3c.dom.Element

/**
 * Test for ActiveObjectParser
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class ActiveObjectBeanDefinitionParserTest extends Spec with ShouldMatchers {
  private class Parser extends ActiveObjectParser

  describe("An ActiveObjectParser") {
    val parser = new Parser()
    it("should parse the active object configuration") {
      val xml = <akka:active-object id="active-object1"
                                    target="foo.bar.MyPojo"
                                    timeout="1000"
                                    transactional="true"
                                                                        scope="prototype">
                                                <property name="someProp" value="someValue" ref="someRef"/>
                                        </akka:active-object>

      val props = parser.parseActiveObject(dom(xml).getDocumentElement);
      assert(props != null)
      assert(props.timeout === 1000)
      assert(props.target === "foo.bar.MyPojo")
      assert(props.transactional)
      assert(props.scope === "prototype")
      assert(props.propertyEntries.entryList.size === 1)
    }

    it("should throw IllegalArgumentException on missing mandatory attributes") {
      val xml = <akka:active-object id="active-object1"
                                    timeout="1000"
                                    transactional="true"/>

      evaluating { parser.parseActiveObject(dom(xml).getDocumentElement) } should produce [IllegalArgumentException]
    }

    it("should parse ActiveObjects configuration with dispatcher") {
      val xml = <akka:active-object id="active-object-with-dispatcher" target="se.scalablesolutions.akka.spring.foo.MyPojo"
                  timeout="1000">
                  <akka:dispatcher type="thread-based" name="my-thread-based-dispatcher"/>
                </akka:active-object>
      val props = parser.parseActiveObject(dom(xml).getDocumentElement);
      assert(props != null)
      assert(props.dispatcher.dispatcherType == "thread-based")
}

    it("should parse remote ActiveObjects configuration") {
      val xml = <akka:active-object id="remote active-object" target="se.scalablesolutions.akka.spring.foo.MyPojo"
                  timeout="1000">
                  <akka:remote host="com.some.host" port="9999"/>
                </akka:active-object>
      val props = parser.parseActiveObject(dom(xml).getDocumentElement);
      assert(props != null)
      assert(props.host == "com.some.host")
      assert(props.port == 9999)
    }
  }
}
