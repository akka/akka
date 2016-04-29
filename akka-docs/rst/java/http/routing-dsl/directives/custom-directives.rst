.. _Custom Directives-java:

Custom Directives
=================
Part of the power of akka-http directives comes from the ease with which it’s possible to define
custom directives at differing levels of abstraction.

There are essentially three ways of creating custom directives:

1. By introducing new “labels” for configurations of existing directives
2. By transforming existing directives
3. By writing a directive “from scratch”

Configuration Labeling
______________________
The easiest way to create a custom directive is to simply assign a new name for a certain configuration
of one or more existing directives. In fact, most of the predefined akka-http directives can be considered
named configurations of more low-level directives.

The basic technique is explained in the chapter about Composing Directives, where, for example, a new directive
``getOrPut`` is defined like this:

.. includecode2:: ../../../code/docs/http/javadsl/server/directives/CustomDirectivesExamplesTest.java
   :snippet: labeling

Multiple directives can be nested to produce a single directive out of multiple like this:

.. includecode2:: ../../../code/docs/http/javadsl/server/directives/CustomDirectivesExamplesTest.java
   :snippet: composition


Another example is the :ref:`MethodDirectives-java` which are simply instances of a preconfigured :ref:`-method-java-` directive.
The low-level directives that most often form the basis of higher-level “named configuration” directives are grouped
together in the :ref:`BasicDirectives-java` trait.

