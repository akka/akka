/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit;

import akka.actor.Terminated;
import scala.runtime.AbstractFunction0;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.event.Logging;
import akka.event.Logging.LogEvent;
import akka.japi.JavaPartialFunction;
import akka.japi.Util;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

/**
 * Java API: Test kit for testing actors. Inheriting from this class enables
 * reception of replies from actors, which are queued by an internal actor and
 * can be examined using the <code>expectMsg...</code> methods. Assertions and
 * bounds concerning timing are available in the form of <code>Within</code>
 * blocks.
 * <p>
 *
 * Beware of two points:
 * <p>
 *
 * <ul>
 * <li>the ActorSystem passed into the constructor needs to be shutdown,
 * otherwise thread pools and memory will be leaked - this trait is not
 * thread-safe (only one actor with one queue, one stack of <code>Within</code>
 * blocks); take care not to run tests within a single test class instance in
 * parallel.</li>
 *
 * <li>It should be noted that for CI servers and the like all maximum Durations
 * are scaled using the <code>dilated</code> method, which uses the
 * TestKitExtension.Settings.TestTimeFactor settable via akka.conf entry
 * "akka.test.timefactor".</li>
 * </ul>
 *
 * @deprecated Use {@link akka.testkit.javadsl.TestKit} instead, since 2.5.0
 *
 */
@Deprecated
public class JavaTestKit {
  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   * <p>
   *
   * If verifySystemShutdown is true, then an exception will be thrown on failure.
   */
  public static void shutdownActorSystem(ActorSystem actorSystem, Duration duration, Boolean verifySystemShutdown) {
      boolean vss = verifySystemShutdown != null ? verifySystemShutdown : false;
      Duration dur = duration != null ? duration : FiniteDuration.create(10, TimeUnit.SECONDS);
    TestKit.shutdownActorSystem(actorSystem, dur, vss);
  }

  public static void shutdownActorSystem(ActorSystem actorSystem) {
    shutdownActorSystem(actorSystem, null, null);
  }
  public static void shutdownActorSystem(ActorSystem actorSystem, Duration duration) {
    shutdownActorSystem(actorSystem, duration, null);
  }
  public static void shutdownActorSystem(ActorSystem actorSystem, Boolean verifySystemShutdown) {
    shutdownActorSystem(actorSystem, null, verifySystemShutdown);
  }

  private final TestProbe p;

  public JavaTestKit(ActorSystem system) {
    p = new TestProbe(system);
  }

  /**
   * ActorRef of the test actor. Access is provided to enable e.g. registration
   * as message target.
   */
  public ActorRef getTestActor() {
    return p.testActor();
  }

  /**
   * Shorthand to get the testActor.
   */
  public ActorRef getRef() {
    return getTestActor();
  }

  public ActorSystem getSystem() {
    return p.system();
  }

  static public FiniteDuration duration(String s) {
    final Duration ret = Duration.apply(s);
    if (ret instanceof FiniteDuration)
      return (FiniteDuration) ret;
    else
      throw new IllegalArgumentException("duration() is only for finite durations, use Duration.Inf() and friends");
  }

  public Duration dilated(Duration d) {
    return d.mul(TestKitExtension.get(getSystem()).TestTimeFactor());
  }

  /**
   * Query queue status.
   */
  public boolean msgAvailable() {
    return p.msgAvailable();
  }

  public ActorRef getLastSender() {
    return p.lastMessage().sender();
  }

  public void send(ActorRef actor, Object msg) {
    actor.tell(msg, p.ref());
  }

  public void forward(ActorRef actor) {
    actor.tell(p.lastMessage().msg(), p.lastMessage().sender());
  }

  public void reply(Object msg) {
    p.lastMessage().sender().tell(msg, p.ref());
  }

  public FiniteDuration getRemainingTime() {
    return p.remaining();
  }

  public FiniteDuration getRemainingTimeOr(FiniteDuration def) {
    return p.remainingOr(def);
  }

  /**
   * Have the testActor watch someone (i.e.
   * <code>getContext().getWatch(...)</code> ).
   */
  public ActorRef watch(ActorRef ref) {
    return p.watch(ref);
  }

