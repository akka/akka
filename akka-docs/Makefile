# Makefile for Sphinx documentation
#

# You can set these variables from the command line.
SPHINXOPTS    =
SPHINXBUILD   = sphinx-build
PAPER         =
BUILDDIR      = _build
EASYINSTALL   = easy_install
LOCALPACKAGES = $(shell pwd)/$(BUILDDIR)/site-packages
PYGMENTSDIR   = _sphinx/pygments

# Internal variables.
PAPEROPT_a4     = -D latex_paper_size=a4
PAPEROPT_letter = -D latex_paper_size=letter
ALLSPHINXOPTS   = -d $(BUILDDIR)/doctrees $(PAPEROPT_$(PAPER)) $(SPHINXOPTS) .

# Set python path to include local packages for pygments styles.
ifneq (,$(PYTHONPATH))
	PYTHONPATH := $(PYTHONPATH):$(LOCALPACKAGES)
else
	PYTHONPATH := $(LOCALPACKAGES)
endif
export PYTHONPATH

ifeq (,$(QUICK))
	SPHINXFLAGS = -a
else
	SPHINXFLAGS =
endif

.PHONY: help clean pygments html singlehtml latex pdf

help:
	@echo "Please use \`make <target>' where <target> is one of"
	@echo "  pygments   to locally install the custom pygments styles"
	@echo "  epub       to make an epub"
	@echo "  html       to make standalone HTML files"
	@echo "  singlehtml to make a single large HTML file"
	@echo "  latex      to make LaTeX files, you can set PAPER=a4 or PAPER=letter"
	@echo "  pdf        to make LaTeX files and run them through pdflatex"

clean:
	-rm -rf $(BUILDDIR)/*

pygments:
	mkdir -p $(LOCALPACKAGES)
	$(EASYINSTALL) --install-dir $(LOCALPACKAGES) $(PYGMENTSDIR)
	-rm -rf $(PYGMENTSDIR)/*.egg-info $(PYGMENTSDIR)/build $(PYGMENTSDIR)/temp
	@echo
	@echo "Custom pygments styles have been installed."
	@echo

$(LOCALPACKAGES):
	$(MAKE) pygments

epub: $(LOCALPACKAGES)
	$(SPHINXBUILD) $(SPHINXFLAGS) -b epub $(ALLSPHINXOPTS) $(BUILDDIR)/epub
	@echo
	@echo "Build finished. The epub file is in $(BUILDDIR)/epub."

html: $(LOCALPACKAGES)
	$(SPHINXBUILD) $(SPHINXFLAGS) -b html $(ALLSPHINXOPTS) $(BUILDDIR)/html
	@echo
	@echo "Build finished. The HTML pages are in $(BUILDDIR)/html."

singlehtml:
	$(SPHINXBUILD) -b singlehtml $(ALLSPHINXOPTS) $(BUILDDIR)/singlehtml
	@echo
	@echo "Build finished. The HTML page is in $(BUILDDIR)/singlehtml."

latex:
	$(SPHINXBUILD) -b latex $(ALLSPHINXOPTS) $(BUILDDIR)/latex
	@echo
	@echo "Build finished; the LaTeX files are in $(BUILDDIR)/latex."
	@echo "Run \`make' in that directory to run these through (pdf)latex" \
	      "(use \`make latexpdf' here to do that automatically)."

pdf: pygments
	$(SPHINXBUILD) -b latex $(ALLSPHINXOPTS) $(BUILDDIR)/latex
	@echo "Running LaTeX files through pdflatex..."
	make -C $(BUILDDIR)/latex all-pdf
	@echo "pdflatex finished; the PDF files are in $(BUILDDIR)/latex."
