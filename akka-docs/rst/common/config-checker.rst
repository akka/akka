.. _config_checker:

######################
 Configuration Checker
######################

Akka comes with a massive amount of configuration settings that can be tweaked.
It can be difficult to know which knobs to turn and which to leave alone.
Finding correct values and appropriate relations between different settings may
seem like a black art. In many cases wrong configuration will contribute to
terrible stability and bad performance.

This utility tries to help you by finding potential configuration issues.
It is based on knowledge that the Akka Team has gathered from typical misunderstanding 
seen in mailing lists and customer consulting.

The advice the tool can give is of course of general character and there will always be
cases where it is wrong and the given configuration is appropriate for the specific
system. Do not hesitate to use `Typesafe Support <http://support.typesafe.com/>`_ if
you need more advice or discussion.

As a general rule; use default values until you have a problem or are sure that the
adjustment is needed.

.. note:: This is a feature of the `Typesafe Reactive Platform <http://www.typesafe.com/products/typesafe-reactive-platform>`_
          that is exclusively available for 
          `Typesafe Project Success Subscription <http://www.typesafe.com/subscription>`_ customers.

What is the output?
===================

By default the checker will be run when the actor system is started and it will log 
recommendations at warning log level. Those log messages start with "Typesafe recommendation: ",
so it should be easy to find them. Such a recommendation log message may look like:

+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| [WARN] [10/01/2015 18:25:03.107] [main] [akka.diagnostics.ConfigChecker] Typesafe recommendation: Use throughput-deadline-time when dispatcher is configured with high throughput [200] batching to avoid unfair processing. Related config properties: [my-dispatcher.throughput = 200, my-dispatcher.throughput-deadline-time]. You may disable this check by adding [dispatcher-throughput] to configuration string list akka.diagnostics.checker.disabled-checks. Please use http://support.typesafe.com/ if you need more advice around this warning. |
+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

The log messages are emitted by the ``akka.diagnostics.ConfigChecker`` logger, which is good to
know if you want to configure your logging backend (e.g. logback, log4j) to direct
those to a separate file. 

To spot potential configuration issues immediately it can be good to have a test that
starts an actor system with the production like configuration. This test can be
configured to fail the startup of the actor system when configuration issues are found.
An `IllegalArgumentException` will then be thrown from ``ActorSystem.apply/create`` 
if there are any issues.
::

    # If this poperty is "on" an IllegalArgumentException is thrown
    # if there are any issues.
    akka.diagnostics.checker.fail-on-warning = on

Typos and misplacements
=======================

An annoying mistake is to use wrong spelling for a setting or place it in the wrong
section so that it is not used. For example:

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#typo
    
That would result in these warnings:

+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| akka.log-level is not an Akka configuration setting. Is it a typo or is it placed in the wrong section? Application specific properties should be placed outside the "akka" config tree. Related config properties: [akka.log-level]. You may disable this check by adding [typo] to configuration string list akka.diagnostics.checker.disabled-checks. |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
 
+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| akka.default-dispatcher.throughput is not an Akka configuration setting. Is it a typo or is it placed in the wrong section? Application specific properties should be placed outside the "akka" config tree. Related config properties: [akka.default-dispatcher.throughput]. You may disable this check by adding [typo] to configuration string list akka.diagnostics.checker.disabled-checks. |
+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

These mistakes are found by looking for properties that are defined in the application 
configuration but have no corresponding property in the ``reference.conf``, with some 
exceptions to sections that are supposed to be added to (e.g. ``akka.actor.serializers``).

It is only checking for typos in configuration paths starting with "akka".

Application specific properties that are not defined by Akka should be placed outside 
the "akka" config tree, but if you still have to define such a configuration property
inside "akka" you can confirm that it is not a typo or misplacement by adding the 
configuration path to ``akka.diagnostics.checker.confirmed-typos``. All properties starting
with that path will not be checked for typos, i.e. you can add the path of a whole section
to skip everything inside that section.
::

    akka.diagnostics.checker.confirmed-typos = [
      akka.myapp.call-timeout,
      akka.some-library
    ]


Power user settings
===================

Many configuration settings are low level tuning parameters or simply constants that
we have placed in the ``reference.conf`` because we dislike magic values in the source code.