  /**
   * Have the testActor stop watching someone (i.e.
   * <code>getContext.unwatch(...)</code>).
   */
  public ActorRef unwatch(ActorRef ref) {
    return p.unwatch(ref);
  }

  /**
   * Ignore all messages in the test actor for which the given function returns
   * true.
   */
  public abstract class IgnoreMsg {
    abstract protected boolean ignore(Object msg);

    public IgnoreMsg() {
      p.ignoreMsg(new JavaPartialFunction<Object, Object>() {
        public Boolean apply(Object in, boolean isCheck) {
          return ignore(in);
        }
      });
    }
  }

  /**
   * Stop ignoring messages in the test actor.
   */
  public void ignoreNoMsg() {
    p.ignoreNoMsg();
  }

  /**
   * Install an AutoPilot to drive the testActor: the AutoPilot will be run for
   * each received message and can be used to send or forward messages, etc.
   * Each invocation must return the AutoPilot for the next round.
   */
  public void setAutoPilot(TestActor.AutoPilot pilot) {
    p.setAutoPilot(pilot);
  }

  /**
   * Obtain time remaining for execution of the innermost enclosing
   * <code>Within</code> block or missing that it returns the properly dilated
   * default for this case from settings (key
   * "akka.test.single-expect-default").
   */
  public FiniteDuration remaining() {
    return p.remaining();
  }

  /**
   * Obtain time remaining for execution of the innermost enclosing
   * <code>Within</code> block or missing that it returns the given duration.
   */
  public FiniteDuration remainingOr(FiniteDuration duration) {
    return p.remainingOr(duration);
  }

  /**
   * Obtain time remaining for execution of the innermost enclosing
   * <code>Within</code> block or missing that it returns the properly dilated
   * default for this case from settings (key
   * "akka.test.single-expect-default").
   */
  public FiniteDuration remainingOrDefault() {
    return p.remainingOrDefault();
  }

  /**
   * Execute code block while bounding its execution time between
   * <code>min</code> and <code>max</code>. <code>Within</code> blocks may be
   * nested. All methods in this trait which take maximum wait times are
   * available in a version which implicitly uses the remaining time governed by
   * the innermost enclosing <code>Within</code> block.
   * <p>
   *
   * Note that the timeout is scaled using <code>dilated</code>, which uses the
   * configuration entry "akka.test.timefactor", while the min Duration is not.
   * <p>
   *
   * <pre>
   * <code>
   * // the run() method needs to finish within 3 seconds
   * new Within(duration("3 seconds")) {
   *   protected void run() {
   *     // ...
   *   }
   * }
   * </code>
   * </pre>
   */
  public abstract class Within {
    protected abstract void run();

    public Within(FiniteDuration max) {
      p.within(max, new AbstractFunction0<Object>() {
        @Override
        public Object apply() {
          run();
          return null;
        }
      });
    }

    public Within(FiniteDuration min, FiniteDuration max) {
      p.within(min, max, new AbstractFunction0<Object>() {
        @Override
        public Object apply() {
          run();
          return null;
        }
      });
    }
  }

  /**
   * Await until the given condition evaluates to <code>true</code> or the
   * timeout expires, whichever comes first.
   * <p>
   *
   * If no timeout is given, take it from the innermost enclosing
   * <code>Within</code> block.
   * <p>
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.test.timefactor".
   */
  public abstract class AwaitCond {
    protected abstract boolean cond();

    public AwaitCond() {
      this(Duration.Undefined(), p.awaitCond$default$3());
    }

    public AwaitCond(Duration max) {
      this(max, p.awaitCond$default$3());
    }

    public AwaitCond(Duration max, Duration interval) {
      p.awaitCond(new AbstractFunction0<Object>() {
        @Override
        public Object apply() {
          return cond();
        }
      }, max, interval, p.awaitCond$default$4());
    }

    public AwaitCond(Duration max, Duration interval, String message) {
      p.awaitCond(new AbstractFunction0<Object>() {
        @Override
        public Object apply() {
          return cond();
        }
      }, max, interval, message);
    }
  }

  /**
   * Await until the given assert does not throw an exception or the timeout
   * expires, whichever comes first. If the timeout expires the last exception
   * is thrown.
   * <p>
   *
   * If no timeout is given, take it from the innermost enclosing
   * <code>Within</code> block.
   * <p>
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.test.timefactor".
   */
  public abstract class AwaitAssert {
    protected abstract void check();

