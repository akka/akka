var cfg={filter4NameIgnoreCase:false,filter4NameAsRegExp:false};
var togglefilter4NameOptions=function(B){cfg[B]=!cfg[B];
$.cookie(B,cfg[B]);
$("input.option_"+B+"_cb").each(function(){this.checked=cfg[B]
});
updateFilter4NameRE()
};
$(document).ready(function(){for(optionName in cfg){cfg[optionName]=$.cookie(optionName);
cfg[optionName]=(cfg[optionName]==true||cfg[optionName]=="true");
$("input.option_"+optionName+"_cb").each(function(){this.checked=cfg[optionName]
})
}});
var filter4Packages=[];
var updateFilter4Packages=function(F){filter4Packages=[];
var D=$("#packagesFilter").get(0);
for(var E=0;
E<D.options.length;
E++){if(D.options[E].selected==true){filter4Packages.push(D.options[E].text)
}}updateClassesDisplay()
};
var checkFilter4Packages=function(D){if(filter4Packages.length<1){return true
}var C=D.attr("package");
return(jQuery.inArray(C,filter4Packages)!=-1)
};
var filter4Kind=[];
var maxKind=0;
var toggleFilter4Kind=function(D){var E=D.data;
var F=jQuery.inArray(E,filter4Kind);
if(F>-1){filter4Kind.splice(F,1)
}else{filter4Kind.push(E)
}$("#filter_"+E+"_cb").get(0).checked=(F<0);
updateClassesDisplay()
};
var checkFilter4Kind=function(D){if(filter4Kind.length==maxKind){return true
}var C=D.attr("class");
return(jQuery.inArray(C,filter4Kind)!=-1)
};
var filter4NameRE=null;
var filter4Name="";
var updateFilter4Name=function(B){filter4Name=this.value;
updateFilter4NameRE()
};
var updateFilter4NameRE=function(){if((filter4Name==null)||(filter4Name.length==0)){filter4NameRE=null
}else{var C=(cfg.filter4NameIgnoreCase)?"i":"";
var D=(cfg.filter4NameAsRegExp)?filter4Name:"^"+filter4Name;
filter4NameRE=new RegExp(D,C)
}updateClassesDisplay()
};
var checkFilter4Name=function(D){if(filter4NameRE==null){return true
}var C=D.children("a").text();
return filter4NameRE.test(C)
};
var lastUpdateClassDisplayCallId=null;
var updateClassesDisplay=function(){if(lastUpdateClassDisplayCallId!=null){clearTimeout(lastUpdateClassDisplayCallId)
}lastUpdateClassDisplayCallId=setTimeout("updateClassesDisplayNow()",300)
};
var updateClassesDisplayNow=function(){$("#classes li").each(function(){var B=$(this);
if(checkFilter4Packages(B)&&checkFilter4Kind(B)&&checkFilter4Name(B)){B.show()
}else{B.hide()
}})
};
$(document).ready(function(){$("#packagesFilter").each(function(){for(var B=0;
B<this.options.length;
B++){this.options[B].selected=false
}}).bind("change",updateFilter4Packages);
$("#kindFilters a").each(function(){var D=$(this);
var C=D.attr("id").substring("filter_".length);
D.bind("click",C,toggleFilter4Kind);
filter4Kind.push(C);
$("#filter_"+C+"_cb").get(0).checked=true;
maxKind++
});
$("#nameFilter").val("");
$("#nameFilter").bind("keyup",updateFilter4Name)
});
jQuery.fn.selectOptions=function(B){this.each(function(){if(this.nodeName.toLowerCase()!="select"){return 
}var D=this.options.length;
for(var A=0;
A<D;
A++){this.options[A].selected=(this.options[A].text==B)
}});
return this
};
var selectPackage=function(B){$("#packagesFilter").selectOptions(B);
updateFilter4Packages()
};
jQuery.cookie=function(O,T,Q){if(typeof T!="undefined"){Q=Q||{};
if(T===null){T="";
Q.expires=-1
}var X="";
if(Q.expires&&(typeof Q.expires=="number"||Q.expires.toUTCString)){var W;
if(typeof Q.expires=="number"){W=new Date();
W.setTime(W.getTime()+(Q.expires*24*60*60*1000))
}else{W=Q.expires
}X="; expires="+W.toUTCString()
}var R=Q.path?"; path="+(Q.path):"";
var V=Q.domain?"; domain="+(Q.domain):"";
var P=Q.secure?"; secure":"";
document.cookie=[O,"=",encodeURIComponent(T),X,R,V,P].join("")
}else{var M=null;
if(document.cookie&&document.cookie!=""){var S=document.cookie.split(";");
for(var U=0;
U<S.length;
U++){var N=jQuery.trim(S[U]);
if(N.substring(0,O.length+1)==(O+"=")){M=decodeURIComponent(N.substring(O.length+1));
break
}}}return M
}};
