#########
Utilities
#########

.. sidebar:: Contents

   .. contents:: :local:

This section of the manual describes miscellaneous utilities which are provided
by Akka and used in multiple places.

.. _Duration:

Duration
========

Durations are used throughout the Akka library, wherefore this concept is
represented by a special data type, :class:`Duration`. Values of this type may
represent infinite (:obj:`Duration.Inf`, :obj:`Duration.MinusInf`) or finite
durations, where the latter are constructable using a mini-DSL:

.. code-block:: scala

   import akka.util.duration._   // notice the small d

   val fivesec = 5.seconds
   val threemillis = 3.millis
   val diff = fivesec - threemillis
   assert (diff < fivesec)

.. note::

   You may leave out the dot if the expression is clearly delimited (e.g.
   within parentheses or in an argument list), but it is recommended to use it
   if the time unit is the last token on a line, otherwise semi-colon inference
   might go wrong, depending on what starts the next line.

Java provides less syntactic sugar, so you have to spell out the operations as
method calls instead:

.. code-block:: java

   final Duration fivesec = Duration.create(5, "seconds");
   final Duration threemillis = Duration.parse("3 millis");
   final Duration diff = fivesec.minus(threemillis);
   assert (diff.lt(fivesec));
   assert (Duration.Zero().lt(Duration.Inf()));


