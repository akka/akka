---
project.description: How to package an Akka application for deployment.
---
# Packaging

The simplest way to use Akka is as a regular library, adding the Akka jars you
need to your classpath (in case of a web app, in `WEB-INF/lib`).

In many cases, such as deploying to an analytics cluster, building your application into a single 'fat jar' is needed.
When building fat jars, some additional configuration is needed to merge Akka config files, because each Akka jar
contains a `reference.conf` resource with default values.

The method for ensuring `reference.conf` and other `*.conf` resources are merged depends on the tooling you use to create the fat jar:

 * sbt: as an application packaged with [sbt-native-packager](https://github.com/sbt/sbt-native-packager)
 * Maven: as an application packaged with a bundler such as jarjar, onejar or assembly
 * Gradle: using the Jar task from the Java plugin
 
## sbt: Native Packager

[sbt-native-packager](https://github.com/sbt/sbt-native-packager) is a tool for creating
distributions of any type of application, including Akka applications.

Define sbt version in `project/build.properties` file:

```none
sbt.version=1.3.12
```

Add [sbt-native-packager](https://github.com/sbt/sbt-native-packager) in `project/plugins.sbt` file:

```none
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.5")
```

Follow the instructions for the `JavaAppPackaging` in the [sbt-native-packager plugin documentation](https://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html).

## Maven: jarjar, onejar or assembly

You can use the [Apache Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/)
support for [Resource Transformers](https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html#AppendingTransformer)
to merge all the reference.confs on the build classpath into one.

The plugin configuration might look like this:

```xml
<plugin>
 <groupId>org.apache.maven.plugins</groupId>
 <artifactId>maven-shade-plugin</artifactId>
 <version>1.5</version>
 <executions>
  <execution>
   <id>shade-my-jar</id>
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
       implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
       <resource>version.conf</resource>
      </transformer>
      <transformer
       implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
       <manifestEntries>
        <Main-Class>myapp.Main</Main-Class>
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

```groovy
import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer

plugins {
    id 'java'
    id "com.github.johnrengelman.shadow" version "7.0.0"
}

shadowJar {
    append 'reference.conf'
    append 'version.conf'
    with jar
}
```

Or when you use the Kotlin DSL:

```kotlin
tasks.withType<ShadowJar> {
    val newTransformer = AppendingTransformer()
    newTransformer.resource = "reference.conf"
    transformers.add(newTransformer)
}
```
