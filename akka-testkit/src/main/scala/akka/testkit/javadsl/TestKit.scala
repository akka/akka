/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.javadsl

import akka.actor._
import akka.japi.JavaPartialFunction
import akka.japi.Util
import akka.testkit.{ TestActor, TestProbe }
import akka.testkit._

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.{ Function ⇒ JFunction, Supplier }

import scala.concurrent.duration._
import scala.reflect._

/**
 * Java API: Test kit for testing actors. Inheriting from this class enables
 * reception of replies from actors, which are queued by an internal actor and
 * can be examined using the `expectMsg...` methods. Assertions and
 * bounds concerning timing are available in the form of `Within`
 * blocks.
 *
 * Beware of two points:
 *
 *  - the ActorSystem passed into the constructor needs to be shutdown,
 *    otherwise thread pools and memory will be leaked - this trait is not
 *    thread-safe (only one actor with one queue, one stack of `Within`
 *    blocks); take care not to run tests within a single test class instance in
 *    parallel.
 *
 *  - It should be noted that for CI servers and the like all maximum Durations
 *    are scaled using the `dilated` method, which uses the
 *    TestKitExtension.Settings.TestTimeFactor settable via akka.conf entry
 *    "akka.test.timefactor".
 *
 *
 */
class TestKit(system: ActorSystem) {

  /**
   * All the Java APIs are delegated to TestProbe
   */
  private val tp: TestProbe = new TestProbe(system)

  /**
   * ActorRef of the test actor. Access is provided to enable e.g. registration
   * as message target.
   */
  def getTestActor: ActorRef = tp.testActor

  /**
   * Shorthand to get the testActor.
   */
  def getRef: ActorRef = getTestActor

  def getSystem: ActorSystem = tp.system

  def duration(s: String): FiniteDuration = {
    Duration.apply(s) match {
      case fd: FiniteDuration ⇒ fd
      case _                  ⇒ throw new IllegalArgumentException("duration() is only for finite durations, use Duration.Inf() and friends")
    }
  }

  def dilated(d: FiniteDuration): FiniteDuration = d.dilated(getSystem)

  /**
   * Query queue status.
   */
  def msgAvailable: Boolean = tp.msgAvailable

  def getLastSender: ActorRef = tp.lastSender

  /**
   * Send message to an actor while using the probe's TestActor as the sender.
   * Replies will be available for inspection with all of TestKit's assertion
   * methods.
   */
  def send(actor: ActorRef, msg: AnyRef): Unit = actor.tell(msg, tp.ref)

  /**
   * Forward this message as if in the TestActor's receive method with self.forward.
   */
  def forward(actor: ActorRef): Unit = actor.tell(tp.lastMessage.msg, tp.lastMessage.sender)

  /**
   * Send message to the sender of the last dequeued message.
   */
  def reply(msg: AnyRef): Unit = tp.lastSender.tell(msg, tp.ref)

  /**
   * Have the testActor watch someone (i.e. `context.watch(...)`).
   */
  def watch(ref: ActorRef): ActorRef = tp.watch(ref)

  /**
   * Have the testActor stop watching someone (i.e. `context.unwatch(...)`).
   */
  def unwatch(ref: ActorRef): ActorRef = tp.unwatch(ref)

  /**
   * Ignore all messages in the test actor for which the given partial
   * function returns true.
   */
  def ignoreMsg(pf: JFunction[Any, Boolean]): Unit = {
    tp.ignoreMsg(new JavaPartialFunction[Any, Boolean] {
      @throws(classOf[Exception])
      override def apply(x: Any, isCheck: Boolean): Boolean = pf.apply(x)
    })
  }

  /**
   * Stop ignoring messages in the test actor.
   */
  def ignoreNoMsg(): Unit = tp.ignoreNoMsg()

  /**
   * Install an AutoPilot to drive the testActor: the AutoPilot will be run
   * for each received message and can be used to send or forward messages,
   * etc. Each invocation must return the AutoPilot for the next round.
   */
  def setAutoPilot(pilot: TestActor.AutoPilot): Unit = tp.setAutoPilot(pilot)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or throw an [[AssertionError]] if no `within` block surrounds this
   * call.
   */
  def remaining: FiniteDuration = tp.remaining

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def remainingOr(fd: FiniteDuration): FiniteDuration = tp.remainingOr(fd)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.test.single-expect-default").
   */
  def remainingOrDefault: FiniteDuration = tp.remainingOrDefault

