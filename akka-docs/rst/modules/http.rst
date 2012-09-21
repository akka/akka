.. _http-module:

HTTP
####

Play2 Mini
==========

The Akka team recommends the `Play2 Mini <https://github.com/typesafehub/play2-mini>`_ framework when building RESTful
service applications that integrates with Akka. It provides a REST API on top of `Play2 <https://github.com/playframework/Play20/>`_.

Getting started
---------------

Easiest way to get started with `Play2 Mini <https://github.com/typesafehub/play2-mini>`_  is to use the
G8 project templates, as described in the `Play2 Mini Documentation <https://github.com/typesafehub/play2-mini>`_.

If you already have an Akka project and want to add Play2 Mini, you must first add the following to 
your ``libraryDependencies``::

  libraryDependencies += "com.typesafe" %% "play-mini" % "<version-number>"

In case you need to start Play2 Mini programatically you can use::

  play.core.server.NettyServer.main(Array())


Akka Mist
=========

If you are using Akka Mist (Akka's old HTTP/REST module) with Akka 1.x and wish to upgrade to 2.x
there is now a port of Akka Mist to Akka 2.x. You can find it `here <https://github.com/thenewmotion/akka-http>`_.

Other Alternatives
==================

There are a bunch of other alternatives for using Akka with HTTP/REST. You can find some of them
among the `Community Projects <http://akka.io/community>`_.
