Dataflow Concurrency (Scala)
============================

Description
===========

IMPORTANT: As of Akka 1.1, Akka Future, CompletableFuture and DefaultCompletableFuture have all the functionality of DataFlowVariables, they also support non-blocking composition and advanced features like fold and reduce, Akka DataFlowVariable is therefor deprecated and will probably resurface in the following release as a DSL on top of Futures.
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

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

.. code-block:: scala

  val x = new DataFlowVariable[Int]

2. Wait for Dataflow Variable to be bound

.. code-block:: scala

  x()

3. Bind Dataflow Variable

.. code-block:: scala

  x << 3

A Dataflow Variable can only be bound once. Subsequent attempts to bind the variable will throw an exception.

You can also shutdown a dataflow variable like this:

.. code-block:: scala

  x.shutdown

Threads
-------

You can easily create millions lightweight (event-driven) threads on a regular workstation.

.. code-block:: scala

  thread { ... }

You can also set the thread to a reference to be able to control its life-cycle:

.. code-block:: scala

  val t = thread { ... }

  ... // time passes

  t ! 'exit // shut down the thread

Examples
========

Most of these examples are taken from the `Oz wikipedia page <http://en.wikipedia.org/wiki/Oz_%28programming_language%29>`_

To run these examples:

1. Start REPL

::

  $ sbt
  > project akka-actor
  > console

::

  Welcome to Scala version 2.8.0.final (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_22).
  Type in expressions to have them evaluated.
  Type :help for more information.

  scala>

2. Paste the examples (below) into the Scala REPL.
Note: Do not try to run the Oz version, it is only there for reference.

3. Have fun.

Simple DataFlowVariable example
-------------------------------

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

  import  akka.dataflow.DataFlow._

  val x, y, z = new DataFlowVariable[Int]

  thread {
    z << x() + y()
    println("z = " + z())
  }
  thread { x << 40 }
  thread { y << 2 }

Example of using DataFlowVariable with recursion
------------------------------------------------

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

  import  akka.dataflow.DataFlow._

  def ints(n: Int, max: Int): List[Int] =
    if (n == max) Nil
    else n :: ints(n + 1, max)

   def sum(s: Int, stream: List[Int]): List[Int] = stream match {
    case Nil => s :: Nil
    case h :: t => s :: sum(h + s, t)
  }

  val x = new DataFlowVariable[List[Int]]
  val y = new DataFlowVariable[List[Int]]

  thread { x << ints(0, 1000) }
  thread { y << sum(0, x()) }
  thread { println("List of sums: " + y()) }

Example on life-cycle management of DataFlowVariables
-----------------------------------------------------

Shows how to shutdown dataflow variables and bind threads to values to be able to interact with them (exit etc.).

Example in Akka:

`<code format="scala">`_
import  akka.dataflow.DataFlow._

// create four 'Int' data flow variables
val x, y, z, v = new DataFlowVariable[Int]

val main = thread {
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

  // main completed, shut down the data flow variables
  x.shutdown
  y.shutdown
  z.shutdown
  v.shutdown
}

val setY = thread {
  println("Thread 'setY', sleeping...")
  Thread.sleep(5000)
  y << 2
  println("'y' set to: " + y())
}

val setV = thread {
  println("Thread 'setV'")
  v << y
  println("'v' set to 'y': " + v())
}

// shut down the threads
main ! 'exit
setY ! 'exit
setV ! 'exit
`<code>`_
