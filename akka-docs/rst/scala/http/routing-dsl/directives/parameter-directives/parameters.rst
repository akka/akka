.. _-parameters-scala-:

parameters
==========

Signature
---------

::

    def parameters(param: <ParamDef[T]>): Directive1[T]
    def parameters(params: <ParamDef[T_i]>*): Directive[T_0 :: ... T_i ... :: HNil]
    def parameters(params: <ParamDef[T_0]> :: ... <ParamDef[T_i]> ... :: HNil): Directive[T_0 :: ... T_i ... :: HNil]

The signature shown is simplified and written in pseudo-syntax, the real signature uses magnets. [1]_ The type
``<ParamDef>`` doesn't really exist but consists of the syntactic variants as shown in the description and the examples.

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: http://spray.io/blog/2012-12-13-the-magnet-pattern/

Description
-----------
The parameters directive filters on the existence of several query parameters and extract their values.

Query parameters can be either extracted as a String or can be converted to another type. The parameter name
can be supplied either as a String or as a Symbol. Parameter extraction can be modified to mark a query parameter
as required, optional, or repeated, or to filter requests where a parameter has a certain value:

``"color"``
    extract value of parameter "color" as ``String``
``"color".?``
    extract optional value of parameter "color" as ``Option[String]``
``"color" ? "red"``
    extract optional value of parameter "color" as ``String`` with default value ``"red"``
``"color" ! "blue"``
    require value of parameter "color" to be ``"blue"`` and extract nothing
``"amount".as[Int]``
    extract value of parameter "amount" as ``Int``, you need a matching ``Deserializer`` in scope for that to work
    (see also :ref:`http-unmarshalling-scala`)
``"amount".as(deserializer)``
    extract value of parameter "amount" with an explicit ``Deserializer``
``"distance".*``
    extract multiple occurrences of parameter "distance" as ``Iterable[String]``
``"distance".as[Int].*``
    extract multiple occurrences of parameter "distance" as ``Iterable[Int]``, you need a matching ``Deserializer`` in scope for that to work
    (see also :ref:`http-unmarshalling-scala`)
``"distance".as(deserializer).*``
    extract multiple occurrences of parameter "distance" with an explicit ``Deserializer``

You can use :ref:`Case Class Extraction` to group several extracted values together into a case-class
instance.

Requests missing a required parameter or parameter value will be rejected with an appropriate rejection.

There's also a singular version, :ref:`-parameter-`. Form fields can be handled in a similar way, see ``formFields``. If
you want unified handling for both query parameters and form fields, see ``anyParams``.

Examples
--------

Required parameter
^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: required-1

Optional parameter
^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: optional

Optional parameter with default value
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: optional-with-default

Parameter with required value
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: required-value

Deserialized parameter
^^^^^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: mapped-value

Repeated parameter
^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: repeated

CSV parameter
^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: csv

Repeated, deserialized parameter
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/ParameterDirectivesExamplesSpec.scala
   :snippet: mapped-repeated
