/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import akka.testkit.{ EventFilter, filterEvents, AkkaSpec }
import akka.util.Timeout
import akka.japi.{ Option ⇒ JOption }
import akka.testkit.DefaultTimeout
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.serialization.JavaSerializer
import akka.actor.TypedActor._
import java.util.concurrent.atomic.AtomicReference
import java.lang.IllegalStateException
import java.util.concurrent.{ TimeoutException, TimeUnit, CountDownLatch }
import akka.testkit.TimingTest

object TypedActorSpec {

  val config = """
    pooled-dispatcher {
      type = "akka.dispatch.BalancingDispatcherConfigurator"
      executor = "thread-pool-executor"
      thread-pool-executor {
        core-pool-size-min = 60
        core-pool-size-max = 60
        max-pool-size-min = 60
        max-pool-size-max = 60
      }
    }
    """

  class CyclicIterator[T](val items: immutable.Seq[T]) extends Iterator[T] {

    private[this] val current = new AtomicReference(items)

    def hasNext = items != Nil

    def next: T = {
      @tailrec
      def findNext: T = {
        val currentItems = current.get
        val newItems = currentItems match {
          case Nil ⇒ items
          case xs  ⇒ xs
        }

        if (current.compareAndSet(currentItems, newItems.tail)) newItems.head
        else findNext
      }

      findNext
    }

    override def exists(f: T ⇒ Boolean): Boolean = items exists f
  }

  trait Foo {
    def pigdog(): String

    @throws(classOf[TimeoutException])
    def self = TypedActor.self[Foo]

    def futurePigdog(): Future[String]

    def futurePigdog(delay: FiniteDuration): Future[String]

    def futurePigdog(delay: FiniteDuration, numbered: Int): Future[String]

    def futureComposePigdogFrom(foo: Foo): Future[String]

    def failingFuturePigdog(): Future[String] = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def failingOptionPigdog(): Option[String] = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def failingJOptionPigdog(): JOption[String] = throw new IllegalStateException("expected")

    def failingPigdog(): Unit = throw new IllegalStateException("expected")

    @throws(classOf[TimeoutException])
    def optionPigdog(): Option[String]

    @throws(classOf[TimeoutException])
    def optionPigdog(delay: FiniteDuration): Option[String]

    @throws(classOf[TimeoutException])
    def joptionPigdog(delay: FiniteDuration): JOption[String]

    def nullFuture(): Future[Any] = null

    def nullJOption(): JOption[Any] = null

    def nullOption(): Option[Any] = null

    def nullReturn(): Any = null

    def incr()

    @throws(classOf[TimeoutException])
    def read(): Int

    def testMethodCallSerialization(foo: Foo, s: String, i: Int): Unit = throw new IllegalStateException("expected")
  }

  class Bar extends Foo with Serializable {

    import TypedActor.dispatcher

    def pigdog = "Pigdog"

    def futurePigdog(): Future[String] = Promise.successful(pigdog).future

    def futurePigdog(delay: FiniteDuration): Future[String] = {
      Thread.sleep(delay.toMillis)
      futurePigdog
    }

    def futurePigdog(delay: FiniteDuration, numbered: Int): Future[String] = {
      Thread.sleep(delay.toMillis)
      Promise.successful(pigdog + numbered).future
    }

    def futureComposePigdogFrom(foo: Foo): Future[String] = {
      implicit val timeout = TypedActor(TypedActor.context.system).DefaultReturnTimeout
      foo.futurePigdog(500 millis).map(_.toUpperCase)
    }

    def optionPigdog(): Option[String] = Some(pigdog)

    def optionPigdog(delay: FiniteDuration): Option[String] = {
      Thread.sleep(delay.toMillis)
      Some(pigdog)
    }

    def joptionPigdog(delay: FiniteDuration): JOption[String] = {
      Thread.sleep(delay.toMillis)
      JOption.some(pigdog)
    }

    var internalNumber = 0

    def incr() {
      internalNumber += 1
    }

    def read() = internalNumber
  }

  trait Stackable1 {
    def stackable1: String = "foo"
  }

  trait Stackable2 {
    def stackable2: String = "bar"
  }

  trait Stacked extends Stackable1 with Stackable2 {
    def stacked: String = stackable1 + stackable2

    def notOverriddenStacked: String = stackable1 + stackable2
  }

  class StackedImpl extends Stacked {
    override def stacked: String = "FOOBAR" //Uppercase
  }

  trait LifeCycles {
    def crash(): Unit
  }

  class LifeCyclesImpl(val latch: CountDownLatch) extends PreStart with PostStop with PreRestart with PostRestart with LifeCycles with Receiver {

    private def ensureContextAvailable[T](f: ⇒ T): T = TypedActor.context match {
      case null ⇒ throw new IllegalStateException("TypedActor.context is null!")
      case some ⇒ f
    }

    override def crash(): Unit = throw new IllegalStateException("Crash!")

    override def preStart(): Unit = ensureContextAvailable(latch.countDown())

    override def postStop(): Unit = ensureContextAvailable(for (i ← 1 to 3) latch.countDown())

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = ensureContextAvailable(for (i ← 1 to 5) latch.countDown())