    public AwaitAssert() {
      this(Duration.Undefined(), p.awaitAssert$default$3());
    }

    public AwaitAssert(Duration max) {
      this(max, p.awaitAssert$default$3());
    }

    public AwaitAssert(Duration max, Duration interval) {
      p.awaitAssert(new AbstractFunction0<Object>() {
        @Override
        public Object apply() {
          check();
          return null;
        }
      }, max, interval);
    }
  }

  /**
   * Receive one message from the test actor and assert that the given matching
   * function accepts it. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   * <p>
   * The received object as transformed by the matching function can be
   * retrieved with the <code>get</code> method.
   *
   * Use this variant to implement more complicated or conditional processing.
   * <p>
   *
   * <pre>
   * <code>
   * final String out = new ExpectMsg&lt;String&gt;("match hint") {
   *   protected String match(Object in) {
   *     if (in instanceof Integer)
   *       return "match";
   *     else
   *       throw noMatch();
   *   }
   * }.get(); // this extracts the received message
   * </code>
   * </pre>
   */
  public abstract class ExpectMsg<T> {
    private final T result;

    public ExpectMsg(String hint) {
      this(Duration.Undefined(), hint);
    }

    public ExpectMsg(Duration max, String hint) {
      final Object received = p.receiveOne(max);
      try {
        result = match(received);
      } catch (JavaPartialFunction.NoMatchException ex) {
        throw new AssertionError("while expecting '" + hint + "' received unexpected: " + received);
      }
    }

    abstract protected T match(Object msg);

    protected RuntimeException noMatch() {
      throw JavaPartialFunction.noMatch();
    }

    public T get() {
      return result;
    }
  }

  /**
   * Same as <code>expectMsgEquals(remainingOrDefault(), obj)</code>, but correctly
   * treating the timeFactor.
   */
  public <T> T expectMsgEquals(T msg) {
    return p.expectMsg(msg);
  }

  /**
   * Receive one message from the test actor and assert that it equals the given
   * object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  public <T> T expectMsgEquals(FiniteDuration max, T msg) {
    return p.expectMsg(max, msg);
  }

  /**
   * Same as <code>expectMsgClass(remainingOrDefault(), clazz)</code>, but correctly
   * treating the timeFactor.
   */
  public <T> T expectMsgClass(Class<T> clazz) {
    return p.expectMsgClass(clazz);
  }

  /**
   * Receive one message from the test actor and assert that it conforms to the
   * given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  public <T> T expectMsgClass(FiniteDuration max, Class<T> clazz) {
    return p.expectMsgClass(max, clazz);
  }

  /**
   * Same as <code>expectMsgAnyOf(remainingOrDefault(), obj...)</code>, but correctly
   * treating the timeFactor.
   */
  public Object expectMsgAnyOf(Object... msgs) {
    return p.expectMsgAnyOf(Util.immutableSeq(msgs));
  }

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  public Object expectMsgAnyOf(FiniteDuration max, Object... msgs) {
    return p.expectMsgAnyOf(max, Util.immutableSeq(msgs));
  }

  /**
   * Same as <code>expectMsgAllOf(remainingOrDefault(), obj...)</code>, but correctly
   * treating the timeFactor.
   */
  public Object[] expectMsgAllOf(Object... msgs) {
    return (Object[]) p.expectMsgAllOf(Util.immutableSeq(msgs)).toArray(Util.classTag(Object.class));
  }

  /**
   * Receive a number of messages from the test actor matching the given number
   * of objects and assert that for each given object one is received which
   * equals it and vice versa. This construct is useful when the order in which
   * the objects are received is not fixed. Wait time is bounded by the given
   * duration, with an AssertionFailure being thrown in case of timeout.
   */
  public Object[] expectMsgAllOf(FiniteDuration max, Object... msgs) {
    return (Object[]) p.expectMsgAllOf(max, Util.immutableSeq(msgs)).toArray(Util.classTag(Object.class));
  }

