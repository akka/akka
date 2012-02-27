Dataflow Concurrency (Scala)
============================

.. sidebar:: Contents

   .. contents:: :local:

Description
-----------

Akka implements `Oz-style dataflow concurrency <http://www.mozart-oz.org/documentation/tutorial/node8.html#chapter.concurrency>`_ by using a special API for :ref:`futures-scala` that allows single assignment variables and multiple lightweight (event-based) processes/threads.

Dataflow concurrency is deterministic. This means that it will always behave the same. If you run it once and it yields output 5 then it will do that **every time**, run it 10 million times, same result. If it on the other hand deadlocks the first time you run it, then it will deadlock **every single time** you run it. Also, there is **no difference** between sequential code and concurrent code. These properties makes it very easy to reason about concurrency. The limitation is that the code needs to be side-effect free, e.g. deterministic. You can't use exceptions, time, random etc., but need to treat the part of your program that uses dataflow concurrency as a pure function with input and output.

The best way to learn how to program with dataflow variables is to read the fantastic book `Concepts, Techniques, and Models of Computer Programming <http://www.info.ucl.ac.be/%7Epvr/book.html>`_. By Peter Van Roy and Seif Haridi.

The documentation is not as complete as it should be, something we will improve shortly. For now, besides above listed resources on dataflow concurrency, I recommend you to read the documentation for the GPars implementation, which is heavily influenced by the Akka implementation:

* `<http://gpars.codehaus.org/Dataflow>`_
* `<http://www.gpars.org/guide/guide/7.%20Dataflow%20Concurrency.html>`_

Getting Started
---------------

Scala's Delimited Continuations plugin is required to use the Dataflow API. To enable the plugin when using sbt, your project must inherit the ``AutoCompilerPlugins`` trait and contain a bit of configuration as is seen in this example:

.. code-block:: scala

  autoCompilerPlugins := true,
  libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % <scalaVersion>) },
  scalacOptions += "-P:continuations:enable",

Dataflow Variables
------------------

Dataflow Variable defines four different operations:

1. Define a Dataflow Variable

.. code-block:: scala

  val x = Promise[Int]()

2. Wait for Dataflow Variable to be bound (must be contained within a ``Future.flow`` block as described in the next section)

.. code-block:: scala

  x()

3. Bind Dataflow Variable (must be contained within a ``Future.flow`` block as described in the next section)

.. code-block:: scala

  x << 3

4. Bind Dataflow Variable with a Future (must be contained within a ``Future.flow`` block as described in the next section)

.. code-block:: scala

  x << y

A Dataflow Variable can only be bound once. Subsequent attempts to bind the variable will be ignored.

Dataflow Delimiter
------------------

Dataflow is implemented in Akka using Scala's Delimited Continuations. To use the Dataflow API the code must be contained within a ``Future.flow`` block. For example:

.. code-block:: scala

  import Future.flow
  implicit val dispatcher = ...

  val a = Future( ... )
  val b = Future( ... )
  val c = Promise[Int]()

  flow {
    c << (a() + b())
  }

  val result = Await.result(c, timeout)

The ``flow`` method also returns a ``Future`` for the result of the contained expression, so the previous example could also be written like this:

.. code-block:: scala

  import Future.flow
  implicit val dispatcher = ...

  val a = Future( ... )
  val b = Future( ... )

  val c = flow {
    a() + b()
  }

  val result = Await.result(c, timeout)

Examples
--------

Most of these examples are taken from the `Oz wikipedia page <http://en.wikipedia.org/wiki/Oz_%28programming_language%29>`_

To run these examples:

1. Start REPL

::

  $ sbt
  > project akka-actor
  > console

::

  Welcome to Scala version 2.9.1 (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_25).
  Type in expressions to have them evaluated.
  Type :help for more information.

  scala>

2. Paste the examples (below) into the Scala REPL.
Note: Do not try to run the Oz version, it is only there for reference.

3. Have fun.

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

.. code-block:: scala

  import akka.dispatch._
  import Future.flow
  implicit val dispatcher = ...

  val x, y, z = Promise[Int]()

  flow {
    z << x() + y()
    println("z = " + z())
  }
  flow { x << 40 }
  flow { y << 2 }

Example of using DataFlowVariable with recursion
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Using DataFlowVariable and recursion to calculate sum.

Example in Oz:

.. code-block:: ruby

  fun {Ints N Max}
    if N == Max then nil
    else
      {Delay 1000}
      N|{Ints N+1 Max}
    end
  end

  fun {Sum S Stream}
    case Stream of nil then S
    [] H|T then S|{Sum H+S T} end
  end

  local X Y in
    thread X = {Ints 0 1000} end
    thread Y = {Sum 0 X} end
    {Browse Y}
  end

Example in Akka:

.. code-block:: scala

  import akka.dispatch._
  import Future.flow
  implicit val dispatcher = ...

  def ints(n: Int, max: Int): List[Int] = {
    if (n == max) Nil
    else n :: ints(n + 1, max)
  }

  def sum(s: Int, stream: List[Int]): List[Int] = stream match {
    case Nil => s :: Nil
    case h :: t => s :: sum(h + s, t)
  }

  val x, y = Promise[List[Int]]()

  flow { x << ints(0, 1000) }
  flow { y << sum(0, x()) }
  flow { println("List of sums: " + y()) }

Example using concurrent Futures
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Shows how to have a calculation run in another thread.

Example in Akka:

.. code-block:: scala

  import akka.dispatch._
  import Future.flow
  implicit val dispatcher = ...

  // create four 'Int' data flow variables
  val x, y, z, v = Promise[Int]()

  flow {
    println("Thread 'main'")

    x << 1
    println("'x' set to: " + x())

    println("Waiting for 'y' to be set...")

    if (x() > y()) {
      z << x
      println("'z' set to 'x': " + z())
    } else {
      z << y
      println("'z' set to 'y': " + z())
    }
  }

  flow {
    y << Future {
      println("Thread 'setY', sleeping")
      Thread.sleep(2000)
      2
    }
    println("'y' set to: " + y())
  }

  flow {
    println("Thread 'setV'")
    v << y
    println("'v' set to 'y': " + v())
  }
