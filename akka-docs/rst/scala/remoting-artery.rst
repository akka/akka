.. _remoting-artery-scala:

##########################
Remoting (codename Artery)
##########################

.. note::

  This page describes the experimental remoting subsystem, codenamed *Artery* that will eventually replace the
  old remoting implementation. For the current stable remoting system please refer to :ref:`_remoting-scala`.

Remoting enables Actor systems on different hosts or JVMs to communicate with each other. By enabling remoting
the system will start listening on a provided network address and also gains the ability to connect to other
systems through the network. From the application's perspective there is no API difference between local or remote
systems, :class:`ActorRef` instances that point to remote systems look exactly the same as local ones: they can be
sent messages to, watched, etc.
Every :class:`ActorRef` contains hostname and port information and can be passed around even on the network. This means
that on a network every :class:`ActorRef` is a unique identifier of an actor on that network.

Remoting is not a server-client technology. All systems using remoting can contact any other system on the network
if they possess an :class:`ActorRef` pointing to those system. This means that every system that is remoting enabled
acts as a "server" to which arbitrary systems on the same network can connect to.

What is new in Artery
---------------------

Artery is a reimplementation of the old remoting module aimed at improving performance and stability. It is mostly
backwards compatible with the old implementation and it is a drop-in replacement in many cases. Main features
of Artery compared to the previous implementation:

* Based on `Aeron <https://github.com/real-logic/Aeron>`_ instead of TCP
* Focused on high-throughput, low-latency communication
* Isolation of internal control messages from user messages improving stability and reducing false failure detection
  in case of heavy traffic by using a dedicated subchannel.
* Mostly allocation-free operation
* Support for a separate subchannel for large messages to avoid interference with smaller messages
* Compression of actor paths on the wire to reduce overhead for smaller messages
* Support for faster serialization/deserialization using ByteBuffers directly
* Built-in Flight-Recorder to help debugging implementation issues without polluting users logs with implementaiton
  specific events
* Providing protocol stability across major Akka versions to support rolling updates of large-scale systems

The main incompatible change from the previous implementation that the protocol field of the string representation of an
:class:`ActorRef` is always `akka` instead of the previously used `akka.tcp` or `akka.ssl.tcp`.

Preparing your ActorSystem for Remoting
---------------------------------------

The Akka remoting is a separate jar file. Make sure that you have the following dependency in your project::

  "com.typesafe.akka" %% "akka-remote" % "@version@" @crossString@

To enable remote capabilities in your Akka project you should, at a minimum, add the following changes
to your ``application.conf`` file::

  akka {
    actor {
      provider = remote
    }
    remote {
      artery {
        enabled = on
        canonical.hostname = "127.0.0.1"
        canonical.port = 25520
      }
    }
  }

As you can see in the example above there are four things you need to add to get started:

* Change provider from ``local`` to ``remote``
* Enable Artery to use it as the remoting implementation
* Add host name - the machine you want to run the actor system on; this host
  name is exactly what is passed to remote systems in order to identify this
  system and consequently used for connecting back to this system if need be,
  hence set it to a reachable IP address or resolvable name in case you want to
  communicate across the network.
* Add port number - the port the actor system should listen on, set to 0 to have it chosen automatically

.. note::

  The port number needs to be unique for each actor system on the same machine even if the actor
  systems have different names. This is because each actor system has its own networking subsystem
  listening for connections and handling messages as not to interfere with other actor systems.

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
All settings are described in :ref:`remote-configuration-scala`.

Canonical address
^^^^^^^^^^^^^^^^^

In order to remoting to work properly, where each system can send messages to any other system on the same network
(for example a system forwards a message to a third system, and the third replies directly to the sender system)
it is essential for every system to have a *unique, globally reachable* address and port. This address is part of the
unique name of the system and will be used by other systems to open a connection to it and send messages. This means
that if a host has multiple names (different DNS records pointing to the same IP address) then only one of these
can be *canonical*. If a message arrives to a system but it contains a different hostname than the expected canonical
name then the message will be dropped. If multiple names for a system would be allowed, then equality checks among
:class:`ActorRef` instances would no longer to be trusted and this would violate the fundamental assumption that
an actor has a globally unique reference on a given network. As a consequence, this also means that localhost addresses
(e.g. `127.0.0.1`) cannot be used in general (apart from local development) since they are not unique addresses in a
real network.

