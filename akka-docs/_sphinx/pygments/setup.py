""" 
Akka syntax styles for Pygments. 
"""

from setuptools import setup

entry_points = """ 
[pygments.styles]
simple = styles.simple:SimpleStyle
"""

setup( 
    name         = 'akkastyles', 
    version      = '0.1', 
    description  = __doc__, 
    author       = "Akka", 
    packages     = ['styles'], 
    entry_points = entry_points,
    html_use_smartypants = false 
) 
