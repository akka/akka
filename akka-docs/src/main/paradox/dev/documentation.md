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

For more details see `The Sphinx Documentation <http://sphinx.pocoo.org/contents.html>`_

reStructuredText
================

For more details see `The reST Quickref <http://docutils.sourceforge.net/docs/user/rst/quickref.html>`_

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

    sbt sphinx:generateHtml

    open <project-dir>/akka-docs/target/sphinx/html/index.html

For the pdf version of the docs::

    sbt sphinx:generatePdf

    open <project-dir>/akka-docs/target/sphinx/latex/AkkaJava.pdf
    or
    open <project-dir>/akka-docs/target/sphinx/latex/AkkaScala.pdf

Installing Sphinx on OS X
-------------------------

Install `Homebrew <https://github.com/mxcl/homebrew>`_

Install Python with Homebrew:

::

  brew install python

Homebrew will automatically add Python executable to your $PATH and pip is a part of the default Python installation with Homebrew.

More information in case of trouble:
https://github.com/mxcl/homebrew/wiki/Homebrew-and-Python

Install sphinx:

::

  pip install sphinx

Install BasicTeX package from:
http://www.tug.org/mactex/morepackages.html

Add texlive bin to $PATH:

::

  export TEXLIVE_PATH=/usr/local/texlive/2016basic/bin/universal-darwin
  export PATH=$TEXLIVE_PATH:$PATH

Add missing tex packages:

::

  sudo tlmgr update --self
  sudo tlmgr install titlesec
  sudo tlmgr install framed
  sudo tlmgr install threeparttable
  sudo tlmgr install wrapfig
  sudo tlmgr install helvetic
  sudo tlmgr install courier
  sudo tlmgr install multirow
  sudo tlmgr install capt-of
  sudo tlmgr install needspace
  sudo tlmgr install eqparbox
  sudo tlmgr install environ
  sudo tlmgr install trimspaces

If you get the error "unknown locale: UTF-8" when generating the documentation the solution is to define the following environment variables:

::

  export LANG=en_US.UTF-8
  export LC_ALL=en_US.UTF-8

Installing Sphinx on Linux
--------------------------
Install Python with your package manager:

::

  apt-get install python # for Debian based systems
  yum install python     # for CentOS/RHEL systems

This will automatically add Python executable to your $PATH and pip is a part of the default Python installation. Remember you need `sudo` rights to run this command.

More information in case of trouble:
https://packaging.python.org/install_requirements_linux/ 

Install Sphinx:

::

  apt-get install python-sphinx # for Debian based systems
  #alternatively
  pip install sphinx

For other Linux systems please check Sphinx website:
http://www.sphinx-doc.org/en/stable/install.html#other-linux-distributions

Install TextLive:

::

  apt-get install texlive-latex-base texlive-latex-extra texlive-latex-recommended
  # additionally you may need xzdec
  apt-get install xzdec
  
In case you get the following error:



 Unknown directive ...containerchecksum c59200574a316416a23695c258edf3a32531fbda43ccdc09360ee105c3f07f9fb77df17c4ba4c2ea4f3a5ea6667e064b51e3d8c2fe6c984ba3e71b4e32716955... , please fix it! at /usr/share/texlive/tlpkg/TeXLive/TLPOBJ.pm line 210, <$retfh> line 5579.

you need to specify you want to continue using the 2015 version:

::

  tlmgr option repository ftp://tug.org/historic/systems/texlive/2015/tlnet-final 

Add missing tex packages:

::

  sudo tlmgr update --self
  sudo tlmgr install titlesec
  sudo tlmgr install framed
  sudo tlmgr install threeparttable
  sudo tlmgr install wrapfig
  sudo tlmgr install helvetic
  sudo tlmgr install courier
  sudo tlmgr install multirow
  sudo tlmgr install capt-of
  sudo tlmgr install needspace
  sudo tlmgr install eqparbox
  sudo tlmgr install environ
  sudo tlmgr install trimspaces

If you get the error "unknown locale: UTF-8" when generating the documentation the solution is to define the following environment variables:

::

  export LANG=en_US.UTF-8
  export LC_ALL=en_US.UTF-8