We have classified such settings as advanced "power user" settings. You should be 
sure that you fully understand the implications of changing the default value of such
settings. It may result in negative side-effects that you did not think about. Sometimes
it might only reduce symptoms and not fix the root cause of the problem. 

Let us repeat the general rule; use default values until you have a problem or are sure that the
adjustment is needed. Please verify with tests and measurements that a change is really an
improvement.

Example:

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#power-user

The warning would look like:

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| akka.cluster.gossip-interval is an advanced configuration setting. Make sure that you fully understand the implications of changing the default value. You can confirm that you know the meaning of this configuration setting by adding [akka.cluster.gossip-interval] to configuration string list akka.diagnostics.checker.confirmed-power-user-settings. Related config properties: [akka.cluster.gossip-interval = 5s]. Corresponding default values: [akka.cluster.gossip-interval = 1s]. You may disable this check by adding [power-user-settings] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

As you can see in the warning message you can confirm that you know the meaning of a 
specific configuration setting that is classified as "power user" setting.
When confirmed it will not warn about that setting any more.
::

    akka.diagnostics.checker.confirmed-power-user-settings = [
      akka.cluster.gossip-interval]

Dispatchers
===========

Tuning of dispatchers is a common question we get in design and code reviews. As with all tuning
that depends a lot on the application and must be tested and measured, but there are a few things
that should be avoided. The checker will detect the following potential dispatcher issues.

Default dispatcher
------------------

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#default-dispatcher-size-large

+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Don't use too large pool size [512] for the default-dispatcher. Note that the pool size is calculated by ceil(available processors * parallelism-factor), and then bounded by the parallelism-min and parallelism-max values. This machine has [8] available processors. If you use a large pool size here because of blocking execution you should instead use a dedicated dispatcher to manage blocking tasks/actors. Blocking execution shouldn't run on the default-dispatcher because that may starve system internal tasks. Related config properties: [akka.actor.default-dispatcher]. You may disable this check by adding [default-dispatcher-size] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

There are a few more checks for the default dispatcher:

* pool size not too small
* the type not PinnedDispatcher and not calling thread dispatcher
* throughput settings as described in next section 

Throughput settings
-------------------

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#dispatcher-throughput

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Use throughput-deadline-time when dispatcher is configured with high throughput [200] batching to avoid unfair processing. Related config properties: [my-dispatcher.throughput = 200, my-dispatcher.throughput-deadline-time]. You may disable this check by adding [dispatcher-throughput] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

Number of dispatchers
---------------------

+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| You have configured [11] different custom dispatchers. Do you really need that many dispatchers. Separating into CPU bound tasks and blocking (IO) tasks are often enough. Related config properties: [disp-11, disp-2, disp-6, disp-3, disp-9, disp-5, disp-8, disp-1, disp-7, disp-10, disp-4]. You may disable this check by adding [dispatcher-count] to configuration string list akka.diagnostics.checker.disabled-checks. |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

Total number of threads
-----------------------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| You have a total of [1000] threads in all configured dispatchers. That many threads might result in reduced performance. This machine has [8] available processors. Related config properties: [disp3, disp2, disp1]. You may disable this check by adding [dispatcher-total-size] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

Fork join pool size
-------------------

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#fork-join-large

+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Don't use too large pool size [100] for fork-join pool. Note that the pool size is calculated by ceil(available processors * parallelism-factor), and then bounded by the parallelism-min and parallelism-max values. This machine has [8] available processors. If you use a large pool size here because of blocking execution you should use a thread-pool-executor instead. Related config properties: [my-fjp]. You may disable this check by adding [fork-join-pool-size] to configuration string list akka.diagnostics.checker.disabled-checks. |
+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+


Failure detectors
=================

There are 3 different failure detectors that monitor remote connections.

Cluster Failure Detector
------------------------

When using Akka Cluster this is the important failure detector, and you should normally not 
worry about the other two failure detectors. Each node in an Akka Cluster monitors a few
other nodes by sending heartbeat messages to them and expecting timely response messages.
If no heartbeat replies are received within a timeout the node is marked as unreachable.
A node marked as unreachable will become reachable again if the failure detector observes 
that it can communicate with it again, i.e. unreachable is not a fatal condition.

