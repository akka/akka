.. _futures-scala:

Futures (Scala)
===============

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

In Akka, a `Future <http://en.wikipedia.org/wiki/Futures_and_promises>`_ is a data structure used to retrieve the result of some concurrent operation. This operation is usually performed by an ``Actor`` or by the ``Dispatcher`` directly. This result can be accessed synchronously (blocking) or asynchronously (non-blocking).

Use with Actors
---------------

There are generally two ways of getting a reply from an ``Actor``: the first is by a sent message (``actor ! msg``), which only works if the original sender was an ``Actor``) and the second is through a ``Future``.

Using an ``Actor``\'s ``?`` method to send a message will return a Future. To wait for and retrieve the actual result the simplest method is:

.. code-block:: scala

  val future = actor ? msg
  val result: Any = future.get()

This will cause the current thread to block and wait for the ``Actor`` to 'complete' the ``Future`` with it's reply. Due to the dynamic nature of Akka's ``Actor``\s this result will be untyped and will default to ``Nothing``. The safest way to deal with this is to cast the result to an ``Any`` as is shown in the above example. You can also use the expected result type instead of ``Any``, but if an unexpected type were to be returned you will get a ``ClassCastException``. For more elegant ways to deal with this and to use the result without blocking, refer to `Functional Futures`_.

Use Directly
------------

A common use case within Akka is to have some computation performed concurrently without needing the extra utility of an ``Actor``. If you find yourself creating a pool of ``Actor``\s for the sole reason of performing a calculation in parallel, there is an easier (and faster) way:

.. code-block:: scala

  import akka.dispatch.Future

  val future = Future {
    "Hello" + "World"
  }
  val result = future.get()

In the above code the block passed to ``Future`` will be executed by the default ``Dispatcher``, with the return value of the block used to complete the ``Future`` (in this case, the result would be the string: "HelloWorld"). Unlike a ``Future`` that is returned from an ``Actor``, this ``Future`` is properly typed, and we also avoid the overhead of managing an ``Actor``.


onComplete
----------

The mother of ``Future`-composition is the onComplete callback, which allows you to get notified asynchronously when the ``Future```gets completed:

.. code-block:: scala

  val future: Future[Any] = ...
  future onComplete {
    _.value.get match {
      case Left(problem) => handleCompletedWithException(problem)
      case Right(result) => handleCompletedWithResult(result)
    }
  }

  //You can also short that down to:
  future onComplete { _.value.get.fold(handleCompletedWithException(_), handleCompletedWithResult(_)) }


  //There's also a callback named ``onResult`` that only deals with results (and the other, ``onException`` is described further down in this document)
  future onResult {
    case "foo" => logResult("GOT MYSELF A FOO OH YEAH BABY!")
  }

Functional Futures
------------------

A recent addition to Akka's ``Future`` is several monadic methods that are very similar to the ones used by Scala's collections. These allow you to create 'pipelines' or 'streams' that the result will travel through.

Future is a Monad
^^^^^^^^^^^^^^^^^

The first method for working with ``Future`` functionally is ``map``. This method takes a ``Function`` which performs some operation on the result of the ``Future``, and returning a new result. The return value of the ``map`` method is another ``Future`` that will contain the new result:

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  val f2 = f1 map { x =>
    x.length
  }

  val result = f2.get

In this example we are joining two strings together within a Future. Instead of waiting for this to complete, we apply our function that calculates the length of the string using the ``map`` method. Now we have a second Future that will eventually contain an ``Int``. When our original ``Future`` completes, it will also apply our function and complete the second Future with it's result. When we finally ``get`` the result, it will contain the number 10. Our original Future still contains the string "HelloWorld" and is unaffected by the ``map``.

The ``map`` method is fine if we are modifying a single ``Future``, but if 2 or more ``Future``\s are involved ``map`` will not allow you to combine them together:

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  val f2 = Future {
    3
  }

  val f3 = f1 map { x =>
    f2 map { y =>
      x.length * y
    }
  }

  val result = f2.get.get

