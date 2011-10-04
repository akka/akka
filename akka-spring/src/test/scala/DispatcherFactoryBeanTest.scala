/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.dispatch.MessageDispatcher

@RunWith(classOf[JUnitRunner])
class DispatcherFactoryBeanTest extends Spec with ShouldMatchers {

  describe("A DispatcherFactoryBean") {
    val bean = new DispatcherFactoryBean
    it("should have java getters and setters for the dispatcher properties") {
      val props = new DispatcherProperties()
      bean.setProperties(props)
      assert(bean.getProperties == props)
    }

    it("should return the object type MessageDispatcher") {
      assert(bean.getObjectType == classOf[MessageDispatcher])
    }
  }
}
