import os
import codecs
import re
from os import path

from docutils import nodes
from docutils.parsers.rst import Directive, directives

class IncludeCode2(Directive):
    """
    Include a code example from a file with sections delimited with special comments.
    """

    has_content = False
    required_arguments = 1
    optional_arguments = 0
    final_argument_whitespace = False
    option_spec = {
        'snippet': directives.unchanged_required
    }

    def run(self):
        document = self.state.document
        arg0 = self.arguments[0]
        (filename, sep, section) = arg0.partition('#')

        if not document.settings.file_insertion_enabled:
            return [document.reporter.warning('File insertion disabled', line=self.lineno)]
        env = document.settings.env
        if filename.startswith('/') or filename.startswith(os.sep):
            rel_fn = filename[1:]
        else:
            docdir = path.dirname(env.doc2path(env.docname, base=None))
            rel_fn = path.join(docdir, filename)
        try:
            fn = path.join(env.srcdir, rel_fn)
        except UnicodeDecodeError:
            # the source directory is a bytestring with non-ASCII characters;
            # let's try to encode the rel_fn in the file system encoding
            rel_fn = rel_fn.encode(sys.getfilesystemencoding())
            fn = path.join(env.srcdir, rel_fn)

        encoding = self.options.get('encoding', env.config.source_encoding)
        codec_info = codecs.lookup(encoding)
        try:
            f = codecs.StreamReaderWriter(open(fn, 'Ub'),
                    codec_info[2], codec_info[3], 'strict')
            lines = f.readlines()
            f.close()
        except (IOError, OSError):
            return [document.reporter.error(
                'Include file %r not found or reading it failed' % fn,
                line=self.lineno)]
        except UnicodeError:
            return [document.reporter.warning(
                'Encoding %r used for reading included file %r seems to '
                'be wrong, try giving an :encoding: option' %
                (encoding, fn))]

        snippet = self.options.get('snippet')
        current_snippets = ""
        res = []
        for line in lines:
            comment = line.rstrip().split("//", 1)[1] if line.find("//") >= 0 else ""
            if comment.startswith("#") and len(comment) > 1:
                current_snippets = comment
                indent = line.find("//")
            elif len(line) > 2 and line[2] == '"' and not current_snippets.startswith("#"):
                current_snippets = line[2:]
                indent = 4
            elif comment == "#" and current_snippets.startswith("#"):
                current_snippets = ""
            elif len(line) > 2 and line[2] == '}' and not current_snippets.startswith("#"):
                current_snippets = ""
            elif current_snippets.find(snippet) >= 0 and comment.find("hide") == -1:
                res.append(line[indent:].rstrip() + '\n')
            elif comment.find(snippet) >= 0:
                array = line.split("//", 1)
                l = array[0].rstrip() if array[1].startswith("/") > 0 else array[0].strip()
                res.append(l + '\n')
            elif re.search("(def|val) "+re.escape(snippet)+"(?=[:(\[])", line):
                # match signature line from `def <snippet>` but without trailing `=`
                start = line.find("def")
                if start == -1: start = line.find("val")

                # include `implicit` if definition is prefixed with it
                implicitstart = line.find("implicit")
                if implicitstart != -1 and implicitstart < start: start = implicitstart

                end = line.rfind("=")
                if end == -1: current_snippets = "matching_signature"
                res.append(line[start:end] + '\n')
            elif current_snippets == "matching_signature":
                end = line.rfind("=")
                if end != -1: current_snippets = ""
                res.append(line[start:end] + '\n')

        text = ''.join(res)

        if text == "":
            return [document.reporter.error('Snippet "' + snippet + '" not found!', line=self.lineno)]

        retnode = nodes.literal_block(text, text, source=fn)
        document.settings.env.note_dependency(rel_fn)
        return [retnode]

def setup(app):
    app.require_sphinx('1.0')
    app.add_directive('includecode2', IncludeCode2)