You may want quick failure detection to avoid sending messages to the void, but too short
timeouts will result in too many false failure detections caused by for example GC pauses.

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#cluster-fd-short

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Cluster failure detector acceptable-heartbeat-pause of [1000 ms] is probably too short to be meaningful. It may cause marking nodes unreachable and then back to reachable because of false failure detection caused by for example GC pauses. Related config properties: [akka.cluster.failure-detector.acceptable-heartbeat-pause = 1s]. Corresponding default values: [akka.cluster.failure-detector.acceptable-heartbeat-pause = 3 s]. You may disable this check by adding [cluster-failure-detector] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

You should normally not change the default ``heartbeat-interval``, but if you do you should
maintain a good ratio between the ``acceptable-heartbeat-pause`` and the ``heartbeat-interval``, 
i.e. allow for a few "lost" heartbeats.

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#cluster-fd-ratio

+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Cluster failure detector ratio [2] between acceptable-heartbeat-pause and heartbeat-interval is too small, decrease the heartbeat-interval and/or increase acceptable-heartbeat-pause. Otherwise it may trigger false failure detection and resulting in quarantining of remote system. Related config properties: [akka.cluster.failure-detector.acceptable-heartbeat-pause = 6s, akka.cluster.failure-detector.heartbeat-interval = 3s]. Corresponding default values: [akka.cluster.failure-detector.acceptable-heartbeat-pause = 3 s, akka.cluster.failure-detector.heartbeat-interval = 1 s]. You may disable this check by adding [cluster-failure-detector] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

There are a few more checks related to the Cluster failure detector:

* not too short ``heartbeat-interval``
* not too long ``heartbeat-interval``
* not too long ``acceptable-heartbeat-pause``
* sane relation between ``heartbeat-interval`` and ``akka.cluster.unreachable-nodes-reaper-interval``

Remote Watch Failure Detector
-----------------------------

In case you are not using Akka Cluster but plain Akka Remoting the remote watch failure detector is used
for :meth:`watch` between actors running on different nodes. 

Note that it is not used for :meth:`watch` between actors that are running on nodes in the same Akka Cluster, 
but if you :meth:`watch` between different clusters or to external non-cluster nodes it is used. Such external
:meth:`watch` is by the way something we recommend against, since it creates a too tight coupling between
the nodes/clusters.

When the remote watch failure detector triggers the remote address is quarantined, which is a fatal
condition and one of the nodes must be restarted before they can communicate again. Note that this is
a major difference to the Cluster failure detector. Therefore it is important to avoid false failure 
detections becuase of for example long GC pauses.

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#remote-watch-fd-short

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Remote watch failure detector acceptable-heartbeat-pause of [3000 ms] is probably too short to be meaningful. It may cause quarantining of remote system because of false failure detection caused by for example GC pauses. Related config properties: [akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 3s]. Corresponding default values: [akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 10 s]. You may disable this check by adding [remote-watch-failure-detector] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

There are a few more checks related to the Remote watch failure detector:

* not too short ``heartbeat-interval``
* not too long ``heartbeat-interval``
* not too long ``acceptable-heartbeat-pause``
* sane ratio betwen ``heartbeat-interval`` and ``acceptable-heartbeat-pause`` 
* sane relation between ``heartbeat-interval`` and ``unreachable-nodes-reaper-interval``

Transport Failure Detector
--------------------------

The remote transport failure detector is the least interesting of the three and you should normally
not touch it. It is used for detecting broken associations (connections), but when using TCP (or SSL) 
that is handled by TCP itself in most cases.

There are similar checks as for the Cluster and Remote watch failure detectors:

* not too short ``heartbeat-interval``
* not too long ``heartbeat-interval``
* not too short ``acceptable-heartbeat-pause``
* not too long ``acceptable-heartbeat-pause``
* sane ratio betwen ``heartbeat-interval`` and ``acceptable-heartbeat-pause``

More akka-actor checks
======================

