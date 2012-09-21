.. _support:

Commercial Support
==================

Commercial support is provided by `Typesafe <http://typesafe.com>`_.
Akka is now part of the `Typesafe Stack <http://typesafe.com/stack>`_.

Mailing List
============

`Akka User Google Group <http://groups.google.com/group/akka-user>`_

`Akka Developer Google Group <http://groups.google.com/group/akka-dev>`_


Downloads
=========

`<http://akka.io/downloads/>`_


Source Code
===========

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/akka/akka>`_


Releases Repository
===================

The Akka Maven repository can be found at http://repo.akka.io/releases/.

Typesafe provides http://repo.typesafe.com/typesafe/releases/ that proxies
several other repositories, including akka.io.  It is convenient to use the
Typesafe repository, since it includes all external dependencies of Akka.  It is
a "best-effort" service, and if it is unavailable you may need to use the
underlying repositories directly.

* http://repo.akka.io/releases/
* http://repository.codehaus.org/
* http://guiceyfruit.googlecode.com/svn/repo/releases/
* http://repository.jboss.org/nexus/content/groups/public/
* http://download.java.net/maven/2/
* http://oss.sonatype.org/content/repositories/releases/
* http://download.java.net/maven/glassfish/
* http://databinder.net/repo/


Snapshots Repository
====================

Nightly builds are available in http://repo.akka.io/snapshots/ and proxied through
http://repo.typesafe.com/typesafe/snapshots/ as both ``SNAPSHOT`` and
timestamped versions.

For timestamped versions, pick a timestamp from
http://repo.typesafe.com/typesafe/snapshots/com/typesafe/akka/akka-actor_@binVersion@/.
All Akka modules that belong to the same build have the same timestamp.

Make sure that you add the repository to the sbt resolvers or maven repositories::

  resolvers += "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"

Define the library dependencies with the timestamp as version. For example::

    libraryDependencies += "com.typesafe.akka" % "akka-actor_@binVersion@" % "2.1-20120913-000917"

    libraryDependencies += "com.typesafe.akka" % "akka-remote_@binVersion@" % "2.1-20120913-000917"
