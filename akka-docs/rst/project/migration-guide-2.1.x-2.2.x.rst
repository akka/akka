.. _migration-2.2:

################################
 Migration Guide 2.1.x to 2.2.x
################################

The 2.2 release contains several structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from 1.3.x to 2.1.x you should first follow the instructions for
migrating `1.3.x to 2.0.x <http://doc.akka.io/docs/akka/2.0.3/project/migration-guide-1.3.x-2.0.x.html>`_ and then :ref:`2.0.x to 2.1.x <migration-2.1>`.

Immutable everywhere
====================

Akka has in 2.2 been refactored to require ``scala.collection.immutable`` data structures as much as possible,
this leads to fewer bugs and more opportunity for sharing data safely.

==================================== ====================================
Search                               Replace with
==================================== ====================================
``akka.japi.Util.arrayToSeq``          ``akka.japi.Util.immutableSeq``
==================================== ====================================

If you need to convert from Java to ``scala.collection.immutable.Seq`` or ``scala.collection.immutable.Iterable`` you should use ``akka.japi.Util.immutableSeq(…)``,
and if you need to convert from Scala you can simply switch to using immutable collections yourself or use the ``to[immutable.<collection-type>]`` method.

API changes to FSM and TestFSMRef
=================================

The ``timerActive_?`` method has been deprecated in both the ``FSM`` trait and the ``TestFSMRef``
class. You should now use the ``isTimerActive`` method instead. The old method will remain
throughout 2.2.x. It will be removed in Akka 2.3.