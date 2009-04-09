/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.configuration.*;
import se.scalablesolutions.akka.kernel.TransientObjectState;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

import junit.framework.TestCase;

public class ActiveObjectGuiceConfiguratorTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfigurator conf = new ActiveObjectGuiceConfigurator();

  protected void setUp() {
    conf.addExternalGuiceModule(new AbstractModule() {
      protected void configure() {
        bind(Ext.class).to(ExtImpl.class).in(Scopes.SINGLETON);
      }
    }).configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000), new Component[]{
            new Component(
                Foo.class,
                FooImpl.class,
                new LifeCycle(new Permanent(), 1000),
                1000),
            new Component(
                Bar.class,
                BarImpl.class,
                new LifeCycle(new Permanent(), 1000),
                1000),
            new Component(
                Stateful.class,
                StatefulImpl.class,
                new LifeCycle(new Permanent(), 1000),
                10000000),
            new Component(
                Failer.class,
                FailerImpl.class,
                new LifeCycle(new Permanent(), 1000),
                1000),
            new Component(
                Clasher.class,
                ClasherImpl.class,
                new LifeCycle(new Permanent(), 1000),
                100000)
        }).inject().supervise();

  }

  public void testGuiceActiveObjectInjection() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    Bar bar = conf.getActiveObject(Bar.class);
    assertTrue(foo.getBar().toString().equals(bar.toString()));
  }

  public void testGuiceExternalDependencyInjection() {
    messageLog = "";
    Bar bar = conf.getActiveObject(Bar.class);
    Ext ext = conf.getExternalDependency(Ext.class);
    assertTrue(bar.getExt().toString().equals(ext.toString()));
  }

  public void testLookupNonSupervisedInstance() {
    try {
      String str = conf.getActiveObject(String.class);
      fail("exception should have been thrown");
    } catch (Exception e) {
      assertEquals("Class java.lang.String has not been put under supervision (by passing in the config to the supervise()  method", e.getMessage());
    }
  }

  public void testActiveObjectInvocation() throws InterruptedException {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    messageLog += foo.foo("foo ");
    foo.bar("bar ");
    messageLog += "before_bar ";
    Thread.sleep(500);
    assertEquals("foo return_foo before_bar ", messageLog);
  }

  public void testActiveObjectInvocationsInvocation() throws InterruptedException {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    Bar bar = conf.getActiveObject(Bar.class);
    messageLog += foo.foo("foo ");
    foo.bar("bar ");
    messageLog += "before_bar ";
    Thread.sleep(500);
    assertEquals("foo return_foo before_bar ", messageLog);
  }


  public void testForcedTimeout() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    try {
      foo.longRunning();
      fail("exception should have been thrown");
    } catch (se.scalablesolutions.akka.kernel.ActiveObjectInvocationTimeoutException e) {
    }
  }

  public void testForcedException() {
    messageLog = "";
    Foo foo = conf.getActiveObject(Foo.class);
    try {
      foo.throwsException();
      fail("exception should have been thrown");
    } catch (RuntimeException e) {
    }
  }
