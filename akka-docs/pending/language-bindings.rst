Other Language Bindings
=======================

JRuby
-----

High level concurrency using Akka actors and JRuby.

`<https://github.com/danielribeiro/RubyOnAkka>`_

If you are using STM with JRuby then you need to unwrap the Multiverse control flow exception as follows:

.. code-block:: ruby

  begin
      ... atomic stuff
  rescue NativeException => e
     raise e.cause if e.cause.java_class.package.name.include? "org.multiverse"
  end

Groovy/Groovy++
---------------

`<https://gist.github.com/620439>`_