    override def postRestart(reason: Throwable): Unit = ensureContextAvailable(for (i ← 1 to 7) latch.countDown())

    override def onReceive(msg: Any, sender: ActorRef): Unit = {
      ensureContextAvailable(
        msg match {
          case "pigdog" ⇒ sender ! "dogpig"
        })
    }
  }

  trait F { def f(pow: Boolean): Int }
  class FI extends F { def f(pow: Boolean): Int = if (pow) throw new IllegalStateException("expected") else 1 }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TypedActorSpec extends AkkaSpec(TypedActorSpec.config)
  with BeforeAndAfterEach with BeforeAndAfterAll with DefaultTimeout {

  import TypedActorSpec._

  def newFooBar: Foo = newFooBar(timeout.duration)

  def newFooBar(d: FiniteDuration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)))

  def newFooBar(dispatcher: String, d: FiniteDuration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)).withDispatcher(dispatcher))

  def newStacked(): Stacked =
    TypedActor(system).typedActorOf(
      TypedProps[StackedImpl](classOf[Stacked], classOf[StackedImpl]).withTimeout(timeout))

  def mustStop(typedActor: AnyRef) = TypedActor(system).stop(typedActor) should be(true)

  "TypedActors" must {

    "be able to instantiate" in {
      val t = newFooBar
      TypedActor(system).isTypedActor(t) should be(true)
      mustStop(t)
    }

    "be able to stop" in {
      val t = newFooBar
      mustStop(t)
    }

    "not stop non-started ones" in {
      TypedActor(system).stop(null) should be(false)
    }

    "throw an IllegalStateExcpetion when TypedActor.self is called in the wrong scope" in {
      filterEvents(EventFilter[IllegalStateException]("Calling")) {
        (intercept[IllegalStateException] {
          TypedActor.self[Foo]
        }).getMessage should equal("Calling TypedActor.self outside of a TypedActor implementation method!")
      }
    }

    "have access to itself when executing a method call" in {
      val t = newFooBar
      t.self should be(t)
      mustStop(t)
    }

    "be able to call toString" in {
      val t = newFooBar
      t.toString should be(TypedActor(system).getActorRefFor(t).toString)
      mustStop(t)
    }

    "be able to call equals" in {
      val t = newFooBar
      t should equal(t)
      t should not equal (null)
      mustStop(t)
    }

    "be able to call hashCode" in {
      val t = newFooBar
      t.hashCode should be(TypedActor(system).getActorRefFor(t).hashCode)
      mustStop(t)
    }

    "be able to call user-defined void-methods" in {
      val t = newFooBar
      t.incr()
      t.read() should be(1)
      t.incr()
      t.read() should be(2)
      t.read() should be(2)
      mustStop(t)
    }

    "be able to call normally returning methods" in {
      val t = newFooBar
      t.pigdog() should be("Pigdog")
      mustStop(t)
    }

    "be able to call null returning methods" in {
      val t = newFooBar
      t.nullJOption() should equal(JOption.none)
      t.nullOption() should equal(None)
      t.nullReturn() should ===(null)
      Await.result(t.nullFuture(), timeout.duration) should ===(null)
    }

    "be able to call Future-returning methods non-blockingly" in {
      val t = newFooBar
      val f = t.futurePigdog(200 millis)
      f.isCompleted should be(false)
      Await.result(f, timeout.duration) should be("Pigdog")
      mustStop(t)
    }

    "be able to call multiple Future-returning methods non-blockingly" in within(timeout.duration) {
      val t = newFooBar
      val futures = for (i ← 1 to 20) yield (i, t.futurePigdog(20 millis, i))
      for ((i, f) ← futures) {
        Await.result(f, remaining) should be("Pigdog" + i)
      }
      mustStop(t)
    }

    "be able to call methods returning Java Options" taggedAs TimingTest in {
      val t = newFooBar(1 second)
      t.joptionPigdog(100 millis).get should be("Pigdog")
      t.joptionPigdog(2 seconds) should be(JOption.none[String])
      mustStop(t)
    }

    "be able to handle AskTimeoutException as None" taggedAs TimingTest in {
      val t = newFooBar(200 millis)
      t.joptionPigdog(600 millis) should be(JOption.none[String])
      mustStop(t)
    }

    "be able to call methods returning Scala Options" taggedAs TimingTest in {
      val t = newFooBar(1 second)
      t.optionPigdog(100 millis).get should be("Pigdog")
      t.optionPigdog(2 seconds) should be(None)
      mustStop(t)
    }

    "be able to compose futures without blocking" in within(timeout.duration) {
      val t, t2 = newFooBar(remaining)
      val f = t.futureComposePigdogFrom(t2)
      f.isCompleted should be(false)
      Await.result(f, remaining) should equal("PIGDOG")
      mustStop(t)
      mustStop(t2)
    }

    "be able to handle exceptions when calling methods" in {
      filterEvents(EventFilter[IllegalStateException]("expected")) {
        val boss = system.actorOf(Props(new Actor {
          override val supervisorStrategy = OneForOneStrategy() {
            case e: IllegalStateException if e.getMessage == "expected" ⇒ SupervisorStrategy.Resume
          }
          def receive = {
            case p: TypedProps[_] ⇒ context.sender() ! TypedActor(context).typedActorOf(p)
          }
        }))
        val t = Await.result((boss ? TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(2 seconds)).mapTo[Foo], timeout.duration)

        t.incr()
        t.failingPigdog()
        t.read() should be(1) //Make sure state is not reset after failure

        intercept[IllegalStateException] { Await.result(t.failingFuturePigdog, 2 seconds) }.getMessage should be("expected")
        t.read() should be(1) //Make sure state is not reset after failure

        (intercept[IllegalStateException] { t.failingJOptionPigdog }).getMessage should be("expected")
        t.read() should be(1) //Make sure state is not reset after failure

        (intercept[IllegalStateException] { t.failingOptionPigdog }).getMessage should be("expected")

        t.read() should be(1) //Make sure state is not reset after failure

        mustStop(t)
      }
    }

    "be restarted on failure" in {
      filterEvents(EventFilter[IllegalStateException]("expected")) {
        val t = newFooBar(Duration(2, "s"))
        intercept[IllegalStateException] { t.failingOptionPigdog() }.getMessage should equal("expected")
        t.optionPigdog() should equal(Some("Pigdog"))
        mustStop(t)

        val ta: F = TypedActor(system).typedActorOf(TypedProps[FI]())
        intercept[IllegalStateException] { ta.f(true) }.getMessage should equal("expected")
        ta.f(false) should equal(1)

        mustStop(ta)
      }
    }

    "be able to support stacked traits for the interface part" in {
      val t = newStacked()
      t.notOverriddenStacked should be("foobar")
      t.stacked should be("FOOBAR")
      mustStop(t)
    }

    "be able to support implementation only typed actors" in within(timeout.duration) {
      val t: Foo = TypedActor(system).typedActorOf(TypedProps[Bar]())
      val f = t.futurePigdog(200 millis)
      val f2 = t.futurePigdog(Duration.Zero)
      f2.isCompleted should be(false)
      f.isCompleted should be(false)
      Await.result(f, remaining) should equal(Await.result(f2, remaining))
      mustStop(t)
    }

    "be able to support implementation only typed actors with complex interfaces" in {
      val t: Stackable1 with Stackable2 = TypedActor(system).typedActorOf(TypedProps[StackedImpl]())
      t.stackable1 should be("foo")
      t.stackable2 should be("bar")
      mustStop(t)
    }

    "be able to use balancing dispatcher" in within(timeout.duration) {
      val thais = for (i ← 1 to 60) yield newFooBar("pooled-dispatcher", 6 seconds)
      val iterator = new CyclicIterator(thais)

      val results = for (i ← 1 to 120) yield (i, iterator.next.futurePigdog(200 millis, i))

      for ((i, r) ← results) Await.result(r, remaining) should be("Pigdog" + i)

      for (t ← thais) mustStop(t)
    }

    "be able to serialize and deserialize invocations" in {
      import java.io._
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("pigdog"), Array[AnyRef]())
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        mNew.method should be(m.method)
      }
    }

    "be able to serialize and deserialize invocations' parameters" in {
      import java.io._
      val someFoo: Foo = new Bar
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("testMethodCallSerialization", Array[Class[_]](classOf[Foo], classOf[String], classOf[Int]): _*), Array[AnyRef](someFoo, null, 1.asInstanceOf[AnyRef]))
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        mNew.method should be(m.method)
        mNew.parameters should have size 3
        mNew.parameters(0) should not be null
        mNew.parameters(0).getClass should equal(classOf[Bar])
        mNew.parameters(1) should be(null)
        mNew.parameters(2) should not be null
        mNew.parameters(2).asInstanceOf[Int] should equal(1)
      }
    }

    "be able to serialize and deserialize proxies" in {
      import java.io._
      JavaSerializer.currentSystem.withValue(system.asInstanceOf[ExtendedActorSystem]) {
        val t = newFooBar(Duration(2, "s"))

        t.optionPigdog() should equal(Some("Pigdog"))

        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(t)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val tNew = in.readObject().asInstanceOf[Foo]

        tNew should equal(t)

        tNew.optionPigdog() should equal(Some("Pigdog"))

        mustStop(t)
      }
    }

    "be able to override lifecycle callbacks" in {
      val latch = new CountDownLatch(16)
      val ta = TypedActor(system)
      val t: LifeCycles = ta.typedActorOf(TypedProps[LifeCyclesImpl](classOf[LifeCycles], new LifeCyclesImpl(latch)))
      EventFilter[IllegalStateException]("Crash!", occurrences = 1) intercept {
        t.crash()
      }

      //Sneak in a check for the Receiver override
      val ref = ta getActorRefFor t

      ref.tell("pigdog", testActor)

      expectMsg(timeout.duration, "dogpig")

      //Done with that now

      ta.poisonPill(t)
      latch.await(10, TimeUnit.SECONDS) should equal(true)
    }
  }
}
