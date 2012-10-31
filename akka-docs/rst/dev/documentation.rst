
.. highlightlang:: rest

.. _documentation:

#########################
 Documentation Guidelines
#########################

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

Build the documentation
=======================

First install `Sphinx`_. See below.

Building
--------

For the html version of the docs::

    sbt sphinx:generate-html

    open <project-dir>/akka-docs/target/sphinx/html/index.html

For the pdf version of the docs::

    sbt sphinx:generate-pdf

    open <project-dir>/akka-docs/target/sphinx/latex/Akka.pdf

Installing Sphinx on OS X
-------------------------

Install `Homebrew <https://github.com/mxcl/homebrew>`_

Install Python and pip:

::

  brew install python
  /usr/local/share/python/easy_install pip

Add the Homebrew Python path to your $PATH:

::

  /usr/local/Cellar/python/2.7.1/bin


More information in case of trouble:
https://github.com/mxcl/homebrew/wiki/Homebrew-and-Python

Install sphinx:

::

  pip install sphinx

Add sphinx_build to your $PATH:

::

  /usr/local/share/python

Install BasicTeX package from:
http://www.tug.org/mactex/morepackages.html

Add texlive bin to $PATH:

::

  /usr/local/texlive/2010basic/bin/universal-darwin

Add missing tex packages:

::

  sudo tlmgr update --self
  sudo tlmgr install titlesec
  sudo tlmgr install framed
  sudo tlmgr install threeparttable
  sudo tlmgr install wrapfig
  sudo tlmgr install helvetic
  sudo tlmgr install courier

Link the akka pygments style:

::

  cd /usr/local/Cellar/python/2.7.1/lib/python2.7/site-packages/pygments/styles
  ln -s /path/to/akka/akka-docs/themes/akka/pygments/akka.py akka.py
