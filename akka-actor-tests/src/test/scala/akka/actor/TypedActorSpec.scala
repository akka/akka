package akka.actor

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import akka.util.Duration
import akka.util.Timeout
import akka.util.duration._
import akka.serialization.Serialization
import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec
import akka.testkit.{ EventFilter, filterEvents, AkkaSpec }
import akka.serialization.SerializationExtension
import akka.actor.TypedActor.{ PostRestart, PreRestart, PostStop, PreStart }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.japi.{ Creator, Option ⇒ JOption }
import akka.testkit.DefaultTimeout
import akka.dispatch.{ Await, Dispatchers, Future, Promise }
import akka.pattern.ask

object TypedActorSpec {

  val config = """
    pooled-dispatcher {
      type = BalancingDispatcher
      core-pool-size-min = 60
      core-pool-size-max = 60
      max-pool-size-min = 60
      max-pool-size-max = 60
    }
    """

  class CyclicIterator[T](val items: Seq[T]) extends Iterator[T] {

    private[this] val current: AtomicReference[Seq[T]] = new AtomicReference(items)

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

    def self = TypedActor.self[Foo]

    def futurePigdog(): Future[String]

    def futurePigdog(delay: Long): Future[String]

    def futurePigdog(delay: Long, numbered: Int): Future[String]

    def futureComposePigdogFrom(foo: Foo): Future[String]

    def failingFuturePigdog(): Future[String] = throw new IllegalStateException("expected")

    def failingOptionPigdog(): Option[String] = throw new IllegalStateException("expected")

    def failingJOptionPigdog(): JOption[String] = throw new IllegalStateException("expected")

    def failingPigdog(): Unit = throw new IllegalStateException("expected")

    def optionPigdog(): Option[String]

    def optionPigdog(delay: Long): Option[String]

    def joptionPigdog(delay: Long): JOption[String]

    def incr()

    def read(): Int

    def testMethodCallSerialization(foo: Foo, s: String, i: Int): Unit = throw new IllegalStateException("expected")
  }

  class Bar extends Foo with Serializable {

    import TypedActor.dispatcher

    def pigdog = "Pigdog"

    def futurePigdog(): Future[String] = Promise.successful(pigdog)

    def futurePigdog(delay: Long): Future[String] = {
      Thread.sleep(delay)
      futurePigdog
    }

    def futurePigdog(delay: Long, numbered: Int): Future[String] = {
      Thread.sleep(delay)
      Promise.successful(pigdog + numbered)
    }

    def futureComposePigdogFrom(foo: Foo): Future[String] = {
      implicit val timeout = TypedActor.context.system.settings.ActorTimeout
      foo.futurePigdog(500).map(_.toUpperCase)
    }

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

  class LifeCyclesImpl(val latch: CountDownLatch) extends PreStart with PostStop with PreRestart with PostRestart with LifeCycles {

    override def crash(): Unit = throw new IllegalStateException("Crash!")

    override def preStart(): Unit = latch.countDown()

    override def postStop(): Unit = for (i ← 1 to 3) latch.countDown()

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = for (i ← 1 to 5) latch.countDown()

    override def postRestart(reason: Throwable): Unit = for (i ← 1 to 7) latch.countDown()
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TypedActorSpec extends AkkaSpec(TypedActorSpec.config)
  with BeforeAndAfterEach with BeforeAndAfterAll with DefaultTimeout {

  import TypedActorSpec._

  def newFooBar: Foo = newFooBar(Duration(2, "s"))

  def newFooBar(d: Duration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)))

  def newFooBar(dispatcher: String, d: Duration): Foo =
    TypedActor(system).typedActorOf(TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(Timeout(d)).withDispatcher(dispatcher))

  def newStacked(): Stacked =
    TypedActor(system).typedActorOf(
      TypedProps[StackedImpl](classOf[Stacked], classOf[StackedImpl]).withTimeout(Timeout(2000)))

  def mustStop(typedActor: AnyRef) = TypedActor(system).stop(typedActor) must be(true)

