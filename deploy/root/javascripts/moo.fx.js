//(c) 2006 Valerio Proietti (http://mad4milk.net). MIT-style license.
//moo.fx.js - depends on prototype.js OR prototype.lite.js
//version 2.0

var Fx = fx = {};

Fx.Base = function(){};
Fx.Base.prototype = {

	setOptions: function(options){
		this.options = Object.extend({
			onStart: function(){},
			onComplete: function(){},
			transition: Fx.Transitions.sineInOut,
			duration: 500,
			unit: 'px',
			wait: true,
			fps: 50
		}, options || {});
	},

	step: function(){
		var time = new Date().getTime();
		if (time < this.time + this.options.duration){
			this.cTime = time - this.time;
			this.setNow();
		} else {
			setTimeout(this.options.onComplete.bind(this, this.element), 10);
			this.clearTimer();
			this.now = this.to;
		}
		this.increase();
	},

	setNow: function(){
		this.now = this.compute(this.from, this.to);
	},

	compute: function(from, to){
		var change = to - from;
		return this.options.transition(this.cTime, from, change, this.options.duration);
	},

	clearTimer: function(){
		clearInterval(this.timer);
		this.timer = null;
		return this;
	},

	_start: function(from, to){
		if (!this.options.wait) this.clearTimer();
		if (this.timer) return;
		setTimeout(this.options.onStart.bind(this, this.element), 10);
		this.from = from;
		this.to = to;
		this.time = new Date().getTime();
		this.timer = setInterval(this.step.bind(this), Math.round(1000/this.options.fps));
		return this;
	},

	custom: function(from, to){
		return this._start(from, to);
	},

	set: function(to){
		this.now = to;
		this.increase();
		return this;
	},

	hide: function(){
		return this.set(0);
	},

	setStyle: function(e, p, v){
		if (p == 'opacity'){
			if (v == 0 && e.style.visibility != "hidden") e.style.visibility = "hidden";
			else if (e.style.visibility != "visible") e.style.visibility = "visible";
			if (window.ActiveXObject) e.style.filter = "alpha(opacity=" + v*100 + ")";
			e.style.opacity = v;
		} else e.style[p] = v+this.options.unit;
	}

};

Fx.Style = Class.create();
Fx.Style.prototype = Object.extend(new Fx.Base(), {

	initialize: function(el, property, options){
		this.element = $(el);
		this.setOptions(options);
		this.property = property.camelize();
	},

	increase: function(){
		this.setStyle(this.element, this.property, this.now);
	}

});

Fx.Styles = Class.create();
Fx.Styles.prototype = Object.extend(new Fx.Base(), {

	initialize: function(el, options){
		this.element = $(el);
		this.setOptions(options);
		this.now = {};
	},

	setNow: function(){
		for (p in this.from) this.now[p] = this.compute(this.from[p], this.to[p]);
	},

	custom: function(obj){
		if (this.timer && this.options.wait) return;
		var from = {};
		var to = {};
		for (p in obj){
			from[p] = obj[p][0];
			to[p] = obj[p][1];
		}
		return this._start(from, to);
	},

	increase: function(){
		for (var p in this.now) this.setStyle(this.element, p, this.now[p]);
	}

});

//Transitions (c) 2003 Robert Penner (http://www.robertpenner.com/easing/), BSD License.

Fx.Transitions = {
	linear: function(t, b, c, d) { return c*t/d + b; },
	sineInOut: function(t, b, c, d) { return -c/2 * (Math.cos(Math.PI*t/d) - 1) + b; }
};