  /**
   * Same as <code>expectMsgAnyClassOf(remainingOrDefault(), obj...)</code>, but
   * correctly treating the timeFactor.
   */
  @SuppressWarnings("unchecked")
  public <T> T expectMsgAnyClassOf(Class<? extends T>... classes) {
    final Object result = p.expectMsgAnyClassOf(Util.immutableSeq(classes));
    return (T) result;
  }

  /**
   * Receive one message from the test actor and assert that it conforms to one
   * of the given classes. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  public Object expectMsgAnyClassOf(FiniteDuration max, Class<?>... classes) {
    return p.expectMsgAnyClassOf(max, Util.immutableSeq(classes));
  }

  /**
   * Same as <code>expectNoMsg(remainingOrDefault())</code>, but correctly treating the
   * timeFactor.
   */
  public void expectNoMsg() {
    p.expectNoMsg();
  }

  /**
   * Assert that no message is received for the specified time.
   */
  public void expectNoMsg(FiniteDuration max) {
    p.expectNoMsg(max);
  }


  /**
   * Assert that the given ActorRef is Terminated within the specified time.
   * Don't forget to 'watch' it first!
   */
  public Terminated expectTerminated(Duration max, ActorRef target) {
      return p.expectTerminated(target, max);
  }

  /**
   * Same as <code>expectTerminated(remainingOrDefault(), target)</code>,
   * but correctly treating the timeFactor.
   * Don't forget to 'watch' it first!
   */
  public Terminated expectTerminated(ActorRef target) {
      return expectTerminated(Duration.Undefined(), target);
  }

  /**
   * Same as <code>receiveN(n, remaining())</code>, but correctly treating the
   * timeFactor.
   */

  public Object[] receiveN(int n) {
    return (Object[]) p.receiveN(n).toArray(Util.classTag(Object.class));
  }

  /**
   * Receive N messages in a row before the given deadline.
   */
  public Object[] receiveN(int n, FiniteDuration max) {
    return (Object[]) p.receiveN(n, max).toArray(Util.classTag(Object.class));
  }

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   * <p>
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  public Object receiveOne(Duration max) {
    return p.receiveOne(max);
  }

  /**
   * Receive a series of messages until one does not match the given
   * <code>match</code> function or the idle timeout is met (disabled by
   * default) or the overall maximum duration is elapsed. Returns the sequence
   * of messages.
   * <p>
   *
   * Note that it is not an error to hit the <code>max</code> duration in this
   * case.
   * <p>
   *
   * One possible use of this method is for testing whether messages of certain
   * characteristics are generated at a certain rate.
   */
  public abstract class ReceiveWhile<T> {
    abstract protected T match(Object msg) throws Exception;

    private Object results;

    public ReceiveWhile(Class<T> clazz) {
      this(clazz, Duration.Undefined());
    }

    public ReceiveWhile(Class<T> clazz, Duration max) {
      this(clazz, max, Duration.Inf(), Integer.MAX_VALUE);
    }

    public ReceiveWhile(Class<T> clazz, Duration max, int messages) {
      this(clazz, max, Duration.Inf(), messages);
    }

    @SuppressWarnings("all")
    public ReceiveWhile(Class<T> clazz, Duration max, Duration idle, int messages) {
      results = p.receiveWhile(max, idle, messages, new CachingPartialFunction<Object, T>() {
        public T match(Object msg) throws Exception {
          return ReceiveWhile.this.match(msg);
        }
      }).toArray(Util.classTag(clazz));
    }

    protected RuntimeException noMatch() {
      throw JavaPartialFunction.noMatch();
    }

    @SuppressWarnings("unchecked")
    public T[] get() {
      return (T[]) results;
    }
  }

  /**
   * Facilities for selectively filtering out expected events from logging so
   * that you can keep your test run’s console output clean and do not miss real
   * error messages.
   * <p>
   *
   * If the <code>occurrences</code> is set to <code>Integer.MAX_VALUE</code>,
   * no tracking is done.
   */
  public abstract class EventFilter<T> {
    abstract protected T run();

    private final Class<? extends Logging.LogEvent> clazz;

    private String source = null;
    private String message = null;
    private boolean pattern = false;
    private boolean complete = false;
    private int occurrences = Integer.MAX_VALUE;
    private Class<? extends Throwable> exceptionType = null;

