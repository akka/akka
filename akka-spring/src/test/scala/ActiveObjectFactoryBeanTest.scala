/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.springframework.core.io.ResourceEditor
import org.springframework.context.support.ClassPathXmlApplicationContext;

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

    it("should create object that implements the given interface") {
      bean.setInterface("com.biz.IPojo");
      assert(bean.hasInterface)
    }

    it("should create an active object with dispatcher if dispatcher is set") {
      val props = new DispatcherProperties()
      props.dispatcherType = "executor-based-event-driven"
      bean.setDispatcher(props);
      assert(bean.hasDispatcher)
    }

    it("should return the object type") {
      bean.setTarget("java.lang.String")
      assert(bean.getObjectType == classOf[String])
    }

    it("should create a proxy of type ResourceEditor") {
      val bean = new ActiveObjectFactoryBean()
      // we must have a java class here
      bean.setTarget("org.springframework.core.io.ResourceEditor")
      val entries = new PropertyEntries()
      val entry = new PropertyEntry()
      entry.name = "source"
      entry.value = "sourceBeanIsAString"
      entries.add(entry)
      bean.setProperty(entries)
      assert(bean.getObjectType == classOf[ResourceEditor])

      // Check that we have injected the depencency correctly
      val target:ResourceEditor = bean.createInstance.asInstanceOf[ResourceEditor]
      assert(target.getSource === entry.value)
    }

    it("should create an application context and inject a string dependency") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target:ResourceEditor = ctx.getBean("bean").asInstanceOf[ResourceEditor]
      assert(target.getSource === "someString")
    }
  }
}
