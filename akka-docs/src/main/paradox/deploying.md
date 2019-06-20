# Package, Deploy and Run
 
* [Packaging](#packaging)
* [Deploying](#deploying)
    - [Rolling Updates](#rolling-updates)
    - [Rolling Updates and Akka versions](project/rolling-update.md)
* [Running]()
    - [Akka Management](https://doc.akka.io/docs/akka-management/current/) - a suite of tools for bootstrapping and operating Akka Cluster applications
    - [Akka Discovery](discovery/index.md) - an interface around various ways of locating services 

## Packaging

The simplest way to use Akka is as a regular library, adding the Akka jars you
need to your classpath (in case of a web app, in `WEB-INF/lib`).

In many cases, such as deploying to an analytics cluster, building your application into a single 'fat jar' is needed.
When building fat jars, some additional configuration is needed to merge Akka config files, because each Akka jar
contains a `reference.conf` resource with default values.

The method for ensuring `reference.conf` resources are merged depends on the tooling you use to create the fat jar:

 * SBT: as an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 * Maven: as an application packaged with a bundler such as jarjar, onejar or assembly
 * Gradle: using the Jar task from the Java plugin
 
### SBT: Native Packager

[sbt-native-packager](https://github.com/sbt/sbt-native-packager) is a tool for creating
distributions of any type of application, including Akka applications.

Define sbt version in `project/build.properties` file:

```none
sbt.version=0.13.13
```

Add [sbt-native-packager](https://github.com/sbt/sbt-native-packager) in `project/plugins.sbt` file:

```none
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.5")
```

Follow the instructions for the `JavaAppPackaging` in the [sbt-native-packager plugin documentation](http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html).

### Maven: jarjar, onejar or assembly

You can use the [Apache Maven Shade Plugin](http://maven.apache.org/plugins/maven-shade-plugin)
support for [Resource Transformers](http://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html#AppendingTransformer)
to merge all the reference.confs on the build classpath into one.

The plugin configuration might look like this:

```
<plugin>
 <groupId>org.apache.maven.plugins</groupId>
 <artifactId>maven-shade-plugin</artifactId>
 <version>1.5</version>
 <executions>
  <execution>
   <phase>package</phase>
   <goals>
    <goal>shade</goal>
   </goals>
   <configuration>
    <shadedArtifactAttached>true</shadedArtifactAttached>
    <shadedClassifierName>allinone</shadedClassifierName>
    <artifactSet>
     <includes>
      <include>*:*</include>
     </includes>
    </artifactSet>
    <transformers>
      <transformer
       implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
       <resource>reference.conf</resource>
      </transformer>
      <transformer
       implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
       <manifestEntries>
        <Main-Class>akka.Main</Main-Class>
       </manifestEntries>
      </transformer>
    </transformers>
   </configuration>
  </execution>
 </executions>
</plugin>
```


### Gradle: the Jar task from the Java plugin

When using Gradle, you would typically use the
[Jar task from the Java plugin](https://www.baeldung.com/gradle-fat-jar)
to create the fat jar.

To make sure the `reference.conf` resources are correctly merged, you might
use the [Shadow plugin](https://imperceptiblethoughts.com/shadow/), which might
look something like this:

```
import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer

plugins {
    id 'java'
    id "com.github.johnrengelman.shadow" version "5.0.0"
}

shadowJar {
    transform(AppendingTransformer) {
        resource = 'reference.conf'
    }
    with jar
}
```

## Deploying

### Deploying to Docker containers

You can use both Akka remoting and Akka Cluster inside Docker containers. Note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](remoting-artery.md#remote-configuration-nat-artery)

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning:

#### Resource limits

Docker allows [constraining each containers' resource usage](https://docs.docker.com/config/containers/resource_constraints/).

##### Memory

You may want to look into using [`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`](https://dzone.com/articles/running-a-jvm-in-a-container-without-getting-kille) options for your JVM later than 8u131, which makes it understand c-group memory limits. On JVM 10 and later, the `-XX:+UnlockExperimentalVMOptions` option is no longer needed.

##### CPU

For multi-threaded applications such as the JVM, the CFS scheduler limits are an ill fit, because they will restrict
the allowed CPU usage even when more CPU cycles are available from the host system. This means your application may be
starved of CPU time, but your system appears idle.

For this reason, it is best to avoid `--cpus` and `--cpu-quota` entirely, and instead specify relative container weights using `--cpu-shares` instead.

### Deploying to Kubernetes

#### Cluster bootstrap

To take advantage of the fact that your are running inside of Kubernetes while forming a cluster, you can use the [Akka Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/) module.

You can look at the
@extref[Cluster with Kubernetes example project](samples:akka-sample-cluster-kubernetes-java)
to see what this looks like in practice.

#### Resource limits

To avoid CFS scheduler limits, it is best not to use `resources.limits.cpu` limits, but use `resources.requests.cpu` configuration instead.


## Rolling Updates

A rolling update is the process of replacing one version of the system with another without downtime.
The changes can be new code, changed dependencies such as new Akka version, or modified configuration.

In Akka, rolling updates are typically used for a stateful Akka Cluster where you can't run two separate clusters in
parallel during the update, for example in blue green deployments.

### Compatibility of remote message protocols and serialization

There are two parts of Akka that need careful consideration when performing an rolling update.

1. Compatibility of remote message protocols. Old nodes may send messages to new nodes and vice versa.
1. Serialization format of persisted events and snapshots. New nodes must be able to read old data, and
   during the update old nodes must be able to read data stored by new nodes.

There are many more application specific aspects. It's important to have a strategy for serialization
format that can be evolved and you can find advice in
@ref:[Persistence - Schema Evolution](persistence-schema-evolution.md), which also applies to
remote messages when deploying with rolling updates.

### Akka Cluster Configuration Compatibility Check

Information on enforcing [Akka Cluster configuration compatibility checks](cluster-usage.md#configuration-compatibility-check) on joining nodes.

### Shutdown order

It's best to keep the node that has been running the longest until last. That is because
Cluster Singletons (including Cluster Sharding coordinators) are running on the oldest nodes. It's more
efficient to avoid moving the singletons more than necessary since those typically have to recover
their state and it might introduce unnecessary delays in for example access to new sharded actors.
