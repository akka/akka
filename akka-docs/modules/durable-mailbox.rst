
.. _durable-mailboxes:

###################
 Durable Mailboxes
###################


Overview
========

Akka supports a set of durable mailboxes. A durable mailbox is a replacement for
the standard actor mailbox that is durable. What this means in practice is that
if there are pending messages in the actor's mailbox when the node of the actor
resides on crashes, then when you restart the node, the actor will be able to
continue processing as if nothing had happened; with all pending messages still
in its mailbox.

None of these mailboxes implements transactions for current message. It's possible
if the actor crashes after receiving a message, but before completing processing of
it, that the message could be lost.

.. warning:: **IMPORTANT**

   None of these mailboxes work with blocking message send, i.e. the message
   send operations that are relying on futures; ``?`` or ``ask``. If the node
   has crashed and then restarted, the thread that was blocked waiting for the
   reply is gone and there is no way we can deliver the message.

The durable mailboxes supported out-of-the-box are:

  - ``FileBasedMailbox`` -- backed by a journaling transaction log on the local file system

You can easily implement your own mailbox. Look at the existing implementation for inspiration.

.. _DurableMailbox.General:

General Usage
-------------

The durable mailboxes and their configuration options reside in the
``akka.actor.mailbox`` package.

You configure durable mailboxes through the dispatcher. The
actor is oblivious to which type of mailbox it is using.

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

The actor is oblivious to which type of mailbox it is using.

This gives you an excellent way of creating bulkheads in your application, where
groups of actors sharing the same dispatcher also share the same backing
storage. Read more about that in the :ref:`dispatchers-scala` documentation.

File-based durable mailbox
==========================

This mailbox is backed by a journaling transaction log on the local file
system. It is the simplest to use since it does not require an extra
infrastructure piece to administer, but it is usually sufficient and just what
you need.

You configure durable mailboxes through the dispatcher, as described in
:ref:`DurableMailbox.General` with the following mailbox type.

Config::

  my-dispatcher {
    mailbox-type = akka.actor.mailbox.FileBasedMailboxType
  }

You can also configure and tune the file-based durable mailbox. This is done in
the ``akka.actor.mailbox.file-based`` section in the :ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-file-mailbox/src/main/resources/reference.conf
   :language: none

