.. _support:

#########
 Project
#########

Commercial Support
^^^^^^^^^^^^^^^^^^

Commercial support is provided by `Lightbend <http://www.lightbend.com>`_.
Akka is part of the `Lightbend Reactive Platform <http://www.lightbend.com/platform>`_.

Mailing List
^^^^^^^^^^^^

`Akka User Google Group <http://groups.google.com/group/akka-user>`_

`Akka Developer Google Group <http://groups.google.com/group/akka-dev>`_


Downloads
^^^^^^^^^

`<http://akka.io/downloads>`_


Source Code
^^^^^^^^^^^

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/akka/akka>`_


Releases Repository
^^^^^^^^^^^^^^^^^^^

All Akka releases are published via Sonatype to Maven Central, see
`search.maven.org
<http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.typesafe.akka%22>`_
or
`search.maven.org (Akka versions before 2.4.3)
<http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.typesafe.akka%22>`_

Snapshots Repository
^^^^^^^^^^^^^^^^^^^^

Nightly builds are available in http://repo.akka.io/snapshots/ as both ``SNAPSHOT`` and
timestamped versions.

For timestamped versions, pick a timestamp from
http://repo.akka.io/snapshots/com/lightbend/akka/akka-actor_@binVersion@/.
All Akka modules that belong to the same build have the same timestamp.

sbt definition of snapshot repository
-------------------------------------

Make sure that you add the repository to the sbt resolvers::

  resolvers += "Lightbend Snapshots" at "http://repo.akka.io/snapshots/"

Define the library dependencies with the timestamp as version. For example::

    libraryDependencies += "com.typesafe.akka" % "akka-remote_@binVersion@" %
      "2.1-20121016-001042"

maven definition of snapshot repository
---------------------------------------

Make sure that you add the repository to the maven repositories in pom.xml::

  <repositories>
    <repository>
      <id>akka-snapshots</id>
      <name>Akka Snapshots</name>
      <url>http://repo.akka.io/snapshots/</url>
      <layout>default</layout>
    </repository>
  </repositories>  

Define the library dependencies with the timestamp as version. For example::

  <dependencies>
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-remote_@binVersion@</artifactId>
      <version>2.1-20121016-001042</version>
    </dependency>
  </dependencies>