  "TypedActors" must {

    "be able to instantiate" in {
      val t = newFooBar
      TypedActor(system).isTypedActor(t) must be(true)
      mustStop(t)
    }

    "be able to stop" in {
      val t = newFooBar
      mustStop(t)
    }

    "not stop non-started ones" in {
      TypedActor(system).stop(null) must be(false)
    }

    "throw an IllegalStateExcpetion when TypedActor.self is called in the wrong scope" in {
      filterEvents(EventFilter[IllegalStateException]("Calling")) {
        (intercept[IllegalStateException] {
          TypedActor.self[Foo]
        }).getMessage must equal("Calling TypedActor.self outside of a TypedActor implementation method!")
      }
    }

    "have access to itself when executing a method call" in {
      val t = newFooBar
      t.self must be(t)
      mustStop(t)
    }

    "be able to call toString" in {
      val t = newFooBar
      t.toString must be(TypedActor(system).getActorRefFor(t).toString)
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
      t.hashCode must be(TypedActor(system).getActorRefFor(t).hashCode)
      mustStop(t)
    }

    "be able to call user-defined void-methods" in {
      val t = newFooBar
      t.incr()
      t.read() must be(1)
      t.incr()
      t.read() must be(2)
      t.read() must be(2)
      mustStop(t)
    }

    "be able to call normally returning methods" in {
      val t = newFooBar
      t.pigdog() must be("Pigdog")
      mustStop(t)
    }

    "be able to call Future-returning methods non-blockingly" in {
      val t = newFooBar
      val f = t.futurePigdog(200)
      f.isCompleted must be(false)
      Await.result(f, timeout.duration) must be("Pigdog")
      mustStop(t)
    }

    "be able to call multiple Future-returning methods non-blockingly" in {
      val t = newFooBar
      val futures = for (i ← 1 to 20) yield (i, t.futurePigdog(20, i))
      for ((i, f) ← futures) {
        Await.result(f, timeout.duration) must be("Pigdog" + i)
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
      Await.result(f, timeout.duration) must equal("PIGDOG")
      mustStop(t)
      mustStop(t2)
    }

    "be able to handle exceptions when calling methods" in {
      filterEvents(EventFilter[IllegalStateException]("expected")) {
        val boss = system.actorOf(Props(new Actor {
          override val supervisorStrategy = OneForOneStrategy {
            case e: IllegalStateException if e.getMessage == "expected" ⇒ SupervisorStrategy.Resume
          }
          def receive = {
            case p: TypedProps[_] ⇒ context.sender ! TypedActor(context).typedActorOf(p)
          }
        }))
        val t = Await.result((boss ? TypedProps[Bar](classOf[Foo], classOf[Bar]).withTimeout(2 seconds)).mapTo[Foo], timeout.duration)

        t.incr()
        t.failingPigdog()
        t.read() must be(1) //Make sure state is not reset after failure

        intercept[IllegalStateException] { Await.result(t.failingFuturePigdog, 2 seconds) }.getMessage must be("expected")
        t.read() must be(1) //Make sure state is not reset after failure

        (intercept[IllegalStateException] { t.failingJOptionPigdog }).getMessage must be("expected")
        t.read() must be(1) //Make sure state is not reset after failure

        (intercept[IllegalStateException] { t.failingOptionPigdog }).getMessage must be("expected")

        t.read() must be(1) //Make sure state is not reset after failure

        mustStop(t)
      }
    }

    "be able to support stacked traits for the interface part" in {
      val t = newStacked()
      t.notOverriddenStacked must be("foobar")
      t.stacked must be("FOOBAR")
      mustStop(t)
    }

    "be able to support implementation only typed actors" in {
      val t: Foo = TypedActor(system).typedActorOf(TypedProps[Bar]())
      val f = t.futurePigdog(200)
      val f2 = t.futurePigdog(0)
      f2.isCompleted must be(false)
      f.isCompleted must be(false)
      Await.result(f, timeout.duration) must equal(Await.result(f2, timeout.duration))
      mustStop(t)
    }

    "be able to support implementation only typed actors with complex interfaces" in {
      val t: Stackable1 with Stackable2 = TypedActor(system).typedActorOf(TypedProps[StackedImpl]())
      t.stackable1 must be("foo")
      t.stackable2 must be("bar")
      mustStop(t)
    }

    "be able to use balancing dispatcher" in {
      val thais = for (i ← 1 to 60) yield newFooBar("pooled-dispatcher", 6 seconds)
      val iterator = new CyclicIterator(thais)

      val results = for (i ← 1 to 120) yield (i, iterator.next.futurePigdog(200L, i))

      for ((i, r) ← results) Await.result(r, timeout.duration) must be("Pigdog" + i)

      for (t ← thais) mustStop(t)
    }

    "be able to serialize and deserialize invocations" in {
      import java.io._
      Serialization.currentSystem.withValue(system.asInstanceOf[ActorSystemImpl]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("pigdog"), Array[AnyRef]())
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        mNew.method must be(m.method)
      }
    }

    "be able to serialize and deserialize invocations' parameters" in {
      import java.io._
      val someFoo: Foo = new Bar
      Serialization.currentSystem.withValue(system.asInstanceOf[ActorSystemImpl]) {
        val m = TypedActor.MethodCall(classOf[Foo].getDeclaredMethod("testMethodCallSerialization", Array[Class[_]](classOf[Foo], classOf[String], classOf[Int]): _*), Array[AnyRef](someFoo, null, 1.asInstanceOf[AnyRef]))
        val baos = new ByteArrayOutputStream(8192 * 4)
        val out = new ObjectOutputStream(baos)

        out.writeObject(m)
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

        val mNew = in.readObject().asInstanceOf[TypedActor.MethodCall]

        mNew.method must be(m.method)
        mNew.parameters must have size 3
        mNew.parameters(0) must not be null
        mNew.parameters(0).getClass must be === classOf[Bar]
        mNew.parameters(1) must be(null)
        mNew.parameters(2) must not be null
        mNew.parameters(2).asInstanceOf[Int] must be === 1
      }
    }

    "be able to override lifecycle callbacks" in {
      val latch = new CountDownLatch(16)
      val ta = TypedActor(system)
      val t: LifeCycles = ta.typedActorOf(TypedProps[LifeCyclesImpl](classOf[LifeCycles], new LifeCyclesImpl(latch)))
      EventFilter[IllegalStateException]("Crash!", occurrences = 1) intercept {
        t.crash()
      }
      ta.poisonPill(t)
      latch.await(10, TimeUnit.SECONDS) must be === true
    }
  }
}
