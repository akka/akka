package akka.thaipedactor

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.{ BeforeAndAfterAll, WordSpec, BeforeAndAfterEach }
import akka.thaipedactor.ThaipedActor._
import akka.japi.{ Option ⇒ JOption }
import akka.util.Duration
import akka.dispatch.{ Dispatchers, Future, AlreadyCompletedFuture }
import akka.routing.CyclicIterator

object ThaipedActorSpec {
  trait Foo {
    def pigdog(): String

    def futurePigdog(): Future[String]
    def futurePigdog(delay: Long): Future[String]
    def futurePigdog(delay: Long, numbered: Int): Future[String]
    def futureComposePigdogFrom(foo: Foo): Future[String]

    def optionPigdog(): Option[String]
    def optionPigdog(delay: Long): Option[String]
    def joptionPigdog(delay: Long): JOption[String]

    def incr()
    def read(): Int
  }

  class Bar extends Foo {
    def pigdog = "Pigdog"

    def futurePigdog(): Future[String] = new AlreadyCompletedFuture(Right(pigdog))
    def futurePigdog(delay: Long): Future[String] = {
      Thread.sleep(delay)
      futurePigdog
    }

    def futurePigdog(delay: Long, numbered: Int): Future[String] = {
      Thread.sleep(delay)
      new AlreadyCompletedFuture(Right(pigdog + numbered))
    }

    def futureComposePigdogFrom(foo: Foo): Future[String] =
      foo.futurePigdog(500).map(_.toUpperCase)

    def optionPigdog(): Option[String] = Some(pigdog)

    def optionPigdog(delay: Long): Option[String] = {
      Thread.sleep(delay)
      Some(pigdog)
    }

    def joptionPigdog(delay: Long): JOption[String] = {
      Thread.sleep(delay)
      JOption.some(pigdog)
    }

    var internalNumber = 0

    def incr() {
      internalNumber += 1
    }

    def read() = internalNumber
  }
}

@RunWith(classOf[JUnitRunner])
class ThaipedActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  import akka.thaipedactor.ThaipedActorSpec._

  def newFooBar: Foo = newFooBar(Duration(2, "seconds"))

  def newFooBar(timeout: Duration): Foo =
    newFooBar(Configuration(timeout))

  def newFooBar(config: Configuration): Foo =
    thaipedActorOf(classOf[Foo],
      classOf[Bar],
      config,
      classOf[Foo].getClassLoader)

  def mustStop(foo: Foo) = stop(foo) must be(true)

  "ThaipedActors" must {

    "be able to instantiate" in {
      val t = newFooBar
      isThaipedActor(t) must be(true)
      mustStop(t)
    }

    "be able to stop" in {
      val t = newFooBar
      mustStop(t)
    }

    "not stop non-started ones" in {
      stop(null) must be(false)
    }

    "be able to call toString" in {
      val t = newFooBar
      t.toString must be(getActorFor(t).get.toString)
      mustStop(t)
    }

    "be able to call equals" in {
      val t = newFooBar
      t must equal(t)
      t must not equal (null)
      mustStop(t)
    }

    "be able to call hashCode" in {
      val t = newFooBar
      t.hashCode must be(getActorFor(t).get.hashCode)
      mustStop(t)
    }

    "be able to call user-defined void-methods" in {
      val t = newFooBar
      t.incr()
      t.read() must be(1)
      mustStop(t)
    }

    "be able to call Future-returning methods non-blockingly" in {
      val t = newFooBar
      val f = t.futurePigdog(200)
      f.isCompleted must be(false)
      f.get must be("Pigdog")
      mustStop(t)
    }

    "be able to call multiple Future-returning methods non-blockingly" in {
      val t = newFooBar
      val futures = for (i ← 1 to 20) yield (i, t.futurePigdog(20, i))
      for ((i, f) ← futures) {
        f.get must be("Pigdog" + i)
      }
      mustStop(t)
    }

    "be able to call methods returning Java Options" in {
      val t = newFooBar(Duration(500, "ms"))
      t.joptionPigdog(200).get must be("Pigdog")
      t.joptionPigdog(700) must be(JOption.none[String])
      mustStop(t)
    }

    "be able to call methods returning Scala Options" in {
      val t = newFooBar(Duration(500, "ms"))
      t.optionPigdog(200).get must be("Pigdog")
      t.optionPigdog(700) must be(None)
      mustStop(t)
    }

    "be able to compose futures without blocking" in {
      val t, t2 = newFooBar(Duration(2, "s"))
      val f = t.futureComposePigdogFrom(t2)
      f.isCompleted must be(false)
      f.get must equal("PIGDOG")
      mustStop(t)
      mustStop(t2)
    }

    "be able to use work-stealing dispatcher" in {
      val config = Configuration(
        Duration(6600, "ms"),
        Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")
          .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
          .setCorePoolSize(60)
          .setMaxPoolSize(60)
          .build)

      val thais = for (i ← 1 to 60) yield newFooBar(config)
      val iterator = new CyclicIterator(thais)

      val results = for (i ← 1 to 120) yield (i, iterator.next.futurePigdog(200L, i))

      for ((i, r) ← results) r.get must be("Pigdog" + i)

      for (t ← thais) mustStop(t)
    }
  }
}
