# -*- coding: utf-8 -*-
#
# Akka documentation build configuration file.
#

import sys, os

# -- General configuration -----------------------------------------------------

sys.path.append(os.path.abspath('../_sphinx/exts'))
extensions = ['sphinx.ext.todo', 'includecode', 'includecode2']

templates_path = ['_templates']
source_suffix = '.rst'
master_doc = 'index'
exclude_patterns = ['_build', 'pending', 'disabled']

project = u'Akka'
copyright = u'2011-2015, Lightbend Inc'
version = '@version@'
release = '@version@'

pygments_style = 'simple'
highlight_language = 'scala'
add_function_parentheses = False
show_authors = True

# -- Options for HTML output ---------------------------------------------------

html_theme = 'akka'
html_theme_path = ['../_sphinx/themes']
html_favicon = '../_sphinx/static/favicon.ico'

html_title = 'Akka Documentation'
html_logo = '../_sphinx/static/logo.png'
#html_favicon = None

html_static_path = ['../_sphinx/static']

html_last_updated_fmt = '%b %d, %Y'
#html_sidebars = {}
#html_additional_pages = {}
html_domain_indices = False
html_use_index = False
html_show_sourcelink = False
html_show_sphinx = False
html_show_copyright = True
htmlhelp_basename = 'Akkadoc'
html_use_smartypants = False
html_add_permalinks = ''

html_context = {
  'include_analytics': 'online' in tags
}

# -- Options for EPUB output ---------------------------------------------------
epub_author = "Lightbend Inc"
epub_language = "en"
epub_publisher = epub_author
epub_identifier = "http://doc.akka.io/docs/akka/snapshot/"
epub_scheme = "URL"
epub_cover = ("../_sphinx/static/akka.png", "")

# -- Options for LaTeX output --------------------------------------------------

def setup(app):
  from sphinx.util.texescape import tex_replacements
  tex_replacements.append((u'⇒', ur'\(\Rightarrow\)'))

latex_paper_size = 'a4'
latex_font_size = '10pt'

latex_documents = [
  ('java', 'AkkaJava.tex', u' Akka Java Documentation',
   u'Lightbend Inc', 'manual'),
  ('scala', 'AkkaScala.tex', u' Akka Scala Documentation',
   u'Lightbend Inc', 'manual'),
]

latex_elements = {
    'classoptions': ',oneside,openany',
    'babel': '\\usepackage[english]{babel}',
    'fontpkg': '\\PassOptionsToPackage{warn}{textcomp} \\usepackage{times}',
    'preamble': '\\definecolor{VerbatimColor}{rgb}{0.935,0.935,0.935}'
    }

# latex_logo = '_sphinx/static/akka.png'
