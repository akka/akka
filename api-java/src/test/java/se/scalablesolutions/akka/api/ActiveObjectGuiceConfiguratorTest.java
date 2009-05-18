/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.ActiveObjectGuiceConfiguratorForJava;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.InMemoryTransactionalMap;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

import junit.framework.TestCase;

public class ActiveObjectGuiceConfiguratorTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  protected void setUp() {
    conf.addExternalGuiceModule(new AbstractModule() {
      protected void configure() {
        bind(Ext.class).to(ExtImpl.class).in(Scopes.SINGLETON);
      }
    }).configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000), new Component[]{
            new Component(
                "foo",
                Foo.class,
                FooImpl.class,
                new LifeCycle(new Permanent(), 1000),
                1000),
            new Component(
                "bar",
                Bar.class,
                BarImpl.class,
                new LifeCycle(new Permanent(), 1000),
                1000)
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
      assertEquals("Class string has not been put under supervision (by passing in the config to the supervise()  method", e.getMessage());
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
}

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


