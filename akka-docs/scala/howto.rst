
.. _howto-scala:

######################
HowTo: Common Patterns
######################

This section lists common actor patterns which have been found to be useful,
elegant or instructive. Anything is welcome, example topics being message
routing strategies, supervision patterns, restart handling, etc. As a special
bonus, additions to this section are marked with the contributor’s name, and it
would be nice if every Akka user who finds a recurring pattern in his or her
code could share it for the profit of all. Where applicable it might also make
sense to add to the ``akka.pattern`` package for creating an `OTP-like library
<http://www.erlang.org/doc/man_index.html>`_.

Throttling Messages
===================

Contributed by: Kaspar Fischer

A message throttler that ensures that messages are not sent out at too high a rate.

The pattern is described `here <http://letitcrash.com/post/28901663062/throttling-messages-in-akka-2>`_.

Balancing Workload Across Nodes
===============================

Contributed by: Derek Wyatt

Often times, people want the functionality of the BalancingDispatcher with the 
stipulation that the Actors doing the work have distinct Mailboxes on remote 
nodes. In this post we’ll explore the implementation of such a concept.

The pattern is described `here <http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2>`_.

Ordered Termination
===================

Contributed by: Derek Wyatt

When an Actor stops, its children stop in an undefined order. Child termination is 
asynchronous and thus non-deterministic.

If an Actor has children that have order dependencies, then you might need to ensure 
a particular shutdown order of those children so that their postStop() methods get 
called in the right order.

The pattern is described `here <http://letitcrash.com/post/29773618510/an-akka-2-terminator>`_.

Template Pattern
================

*Contributed by: N. N.*

This is an especially nice pattern, since it does even come with some empty example code:

.. includecode:: code/docs/pattern/ScalaTemplate.scala
   :include: all-of-it
   :exclude: uninteresting-stuff

.. note::

   Spread the word: this is the easiest way to get famous!

Please keep this pattern at the end of this file.

