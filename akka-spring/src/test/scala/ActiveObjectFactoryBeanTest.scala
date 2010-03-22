/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

/**
 * Test for ActiveObjectFactoryBean
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class ActiveObjectFactoryBeanTest extends Spec with ShouldMatchers {

  describe("A ActiveObjectFactoryBean") {
    val bean = new ActiveObjectFactoryBean
    it("should have java getters and setters for all properties") {
      bean.setTarget("java.lang.String")
      assert(bean.getTarget == "java.lang.String")
      bean.setTimeout(1000)
      assert(bean.getTimeout == 1000)
    }

    it("should create a remote active object when a host is set") {
      bean.setHost("some.host.com");
      assert(bean.isRemote)
    }

    it("should return the object type") {
      bean.setTarget("java.lang.String")
      assert(bean.getObjectType == classOf[String])
    }

    it("should create an active object") {
      // TODO:
    }
  }
}
