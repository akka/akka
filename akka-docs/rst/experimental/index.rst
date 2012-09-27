.. _experimental:

####################
Experimental Modules
####################

The following modules of Akka are marked as experimental, which means
that they are in early access mode, which also means that they are not
covered by commercial support. The purpose of releasing them early, as 
experimental, is to make them easily available and improve based on 
feedback, or even discover that the module wasn't useful.

An experimental module doesn't have to obey the rule of staying binary
compatible between minor releases. Breaking API changes may be introduced
in minor releases without notice as we refine and simplify based on your
feedback. An experimental module may be dropped in major releases without 
prior deprecation.

.. toctree::
   :maxdepth: 1

   ../cluster/index
   ../dev/multi-node-testing

Another reason for marking a module as experimental is that it's too early
to tell if the module has a maintainer that can take the responsibility
of the module over time. These modules live in the ``akka-contrib`` subproject:

.. toctree::
   :maxdepth: 1

   ../contrib/index

