/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit;

import scala.runtime.AbstractFunction0;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.Logging.LogEvent;
import akka.japi.JavaPartialFunction;
import akka.japi.Util;
import scala.concurrent.util.Duration;

/**
 * Java API for the TestProbe. Proper JavaDocs to come once JavaDoccing is implemented.
 */
public class JavaTestKit {
  private final TestProbe p;

  public JavaTestKit(ActorSystem system) {
    p = new TestProbe(system);
  }

  public ActorRef getRef() {
    return p.ref();
  }

  public ActorSystem getSystem() {
    return p.system();
  }
  
  static public Duration duration(String s) {
    return Duration.parse(s);
  }
  
  public Duration dilated(Duration d) {
    return d.mul(TestKitExtension.get(p.system()).TestTimeFactor());
  }

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

  public Duration getRemainingTime() {
    return p.remaining();
  }

  public Duration getRemainingTimeOr(Duration def) {
    return p.remainingOr(def);
  }

  public ActorRef watch(ActorRef ref) {
    return p.watch(ref);
  }

  public ActorRef unwatch(ActorRef ref) {
    return p.unwatch(ref);
  }

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

  public void ignoreNoMsg() {
    p.ignoreNoMsg();
  }

  public void setAutoPilot(TestActor.AutoPilot pilot) {
    p.setAutoPilot(pilot);
  }

  public abstract class Within {
    protected abstract void run();

    public Within(Duration max) {
      p.within(max, new AbstractFunction0<Object>() {
        public Object apply() {
          run();
          return null;
        }
      });
    }

    public Within(Duration min, Duration max) {
      p.within(min, max, new AbstractFunction0<Object>() {
        public Object apply() {
          run();
          return null;
        }
      });
    }
  }

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
        public Object apply() {
          return cond();
        }
      }, max, interval);
    }
  }

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
        throw new AssertionError("while expecting '" + hint
            + "' received unexpected: " + received);
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

  public <T> T expectMsgEquals(T msg) {
    return p.expectMsg(msg);
  }

  public <T> T expectMsgEquals(Duration max, T msg) {
    return p.expectMsg(max, msg);
  }

  public <T> T expectMsgClass(Class<T> clazz) {
    return p.expectMsgClass(clazz);
  }

  public <T> T expectMsgClass(Duration max, Class<T> clazz) {
    return p.expectMsgClass(max, clazz);
  }

  public Object expectMsgAnyOf(Object... msgs) {
    return p.expectMsgAnyOf(Util.arrayToSeq(msgs));
  }

  public Object expectMsgAnyOf(Duration max, Object... msgs) {
    return p.expectMsgAnyOf(max, Util.arrayToSeq(msgs));
  }

  public Object[] expectMsgAllOf(Object... msgs) {
    return (Object[]) p.expectMsgAllOf(Util.arrayToSeq(msgs)).toArray(
        Util.classTag(Object.class));
  }

  public Object[] expectMsgAllOf(Duration max, Object... msgs) {
    return (Object[]) p.expectMsgAllOf(max, Util.arrayToSeq(msgs)).toArray(
        Util.classTag(Object.class));
  }

  @SuppressWarnings("unchecked")
  public <T> T expectMsgAnyClassOf(Class<? extends T>... classes) {
    final Object result = p.expectMsgAnyClassOf(Util.arrayToSeq(classes));
    return (T) result;
  }

  public Object expectMsgAnyClassOf(Duration max, Class<?>... classes) {
    return p.expectMsgAnyClassOf(max, Util.arrayToSeq(classes));
  }

  public void expectNoMsg() {
    p.expectNoMsg();
  }

  public void expectNoMsg(Duration max) {
    p.expectNoMsg(max);
  }

  public abstract class ReceiveWhile<T> {
    abstract protected T match(Object msg);

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

    @SuppressWarnings("unchecked")
    public ReceiveWhile(Class<T> clazz, Duration max, Duration idle, int messages) {
      results = p.receiveWhile(max, idle, messages,
          new CachingPartialFunction<Object, T>() {
            public T match(Object msg) {
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
      } else throw new IllegalArgumentException("supplied class must either be LogEvent or Throwable");
    }

    public T exec() {
      akka.testkit.EventFilter filter;
      if (clazz == Logging.Error.class) {
        if (exceptionType == null) exceptionType = Logging.noCause().getClass();
        filter = new ErrorFilter(exceptionType, source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Warning.class) {
        filter = new WarningFilter(source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Info.class) {
        filter = new InfoFilter(source, message, pattern, complete, occurrences);
      } else if (clazz == Logging.Debug.class) {
        filter = new DebugFilter(source, message, pattern, complete, occurrences);
      } else throw new IllegalArgumentException("unknown LogLevel " + clazz);
      return filter.intercept(new AbstractFunction0<T>() {
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

}
