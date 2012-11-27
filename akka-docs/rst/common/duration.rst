.. _Duration:

########
Duration
########

Durations are used throughout the Akka library, wherefore this concept is
represented by a special data type, :class:`scala.concurrent.duration.Duration`.
Values of this type may represent infinite (:obj:`Duration.Inf`,
:obj:`Duration.MinusInf`) or finite durations, or be :obj:`Duration.Undefined`.

Finite vs. Infinite
===================

Since trying to convert an infinite duration into a concrete time unit like
seconds will throw an exception, there are different types available for
distinguishing the two kinds at compile time:

* :class:`FiniteDuration` is guaranteed to be finite, calling :meth:`toNanos`
  and friends is safe
* :class:`Duration` can be finite or infinite, so this type should only be used
  when finite-ness does not matter; this is a supertype of :class:`FiniteDuration`

Scala
=====

In Scala durations are constructable using a mini-DSL and support all expected
arithmetic operations:

.. includecode:: code/docs/duration/Sample.scala#dsl

.. note::

   You may leave out the dot if the expression is clearly delimited (e.g.
   within parentheses or in an argument list), but it is recommended to use it
   if the time unit is the last token on a line, otherwise semi-colon inference
   might go wrong, depending on what starts the next line.

Java
====

Java provides less syntactic sugar, so you have to spell out the operations as
method calls instead:

.. includecode:: code/docs/duration/Java.java#import
.. includecode:: code/docs/duration/Java.java#dsl

Deadline
========

Durations have a brother named :class:`Deadline`, which is a class holding a representation
of an absolute point in time, and support deriving a duration from this by calculating the
difference between now and the deadline. This is useful when you want to keep one overall
deadline without having to take care of the book-keeping wrt. the passing of time yourself:

.. includecode:: code/docs/duration/Sample.scala#deadline

In Java you create these from durations:

.. includecode:: code/docs/duration/Java.java#deadline
