Dataflow Concurrency (Java)
===========================

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

**IMPORTANT: As of Akka 1.1, Akka Future, Promise and DefaultPromise have all the functionality of DataFlowVariables, they also support non-blocking composition and advanced features like fold and reduce, Akka DataFlowVariable is therefor deprecated and will probably resurface in the following release as a DSL on top of Futures.**

Akka implements `Oz-style dataflow concurrency <http://www.mozart-oz.org/documentation/tutorial/node8.html#chapter.concurrency>`_ through dataflow (single assignment) variables and lightweight (event-based) processes/threads.

Dataflow concurrency is deterministic. This means that it will always behave the same. If you run it once and it yields output 5 then it will do that **every time**, run it 10 million times, same result. If it on the other hand deadlocks the first time you run it, then it will deadlock **every single time** you run it. Also, there is **no difference** between sequential code and concurrent code. These properties makes it very easy to reason about concurrency. The limitation is that the code needs to be side-effect free, e.g. deterministic. You can't use exceptions, time, random etc., but need to treat the part of your program that uses dataflow concurrency as a pure function with input and output.

The best way to learn how to program with dataflow variables is to read the fantastic book `Concepts, Techniques, and Models of Computer Programming <http://www.info.ucl.ac.be/%7Epvr/book.html>`_. By Peter Van Roy and Seif Haridi.

The documentation is not as complete as it should be, something we will improve shortly. For now, besides above listed resources on dataflow concurrency, I recommend you to read the documentation for the GPars implementation, which is heavily influenced by the Akka implementation:

* `<http://gpars.codehaus.org/Dataflow>`_
* `<http://www.gpars.org/guide/guide/7.%20Dataflow%20Concurrency.html>`_

Dataflow Variables
------------------

Dataflow Variable defines three different operations:

1. Define a Dataflow Variable

.. code-block:: java

  import static akka.dataflow.DataFlow.*;

  DataFlowVariable<int> x = new DataFlowVariable<int>();

2. Wait for Dataflow Variable to be bound

.. code-block:: java

  x.get();

3. Bind Dataflow Variable

.. code-block:: java

  x.set(3);

A Dataflow Variable can only be bound once. Subsequent attempts to bind the variable will throw an exception.

You can also shutdown a dataflow variable like this:

.. code-block:: java

  x.shutdown();

Threads
-------

You can easily create millions lightweight (event-driven) threads on a regular workstation.

.. code-block:: java

  import static akka.dataflow.DataFlow.*;
  import akka.japi.Effect;

  thread(new Effect() {
    public void apply() { ... }
  });

You can also set the thread to a reference to be able to control its life-cycle:

.. code-block:: java

  import static akka.dataflow.DataFlow.*;
  import akka.japi.Effect;

  ActorRef t = thread(new Effect() {
    public void apply() { ... }
  });

  ... // time passes

  t.sendOneWay(new Exit()); // shut down the thread

Examples
--------

Most of these examples are taken from the `Oz wikipedia page <http://en.wikipedia.org/wiki/Oz_%28programming_language%29>`_

Simple DataFlowVariable example
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This example is from Oz wikipedia page: http://en.wikipedia.org/wiki/Oz_(programming_language).
Sort of the "Hello World" of dataflow concurrency.

Example in Oz:

.. code-block:: ruby

  thread
    Z = X+Y     % will wait until both X and Y are bound to a value.
    {Browse Z}  % shows the value of Z.
  end
  thread X = 40 end
  thread Y = 2 end

Example in Akka:

.. code-block:: java

  import static akka.dataflow.DataFlow.*;
  import akka.japi.Effect;

  DataFlowVariable<int> x = new DataFlowVariable<int>();
  DataFlowVariable<int> y = new DataFlowVariable<int>();
  DataFlowVariable<int> z = new DataFlowVariable<int>();

  thread(new Effect() {
    public void apply() {
      z.set(x.get() + y.get());
      System.out.println("z = " + z.get());
    }
  });

  thread(new Effect() {
    public void apply() {
      x.set(40);
    }
  });

  thread(new Effect() {
    public void apply() {
      y.set(40);
    }
  });

Example on life-cycle management of DataFlowVariables
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Shows how to shutdown dataflow variables and bind threads to values to be able to interact with them (exit etc.).

Example in Akka:

.. code-block:: java

  import static akka.dataflow.DataFlow.*;
  import akka.japi.Effect;

  // create four 'int' data flow variables
  DataFlowVariable<int> x = new DataFlowVariable<int>();
  DataFlowVariable<int> y = new DataFlowVariable<int>();
  DataFlowVariable<int> z = new DataFlowVariable<int>();
  DataFlowVariable<int> v = new DataFlowVariable<int>();

  ActorRef main = thread(new Effect() {
    public void apply() {
      System.out.println("Thread 'main'")
      if (x.get() > y.get()) {
        z.set(x);
        System.out.println("'z' set to 'x': " + z.get());
      } else {
        z.set(y);
        System.out.println("'z' set to 'y': " + z.get());
      }

      // main completed, shut down the data flow variables
      x.shutdown();
      y.shutdown();
      z.shutdown();
      v.shutdown();
    }
  });

  ActorRef setY = thread(new Effect() {
    public void apply() {
      System.out.println("Thread 'setY', sleeping...");
      Thread.sleep(5000);
      y.set(2);
      System.out.println("'y' set to: " + y.get());
    }
  });

  ActorRef setV = thread(new Effect() {
    public void apply() {
      System.out.println("Thread 'setV'");
      y.set(2);
      System.out.println("'v' set to y: " + v.get());
    }
  });

  // shut down the threads
  main.sendOneWay(new Exit());
  setY.sendOneWay(new Exit());
  setV.sendOneWay(new Exit());
