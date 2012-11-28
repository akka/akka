
.. _serialization-scala:

######################
 Serialization (Scala)
######################

Akka has a built-in Extension for serialization,
and it is both possible to use the built-in serializers and to write your own.

The serialization mechanism is both used by Akka internally to serialize messages,
and available for ad-hoc serialization of whatever you might need it for.

Usage
=====

Configuration
-------------

For Akka to know which ``Serializer`` to use for what, you need edit your :ref:`configuration`,
in the "akka.actor.serializers"-section you bind names to implementations of the ``akka.serialization.Serializer``
you wish to use, like this:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala#serialize-serializers-config

After you've bound names to different implementations of ``Serializer`` you need to wire which classes
should be serialized using which ``Serializer``, this is done in the "akka.actor.serialization-bindings"-section:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala#serialization-bindings-config

You only need to specify the name of an interface or abstract base class of the
messages. In case of ambiguity, i.e. the message implements several of the
configured classes, the most specific configured class will be used, i.e. the
one of which all other candidates are superclasses. If this condition cannot be
met, because e.g. ``java.io.Serializable`` and ``MyOwnSerializable`` both apply
and neither is a subtype of the other, a warning will be issued

Akka provides serializers for :class:`java.io.Serializable` and `protobuf
<http://code.google.com/p/protobuf/>`_
:class:`com.google.protobuf.GeneratedMessage` by default (the latter only if
depending on the akka-remote module), so normally you don't need to add
configuration for that; since :class:`com.google.protobuf.GeneratedMessage`
implements :class:`java.io.Serializable`, protobuf messages will always by
serialized using the protobuf protocol unless specifically overridden. In order
to disable a default serializer, map its marker type to “none”::

  akka.actor.serialization-bindings {
    "java.io.Serializable" = none
  }

Verification
------------

If you want to verify that your messages are serializable you can enable the following config option:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala#serialize-messages-config

.. warning::

   We only recommend using the config option turned on when you're running tests.
   It is completely pointless to have it turned on in other scenarios.

If you want to verify that your ``Props`` are serializable you can enable the following config option:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala#serialize-creators-config

.. warning::

   We only recommend using the config option turned on when you're running tests.
   It is completely pointless to have it turned on in other scenarios.

Programmatic
------------

If you want to programmatically serialize/deserialize using Akka Serialization,
here's some examples:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala
   :include: imports,programmatic

For more information, have a look at the ``ScalaDoc`` for ``akka.serialization._``

Customization
=============

So, lets say that you want to create your own ``Serializer``,
you saw the ``docs.serialization.MyOwnSerializer`` in the config example above?

Creating new Serializers
------------------------

First you need to create a class definition of your ``Serializer`` like so:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala
   :include: imports,my-own-serializer
   :exclude: ...

Then you only need to fill in the blanks, bind it to a name in your :ref:`configuration` and then
list which classes that should be serialized using it.

Serializing ActorRefs
---------------------

All ActorRefs are serializable using JavaSerializer, but in case you are writing your own serializer,
you might want to know how to serialize and deserialize them properly, here's the magic incantation:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala
   :include: imports,actorref-serializer

.. note::
  
  ``ActorPath.toStringWithAddress`` only differs from ``toString`` if the
  address does not already have ``host`` and ``port`` components, i.e. it only
  inserts address information for local addresses.

This assumes that serialization happens in the context of sending a message
through the remote transport. There are other uses of serialization, though,
e.g. storing actor references outside of an actor application (database,
durable mailbox, etc.). In this case, it is important to keep in mind that the
address part of an actor’s path determines how that actor is communicated with.
Storing a local actor path might be the right choice if the retrieval happens
in the same logical context, but it is not enough when deserializing it on a
different network host: for that it would need to include the system’s remote
transport address. An actor system is not limited to having just one remote
transport per se, which makes this question a bit more interesting.

In the general case, the local address to be used depends on the type of remote
address which shall be the recipient of the serialized information. Use
:meth:`ActorRefProvider.getExternalAddressFor(remoteAddr)` to query the system
for the appropriate address to use when sending to ``remoteAddr``:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala
   :include: external-address

This requires that you know at least which type of address will be supported by
the system which will deserialize the resulting actor reference; if you have no
concrete address handy you can create a dummy one for the right protocol using
``Address(protocol, "", "", 0)`` (assuming that the actual transport used is as
lenient as Akka’s RemoteActorRefProvider).

There is also a default remote address which is the one used by cluster support
(and typical systems have just this one); you can get it like this:

.. includecode:: code/docs/serialization/SerializationDocSpec.scala
   :include: external-address-default

Deep serialization of Actors
----------------------------

The current recommended approach to do deep serialization of internal actor state is to use Event Sourcing,
for more reading on the topic, see these examples:

`Martin Krasser on EventSourcing Part1 <http://krasserm.blogspot.com/2011/11/building-event-sourced-web-application.html>`_

`Martin Krasser on EventSourcing Part2 <http://krasserm.blogspot.com/2012/01/building-event-sourced-web-application.html>`_


.. note::

    Built-in API support for persisting Actors will come in a later release, see the roadmap for more info:

    `Akka 2.0 roadmap <https://docs.google.com/a/typesafe.com/document/d/18W9-fKs55wiFNjXL9q50PYOnR7-nnsImzJqHOPPbM4E>`_

A Word About Java Serialization
===============================

When using Java serialization without employing the :class:`JavaSerializer` for
the task, you must make sure to supply a valid :class:`ExtendedActorSystem` in
the dynamic variable ``JavaSerializer.currentSystem``. This is used when
reading in the representation of an :class:`ActorRef` for turning the string
representation into a real reference. :class:`DynamicVariable` is a
thread-local variable, so be sure to have it set while deserializing anything
which might contain actor references.


External Akka Serializers
=========================

`Akka-protostuff by Roman Levenstein <https://github.com/romix/akka-protostuff-serialization>`_


`Akka-quickser by Roman Levenstein <https://github.com/romix/akka-quickser-serialization>`_


`Akka-kryo by Roman Levenstein <https://github.com/romix/akka-kryo-serialization>`_
