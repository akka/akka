
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

.. sidebar:: **IMPORTANT**

   None of these mailboxes work with blocking message send, e.g. the message
   send operations that are relying on futures; ``?`` or ``ask``. If the node
   has crashed and then restarted, the thread that was blocked waiting for the
   reply is gone and there is no way we can deliver the message.

The durable mailboxes currently supported are:

  - ``FileDurableMailboxStorage`` -- backed by a journaling transaction log on the local file system
  - ``RedisDurableMailboxStorage`` -- backed by Redis
  - ``ZooKeeperDurableMailboxStorage`` -- backed by ZooKeeper
  - ``BeanstalkDurableMailboxStorage`` -- backed by Beanstalkd
  - ``MongoNaiveDurableMailboxStorage`` -- backed by MongoDB

We'll walk through each one of these in detail in the sections below.

Soon Akka will also have:

  - ``AmqpDurableMailboxStorage`` -- AMQP based mailbox (default RabbitMQ)
  - ``JmsDurableMailboxStorage`` -- JMS based mailbox (default ActiveMQ)


File-based durable mailbox
==========================

This mailbox is backed by a journaling transaction log on the local file
system. It is the simplest want to use since it does not require an extra
infrastructure piece to administer, but it is usually sufficient and just what
you need.

The durable dispatchers and their configuration options reside in the
``akka.actor.mailbox`` package.

You configure durable mailboxes through the "Akka"-only durable dispatchers, the
actor is oblivious to which type of mailbox it is using. Here is an example::

    val dispatcher = DurableDispatcher(
      "my:service",
      FileDurableMailboxStorage)
    // Then set the actors dispatcher to this dispatcher

or for a thread-based durable dispatcher::

    self.dispatcher = DurablePinnedDispatcher(
      self,
      FileDurableMailboxStorage)

There are 2 different durable dispatchers, ``DurableDispatcher`` and
``DurablePinnedDispatcher``, which are durable versions of
``Dispatcher`` and ``PinnedDispatcher``.

This gives you an excellent way of creating bulkheads in your application, where
groups of actors sharing the same dispatcher also share the same backing
storage.

Read more about that in the :ref:`dispatchers-scala` documentation.

You can also configure and tune the file-based durable mailbox. This is done in
the ``akka.actor.mailbox.file-based`` section in the :ref:`configuration`.

.. code-block:: none

    akka {
      actor {
        mailbox {
          file-based {
            directory-path = "./_mb"
            max-items = 2147483647
            max-size = 2147483647
            max-items = 2147483647
            max-age = 0
            max-journal-size = 16777216 # 16 * 1024 * 1024
            max-memory-size = 134217728 # 128 * 1024 * 1024
            max-journal-overflow = 10
            max-journal-size-absolute = 9223372036854775807
            discard-old-when-full = on
            keep-journal = on
            sync-journal = off
          }
        }
      }
    }

.. todo:: explain all the above options in detail


Redis-based durable mailbox
===========================

This mailbox is backed by a Redis queue. `Redis <http://redis.io>`_ Is a very
fast NOSQL database that has a wide range of data structure abstractions, one of
them is a queue which is what we are using in this implementation. This means
that you have to start up a Redis server that can host these durable
mailboxes. Read more in the Redis documentation on how to do that.

Here is an example of how you can configure your dispatcher to use this mailbox::

    val dispatcher = DurableDispatcher(
      "my:service",
      RedisDurableMailboxStorage)

or for a thread-based durable dispatcher::

    self.dispatcher = DurablePinnedDispatcher(
      self,
      RedisDurableMailboxStorage)

You also need to configure the IP and port for the Redis server. This is done in
the ``akka.actor.mailbox.redis`` section in the :ref:`configuration`.

.. code-block:: none

    akka {
      actor {
        mailbox {
          redis {
            hostname = "127.0.0.1"
            port = 6379
          }
        }
      }
    }


ZooKeeper-based durable mailbox
===============================

This mailbox is backed by `ZooKeeper <http://zookeeper.apache.org/>`_. ZooKeeper
is a centralized service for maintaining configuration information, naming,
providing distributed synchronization, and providing group services This means
that you have to start up a ZooKeeper server (for production a ZooKeeper server
ensamble) that can host these durable mailboxes. Read more in the ZooKeeper
documentation on how to do that.

Akka is using ZooKeeper for many other things, for example the clustering
support so if you're using that you love to run a ZooKeeper server anyway and
there will not be that much more work to set up this durable mailbox.

