
.. _durable-mailboxes:

###################
 Durable Mailboxes
###################

.. sidebar:: Contents

   .. contents:: :local:

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

The durable mailboxes currently supported are:

  - ``FileBasedMailbox`` -- backed by a journaling transaction log on the local file system
  - ``RedisBasedMailbox`` -- backed by Redis
  - ``ZooKeeperBasedMailbox`` -- backed by ZooKeeper
  - ``BeanstalkBasedMailbox`` -- backed by Beanstalkd
  - ``MongoBasedMailbox`` -- backed by MongoDB

We'll walk through each one of these in detail in the sections below.

You can easily implement your own mailbox. Look at the existing implementations for inspiration.

We are also discussing adding some of these durable mailboxes:

  - ``AmqpBasedMailbox`` -- AMQP based mailbox (default RabbitMQ)
  - ``JmsBasedMailbox`` -- JMS based mailbox (default ActiveMQ)
  - ``CassandraBasedMailbox`` -- Cassandra based mailbox
  - ``CamelBasedMailbox`` -- Camel based mailbox
  - ``SqlBasedMailbox`` -- SQL based mailbox for general RDBMS (Postgres, MySQL, Oracle etc.)

Let us know if you have a wish for a certain priority order.

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


Redis-based durable mailbox
===========================

This mailbox is backed by a Redis queue. `Redis <http://redis.io>`_ Is a very
fast NOSQL database that has a wide range of data structure abstractions, one of
them is a queue which is what we are using in this implementation. This means
that you have to start up a Redis server that can host these durable
mailboxes. Read more in the Redis documentation on how to do that.

You configure durable mailboxes through the dispatcher, as described in
:ref:`DurableMailbox.General` with the following mailbox type.

Config::

  my-dispatcher {
    mailbox-type = akka.actor.mailbox.RedisBasedMailboxType
  }

You also need to configure the IP and port for the Redis server. This is done in
the ``akka.actor.mailbox.redis`` section in the :ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-redis-mailbox/src/main/resources/reference.conf
   :language: none


ZooKeeper-based durable mailbox
===============================

This mailbox is backed by `ZooKeeper <http://zookeeper.apache.org/>`_. ZooKeeper
is a centralized service for maintaining configuration information, naming,
providing distributed synchronization, and providing group services This means
that you have to start up a ZooKeeper server (for production a ZooKeeper server
ensamble) that can host these durable mailboxes. Read more in the ZooKeeper
documentation on how to do that.

You configure durable mailboxes through the dispatcher, as described in
:ref:`DurableMailbox.General` with the following mailbox type.

Config::

  my-dispatcher {
    mailbox-type = akka.actor.mailbox.ZooKeeperBasedMailboxType
  }

You also need to configure ZooKeeper server addresses, timeouts, etc. This is
done in the ``akka.actor.mailbox.zookeeper`` section in the :ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-zookeeper-mailbox/src/main/resources/reference.conf
   :language: none

Beanstalk-based durable mailbox
===============================

This mailbox is backed by `Beanstalkd <http://kr.github.com/beanstalkd/>`_.
Beanstalk is a simple, fast work queue. This means that you have to start up a
Beanstalk server that can host these durable mailboxes. Read more in the
Beanstalk documentation on how to do that.

You configure durable mailboxes through the dispatcher, as described in
:ref:`DurableMailbox.General` with the following mailbox type.

Config::

  my-dispatcher {
    mailbox-type = akka.actor.mailbox.BeanstalkBasedMailboxType
  }

You also need to configure the IP, and port, and so on, for the Beanstalk
server. This is done in the ``akka.actor.mailbox.beanstalk`` section in the
:ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-beanstalk-mailbox/src/main/resources/reference.conf
   :language: none

MongoDB-based Durable Mailboxes
===============================

This mailbox is backed by `MongoDB <http://mongodb.org>`_.
MongoDB is a fast, lightweight and scalable document-oriented database.  It contains a number of
features cohesive to a fast, reliable & durable queueing mechanism which the Akka Mailbox takes advantage of.

Akka's implementations of MongoDB mailboxes are built on top of the purely asynchronous MongoDB driver
(often known as `Hammersmith <http://github.com/bwmcadams/hammersmith>`_ and ``com.mongodb.async``)
and as such are purely callback based with a Netty network layer.  This makes them extremely fast &
lightweight versus building on other MongoDB implementations such as
`mongo-java-driver <http://github.com/mongodb/mongo-java-driver>`_ and `Casbah <http://github.com/mongodb/casbah>`_.

You configure durable mailboxes through the dispatcher, as described in
:ref:`DurableMailbox.General` with the following mailbox type.

Config::

  my-dispatcher {
    mailbox-type = akka.actor.mailbox.MongoBasedMailboxType
  }

You will need to configure the URI for the MongoDB server, using the URI Format specified in the
`MongoDB Documentation <http://www.mongodb.org/display/DOCS/Connections>`_. This is done in
the ``akka.actor.mailbox.mongodb`` section in the :ref:`configuration`.

.. literalinclude:: ../../akka-durable-mailboxes/akka-mongo-mailbox/src/main/resources/reference.conf
   :language: none

You must specify a hostname (and optionally port) and at *least* a Database name.  If you specify a
collection name, it will be used as a 'prefix' for the collections Akka creates to store mailbox messages.
Otherwise, collections will be prefixed with ``mailbox.``

It is also possible to configure the timeout thresholds for Read and Write operations in the ``timeout`` block.

