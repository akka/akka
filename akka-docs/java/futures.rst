.. _futures-java:

Futures (Java)
===============

.. sidebar:: Contents

   .. contents:: :local:

Introduction
------------

In Akka, a `Future <http://en.wikipedia.org/wiki/Futures_and_promises>`_ is a data structure used to retrieve the result of some concurrent operation. This operation is usually performed by an ``Actor`` or by the ``Dispatcher`` directly. This result can be accessed synchronously (blocking) or asynchronously (non-blocking).

Use with Actors
---------------

There are generally two ways of getting a reply from an ``UntypedActor``: the first is by a sent message (``actorRef.tell(msg);``), which only works if the original sender was an ``UntypedActor``) and the second is through a ``Future``.

Using the ``ActorRef``\'s ``sendRequestReplyFuture`` method to send a message will return a Future. To wait for and retrieve the actual result the simplest method is:

.. code-block:: java

  Future[Object] future = actorRef.sendRequestReplyFuture[Object](msg);
  Object result = future.get(); //Block until result is available, usually bad practice

This will cause the current thread to block and wait for the ``UntypedActor`` to 'complete' the ``Future`` with it's reply. Due to the dynamic nature of Akka's ``UntypedActor``\s this result can be anything. The safest way to deal with this is to specify the result to an ``Object`` as is shown in the above example. You can also use the expected result type instead of ``Any``, but if an unexpected type were to be returned you will get a ``ClassCastException``. For more elegant ways to deal with this and to use the result without blocking, refer to `Functional Futures`_.

Use Directly
------------

A common use case within Akka is to have some computation performed concurrently without needing the extra utility of an ``UntypedActor``. If you find yourself creating a pool of ``UntypedActor``\s for the sole reason of performing a calculation in parallel, there is an easier (and faster) way:

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.future;
  import java.util.concurrent.Callable;

  Future<String> f = future(new Callable<String>() {
                            public String call() {
                              return "Hello" + "World!";
                            }
                          });
  String result = f.get(); //Blocks until timeout, default timeout is set in akka.conf, otherwise 5 seconds

In the above code the block passed to ``future`` will be executed by the default ``Dispatcher``, with the return value of the block used to complete the ``Future`` (in this case, the result would be the string: "HelloWorld"). Unlike a ``Future`` that is returned from an ``UntypedActor``, this ``Future`` is properly typed, and we also avoid the overhead of managing an ``UntypedActor``.

Functional Futures
------------------

A recent addition to Akka's ``Future`` is several monadic methods that are very similar to the ones used by ``Scala``'s collections. These allow you to create 'pipelines' or 'streams' that the result will travel through.

Future is a Monad
^^^^^^^^^^^^^^^^^

The first method for working with ``Future`` functionally is ``map``. This method takes a ``Function`` which performs some operation on the result of the ``Future``, and returning a new result. The return value of the ``map`` method is another ``Future`` that will contain the new result:

.. code-block:: java

  import akka.dispatch.Future; 
  import static akka.dispatch.Futures.future;
  import static akka.japi.Function;
  import java.util.concurrent.Callable;

  Future<String> f1 = future(new Callable<String>() {
                        public String call() {
                            return "Hello" + "World";
                        }
                    });

  Future<Integer> f2 = f1.map(new Function<String, Integer>() {
                        public Integer apply(String s) {
                            return s.length();
                        }
                    });

  Integer result = f2.get();

In this example we are joining two strings together within a Future. Instead of waiting for f1 to complete, we apply our function that calculates the length of the string using the ``map`` method. Now we have a second Future, f2, that will eventually contain an ``Integer``. When our original ``Future``, f1, completes, it will also apply our function and complete the second Future with it's result. When we finally ``get`` the result, it will contain the number 10. Our original Future still contains the string "HelloWorld" and is unaffected by the ``map``.

Something to note when using these methods: if the ``Future`` is still being processed when one of these methods are called, it will be the completing thread that actually does the work. If the ``Future`` is already complete though, it will be run in our current thread. For example:

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.future;
  import static akka.japi.Function;
  import java.util.concurrent.Callable;

  Future<String> f1 = future(new Callable<String>() {
                        public String call() {
                            Thread.sleep(1000);
                            return "Hello" + "World";
                        }
                    });

  Future<Integer> f2 = f1.map(new Function<String, Integer>() {
                        public Integer apply(String s) {
                            return s.length();
                        }
                    });

  Integer result = f2.get();

The original ``Future`` will take at least 1 second to execute now, which means it is still being processed at the time we call ``map``. The function we provide gets stored within the ``Future`` and later executed automatically by the dispatcher when the result is ready.

If we do the opposite:

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.future;
  import static akka.japi.Function;
  import java.util.concurrent.Callable;

  Future<String> f1 = future(new Callable<String>() {
                        public String call() {
                            return "Hello" + "World";
                        }
                    });

  Thread.sleep(1000);

  Future<Integer> f2 = f1.map(new Function<String, Integer>() {
                        public Integer apply(String s) {
                            return s.length();
                        }
                    });

  Integer result = f2.get();

Our little string has been processed long before our 1 second sleep has finished. Because of this, the dispatcher has moved onto other messages that need processing and can no longer calculate the length of the string for us, instead it gets calculated in the current thread just as if we weren't using a ``Future``.

