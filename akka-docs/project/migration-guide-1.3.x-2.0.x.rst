.. _migration-2.0:

################################
 Migration Guide 1.3.x to 2.0.x
################################

Actors
======

The 2.0 release contains several new features which require source-level
changes in client code. This API cleanup is planned to be the last one for a
significant amount of time.

Detailed migration guide will be written.

Unordered Collection of Migration Items
=======================================

``ActorRef.ask()``
------------------

The mechanism for collecting an actor’s reply in a :class:`Future` has been
reworked for better location transparency: it uses an actor under the hood.
This actor needs to be disposable by the garbage collector in case no reply is
ever received, and the decision is based upon a timeout. This timeout
determines when the actor will stop itself and hence closes the window for a
reply to be received; it is independent of the timeout applied when awaiting
completion of the :class:`Future`, however, the actor will complete the
:class:`Future` with an :class:`AskTimeoutException` when it stops itself.

ActorPool
---------

The ActorPool has been replaced by dynamically resizable routers.
