.. _ParameterDirectives:

ParameterDirectives
===================

.. toctree::
   :maxdepth: 1

   parameter
   parameterMap
   parameterMultiMap
   parameters
   parameterSeq

.. _which-parameter-directive:

When to use which parameter directive?
--------------------------------------

Usually, you want to use the high-level :ref:`-parameters-scala-` directive. When you need
more low-level access you can use the table below to decide which directive
to use which shows properties of different parameter directives.

================================ ====== ======== =====
directive                        level  ordering multi
================================ ====== ======== =====
:ref:`-parameter-`               high   no       no
:ref:`-parameters-scala-`        high   no       yes
:ref:`-parameterMap-`            low    no       no
:ref:`-parameterMultiMap-`       low    no       yes
:ref:`-parameterSeq-`            low    yes      yes
================================ ====== ======== =====

level
    high-level parameter directives extract subset of all parameters by name and allow conversions
    and automatically report errors if expectations are not met, low-level directives give you
    all parameters at once, leaving all further processing to you

ordering
    original ordering from request URL is preserved

multi
    multiple values per parameter name are possible