actor-ref-provider
------------------

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| [some.Other] is not a supported ActorRef provider. Use one of [akka.actor.LocalActorRefProvider, akka.remote.RemoteActorRefProvider, akka.cluster.ClusterActorRefProvider]. Related config properties: [akka.actor.provider = some.Other]. Corresponding default values: [akka.actor.provider = akka.actor.LocalActorRefProvider]. You may disable this check by adding [actor-ref-provider] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

jvm-exit-on-fatal-error
-----------------------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Don't use jvm-exit-on-fatal-error=off. It's safer to shutdown the JVM in case of a fatal error, such as OutOfMemoryError. Related config properties: [akka.jvm-exit-on-fatal-error = off]. Corresponding default values: [akka.jvm-exit-on-fatal-error = on]. You may disable this check by adding [jvm-exit-on-fatal-error] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

More akka-remote checks
=======================

remote-dispatcher
-----------------

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Use a dedicated dispatcher for remoting instead of default-dispatcher. The internal actors in remoting may use the threads in a way that should not interfere with other system internal tasks that are running on the default-dispatcher. It can be things like serialization and blocking DNS lookups. Related config properties: [akka.remote.use-dispatcher = akka.actor.default-dispatcher]. Corresponding default values: [akka.remote.use-dispatcher = akka.remote.default-remote-dispatcher]. You may disable this check by adding [remote-dispatcher] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

secure-cookie
-------------

+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Secure cookie is not a proper security solution. It is deprecated in Akka 2.4.x. Related config properties: [akka.remote.require-cookie = on, akka.remote.secure-cookie = abc]. Corresponding default values: [akka.remote.require-cookie = off, akka.remote.secure-cookie = ]. You may disable this check by adding [secure-cookie] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

retry-gate-closed-for
---------------------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Remote retry-gate-closed-for of [100 ms] is probably too short to be meaningful. This setting controls how much time should be elapsed before reattempting a new connection after a failed outbound connection. Setting it to a short interval may result in a storm of reconnect attempts.  Related config properties: [akka.remote.retry-gate-closed-for = 100ms]. Corresponding default values: [akka.remote.retry-gate-closed-for = 5 s]. You may disable this check by adding [retry-gate-closed-for] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Remote retry-gate-closed-for of [11000 ms] is probably too long. All messages sent to the gated address are dropped during the gating period. Related config properties: [akka.remote.retry-gate-closed-for = 11s]. Corresponding default values: [akka.remote.retry-gate-closed-for = 5 s]. You may disable this check by adding [retry-gate-closed-for] to configuration string list akka.diagnostics.checker.disabled-checks. |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

prune-quarantine-marker-after
-----------------------------

+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Don't change prune-quarantine-marker-after to a small value to re-enable communication with quarantined nodes. Such feature is not supported and any behavior between the affected systems after lifting the quarantine is undefined. Related config properties: [akka.remote.prune-quarantine-marker-after = 3600s]. Corresponding default values: [akka.remote.prune-quarantine-marker-after = 5 d]. You may disable this check by adding [prune-quarantine-marker-after] to configuration string list akka.diagnostics.checker.disabled-checks. |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

enabled-transports
------------------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| [akka.remote.netty.udp] is not a recommended transport for remote actor messages in production. Related config properties: [akka.remote.enabled-transports]. You may disable this check by adding [enabled-transports] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

hostname
--------

+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| hostname is not defined, which means that `InetAddress.getLocalHost.getHostAddress` will be used to resolve the hostname. That can result in wrong hostname in some environments, such as "127.0.1.1". Define the hostname explicitly instead. On this machine `InetAddress.getLocalHost.getHostAddress` is [192.168.2.189]. Related config properties: [akka.remote.netty.tcp.hostname = ]. Corresponding default values: [akka.remote.netty.tcp.hostname = ]. You may disable this check by adding [hostname] to configuration string list akka.diagnostics.checker.disabled-checks. |
+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

maximum-frame-size
------------------

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| You have configured maximum-frame-size to [2097152 bytes]. We recommend against sending too large messages, since that may cause other messages to be delayed. For example, it's important that failure detector heartbeat messages have a chance to get through without too long delays. Try to split up large messages into smaller chunks, or use another communication channel (HTTP, Akka IO) for large payloads. Related config properties: [akka.remote.netty.tcp.maximum-frame-size = 2MiB]. Corresponding default values: [akka.remote.netty.tcp.maximum-frame-size = 128000b]. You may disable this check by adding [maximum-frame-size] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

