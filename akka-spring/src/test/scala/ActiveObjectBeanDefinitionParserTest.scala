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
 * Test for ActiveObjectBeanDefinitionParser
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class ActiveObjectBeanDefinitionParserTest extends Spec with ShouldMatchers {
  private class Parser extends ActiveObjectBeanDefinitionParser
  
  describe("An ActiveObjectBeanDefinitionParser") {
    val parser = new Parser()
    it("should parse the active object configuration") {
      val props = parser.parseActiveObject(createTestElement);
      assert(props != null)
      assert(props.timeout == 1000)
      assert(props.target == "foo.bar.MyPojo")
      assert(props.transactional)
    }

    it("should throw IllegalArgumentException on missing mandatory attributes") {
      evaluating { parser.parseActiveObject(createTestElement2) } should produce [IllegalArgumentException]
    }
  }

  private def createTestElement : Element = {
    val xml = <akka:active-object id="active-object1"
                                        target="foo.bar.MyPojo"
                                        timeout="1000"
                        transactional="true"/>
    dom(xml).getDocumentElement
  }

  private def createTestElement2 : Element = {
    val xml = <akka:active-object id="active-object1"
                                        timeout="1000"
                        transactional="true"/>
    dom(xml).getDocumentElement
  }
}