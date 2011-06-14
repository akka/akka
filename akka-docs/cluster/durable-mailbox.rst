
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
   send operations that are relying on futures; ``!!``, ``?``,
   ``sendRequestReply`` and ``ask``. If the node has crashed
   and then restarted, the thread that was blocked waiting for the reply is gone
   and there is no way we can deliver the message.

The durable mailboxes currently supported are:

  - ``FileDurableMailboxStorage`` -- backed by a journaling transaction log on the local file system
  - ``RedisDurableMailboxStorage`` -- backed by Redis
  - ``ZooKeeperDurableMailboxStorage`` -- backed by ZooKeeper
  - ``BeanstalkDurableMailboxStorage`` -- backed by Beanstalkd

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
the ``akka.actor.mailbox.file-based`` section in the ``akka.conf`` configuration
file.

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
the ``akka.actor.mailbox.redis`` section in the ``akka.conf`` configuration
file.

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
done in the ``akka.actor.mailbox.zookeeper`` section in the ``akka.conf``
configuration file.

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
``akka.conf`` configuration file.

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