Normally this works quite well as it means there is very little overhead to running a quick function. If there is a possibility of the function taking a non-trivial amount of time to process it might be better to have this done concurrently, and for that we use ``flatMap``:

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.future;
  import static akka.japi.Function;
  import java.util.concurrent.Callable;

  Future<String> f1 = future(new Callable<String>() {
                        public String call() {
                            return "Hello" + "World";
                        }
                    });

  Future<Integer> f2 = f1.flatMap(new Function<String, Future<Integer>>() {
                         public Future<Integer> apply(final String s) {
                             return future(
                                new Callable<Integer>() {
                                    public Integer call() {
                                       return s.length();
                                    }
                               });
                         }
                    });

  Integer result = f2.get();

Now our second Future is executed concurrently as well. This technique can also be used to combine the results of several Futures into a single calculation, which will be better explained in the following sections.

Composing Futures
^^^^^^^^^^^^^^^^^

It is very often desirable to be able to combine different Futures with eachother, below are some examples on how that can be done in a non-blocking fashion.

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.sequence;
  import akka.japi.Function;
  import java.lang.Iterable;

  Iterable<Future<Integer>> listOfFutureInts = ... //Some source generating a sequence of Future<Integer>:s

  // now we have a Future[Iterable[Int]]
  Future<Iterable<Integer>> futureListOfInts = sequence(listOfFutureInts);

  // Find the sum of the odd numbers
  Long totalSum = futureListOfInts.map(
      new Function<LinkedList<Integer>, Long>() {
          public Long apply(LinkedList<Integer> ints) {
              long sum = 0;
              for(Integer i : ints)
                sum += i;
              return sum;
          }
      }).get();

To better explain what happened in the example, ``Future.sequence`` is taking the ``Iterable<Future<Integer>>`` and turning it into a ``Future<Iterable<Integer>>``. We can then use ``map`` to work with the ``Iterable<Integer>`` directly, and we aggregate the sum of the ``Iterable``.

The ``traverse`` method is similar to ``sequence``, but it takes a sequence of ``A``s and applies a function from ``A`` to ``Future<B>`` and returns a ``Future<Iterable<B>>``, enabling parallel ``map`` over the sequence, if you use ``Futures.future`` to create the ``Future``.

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.traverse;
  import static akka.dispatch.Futures.future;
  import java.lang.Iterable;
  import akka.japi.Function;

  Iterable<String> listStrings = ... //Just a sequence of Strings

  Future<Iterable<String>> result = traverse(listStrings, new Function<String,Future<String>>(){
          public Future<String> apply(final String r) {
              return future(new Callable<String>() {
                        public String call() {
                            return r.toUpperCase();
                        }
                    });
          }
        });

  result.get(); //Returns the sequence of strings as upper case

It's as simple as that!

Then there's a method that's called ``fold`` that takes a start-value, a sequence of ``Future``:s and a function from the type of the start-value, a timeout, and the type of the futures and returns something with the same type as the start-value, and then applies the function to all elements in the sequence of futures, non-blockingly, the execution will run on the Thread of the last completing Future in the sequence.

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.fold;
  import java.lang.Iterable;
  import akka.japi.Function2;

  Iterable<Future<String>> futures = ... //A sequence of Futures, in this case Strings

  Future<String> result = fold("", 15000, futures, new Function2<String, String, String>(){ //Start value is the empty string, timeout is 15 seconds
          public String apply(String r, String t) {
              return r + t; //Just concatenate
          }
        });
  
  result.get(); // Will produce a String that says "testtesttesttest"(... and so on).

That's all it takes!


If the sequence passed to ``fold`` is empty, it will return the start-value, in the case above, that will be 0. In some cases you don't have a start-value and you're able to use the value of the first completing Future in the sequence as the start-value, you can use ``reduce``, it works like this:

.. code-block:: java

  import akka.dispatch.Future;
  import static akka.dispatch.Futures.reduce;
  import java.util.Iterable;
  import akka.japi.Function2;

  Iterable<Future<String>> futures = ... //A sequence of Futures, in this case Strings

  Future<String> result = reduce(futures, 15000, new Function2<String, String, String>(){ //Timeout is 15 seconds
          public String apply(String r, String t) {
              return r + t; //Just concatenate
          }
        });
  
  result.get(); // Will produce a String that says "testtesttesttest"(... and so on).

Same as with ``fold``, the execution will be done by the Thread that completes the last of the Futures, you can also parallize it by chunking your futures into sub-sequences and reduce them, and then reduce the reduced results again.

This is just a sample of what can be done.

Exceptions
----------

Since the result of a ``Future`` is created concurrently to the rest of the program, exceptions must be handled differently. It doesn't matter if an ``UntypedActor`` or the dispatcher is completing the ``Future``, if an ``Exception`` is caught the ``Future`` will contain it instead of a valid result. If a ``Future`` does contain an ``Exception``, calling ``get`` will cause it to be thrown again so it can be handled properly.

Cancelation
-----------

In case the result of a Future is not needed any more, it may be completed with
a :class:`FutureCanceledException` by calling ``Future.cancel()``, thus
inhibiting later normal completion (unless it was already completed).  While
this action does *not* cancel the computation of the ``Future`` value, it will
prevent the invokation of registered ``onComplete`` callbacks with that value,
hence possibly aborting a processing chain; it does nothing, however, it the
``Future`` already has been completed.

.. code-block:: java

  Future<String> future = ...
  ...
  future.cancel();

If applied to a composite ``Future``, i.e. one returned by the
``firstCompletedOf``, ``fold``, ``sequence`` or ``reduce`` methods on the
``Futures`` class, it will cancel all contained future, with
the natural effect an exception would have for that composition.

This method is probably most useful in ``onTimeout`` callbacks.