//
//  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
//    Stateful stateful = conf.getActiveObject(Stateful.class);
//    stateful.setState("stateful", "init"); // set init state
//    stateful.success("stateful", "new state"); // transactional
//    assertEquals("new state", stateful.getState("stateful"));
//  }
//
//  public void testShouldRollbackStateForStatefulServerInCaseOfFailure() {
//    Stateful stateful = conf.getActiveObject(Stateful.class);
//    stateful.setState("stateful", "init"); // set init state
//
//    Failer failer = conf.getActiveObject(Failer.class);
//    try {
//      stateful.failure("stateful", "new state", failer); // call failing transactional method
//      fail("should have thrown an exception");
//    } catch (RuntimeException e) { } // expected
//    assertEquals("init", stateful.getState("stateful")); // check that state is == init state
//  }

  public void testShouldRollbackStateForStatefulServerInCaseOfMessageClash() {
    Stateful stateful = conf.getActiveObject(Stateful.class);
    stateful.setState("stateful", "init"); // set init state

    Clasher clasher = conf.getActiveObject(Clasher.class);
    clasher.setState("clasher", "init"); // set init state

//    try {
//      stateful.clashOk("stateful", "new state", clasher);
//    } catch (RuntimeException e) { } // expected
//    assertEquals("new state", stateful.getState("stateful")); // check that state is == init state
//    assertEquals("was here", clasher.getState("clasher")); // check that state is == init state

    try {
      stateful.clashNotOk("stateful", "new state", clasher);
      fail("should have thrown an exception");
    } catch (RuntimeException e) { System.out.println(e); } // expected
    assertEquals("init", stateful.getState("stateful")); // check that state is == init state
    //assertEquals("init", clasher.getState("clasher")); // check that state is == init state
  }
}

// ============== TEST SERVICES ===============

interface Foo {
  public String foo(String msg);
  @oneway public void bar(String msg);
  public void longRunning();
  public void throwsException();
  public Bar getBar();
}

class FooImpl implements Foo {
  @Inject private Bar bar;
  public Bar getBar() {
    return bar;
  }
  public String foo(String msg) {
    return msg + "return_foo ";
  }
  public void bar(String msg) {
    bar.bar(msg);
  }
  public void longRunning() {
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
    }
  }
  public void throwsException() {
    throw new RuntimeException("expected");
  }
}

interface Bar {
  @oneway void bar(String msg);
  Ext getExt();
}

class BarImpl implements Bar {
  @Inject private Ext ext;
  public Ext getExt() {
    return ext;
  }
  public void bar(String msg) {
  }
}

interface Ext {
  void ext();
}

class ExtImpl implements Ext {
  public void ext() {
  }
}

interface Stateful {
  // transactional
  @transactional public void success(String key, String msg);
  @transactional public void failure(String key, String msg, Failer failer);
  @transactional public void clashOk(String key, String msg, Clasher clasher);
  @transactional public void clashNotOk(String key, String msg, Clasher clasher);

  // non-transactional
  public String getState(String key);
  public void setState(String key, String value);
}

class StatefulImpl implements Stateful {
  @state private TransientObjectState state = new TransientObjectState();
  public String getState(String key) {
    return (String)state.get(key);
  }
  public void setState(String key, String msg) {
    state.put(key, msg);
  }
  public void success(String key, String msg) {
    state.put(key, msg);
  }
  public void failure(String key, String msg, Failer failer) {
    state.put(key, msg);
    failer.fail();
  }
  public void clashOk(String key, String msg, Clasher clasher) {
    state.put(key, msg);
    clasher.clash();
  }
  public void clashNotOk(String key, String msg, Clasher clasher) {
    state.put(key, msg);
    clasher.clash();
    clasher.clash();
  }
}

interface Failer {
  public void fail();
}

class FailerImpl implements Failer {
  public void fail() {
    throw new RuntimeException("expected");
  }
}

interface Clasher {
  public void clash();
  public String getState(String key);
  public void setState(String key, String value);
}

class ClasherImpl implements Clasher {
  @state private TransientObjectState state = new TransientObjectState();
  public String getState(String key) {
    return (String)state.get(key);
  }
  public void setState(String key, String msg) {
    state.put(key, msg);
  }
  public void clash() {
    state.put("clasher", "was here");
    // spend some time here
    for (long i = 0; i < 1000000000; i++) {
      for (long j = 0; j < 10000000; j++) {
        j += i;
      }
    }

    // FIXME: this statement gives me this error:
    // se.scalablesolutions.akka.kernel.ActiveObjectException:
    //      Unexpected message [!(scala.actors.Channel@c2b2f6,ErrRef[Right(null)])] to
    //      [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    //      [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    //try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}


