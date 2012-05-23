
.. _durable-mailboxes:

###################
 Durable Mailboxes
###################


Overview
========

A durable mailbox is a mailbox which stores the messages on durable storage.
What this means in practice is that if there are pending messages in the actor's
mailbox when the node of the actor resides on crashes, then when you restart the
node, the actor will be able to continue processing as if nothing had happened;
with all pending messages still in its mailbox.

You configure durable mailboxes through the dispatcher. The actor is oblivious
to which type of mailbox it is using.

This gives you an excellent way of creating bulkheads in your application, where
groups of actors sharing the same dispatcher also share the same backing
storage. Read more about that in the :ref:`dispatchers-scala` documentation.

One basic file based durable mailbox is provided by Akka out-of-the-box.
Other implementations can easily be added. Some are available as separate community
Open Source projects, such as:

* `AMQP Durable Mailbox <https://github.com/drexin/akka-amqp-mailbox>`_


A durable mailbox is like any other mailbox not likely to be transactional. It's possible
if the actor crashes after receiving a message, but before completing processing of
it, that the message could be lost.

.. warning::

   A durable mailbox typically doesn't work with blocking message send, i.e. the message
   send operations that are relying on futures; ``?`` or ``ask``. If the node
   has crashed and then restarted, the thread that was blocked waiting for the
   reply is gone and there is no way we can deliver the message.


File-based durable mailbox
==========================

This mailbox is backed by a journaling transaction log on the local file
system. It is the simplest to use since it does not require an extra
infrastructure piece to administer, but it is usually sufficient and just what
you need.

In the configuration of the dispatcher you specify the fully qualified class name
of the mailbox:

.. includecode:: code/akka/docs/actor/mailbox/DurableMailboxDocSpec.scala
   :include: dispatcher-config

Here is an example of how to create an actor with a durable dispatcher, in Scala:

.. includecode:: code/akka/docs/actor/mailbox/DurableMailboxDocSpec.scala
   :include: imports,dispatcher-config-use

Corresponding example in Java:

.. includecode:: code/akka/docs/actor/mailbox/DurableMailboxDocTestBase.java
   :include: imports,dispatcher-config-use

You can also configure and tune the file-based durable mailbox. This is done in
the ``akka.actor.mailbox.file-based`` section in the :ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-file-mailbox/src/main/resources/reference.conf
   :language: none

How to implement a durable mailbox
==================================

Here is an example of how to implement a custom durable mailbox. Essentially it consists of
a configurator (MailboxType) and a queue implementation (DurableMessageQueue).

The envelope contains the message sent to the actor, and information about sender. It is the
envelope that needs to be stored. As a help utility you can mixin DurableMessageSerialization
to serialize and deserialize the envelope using the ordinary :ref:`serialization-scala`
mechanism. This optional and you may store the envelope data in any way you like.

.. includecode:: code/akka/docs/actor/mailbox/DurableMailboxDocSpec.scala
   :include: custom-mailbox

To facilitate testing of a durable mailbox you may use ``DurableMailboxSpec`` as base class.
It implements a few basic tests and helps you setup the a fixture. More tests can be
added in concrete subclass like this:

.. includecode:: code/akka/docs/actor/mailbox/DurableMailboxDocSpec.scala
   :include: custom-mailbox-test

You find DurableMailboxDocSpec in ``akka-mailboxes-common-test-2.1-SNAPSHOT.jar``.
Add this dependency::

  "com.typesafe.akka" % "akka-mailboxes-common-test" % "2.1-SNAPSHOT"

For more inspiration you can look at the old implementations based on Redis, MongoDB, Beanstalk,
and ZooKeeper, which can be found in Akka git repository tag
`v2.0.1 <https://github.com/akka/akka/tree/v2.0.1/akka-durable-mailboxes>`_.