  /**
   * Execute code block while bounding its execution time between `min` and
   * `max`. `within` blocks may be nested. All methods in this trait which
   * take maximum wait times are available in a version which implicitly uses
   * the remaining time governed by the innermost enclosing `within` block.
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.test.timefactor", while the min Duration is not.
   *
   * {{{
   *
   *  within(duration("50 millis"), () -> {
   *    test.tell("ping");
   *    return expectMsgClass(String.class);
   *  });
   *
   * }}}
   */
  def within[T](min: FiniteDuration, max: FiniteDuration, f: Supplier[T]): T = tp.within(min, max)(f.get)

  /**
   * Same as calling `within(0 seconds, max, f)`.
   */
  def within[T](max: FiniteDuration, f: Supplier[T]): T = tp.within(max)(f.get)

  def awaitCond(p: Supplier[Boolean]): Unit = tp.awaitCond(p.get)

  def awaitCond(max: Duration, p: Supplier[Boolean]): Unit = tp.awaitCond(p.get, max)

  def awaitCond(max: Duration, interval: Duration, p: Supplier[Boolean]): Unit =
    tp.awaitCond(p.get, max, interval)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(max: Duration, interval: Duration, message: String, p: Supplier[Boolean]): Unit =
    tp.awaitCond(p.get, max, interval, message)

  def awaitAssert(a: Supplier[Any]): Unit = tp.awaitAssert(a.get)

  def awaitAssert(max: Duration, a: Supplier[Any]): Unit = tp.awaitAssert(a.get, max)

  /**
   * Await until the given assert does not throw an exception or the timeout
   * expires, whichever comes first. If the timeout expires the last exception
   * is thrown.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert(max: Duration, interval: Duration, a: Supplier[Any]): Unit = tp.awaitAssert(a.get, max, interval)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsgEquals[T](obj: T): T = tp.expectMsg(obj)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsg[T](obj: T): T = tp.expectMsg(obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsg[T](max: FiniteDuration, obj: T): T = tp.expectMsg(max, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsg[T](max: FiniteDuration, obj: T, hint: String): T = tp.expectMsg(max, hint, obj)

  /**
   * Receive one message from the test actor and assert that the given
   * partial function accepts it. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * Use this variant to implement more complicated or conditional
   * processing.
   */
  def expectMsgPF[T](hint: String, f: JFunction[Any, T]): T = {
    tp.expectMsgPF(hint = hint)(new JavaPartialFunction[Any, T] {
      @throws(classOf[Exception])
      override def apply(x: Any, isCheck: Boolean): T = f.apply(x)
    })
  }

  /**
   * Receive one message from the test actor and assert that the given
   * partial function accepts it. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * Use this variant to implement more complicated or conditional
   * processing.
   */
  def expectMsgPF[T](max: Duration, hint: String, f: JFunction[Any, T]): T = {
    tp.expectMsgPF(max, hint)(new JavaPartialFunction[Any, T] {
      @throws(classOf[Exception])
      override def apply(x: Any, isCheck: Boolean): T = f.apply(x)
    })
  }

  /**
   * Same as `expectMsgClass(remainingOrDefault, c)`, but correctly treating the timeFactor.
   */
  def expectMsgClass[T](c: Class[T]): T = tp.expectMsgClass(c)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * the given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsgClass[T](max: FiniteDuration, c: Class[T]): T = tp.expectMsgClass(max, c)

  /**
   * Same as `expectMsgAnyOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAnyOf[T](objs: JIterable[T]): T = tp.expectMsgAnyOf(Util.immutableSeq(objs): _*)

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsgAnyOf[T](max: FiniteDuration, objs: JIterable[T]): T = tp.expectMsgAnyOf(max, Util.immutableSeq(objs): _*)

  /**
   * Same as `expectMsgAllOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAllOf[T](objs: JIterable[T]): Array[T] = tp.expectMsgAllOf(Util.immutableSeq(objs): _*).toArray(classTag[Any]).asInstanceOf[Array[T]]

  /**
   * Receive a number of messages from the test actor matching the given
   * number of objects and assert that for each given object one is received
   * which equals it and vice versa. This construct is useful when the order in
   * which the objects are received is not fixed. Wait time is bounded by the
   * given duration, with an AssertionFailure being thrown in case of timeout.
   */
  def expectMsgAllOf[T](max: FiniteDuration, objs: JIterable[T]): Array[T] = tp.expectMsgAllOf(max, Util.immutableSeq(objs): _*).toArray(classTag[Any]).asInstanceOf[Array[T]]

  /**
   * Same as `expectMsgAnyClassOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAnyClassOf[T](objs: JIterable[Class[_]]): T = tp.expectMsgAnyClassOf(Util.immutableSeq(objs): _*).asInstanceOf[T]

  /**
   * Receive one message from the test actor and assert that it conforms to
   * one of the given classes. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   */
  def expectMsgAnyClassOf[T](max: FiniteDuration, objs: JIterable[Class[_]]): T = tp.expectMsgAnyClassOf(max, Util.immutableSeq(objs): _*).asInstanceOf[T]

