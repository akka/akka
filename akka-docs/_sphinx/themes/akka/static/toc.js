/*!
 * samaxesJS JavaScript Library
 * jQuery TOC Plugin v1.1.3
 * http://code.google.com/p/samaxesjs/
 *
 * Copyright (c) 2011 samaxes.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(function($) {

    /*
     * The TOC plugin dynamically builds a table of contents from the headings in
     * a document and prepends legal-style section numbers to each of the headings.
     */
    $.fn.toc = function(options) {
        var opts = $.extend({}, $.fn.toc.defaults, options);
        var toc = this.append('<ul></ul>').children('ul');
        var headers = {h1: 0, h2: 0, h3: 0, h4: 0, h5: 0, h6: 0};
        var index = 0;
        var indexes = {h1: 0, h2: 0, h3: 0, h4: 0, h5: 0, h6: 0};
        for (var i = 1; i <= 6; i++) {
            indexes['h' + i] = (opts.exclude.match(new RegExp('h' + i, 'i')) === null && $('h' + i).length > 0) ? ++index : 0;
        }

        return this.each(function() {
            $(opts.context + ' :header').not(opts.exclude).each(function() {
                var $this = $(this);
                for (var i = 6; i >= 1; i--) {
                    if ($this.is('h' + i)) {
                        if (opts.numerate) {
                            checkContainer(headers['h' + i], toc);
                            updateNumeration(headers, 'h' + i);
                            if (opts.autoId && !$this.attr('id')) {
                                $this.attr('id', generateId($this.text()));
                            }
                            $this.text(addNumeration(headers, 'h' + i, $this.text()));
                        }
                        if (opts.autoId && !$this.attr('id')) {
                            $this.attr('id', generateId($this.text()));
                        }                        
                        appendToTOC(toc, indexes['h' + i], $this.attr('id'), $this.text());
                    }
                }
            });
        });
    };

    /*
     * Checks if the last node is an 'ul' element.
     * If not, a new one is created.
     */
    function checkContainer(header, toc) {
        if (header === 0 && toc.find(':last').length !== 0 && !toc.find(':last').is('ul')) {
            toc.find('li:last').append('<ul></ul>');
        }
    };

    /*
     * Updates headers numeration.
     */
    function updateNumeration(headers, header) {
        $.each(headers, function(i, val) {
            if (i === header)  {
                ++headers[i];
            } else if (i > header) {
                headers[i] = 0;
            }
        });
    };

    /*
     * Generate an anchor id from a string by replacing unwanted characters.
     */
    function generateId(text) {
        return text.replace(/[ <#\/\\?&.,():;]/g, '_');
    };

    /*
     * Prepends the numeration to a heading.
     */
    function addNumeration(headers, header, text) {
        var numeration = '';

        $.each(headers, function(i, val) {
            if (i <= header && headers[i] > 0)  {
                numeration += headers[i] + '.';
            }
        });

        return numeration + ' ' + text;
    };

    /*
     * Appends a new node to the TOC.
     */
    function appendToTOC(toc, index, id, text) {
        var parent = toc;

        for (var i = 1; i < index; i++) {
            if (parent.find('> li:last > ul').length === 0) {
                parent.append('<li style="list-style: none;"><ul></ul></li>');
            }
            parent = parent.find('> li:last > ul:first');
        }

        if (id === '') {
            parent.append('<li>' + text + '</li>');
        } else {
            parent.append('<li><a href="#' + id + '" class="scroll">' + text + '</a></li>');
        }
    };

    $.fn.toc.defaults = {
        exclude: 'h1, h5, h6',
        context: '',
        autoId: true,
        numerate: false
    };
})(jQuery);
