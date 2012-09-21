Dataflow Concurrency (Scala)
============================

Description
-----------

Akka implements `Oz-style dataflow concurrency <http://www.mozart-oz.org/documentation/tutorial/node8.html#chapter.concurrency>`_
by using a special API for :ref:`futures-scala` that enables a complimentary way of writing synchronous-looking code that in reality is asynchronous.

The benefit of Dataflow concurrency is that it is deterministic; that means that it will always behave the same.
If you run it once and it yields output 5 then it will do that **every time**, run it 10 million times - same result.
If it on the other hand deadlocks the first time you run it, then it will deadlock **every single time** you run it.
Also, there is **no difference** between sequential code and concurrent code. These properties makes it very easy to reason about concurrency.
The limitation is that the code needs to be side-effect free, i.e. deterministic.
You can't use exceptions, time, random etc., but need to treat the part of your program that uses dataflow concurrency as a pure function with input and output.

The best way to learn how to program with dataflow variables is to read the fantastic book `Concepts, Techniques, and Models of Computer Programming <http://www.info.ucl.ac.be/%7Epvr/book.html>`_. By Peter Van Roy and Seif Haridi.

Getting Started (SBT)
---------------------

Scala's Delimited Continuations plugin is required to use the Dataflow API. To enable the plugin when using sbt, your project must inherit the ``AutoCompilerPlugins`` trait and contain a bit of configuration as is seen in this example:

.. code-block:: scala

  autoCompilerPlugins := true,
  libraryDependencies <+= scalaVersion {
    v => compilerPlugin("org.scala-lang.plugins" % "continuations" % @scalaVersion@)
  },
  scalacOptions += "-P:continuations:enable",


You will also need to include a dependency on ``akka-dataflow``:

.. code-block:: scala

  "com.typesafe.akka" %% "akka-dataflow" % "@version@" @crossString@

Dataflow variables
------------------

A Dataflow variable can be read any number of times but only be written to once, which maps very well to the concept of Futures/Promises :ref:`futures-scala`.
Conversion from ``Future`` and ``Promise`` to Dataflow Variables is implicit and is invisible to the user (after importing akka.dataflow._).

The mapping from ``Promise`` and ``Future`` is as follows:

  - Futures are readable-many, using the ``apply`` method, inside ``flow`` blocks.
  - Promises are readable-many, just like Futures.
  - Promises are writable-once, using the ``<<`` operator, inside ``flow`` blocks.
    Writing to an already written Promise throws a ``java.lang.IllegalStateException``,
    this has the effect that races to write a promise will be deterministic,
    only one of the writers will succeed and the others will fail.

The flow
--------

The ``flow`` method acts as the delimiter of dataflow expressions (this also neatly aligns with the concept of delimited continuations),
and flow-expressions compose. At this point you might wonder what the ``flow``-construct brings to the table that for-comprehensions don't,
and that is the use of the CPS plugin that makes the *look like* it is synchronous, but in reality is asynchronous and non-blocking.
The result of a call to ``flow`` is a Future with the resulting value of the flow.

To be able to use the ``flow`` method, you need to import:

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: import-akka-dataflow

The ``flow`` method will, just like Futures and Promises, require an implicit ``ExecutionContext`` in scope.
For the examples here we will use:

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: import-global-implicit

Using flow
~~~~~~~~~~

First off we have the obligatory "Hello world!":

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: simplest-hello-world

You can also refer to the results of other flows within flows:

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: nested-hello-world-a

… or:

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: nested-hello-world-b

Working with variables
~~~~~~~~~~~~~~~~~~~~~~

Inside the flow method you can use Promises as Dataflow variables:

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: dataflow-variable-a

Flow compared to for
--------------------

Should I use Dataflow or for-comprehensions?

.. includecode:: code/docs/dataflow/DataflowDocSpec.scala
   :include: for-vs-flow

Conclusions:

 - Dataflow has a smaller code footprint and arguably is easier to reason about.
 - For-comprehensions are more general than Dataflow, and can operate on a wide array of types.