The ``get`` method had to be used twice because ``f3`` is a ``Future[Future[Int]]`` instead of the desired ``Future[Int]``. Instead, the ``flatMap`` method should be used:

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  val f2 = Future {
    3
  }

  val f3 = f1 flatMap { x =>
    f2 map { y =>
      x.length * y
    }
  }

  val result = f2.get

For Comprehensions
^^^^^^^^^^^^^^^^^^

Since ``Future`` has a ``map`` and ``flatMap`` method it can be easily used in a 'for comprehension':

.. code-block:: scala

  val f = for {
    a <- Future(10 / 2) // 10 / 2 = 5
    b <- Future(a + 1)  //  5 + 1 = 6
    c <- Future(a - 1)  //  5 - 1 = 4
  } yield b * c         //  6 * 4 = 24

  val result = f.get

Something to keep in mind when doing this is even though it looks like parts of the above example can run in parallel, each step of the for comprehension is run sequentially. This will happen on separate threads for each step but there isn't much benefit over running the calculations all within a single Future. The real benefit comes when the ``Future``\s are created first, and then combining them together.

Composing Futures
^^^^^^^^^^^^^^^^^

The example for comprehension above is an example of composing ``Future``\s. A common use case for this is combining the replies of several ``Actor``\s into a single calculation without resorting to calling ``get`` or ``await`` to block for each result. First an example of using ``get``:

.. code-block:: scala

  val f1 = actor1 ? msg1
  val f2 = actor2 ? msg2

  val a: Int = f1.get
  val b: Int = f2.get

  val f3 = actor3 ? (a + b)

  val result: String = f3.get

Here we wait for the results from the first 2 ``Actor``\s before sending that result to the third ``Actor``. We called ``get`` 3 times, which caused our little program to block 3 times before getting our final result. Now compare that to this example:

.. code-block:: scala

  val f1 = actor1 ? msg1
  val f2 = actor2 ? msg2

  val f3 = for {
    a: Int    <- f1
    b: Int    <- f2
    c: String <- actor3 ? (a + b)
  } yield c

  val result = f3.get