In cases, where Network Address Translation (NAT) is used or other network bridging is involved, it is important
to configure the system so that it understands that there is a difference between his externally visible, canonical
address and between the host-port pair that is used to listen for connections. See :ref:`remote-configuration-nat-artery`
for details.

Aquiring references to remote actors
------------------------------------

In order to communicate with an actor, it is necessary to have its :class:`ActorRef`. In the local case it is usually
the creator of the actor (the caller of ``actorOf()``) is who gets the :class:`ActorRef` for an actor that it can
then send to other actors. Alternatively, an actor can look up another located at a known path using
:class:`ActorSelection`. These methods are available even in remoting enabled systems:

* Remote Lookup    : used to look up an actor on a remote node with ``actorSelection(path)``
* Remote Creation  : used to create an actor on a remote node with ``actorOf(Props(...), actorName)``

In the next sections the two alternatives are described in detail.


Looking up Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^

``actorSelection(path)`` will obtain an ``ActorSelection`` to an Actor on a remote node, e.g.::

  val selection =
    context.actorSelection("akka://actorSystemName@10.0.0.1:25520/user/actorName")

As you can see from the example above the following pattern is used to find an actor on a remote node::

  akka://<actor system>@<hostname>:<port>/<actor path>

.. note::

  Unlike with earlier remoting, the protocol field is always `akka` as pluggable transports are no longer supported.

Once you obtained a selection to the actor you can interact with it in the same way you would with a local actor, e.g.::

  selection ! "Pretty awesome feature"

To acquire an :class:`ActorRef` for an :class:`ActorSelection` you need to
send a message to the selection and use the ``sender`` reference of the reply from
the actor. There is a built-in ``Identify`` message that all Actors will understand
and automatically reply to with a ``ActorIdentity`` message containing the
:class:`ActorRef`. This can also be done with the ``resolveOne`` method of
the :class:`ActorSelection`, which returns a ``Future`` of the matching
:class:`ActorRef`.

For more details on how actor addresses and paths are formed and used, please refer to :ref:`addressing`.

.. note::

  Message sends to actors that are actually in the sending actor system do not
  get delivered via the remote actor ref provider. They're delivered directly,
  by the local actor ref provider.

  Aside from providing better performance, this also means that if the hostname
  you configure remoting to listen as cannot actually be resolved from within
  the very same actor system, such messages will (perhaps counterintuitively)
  be delivered just fine.


Creating Actors Remotely
^^^^^^^^^^^^^^^^^^^^^^^^

If you want to use the creation functionality in Akka remoting you have to further amend the
``application.conf`` file in the following way (only showing deployment section)::

  akka {
    actor {
      deployment {
        /sampleActor {
          remote = "akka://sampleActorSystem@127.0.0.1:2553"
        }
      }
    }
  }

The configuration above instructs Akka to react when an actor with path ``/sampleActor`` is created, i.e.
using ``system.actorOf(Props(...), "sampleActor")``. This specific actor will not be directly instantiated,
but instead the remote daemon of the remote system will be asked to create the actor,
which in this sample corresponds to ``sampleActorSystem@127.0.0.1:2553``.

Once you have configured the properties above you would do the following in code:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#sample-actor

The actor class ``SampleActor`` has to be available to the runtimes using it, i.e. the classloader of the
actor systems has to have a JAR containing the class.

.. note::

  In order to ensure serializability of ``Props`` when passing constructor
  arguments to the actor being created, do not make the factory an inner class:
  this will inherently capture a reference to its enclosing object, which in
  most cases is not serializable. It is best to create a factory method in the
  companion object of the actor’s class.

  Serializability of all Props can be tested by setting the configuration item
  ``akka.actor.serialize-creators=on``. Only Props whose ``deploy`` has
  ``LocalScope`` are exempt from this check.

