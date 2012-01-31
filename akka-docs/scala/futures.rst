.. _futures-scala:

Futures (Scala)
===============

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

In Akka, a `Future <http://en.wikipedia.org/wiki/Futures_and_promises>`_ is a data structure used to
retrieve the result of some concurrent operation. This operation is usually performed by an ``Actor``
or by the ``Dispatcher`` directly. This result can be accessed synchronously (blocking) or asynchronously (non-blocking).

Use with Actors
---------------

There are generally two ways of getting a reply from an ``Actor``: the first is by a sent message (``actor ! msg``),
which only works if the original sender was an ``Actor``) and the second is through a ``Future``.

Using an ``Actor``\'s ``?`` method to send a message will return a Future. To wait for and retrieve the actual result the simplest method is:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: ask-blocking

This will cause the current thread to block and wait for the ``Actor`` to 'complete' the ``Future`` with it's reply.
Blocking is discouraged though as it will cause performance problems.
The blocking operations are located in ``Await.result`` and ``Await.ready`` to make it easy to spot where blocking occurs.
Alternatives to blocking are discussed further within this documentation. Also note that the ``Future`` returned by
an ``Actor`` is a ``Future[Any]`` since an ``Actor`` is dynamic. That is why the ``asInstanceOf`` is used in the above sample.
When using non-blocking it is better to use the ``mapTo`` method to safely try to cast a ``Future`` to an expected type:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: map-to

The ``mapTo`` method will return a new ``Future`` that contains the result if the cast was successful,
or a ``ClassCastException`` if not. Handling ``Exception``\s will be discussed further within this documentation.

Use Directly
------------

A common use case within Akka is to have some computation performed concurrently without needing the extra utility of an ``Actor``.
If you find yourself creating a pool of ``Actor``\s for the sole reason of performing a calculation in parallel,
there is an easier (and faster) way:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: future-eval

In the above code the block passed to ``Future`` will be executed by the default ``Dispatcher``,
with the return value of the block used to complete the ``Future`` (in this case, the result would be the string: "HelloWorld").
Unlike a ``Future`` that is returned from an ``Actor``, this ``Future`` is properly typed,
and we also avoid the overhead of managing an ``Actor``.

You can also create already completed Futures using the ``Promise`` companion, which can be either successes:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: successful

Or failures:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: failed

Functional Futures
------------------

Akka's ``Future`` has several monadic methods that are very similar to the ones used by Scala's collections.
These allow you to create 'pipelines' or 'streams' that the result will travel through.

Future is a Monad
^^^^^^^^^^^^^^^^^

The first method for working with ``Future`` functionally is ``map``. This method takes a ``Function``
which performs some operation on the result of the ``Future``, and returning a new result.
The return value of the ``map`` method is another ``Future`` that will contain the new result:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: map

In this example we are joining two strings together within a ``Future``. Instead of waiting for this to complete,
we apply our function that calculates the length of the string using the ``map`` method.
Now we have a second ``Future`` that will eventually contain an ``Int``.
When our original ``Future`` completes, it will also apply our function and complete the second ``Future`` with its result.
When we finally get the result, it will contain the number 10. Our original ``Future`` still contains the
string "HelloWorld" and is unaffected by the ``map``.

The ``map`` method is fine if we are modifying a single ``Future``,
but if 2 or more ``Future``\s are involved ``map`` will not allow you to combine them together:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: wrong-nested-map

``f3`` is a ``Future[Future[Int]]`` instead of the desired ``Future[Int]``. Instead, the ``flatMap`` method should be used:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: flat-map

If you need to do conditional propagation, you can use ``filter``:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: filter

For Comprehensions
^^^^^^^^^^^^^^^^^^