Here is an example of how you can configure your dispatcher to use this mailbox::

    val dispatcher = DurableDispatcher(
      "my:service",
      ZooKeeperDurableMailboxStorage)

or for a thread-based durable dispatcher::

    self.dispatcher = DurablePinnedDispatcher(
      self,
      ZooKeeperDurableMailboxStorage)

You also need to configure ZooKeeper server addresses, timeouts, etc. This is
done in the ``akka.actor.mailbox.zookeeper`` section in the :ref:`configuration`.

.. code-block:: none

    akka {
      actor {
        mailbox {
          zookeeper {
            server-addresses = "localhost:2181"
            session-timeout = 60
            connection-timeout = 30
            blocking-queue = on
          }
        }
      }
    }


Beanstalk-based durable mailbox
===============================

This mailbox is backed by `Beanstalkd <http://kr.github.com/beanstalkd/>`_.
Beanstalk is a simple, fast work queue. This means that you have to start up a
Beanstalk server that can host these durable mailboxes. Read more in the
Beanstalk documentation on how to do that. ::

    val dispatcher = DurableDispatcher(
      "my:service",
      BeanstalkDurableMailboxStorage)

or for a thread-based durable dispatcher. ::

    self.dispatcher = DurablePinnedDispatcher(
      self,
      BeanstalkDurableMailboxStorage)

You also need to configure the IP, and port, and so on, for the Beanstalk
server. This is done in the ``akka.actor.mailbox.beanstalk`` section in the
:ref:`configuration`.

.. code-block:: none

    akka {
      actor {
        mailbox {
          beanstalk {
            hostname = "127.0.0.1"
            port = 11300
            reconnect-window = 5
            message-submit-delay = 0
            message-submit-timeout = 5
            message-time-to-live = 120
          }
        }
      }
    }

MongoDB-based Durable Mailboxes
===============================

This mailbox is backed by `MongoDB <http://mongodb.org>`_.
MongoDB is a fast, lightweight and scalable document-oriented database.  It contains a number of 
features cohesive to a fast, reliable & durable queueing mechanism which the Akka Mailbox takes advantage of.


Akka's implementations of MongoDB mailboxes are built on top of the purely asynchronous MongoDB driver (often known as `Hammersmith <http://github.com/bwmcadams/hammersmith>`_ and ``com.mongodb.async``) and as such are purely callback based with a Netty network layer.  This makes them extremely fast & lightweight versus building on other MongoDB implementations such as `mongo-java-driver <http://github.com/mongodb/mongo-java-driver>`_ and `Casbah <http://github.com/mongodb/casbah>`_.

You will need to configure the URI for the MongoDB server, using the URI Format specified in the `MongoDB Documentation <http://www.mongodb.org/display/DOCS/Connections>`_. This is done in
the ``akka.actor.mailbox.mongodb`` section in the :ref:`configuration`.

.. code-block:: none

      mongodb {
        # Any specified collection name will be used as a prefix for collections that use durable mongo mailboxes
        uri = "mongodb://localhost/akka.mailbox"   # Follow Mongo URI Spec - http://www.mongodb.org/display/DOCS/Connections
        # Configurable timeouts for certain ops
        timeout {
            read = 3000 # number of milliseconds to wait for a read to succeed before timing out the future
            write = 3000 # number of milliseconds to wait for a write to succeed before timing out the future
        }
      }

You must specify a hostname (and optionally port) and at *least* a Database name.  If you specify a collection name, it will be used as a 'prefix' for the collections Akka creates to store mailbox messages.  Otherwise, collections will be prefixed with ``mailbox.``

It is also possible to configure the timeout threshholds for Read and Write operations in the ``timeout`` block.
Currently Akka offers only one "type" of MongoDB based Mailbox but there are plans to support at least 
one other kind which uses a different queueing strategy.  


'Naive' MongoDB-based Durable Mailbox
-------------------------------------
The currently supported mailbox is considered "Naive" as it removes messages (using the ``findAndRemove``
command) from the MongoDB datastore as soon as the actor consumes them.  This could cause message loss 
if an actor crashes before completely processing a message.  It is not a problem per sé, but behavior 
users should be aware of.

Here is an example of how you can configure your dispatcher to use this mailbox::

    val dispatcher = DurableDispatcher(
      "my:service",
      MongoNaiveDurableMailboxStorage)

or for a thread-based durable dispatcher::

    self.dispatcher = DurablePinnedDispatcher(
      self,
      MongoNaiveDurableMailboxStorage)


