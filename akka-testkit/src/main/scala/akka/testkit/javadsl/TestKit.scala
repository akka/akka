/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.javadsl

import java.util.function.{ Supplier, Function => JFunction }
import java.util.{ List => JList }

import akka.actor._
import akka.testkit.{ TestActor, TestDuration, TestProbe }
import akka.util.JavaDurationConverters._

import scala.annotation.varargs
import scala.collection.JavaConverters._
import scala.concurrent.duration._

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
      case fd: FiniteDuration => fd
      case _ =>
        throw new IllegalArgumentException("duration() is only for finite durations, use Duration.Inf() and friends")
    }
  }

  /**
   * Scale timeouts (durations) during tests with the configured
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def dilated(d: FiniteDuration): FiniteDuration = d.dilated(getSystem)

  /**
   * Java timeouts (durations) during tests with the configured
   */
  def dilated(duration: java.time.Duration): java.time.Duration = dilated(duration.asScala).asJava

  /**
   * Query queue status.
   */
  def msgAvailable: Boolean = tp.msgAvailable

  /**
   * Get the last sender of the TestProbe
   */
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
    tp.ignoreMsg(new CachingPartialFunction[Any, Boolean] {
      @throws(classOf[Exception])
      override def `match`(x: Any): Boolean = pf.apply(x)
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
  @Deprecated
  @deprecated("Use getRemaining which returns java.time.Duration instead.", since = "2.5.12")
  def remaining: FiniteDuration = tp.remaining

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or throw an [[AssertionError]] if no `within` block surrounds this
   * call.
   */
  def getRemaining: java.time.Duration = remaining.asJava

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  @Deprecated
  @deprecated("Use getRemainingOr which returns java.time.Duration instead.", since = "2.5.12")
  def remainingOr(fd: FiniteDuration): FiniteDuration = tp.remainingOr(fd)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def getRemainingOr(duration: java.time.Duration): java.time.Duration = remainingOr(duration.asScala).asJava

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.test.single-expect-default").
   */
  @Deprecated
  @deprecated("Use getRemainingOrDefault which returns java.time.Duration instead.", since = "2.5.12")
  def remainingOrDefault: FiniteDuration = tp.remainingOrDefault

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.test.single-expect-default").
   */
  def getRemainingOrDefault: java.time.Duration = remainingOrDefault.asJava

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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def within[T](min: FiniteDuration, max: FiniteDuration, f: Supplier[T]): T = tp.within(min, max)(f.get)

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
   *  within(java.time.Duration.ofMillis(50), () -> {
   *    test.tell("ping");
   *    return expectMsgClass(String.class);
   *  });
   *
   * }}}
   */
  def within[T](min: java.time.Duration, max: java.time.Duration, f: Supplier[T]): T =
    within(min.asScala, max.asScala, f)

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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def within[T](max: FiniteDuration, f: Supplier[T]): T = tp.within(max)(f.get)

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
   *  within(java.time.Duration.ofMillis(50), () -> {
   *    test.tell("ping");
   *    return expectMsgClass(String.class);
   *  });
   *
   * }}}
   */
  def within[T](max: java.time.Duration, f: Supplier[T]): T = within(max.asScala, f)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(p: Supplier[Boolean]): Unit = tp.awaitCond(p.get)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def awaitCond(max: Duration, p: Supplier[Boolean]): Unit = tp.awaitCond(p.get, max)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(max: java.time.Duration, p: Supplier[Boolean]): Unit = awaitCond(max.asScala, p)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def awaitCond(max: Duration, interval: Duration, p: Supplier[Boolean]): Unit =
    tp.awaitCond(p.get, max, interval)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(max: java.time.Duration, interval: java.time.Duration, p: Supplier[Boolean]): Unit =
    awaitCond(max.asScala, interval.asScala, p)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def awaitCond(max: Duration, interval: Duration, message: String, p: Supplier[Boolean]): Unit =
    tp.awaitCond(p.get, max, interval, message)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(max: java.time.Duration, interval: java.time.Duration, message: String, p: Supplier[Boolean]): Unit =
    awaitCond(max.asScala, interval.asScala, message, p)

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert[A](a: Supplier[A]): A = tp.awaitAssert(a.get)

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.13")
  def awaitAssert[A](max: Duration, a: Supplier[A]): A = tp.awaitAssert(a.get, max)

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert[A](max: java.time.Duration, a: Supplier[A]): A = tp.awaitAssert(a.get, max.asScala)

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception.
   * If the `max` timeout expires the last exception is thrown.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   *
   * @return an arbitrary value that would be returned from awaitAssert if successful, if not interested in such value you can return null.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.13")
  def awaitAssert[A](max: Duration, interval: Duration, a: Supplier[A]): A = tp.awaitAssert(a.get, max, interval)

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception.
   * If the `max` timeout expires the last exception is thrown.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   *
   * @return an arbitrary value that would be returned from awaitAssert if successful, if not interested in such value you can return null.
   */
  def awaitAssert[A](max: java.time.Duration, interval: java.time.Duration, a: Supplier[A]): A =
    tp.awaitAssert(a.get, max.asScala, interval.asScala)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsgEquals[T](obj: T): T = tp.expectMsg(obj)

  /**
   * Receive one message from the test actor and assert that it equals the given
   * object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsgEquals[T](max: FiniteDuration, obj: T): T = tp.expectMsg(max, obj)

  /**
   * Receive one message from the test actor and assert that it equals the given
   * object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgEquals[T](max: java.time.Duration, obj: T): T = expectMsgEquals(max.asScala, obj)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsg[T](obj: T): T = tp.expectMsg(obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsg[T](max: FiniteDuration, obj: T): T = tp.expectMsg(max, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsg[T](max: java.time.Duration, obj: T): T = expectMsg(max.asScala, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsg[T](max: FiniteDuration, obj: T, hint: String): T = tp.expectMsg(max, hint, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsg[T](max: java.time.Duration, obj: T, hint: String): T = expectMsg(max, obj, hint)

  /**
   * Receive one message from the test actor and assert that the given
   * partial function accepts it. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * Use this variant to implement more complicated or conditional
   * processing.
   */
  def expectMsgPF[T](hint: String, f: JFunction[Any, T]): T = {
    tp.expectMsgPF(hint = hint)(new CachingPartialFunction[Any, T] {
      @throws(classOf[Exception])
      override def `match`(x: Any): T = f.apply(x)
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
    tp.expectMsgPF(max, hint)(new CachingPartialFunction[Any, T] {
      @throws(classOf[Exception])
      override def `match`(x: Any): T = f.apply(x)
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
  def expectMsgPF[T](max: java.time.Duration, hint: String, f: JFunction[Any, T]): T = expectMsgPF(max.asScala, hint, f)

  /**
   * Same as `expectMsgClass(remainingOrDefault, c)`, but correctly treating the timeFactor.
   */
  def expectMsgClass[T](c: Class[T]): T = tp.expectMsgClass(c)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * the given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsgClass[T](max: FiniteDuration, c: Class[T]): T = tp.expectMsgClass(max, c)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * the given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsgClass[T](max: java.time.Duration, c: Class[T]): T = expectMsgClass(max.asScala, c)

  /**
   * Same as `expectMsgAnyOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */
  @varargs
  def expectMsgAnyOf[T](objs: T*): T = tp.expectMsgAnyOf(objs: _*)

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  @varargs
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsgAnyOf[T](max: FiniteDuration, objs: T*): T = tp.expectMsgAnyOf(max, objs: _*)

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   */
  def expectMsgAnyOf[T](max: java.time.Duration, objs: T*): T = expectMsgAnyOf(max.asScala, objs: _*)

  /**
   * Same as `expectMsgAllOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */
  @varargs
  def expectMsgAllOf[T](objs: T*): JList[T] = tp.expectMsgAllOf(objs: _*).asJava

  /**
   * Receive a number of messages from the test actor matching the given
   * number of objects and assert that for each given object one is received
   * which equals it and vice versa. This construct is useful when the order in
   * which the objects are received is not fixed. Wait time is bounded by the
   * given duration, with an AssertionFailure being thrown in case of timeout.
   */
  @varargs
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsgAllOf[T](max: FiniteDuration, objs: T*): JList[T] = tp.expectMsgAllOf(max, objs: _*).asJava

  /**
   * Receive a number of messages from the test actor matching the given
   * number of objects and assert that for each given object one is received
   * which equals it and vice versa. This construct is useful when the order in
   * which the objects are received is not fixed. Wait time is bounded by the
   * given duration, with an AssertionFailure being thrown in case of timeout.
   */
  def expectMsgAllOf[T](max: java.time.Duration, objs: T*): JList[T] = expectMsgAllOf(max.asScala, objs: _*)

  /**
   * Same as `expectMsgAnyClassOf(remainingOrDefault, obj...)`, but correctly treating the timeFactor.
   */

  @varargs
  def expectMsgAnyClassOf[T](objs: Class[_]*): T = tp.expectMsgAnyClassOf(objs: _*).asInstanceOf[T]

  /**
   * Receive one message from the test actor and assert that it conforms to
   * one of the given classes. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   */
  @varargs
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectMsgAnyClassOf[T](max: FiniteDuration, objs: Class[_]*): T =
    tp.expectMsgAnyClassOf(max, objs: _*).asInstanceOf[T]

  /**
   * Receive one message from the test actor and assert that it conforms to
   * one of the given classes. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   */
  @varargs
  def expectMsgAnyClassOf[T](max: java.time.Duration, objs: Class[_]*): T =
    expectMsgAnyClassOf(max.asScala, objs: _*)

  /**
   * Same as `expectNoMsg(remainingOrDefault)`, but correctly treating the timeFactor.
   */
  @deprecated(message = "Use expectNoMessage instead", since = "2.5.10")
  def expectNoMsg(): Unit = tp.expectNoMessage()

  /**
   * Same as `expectNoMessage(remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectNoMessage(): Unit = tp.expectNoMessage()

  /**
   * Assert that no message is received for the specified time.
   */
  @deprecated(message = "Use expectNoMessage instead", since = "2.5.10")
  def expectNoMsg(max: FiniteDuration): Unit = tp.expectNoMsg(max)

  /**
   * Assert that no message is received for the specified time.
   * Supplied value is not dilated.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def expectNoMessage(max: FiniteDuration): Unit = tp.expectNoMessage(max)

  /**
   * Assert that no message is received for the specified time.
   * Supplied value is not dilated.
   */
  def expectNoMessage(max: java.time.Duration): Unit = expectNoMessage(max.asScala)

  /**
   * Receive one message from the test actor and assert that it is the Terminated message of the given ActorRef.
   * Before calling this method, you have to `watch` the target actor ref.
   * Wait time is bounded by the given duration, with an AssertionFailure being thrown in case of timeout.
   *
   * @param max wait no more than max time, otherwise throw AssertionFailure
   * @param target the actor ref expected to be Terminated
   * @return the received Terminated message
   */
  def expectTerminated(max: Duration, target: ActorRef): Terminated = tp.expectTerminated(target, max)

  /**
   * Receive one message from the test actor and assert that it is the Terminated message of the given ActorRef.
   * Before calling this method, you have to `watch` the target actor ref.
   * Wait time is bounded by the given duration, with an AssertionFailure being thrown in case of timeout.
   *
   * @param max wait no more than max time, otherwise throw AssertionFailure
   * @param target the actor ref expected to be Terminated
   * @return the received Terminated message
   */
  def expectTerminated(max: java.time.Duration, target: ActorRef): Terminated = tp.expectTerminated(target, max.asScala)

  /**
   * Receive one message from the test actor and assert that it is the Terminated message of the given ActorRef.
   * Before calling this method, you have to `watch` the target actor ref.
   * Wait time is bounded by the given duration, with an AssertionFailure being thrown in case of timeout.
   *
   * @param target the actor ref expected to be Terminated
   * @return the received Terminated message
   */
  def expectTerminated(target: ActorRef): Terminated = tp.expectTerminated(target)

  /**
   * Hybrid of expectMsgPF and receiveWhile: receive messages while the
   * partial function matches and returns false. Use it to ignore certain
   * messages while waiting for a specific message.
   *
   * @return the last received message, i.e. the first one for which the
   *         partial function returned true
   */
  def fishForMessage(max: Duration, hint: String, f: JFunction[Any, Boolean]): Any = {
    tp.fishForMessage(max, hint)(new CachingPartialFunction[Any, Boolean] {
      @throws(classOf[Exception])
      override def `match`(x: Any): Boolean = f.apply(x)
    })
  }

  /**
   * Same as `fishForMessage`, but gets a different partial function and returns properly typed message.
   */
  def fishForSpecificMessage[T](max: Duration, hint: String, f: JFunction[Any, T]): T = {
    tp.fishForSpecificMessage(max, hint)(new CachingPartialFunction[Any, T] {
      @throws(classOf[Exception])
      override def `match`(x: Any): T = f.apply(x)
    })
  }

  /**
   * Same as `receiveN(n, remaining)` but correctly taking into account
   * Duration.timeFactor.
   */
  def receiveN(n: Int): JList[AnyRef] =
    tp.receiveN(n).asJava

  /**
   * Receive N messages in a row before the given deadline.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def receiveN(n: Int, max: FiniteDuration): JList[AnyRef] =
    tp.receiveN(n, max).asJava

  /**
   * Receive N messages in a row before the given deadline.
   */
  def receiveN(n: Int, max: java.time.Duration): JList[AnyRef] = receiveN(n, max.asScala)

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.13")
  def receiveOne(max: Duration): AnyRef = tp.receiveOne(max)

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  def receiveOne(max: java.time.Duration): AnyRef = tp.receiveOne(max.asScala)

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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.13")
  def receiveWhile[T](max: Duration, idle: Duration, messages: Int, f: JFunction[AnyRef, T]): JList[T] = {
    tp.receiveWhile(max, idle, messages)(new CachingPartialFunction[AnyRef, T] {
        @throws(classOf[Exception])
        override def `match`(x: AnyRef): T = f.apply(x)
      })
      .asJava
  }

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
  def receiveWhile[T](
      max: java.time.Duration,
      idle: java.time.Duration,
      messages: Int,
      f: JFunction[AnyRef, T]): JList[T] = {
    tp.receiveWhile(max.asScala, idle.asScala, messages)(new CachingPartialFunction[AnyRef, T] {
        @throws(classOf[Exception])
        override def `match`(x: AnyRef): T = f.apply(x)
      })
      .asJava
  }

  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.13")
  def receiveWhile[T](max: Duration, f: JFunction[AnyRef, T]): JList[T] = {
    tp.receiveWhile(max = max)(new CachingPartialFunction[AnyRef, T] {
        @throws(classOf[Exception])
        override def `match`(x: AnyRef): T = f.apply(x)
      })
      .asJava
  }

  def receiveWhile[T](max: java.time.Duration, f: JFunction[AnyRef, T]): JList[T] = {
    tp.receiveWhile(max = max.asScala)(new CachingPartialFunction[AnyRef, T] {
        @throws(classOf[Exception])
        override def `match`(x: AnyRef): T = f.apply(x)
      })
      .asJava
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
  def shutdownActorSystem(actorSystem: ActorSystem, duration: Duration, verifySystemShutdown: Boolean): Unit = {

    akka.testkit.TestKit.shutdownActorSystem(actorSystem, duration, verifySystemShutdown)
  }

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   */
  def shutdownActorSystem(actorSystem: ActorSystem): Unit = {
    shutdownActorSystem(actorSystem, 10.seconds, false)
  }

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   */
  def shutdownActorSystem(actorSystem: ActorSystem, duration: Duration): Unit = {
    shutdownActorSystem(actorSystem, duration, false)
  }

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   *
   * If verifySystemShutdown is true, then an exception will be thrown on failure.
   */
  def shutdownActorSystem(actorSystem: ActorSystem, verifySystemShutdown: Boolean): Unit = {
    shutdownActorSystem(actorSystem, 10.seconds, verifySystemShutdown)
  }

}

/**
 * INTERNAL API
 *
 * This is a specialized variant of PartialFunction which is <b><i>only
 * applicable if you know that `isDefinedAt(x)` is always called before
 * `apply(x)`—with the same `x` of course.</i></b>
 *
 * `match(x)` will be called for `isDefinedAt(x)` only, and its semantics
 * are the same as for [[akka.japi.JavaPartialFunction]] (apart from the
 * missing because unneeded boolean argument).
 *
 * This class is used internal to [[akka.testkit.javadsl.TestKit]] and
 * should not be extended by client code directly.
 */
private abstract class CachingPartialFunction[A, B] extends scala.runtime.AbstractPartialFunction[A, B] {
  import akka.japi.JavaPartialFunction._

  @throws(classOf[Exception])
  def `match`(x: A): B

  var cache: B = _
  final def isDefinedAt(x: A): Boolean =
    try {
      cache = `match`(x); true
    } catch { case NoMatch => cache = null.asInstanceOf[B]; false }
  final override def apply(x: A): B = cache
}