Since ``Future`` has a ``map``, ``filter` and ``flatMap`` method it can be easily used in a 'for comprehension':

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: for-comprehension

Something to keep in mind when doing this is even though it looks like parts of the above example can run in parallel,
each step of the for comprehension is run sequentially. This will happen on separate threads for each step but
there isn't much benefit over running the calculations all within a single ``Future``.
The real benefit comes when the ``Future``\s are created first, and then combining them together.

Composing Futures
^^^^^^^^^^^^^^^^^

The example for comprehension above is an example of composing ``Future``\s.
A common use case for this is combining the replies of several ``Actor``\s into a single calculation
without resorting to calling ``Await.result`` or ``Await.ready`` to block for each result.
First an example of using ``Await.result``:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: composing-wrong

Here we wait for the results from the first 2 ``Actor``\s before sending that result to the third ``Actor``.
We called ``Await.result`` 3 times, which caused our little program to block 3 times before getting our final result.
Now compare that to this example:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: composing

Here we have 2 actors processing a single message each. Once the 2 results are available
(note that we don't block to get these results!), they are being added together and sent to a third ``Actor``,
which replies with a string, which we assign to 'result'.

This is fine when dealing with a known amount of Actors, but can grow unwieldy if we have more then a handful.
The ``sequence`` and ``traverse`` helper methods can make it easier to handle more complex use cases.
Both of these methods are ways of turning, for a subclass ``T`` of ``Traversable``, ``T[Future[A]]`` into a ``Future[T[A]]``.
For example:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: sequence-ask

To better explain what happened in the example, ``Future.sequence`` is taking the ``List[Future[Int]]``
and turning it into a ``Future[List[Int]]``. We can then use ``map`` to work with the ``List[Int]`` directly, a
nd we find the sum of the ``List``.

The ``traverse`` method is similar to ``sequence``, but it takes a ``T[A]`` and a function ``A => Future[B]`` to return a ``Future[T[B]]``,
where ``T`` is again a subclass of Traversable. For example, to use ``traverse`` to sum the first 100 odd numbers:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: traverse

This is the same result as this example:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: sequence

But it may be faster to use ``traverse`` as it doesn't have to create an intermediate ``List[Future[Int]]``.

Then there's a method that's called ``fold`` that takes a start-value, a sequence of ``Future``\s and a function
from the type of the start-value and the type of the futures and returns something with the same type as the start-value,
and then applies the function to all elements in the sequence of futures, asynchronously,
the execution will start when the last of the Futures is completed.

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: fold

That's all it takes!


If the sequence passed to ``fold`` is empty, it will return the start-value, in the case above, that will be 0.
In some cases you don't have a start-value and you're able to use the value of the first completing Future in the sequence
as the start-value, you can use ``reduce``, it works like this:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: reduce

Same as with ``fold``, the execution will be done asynchronously when the last of the Future is completed,`
you can also parallelize it by chunking your futures into sub-sequences and reduce them, and then reduce the reduced results again.

Callbacks
---------

Sometimes you just want to listen to a ``Future`` being completed, and react to that not by creating a new Future, but by side-effecting.
For this Akka supports ``onComplete``, ``onSuccess`` and ``onFailure``, of which the latter two are specializations of the first.

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: onSuccess

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: onFailure

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: onComplete

Ordering
--------

Since callbacks are executed in any order and potentially in parallel,
it can be tricky at the times when you need sequential ordering of operations.
But there's a solution! And it's name is ``andThen``, and it creates a new Future with
the specified callback, a Future that will have the same result as the Future it's called on,
which allows for ordering like in the following sample:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: and-then

Auxiliary methods
-----------------

``Future`` ``fallbackTo`` combines 2 Futures into a new ``Future``, and will hold the successful value of the second ``Future`
if the first ``Future`` fails.

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: fallback-to

You can also combine two Futures into a new ``Future`` that will hold a tuple of the two Futures successful results,
using the ``zip`` operation.

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: zip

Exceptions
----------

Since the result of a ``Future`` is created concurrently to the rest of the program, exceptions must be handled differently.
It doesn't matter if an ``Actor`` or the dispatcher is completing the ``Future``,
if an ``Exception`` is caught the ``Future`` will contain it instead of a valid result.
If a ``Future`` does contain an ``Exception``, calling ``Await.result`` will cause it to be thrown again so it can be handled properly.

It is also possible to handle an ``Exception`` by returning a different result.
This is done with the ``recover`` method. For example:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: recover

In this example, if the actor replied with a ``akka.actor.Status.Failure`` containing the ``ArithmeticException``,
our ``Future`` would have a result of 0. The ``recover`` method works very similarly to the standard try/catch blocks,
so multiple ``Exception``\s can be handled in this manner, and if an ``Exception`` is not handled this way
it will behave as if we hadn't used the ``recover`` method.

You can also use the ``tryRecover`` method, which has the same relationship to ``recover`` as ``flatMap` has to ``map``,
and is use like this:

.. includecode:: code/akka/docs/future/FutureDocSpec.scala
   :include: try-recover

