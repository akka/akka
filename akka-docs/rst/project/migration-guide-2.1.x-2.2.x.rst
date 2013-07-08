.. _migration-2.2:

################################
 Migration Guide 2.1.x to 2.2.x
################################

The 2.2 release contains several structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from 1.3.x to 2.1.x you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`.

Deprecated Closure-Taking Props
===============================

:class:`Props` instances used to contain a closure which produces an
:class:`Actor` instance when invoked. This approach is flawed in that closures
are usually created in-line and thus carry a reference to their enclosing
object; this is not well known among programmers, in particular it can be
surprising that innocent-looking actor creation should not be serializable,
e.g. if the enclosing class is an actor. Another issue which came up often
during reviews is that these actor creators inadvertedly close over the Actor’s
``this`` reference for calling methods on it, which is inherently unsafe.

Another reason for changing the underlying implementation is that Props now
carries information about which class of actor will be created, allowing the
extraction of mailbox type requirements (e.g. when using the Stash) before
trying to create the actor. Being based on the actor class and a list of
constructor arguments also allows these arguments to be serialized according to
the configured serializer bindings instead of mandating Java serialization
(which was used previously).

What changes for Java?
----------------------

A new method ``Props.create`` has been introduced with two overloads::

  Props.create(MyActor.class, arg1, arg2, ...);
  // or
  Props.create(new MyActorCreator(args ...));

In the first case the existence of a constructor signature matching the
supplied arguments is verified at Props construction time. In the second case
it is verified that ``MyActorCreator`` (which must be a ``akka.japi.Creator<?
extends Actor>``) is a static class. In both cases failure is signaled by
throwing a :class:`IllegalArgumentException`.

The constructors of :class:`Props` have been deprecated to facilitate migration.

The :meth:`withCreator` methods have been deprecated. The functionality is
available by using ``Props.create(...).withDeploy(oldProps.deploy());``.

:class:`UntypedActorFactory` has been deprecated in favor of the more precisely
typed :class:`Creator<T>`.

What changes for Scala?
-----------------------

The case class signature of Props has been changed to only contain a
:class:`Deploy`, a :class:`Class[_]` and an immutable :class:`Seq[Any]` (the
constructor arguments for the class). The old factory and extractor methods
have been deprecated.

Properly serializable :class:`Props` can now be created for actors which take
constructor arguments by using ``Props(classOf[MyActor], arg1, arg2, ...)``.
In a future update—possibly within the 2.2.x timeframe—we plan to introduce a
macro which will transform the by-name argument to ``Props(new MyActor(...))``
into a call to the former.

The :meth:`withCreator` methods have been deprecated. The functionality is
available by using ``Props(...).withDeploy(oldProps.deploy)``.

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

ActorContext & ActorRefFactory Dispatcher
=========================================

The return type of ``ActorContext``'s and ``ActorRefFactory``'s ``dispatcher``-method now returns ``ExecutionContext`` instead of ``MessageDispatcher``.

Removed Fallback to Default Dispatcher
======================================

If deploying an actor with a specific dispatcher, e.g.
``Props(...).withDispatcher("d")``, then it would previously fall back to
``akka.actor.default-dispatcher`` if no configuration section for ``d`` could
be found.

This was beneficial for preparing later deployment choices during development
by grouping actors on dispatcher IDs but not immediately configuring those.
Akka 2.2 introduces the possibility to add dispatcher configuration to the
``akka.actor.deployment`` section, making this unnecessary.

The fallback was removed because in many cases its application was neither
intended nor noticed.

Changed Configuration Section for Dispatcher & Mailbox
======================================================

The mailbox configuration defaults moved from ``akka.actor.default-dispatcher``
to ``akka.actor.default-mailbox``. You will not have to change anything unless
your configuration overrides a setting in the default dispatcher section.

The ``mailbox-type`` now requires a fully-qualified class name for the mailbox
to use. The special words ``bounded`` and ``unbounded`` are retained for a
migration period throughout the 2.2 series.

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
It's also now an abstract class with the potential for subtyping and has a new factory method
allowing Java to correctly infer the type of the Agent.
The Java API has also been harmonized so both Java and Scala call the same methods.

======================================================= =======================================================
Old Java API                                            New Java API
======================================================= =======================================================
``new Agent<type>(value, actorSystem)``                   ``Agent.create(value, executionContext)``
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


``event-handlers`` renamed to ``loggers``
=========================================

If you have defined custom event handlers (loggers) in your configuration you need to change
``akka.event-handlers`` to ``akka.loggers`` and
``akka.event-handler-startup-timeout`` to ``akka.logger-startup-timeout``.

The SLF4J logger has been renamed from ``akka.event.slf4j.Slf4jEventHandler`` to
``akka.event.slf4j.Slf4jLogger``.

The ``java.util.logging`` logger has been renamed from ``akka.contrib.jul.JavaLoggingEventHandler`` to
``akka.contrib.jul.JavaLogger``.

Remoting
========

The remoting subsystem of Akka has been replaced in favor of a more flexible, pluggable driver based implementation. This
has required some changes to the configuration sections of ``akka.remote``, the format of Akka remote addresses
and the Akka protocol itself.

The internal communication protocol of Akka has been evolved into a completely standalone entity, not tied to any
particular transport. This change has the effect that Akka 2.2 remoting is no longer able to directly communicate with
older versions.

The ``akka.remote.transport`` configuration key has been removed as the remoting system itself is no longer replaceable.
Custom transports are now pluggable via the ``akka.remote.enabled-transpotrs`` key (see the :meth:`akka.remote.Transport` SPI
and the documentation of remoting for more detail on drivers). The transport loaded by default is a Netty based TCP
driver similar in functionality to the default remoting in Akka 2.1.

Transports are now fully pluggable through drivers, therefore transport specific settings like listening ports now live in the namespace
of their driver configuration. In particular TCP related settings are now under ``akka.remote.netty.tcp``.

As a result of being able to replace the transport protocol, it is now necessary to include the protocol information
in Akka URLs for remote addresses. Therefore a remote address of ``akka://remote-sys@remotehost:2552/user/actor``
has to be changed to ``akka.tcp://remote-sys@remotehost:2552/user/actor`` if the remote system uses TCP as transport. If
the other system uses SSL on top of TCP, the correct address would be ``akka.ssl.tcp://remote-sys@remotehost:2552/user/actor``.

Remote lifecycle events have been changed to a more coarse-grained, simplified model. All remoting events are subclasses
of :meth:`akka.remote.RemotingLifecycle`. Events related to the lifecycle of *associations* (formerly called *connections*)
be it inbound or outbound are subclasses of :meth:`akka.remote.AssociationEvent` (which is in turn a subclass of
:meth:`RemotingLifecycle`). The direction of the association (inbound or outbound) triggering an ``AssociationEvent`` is
available via the ``inbound`` boolean field of the event.

.. note::
    The change in terminology from "Connection" to "Association" reflects the fact that the remoting subsystem may use
    connectionless transports, but an association similar to transport layer connections is maintained between endpoints
    by the Akka protocol.

New configuration settings are also available, see the remoting documentation for more detail: :ref:`remoting-scala`

.. _migration_2.2_actorSelection:

Use ``actorSelection`` instead of ``actorFor``
==============================================

``actorFor`` is deprecated in favor of ``actorSelection`` because actor references
acquired with ``actorFor`` behave differently for local and remote actors.
In the case of a local actor reference, the named actor needs to exist before the
lookup, or else the acquired reference will be an :class:`EmptyLocalActorRef`.
This will be true even if an actor with that exact path is created after acquiring
the actor reference. For remote actor references acquired with `actorFor` the
behaviour is different and sending messages to such a reference will under the hood
look up the actor by path on the remote system for every message send.

Messages can be sent via the :class:`ActorSelection` and the path of the
:class:`ActorSelection` is looked up when delivering each message. If the selection
does not match any actors the message will be dropped.

To acquire an :class:`ActorRef` for an :class:`ActorSelection` you need to
send a message to the selection and use the ``sender`` reference of the reply from
the actor. There is a built-in ``Identify`` message that all Actors will understand
and automatically reply to with a ``ActorIdentity`` message containing the
:class:`ActorRef`.

Read more about ``actorSelection`` in :ref:`docs for Java <actorSelection-java>` or
:ref:`docs for Scala <actorSelection-scala>`.

ActorRef equality and sending to remote actors
==============================================

Sending messages to an ``ActorRef`` must have the same semantics no matter if the target actor is located
on a remote host or in the same ``ActorSystem`` in the same JVM. This was not always the case. For example
when the target actor is terminated and created again under the same path. Sending to local references
of the previous incarnation of the actor will not be delivered to the new incarnation, but that was the case
for remote references. The reason was that the target actor was looked up by its path on every message
delivery and the path didn't distinguish between the two incarnations of the actor. This has been fixed, and
messages sent to a remote reference that points to a terminated actor will not be delivered to a new
actor with the same path.

Equality of ``ActorRef`` has been changed to match the intention that an ``ActorRef`` corresponds to the target
actor instance. Two actor references are compared equal when they have the same path and point to the same
actor incarnation. A reference pointing to a terminated actor does not compare equal to a reference pointing
to another (re-created) actor with the same path. Note that a restart of an actor caused by a failure still
means that it's the same actor incarnation, i.e. a restart is not visible for the consumer of the ``ActorRef``.

Equality in 2.1 was only based on the path of the ``ActorRef``. If you need to keep track of actor references
in a collection and do not care about the exact actor incarnation you can use the ``ActorPath`` as key, because
the identifier of the target actor is not taken into account when comparing actor paths.

Remote actor references acquired with ``actorFor`` do not include the full information about the underlying actor
identity and therefore such references do not compare equal to references acquired with ``actorOf``,
``sender``, or ``context.self``. Because of this ``actorFor`` is deprecated, as explained in
:ref:`migration_2.2_actorSelection`.

Note that when a parent actor is restarted its children are by default stopped and re-created, i.e. the child
after the restart will be a different incarnation than the child before the restart. This has always been the
case, but in some situations you might not have noticed, e.g. when comparing such actor references or sending
messages to remote deployed children of a restarted parent.

This may also have implications if you compare the ``ActorRef`` received in a ``Terminated`` message
with an expected ``ActorRef``.

The following will not match::

  val ref = context.actorFor("akka.tcp://actorSystemName@10.0.0.1:2552/user/actorName")

  def receive = {
    case Terminated(`ref`) => // ...
  }

Instead, use actorSelection followed by identify request, and watch the verified actor reference::

  val selection = context.actorSelection(
    "akka.tcp://actorSystemName@10.0.0.1:2552/user/actorName")
  selection ! Identify(None)
  var ref: ActorRef = _

  def receive = {
    case ActorIdentity(_, Some(actorRef)) =>
      ref = actorRef
      context watch ref
    case ActorIdentity(_, None) => // not alive
    case Terminated(r) if r == ref => // ...
  }

Use ``watch`` instead of ``isTerminated``
=========================================

``ActorRef.isTerminated`` is deprecated in favor of ``ActorContext.watch`` because
``isTerminated`` behaves differently for local and remote actors.

DeathWatch Semantics are Simplified
===================================

DeathPactException is now Fatal
-------------------------------

Previously an unhandled :class:`Terminated` message which led to a
:class:`DeathPactException` to the thrown would be answered with a ``Restart``
directive by the default supervisor strategy. This is not intuitive given the
name of the exception and the Erlang linking feature by which it was inspired.
The default strategy has thus be changed to return ``Stop`` in this case.

It can be argued that previously the actor would likely run into a restart loop
because watching a terminated actor would lead to a :class:`DeathPactException`
immediately again.

Unwatching now Prevents Reception of Terminated
-----------------------------------------------

Previously calling :meth:`ActorContext.unwatch` would unregister lifecycle
monitoring interest, but if the target actor had terminated already the
:class:`Terminated` message had already been enqueued and would be received
later—possibly leading to a :class:`DeathPactException`. This behavior has been
modified such that the :class:`Terminated` message will be silently discarded
if :meth:`unwatch` is called before processing the :class:`Terminated`
message. Therefore the following is now safe::

  context.stop(target)
  context.unwatch(target)

Dispatcher and Mailbox Implementation Changes
=============================================

This point is only relevant if you have implemented a custom mailbox or
dispatcher and want to migrate that to Akka 2.2. The constructor signature of
:class:`MessageDispatcher` has changed, it now takes a
:class:`MessageDispatcherConfigurator` instead of
:class:`DispatcherPrerequisites`. Its :class:`createMailbox` method now
receives one more argument of type :class:`MailboxType`, which is the mailbox
type determined by the :class:`ActorRefProvider` for the actor based on its
deployment. The :class:`DispatcherPrerequisites` now include a
:class:`Mailboxes` instance which can be used for resolving mailbox references.
The constructor signatures of the built-in dispatcher implementation have been
adapted accordingly.  The traits describing mailbox semantics have been
separated from the implementation traits.


