
.. _serialization-scala:

######################
 Serialization (Scala)
######################

.. sidebar:: Contents

   .. contents:: :local:

Akka has a built-in Extension for serialization,
and it is both possible to use the built-in serializers and to write your own.

The serialization mechanism is both used by Akka internally to serialize messages,
and available for ad-hoc serialization of whatever you might need it for.

Usage
=====

Configuration
-------------

Programmatic
------------

Customization
=============

Creating new Serializers
------------------------


* `Serializers <https://github.com/jboner/akka/blob/master/akka-actor/src/main/resources/reference.conf#L180>`_