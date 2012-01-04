.. _migration-2.0:

################################
 Migration Guide 1.3.x to 2.0.x
################################

.. sidebar:: Contents

   .. contents:: :local:

Actors
======

The 2.0 release contains several new features which require source-level
changes in client code. This API cleanup is planned to be the last one for a
significant amount of time.

Detailed migration guide will be written.

Migration Kit
=============

Nobody likes a big refactoring that takes several days to complete until
anything is able to run again. Therefore we provide a migration kit that
makes it possible to do the migration changes in smaller steps.

The migration kit only covers the most common usage of Akka. It is not intended
as a final solution. The whole migration kit is deprecated and will be removed in
Akka 2.1.

The migration kit is provided in separate jar files. Add the following dependency::

  "com.typesafe.akka" % "akka-actor-migration" % "2.0-SNAPSHOT"

The first step of the migration is to do some trivial replacements.
Search and replace the following:

==================================== ====================================
Search                               Replace with
==================================== ====================================
``akka.actor.Actor``                 ``akka.actor.OldActor``
``extends Actor``                    ``extends OldActor``
``akka.actor.Scheduler``             ``akka.actor.OldScheduler``
``akka.event.EventHandler``          ``akka.event.OldEventHandler``
``akka.config.Config``               ``akka.config.OldConfig``
==================================== ====================================

For Scala users the migration kit also contains some implicit conversions to be
able to use some old methods. These conversions are useful from tests or other
code used outside actors.

::

  import akka.migration._

Thereafter you need to fix compilation errors that are not handled by the migration
kit, such as:

* Definition of supervisors
* Definition of dispatchers
* ActorRegistry

When everything compiles you continue by replacing/removing the ``OldXxx`` classes
one-by-one from the migration kit with appropriate migration.

When using the migration kit there will be one global actor system, which loads
the configuration ``akka.conf`` from the same locations as in Akka 1.x.
This means that while you are using the migration kit you should not create your
own ``ActorSystem``, but instead use the ``akka.actor.GlobalActorSystem``. Last
task of the migration would be to create your own ``ActorSystem``.



Unordered Collection of Migration Items
=======================================

``ActorRef.start()``
--------------------

``ActorRef.start()`` has been removed. Actors are now started automatically when created.
Remove all invocations of ``ActorRef.start()``.
There will be compilation errors for this.

``ActorRef.stop()``
--------------------

``ActorRef.start()`` has been moved. Use ``ActorSystem`` or ``ActorContext`` to stop actors.
There will be compilation errors for this.

``Channel``
-----------

``self.channel`` has been replaced with unified reply mechanism using ``sender`` (Scala)
or ``getSender()`` (Java).
There will be compilation errors for this.

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

``UntypedActor.getContext()``
-----------------------------

``getContext()`` and ``context()`` in the Java API for UntypedActor is renamed to
``getSelf()``.
