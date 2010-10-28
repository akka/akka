package akka.actor

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, Spec}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import akka.actor.TypedActor._

import akka.config.Supervision._

import java.util.concurrent.CountDownLatch
import akka.config.TypedActorConfigurator

/**
 * @author Martin Krasser
 */
@RunWith(classOf[JUnitRunner])
class TypedActorLifecycleSpec extends Spec with ShouldMatchers with BeforeAndAfterAll {
  var conf1: TypedActorConfigurator = _
  var conf2: TypedActorConfigurator = _

  override protected def beforeAll() = {
    val strategy = AllForOneStrategy(classOf[Exception] :: Nil, 3, 1000)
    val comp3 = new SuperviseTypedActor(classOf[SamplePojo], classOf[SamplePojoImpl], permanent(), 1000)
    val comp4 = new SuperviseTypedActor(classOf[SamplePojo], classOf[SamplePojoImpl], temporary(), 1000)
    conf1 = new TypedActorConfigurator().configure(strategy, Array(comp3)).supervise
    conf2 = new TypedActorConfigurator().configure(strategy, Array(comp4)).supervise
  }

  override protected def afterAll() = {
    conf1.stop
    conf2.stop
  }

  describe("TypedActor lifecycle management") {
    it("should restart supervised, non-annotated typed actor on failure") {
      SamplePojoImpl.reset
      val obj = conf1.getInstance[SamplePojo](classOf[SamplePojo])
      val cdl = new CountDownLatch(2)
      SamplePojoImpl.latch = cdl
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(SamplePojoImpl._pre)
          assert(SamplePojoImpl._post)
          assert(!SamplePojoImpl._down)
//          assert(AspectInitRegistry.initFor(obj) ne null)
        }
      }
    }

    it("should shutdown supervised, non-annotated typed actor on failure") {
      SamplePojoImpl.reset
      val obj = conf2.getInstance[SamplePojo](classOf[SamplePojo])
      val cdl = new CountDownLatch(1)
      SamplePojoImpl.latch = cdl
      assert(AspectInitRegistry.initFor(obj) ne null)
      try {
        obj.fail
        fail("expected exception not thrown")
      } catch {
        case e: RuntimeException => {
          cdl.await
          assert(!SamplePojoImpl._pre)
          assert(!SamplePojoImpl._post)
          assert(SamplePojoImpl._down)
 //         assert(AspectInitRegistry.initFor(obj) eq null)
        }
      }
    }

    it("should shutdown non-supervised, non-initialized typed actor on TypedActor.stop") {
      SamplePojoImpl.reset
      val obj = TypedActor.newInstance(classOf[SamplePojo], classOf[SamplePojoImpl])
      TypedActor.stop(obj)
      assert(!SamplePojoImpl._pre)
      assert(!SamplePojoImpl._post)
      assert(SamplePojoImpl._down)
    }

    it("both preRestart and postRestart methods should be invoked when an actor is restarted") {
      SamplePojoImpl.reset
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val supervisor = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      link(supervisor, pojo, OneForOneStrategy(classOf[Throwable] :: Nil, 3, 2000))
      pojo.throwException
      Thread.sleep(500)
      SimpleJavaPojoImpl._pre should be(true)
      SimpleJavaPojoImpl._post should be(true)
    }

    /*
        it("should postStop non-supervised, annotated typed actor on TypedActor.stop") {
          val obj = TypedActor.newInstance(classOf[SamplePojoAnnotated])
          assert(AspectInitRegistry.initFor(obj) ne null)
          assert("hello akka" === obj.greet("akka"))
          TypedActor.stop(obj)
          assert(AspectInitRegistry.initFor(obj) eq null)
          assert(!obj.pre)
          assert(!obj.post)
          assert(obj.down)
          try {
            obj.greet("akka")
            fail("access to stopped typed actor")
          } catch {
            case e: Exception => {}
          }
        }

        it("should postStop non-supervised, annotated typed actor on ActorRegistry.shutdownAll") {
          val obj = TypedActor.newInstance(classOf[SamplePojoAnnotated])
          assert(AspectInitRegistry.initFor(obj) ne null)
          assert("hello akka" === obj.greet("akka"))
          ActorRegistry.shutdownAll
          assert(AspectInitRegistry.initFor(obj) eq null)
          assert(!obj.pre)
          assert(!obj.post)
          assert(obj.down)
          try {
            obj.greet("akka")
            fail("access to stopped typed actor")
          } catch {
            case e: Exception => { }
          }
        }

        it("should restart supervised, annotated typed actor on failure") {
          val obj = conf1.getInstance[SamplePojoAnnotated](classOf[SamplePojoAnnotated])
          val cdl = obj.newCountdownLatch(2)
          assert(AspectInitRegistry.initFor(obj) ne null)
          try {
            obj.fail
            fail("expected exception not thrown")
          } catch {
            case e: RuntimeException => {
              cdl.await
              assert(obj.pre)
              assert(obj.post)
              assert(!obj.down)
              assert(AspectInitRegistry.initFor(obj) ne null)
            }
          }
        }

        it("should postStop supervised, annotated typed actor on failure") {
          val obj = conf2.getInstance[SamplePojoAnnotated](classOf[SamplePojoAnnotated])
          val cdl = obj.newCountdownLatch(1)
          assert(AspectInitRegistry.initFor(obj) ne null)
          try {
            obj.fail
            fail("expected exception not thrown")
          } catch {
            case e: RuntimeException => {
              cdl.await
              assert(!obj.pre)
              assert(!obj.post)
              assert(obj.down)
              assert(AspectInitRegistry.initFor(obj) eq null)
            }
          }
        }
    */
  }
}
