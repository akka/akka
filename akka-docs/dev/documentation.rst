
.. highlightlang:: rest

.. _documentation:

###############
 Documentation
###############

The Akka documentation uses `reStructuredText`_ as its markup language and is
built using `Sphinx`_.

.. _reStructuredText: http://docutils.sourceforge.net/rst.html
.. _sphinx: http://sphinx.pocoo.org


Sphinx
======

More to come...


reStructuredText
================

More to come...

Sections
--------

Section headings are very flexible in reST. We use the following convention in
the Akka documentation:

* ``#`` (over and under) for module headings
* ``=`` for sections
* ``-`` for subsections
* ``^`` for subsubsections
* ``~`` for subsubsubsections


Cross-referencing
-----------------

Sections that may be cross-referenced across the documentation should be marked
with a reference. To mark a section use ``.. _ref-name:`` before the section
heading. The section can then be linked with ``:ref:`ref-name```. These are
unique references across the entire documentation.

For example::

  .. _akka-module:

  #############
   Akka Module
  #############

  This is the module documentation.

  .. _akka-section:

  Akka Section
  ============

  Akka Subsection
  ---------------

  Here is a reference to "akka section": :ref:`akka-section` which will have the
  name "Akka Section".