default-remote-dispatcher-size
------------------------------

+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Don't use too small pool size [1] for the default-remote-dispatcher-size. Related config properties: [akka.remote.default-remote-dispatcher]. You may disable this check by adding [default-remote-dispatcher-size] to configuration string list akka.diagnostics.checker.disabled-checks. |
+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

More akka-cluster checks
========================

auto-down
---------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Use Akka Split Brain Resolver instead of auto-down, since auto-down may cause the cluster to be split into two separate disconnected clusters when there are network partitions, long garbage collection pauses or system overload. This is especially important if you use Cluster Singleton, Cluster Sharding, or Persistence. Related config properties: [akka.cluster.auto-down-unreachable-after = 10s]. Corresponding default values: [akka.cluster.auto-down-unreachable-after = off]. You may disable this check by adding [auto-down] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

See :ref:`split_brain_resolver_scala`. 

split-brain-resolver
--------------------

+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| It is normally best to configure akka.cluster.down-removal-margin and akka.cluster.split-brain-resolver.stable-after to the same duration.  Related config properties: [akka.cluster.down-removal-margin = 10s, akka.cluster.split-brain-resolver.stable-after = 20s]. Corresponding default values: [akka.cluster.down-removal-margin = 20s, akka.cluster.split-brain-resolver.stable-after = 20s]. You may disable this check by adding [split-brain-resolver] to configuration string list akka.diagnostics.checker.disabled-checks. |
+-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

See :ref:`split_brain_resolver_scala`.

There are a few more sanity checks of the Split Brain Resolver configuration. 

cluster-dispatcher
------------------

+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Normally it should not be necessary to configure a separate dispatcher for the Cluster. The default-dispatcher should be sufficient for performing the Cluster tasks, i.e. akka.cluster.use-dispatcher should not be changed. If you have Cluster related problems when using the default-dispatcher that is typically an indication that you are running blocking or CPU intensive actors/tasks on the default-dispatcher. Use dedicated dispatchers for such actors/tasks instead of running them on the default-dispatcher, because that may starve system internal tasks. Related config properties: [akka.cluster.use-dispatcher = disp1]. Corresponding default values: [akka.cluster.use-dispatcher = ]. You may disable this check by adding [cluster-dispatcher] to configuration string list akka.diagnostics.checker.disabled-checks. |
+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

There are a few more sanity checks of the Cluster dispatcher configuration.

Run as Java main program
========================

Sometimes it can be useful to just verify a configuration file without running
the application and for that purpose the ``ConfigChecker`` can be run as a
Java main program. The main class is::

    akka.diagnostics.ConfigChecker

The configuration is loaded by the Typesafe Config library, i.e. ``application.conf`` 
if you don't specify another file with for example ``-Dconfig.file``.
See `Typesafe Config <https://github.com/typesafehub/config>`_ for details of how to 
specify configuration location.

Potential configuration issues, if any, are printed to ``System.out`` and the JVM
is exited with -1 status code.

If no configuration issues are found the message "No configuration issues found"
is printed to ``System.out`` and the JVM is exited with 0 status code.

Disable checks
==============

As can be seen in the log messages individual checks can be disabled if the advice
is not appropriate for your system. For example:

+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Use throughput-deadline-time when dispatcher is configured with high throughput [200] batching to avoid unfair processing. Related config properties: [my-dispatcher.throughput = 200, my-dispatcher.throughput-deadline-time]. You may disable this check by adding [dispatcher-throughput] to configuration string list akka.diagnostics.checker.disabled-checks. |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

To disable this recommendation and thereby suppress the log message:

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#disabled-checks

It is also possible to disable all checks with:

.. includecode:: ../../../akka-diagnostics-tests/src/test/scala/akka/diagnostics/ConfigCheckerSpec.scala#disabled

Configuration
=============

Below is the configuration of the checker itself, which you may amend to adjust its behavior
or suppress certain warnings.

.. includecode:: ../../../akka-actor/src/main/resources/reference.conf#config-checker