    @SuppressWarnings("unchecked")
    public EventFilter(Class<?> clazz) {
      if (Throwable.class.isAssignableFrom(clazz)) {
        this.clazz = Logging.Error.class;
        exceptionType = (Class<? extends Throwable>) clazz;
      } else if (Logging.LogEvent.class.isAssignableFrom(clazz)) {
        this.clazz = (Class<? extends LogEvent>) clazz;
      } else
        throw new IllegalArgumentException("supplied class must either be LogEvent or Throwable");

    }

    public T exec() {
      akka.testkit.EventFilter filter;
      if (clazz == Logging.Error.class) {
        if (exceptionType == null)
          exceptionType = Logging.noCause().getClass();
        filter = new ErrorFilter(exceptionType, source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Warning.class) {
        filter = new WarningFilter(source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Info.class) {
        filter = new InfoFilter(source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Debug.class) {
        filter = new DebugFilter(source, message, pattern, complete, occurrences);
      } else
        throw new IllegalArgumentException("unknown LogLevel " + clazz);
      return filter.intercept(new AbstractFunction0<T>() {
        @Override
        public T apply() {
          return run();
        }
      }, p.system());
    }

    public EventFilter<T> message(String msg) {
      message = msg;
      pattern = false;
      complete = true;
      return this;
    }

    public EventFilter<T> startsWith(String msg) {
      message = msg;
      pattern = false;
      complete = false;
      return this;
    }

    public EventFilter<T> matches(String regex) {
      message = regex;
      pattern = true;
      return this;
    }

    public EventFilter<T> from(String source) {
      this.source = source;
      return this;
    }

    public EventFilter<T> occurrences(int number) {
      occurrences = number;
      return this;
    }
  }

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   * <p>
   *
   * If verifySystemShutdown is true, then an exception will be thrown on failure.
   */
  public void shutdown(ActorSystem actorSystem, Duration duration, Boolean verifySystemShutdown) {
    boolean vss = verifySystemShutdown != null ? verifySystemShutdown : false;
    Duration dur = duration != null ? duration :
            dilated(FiniteDuration.create(5, TimeUnit.SECONDS)).min(FiniteDuration.create(10, TimeUnit.SECONDS));
    JavaTestKit.shutdownActorSystem(actorSystem, dur, vss);
  }

  public void shutdown(ActorSystem actorSystem) {
      shutdown(actorSystem, null, null);
  }
  public void shutdown(ActorSystem actorSystem, Duration duration) {
      shutdown(actorSystem, duration, null);
  }
  public void shutdown(ActorSystem actorSystem, Boolean verifySystemShutdown) {
      shutdown(actorSystem, null, verifySystemShutdown);
  }

  /**
   * Spawns an actor as a child of this test actor, and returns the child's ActorRef.
   * @param props Props to create the child actor
   * @param name Actor name for the child actor
   * @param supervisorStrategy Strategy should decide what to do with failures in the actor.
   */
  public ActorRef childActorOf(Props props, String name, SupervisorStrategy supervisorStrategy) {
      return p.childActorOf(props, name, supervisorStrategy);
  }

  /**
   * Spawns an actor as a child of this test actor, and returns the child's ActorRef.
   * The actor will have an auto-generated name.
   * @param props Props to create the child actor
   * @param supervisorStrategy Strategy should decide what to do with failures in the actor.
   */
  public ActorRef childActorOf(Props props, SupervisorStrategy supervisorStrategy) {
      return p.childActorOf(props, supervisorStrategy);
  }

  /**
   * Spawns an actor as a child of this test actor, and returns the child's ActorRef.
   * The actor will be supervised using {@link SupervisorStrategy.stoppingStrategy}.
   * @param props Props to create the child actor
   * @param name Actor name for the child actor
   */
  public ActorRef childActorOf(Props props, String name) {
      return p.childActorOf(props, name);
  }

  /**
   * Spawns an actor as a child of this test actor, and returns the child's ActorRef.
   * The actor will have an auto-generated name and will be supervised using {@link SupervisorStrategy.stoppingStrategy}.
   * @param props Props to create the child actor
   */
  public ActorRef childActorOf(Props props) {
      return p.childActorOf(props);
  }
}
