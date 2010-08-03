/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import se.scalablesolutions.akka.actor.ActorRegistry;

import org.junit.runner.RunWith
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, Spec}
import org.scalatest.matchers.ShouldMatchers

/**
 * Test for TypedActorFactoryBean
 * @author michaelkober
 */
@RunWith(classOf[JUnitRunner])
class TypedActorFactoryBeanTest extends Spec with ShouldMatchers with BeforeAndAfterAll {

  override protected def afterAll = ActorRegistry.shutdownAll

  describe("A TypedActorFactoryBean") {
    val bean = new TypedActorFactoryBean
    it("should have java getters and setters for all properties") {
      bean.setImplementation("java.lang.String")
      assert(bean.getImplementation == "java.lang.String")
      bean.setTimeout(1000)
      assert(bean.getTimeout == 1000)
    }

    it("should create a remote typed actor when a host is set") {
      bean.setHost("some.host.com");
      assert(bean.isRemote)
    }

    it("should create object that implements the given interface") {
      bean.setInterface("com.biz.IPojo");
      assert(bean.hasInterface)
    }

    it("should create an typed actor with dispatcher if dispatcher is set") {
      val props = new DispatcherProperties()
      props.dispatcherType = "executor-based-event-driven"
      bean.setDispatcher(props);
      assert(bean.hasDispatcher)
    }

    it("should return the object type") {
      bean.setImplementation("java.lang.String")
      assert(bean.getObjectType == classOf[String])
    }

    it("should create a proxy of type PojoInf") {
      val bean = new TypedActorFactoryBean()
      bean.setInterface("se.scalablesolutions.akka.spring.PojoInf")
      bean.setImplementation("se.scalablesolutions.akka.spring.Pojo")
      bean.timeout = 1000
      val entries = new PropertyEntries()
      val entry = new PropertyEntry()
      entry.name = "stringFromVal"
      entry.value = "tests rock"
      entries.add(entry)
      bean.setProperty(entries)
      assert(classOf[PojoInf].isAssignableFrom(bean.getObjectType))

      // Check that we have injected the depencency correctly
      val target = bean.createInstance.asInstanceOf[PojoInf]
      assert(target.getStringFromVal === entry.value)
    }

    it("should create an application context and verify dependency injection") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val ta = ctx.getBean("typedActor").asInstanceOf[PojoInf];
      assert(ta.isInitInvoked)
      assert(ta.getStringFromVal == "akka rocks")
      assert(ta.getStringFromRef == "spring rocks")
      assert(ta.gotApplicationContext)
      ctx.close
    }

    it("should stop the created typed actor when scope is singleton and the context is closed") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target = ctx.getBean("bean-singleton").asInstanceOf[SampleBeanIntf]
      assert(!target.down)
      ctx.close
      assert(target.down)
    }

    it("should not stop the created typed actor when scope is prototype and the context is closed") {
      var ctx = new ClassPathXmlApplicationContext("appContext.xml");
      val target = ctx.getBean("bean-prototype").asInstanceOf[SampleBeanIntf]
      assert(!target.down)
      ctx.close
      assert(!target.down)
    }
  }
}