You can use asterisks as wildcard matches for the actor paths, so you could specify:
``/*/sampleActor`` and that would match all ``sampleActor`` on that level in the hierarchy.
You can also use wildcard in the last position to match all actors at a certain level:
``/someParent/*``. Non-wildcard matches always have higher priority to match than wildcards, so:
``/foo/bar`` is considered **more specific** than ``/foo/*`` and only the highest priority match is used.
Please note that it **cannot** be used to partially match section, like this: ``/foo*/bar``, ``/f*o/bar`` etc.

Programmatic Remote Deployment
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To allow dynamically deployed systems, it is also possible to include
deployment configuration in the :class:`Props` which are used to create an
actor: this information is the equivalent of a deployment section from the
configuration file, and if both are given, the external configuration takes
precedence.

With these imports:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#import

and a remote address like this:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#make-address

you can advise the system to create a child on that remote node like so:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#deploy

Untrusted Mode
^^^^^^^^^^^^^^

As soon as an actor system can connect to another remotely, it may in principle
send any possible message to any actor contained within that remote system. One
example may be sending a :class:`PoisonPill` to the system guardian, shutting
that system down. This is not always desired, and it can be disabled with the
following setting::

    akka.remote.artery.untrusted-mode = on

This disallows sending of system messages (actor life-cycle commands,
DeathWatch, etc.) and any message extending :class:`PossiblyHarmful` to the
system on which this flag is set. Should a client send them nonetheless they
are dropped and logged (at DEBUG level in order to reduce the possibilities for
a denial of service attack). :class:`PossiblyHarmful` covers the predefined
messages like :class:`PoisonPill` and :class:`Kill`, but it can also be added
as a marker trait to user-defined messages.

Messages sent with actor selection are by default discarded in untrusted mode, but
permission to receive actor selection messages can be granted to specific actors
defined in configuration::

    akka.remote.artery..trusted-selection-paths = ["/user/receptionist", "/user/namingService"]

The actual message must still not be of type :class:`PossiblyHarmful`.

In summary, the following operations are ignored by a system configured in
untrusted mode when incoming via the remoting layer:

* remote deployment (which also means no remote supervision)
* remote DeathWatch
* ``system.stop()``, :class:`PoisonPill`, :class:`Kill`
* sending any message which extends from the :class:`PossiblyHarmful` marker
  interface, which includes :class:`Terminated`
* messages sent with actor selection, unless destination defined in ``trusted-selection-paths``.

.. note::

  Enabling the untrusted mode does not remove the capability of the client to
  freely choose the target of its message sends, which means that messages not
  prohibited by the above rules can be sent to any actor in the remote system.
  It is good practice for a client-facing system to only contain a well-defined
  set of entry point actors, which then forward requests (possibly after
  performing validation) to another actor system containing the actual worker
  actors. If messaging between these two server-side systems is done using
  local :class:`ActorRef` (they can be exchanged safely between actor systems
  within the same JVM), you can restrict the messages on this interface by
  marking them :class:`PossiblyHarmful` so that a client cannot forge them.


Lifecycle and Failure Recovery Model
------------------------------------

TODO


Watching Remote Actors
^^^^^^^^^^^^^^^^^^^^^^

Watching a remote actor is API wise not different than watching a local actor, as described in
:ref:`deathwatch-scala`. However, it is important to note, that unlike in the local case, remoting has to handle
when a remote actor does not terminate in a graceful way sending a system message to notify the watcher actor about
the event, but instead being hosted on a system which stopped abruptly (crashed). These situations are handled
by the built-in failure detector.

Failure Detector
^^^^^^^^^^^^^^^^

Under the hood remote death watch uses heartbeat messages and a failure detector to generate ``Terminated``
message from network failures and JVM crashes, in addition to graceful termination of watched
actor.

The heartbeat arrival times is interpreted by an implementation of
`The Phi Accrual Failure Detector <http://www.jaist.ac.jp/~defago/files/pdf/IS_RR_2004_010.pdf>`_.

The suspicion level of failure is given by a value called *phi*.
The basic idea of the phi failure detector is to express the value of *phi* on a scale that
is dynamically adjusted to reflect current network conditions.

