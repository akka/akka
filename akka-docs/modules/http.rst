.. _http-module:

HTTP
####

Play2 Mini
==========

The Akka team recommends the `Play2 Mini <https://github.com/typesafehub/play2-mini>`_ framework when building RESTful
service applications that integrates with Akka. It provides a REST API on top of `Play2 <https://github.com/playframework/Play20/>`_.

Getting started
---------------

First you must make your application aware of play-mini.
In SBT you just have to add the following to your ``libraryDependencies``::

  libraryDependencies += "com.typesafe" %% "play-mini" % "<version-number>"

Akka Mist
=========

If you are using Akka Mist (Akka's old HTTP/REST module) with Akka 1.x and wish to upgrade to 2.x
there is now a port of Akka Mist to Akka 2.x. You can find it `here <https://github.com/thenewmotion/akka-http>`_.

Other Alternatives
==================

There are a bunch of other alternatives for using Akka with HTTP/REST. You can find some of them
among the `Community Projects <http://akka.io/community>`_.
