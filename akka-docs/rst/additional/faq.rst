Frequently Asked Questions
==========================

Akka Project
^^^^^^^^^^^^

Where does the name Akka come from?
-----------------------------------

It is the name of a beautiful Swedish `mountain <https://lh4.googleusercontent.com/-z28mTALX90E/UCOsd249TdI/AAAAAAAAAB0/zGyNNZla-zY/w442-h331/akka-beautiful-panorama.jpg>`_
up in the northern part of Sweden called Laponia. The mountain is also sometimes
called 'The Queen of Laponia'.

Akka is also the name of a goddess in the Sámi (the native Swedish population)
mythology. She is the goddess that stands for all the beauty and good in the
world. The mountain can be seen as the symbol of this goddess.

Also, the name AKKA is the a palindrome of letters A and K as in Actor Kernel.

Akka is also:

* the name of the goose that Nils traveled across Sweden on in `The Wonderful Adventures of Nils <http://en.wikipedia.org/wiki/The_Wonderful_Adventures_of_Nils>`_ by the Swedish writer Selma Lagerlöf.
* the Finnish word for 'nasty elderly woman' and the word for 'elder sister' in the Indian languages Tamil, Telugu, Kannada and Marathi.
* a `font <http://www.dafont.com/akka.font>`_
* a town in Morocco
* a near-earth asteroid

Actors in General
^^^^^^^^^^^^^^^^^

sender()/getSender() disappears when I use Future in my Actor, why?
-------------------------------------------------------------------

When using future callbacks, inside actors you need to carefully avoid closing over
the containing actor’s reference, i.e. do not call methods or access mutable state
on the enclosing actor from within the callback. This breaks the actor encapsulation
and may introduce synchronization bugs and race conditions because the callback will
be scheduled concurrently to the enclosing actor. Unfortunately there is not yet a way
to detect these illegal accesses at compile time.

Read more about it in the docs for :ref:`jmm-shared-state`.

Why OutOfMemoryError?
---------------------

It can be many reasons for OutOfMemoryError. For example, in a pure push based system with
message consumers that are potentially slower than corresponding message producers you must
add some kind of message flow control. Otherwise messages will be queued in the consumers'
mailboxes and thereby filling up the heap memory.

Some articles for inspiration:

* `Balancing Workload across Nodes with Akka 2 <http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2>`_.
* `Work Pulling Pattern to prevent mailbox overflow, throttle and distribute work <http://www.michaelpollmeier.com/akka-work-pulling-pattern/>`_

Actors Scala API
^^^^^^^^^^^^^^^^

How can I get compile time errors for missing messages in `receive`?
--------------------------------------------------------------------

One solution to help you get a compile time warning for not handling a message
that you should be handling is to define your actors input and output messages
implementing base traits, and then do a match that the will be checked for
exhaustiveness.

Here is an example where the compiler will warn you that the match in
receive isn't exhaustive:

.. includecode:: code/docs/faq/Faq.scala#exhaustiveness-check

Remoting
^^^^^^^^

I want to send to a remote system but it does not do anything
-------------------------------------------------------------

Make sure that you have remoting enabled on both ends: client and server. Both
do need hostname and port configured, and you will need to know the port of the
server; the client can use an automatic port in most cases (i.e. configure port
zero). If both systems are running on the same network host, their ports must
be different

If you still do not see anything, look at what the logging of remote
life-cycle events tells you (normally logged at INFO level) or switch on 
:ref:`logging-remote-java`
to see all sent and received messages (logged at DEBUG level).

Which options shall I enable when debugging remoting issues?
------------------------------------------------------------

Have a look at the :ref:`remote-configuration-java`, the typical candidates are:

* `akka.remote.log-sent-messages`
* `akka.remote.log-received-messages`
* `akka.remote.log-remote-lifecycle-events` (this also includes deserialization errors)

What is the name of a remote actor?
-----------------------------------

When you want to send messages to an actor on a remote host, you need to know
its :ref:`full path <addressing>`, which is of the form::

    akka.protocol://system@host:1234/user/my/actor/hierarchy/path

Observe all the parts you need here:

* ``protocol`` is the protocol to be used to communicate with the remote system. 
   Most of the cases this is `tcp`.

* ``system`` is the remote system’s name (must match exactly, case-sensitive!)

* ``host`` is the remote system’s IP address or DNS name, and it must match that
  system’s configuration (i.e. `akka.remote.netty.hostname`)

* ``1234`` is the port number on which the remote system is listening for
  connections and receiving messages

* ``/user/my/actor/hierarchy/path`` is the absolute path of the remote actor in
  the remote system’s supervision hierarchy, including the system’s guardian
  (i.e. ``/user``, there are others e.g. ``/system`` which hosts loggers, ``/temp``
  which keeps temporary actor refs used with `ask()`, `/remote` which enables
  remote deployment, etc.); this matches how the actor prints its own ``self``
  reference on the remote host, e.g. in log output.

Why are replies not received from a remote actor?
-------------------------------------------------

The most common reason is that the local system’s name (i.e. the
``system@host:1234`` part in the answer above) is not reachable from the remote
system’s network location, e.g. because ``host`` was configured to be ``0.0.0.0``,
``localhost`` or a NAT’ed IP address.

How reliable is the message delivery?
-------------------------------------

The general rule is **at-most-once delivery**, i.e. no guaranteed delivery.
Stronger reliability can be built on top, and Akka provides tools to do so.

Read more in :ref:`message-delivery-reliability`.

Debugging
^^^^^^^^^

How do I turn on debug logging?
-------------------------------

To turn on debug logging in your actor system add the following to your configuration::

    akka.loglevel = DEBUG  

To enable different types of debug logging add the following to your configuration:

* ``akka.actor.debug.receive`` will log all messages sent to an actor if that actors `receive` method is a ``LoggingReceive``

* ``akka.actor.debug.autoreceive`` will log all *special* messages like ``Kill``, ``PoisonPill`` e.t.c. sent to all actors

* ``akka.actor.debug.lifecycle`` will log all actor lifecycle events of all actors

Read more about it in the docs for :ref:`logging-java` and :ref:`actor.logging-scala`.
