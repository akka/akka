
.. _serialization-scala:

######################
 Serialization (Scala)
######################

.. sidebar:: Contents

   .. contents:: :local:

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

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala#serialize-serializers-config

After you've bound names to different implementations of ``Serializer`` you need to wire which classes
should be serialized using which ``Serializer``, this is done in the "akka.actor.serialization-bindings"-section:

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala#serialization-bindings-config

You only need to specify the name of an interface or abstract base class of the messages. In case of ambiguity,
i.e. the message implements several of the configured classes, it is primarily using the most specific
configured class, and secondly the entry configured first.

Akka provides serializers for ``java.io.Serializable`` and `protobuf <http://code.google.com/p/protobuf/>`_
``com.google.protobuf.Message`` by default, so normally you don't need to add configuration for that, but
it can be done to force a specific serializer in case messages implements both ``java.io.Serializable``
and ``com.google.protobuf.Message``.

Verification
------------

If you want to verify that your messages are serializable you can enable the following config option:

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala#serialize-messages-config

.. warning::

   We only recommend using the config option turned on when you're running tests.
   It is completely pointless to have it turned on in other scenarios.

If you want to verify that your ``Props`` are serializable you can enable the following config option:

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala#serialize-creators-config

.. warning::

   We only recommend using the config option turned on when you're running tests.
   It is completely pointless to have it turned on in other scenarios.

Programmatic
------------

If you want to programmatically serialize/deserialize using Akka Serialization,
here's some examples:

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala
   :include: imports,programmatic

For more information, have a look at the ``ScalaDoc`` for ``akka.serialization._``

Customization
=============

So, lets say that you want to create your own ``Serializer``,
you saw the ``akka.docs.serialization.MyOwnSerializer`` in the config example above?

Creating new Serializers
------------------------

First you need to create a class definition of your ``Serializer`` like so:

.. includecode:: code/akka/docs/serialization/SerializationDocSpec.scala
   :include: imports,my-own-serializer
   :exclude: ...

Then you only need to fill in the blanks, bind it to a name in your :ref:`configuration` and then
list which classes that should be serialized using it.