The value of *phi* is calculated as::

  phi = -log10(1 - F(timeSinceLastHeartbeat))

where F is the cumulative distribution function of a normal distribution with mean
and standard deviation estimated from historical heartbeat inter-arrival times.

In the :ref:`remote-configuration-scala` you can adjust the ``akka.remote.watch-failure-detector.threshold``
to define when a *phi* value is considered to be a failure.

A low ``threshold`` is prone to generate many false positives but ensures
a quick detection in the event of a real crash. Conversely, a high ``threshold``
generates fewer mistakes but needs more time to detect actual crashes. The
default ``threshold`` is 10 and is appropriate for most situations. However in
cloud environments, such as Amazon EC2, the value could be increased to 12 in
order to account for network issues that sometimes occur on such platforms.

The following chart illustrates how *phi* increase with increasing time since the
previous heartbeat.

.. image:: ../images/phi1.png

Phi is calculated from the mean and standard deviation of historical
inter arrival times. The previous chart is an example for standard deviation
of 200 ms. If the heartbeats arrive with less deviation the curve becomes steeper,
i.e. it is possible to determine failure more quickly. The curve looks like this for
a standard deviation of 100 ms.

.. image:: ../images/phi2.png

To be able to survive sudden abnormalities, such as garbage collection pauses and
transient network failures the failure detector is configured with a margin,
``akka.remote.watch-failure-detector.acceptable-heartbeat-pause``. You may want to
adjust the :ref:`remote-configuration-scala` of this depending on you environment.
This is how the curve looks like for ``acceptable-heartbeat-pause`` configured to
3 seconds.

.. image:: ../images/phi3.png

Serialization
-------------

When using remoting for actors you must ensure that the ``props`` and ``messages`` used for
those actors are serializable. Failing to do so will cause the system to behave in an unintended way.

For more information please see :ref:`serialization-scala`.

ByteBuffer based serialization
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TODO

Disabling the Java Serializer
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TODO

Routers with Remote Destinations
--------------------------------

It is absolutely feasible to combine remoting with :ref:`routing-scala`.

A pool of remote deployed routees can be configured as:

.. includecode:: ../scala/code/docs/routing/RouterDocSpec.scala#config-remote-round-robin-pool

This configuration setting will clone the actor defined in the ``Props`` of the ``remotePool`` 10
times and deploy it evenly distributed across the two given target nodes.

A group of remote actors can be configured as:

.. includecode:: ../scala/code/docs/routing/RouterDocSpec.scala#config-remote-round-robin-group

This configuration setting will send messages to the defined remote actor paths.
It requires that you create the destination actors on the remote nodes with matching paths.
That is not done by the router.

.. _remote-sample-scala-artery:

Remoting Sample
---------------

There is a more extensive remote example that comes with `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_.
The tutorial named `Akka Remote Samples with Scala <http://www.lightbend.com/activator/template/akka-sample-remote-scala>`_
demonstrates both remote deployment and look-up of remote actors.

Performance tuning
------------------

Dedicated lane for large messages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TODO

External, shared Aeron media driver
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TODO

Fine-tuning CPU usage latency tradeoff
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

TODO

Remote Configuration
--------------------

There are lots of configuration properties that are related to remoting in Akka. We refer to the
:ref:`reference configuration <config-akka-remote>` for more information.

.. note::

   Setting properties like the listening IP and port number programmatically is
   best done by using something like the following:

   .. includecode:: ../java/code/docs/remoting/RemoteDeploymentDocTest.java#programmatic


.. _remote-configuration-nat-artery:

Akka behind NAT or in a Docker container
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In setups involving Network Address Translation (NAT), Load Balancers or Docker
containers the hostname and port pair that Akka binds to will be different than the "logical"
host name and port pair that is used to connect to the system from the outside. This requires
special configuration that sets both the logical and the bind pairs for remoting.

.. code-block:: ruby

  akka {
    remote {
      artery {
        canonical.hostname = my.domain.com      # external (logical) hostname
        canonical.port = 8000                   # external (logical) port

        bind.hostname = local.address # internal (bind) hostname
        bind.port = 25520              # internal (bind) port
      }
   }
  }