Here we have 2 actors processing a single message each. Once the 2 results are available (note that we don't block to get these results!), they are being added together and sent to a third ``Actor``, which replies with a string, which we assign to 'result'.

This is fine when dealing with a known amount of Actors, but can grow unwieldy if we have more then a handful. The ``sequence`` and ``traverse`` helper methods can make it easier to handle more complex use cases. Both of these methods are ways of turning, for a subclass ``T`` of ``Traversable``, ``T[Future[A]]`` into a ``Future[T[A]]``. For example:

.. code-block:: scala

  // oddActor returns odd numbers sequentially from 1
  val listOfFutures: List[Future[Int]] = List.fill(100)(oddActor ? GetNext)

  // now we have a Future[List[Int]]
  val futureList = Future.sequence(listOfFutures)

  // Find the sum of the odd numbers
  val oddSum = futureList.map(_.sum).get

To better explain what happened in the example, ``Future.sequence`` is taking the ``List[Future[Int]]`` and turning it into a ``Future[List[Int]]``. We can then use ``map`` to work with the ``List[Int]`` directly, and we find the sum of the ``List``.

The ``traverse`` method is similar to ``sequence``, but it takes a ``T[A]`` and a function ``T => Future[B]`` to return a ``Future[T[B]]``, where ``T`` is again a subclass of Traversable. For example, to use ``traverse`` to sum the first 100 odd numbers:

.. code-block:: scala

  val oddSum = Future.traverse((1 to 100).toList)(x => Future(x * 2 - 1)).map(_.sum).get

This is the same result as this example:

.. code-block:: scala

  val oddSum = Future.sequence((1 to 100).toList.map(x => Future(x * 2 - 1))).map(_.sum).get

But it may be faster to use ``traverse`` as it doesn't have to create an intermediate ``List[Future[Int]]``.


Then there's a method that's called ``fold`` that takes a start-value, a sequence of ``Future``:s and a function from the type of the start-value and the type of the futures and returns something with the same type as the start-value, and then applies the function to all elements in the sequence of futures, non-blockingly, the execution will run on the Thread of the last completing Future in the sequence.

.. code-block:: scala

  val futures = for(i <- 1 to 1000) yield Future(i * 2) // Create a sequence of Futures
  
  val futureSum = Futures.fold(0)(futures)(_ + _)

That's all it takes!


If the sequence passed to ``fold`` is empty, it will return the start-value, in the case above, that will be 0. In some cases you don't have a start-value and you're able to use the value of the first completing Future in the sequence as the start-value, you can use ``reduce``, it works like this:

.. code-block:: scala

  val futures = for(i <- 1 to 1000) yield Future(i * 2) // Create a sequence of Futures

  val futureSum = Futures.reduce(futures)(_ + _)

Same as with ``fold``, the execution will be done by the Thread that completes the last of the Futures, you can also parallize it by chunking your futures into sub-sequences and reduce them, and then reduce the reduced results again.



This is just a sample of what can be done, but to use more advanced techniques it is easier to take advantage of Scalaz, which Akka has support for in its akka-scalaz module.

Scalaz
^^^^^^

Akka also has a Scalaz module (:ref:`add-on-modules`) for a more complete support of programming in a functional style.

Exceptions
----------

Since the result of a ``Future`` is created concurrently to the rest of the program, exceptions must be handled differently. It doesn't matter if an ``Actor`` or the dispatcher is completing the ``Future``, if an ``Exception`` is caught the ``Future`` will contain it instead of a valid result. If a ``Future`` does contain an ``Exception``, calling ``get`` will cause it to be thrown again so it can be handled properly.

It is also possible to handle an ``Exception`` by returning a different result. This is done with the ``recover`` method. For example:

.. code-block:: scala

  val future = actor ? msg1 recover {
    case e: ArithmeticException => 0
  }

In this example, if an ``ArithmeticException`` was thrown while the ``Actor`` processed the message, our ``Future`` would have a result of 0. The ``failure`` method works very similarly to the standard try/catch blocks, so multiple ``Exception``\s can be handled in this manner, and if an ``Exception`` is not handled this way it will be behave as if we hadn't used the ``failure`` method.

You also have the option to register a callback that will be executed if the ``Future`` is completed with an exception:

.. code-block:: scala

  val f: Future[Any] = ...
    f onException {
      case npe: NullPointerExcep => doSomething
      case 6 => doSomethingElse
      case SomeRegex(param) => doSomethingOther
      case _ => doAnything
    } // Applies the specified partial function to the result of the future when it is completed with an exception



Timeouts
--------

Waiting forever for a ``Future`` to be completed can be dangerous. It could cause your program to block indefinitly or produce a memory leak. ``Future`` has support for a timeout already builtin with a default of 5000 milliseconds (taken from 'akka.conf'). A timeout can also be specified when creating a ``Future``:

.. code-block:: scala

  val future = Future( { doSomething }, 10000 )

This example creates a ``Future`` with a 10 second timeout.

If the timeout is reached the ``Future`` becomes unusable, even if an attempt is made to complete it. It is possible to have a ``Future`` perform an action on timeout if needed with the ``onTimeout`` method:

.. code-block:: scala

  val future1 = actor ? msg onTimeout { _ =>
    println("Timed out!")
  }

Cancelation
-----------

In case the result of a Future is not needed any more, it may be completed with
a :class:`FutureCanceledException` by calling ``Future.cancel()``, thus
inhibiting later normal completion (unless it was already completed).  While
this action does *not* cancel the computation of the ``Future`` value, it will
prevent the invokation of registered ``onComplete`` callbacks with that value,
hence possibly aborting a processing chain; it does nothing, however, it the
``Future`` already has been completed.

.. code-block:: scala

  val future = Future( ... )
  ...
  future.cancel()

If applied to a composite ``Future``, i.e. one returned by the
``firstCompletedOf``, ``fold``, ``sequence`` or ``reduce`` methods on the
``Future`` or ``Futures`` objects, it will cancel all contained future, with
the natural effect an exception would have for that composition.

This method is probably most useful in ``onTimeout`` callbacks.

