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

ActorContext & ActorRefFactory dispatcher
=========================================

The return type of ``ActorContext``'s and ``ActorRefFactory``'s ``dispatcher``-method now returns ``ExecutionContext`` instead of ``MessageDispatcher``.


API changes to FSM and TestFSMRef
=================================

The ``timerActive_?`` method has been deprecated in both the ``FSM`` trait and the ``TestFSMRef``
class. You should now use the ``isTimerActive`` method instead. The old method will remain
throughout 2.2.x. It will be removed in Akka 2.3.


ThreadPoolConfigBuilder
=======================

``akka.dispatch.ThreadPoolConfigBuilder`` companion object has been removed,
and with it the ``conf_?`` method that was essentially only a type-inferencer aid for creation
of optional transformations on ``ThreadPoolConfigBuilder``.
Instead use: ``option.map(o => (t: ThreadPoolConfigBuilder) => t.op(o))``.

Scheduler
=========

Akka's ``Scheduler`` has been augmented to also include a ``sender`` when scheduling to send messages, this should work Out-Of-The-Box for Scala users,
but for Java Users you will need to manually provide the ``sender`` – as usual use ``null`` to designate "no sender" which will behave just as before the change.

ZeroMQ ByteString
=================

``akka.zeromq.Frame`` and the use of ``Seq[Byte]`` in the API has been removed and is replaced by ``akka.util.ByteString``.

``ZMQMessage.firstFrameAsString`` has been removed, please use ``ZMQMessage.frames`` or ``ZMQMessage.frame(int)`` to access the frames.

Brand new Agents
================

Akka's ``Agent`` has been rewritten to improve the API and to remove the need to manually ``close`` an Agent.
The Java API has also been harmonized so both Java and Scala call the same methods.

======================================================= =======================================================
Old Java API                                            New Java API
======================================================= =======================================================
``new Agent<type>(value, actorSystem)``                   ``new Agent<type>(value, executionContext)``
``agent.update(newValue)``                                ``agent.send(newValue)``
``agent.future(Timeout)``                                 ``agent.future()``
``agent.await(Timeout)``                                  ``Await.result(agent.future(), Timeout)``
``agent.send(Function)``                                  ``agent.send(Mapper)``
``agent.sendOff(Function, ExecutionContext)``             ``agent.sendOff(Mapper, ExecutionContext)``
``agent.alter(Function, Timeout)``                        ``agent.alter(Mapper)``
``agent.alterOff(Function, Timeout, ExecutionContext)``   ``agent.alter(Mapper, ExecutionContext)``
``agent.map(Function)``                                   ``agent.map(Mapper)``
``agent.flatMap(Function)``                               ``agent.flatMap(Mapper)``
``agent.foreach(Procedure)``                              ``agent.foreach(Foreach)``
``agent.suspend()``                                       ``No replacement, pointless feature``
``agent.resume()``                                        ``No replacement, pointless feature``
``agent.close()``                                         ``No replacement, not needed in new implementation``
======================================================= =======================================================


======================================================== ========================================================
Old Scala API                                            New Scala API
======================================================== ========================================================
``Agent[T](value)(implicit ActorSystem)``                  ``Agent[T](value)(implicit ExecutionContext)``
``agent.update(newValue)``                                 ``agent.send(newValue)``
``agent.alterOff(Function1)(Timeout, ExecutionContext)``   ``agent.alterOff(Function1)(ExecutionContext)``
``agent.await(Timeout)``                                   ``Await.result(agent.future, Timeout)``
``agent.future(Timeout)``                                  ``agent.future``
``agent.suspend()``                                        ``No replacement, pointless feature``
``agent.resume()``                                         ``No replacement, pointless feature``
``agent.close()``                                          ``No replacement, not needed in new implementation``
======================================================== ========================================================