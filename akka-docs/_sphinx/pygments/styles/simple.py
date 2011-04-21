# -*- coding: utf-8 -*-
"""
    pygments.styles.akka
    ~~~~~~~~~~~~~~~~~~~~~~~~

    Simple style for Scala highlighting.
"""

from pygments.style import Style
from pygments.token import Keyword, Name, Comment, String, Error, \
     Number, Operator, Generic, Whitespace


class SimpleStyle(Style):
    """
    Simple style for Scala highlighting.
    """

    background_color = "#f0f0f0"
    default_style = ""

    styles = {
        Whitespace:                "#f0f0f0",
        Comment:                   "#777766",
        Comment.Preproc:           "",
        Comment.Special:           "",

        Keyword:                   "#000080",
        Keyword.Pseudo:            "",
        Keyword.Type:              "",

        Operator:                  "#000000",
        Operator.Word:             "",

        Name.Builtin:              "#000000",
        Name.Function:             "#000000",
        Name.Class:                "#000000",
        Name.Namespace:            "#000000",
        Name.Exception:            "#000000",
        Name.Variable:             "#000000",
        Name.Constant:             "bold #000000",
        Name.Label:                "#000000",
        Name.Entity:               "#000000",
        Name.Attribute:            "#000000",
        Name.Tag:                  "#000000",
        Name.Decorator:            "#000000",

        String:                    "#008000",
        String.Doc:                "",
        String.Interpol:           "",
        String.Escape:             "",
        String.Regex:              "",
        String.Symbol:             "",
        String.Other:              "",
        Number:                    "#008000",

        Error:                     "border:#FF0000"
    }
