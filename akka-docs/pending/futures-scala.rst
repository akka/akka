Futures (Scala)
===============

Introduction
------------

In Akka, a `Future <http://en.wikipedia.org/wiki/Futures_and_promises>`_ is a data structure used to retrieve the result of some concurrent operation. This operation is usually performed by an `Actor <futures-scala#use-actor>`_ or by the Dispatcher `directly <futures-scala#use-direct>`_. This result can be accessed synchronously (blocking) or asynchronously (non-blocking).

Use with Actors
---------------

There are generally two ways of getting a reply from an Actor: the first is by a sent message (`actor ! msg <actors-scala#fire-forget>`_), which only works if the original sender was an Actor) and the second is through a Future.

Using an Actor's '!!!' method to send a message will return a Future. To wait for and retreive the actual result the simplest method is:

.. code-block:: scala

  val future = actor !!! msg
  val result: Any = future.apply
  // or more simply
  val result: Any = future()

This will cause the current thread to block and wait for the Actor to 'complete' the Future with it's reply. Due to the dynamic nature of Akka's Actors this result will be untyped and will default to 'Nothing'. The safest way to deal with this is to cast the result to an Any as is shown in the above example. You can also use the expected result type instead of Any, but if an unexpected type were to be returned you will get a ClassCastException. For more elegant ways to deal with this and to use the result without blocking refer to `Functional Futures <futures-scala#functional>`_.

Use Directly
------------

A common use case within Akka is to have some computation performed concurrently without needing the extra utility of an Actor. If you find yourself creating a pool of Actors for the sole reason of performing a calculation in parallel, there is an easier (and faster) way:

.. code-block:: scala

  import akka.dispatch.Future

  val future = Future {
    "Hello" + "World"
  }
  val result = future()

In the above code the block passed to Future will be executed by the default `Dispatcher <dispatchers-scala>`_, with the return value of the block used to complete the Future (in this case, the result would be the string: "HelloWorld"). Unlike a Future that is returned from an Actor, this Future is properly typed, and we also avoid the overhead of managing an Actor.

Functional Futures
------------------

A recent addition to Akka's Future is several monadic methods that are very similar to the ones used by Scala's collections. These allow you to create 'pipelines' or 'streams' that the result will travel through.

Future is a Monad
^^^^^^^^^^^^^^^^^

The first method for working with Future functionally is 'map'. This method takes a Function which performs some operation on the result of the Future, and returning a new result. The return value of the 'map' method is another Future that will contain the new result:

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  val f2 = f1 map { x =>
    x.length
  }

  val result = f2()

In this example we are joining two strings together within a Future. Instead of waiting for this to complete, we apply our Function that calculates the length of the string using the 'map' method. Now we have a second Future that will contain an Int. When our original Future completes, it will also apply our Function and complete the second Future with that result. When we finally await the result, it will contain the number 10. Our original Future still contains the string "HelloWorld" and is unaffected by the 'map'.

Something to note when using these methods: if the Future is still being processed when one of these methods are called, it will be the completing thread that actually does the work. If the Future is already complete though, it will be run in our current thread. For example:

.. code-block:: scala

  val f1 = Future {
    Thread.sleep(1000)
    "Hello" + "World"
  }

  val f2 = f1 map { x =>
    x.length
  }

  val result = fs()

The original Future will take at least 1 second to execute due to sleep, which means it is still being processed at the time we call 'map'. The Function we provide gets stored within the Future and later executed by the dispatcher when the result is ready.

If we do the opposite:

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  Thread.sleep(1000)

  val f2 = f1 map { x =>
     x.length
  }

  val result = fs()

Our little string has been processed long before our 1 second sleep has finished. Because of this, the dispatcher has moved onto other messages that need processing and can no longer calculate the length of the string for us, instead it gets calculated in the current thread just as if we weren't using a Future.

Normally this works quite well for us as it means there is very little overhead to running a quick Function. If there is a possiblity of the Function taking a non-trivial amount of time to process it might be better to have this done concurrently, and for that we use 'flatMap':

.. code-block:: scala

  val f1 = Future {
    "Hello" + "World"
  }

  val f2 = f1 flatMap {x =>
    Future(x.length)
  }

  val result = fs()

Now our second Future is executed concurrently as well. This technique can also be used to combine the results of several Futures into a single calculation, which will be better explained in the following sections.

For Comprehensions
^^^^^^^^^^^^^^^^^^

Since Future has a 'map' and 'flatMap' method it can be easily used in a for comprehension:

.. code-block:: scala

  val f = for {
    a <- Future(10 / 2) // 10 / 2 = 5
    b <- Future(a + 1)  //  5 + 1 = 6
    c <- Future(a - 1)  //  5 - 1 = 4
  } yield b * c         //  6 * 4 = 24

  val result = f()

Something to keep in mind when doing this is even though it looks like parts of the above example can run in parallel, each step of the for comprehension is run sequentially. This will happen on separate threads for each step but there isn't much benefit over running the calculations all within a single Future. The real benefit comes when the Futures are created first, and then combining them together.

Composing Futures
^^^^^^^^^^^^^^^^^

The example for comprehension above is an example of composing Futures. A common use case for this is combining the replies of several Actors into a single calculation without resorting to calling 'await' to block for each result. For example:

.. code-block:: scala

  val f1 = actor1 !!! msg1
  val f2 = actor2 !!! msg2

  val f3 = for {
    a: Int    <- f1
    b: Int    <- f2
    c: String <- actor3 !!! (a + b)
  } yield c

  val result = f3()

Here we have 2 actors processing a single message each. In the for comprehension we need to add the expected types in order to work with the results. Once the 2 results are available, they are being added together and sent to a third actor, which replies with a String, which we assign to 'result'.

This is fine when dealing with a known amount of Actors, but can grow unwieldly if we have more then a handful. The 'sequence' and 'traverse' helper methods can make it easier to handle more complex use cases. Both of these methods are ways of turning a Traversable[Future[A]] into a Future[Traversable[A]]. For example:

.. code-block:: scala

  // oddActor returns odd numbers sequentially from 1
  val listOfFutures: List[Future[Int]] = List.fill(100)(oddActor !!! GetNext)

  // now we have a Future[List[Int]]
  val futureList = Futures.sequence(listOfFutures)

  // Find the sum of the odd numbers
  val oddSum = futureList.map(_.sum).apply

To better explain what happened in the example, Futures.sequence is taking the List[Future[Int]] and turning it into a Future[List[Int]]. We can then use 'map' to work with the List[Int] directly, and we find the sum of the List.

The 'traverse' method is similar to 'sequence', but it takes a Traversable[A] and a Function T => Future[B] to return a Future[Traversable[B]]. For example, to use 'traverse' to sum the first 100 odd numbers:

.. code-block:: scala

  val oddSum = Futures.traverse((1 to 100).toList)(x => Future(x * 2 - 1)).map(_.sum).apply

This is the same result as this example:

.. code-block:: scala

  val oddSum = Futures.sequence((1 to 100).toList.map(x => Future(x * 2 - 1))).map(_.sum).apply

But it may be faster to use 'traverse' as it doesn't have to create an intermediate List[Future[Int]].

This is just a sample of what can be done, but to use more advanced techniques it is easier to take advantage of Scalaz, which Akka has support for in it's akka-scalaz module.

Scalaz
^^^^^^

Akka also has a `Scalaz module <scalaz>`_ for a more complete support of programming in a functional style.

Exceptions (TODO)
-----------------

Handling exceptions.

Fine Tuning (TODO)
------------------

Dispatchers and timeouts
