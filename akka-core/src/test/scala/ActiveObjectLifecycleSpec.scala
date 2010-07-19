package se.scalablesolutions.akka.actor

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Spec}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import se.scalablesolutions.akka.actor.ActiveObject._

import se.scalablesolutions.akka.config.{OneForOneStrategy, ActiveObjectConfigurator}
import se.scalablesolutions.akka.config.JavaConfig._

/**
 * @author Martin Krasser
 */
@RunWith(classOf[JUnitRunner])
class ActiveObjectLifecycleSpec extends Spec with ShouldMatchers with BeforeAndAfterAll {
  var conf1: ActiveObjectConfigurator = _
  var conf2: ActiveObjectConfigurator = _
  var conf3: ActiveObjectConfigurator = _
  var conf4: ActiveObjectConfigurator = _

  override protected def beforeAll() = {
    val strategy = new RestartStrategy(new AllForOne(), 3, 1000, Array(classOf[Exception]))
    val comp1 = new Component(classOf[SamplePojoAnnotated], new LifeCycle(new Permanent()), 1000)
    val comp2 = new Component(classOf[SamplePojoAnnotated], new LifeCycle(new Temporary()), 1000)
    val comp3 = new Component(classOf[SamplePojo], new LifeCycle(new Permanent(), new RestartCallbacks("pre", "post")), 1000)
    val comp4 = new Component(classOf[SamplePojo], new LifeCycle(new Temporary(), new ShutdownCallback("down")), 1000)
    conf1 = new ActiveObjectConfigurator().configure(strategy, Array(comp1)).supervise
    conf2 = new ActiveObjectConfigurator().configure(strategy, Array(comp2)).supervise
    conf3 = new ActiveObjectConfigurator().configure(strategy, Array(comp3)).supervise
    conf4 = new ActiveObjectConfigurator().configure(strategy, Array(comp4)).supervise
  }

  override protected def afterAll() = {
    conf1.stop
    conf2.stop
    conf3.stop
    conf4.stop
  }

  describe("ActiveObject lifecycle management") {
    it("should restart supervised, annotated active object on failure") {
      val obj = conf1.getInstance[SamplePojoAnnotated](classOf[SamplePojoAnnotated])
      val cdl = obj.newCountdownLatch(2)
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(obj._pre)
          assert(obj._post)
          assert(!obj._down)
          assert(AspectInitRegistry.initFor(obj) ne null)
        }
      }
    }

    it("should shutdown supervised, annotated active object on failure") {
      val obj = conf2.getInstance[SamplePojoAnnotated](classOf[SamplePojoAnnotated])
      val cdl = obj.newCountdownLatch(1)
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(!obj._pre)
          assert(!obj._post)
          assert(obj._down)
          assert(AspectInitRegistry.initFor(obj) eq null)
        }
      }
    }

    it("should restart supervised, non-annotated active object on failure") {
      val obj = conf3.getInstance[SamplePojo](classOf[SamplePojo])
      val cdl = obj.newCountdownLatch(2)
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(obj._pre)
          assert(obj._post)
          assert(!obj._down)
          assert(AspectInitRegistry.initFor(obj) ne null)
        }
      }
    }

    it("should shutdown supervised, non-annotated active object on failure") {
      val obj = conf4.getInstance[SamplePojo](classOf[SamplePojo])
      val cdl = obj.newCountdownLatch(1)
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(!obj._pre)
          assert(!obj._post)
          assert(obj._down)
          assert(AspectInitRegistry.initFor(obj) eq null)
        }
      }
    }

    it("should shutdown non-supervised, annotated active object on ActiveObject.stop") {
      val obj = ActiveObject.newInstance(classOf[SamplePojoAnnotated])
      assert(AspectInitRegistry.initFor(obj) ne null)
      assert("hello akka" === obj.greet("akka"))
      ActiveObject.stop(obj)
      assert(AspectInitRegistry.initFor(obj) eq null)
      assert(!obj._pre)
      assert(!obj._post)
      assert(obj._down)
      try {
        obj.greet("akka")
        fail("access to stopped active object")
      } catch {
        case e: Exception => { /* test passed */ }
      }
    }

    it("should shutdown non-supervised, annotated active object on ActorRegistry.shutdownAll") {
      val obj = ActiveObject.newInstance(classOf[SamplePojoAnnotated])
      assert(AspectInitRegistry.initFor(obj) ne null)
      assert("hello akka" === obj.greet("akka"))
      ActorRegistry.shutdownAll
      assert(AspectInitRegistry.initFor(obj) eq null)
      assert(!obj._pre)
      assert(!obj._post)
      assert(obj._down)
      try {
        obj.greet("akka")
        fail("access to stopped active object")
      } catch {
        case e: Exception => { /* test passed */ }
      }
    }

    it("should shutdown non-supervised, non-initialized active object on ActiveObject.stop") {
      val obj = ActiveObject.newInstance(classOf[SamplePojoAnnotated])
      ActiveObject.stop(obj)
      assert(!obj._pre)
      assert(!obj._post)
      assert(obj._down)
    }

    it("both preRestart and postRestart methods should be invoked when an actor is restarted") {
      val pojo = ActiveObject.newInstance(classOf[SimpleJavaPojo])
      val supervisor = ActiveObject.newInstance(classOf[SimpleJavaPojo])
      link(supervisor,pojo, new OneForOneStrategy(3, 2000),Array(classOf[Throwable]))
      pojo.throwException
      Thread.sleep(500)
      pojo.pre should be(true)
      pojo.post should be(true)
    }
  }
}