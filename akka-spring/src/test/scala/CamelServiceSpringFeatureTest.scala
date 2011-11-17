package akka.spring

import org.apache.camel.impl.{ SimpleRegistry, DefaultCamelContext }
import org.apache.camel.spring.SpringCamelContext
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, FeatureSpec }
import org.springframework.context.support.ClassPathXmlApplicationContext

import akka.camel.CamelContextManager
import akka.actor.{ TypedActor, Actor }

class CamelServiceSpringFeatureTest extends FeatureSpec with BeforeAndAfterEach with BeforeAndAfterAll {
  override protected def beforeAll = {
    Actor.registry.shutdownAll
  }

  override protected def afterEach = {
    Actor.registry.shutdownAll
  }

  feature("start CamelService from Spring system context") {
    import CamelContextManager._
    scenario("with a custom CamelContext and access a registered typed actor") {
      val appctx = new ClassPathXmlApplicationContext("/appContextCamelServiceCustom.xml")
      assert(mandatoryContext.isInstanceOf[SpringCamelContext])
      assert("hello sample" === mandatoryTemplate.requestBody("direct:test", "sample"))
      appctx.close
    }

    scenario("with a default CamelContext and access a registered typed actor") {
      val appctx = new ClassPathXmlApplicationContext("/appContextCamelServiceDefault.xml")
      // create a custom registry
      val registry = new SimpleRegistry
      registry.put("custom", TypedActor.newInstance(classOf[SampleBeanIntf], classOf[SampleBean]))
      // set custom registry in DefaultCamelContext
      assert(mandatoryContext.isInstanceOf[DefaultCamelContext])
      mandatoryContext.asInstanceOf[DefaultCamelContext].setRegistry(registry)
      // access registered typed actor
      assert("hello sample" === mandatoryTemplate.requestBody("typed-actor:custom?method=foo", "sample"))
      appctx.close
    }
  }
}
