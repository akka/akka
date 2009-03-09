/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.api;

import com.scalablesolutions.akka.annotation.oneway;
import com.scalablesolutions.akka.kernel.configuration.*;

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
        new RestartStrategy(new AllForOne(), 3, 100), new Component[]{
            new Component(
                Foo.class,
                FooImpl.class,
                new LifeCycle(new Permanent(), 100),
                1000),
            new Component(
                Bar.class,
                BarImpl.class,
                new LifeCycle(new Permanent(), 100),
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
    } catch (com.scalablesolutions.akka.kernel.ActiveObjectInvocationTimeoutException e) {
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

// ============== TEST SERVICES ===============

interface Foo {
  public String foo(String msg);

  @oneway
  public void bar(String msg);

  public void longRunning();

  public void throwsException();

  public Bar getBar();
}

class FooImpl implements Foo {
  @Inject
  private Bar bar;

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
  @oneway
  void bar(String msg);

  Ext getExt();
}

class BarImpl implements Bar {
  @Inject
  private Ext ext;

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

             