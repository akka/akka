# How can I deploy Akka?

The simplest way to use Akka is as a regular library, adding the Akka jars you
need to your classpath (in case of a web app, in `WEB-INF/lib`).

Sometimes it can be useful to bundle your application into a single 'fat jar'.
If you do that, some additional configuration is needed, because each Akka jar
contains a `reference.conf` resource with default values, and these must be
merged.

How to make sure reference.conf resources are merged depends on the tooling you
are using to create the fat jar:

 * When using sbt: as an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 * When using Maven: as an application packaged with a bundler such as jarjar, onejar or assembly
 * When using Gradle: using the Jar task from the Java plugin
 
## sbt: Native Packager

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

## Maven: jarjar, onejar or assembly

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


## Gradle: the Jar task from the Java plugin

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

## In a Docker container

You can use both Akka remoting and Akka Cluster inside of Docker containers. But note
that you will need to take special care with the network configuration when using Docker,
described here: @ref:[Akka behind NAT or in a Docker container](../remoting-artery.md#remote-configuration-nat-artery)

You can look at the
@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]
to see what this looks like in practice.

For the JVM to run well in a Docker container, there are some general (not Akka specific) parameters that might need tuning:

### Resource limits

Docker allows [constraining each containers' resource usage](https://docs.docker.com/config/containers/resource_constraints/).

#### Memory

You may want to look into using [`-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap`](https://dzone.com/articles/running-a-jvm-in-a-container-without-getting-kille) options for your JVM later than 8u131, which makes it understand c-group memory limits. On JVM 10 and later, the `-XX:+UnlockExperimentalVMOptions` option is no longer needed.

#### CPU

For multi-threaded applications such as the JVM, the CFS scheduler limits are an ill fit, because they will restrict
the allowed CPU usage even when more CPU cycles are available from the host system. This means your application may be
starved of CPU time, but your system appears idle.

For this reason, it is best to avoid `--cpus` and `--cpu-quota` entirely, and instead specify relative container weights using `--cpu-shares` instead.

## In Kubernetes

### Cluster bootstrap

To take advantage of the fact that your are running inside of Kubernetes while forming a cluster, you can use the [Akka Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/) module.

You can look at the
@extref[Cluster with Kubernetes example project](samples:akka-sample-cluster-kubernetes-java)
to see what this looks like in practice.

### Resource limits

To avoid CFS scheduler limits, it is best not to use `resources.limits.cpu` limits, but use `resources.requests.cpu` configuration instead.
