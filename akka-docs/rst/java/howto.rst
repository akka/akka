
.. _howto-java:

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

You might find some of the patterns described in the Scala chapter of 
:ref:`howto-scala` useful even though the example code is written in Scala.

Scheduling Periodic Messages
============================

This pattern describes how to schedule periodic messages to yourself in two different
ways.

The first way is to set up periodic message scheduling in the constructor of the actor,
and cancel that scheduled sending in ``postStop`` or else we might have multiple registered
message sends to the same actor.

.. note::

   With this approach the scheduled periodic message send will be restarted with the actor on restarts.
   This also means that the time period that elapses between two tick messages during a restart may drift
   off based on when you restart the scheduled message sends relative to the time that the last message was
   sent, and how long the initial delay is. Worst case scenario is ``interval`` plus ``initialDelay``.

.. includecode:: code/docs/pattern/SchedulerPatternTest.java#schedule-constructor

The second variant sets up an initial one shot message send in the ``preStart`` method
of the actor, and the then the actor when it receives this message sets up a new one shot
message send. You also have to override ``postRestart`` so we don't call ``preStart``
and schedule the initial message send again.

.. note::

   With this approach we won't fill up the mailbox with tick messages if the actor is
   under pressure, but only schedule a new tick message when we have seen the previous one.

.. includecode:: code/docs/pattern/SchedulerPatternTest.java#schedule-receive

Template Pattern
================

*Contributed by: N. N.*

This is an especially nice pattern, since it does even come with some empty example code:

.. includecode:: code/docs/pattern/JavaTemplate.java
   :include: all-of-it
   :exclude: uninteresting-stuff

.. note::

   Spread the word: this is the easiest way to get famous!

Please keep this pattern at the end of this file.
