# Akka in OSGi

## Background

[OSGi](http://www.osgi.org/developer) is a mature packaging and deployment standard for component-based systems. It
has similar capabilities as Project Jigsaw (originally scheduled for JDK 1.8), but has far stronger facilities to
support legacy Java code. This is to say that while Jigsaw-ready modules require significant changes to most source files
and on occasion to the structure of the overall application, OSGi can be used to modularize almost any Java code as far
back as JDK 1.2, usually with no changes at all to the binaries.

These legacy capabilities are OSGi's major strength and its major weakness. The creators of OSGi realized early on that
implementors would be unlikely to rush to support OSGi metadata in existing JARs. There were already a handful of new
concepts to learn in the JRE and the added value to teams that were managing well with straight J2EE was not obvious.
Facilities emerged to "wrap" binary JARs so they could be used as bundles, but this functionality was only used in limited
situations. An application of the "80/20 Rule" here would have that "80% of the complexity is with 20% of the configuration",
but it was enough to give OSGi a reputation that has stuck with it to this day.

This document aims to the productivity basics folks need to use it with Akka, the 20% that users need to get 80% of what they want.
For more information than is provided here, [OSGi In Action](https://www.manning.com/books/osgi-in-action) is worth exploring.

## Core Components and Structure of OSGi Applications

The fundamental unit of deployment in OSGi is the `Bundle`. A bundle is a Java JAR with *additional
entries <https://www.osgi.org/bundle-headers-reference/>* in `MANIFEST.MF` that minimally expose the name and version
of the bundle and packages for import and export. Since these manifest entries are ignored outside OSGi deployments,
a bundle can interchangeably be used as a JAR in the JRE.

When a bundle is loaded, a specialized implementation of the Java `ClassLoader` is instantiated for each bundle. Each
classloader reads the manifest entries and publishes both capabilities (in the form of the `Bundle-Exports`) and
requirements (as `Bundle-Imports`) in a container singleton for discovery by other bundles. The process of matching imports to
exports across bundles through these classloaders is the process of resolution, one of six discrete steps in the lifecycle
FSM of a bundle in an OSGi container:

 1. INSTALLED: A bundle that is installed has been loaded from disk and a classloader instantiated with its capabilities.
Bundles are iteratively installed manually or through container-specific descriptors. For those familiar with legacy packging
such as EJB, the modular nature of OSGi means that bundles may be used by multiple applications with overlapping dependencies.
By resolving them individually from repositories, these overlaps can be de-duplicated across multiple deployemnts to
the same container.
 2. RESOLVED: A bundle that has been resolved is one that has had its requirements (imports) satisfied. Resolution does
mean that a bundle can be started.
 3. STARTING: A bundle that is started can be used by other bundles. For an otherwise complete application closure of
resolved bundles, the implication here is they must be started in the order directed by a depth-first search for all to
be started. When a bundle is starting, any exposed lifecycle interfaces in the bundle are called, giving the bundle
the opportunity to start its own service endpoints and threads.
 4. ACTIVE: Once a bundle's lifecycle interfaces return without error, a bundle is marked as active.
 5. STOPPING: A bundle that is stopping is in the process of calling the bundle's stop lifecycle and transitions back to
the RESOLVED state when complete. Any long running services or threads that were created while STARTING should be shut
down when the bundle's stop lifecycle is called.
 6. UNINSTALLED: A bundle can only transition to this state from the INSTALLED state, meaning it cannot be uninstalled
before it is stopped.

Note the dependency in this FSM on lifecycle interfaces. While there is no requirement that a bundle publishes these
interfaces or accepts such callbacks, the lifecycle interfaces provide the semantics of a `main()` method and allow
the bundle to start and stop long-running services such as REST web services, ActorSystems, Clusters, etc.

Secondly, note when considering requirements and capabilities, it's a common misconception to equate these with repository
dependencies as might be found in Maven or Ivy. While they provide similar practical functionality, OSGi has several
parallel type of dependency (such as Blueprint Services) that cannot be easily mapped to repository capabilities. In fact,
the core specification leaves these facilities up to the container in use. In turn, some containers have tooling to generate
application load descriptors from repository metadata.

## Notable Behavior Changes

Combined with understanding the bundle lifecycle, the OSGi developer must pay attention to sometimes unexpected behaviors
that are introduced. These are generally within the JVM specification, but are unexpected and can lead to frustration.

 * 
   Bundles should not export overlapping package spaces. It is not uncommon for legacy JVM frameworks to expect plugins
in an application composed of multiple JARs to reside under a single package name. For example, a frontend application
might scan all classes from `com.example.plugins` for specific service implementations with that package existing in
several contributed JARs.
   While it is possible to support overlapping packages with complex manifest headers, it's much better to use non-overlapping
package spaces and facilities such as @ref:[Akka Cluster](../common/cluster.md)
for service discovery. Stylistically, many organizations opt to use the root package path as the name of the bundle
distribution file.

 * Resources are not shared across bundles unless they are explicitly exported, as with classes. The common
case of this is expecting that `getClass().getClassLoader().getResources("foo")` will return all files on the classpath
named `foo`. The `getResources()` method only returns resources from the current classloader, and since there are
separate classloaders for every bundle, resource files such as configurations are no longer searchable in this manner.

## Configuring the OSGi Framework

To use Akka in an OSGi environment, the container must be configured such that the `org.osgi.framework.bootdelegation`
property delegates the `sun.misc` package to the boot classloader instead of resolving it through the normal OSGi class space.

## Intended Use

Akka only supports the usage of an ActorSystem strictly confined to a single OSGi bundle, where that bundle contains or imports
all of the actor system's requirements. This means that the approach of offering an ActorSystem as a service to which Actors
can be deployed dynamically via other bundles is not recommended — an ActorSystem and its contained actors are not meant to be
dynamic in this way. ActorRefs may safely be exposed to other bundles.

## Activator

To bootstrap Akka inside an OSGi environment, you can use the `akka.osgi.ActorSystemActivator` class
to conveniently set up the ActorSystem.

@@snip [Activator.scala]($akka$/akka-osgi/src/test/scala/docs/osgi/Activator.scala) { #Activator }

The goal here is to map the OSGi lifecycle more directly to the Akka lifecycle. The `ActorSystemActivator` creates
the actor system with a class loader that finds resources (`application.conf` and `reference.conf` files) and classes
from the application bundle and all transitive dependencies.

The `ActorSystemActivator` class is included in the `akka-osgi` artifact:

@@@vars
```
<dependency>
  <groupId>com.typesafe.akka</groupId>
  <artifactId>akka-osgi_$scala.binary_version$</artifactId>
  <version>$akka.version$</version>
</dependency>
```
@@@

## Sample

A complete sample project is provided in @extref[akka-sample-osgi-dining-hakkers](samples:akka-sample-osgi-dining-hakkers)
