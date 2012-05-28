.. _http-module:

HTTP
====

Play2-mini
----------

The Akka team recommends the `Play2-mini <https://github.com/typesafehub/play2-mini>`_ framework when building RESTful
service applications that integrates with Akka. It provides a REST API on top of `Play2 <https://github.com/playframework/Play20/>`_.

Getting started
---------------

First you must make your application aware of play-mini.
In SBT you just have to add the following to your _libraryDependencies_::

  libraryDependencies += "com.typesafe" %% "play-mini" % "<version-number>"
