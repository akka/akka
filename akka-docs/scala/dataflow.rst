Dataflow Concurrency (Scala)
============================

Description
-----------

Akka implements `Oz-style dataflow concurrency <http://www.mozart-oz.org/documentation/tutorial/node8.html#chapter.concurrency>`_ by using a special API for :ref:`futures-scala` that allows single assignment variables and multiple lightweight (event-based) processes/threads.

Dataflow concurrency is deterministic. This means that it will always behave the same. If you run it once and it yields output 5 then it will do that **every time**, run it 10 million times, same result. If it on the other hand deadlocks the first time you run it, then it will deadlock **every single time** you run it. Also, there is **no difference** between sequential code and concurrent code. These properties makes it very easy to reason about concurrency. The limitation is that the code needs to be side-effect free, e.g. deterministic. You can't use exceptions, time, random etc., but need to treat the part of your program that uses dataflow concurrency as a pure function with input and output.

The best way to learn how to program with dataflow variables is to read the fantastic book `Concepts, Techniques, and Models of Computer Programming <http://www.info.ucl.ac.be/%7Epvr/book.html>`_. By Peter Van Roy and Seif Haridi.

Getting Started (SBT)
---------------------

Scala's Delimited Continuations plugin is required to use the Dataflow API. To enable the plugin when using sbt, your project must inherit the ``AutoCompilerPlugins`` trait and contain a bit of configuration as is seen in this example:

.. code-block:: scala

  autoCompilerPlugins := true,
  libraryDependencies <+= scalaVersion { v => compilerPlugin("org.scala-lang.plugins" % "continuations" % <scalaVersion>) },
  scalacOptions += "-P:continuations:enable",


You will also need to include a dependency on akka-dataflow

.. code-block:: scala

  "com.typesafe.akka" % "akka-dataflow" % "2.1-SNAPSHOT"

The flow
--------

The ``flow`` construct acts as the delimeter of dataflow expressions (this also neatly aligns with the concept of delimited continuations),
and flow-expressions compose. At this point you might wonder what the ``flow``-construct brings to the table that for-comprehensions don't,
and that is the use of the CPS plugin that makes the look _look like_ it is synchronous, but it indeed isn't when it gets executed.

import scala.concurrent.ExecutionContext.Implicit._
flow { 5 } onComplete println

Dataflow variables
------------------

A Dataflow variable can be read any number of times but only be written to once, which maps very well to the concept of Futures :ref:`futures-scala`.
Conversion from ``Future`` and ``Promise`` to Dataflow is implicit and is invisible to the user.

