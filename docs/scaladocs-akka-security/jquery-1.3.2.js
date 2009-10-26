(function(){var L=this,G,Y=L.jQuery,P=L.$,O=L.jQuery=L.$=function(e,f){return new O.fn.init(e,f)
},d=/^[^<]*(<(.|\s)+>)[^>]*$|^#([\w-]+)$/,F=/^.[^:#\[\.,]*$/;
O.fn=O.prototype={init:function(e,h){e=e||document;
if(e.nodeType){this[0]=e;
this.length=1;
this.context=e;
return this
}if(typeof e==="string"){var g=d.exec(e);
if(g&&(g[1]||!h)){if(g[1]){e=O.clean([g[1]],h)
}else{var i=document.getElementById(g[3]);
if(i&&i.id!=g[3]){return O().find(e)
}var f=O(i||[]);
f.context=document;
f.selector=e;
return f
}}else{return O(h).find(e)
}}else{if(O.isFunction(e)){return O(document).ready(e)
}}if(e.selector&&e.context){this.selector=e.selector;
this.context=e.context
}return this.setArray(O.isArray(e)?e:O.makeArray(e))
},selector:"",jquery:"1.3.2",size:function(){return this.length
},get:function(e){return e===G?Array.prototype.slice.call(this):this[e]
},pushStack:function(f,h,e){var g=O(f);
g.prevObject=this;
g.context=this.context;
if(h==="find"){g.selector=this.selector+(this.selector?" ":"")+e
}else{if(h){g.selector=this.selector+"."+h+"("+e+")"
}}return g
},setArray:function(e){this.length=0;
Array.prototype.push.apply(this,e);
return this
},each:function(f,e){return O.each(this,f,e)
},index:function(e){return O.inArray(e&&e.jquery?e[0]:e,this)
},attr:function(f,h,g){var e=f;
if(typeof f==="string"){if(h===G){return this[0]&&O[g||"attr"](this[0],f)
}else{e={};
e[f]=h
}}return this.each(function(j){for(f in e){O.attr(g?this.style:this,f,O.prop(this,e[f],g,j,f))
}})
},css:function(e,f){if((e=="width"||e=="height")&&parseFloat(f)<0){f=G
}return this.attr(e,f,"curCSS")
},text:function(f){if(typeof f!=="object"&&f!=null){return this.empty().append((this[0]&&this[0].ownerDocument||document).createTextNode(f))
}var e="";
O.each(f||this,function(){O.each(this.childNodes,function(){if(this.nodeType!=8){e+=this.nodeType!=1?this.nodeValue:O.fn.text([this])
}})
});
return e
},wrapAll:function(e){if(this[0]){var f=O(e,this[0].ownerDocument).clone();
if(this[0].parentNode){f.insertBefore(this[0])
}f.map(function(){var g=this;
while(g.firstChild){g=g.firstChild
}return g
}).append(this)
}return this
},wrapInner:function(e){return this.each(function(){O(this).contents().wrapAll(e)
})
},wrap:function(e){return this.each(function(){O(this).wrapAll(e)
})
},append:function(){return this.domManip(arguments,true,function(e){if(this.nodeType==1){this.appendChild(e)
}})
},prepend:function(){return this.domManip(arguments,true,function(e){if(this.nodeType==1){this.insertBefore(e,this.firstChild)
}})
},before:function(){return this.domManip(arguments,false,function(e){this.parentNode.insertBefore(e,this)
})
},after:function(){return this.domManip(arguments,false,function(e){this.parentNode.insertBefore(e,this.nextSibling)
})
},end:function(){return this.prevObject||O([])
},push:[].push,sort:[].sort,splice:[].splice,find:function(e){if(this.length===1){var f=this.pushStack([],"find",e);
f.length=0;
O.find(e,this[0],f);
return f
}else{return this.pushStack(O.unique(O.map(this,function(g){return O.find(e,g)
})),"find",e)
}},clone:function(g){var e=this.map(function(){if(!O.support.noCloneEvent&&!O.isXMLDoc(this)){var i=this.outerHTML;
if(!i){var j=this.ownerDocument.createElement("div");
j.appendChild(this.cloneNode(true));
i=j.innerHTML
}return O.clean([i.replace(/ jQuery\d+="(?:\d+|null)"/g,"").replace(/^\s*/,"")])[0]
}else{return this.cloneNode(true)
}});
if(g===true){var h=this.find("*").andSelf(),f=0;
e.find("*").andSelf().each(function(){if(this.nodeName!==h[f].nodeName){return 
}var i=O.data(h[f],"events");
for(var k in i){for(var j in i[k]){O.event.add(this,k,i[k][j],i[k][j].data)
}}f++
})
}return e
},filter:function(e){return this.pushStack(O.isFunction(e)&&O.grep(this,function(g,f){return e.call(g,f)
})||O.multiFilter(e,O.grep(this,function(f){return f.nodeType===1
})),"filter",e)
},closest:function(e){var g=O.expr.match.POS.test(e)?O(e):null,f=0;
return this.map(function(){var h=this;
while(h&&h.ownerDocument){if(g?g.index(h)>-1:O(h).is(e)){O.data(h,"closest",f);
return h
}h=h.parentNode;
f++
}})
},not:function(e){if(typeof e==="string"){if(F.test(e)){return this.pushStack(O.multiFilter(e,this,true),"not",e)
}else{e=O.multiFilter(e,this)
}}var f=e.length&&e[e.length-1]!==G&&!e.nodeType;
return this.filter(function(){return f?O.inArray(this,e)<0:this!=e
})
},add:function(e){return this.pushStack(O.unique(O.merge(this.get(),typeof e==="string"?O(e):O.makeArray(e))))
},is:function(e){return !!e&&O.multiFilter(e,this).length>0
},hasClass:function(e){return !!e&&this.is("."+e)
},val:function(l){if(l===G){var e=this[0];
if(e){if(O.nodeName(e,"option")){return(e.attributes.value||{}).specified?e.value:e.text
}if(O.nodeName(e,"select")){var j=e.selectedIndex,m=[],n=e.options,h=e.type=="select-one";
if(j<0){return null
}for(var f=h?j:0,k=h?j+1:n.length;
f<k;
f++){var g=n[f];
if(g.selected){l=O(g).val();
if(h){return l
}m.push(l)
}}return m
}return(e.value||"").replace(/\r/g,"")
}return G
}if(typeof l==="number"){l+=""
}return this.each(function(){if(this.nodeType!=1){return 
}if(O.isArray(l)&&/radio|checkbox/.test(this.type)){this.checked=(O.inArray(this.value,l)>=0||O.inArray(this.name,l)>=0)
}else{if(O.nodeName(this,"select")){var i=O.makeArray(l);
O("option",this).each(function(){this.selected=(O.inArray(this.value,i)>=0||O.inArray(this.text,i)>=0)
});
if(!i.length){this.selectedIndex=-1
}}else{this.value=l
}}})
},html:function(e){return e===G?(this[0]?this[0].innerHTML.replace(/ jQuery\d+="(?:\d+|null)"/g,""):null):this.empty().append(e)
},replaceWith:function(e){return this.after(e).remove()
},eq:function(e){return this.slice(e,+e+1)
},slice:function(){return this.pushStack(Array.prototype.slice.apply(this,arguments),"slice",Array.prototype.slice.call(arguments).join(","))
},map:function(e){return this.pushStack(O.map(this,function(g,f){return e.call(g,f,g)
}))
},andSelf:function(){return this.add(this.prevObject)
},domManip:function(k,o,n){if(this[0]){var j=(this[0].ownerDocument||this[0]).createDocumentFragment(),f=O.clean(k,(this[0].ownerDocument||this[0]),j),h=j.firstChild;
if(h){for(var g=0,e=this.length;
g<e;
g++){n.call(m(this[g],h),this.length>1||g>0?j.cloneNode(true):j)
}}if(f){O.each(f,Z)
}}return this;
function m(i,l){return o&&O.nodeName(i,"table")&&O.nodeName(l,"tr")?(i.getElementsByTagName("tbody")[0]||i.appendChild(i.ownerDocument.createElement("tbody"))):i
}}};
O.fn.init.prototype=O.fn;
function Z(e,f){if(f.src){O.ajax({url:f.src,async:false,dataType:"script"})
}else{O.globalEval(f.text||f.textContent||f.innerHTML||"")
}if(f.parentNode){f.parentNode.removeChild(f)
}}function E(){return +new Date
}O.extend=O.fn.extend=function(){var k=arguments[0]||{},h=1,j=arguments.length,e=false,g;
if(typeof k==="boolean"){e=k;
k=arguments[1]||{};
h=2
}if(typeof k!=="object"&&!O.isFunction(k)){k={}
}if(j==h){k=this;
--h
}for(;
h<j;
h++){if((g=arguments[h])!=null){for(var f in g){var l=k[f],m=g[f];
if(k===m){continue
}if(e&&m&&typeof m==="object"&&!m.nodeType){k[f]=O.extend(e,l||(m.length!=null?[]:{}),m)
}else{if(m!==G){k[f]=m
}}}}}return k
};
var B=/z-?index|font-?weight|opacity|zoom|line-?height/i,Q=document.defaultView||{},S=Object.prototype.toString;
O.extend({noConflict:function(e){L.$=P;
if(e){L.jQuery=Y
}return O
},isFunction:function(e){return S.call(e)==="[object Function]"
},isArray:function(e){return S.call(e)==="[object Array]"
},isXMLDoc:function(e){return e.nodeType===9&&e.documentElement.nodeName!=="HTML"||!!e.ownerDocument&&O.isXMLDoc(e.ownerDocument)
},globalEval:function(g){if(g&&/\S/.test(g)){var f=document.getElementsByTagName("head")[0]||document.documentElement,e=document.createElement("script");
e.type="text/javascript";
if(O.support.scriptEval){e.appendChild(document.createTextNode(g))
}else{e.text=g
}f.insertBefore(e,f.firstChild);
f.removeChild(e)
}},nodeName:function(f,e){return f.nodeName&&f.nodeName.toUpperCase()==e.toUpperCase()
},each:function(g,l,f){var e,h=0,j=g.length;
if(f){if(j===G){for(e in g){if(l.apply(g[e],f)===false){break
}}}else{for(;
h<j;
){if(l.apply(g[h++],f)===false){break
}}}}else{if(j===G){for(e in g){if(l.call(g[e],e,g[e])===false){break
}}}else{for(var k=g[0];
h<j&&l.call(k,h,k)!==false;
k=g[++h]){}}}return g
},prop:function(h,j,g,f,e){if(O.isFunction(j)){j=j.call(h,f)
}return typeof j==="number"&&g=="curCSS"&&!B.test(e)?j+"px":j
},className:{add:function(e,f){O.each((f||"").split(/\s+/),function(g,h){if(e.nodeType==1&&!O.className.has(e.className,h)){e.className+=(e.className?" ":"")+h
}})
},remove:function(e,f){if(e.nodeType==1){e.className=f!==G?O.grep(e.className.split(/\s+/),function(g){return !O.className.has(f,g)
}).join(" "):""
}},has:function(f,e){return f&&O.inArray(e,(f.className||f).toString().split(/\s+/))>-1
}},swap:function(h,g,i){var e={};
for(var f in g){e[f]=h.style[f];
h.style[f]=g[f]
}i.call(h);
for(var f in g){h.style[f]=e[f]
}},css:function(h,f,j,e){if(f=="width"||f=="height"){var l,g={position:"absolute",visibility:"hidden",display:"block"},k=f=="width"?["Left","Right"]:["Top","Bottom"];
function i(){l=f=="width"?h.offsetWidth:h.offsetHeight;
if(e==="border"){return 
}O.each(k,function(){if(!e){l-=parseFloat(O.curCSS(h,"padding"+this,true))||0
}if(e==="margin"){l+=parseFloat(O.curCSS(h,"margin"+this,true))||0
}else{l-=parseFloat(O.curCSS(h,"border"+this+"Width",true))||0
}})
}if(h.offsetWidth!==0){i()
}else{O.swap(h,g,i)
}return Math.max(0,Math.round(l))
}return O.curCSS(h,f,j)
},curCSS:function(i,f,g){var l,e=i.style;
if(f=="opacity"&&!O.support.opacity){l=O.attr(e,"opacity");
return l==""?"1":l
}if(f.match(/float/i)){f=W
}if(!g&&e&&e[f]){l=e[f]
}else{if(Q.getComputedStyle){if(f.match(/float/i)){f="float"
}f=f.replace(/([A-Z])/g,"-$1").toLowerCase();
var m=Q.getComputedStyle(i,null);
if(m){l=m.getPropertyValue(f)
}if(f=="opacity"&&l==""){l="1"
}}else{if(i.currentStyle){var j=f.replace(/\-(\w)/g,function(n,o){return o.toUpperCase()
});
l=i.currentStyle[f]||i.currentStyle[j];
if(!/^\d+(px)?$/i.test(l)&&/^\d/.test(l)){var h=e.left,k=i.runtimeStyle.left;
i.runtimeStyle.left=i.currentStyle.left;
e.left=l||0;
l=e.pixelLeft+"px";
e.left=h;
i.runtimeStyle.left=k
}}}}return l
},clean:function(f,l,j){l=l||document;
if(typeof l.createElement==="undefined"){l=l.ownerDocument||l[0]&&l[0].ownerDocument||document
}if(!j&&f.length===1&&typeof f[0]==="string"){var h=/^<(\w+)\s*\/?>$/.exec(f[0]);
if(h){return[l.createElement(h[1])]
}}var g=[],e=[],m=l.createElement("div");
O.each(f,function(q,t){if(typeof t==="number"){t+=""
}if(!t){return 
}if(typeof t==="string"){t=t.replace(/(<(\w+)[^>]*?)\/>/g,function(u,v,i){return i.match(/^(abbr|br|col|img|input|link|meta|param|hr|area|embed)$/i)?u:v+"></"+i+">"
});
var p=t.replace(/^\s+/,"").substring(0,10).toLowerCase();
var r=!p.indexOf("<opt")&&[1,"<select multiple='multiple'>","</select>"]||!p.indexOf("<leg")&&[1,"<fieldset>","</fieldset>"]||p.match(/^<(thead|tbody|tfoot|colg|cap)/)&&[1,"<table>","</table>"]||!p.indexOf("<tr")&&[2,"<table><tbody>","</tbody></table>"]||(!p.indexOf("<td")||!p.indexOf("<th"))&&[3,"<table><tbody><tr>","</tr></tbody></table>"]||!p.indexOf("<col")&&[2,"<table><tbody></tbody><colgroup>","</colgroup></table>"]||!O.support.htmlSerialize&&[1,"div<div>","</div>"]||[0,"",""];
m.innerHTML=r[1]+t+r[2];
while(r[0]--){m=m.lastChild
}if(!O.support.tbody){var s=/<tbody/i.test(t),o=!p.indexOf("<table")&&!s?m.firstChild&&m.firstChild.childNodes:r[1]=="<table>"&&!s?m.childNodes:[];
for(var n=o.length-1;
n>=0;
--n){if(O.nodeName(o[n],"tbody")&&!o[n].childNodes.length){o[n].parentNode.removeChild(o[n])
}}}if(!O.support.leadingWhitespace&&/^\s/.test(t)){m.insertBefore(l.createTextNode(t.match(/^\s*/)[0]),m.firstChild)
}t=O.makeArray(m.childNodes)
}if(t.nodeType){g.push(t)
}else{g=O.merge(g,t)
}});
if(j){for(var k=0;
g[k];
k++){if(O.nodeName(g[k],"script")&&(!g[k].type||g[k].type.toLowerCase()==="text/javascript")){e.push(g[k].parentNode?g[k].parentNode.removeChild(g[k]):g[k])
}else{if(g[k].nodeType===1){g.splice.apply(g,[k+1,0].concat(O.makeArray(g[k].getElementsByTagName("script"))))
}j.appendChild(g[k])
}}return e
}return g
},attr:function(j,g,k){if(!j||j.nodeType==3||j.nodeType==8){return G
}var h=!O.isXMLDoc(j),l=k!==G;
g=h&&O.props[g]||g;
if(j.tagName){var f=/href|src|style/.test(g);
if(g=="selected"&&j.parentNode){j.parentNode.selectedIndex
}if(g in j&&h&&!f){if(l){if(g=="type"&&O.nodeName(j,"input")&&j.parentNode){throw"type property can't be changed"
}j[g]=k
}if(O.nodeName(j,"form")&&j.getAttributeNode(g)){return j.getAttributeNode(g).nodeValue
}if(g=="tabIndex"){var i=j.getAttributeNode("tabIndex");
return i&&i.specified?i.value:j.nodeName.match(/(button|input|object|select|textarea)/i)?0:j.nodeName.match(/^(a|area)$/i)&&j.href?0:G
}return j[g]
}if(!O.support.style&&h&&g=="style"){return O.attr(j.style,"cssText",k)
}if(l){j.setAttribute(g,""+k)
}var e=!O.support.hrefNormalized&&h&&f?j.getAttribute(g,2):j.getAttribute(g);
return e===null?G:e
}if(!O.support.opacity&&g=="opacity"){if(l){j.zoom=1;
j.filter=(j.filter||"").replace(/alpha\([^)]*\)/,"")+(parseInt(k)+""=="NaN"?"":"alpha(opacity="+k*100+")")
}return j.filter&&j.filter.indexOf("opacity=")>=0?(parseFloat(j.filter.match(/opacity=([^)]*)/)[1])/100)+"":""
}g=g.replace(/-([a-z])/ig,function(m,n){return n.toUpperCase()
});
if(l){j[g]=k
}return j[g]
},trim:function(e){return(e||"").replace(/^\s+|\s+$/g,"")
},makeArray:function(g){var e=[];
if(g!=null){var f=g.length;
if(f==null||typeof g==="string"||O.isFunction(g)||g.setInterval){e[0]=g
}else{while(f){e[--f]=g[f]
}}}return e
},inArray:function(g,h){for(var e=0,f=h.length;
e<f;
e++){if(h[e]===g){return e
}}return -1
},merge:function(h,e){var f=0,g,j=h.length;
if(!O.support.getAll){while((g=e[f++])!=null){if(g.nodeType!=8){h[j++]=g
}}}else{while((g=e[f++])!=null){h[j++]=g
}}return h
},unique:function(m){var g=[],f={};
try{for(var h=0,j=m.length;
h<j;
h++){var l=O.data(m[h]);
if(!f[l]){f[l]=true;
g.push(m[h])
}}}catch(k){g=m
}return g
},grep:function(f,k,e){var g=[];
for(var h=0,j=f.length;
h<j;
h++){if(!e!=!k(f[h],h)){g.push(f[h])
}}return g
},map:function(e,k){var f=[];
for(var g=0,h=e.length;
g<h;
g++){var j=k(e[g],g);
if(j!=null){f[f.length]=j
}}return f.concat.apply([],f)
}});
var c=navigator.userAgent.toLowerCase();
O.browser={version:(c.match(/.+(?:rv|it|ra|ie)[\/: ]([\d.]+)/)||[0,"0"])[1],safari:/webkit/.test(c),opera:/opera/.test(c),msie:/msie/.test(c)&&!/opera/.test(c),mozilla:/mozilla/.test(c)&&!/(compatible|webkit)/.test(c)};
O.each({parent:function(e){return e.parentNode
},parents:function(e){return O.dir(e,"parentNode")
},next:function(e){return O.nth(e,2,"nextSibling")
},prev:function(e){return O.nth(e,2,"previousSibling")
},nextAll:function(e){return O.dir(e,"nextSibling")
},prevAll:function(e){return O.dir(e,"previousSibling")
},siblings:function(e){return O.sibling(e.parentNode.firstChild,e)
},children:function(e){return O.sibling(e.firstChild)
},contents:function(e){return O.nodeName(e,"iframe")?e.contentDocument||e.contentWindow.document:O.makeArray(e.childNodes)
}},function(e,f){O.fn[e]=function(g){var h=O.map(this,f);
if(g&&typeof g=="string"){h=O.multiFilter(g,h)
}return this.pushStack(O.unique(h),e,g)
}
});
O.each({appendTo:"append",prependTo:"prepend",insertBefore:"before",insertAfter:"after",replaceAll:"replaceWith"},function(e,f){O.fn[e]=function(g){var k=[],n=O(g);
for(var m=0,h=n.length;
m<h;
m++){var j=(m>0?this.clone(true):this).get();
O.fn[f].apply(O(n[m]),j);
k=k.concat(j)
}return this.pushStack(k,e,g)
}
});
O.each({removeAttr:function(e){O.attr(this,e,"");
if(this.nodeType==1){this.removeAttribute(e)
}},addClass:function(e){O.className.add(this,e)
},removeClass:function(e){O.className.remove(this,e)
},toggleClass:function(f,e){if(typeof e!=="boolean"){e=!O.className.has(this,f)
}O.className[e?"add":"remove"](this,f)
},remove:function(e){if(!e||O.filter(e,[this]).length){O("*",this).add([this]).each(function(){O.event.remove(this);
O.removeData(this)
});
if(this.parentNode){this.parentNode.removeChild(this)
}}},empty:function(){O(this).children().remove();
while(this.firstChild){this.removeChild(this.firstChild)
}}},function(e,f){O.fn[e]=function(){return this.each(f,arguments)
}
});
function J(e,f){return e[0]&&parseInt(O.curCSS(e[0],f,true),10)||0
}var H="jQuery"+E(),V=0,a={};
O.extend({cache:{},data:function(f,e,g){f=f==L?a:f;
var h=f[H];
if(!h){h=f[H]=++V
}if(e&&!O.cache[h]){O.cache[h]={}
}if(g!==G){O.cache[h][e]=g
}return e?O.cache[h][e]:h
},removeData:function(g,f){g=g==L?a:g;
var i=g[H];
if(f){if(O.cache[i]){delete O.cache[i][f];
f="";
for(f in O.cache[i]){break
}if(!f){O.removeData(g)
}}}else{try{delete g[H]
}catch(h){if(g.removeAttribute){g.removeAttribute(H)
}}delete O.cache[i]
}},queue:function(f,e,h){if(f){e=(e||"fx")+"queue";
var g=O.data(f,e);
if(!g||O.isArray(h)){g=O.data(f,e,O.makeArray(h))
}else{if(h){g.push(h)
}}}return g
},dequeue:function(h,g){var e=O.queue(h,g),f=e.shift();
if(!g||g==="fx"){f=e[0]
}if(f!==G){f.call(h)
}}});
O.fn.extend({data:function(e,g){var h=e.split(".");
h[1]=h[1]?"."+h[1]:"";
if(g===G){var f=this.triggerHandler("getData"+h[1]+"!",[h[0]]);
if(f===G&&this.length){f=O.data(this[0],e)
}return f===G&&h[1]?this.data(h[0]):f
}else{return this.trigger("setData"+h[1]+"!",[h[0],g]).each(function(){O.data(this,e,g)
})
}},removeData:function(e){return this.each(function(){O.removeData(this,e)
})
},queue:function(e,f){if(typeof e!=="string"){f=e;
e="fx"
}if(f===G){return O.queue(this[0],e)
}return this.each(function(){var g=O.queue(this,e,f);
if(e=="fx"&&g.length==1){g[0].call(this)
}})
},dequeue:function(e){return this.each(function(){O.dequeue(this,e)
})
}});
(function(){var s=/((?:\((?:\([^()]+\)|[^()]+)+\)|\[(?:\[[^[\]]*\]|['"][^'"]*['"]|[^[\]'"]+)+\]|\\.|[^ >+~,(\[\\]+)+|[>+~])(\s*,\s*)?/g,m=0,i=Object.prototype.toString;
var g=function(y,u,AB,AC){AB=AB||[];
u=u||document;
if(u.nodeType!==1&&u.nodeType!==9){return[]
}if(!y||typeof y!=="string"){return AB
}var z=[],w,AF,AI,e,AD,v,x=true;
s.lastIndex=0;
while((w=s.exec(y))!==null){z.push(w[1]);
if(w[2]){v=RegExp.rightContext;
break
}}if(z.length>1&&n.exec(y)){if(z.length===2&&j.relative[z[0]]){AF=k(z[0]+z[1],u)
}else{AF=j.relative[z[0]]?[u]:g(z.shift(),u);
while(z.length){y=z.shift();
if(j.relative[y]){y+=z.shift()
}AF=k(y,AF)
}}}else{var AE=AC?{expr:z.pop(),set:f(AC)}:g.find(z.pop(),z.length===1&&u.parentNode?u.parentNode:u,r(u));
AF=g.filter(AE.expr,AE.set);
if(z.length>0){AI=f(AF)
}else{x=false
}while(z.length){var AH=z.pop(),AG=AH;
if(!j.relative[AH]){AH=""
}else{AG=z.pop()
}if(AG==null){AG=u
}j.relative[AH](AI,AG,r(u))
}}if(!AI){AI=AF
}if(!AI){throw"Syntax error, unrecognized expression: "+(AH||y)
}if(i.call(AI)==="[object Array]"){if(!x){AB.push.apply(AB,AI)
}else{if(u.nodeType===1){for(var AA=0;
AI[AA]!=null;
AA++){if(AI[AA]&&(AI[AA]===true||AI[AA].nodeType===1&&l(u,AI[AA]))){AB.push(AF[AA])
}}}else{for(var AA=0;
AI[AA]!=null;
AA++){if(AI[AA]&&AI[AA].nodeType===1){AB.push(AF[AA])
}}}}}else{f(AI,AB)
}if(v){g(v,u,AB,AC);
if(h){hasDuplicate=false;
AB.sort(h);
if(hasDuplicate){for(var AA=1;
AA<AB.length;
AA++){if(AB[AA]===AB[AA-1]){AB.splice(AA--,1)
}}}}}return AB
};
g.matches=function(e,u){return g(e,null,null,u)
};
g.find=function(AA,e,AB){var z,x;
if(!AA){return[]
}for(var w=0,v=j.order.length;
w<v;
w++){var y=j.order[w],x;
if((x=j.match[y].exec(AA))){var u=RegExp.leftContext;
if(u.substr(u.length-1)!=="\\"){x[1]=(x[1]||"").replace(/\\/g,"");
z=j.find[y](x,e,AB);
if(z!=null){AA=AA.replace(j.match[y],"");
break
}}}}if(!z){z=e.getElementsByTagName("*")
}return{set:z,expr:AA}
};
g.filter=function(AD,AC,AG,w){var v=AD,AI=[],AA=AC,y,e,z=AC&&AC[0]&&r(AC[0]);
while(AD&&AC.length){for(var AB in j.filter){if((y=j.match[AB].exec(AD))!=null){var u=j.filter[AB],AH,AF;
e=false;
if(AA==AI){AI=[]
}if(j.preFilter[AB]){y=j.preFilter[AB](y,AA,AG,AI,w,z);
if(!y){e=AH=true
}else{if(y===true){continue
}}}if(y){for(var x=0;
(AF=AA[x])!=null;
x++){if(AF){AH=u(AF,y,x,AA);
var AE=w^!!AH;
if(AG&&AH!=null){if(AE){e=true
}else{AA[x]=false
}}else{if(AE){AI.push(AF);
e=true
}}}}}if(AH!==G){if(!AG){AA=AI
}AD=AD.replace(j.match[AB],"");
if(!e){return[]
}break
}}}if(AD==v){if(e==null){throw"Syntax error, unrecognized expression: "+AD
}else{break
}}v=AD
}return AA
};
var j=g.selectors={order:["ID","NAME","TAG"],match:{ID:/#((?:[\w\u00c0-\uFFFF_-]|\\.)+)/,CLASS:/\.((?:[\w\u00c0-\uFFFF_-]|\\.)+)/,NAME:/\[name=['"]*((?:[\w\u00c0-\uFFFF_-]|\\.)+)['"]*\]/,ATTR:/\[\s*((?:[\w\u00c0-\uFFFF_-]|\\.)+)\s*(?:(\S?=)\s*(['"]*)(.*?)\3|)\s*\]/,TAG:/^((?:[\w\u00c0-\uFFFF\*_-]|\\.)+)/,CHILD:/:(only|nth|last|first)-child(?:\((even|odd|[\dn+-]*)\))?/,POS:/:(nth|eq|gt|lt|first|last|even|odd)(?:\((\d*)\))?(?=[^-]|$)/,PSEUDO:/:((?:[\w\u00c0-\uFFFF_-]|\\.)+)(?:\((['"]*)((?:\([^\)]+\)|[^\2\(\)]*)+)\2\))?/},attrMap:{"class":"className","for":"htmlFor"},attrHandle:{href:function(e){return e.getAttribute("href")
}},relative:{"+":function(AA,e,z){var x=typeof e==="string",AB=x&&!/\W/.test(e),y=x&&!AB;
if(AB&&!z){e=e.toUpperCase()
}for(var w=0,v=AA.length,u;
w<v;
w++){if((u=AA[w])){while((u=u.previousSibling)&&u.nodeType!==1){}AA[w]=y||u&&u.nodeName===e?u||false:u===e
}}if(y){g.filter(e,AA,true)
}},">":function(z,u,AA){var x=typeof u==="string";
if(x&&!/\W/.test(u)){u=AA?u:u.toUpperCase();
for(var v=0,e=z.length;
v<e;
v++){var y=z[v];
if(y){var w=y.parentNode;
z[v]=w.nodeName===u?w:false
}}}else{for(var v=0,e=z.length;
v<e;
v++){var y=z[v];
if(y){z[v]=x?y.parentNode:y.parentNode===u
}}if(x){g.filter(u,z,true)
}}},"":function(w,u,y){var v=m++,e=t;
if(!u.match(/\W/)){var x=u=y?u:u.toUpperCase();
e=q
}e("parentNode",u,v,w,x,y)
},"~":function(w,u,y){var v=m++,e=t;
if(typeof u==="string"&&!u.match(/\W/)){var x=u=y?u:u.toUpperCase();
e=q
}e("previousSibling",u,v,w,x,y)
}},find:{ID:function(u,v,w){if(typeof v.getElementById!=="undefined"&&!w){var e=v.getElementById(u[1]);
return e?[e]:[]
}},NAME:function(v,y,z){if(typeof y.getElementsByName!=="undefined"){var u=[],x=y.getElementsByName(v[1]);
for(var w=0,e=x.length;
w<e;
w++){if(x[w].getAttribute("name")===v[1]){u.push(x[w])
}}return u.length===0?null:u
}},TAG:function(e,u){return u.getElementsByTagName(e[1])
}},preFilter:{CLASS:function(w,u,v,e,z,AA){w=" "+w[1].replace(/\\/g,"")+" ";
if(AA){return w
}for(var x=0,y;
(y=u[x])!=null;
x++){if(y){if(z^(y.className&&(" "+y.className+" ").indexOf(w)>=0)){if(!v){e.push(y)
}}else{if(v){u[x]=false
}}}}return false
},ID:function(e){return e[1].replace(/\\/g,"")
},TAG:function(u,e){for(var v=0;
e[v]===false;
v++){}return e[v]&&r(e[v])?u[1]:u[1].toUpperCase()
},CHILD:function(e){if(e[1]=="nth"){var u=/(-?)(\d*)n((?:\+|-)?\d*)/.exec(e[2]=="even"&&"2n"||e[2]=="odd"&&"2n+1"||!/\D/.test(e[2])&&"0n+"+e[2]||e[2]);
e[2]=(u[1]+(u[2]||1))-0;
e[3]=u[3]-0
}e[0]=m++;
return e
},ATTR:function(x,u,v,e,y,z){var w=x[1].replace(/\\/g,"");
if(!z&&j.attrMap[w]){x[1]=j.attrMap[w]
}if(x[2]==="~="){x[4]=" "+x[4]+" "
}return x
},PSEUDO:function(x,u,v,e,y){if(x[1]==="not"){if(x[3].match(s).length>1||/^\w/.test(x[3])){x[3]=g(x[3],null,null,u)
}else{var w=g.filter(x[3],u,v,true^y);
if(!v){e.push.apply(e,w)
}return false
}}else{if(j.match.POS.test(x[0])||j.match.CHILD.test(x[0])){return true
}}return x
},POS:function(e){e.unshift(true);
return e
}},filters:{enabled:function(e){return e.disabled===false&&e.type!=="hidden"
},disabled:function(e){return e.disabled===true
},checked:function(e){return e.checked===true
},selected:function(e){e.parentNode.selectedIndex;
return e.selected===true
},parent:function(e){return !!e.firstChild
},empty:function(e){return !e.firstChild
},has:function(v,u,e){return !!g(e[3],v).length
},header:function(e){return/h\d/i.test(e.nodeName)
},text:function(e){return"text"===e.type
},radio:function(e){return"radio"===e.type
},checkbox:function(e){return"checkbox"===e.type
},file:function(e){return"file"===e.type
},password:function(e){return"password"===e.type
},submit:function(e){return"submit"===e.type
},image:function(e){return"image"===e.type
},reset:function(e){return"reset"===e.type
},button:function(e){return"button"===e.type||e.nodeName.toUpperCase()==="BUTTON"
},input:function(e){return/input|select|textarea|button/i.test(e.nodeName)
}},setFilters:{first:function(u,e){return e===0
},last:function(v,u,e,w){return u===w.length-1
},even:function(u,e){return e%2===0
},odd:function(u,e){return e%2===1
},lt:function(v,u,e){return u<e[3]-0
},gt:function(v,u,e){return u>e[3]-0
},nth:function(v,u,e){return e[3]-0==u
},eq:function(v,u,e){return e[3]-0==u
}},filter:{PSEUDO:function(z,v,w,AA){var u=v[1],x=j.filters[u];
if(x){return x(z,w,v,AA)
}else{if(u==="contains"){return(z.textContent||z.innerText||"").indexOf(v[3])>=0
}else{if(u==="not"){var y=v[3];
for(var w=0,e=y.length;
w<e;
w++){if(y[w]===z){return false
}}return true
}}}},CHILD:function(e,w){var z=w[1],u=e;
switch(z){case"only":case"first":while(u=u.previousSibling){if(u.nodeType===1){return false
}}if(z=="first"){return true
}u=e;
case"last":while(u=u.nextSibling){if(u.nodeType===1){return false
}}return true;
case"nth":var v=w[2],AC=w[3];
if(v==1&&AC==0){return true
}var y=w[0],AB=e.parentNode;
if(AB&&(AB.sizcache!==y||!e.nodeIndex)){var x=0;
for(u=AB.firstChild;
u;
u=u.nextSibling){if(u.nodeType===1){u.nodeIndex=++x
}}AB.sizcache=y
}var AA=e.nodeIndex-AC;
if(v==0){return AA==0
}else{return(AA%v==0&&AA/v>=0)
}}},ID:function(u,e){return u.nodeType===1&&u.getAttribute("id")===e
},TAG:function(u,e){return(e==="*"&&u.nodeType===1)||u.nodeName===e
},CLASS:function(u,e){return(" "+(u.className||u.getAttribute("class"))+" ").indexOf(e)>-1
},ATTR:function(y,w){var v=w[1],e=j.attrHandle[v]?j.attrHandle[v](y):y[v]!=null?y[v]:y.getAttribute(v),z=e+"",x=w[2],u=w[4];
return e==null?x==="!=":x==="="?z===u:x==="*="?z.indexOf(u)>=0:x==="~="?(" "+z+" ").indexOf(u)>=0:!u?z&&e!==false:x==="!="?z!=u:x==="^="?z.indexOf(u)===0:x==="$="?z.substr(z.length-u.length)===u:x==="|="?z===u||z.substr(0,u.length+1)===u+"-":false
},POS:function(x,u,v,y){var e=u[2],w=j.setFilters[e];
if(w){return w(x,v,u,y)
}}}};
var n=j.match.POS;
for(var p in j.match){j.match[p]=RegExp(j.match[p].source+/(?![^\[]*\])(?![^\(]*\))/.source)
}var f=function(u,e){u=Array.prototype.slice.call(u);
if(e){e.push.apply(e,u);
return e
}return u
};
try{Array.prototype.slice.call(document.documentElement.childNodes)
}catch(o){f=function(x,w){var u=w||[];
if(i.call(x)==="[object Array]"){Array.prototype.push.apply(u,x)
}else{if(typeof x.length==="number"){for(var v=0,e=x.length;
v<e;
v++){u.push(x[v])
}}else{for(var v=0;
x[v];
v++){u.push(x[v])
}}}return u
}
}var h;
if(document.documentElement.compareDocumentPosition){h=function(u,e){var v=u.compareDocumentPosition(e)&4?-1:u===e?0:1;
if(v===0){hasDuplicate=true
}return v
}
}else{if("sourceIndex" in document.documentElement){h=function(u,e){var v=u.sourceIndex-e.sourceIndex;
if(v===0){hasDuplicate=true
}return v
}
}else{if(document.createRange){h=function(w,u){var v=w.ownerDocument.createRange(),e=u.ownerDocument.createRange();
v.selectNode(w);
v.collapse(true);
e.selectNode(u);
e.collapse(true);
var x=v.compareBoundaryPoints(Range.START_TO_END,e);
if(x===0){hasDuplicate=true
}return x
}
}}}(function(){var u=document.createElement("form"),v="script"+(new Date).getTime();
u.innerHTML="<input name='"+v+"'/>";
var e=document.documentElement;
e.insertBefore(u,e.firstChild);
if(!!document.getElementById(v)){j.find.ID=function(x,y,z){if(typeof y.getElementById!=="undefined"&&!z){var w=y.getElementById(x[1]);
return w?w.id===x[1]||typeof w.getAttributeNode!=="undefined"&&w.getAttributeNode("id").nodeValue===x[1]?[w]:G:[]
}};
j.filter.ID=function(y,w){var x=typeof y.getAttributeNode!=="undefined"&&y.getAttributeNode("id");
return y.nodeType===1&&x&&x.nodeValue===w
}
}e.removeChild(u)
})();
(function(){var e=document.createElement("div");
e.appendChild(document.createComment(""));
if(e.getElementsByTagName("*").length>0){j.find.TAG=function(u,y){var x=y.getElementsByTagName(u[1]);
if(u[1]==="*"){var w=[];
for(var v=0;
x[v];
v++){if(x[v].nodeType===1){w.push(x[v])
}}x=w
}return x
}
}e.innerHTML="<a href='#'></a>";
if(e.firstChild&&typeof e.firstChild.getAttribute!=="undefined"&&e.firstChild.getAttribute("href")!=="#"){j.attrHandle.href=function(u){return u.getAttribute("href",2)
}
}})();
if(document.querySelectorAll){(function(){var e=g,u=document.createElement("div");
u.innerHTML="<p class='TEST'></p>";
if(u.querySelectorAll&&u.querySelectorAll(".TEST").length===0){return 
}g=function(y,x,v,w){x=x||document;
if(!w&&x.nodeType===9&&!r(x)){try{return f(x.querySelectorAll(y),v)
}catch(z){}}return e(y,x,v,w)
};
g.find=e.find;
g.filter=e.filter;
g.selectors=e.selectors;
g.matches=e.matches
})()
}if(document.getElementsByClassName&&document.documentElement.getElementsByClassName){(function(){var e=document.createElement("div");
e.innerHTML="<div class='test e'></div><div class='test'></div>";
if(e.getElementsByClassName("e").length===0){return 
}e.lastChild.className="e";
if(e.getElementsByClassName("e").length===1){return 
}j.order.splice(1,0,"CLASS");
j.find.CLASS=function(u,v,w){if(typeof v.getElementsByClassName!=="undefined"&&!w){return v.getElementsByClassName(u[1])
}}
})()
}function q(u,z,y,AD,AA,AC){var AB=u=="previousSibling"&&!AC;
for(var w=0,v=AD.length;
w<v;
w++){var e=AD[w];
if(e){if(AB&&e.nodeType===1){e.sizcache=y;
e.sizset=w
}e=e[u];
var x=false;
while(e){if(e.sizcache===y){x=AD[e.sizset];
break
}if(e.nodeType===1&&!AC){e.sizcache=y;
e.sizset=w
}if(e.nodeName===z){x=e;
break
}e=e[u]
}AD[w]=x
}}}function t(u,z,y,AD,AA,AC){var AB=u=="previousSibling"&&!AC;
for(var w=0,v=AD.length;
w<v;
w++){var e=AD[w];
if(e){if(AB&&e.nodeType===1){e.sizcache=y;
e.sizset=w
}e=e[u];
var x=false;
while(e){if(e.sizcache===y){x=AD[e.sizset];
break
}if(e.nodeType===1){if(!AC){e.sizcache=y;
e.sizset=w
}if(typeof z!=="string"){if(e===z){x=true;
break
}}else{if(g.filter(z,[e]).length>0){x=e;
break
}}}e=e[u]
}AD[w]=x
}}}var l=document.compareDocumentPosition?function(u,e){return u.compareDocumentPosition(e)&16
}:function(u,e){return u!==e&&(u.contains?u.contains(e):true)
};
var r=function(e){return e.nodeType===9&&e.documentElement.nodeName!=="HTML"||!!e.ownerDocument&&r(e.ownerDocument)
};
var k=function(e,AA){var w=[],x="",y,v=AA.nodeType?[AA]:AA;
while((y=j.match.PSEUDO.exec(e))){x+=y[0];
e=e.replace(j.match.PSEUDO,"")
}e=j.relative[e]?e+"*":e;
for(var z=0,u=v.length;
z<u;
z++){g(e,v[z],w)
}return g.filter(x,w)
};
O.find=g;
O.filter=g.filter;
O.expr=g.selectors;
O.expr[":"]=O.expr.filters;
g.selectors.filters.hidden=function(e){return e.offsetWidth===0||e.offsetHeight===0
};
g.selectors.filters.visible=function(e){return e.offsetWidth>0||e.offsetHeight>0
};
g.selectors.filters.animated=function(e){return O.grep(O.timers,function(u){return e===u.elem
}).length
};
O.multiFilter=function(v,e,u){if(u){v=":not("+v+")"
}return g.matches(v,e)
};
O.dir=function(v,u){var e=[],w=v[u];
while(w&&w!=document){if(w.nodeType==1){e.push(w)
}w=w[u]
}return e
};
O.nth=function(x,e,v,w){e=e||1;
var u=0;
for(;
x;
x=x[v]){if(x.nodeType==1&&++u==e){break
}}return x
};
O.sibling=function(v,u){var e=[];
for(;
v;
v=v.nextSibling){if(v.nodeType==1&&v!=u){e.push(v)
}}return e
};
return ;
L.Sizzle=g
})();
O.event={add:function(i,f,h,k){if(i.nodeType==3||i.nodeType==8){return 
}if(i.setInterval&&i!=L){i=L
}if(!h.guid){h.guid=this.guid++
}if(k!==G){var g=h;
h=this.proxy(g);
h.data=k
}var e=O.data(i,"events")||O.data(i,"events",{}),j=O.data(i,"handle")||O.data(i,"handle",function(){return typeof O!=="undefined"&&!O.event.triggered?O.event.handle.apply(arguments.callee.elem,arguments):G
});
j.elem=i;
O.each(f.split(/\s+/),function(m,n){var o=n.split(".");
n=o.shift();
h.type=o.slice().sort().join(".");
var l=e[n];
if(O.event.specialAll[n]){O.event.specialAll[n].setup.call(i,k,o)
}if(!l){l=e[n]={};
if(!O.event.special[n]||O.event.special[n].setup.call(i,k,o)===false){if(i.addEventListener){i.addEventListener(n,j,false)
}else{if(i.attachEvent){i.attachEvent("on"+n,j)
}}}}l[h.guid]=h;
O.event.global[n]=true
});
i=null
},guid:1,global:{},remove:function(k,h,j){if(k.nodeType==3||k.nodeType==8){return 
}var g=O.data(k,"events"),f,e;
if(g){if(h===G||(typeof h==="string"&&h.charAt(0)==".")){for(var i in g){this.remove(k,i+(h||""))
}}else{if(h.type){j=h.handler;
h=h.type
}O.each(h.split(/\s+/),function(m,o){var q=o.split(".");
o=q.shift();
var n=RegExp("(^|\\.)"+q.slice().sort().join(".*\\.")+"(\\.|$)");
if(g[o]){if(j){delete g[o][j.guid]
}else{for(var p in g[o]){if(n.test(g[o][p].type)){delete g[o][p]
}}}if(O.event.specialAll[o]){O.event.specialAll[o].teardown.call(k,q)
}for(f in g[o]){break
}if(!f){if(!O.event.special[o]||O.event.special[o].teardown.call(k,q)===false){if(k.removeEventListener){k.removeEventListener(o,O.data(k,"handle"),false)
}else{if(k.detachEvent){k.detachEvent("on"+o,O.data(k,"handle"))
}}}f=null;
delete g[o]
}}})
}for(f in g){break
}if(!f){var l=O.data(k,"handle");
if(l){l.elem=null
}O.removeData(k,"events");
O.removeData(k,"handle")
}}},trigger:function(j,l,i,f){var h=j.type||j;
if(!f){j=typeof j==="object"?j[H]?j:O.extend(O.Event(h),j):O.Event(h);
if(h.indexOf("!")>=0){j.type=h=h.slice(0,-1);
j.exclusive=true
}if(!i){j.stopPropagation();
if(this.global[h]){O.each(O.cache,function(){if(this.events&&this.events[h]){O.event.trigger(j,l,this.handle.elem)
}})
}}if(!i||i.nodeType==3||i.nodeType==8){return G
}j.result=G;
j.target=i;
l=O.makeArray(l);
l.unshift(j)
}j.currentTarget=i;
var k=O.data(i,"handle");
if(k){k.apply(i,l)
}if((!i[h]||(O.nodeName(i,"a")&&h=="click"))&&i["on"+h]&&i["on"+h].apply(i,l)===false){j.result=false
}if(!f&&i[h]&&!j.isDefaultPrevented()&&!(O.nodeName(i,"a")&&h=="click")){this.triggered=true;
try{i[h]()
}catch(m){}}this.triggered=false;
if(!j.isPropagationStopped()){var g=i.parentNode||i.ownerDocument;
if(g){O.event.trigger(j,l,g,true)
}}},handle:function(l){var k,e;
l=arguments[0]=O.event.fix(l||L.event);
l.currentTarget=this;
var m=l.type.split(".");
l.type=m.shift();
k=!m.length&&!l.exclusive;
var i=RegExp("(^|\\.)"+m.slice().sort().join(".*\\.")+"(\\.|$)");
e=(O.data(this,"events")||{})[l.type];
for(var g in e){var h=e[g];
if(k||i.test(h.type)){l.handler=h;
l.data=h.data;
var f=h.apply(this,arguments);
if(f!==G){l.result=f;
if(f===false){l.preventDefault();
l.stopPropagation()
}}if(l.isImmediatePropagationStopped()){break
}}}},props:"altKey attrChange attrName bubbles button cancelable charCode clientX clientY ctrlKey currentTarget data detail eventPhase fromElement handler keyCode metaKey newValue originalTarget pageX pageY prevValue relatedNode relatedTarget screenX screenY shiftKey srcElement target toElement view wheelDelta which".split(" "),fix:function(h){if(h[H]){return h
}var f=h;
h=O.Event(f);
for(var g=this.props.length,k;
g;
){k=this.props[--g];
h[k]=f[k]
}if(!h.target){h.target=h.srcElement||document
}if(h.target.nodeType==3){h.target=h.target.parentNode
}if(!h.relatedTarget&&h.fromElement){h.relatedTarget=h.fromElement==h.target?h.toElement:h.fromElement
}if(h.pageX==null&&h.clientX!=null){var j=document.documentElement,e=document.body;
h.pageX=h.clientX+(j&&j.scrollLeft||e&&e.scrollLeft||0)-(j.clientLeft||0);
h.pageY=h.clientY+(j&&j.scrollTop||e&&e.scrollTop||0)-(j.clientTop||0)
}if(!h.which&&((h.charCode||h.charCode===0)?h.charCode:h.keyCode)){h.which=h.charCode||h.keyCode
}if(!h.metaKey&&h.ctrlKey){h.metaKey=h.ctrlKey
}if(!h.which&&h.button){h.which=(h.button&1?1:(h.button&2?3:(h.button&4?2:0)))
}return h
},proxy:function(f,e){e=e||function(){return f.apply(this,arguments)
};
e.guid=f.guid=f.guid||e.guid||this.guid++;
return e
},special:{ready:{setup:b,teardown:function(){}}},specialAll:{live:{setup:function(e,f){O.event.add(this,f[0],C)
},teardown:function(g){if(g.length){var e=0,f=RegExp("(^|\\.)"+g[0]+"(\\.|$)");
O.each((O.data(this,"events").live||{}),function(){if(f.test(this.type)){e++
}});
if(e<1){O.event.remove(this,g[0],C)
}}}}}};
O.Event=function(e){if(!this.preventDefault){return new O.Event(e)
}if(e&&e.type){this.originalEvent=e;
this.type=e.type
}else{this.type=e
}this.timeStamp=E();
this[H]=true
};
function K(){return false
}function U(){return true
}O.Event.prototype={preventDefault:function(){this.isDefaultPrevented=U;
var f=this.originalEvent;
if(!f){return 
}if(f.preventDefault){f.preventDefault()
}f.returnValue=false
},stopPropagation:function(){this.isPropagationStopped=U;
var f=this.originalEvent;
if(!f){return 
}if(f.stopPropagation){f.stopPropagation()
}f.cancelBubble=true
},stopImmediatePropagation:function(){this.isImmediatePropagationStopped=U;
this.stopPropagation()
},isDefaultPrevented:K,isPropagationStopped:K,isImmediatePropagationStopped:K};
var A=function(g){var f=g.relatedTarget;
while(f&&f!=this){try{f=f.parentNode
}catch(h){f=this
}}if(f!=this){g.type=g.data;
O.event.handle.apply(this,arguments)
}};
O.each({mouseover:"mouseenter",mouseout:"mouseleave"},function(f,e){O.event.special[e]={setup:function(){O.event.add(this,f,A,e)
},teardown:function(){O.event.remove(this,f,A)
}}
});
O.fn.extend({bind:function(f,g,e){return f=="unload"?this.one(f,g,e):this.each(function(){O.event.add(this,f,e||g,e&&g)
})
},one:function(g,h,f){var e=O.event.proxy(f||h,function(i){O(this).unbind(i,e);
return(f||h).apply(this,arguments)
});
return this.each(function(){O.event.add(this,g,e,f&&h)
})
},unbind:function(f,e){return this.each(function(){O.event.remove(this,f,e)
})
},trigger:function(e,f){return this.each(function(){O.event.trigger(e,f,this)
})
},triggerHandler:function(e,g){if(this[0]){var f=O.Event(e);
f.preventDefault();
f.stopPropagation();
O.event.trigger(f,g,this[0]);
return f.result
}},toggle:function(g){var e=arguments,f=1;
while(f<e.length){O.event.proxy(g,e[f++])
}return this.click(O.event.proxy(g,function(h){this.lastToggle=(this.lastToggle||0)%f;
h.preventDefault();
return e[this.lastToggle++].apply(this,arguments)||false
}))
},hover:function(e,f){return this.mouseenter(e).mouseleave(f)
},ready:function(e){b();
if(O.isReady){e.call(document,O)
}else{O.readyList.push(e)
}return this
},live:function(g,f){var e=O.event.proxy(f);
e.guid+=this.selector+g;
O(document).bind(I(g,this.selector),this.selector,e);
return this
},die:function(f,e){O(document).unbind(I(f,this.selector),e?{guid:e.guid+this.selector+f}:null);
return this
}});
function C(h){var e=RegExp("(^|\\.)"+h.type+"(\\.|$)"),g=true,f=[];
O.each(O.data(this,"events").live||[],function(j,k){if(e.test(k.type)){var l=O(h.target).closest(k.data)[0];
if(l){f.push({elem:l,fn:k})
}}});
f.sort(function(j,i){return O.data(j.elem,"closest")-O.data(i.elem,"closest")
});
O.each(f,function(){if(this.fn.call(this.elem,h,this.fn.data)===false){return(g=false)
}});
return g
}function I(f,e){return["live",f,e.replace(/\./g,"`").replace(/ /g,"|")].join(".")
}O.extend({isReady:false,readyList:[],ready:function(){if(!O.isReady){O.isReady=true;
if(O.readyList){O.each(O.readyList,function(){this.call(document,O)
});
O.readyList=null
}O(document).triggerHandler("ready")
}}});
var X=false;
function b(){if(X){return 
}X=true;
if(document.addEventListener){document.addEventListener("DOMContentLoaded",function(){document.removeEventListener("DOMContentLoaded",arguments.callee,false);
O.ready()
},false)
}else{if(document.attachEvent){document.attachEvent("onreadystatechange",function(){if(document.readyState==="complete"){document.detachEvent("onreadystatechange",arguments.callee);
O.ready()
}});
if(document.documentElement.doScroll&&L==L.top){(function(){if(O.isReady){return 
}try{document.documentElement.doScroll("left")
}catch(e){setTimeout(arguments.callee,0);
return 
}O.ready()
})()
}}}O.event.add(L,"load",O.ready)
}O.each(("blur,focus,load,resize,scroll,unload,click,dblclick,mousedown,mouseup,mousemove,mouseover,mouseout,mouseenter,mouseleave,change,select,submit,keydown,keypress,keyup,error").split(","),function(f,e){O.fn[e]=function(g){return g?this.bind(e,g):this.trigger(e)
}
});
O(L).bind("unload",function(){for(var e in O.cache){if(e!=1&&O.cache[e].handle){O.event.remove(O.cache[e].handle.elem)
}}});
(function(){O.support={};
var g=document.documentElement,h=document.createElement("script"),l=document.createElement("div"),k="script"+(new Date).getTime();
l.style.display="none";
l.innerHTML='   <link/><table></table><a href="/a" style="color:red;float:left;opacity:.5;">a</a><select><option>text</option></select><object><param/></object>';
var i=l.getElementsByTagName("*"),f=l.getElementsByTagName("a")[0];
if(!i||!i.length||!f){return 
}O.support={leadingWhitespace:l.firstChild.nodeType==3,tbody:!l.getElementsByTagName("tbody").length,objectAll:!!l.getElementsByTagName("object")[0].getElementsByTagName("*").length,htmlSerialize:!!l.getElementsByTagName("link").length,style:/red/.test(f.getAttribute("style")),hrefNormalized:f.getAttribute("href")==="/a",opacity:f.style.opacity==="0.5",cssFloat:!!f.style.cssFloat,scriptEval:false,noCloneEvent:true,boxModel:null};
h.type="text/javascript";
try{h.appendChild(document.createTextNode("window."+k+"=1;"))
}catch(j){}g.insertBefore(h,g.firstChild);
if(L[k]){O.support.scriptEval=true;
delete L[k]
}g.removeChild(h);
if(l.attachEvent&&l.fireEvent){l.attachEvent("onclick",function(){O.support.noCloneEvent=false;
l.detachEvent("onclick",arguments.callee)
});
l.cloneNode(true).fireEvent("onclick")
}O(function(){var e=document.createElement("div");
e.style.width=e.style.paddingLeft="1px";
document.body.appendChild(e);
O.boxModel=O.support.boxModel=e.offsetWidth===2;
document.body.removeChild(e).style.display="none"
})
})();
var W=O.support.cssFloat?"cssFloat":"styleFloat";
O.props={"for":"htmlFor","class":"className","float":W,cssFloat:W,styleFloat:W,readonly:"readOnly",maxlength:"maxLength",cellspacing:"cellSpacing",rowspan:"rowSpan",tabindex:"tabIndex"};
O.fn.extend({_load:O.fn.load,load:function(g,j,k){if(typeof g!=="string"){return this._load(g)
}var i=g.indexOf(" ");
if(i>=0){var e=g.slice(i,g.length);
g=g.slice(0,i)
}var h="GET";
if(j){if(O.isFunction(j)){k=j;
j=null
}else{if(typeof j==="object"){j=O.param(j);
h="POST"
}}}var f=this;
O.ajax({url:g,type:h,dataType:"html",data:j,complete:function(m,l){if(l=="success"||l=="notmodified"){f.html(e?O("<div/>").append(m.responseText.replace(/<script(.|\s)*?\/script>/g,"")).find(e):m.responseText)
}if(k){f.each(k,[m.responseText,l,m])
}}});
return this
},serialize:function(){return O.param(this.serializeArray())
},serializeArray:function(){return this.map(function(){return this.elements?O.makeArray(this.elements):this
}).filter(function(){return this.name&&!this.disabled&&(this.checked||/select|textarea/i.test(this.nodeName)||/text|hidden|password|search/i.test(this.type))
}).map(function(e,f){var g=O(this).val();
return g==null?null:O.isArray(g)?O.map(g,function(j,h){return{name:f.name,value:j}
}):{name:f.name,value:g}
}).get()
}});
O.each("ajaxStart,ajaxStop,ajaxComplete,ajaxError,ajaxSuccess,ajaxSend".split(","),function(e,f){O.fn[f]=function(g){return this.bind(f,g)
}
});
var R=E();
O.extend({get:function(e,g,h,f){if(O.isFunction(g)){h=g;
g=null
}return O.ajax({type:"GET",url:e,data:g,success:h,dataType:f})
},getScript:function(e,f){return O.get(e,null,f,"script")
},getJSON:function(e,f,g){return O.get(e,f,g,"json")
},post:function(e,g,h,f){if(O.isFunction(g)){h=g;
g={}
}return O.ajax({type:"POST",url:e,data:g,success:h,dataType:f})
},ajaxSetup:function(e){O.extend(O.ajaxSettings,e)
},ajaxSettings:{url:location.href,global:true,type:"GET",contentType:"application/x-www-form-urlencoded",processData:true,async:true,xhr:function(){return L.ActiveXObject?new ActiveXObject("Microsoft.XMLHTTP"):new XMLHttpRequest()
},accepts:{xml:"application/xml, text/xml",html:"text/html",script:"text/javascript, application/javascript",json:"application/json, text/javascript",text:"text/plain",_default:"*/*"}},lastModified:{},ajax:function(n){n=O.extend(true,n,O.extend(true,{},O.ajaxSettings,n));
var y,g=/=\?(&|$)/g,t,x,h=n.type.toUpperCase();
if(n.data&&n.processData&&typeof n.data!=="string"){n.data=O.param(n.data)
}if(n.dataType=="jsonp"){if(h=="GET"){if(!n.url.match(g)){n.url+=(n.url.match(/\?/)?"&":"?")+(n.jsonp||"callback")+"=?"
}}else{if(!n.data||!n.data.match(g)){n.data=(n.data?n.data+"&":"")+(n.jsonp||"callback")+"=?"
}}n.dataType="json"
}if(n.dataType=="json"&&(n.data&&n.data.match(g)||n.url.match(g))){y="jsonp"+R++;
if(n.data){n.data=(n.data+"").replace(g,"="+y+"$1")
}n.url=n.url.replace(g,"="+y+"$1");
n.dataType="script";
L[y]=function(s){x=s;
j();
m();
L[y]=G;
try{delete L[y]
}catch(z){}if(i){i.removeChild(v)
}}
}if(n.dataType=="script"&&n.cache==null){n.cache=false
}if(n.cache===false&&h=="GET"){var f=E();
var w=n.url.replace(/(\?|&)_=.*?(&|$)/,"$1_="+f+"$2");
n.url=w+((w==n.url)?(n.url.match(/\?/)?"&":"?")+"_="+f:"")
}if(n.data&&h=="GET"){n.url+=(n.url.match(/\?/)?"&":"?")+n.data;
n.data=null
}if(n.global&&!O.active++){O.event.trigger("ajaxStart")
}var r=/^(\w+:)?\/\/([^\/?#]+)/.exec(n.url);
if(n.dataType=="script"&&h=="GET"&&r&&(r[1]&&r[1]!=location.protocol||r[2]!=location.host)){var i=document.getElementsByTagName("head")[0];
var v=document.createElement("script");
v.src=n.url;
if(n.scriptCharset){v.charset=n.scriptCharset
}if(!y){var p=false;
v.onload=v.onreadystatechange=function(){if(!p&&(!this.readyState||this.readyState=="loaded"||this.readyState=="complete")){p=true;
j();
m();
v.onload=v.onreadystatechange=null;
i.removeChild(v)
}}
}i.appendChild(v);
return G
}var l=false;
var k=n.xhr();
if(n.username){k.open(h,n.url,n.async,n.username,n.password)
}else{k.open(h,n.url,n.async)
}try{if(n.data){k.setRequestHeader("Content-Type",n.contentType)
}if(n.ifModified){k.setRequestHeader("If-Modified-Since",O.lastModified[n.url]||"Thu, 01 Jan 1970 00:00:00 GMT")
}k.setRequestHeader("X-Requested-With","XMLHttpRequest");
k.setRequestHeader("Accept",n.dataType&&n.accepts[n.dataType]?n.accepts[n.dataType]+", */*":n.accepts._default)
}catch(u){}if(n.beforeSend&&n.beforeSend(k,n)===false){if(n.global&&!--O.active){O.event.trigger("ajaxStop")
}k.abort();
return false
}if(n.global){O.event.trigger("ajaxSend",[k,n])
}var o=function(s){if(k.readyState==0){if(q){clearInterval(q);
q=null;
if(n.global&&!--O.active){O.event.trigger("ajaxStop")
}}}else{if(!l&&k&&(k.readyState==4||s=="timeout")){l=true;
if(q){clearInterval(q);
q=null
}t=s=="timeout"?"timeout":!O.httpSuccess(k)?"error":n.ifModified&&O.httpNotModified(k,n.url)?"notmodified":"success";
if(t=="success"){try{x=O.httpData(k,n.dataType,n)
}catch(AA){t="parsererror"
}}if(t=="success"){var z;
try{z=k.getResponseHeader("Last-Modified")
}catch(AA){}if(n.ifModified&&z){O.lastModified[n.url]=z
}if(!y){j()
}}else{O.handleError(n,k,t)
}m();
if(s){k.abort()
}if(n.async){k=null
}}}};
if(n.async){var q=setInterval(o,13);
if(n.timeout>0){setTimeout(function(){if(k&&!l){o("timeout")
}},n.timeout)
}}try{k.send(n.data)
}catch(u){O.handleError(n,k,null,u)
}if(!n.async){o()
}function j(){if(n.success){n.success(x,t)
}if(n.global){O.event.trigger("ajaxSuccess",[k,n])
}}function m(){if(n.complete){n.complete(k,t)
}if(n.global){O.event.trigger("ajaxComplete",[k,n])
}if(n.global&&!--O.active){O.event.trigger("ajaxStop")
}}return k
},handleError:function(g,i,f,h){if(g.error){g.error(i,f,h)
}if(g.global){O.event.trigger("ajaxError",[i,g,h])
}},active:0,httpSuccess:function(g){try{return !g.status&&location.protocol=="file:"||(g.status>=200&&g.status<300)||g.status==304||g.status==1223
}catch(f){}return false
},httpNotModified:function(h,f){try{var i=h.getResponseHeader("Last-Modified");
return h.status==304||i==O.lastModified[f]
}catch(g){}return false
},httpData:function(j,h,g){var f=j.getResponseHeader("content-type"),e=h=="xml"||!h&&f&&f.indexOf("xml")>=0,i=e?j.responseXML:j.responseText;
if(e&&i.documentElement.tagName=="parsererror"){throw"parsererror"
}if(g&&g.dataFilter){i=g.dataFilter(i,h)
}if(typeof i==="string"){if(h=="script"){O.globalEval(i)
}if(h=="json"){i=L["eval"]("("+i+")")
}}return i
},param:function(e){var g=[];
function h(i,j){g[g.length]=encodeURIComponent(i)+"="+encodeURIComponent(j)
}if(O.isArray(e)||e.jquery){O.each(e,function(){h(this.name,this.value)
})
}else{for(var f in e){if(O.isArray(e[f])){O.each(e[f],function(){h(f,this)
})
}else{h(f,O.isFunction(e[f])?e[f]():e[f])
}}}return g.join("&").replace(/%20/g,"+")
}});
var M={},N,D=[["height","marginTop","marginBottom","paddingTop","paddingBottom"],["width","marginLeft","marginRight","paddingLeft","paddingRight"],["opacity"]];
function T(f,e){var g={};
O.each(D.concat.apply([],D.slice(0,e)),function(){g[this]=f
});
return g
}O.fn.extend({show:function(k,n){if(k){return this.animate(T("show",3),k,n)
}else{for(var h=0,f=this.length;
h<f;
h++){var e=O.data(this[h],"olddisplay");
this[h].style.display=e||"";
if(O.css(this[h],"display")==="none"){var g=this[h].tagName,m;
if(M[g]){m=M[g]
}else{var j=O("<"+g+" />").appendTo("body");
m=j.css("display");
if(m==="none"){m="block"
}j.remove();
M[g]=m
}O.data(this[h],"olddisplay",m)
}}for(var h=0,f=this.length;
h<f;
h++){this[h].style.display=O.data(this[h],"olddisplay")||""
}return this
}},hide:function(h,j){if(h){return this.animate(T("hide",3),h,j)
}else{for(var g=0,f=this.length;
g<f;
g++){var e=O.data(this[g],"olddisplay");
if(!e&&e!=="none"){O.data(this[g],"olddisplay",O.css(this[g],"display"))
}}for(var g=0,f=this.length;
g<f;
g++){this[g].style.display="none"
}return this
}},_toggle:O.fn.toggle,toggle:function(g,f){var e=typeof g==="boolean";
return O.isFunction(g)&&O.isFunction(f)?this._toggle.apply(this,arguments):g==null||e?this.each(function(){var h=e?g:O(this).is(":hidden");
O(this)[h?"show":"hide"]()
}):this.animate(T("toggle",3),g,f)
},fadeTo:function(e,g,f){return this.animate({opacity:g},e,f)
},animate:function(i,f,h,g){var e=O.speed(f,h,g);
return this[e.queue===false?"each":"queue"](function(){var k=O.extend({},e),m,l=this.nodeType==1&&O(this).is(":hidden"),j=this;
for(m in i){if(i[m]=="hide"&&l||i[m]=="show"&&!l){return k.complete.call(this)
}if((m=="height"||m=="width")&&this.style){k.display=O.css(this,"display");
k.overflow=this.style.overflow
}}if(k.overflow!=null){this.style.overflow="hidden"
}k.curAnim=O.extend({},i);
O.each(i,function(o,s){var r=new O.fx(j,k,o);
if(/toggle|show|hide/.test(s)){r[s=="toggle"?l?"show":"hide":s](i)
}else{var q=s.toString().match(/^([+-]=)?([\d+-.]+)(.*)$/),t=r.cur(true)||0;
if(q){var n=parseFloat(q[2]),p=q[3]||"px";
if(p!="px"){j.style[o]=(n||1)+p;
t=((n||1)/r.cur(true))*t;
j.style[o]=t+p
}if(q[1]){n=((q[1]=="-="?-1:1)*n)+t
}r.custom(t,n,p)
}else{r.custom(t,s,"")
}}});
return true
})
},stop:function(f,e){var g=O.timers;
if(f){this.queue([])
}this.each(function(){for(var h=g.length-1;
h>=0;
h--){if(g[h].elem==this){if(e){g[h](true)
}g.splice(h,1)
}}});
if(!e){this.dequeue()
}return this
}});
O.each({slideDown:T("show",1),slideUp:T("hide",1),slideToggle:T("toggle",1),fadeIn:{opacity:"show"},fadeOut:{opacity:"hide"}},function(e,f){O.fn[e]=function(g,h){return this.animate(f,g,h)
}
});
O.extend({speed:function(g,h,f){var e=typeof g==="object"?g:{complete:f||!f&&h||O.isFunction(g)&&g,duration:g,easing:f&&h||h&&!O.isFunction(h)&&h};
e.duration=O.fx.off?0:typeof e.duration==="number"?e.duration:O.fx.speeds[e.duration]||O.fx.speeds._default;
e.old=e.complete;
e.complete=function(){if(e.queue!==false){O(this).dequeue()
}if(O.isFunction(e.old)){e.old.call(this)
}};
return e
},easing:{linear:function(g,h,e,f){return e+f*g
},swing:function(g,h,e,f){return((-Math.cos(g*Math.PI)/2)+0.5)*f+e
}},timers:[],fx:function(f,e,g){this.options=e;
this.elem=f;
this.prop=g;
if(!e.orig){e.orig={}
}}});
O.fx.prototype={update:function(){if(this.options.step){this.options.step.call(this.elem,this.now,this)
}(O.fx.step[this.prop]||O.fx.step._default)(this);
if((this.prop=="height"||this.prop=="width")&&this.elem.style){this.elem.style.display="block"
}},cur:function(f){if(this.elem[this.prop]!=null&&(!this.elem.style||this.elem.style[this.prop]==null)){return this.elem[this.prop]
}var e=parseFloat(O.css(this.elem,this.prop,f));
return e&&e>-10000?e:parseFloat(O.curCSS(this.elem,this.prop))||0
},custom:function(i,h,g){this.startTime=E();
this.start=i;
this.end=h;
this.unit=g||this.unit||"px";
this.now=this.start;
this.pos=this.state=0;
var e=this;
function f(j){return e.step(j)
}f.elem=this.elem;
if(f()&&O.timers.push(f)&&!N){N=setInterval(function(){var k=O.timers;
for(var j=0;
j<k.length;
j++){if(!k[j]()){k.splice(j--,1)
}}if(!k.length){clearInterval(N);
N=G
}},13)
}},show:function(){this.options.orig[this.prop]=O.attr(this.elem.style,this.prop);
this.options.show=true;
this.custom(this.prop=="width"||this.prop=="height"?1:0,this.cur());
O(this.elem).show()
},hide:function(){this.options.orig[this.prop]=O.attr(this.elem.style,this.prop);
this.options.hide=true;
this.custom(this.cur(),0)
},step:function(h){var g=E();
if(h||g>=this.options.duration+this.startTime){this.now=this.end;
this.pos=this.state=1;
this.update();
this.options.curAnim[this.prop]=true;
var e=true;
for(var f in this.options.curAnim){if(this.options.curAnim[f]!==true){e=false
}}if(e){if(this.options.display!=null){this.elem.style.overflow=this.options.overflow;
this.elem.style.display=this.options.display;
if(O.css(this.elem,"display")=="none"){this.elem.style.display="block"
}}if(this.options.hide){O(this.elem).hide()
}if(this.options.hide||this.options.show){for(var j in this.options.curAnim){O.attr(this.elem.style,j,this.options.orig[j])
}}this.options.complete.call(this.elem)
}return false
}else{var k=g-this.startTime;
this.state=k/this.options.duration;
this.pos=O.easing[this.options.easing||(O.easing.swing?"swing":"linear")](this.state,k,0,1,this.options.duration);
this.now=this.start+((this.end-this.start)*this.pos);
this.update()
}return true
}};
O.extend(O.fx,{speeds:{slow:600,fast:200,_default:400},step:{opacity:function(e){O.attr(e.elem.style,"opacity",e.now)
},_default:function(e){if(e.elem.style&&e.elem.style[e.prop]!=null){e.elem.style[e.prop]=e.now+e.unit
}else{e.elem[e.prop]=e.now
}}}});
if(document.documentElement.getBoundingClientRect){O.fn.offset=function(){if(!this[0]){return{top:0,left:0}
}if(this[0]===this[0].ownerDocument.body){return O.offset.bodyOffset(this[0])
}var g=this[0].getBoundingClientRect(),j=this[0].ownerDocument,f=j.body,e=j.documentElement,l=e.clientTop||f.clientTop||0,k=e.clientLeft||f.clientLeft||0,i=g.top+(self.pageYOffset||O.boxModel&&e.scrollTop||f.scrollTop)-l,h=g.left+(self.pageXOffset||O.boxModel&&e.scrollLeft||f.scrollLeft)-k;
return{top:i,left:h}
}
}else{O.fn.offset=function(){if(!this[0]){return{top:0,left:0}
}if(this[0]===this[0].ownerDocument.body){return O.offset.bodyOffset(this[0])
}O.offset.initialized||O.offset.initialize();
var j=this[0],g=j.offsetParent,f=j,o=j.ownerDocument,m,h=o.documentElement,k=o.body,l=o.defaultView,e=l.getComputedStyle(j,null),n=j.offsetTop,i=j.offsetLeft;
while((j=j.parentNode)&&j!==k&&j!==h){m=l.getComputedStyle(j,null);
n-=j.scrollTop,i-=j.scrollLeft;
if(j===g){n+=j.offsetTop,i+=j.offsetLeft;
if(O.offset.doesNotAddBorder&&!(O.offset.doesAddBorderForTableAndCells&&/^t(able|d|h)$/i.test(j.tagName))){n+=parseInt(m.borderTopWidth,10)||0,i+=parseInt(m.borderLeftWidth,10)||0
}f=g,g=j.offsetParent
}if(O.offset.subtractsBorderForOverflowNotVisible&&m.overflow!=="visible"){n+=parseInt(m.borderTopWidth,10)||0,i+=parseInt(m.borderLeftWidth,10)||0
}e=m
}if(e.position==="relative"||e.position==="static"){n+=k.offsetTop,i+=k.offsetLeft
}if(e.position==="fixed"){n+=Math.max(h.scrollTop,k.scrollTop),i+=Math.max(h.scrollLeft,k.scrollLeft)
}return{top:n,left:i}
}
}O.offset={initialize:function(){if(this.initialized){return 
}var l=document.body,f=document.createElement("div"),h,g,n,i,m,e,j=l.style.marginTop,k='<div style="position:absolute;top:0;left:0;margin:0;border:5px solid #000;padding:0;width:1px;height:1px;"><div></div></div><table style="position:absolute;top:0;left:0;margin:0;border:5px solid #000;padding:0;width:1px;height:1px;" cellpadding="0" cellspacing="0"><tr><td></td></tr></table>';
m={position:"absolute",top:0,left:0,margin:0,border:0,width:"1px",height:"1px",visibility:"hidden"};
for(e in m){f.style[e]=m[e]
}f.innerHTML=k;
l.insertBefore(f,l.firstChild);
h=f.firstChild,g=h.firstChild,i=h.nextSibling.firstChild.firstChild;
this.doesNotAddBorder=(g.offsetTop!==5);
this.doesAddBorderForTableAndCells=(i.offsetTop===5);
h.style.overflow="hidden",h.style.position="relative";
this.subtractsBorderForOverflowNotVisible=(g.offsetTop===-5);
l.style.marginTop="1px";
this.doesNotIncludeMarginInBodyOffset=(l.offsetTop===0);
l.style.marginTop=j;
l.removeChild(f);
this.initialized=true
},bodyOffset:function(e){O.offset.initialized||O.offset.initialize();
var g=e.offsetTop,f=e.offsetLeft;
if(O.offset.doesNotIncludeMarginInBodyOffset){g+=parseInt(O.curCSS(e,"marginTop",true),10)||0,f+=parseInt(O.curCSS(e,"marginLeft",true),10)||0
}return{top:g,left:f}
}};
O.fn.extend({position:function(){var i=0,h=0,f;
if(this[0]){var g=this.offsetParent(),j=this.offset(),e=/^body|html$/i.test(g[0].tagName)?{top:0,left:0}:g.offset();
j.top-=J(this,"marginTop");
j.left-=J(this,"marginLeft");
e.top+=J(g,"borderTopWidth");
e.left+=J(g,"borderLeftWidth");
f={top:j.top-e.top,left:j.left-e.left}
}return f
},offsetParent:function(){var e=this[0].offsetParent||document.body;
while(e&&(!/^body|html$/i.test(e.tagName)&&O.css(e,"position")=="static")){e=e.offsetParent
}return O(e)
}});
O.each(["Left","Top"],function(f,e){var g="scroll"+e;
O.fn[g]=function(h){if(!this[0]){return null
}return h!==G?this.each(function(){this==L||this==document?L.scrollTo(!f?h:O(L).scrollLeft(),f?h:O(L).scrollTop()):this[g]=h
}):this[0]==L||this[0]==document?self[f?"pageYOffset":"pageXOffset"]||O.boxModel&&document.documentElement[g]||document.body[g]:this[0][g]
}
});
O.each(["Height","Width"],function(j,g){var e=j?"Left":"Top",h=j?"Right":"Bottom",f=g.toLowerCase();
O.fn["inner"+g]=function(){return this[0]?O.css(this[0],f,false,"padding"):null
};
O.fn["outer"+g]=function(i){return this[0]?O.css(this[0],f,false,i?"margin":"border"):null
};
var k=g.toLowerCase();
O.fn[k]=function(i){return this[0]==L?document.compatMode=="CSS1Compat"&&document.documentElement["client"+g]||document.body["client"+g]:this[0]==document?Math.max(document.documentElement["client"+g],document.body["scroll"+g],document.documentElement["scroll"+g],document.body["offset"+g],document.documentElement["offset"+g]):i===G?(this.length?O.css(this[0],k):null):this.css(k,typeof i==="string"?i:i+"px")
}
})
})();