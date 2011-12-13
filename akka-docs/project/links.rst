.. _support:

`Support <http://typesafe.com>`__
=========================================

`Typesafe <http://typesafe.com>`_

`Mailing List <http://groups.google.com/group/akka-user>`_
==========================================================

`Akka User Google Group <http://groups.google.com/group/akka-user>`_

`Akka Developer Google Group <http://groups.google.com/group/akka-dev>`_


`Downloads <http://akka.io/downloads/>`_
========================================

`<http://akka.io/downloads/>`_


`Source Code <http://github.com/jboner/akka>`_
==============================================

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/jboner/akka>`_


`Maven Repository <http://akka.io/repository/>`_
================================================

The Akka Maven repository can be found at `<http://akka.io/repository>`_. 

Typesafe provides `<http://repo.typesafe.com/typesafe/releases/>`_ that proxies several other repositories, including akka.io.
It is convenient to use the Typesafe repository, since it includes all external dependencies of Akka. 
It is a "best-effort" service, and if it is unavailable you may need to use the underlying repositories
directly.  

* http://akka.io/repository
* http://repository.codehaus.org
* http://guiceyfruit.googlecode.com/svn/repo/releases/
* http://repository.jboss.org/nexus/content/groups/public/
* http://download.java.net/maven/2
* http://oss.sonatype.org/content/repositories/releases
* http://download.java.net/maven/glassfish
* http://databinder.net/repo   

SNAPSHOT Versions
=================

Nightly builds are available in `<http://repo.typesafe.com/typesafe/akka-snapshots/>`_ repository as
timestamped snapshot versions. Pick a timestamp from 
`<http://repo.typesafe.com/typesafe/akka-snapshots/com/typesafe/akka/akka-actor/>`_. 
All Akka modules that belong to the same build have the same timestamp.

Make sure that you add the repository to the sbt resolvers or maven repositories::
 
  resolvers += "Typesafe Timestamp Repo" at "http://repo.typesafe.com/typesafe/akka-snapshots/"
  
Define the library dependencies with the timestamp as version::

    libraryDependencies += "com.typesafe.akka" % "akka-actor" % "2.0-20111118-000627"

    libraryDependencies += "com.typesafe.akka" % "akka-remote" % "2.0-20111118-000627"