  /**
   * Same as `expectNoMsg(remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectNoMsg(): Unit = tp.expectNoMsg()

  /**
   * Assert that no message is received for the specified time.
   */
  def expectNoMsg(max: FiniteDuration): Unit = tp.expectNoMsg(max)

  /**
   * Receive one message from the test actor and assert that it is the Terminated message of the given ActorRef.
   * Wait time is bounded by the given duration, with an AssertionFailure being thrown in case of timeout.
   */
  def expectTerminated(max: Duration, target: ActorRef): Terminated = tp.expectTerminated(target, max)

  /**
   * Receive one message from the test actor and assert that it is the Terminated message of the given ActorRef.
   * Wait time is bounded by the given duration, with an AssertionFailure being thrown in case of timeout.
   */
  def expectTerminated(target: ActorRef): Terminated = tp.expectTerminated(target)

  /**
   * Same as `receiveN(n, remaining)` but correctly taking into account
   * Duration.timeFactor.
   */
  def receiveN(n: Int): Array[AnyRef] =
    tp.receiveN(n).toArray(classTag[AnyRef])

  /**
   * Receive N messages in a row before the given deadline.
   */
  def receiveN(n: Int, max: FiniteDuration): Array[AnyRef] =
    tp.receiveN(n, max).toArray(classTag[AnyRef])

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  def receiveOne(max: Duration): AnyRef = tp.receiveOne(max)

  /**
   * Receive a series of messages until one does not match the given partial
   * function or the idle timeout is met (disabled by default) or the overall
   * maximum duration is elapsed or expected messages count is reached.
   * Returns the sequence of messages.
   *
   * Note that it is not an error to hit the `max` duration in this case.
   *
   * One possible use of this method is for testing whether messages of
   * certain characteristics are generated at a certain rate:
   *
   */
  def receiveWhile[T](max: Duration, idle: Duration, messages: Int, f: JFunction[AnyRef, T]): Array[T] = {
    tp.receiveWhile(max, idle, messages)(new JavaPartialFunction[AnyRef, T] {
      @throws(classOf[Exception])
      override def apply(x: AnyRef, isCheck: Boolean): T = f.apply(x)
    }).asInstanceOf[AnyRef].asInstanceOf[Array[T]]
  }

  def receiveWhile[T](max: Duration, f: JFunction[AnyRef, T]): Array[T] = {
    tp.receiveWhile(max = max)(new JavaPartialFunction[AnyRef, T] {
      @throws(classOf[Exception])
      override def apply(x: AnyRef, isCheck: Boolean): T = f.apply(x)
    }).toArray(classTag[Any]).asInstanceOf[Array[T]]
  }

  /**
   * Spawns an actor as a child of this test actor, and returns the child's ActorRef.
   */
  def childActorOf(props: Props, name: String, supervisorStrategy: SupervisorStrategy) =
    tp.childActorOf(props, name, supervisorStrategy)

  /**
   * Spawns an actor as a child of this test actor with an auto-generated name, and returns the child's ActorRef.
   */
  def childActorOf(props: Props, supervisorStrategy: SupervisorStrategy) =
    tp.childActorOf(props, supervisorStrategy)

  /**
   * Spawns an actor as a child of this test actor with a stopping supervisor strategy, and returns the child's ActorRef.
   */
  def childActorOf(props: Props, name: String) = tp.childActorOf(props, name)

  /**
   * Spawns an actor as a child of this test actor with an auto-generated name and stopping supervisor strategy, returning the child's ActorRef.
   */
  def childActorOf(props: Props) = tp.childActorOf(props)

}

object TestKit {

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   *
   * If verifySystemShutdown is true, then an exception will be thrown on failure.
   */
  def shutdownActorSystem(
    actorSystem:          ActorSystem,
    duration:             Duration,
    verifySystemShutdown: Boolean): Unit = {

    akka.testkit.TestKitBase.shutdownActorSystem(actorSystem, duration, verifySystemShutdown)
  }

  def shutdownActorSystem(actorSystem: ActorSystem): Unit = {
    shutdownActorSystem(actorSystem, 10.seconds, false)
  }

  def shutdownActorSystem(actorSystem: ActorSystem, duration: Duration): Unit = {
    shutdownActorSystem(actorSystem, duration, false)
  }

  def shutdownActorSystem(actorSystem: ActorSystem, verifySystemShutdown: Boolean): Unit = {
    shutdownActorSystem(actorSystem, 10.seconds, verifySystemShutdown)
  }

}
