
.. _cluster_metrics_java:

Cluster Metrics Extension
=========================

Introduction
------------

The member nodes of the cluster can collect system health metrics and publish that to other cluster nodes
and to the registered subscribers on the system event bus with the help of Cluster Metrics Extension.

Cluster metrics information is primarily used for load-balancing routers,
and can also be used to implement advanced metrics-based node life cycles,
such as "Node Let-it-crash" when CPU steal time becomes excessive.

Cluster Metrics Extension is a separate akka module delivered in ``akka-cluster-metrics`` jar. 

To enable usage of the extension you need to add the following dependency to your project: 
::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-cluster-metrics_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

and add the following configuration stanza to your ``application.conf``
::

   akka.extensions = [ "akka.cluster.metrics.ClusterMetricsExtension" ]
 
Make sure to disable legacy metrics in akka-cluster: ``akka.cluster.metrics.enabled=off``,
since it is still enabled in akka-cluster by default (for compatibility with past releases).

Cluster members with status :ref:`WeaklyUp <weakly_up_java>`, if that feature is enabled,
will participate in Cluster Metrics collection and dissemination.
 
Metrics Collector
-----------------

Metrics collection is delegated to an implementation of ``akka.cluster.metrics.MetricsCollector``.

Different collector implementations provide different subsets of metrics published to the cluster.
Certain message routing and let-it-crash functions may not work when Sigar is not provisioned.

Cluster metrics extension comes with two built-in collector implementations:

#. ``akka.cluster.metrics.SigarMetricsCollector``, which requires Sigar provisioning, and is more rich/precise 
#. ``akka.cluster.metrics.JmxMetricsCollector``, which is used as fall back, and is less rich/precise

You can also plug-in your own metrics collector implementation.

By default, metrics extension will use collector provider fall back and will try to load them in this order: 

#. configured user-provided collector
#. built-in ``akka.cluster.metrics.SigarMetricsCollector`` 
#. and finally ``akka.cluster.metrics.JmxMetricsCollector``

Metrics Events
--------------

Metrics extension periodically publishes current snapshot of the cluster metrics to the node system event bus.

The publication period is controlled by the ``akka.cluster.metrics.collector.sample-period`` setting.

The payload of the ``akka.cluster.metrics.ClusterMetricsChanged`` event will contain
latest metrics of the node as well as other cluster member nodes metrics gossip
which was received during the collector sample period.

You can subscribe your metrics listener actors to these events in order to implement custom node lifecycle
::

    ClusterMetricsExtension.get(system).subscribe(metricsListenerActor); 

Hyperic Sigar Provisioning
--------------------------

Both user-provided and built-in metrics collectors can optionally use `Hyperic Sigar <http://www.hyperic.com/products/sigar>`_
for a wider and more accurate range of metrics compared to what can be retrieved from ordinary JMX MBeans.

Sigar is using a native o/s library, and requires library provisioning, i.e. 
deployment, extraction and loading of the o/s native library into JVM at runtime. 

User can provision Sigar classes and native library in one of the following ways:

#. Use `Kamon sigar-loader <https://github.com/kamon-io/sigar-loader>`_ as a project dependency for the user project.
   Metrics extension will extract and load sigar library on demand with help of Kamon sigar provisioner.
#. Use `Kamon sigar-loader <https://github.com/kamon-io/sigar-loader>`_ as java agent: ``java -javaagent:/path/to/sigar-loader.jar``.
   Kamon sigar loader agent will extract and load sigar library during JVM start.
#. Place ``sigar.jar`` on the ``classpath`` and Sigar native library for the o/s on the ``java.library.path``.
   User is required to manage both project dependency and library deployment manually.
 
To enable usage of Sigar you can add the following dependency to the user project  
::

  <dependency>
    <groupId>io.kamon</groupId>
    <artifactId>sigar-loader</artifactId>
    <version>@sigarLoaderVersion@</version>
  </dependency>

You can download Kamon sigar-loader from `Maven Central <http://search.maven.org/#search%7Cga%7C1%7Csigar-loader>`_

Adaptive Load Balancing
-----------------------

The ``AdaptiveLoadBalancingPool`` / ``AdaptiveLoadBalancingGroup`` performs load balancing of messages to cluster nodes based on the cluster metrics data.
It uses random selection of routees with probabilities derived from the remaining capacity of the corresponding node.
It can be configured to use a specific MetricsSelector to produce the probabilities, a.k.a. weights:

* ``heap`` / ``HeapMetricsSelector`` - Used and max JVM heap memory. Weights based on remaining heap capacity; (max - used) / max
* ``load`` / ``SystemLoadAverageMetricsSelector`` - System load average for the past 1 minute, corresponding value can be found in ``top`` of Linux systems. The system is possibly nearing a bottleneck if the system load average is nearing number of cpus/cores. Weights based on remaining load capacity; 1 - (load / processors) 
* ``cpu`` / ``CpuMetricsSelector`` - CPU utilization in percentage, sum of User + Sys + Nice + Wait. Weights based on remaining cpu capacity; 1 - utilization
* ``mix`` / ``MixMetricsSelector`` - Combines heap, cpu and load. Weights based on mean of remaining capacity of the combined selectors.
* Any custom implementation of ``akka.cluster.metrics.MetricsSelector``

The collected metrics values are smoothed with `exponential weighted moving average <http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average>`_. In the :ref:`cluster_configuration_java` you can adjust how quickly past data is decayed compared to new data.

Let's take a look at this router in action. What can be more demanding than calculating factorials?

The backend worker that performs the factorial calculation:

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/FactorialBackend.java#backend

The frontend that receives user jobs and delegates to the backends via the router:

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/FactorialFrontend.java#frontend


As you can see, the router is defined in the same way as other routers, and in this case it is configured as follows:

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/resources/factorial.conf#adaptive-router

It is only ``router`` type and the ``metrics-selector`` parameter that is specific to this router, 
other things work in the same way as other routers.

The same type of router could also have been defined in code:

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/Extra.java#router-lookup-in-code

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/Extra.java#router-deploy-in-code

The `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_ tutorial named
`Akka Cluster Samples with Java <http://www.lightbend.com/activator/template/akka-sample-cluster-java>`_.
contains the full source code and instructions of how to run the **Adaptive Load Balancing** sample.

Subscribe to Metrics Events
---------------------------

It is possible to subscribe to the metrics events directly to implement other functionality.

.. includecode:: ../../../akka-samples/akka-sample-cluster-java/src/main/java/sample/cluster/factorial/MetricsListener.java#metrics-listener

Custom Metrics Collector
------------------------

Metrics collection is delegated to the implementation of ``akka.cluster.metrics.MetricsCollector``

You can plug-in your own metrics collector instead of built-in
``akka.cluster.metrics.SigarMetricsCollector`` or ``akka.cluster.metrics.JmxMetricsCollector``. 

Look at those two implementations for inspiration. 

Custom metrics collector implementation class must be specified in the 
``akka.cluster.metrics.collector.provider`` configuration property.

Configuration
-------------

The Cluster metrics extension can be configured with the following properties:

.. includecode:: ../../../akka-cluster-metrics/src/main/resources/reference.conf
