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

Single-Use Actor Trees with High-Level Error Reporting
======================================================

*Contributed by: Rick Latrine*

A nice way to enter the actor world from java is the use of Patterns.ask().
This method starts a temporary actor to forward the message and collect the result from the actor to be "asked".
In case of errors within the asked actor the default supervision handling will take over.
The caller of Patterns.ask() will not be notified.

If that caller is interested in such an exception, he must make sure that the asked actor replies with Status.Failure(Throwable).
Behind the asked actor a complex actor hierarchy might be spawned to accomplish asynchronous work.
Then supervision is the established way to control error handling.

Unfortunately the asked actor must know about supervision and must catch the exceptions.
Such an actor is unlikely to be reused in a different actor hierarchy and contains crippled try/catch blocks.

This pattern provides a way to encapsulate supervision and error propagation to the temporary actor.
Finally the promise returned by Patterns.ask() is fulfilled as a failure, including the exception.

Let's have a look at the example code:

.. includecode:: code/docs/pattern/SupervisedAsk.java

In the askOf method the SupervisorCreator is sent the user message.
The SupervisorCreator creates a SupervisorActor and forwards the message.
This prevents the actor system from overloading due to actor creations.
The SupervisorActor is responsible to create the user actor, forwards the message, handles actor termination and supervision.
Additionally the SupervisorActor stops the user actor if execution time expired.

In case of an exception the supervisor tells the temporary actor which exception was thrown.
Afterwards the actor hierarchy is stopped.

Finally we are able to execute an actor and receive the results or exceptions.


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

