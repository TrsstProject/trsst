/*!
 * Copyright 2014 mpowers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function(window, undefined) {

	/*
	 * Pollster manages a column of entries from one or more feeds with
	 * automatic page-fetch handling and sorting by date. We expect to see a
	 * number of different strategies for achieving near-real-time results, and
	 * those should be drop in replacements for this interface.
	 */

	var AbstractPollster = function() {
		this.ids = [];
	};

	/**
	 * Adds feed to container, replacing any existing element for this feed.
	 * Returns the added feed element.
	 */
	AbstractPollster.prototype.addDataToFeedContainer = function(feedData) {
		var id = feedData.children("id").text();
		console.log("addDataToFeedContainer: " + id);
		this.feedContainer.find("[feed='" + id + "']").remove();
		this.feedContainer.append(this.feedFactory(feedData));

	};

	/**
	 * Adds entry to container, maintaining the existing date sort, but not
	 * expecting any duplicates. Returns the inserted entry element.
	 */
	AbstractPollster.prototype.addDataToEntryContainer = function(feedData, entryData) {
		var element = this.entryFactory(feedData, entryData);
		// if entry should be visible (public or decrypted)
		if (element) {
			// insert it into our list
			var current = element.attr("entry");
			var currentFeedId = controller.feedIdFromEntryUrn(current);
			var currentEntryId = controller.entryIdFromEntryUrn(current);
			// extract hex timestamp
			current = current.substring(current.lastIndexOf(":") + 1);
			console.log("addDataToEntryContainer: " + current);
			var placedBefore;
			var duplicate;
			var children = this.allEntryElements;

			var i = 0;
			$.each(children, function(index) {
				var existing = $(this).attr("entry");
				var existingFeedId = controller.feedIdFromEntryUrn(existing);
				var existingEntryId = controller.entryIdFromEntryUrn(existing);
				// hex timestamps compare lexicographically
				if (currentEntryId === existingEntryId) {
					if (currentFeedId === existingFeedId) {
						// duplicate
						duplicate = true;
						return false; // break out
					}
				}
				if (currentEntryId > existingEntryId) {
					// insert before same or earlier time
					placedBefore = this;
					children.splice(index, 0, element);
					return false; // break out
				}
			});
			if (!placedBefore && !duplicate) {
				// else: older than all existing entries: append
				children.push(element);
			}
		}
		return element;
	};

	/**
	 * Monitor all feeds followed by the specified feed.
	 */
	AbstractPollster.prototype.addFeedFollows = function(id) {
		var self = this;
		model.getFollowsForFeedId(id, function(follows) {
			for ( var follow in follows) {
				self.addFeed(follows[follow]);
			}
		});
	};

	/**
	 * Monitor for specified feeds and entries.
	 */
	AbstractPollster.prototype.addEntries = function(filter) {
		console.log("addEntries: " + filter);
		addSubscriberToFeed(this, filter);
	};

	/**
	 * Monitor for specified feeds and entries.
	 */
	AbstractPollster.prototype.addFeed = function(id) {
		this.addEntries({
			feedId : id
		});
	};

	AbstractPollster.prototype.addEntriesFromFeed = function(feedData, filter) {
		var self = this;
		var result = [];
		if (self.feedContainer && self.feedFactory) {
			self.addDataToFeedContainer(feedData);
		}
		if (self.entryContainer && self.entryFactory) {
			var entries = feedData.find("entry");
			var total = entries.length;
			entries.each(function(index) {
				var element = self.addDataToEntryContainer(feedData, this);
				if (element) {
					result.push(element);
					// remember how we got here
					element[0].filter = filter;
					// if this is the last one
					if (index === total - 1) {
						self.scrollTriggers.push(element);
						window.setTimeout(function() {
							self.onScroll();
						}, 500); // delay until screen finishes populating
					}
				}
			});
			if (feedData) {
				self.renderLater();
			}
		}
		return result;
	};

	/**
	 * Called to prompt a non-urgent rendering of our elements to the display.
	 */
	AbstractPollster.prototype.renderLater = function() {
		console.log("renderLater");
		var self = this;
		if (self.renderCoalescence) {
			window.clearInterval(self.renderCoalescence);
		}
		self.renderCoalescence = setTimeout(function() {
			console.log("renderingLater");
			self.renderCoalescence = null;
			self.renderNow();
		}, 750);
	};

	/**
	 * Called when the window scrolls to potentially trigger fetch of any
	 * onscreen elements.
	 */
	AbstractPollster.prototype.renderNow = function() {
		console.log("rendering: ");
		if (concurrentFetchCount === 0) {
			$("body").removeClass("pending");
		}
		var self = this;
		// console.log(self.allEntryElements);
		self.renderCoalescence = null;

		// inlining isScrolledIntoView
		var docViewTop = $(window).scrollTop();
		var docViewBottom = docViewTop + $(window).height();
		// add some padding to bottom of view for smoother scrolling
		docViewBottom = docViewBottom + $(window).height() + $(window).height();

		var element;
		var previousElement;
		$.each(self.allEntryElements, function(index, value) {
			element = value[0];
			if (!previousElement) {
				// if the first element is offscreen
				if (!element.parentNode) {
					// make visible as the first element
					self.entryContainer.prepend(element);
					// in case this is the only entry:
					// invoke it as a scroll trigger
					self.fetchPrevious(element);
				}
			} else {
				// if the current element is not visible
				if (!element.parentNode) {
					// and the previous element is visible
					if (previousElement.parentNode) {
						// and is onscreen
						if (previousElement.offsetTop < docViewBottom) {
							// insert this element
							$(element).insertAfter(previousElement);
						}
					} else {
						// last element was offscreen
						return false; // exit
					}
				}
			}
			previousElement = element;
		});
	};

	AbstractPollster.prototype.fetchPrevious = function(elem) {
		var entryUrn = $(elem).attr("entry");
		var entryId = controller.entryIdFromEntryUrn(entryUrn);
		// get filter from element if any
		var filter = elem.filter;
		if ( !filter ) {
			filter = {};
		}
		filter = shallowCopy(filter);

		// fetch only before this entry
		filter.before = entryId;
		// don't overwhelm dom
		filter.count = 5; 

		var self = this;
		if (++concurrentFetchCount > 1) {
			$("body").addClass("pending");
		}

		/*
		 * NOTE: because not all entries are displayable we may need to keep
		 * fetching until there is something to display.
		 */
		var displayableResults;
		model.pull(filter, function(feedData) {
			/* fetch complete */
			concurrentFetchCount--;
			if (!feedData) {
				console.log("fetchPrevious: complete: not found: " + JSON.stringify(filter));
			} else {
				console.log("fetchPrevious: complete: found: " + JSON.stringify(filter));
				displayableResults = self.addEntriesFromFeed(feedData, filter);
				// no more to fetch: exit
			}
		}, function(feedData) {
			/* fetch partial */
			concurrentFetchCount--;
			if (!feedData) {
				console.log("fetchPrevious: partial: not found: " + JSON.stringify(filter));
			} else {
				console.log("fetchPrevious: partial: found: " + JSON.stringify(filter));
				displayableResults = self.addEntriesFromFeed(feedData, filter);
				if (displayableResults.length === 0) {
					// not enough to display: keep fetching
					return true;
				} else {
					// we're done: exit
					return false;
				}
			}
		});
	};

	/**
	 * Called when the window scrolls to potentially trigger fetch of any
	 * onscreen elements.
	 */
	AbstractPollster.prototype.onScroll = function() {
		var self = this;
		if (self.scrollCoalescence) {
			window.clearInterval(self.scrollCoalescence);
		}
		self.scrollCoalescence = setTimeout(function() {
			self.scrollCoalescence = null;
			console.log("onScroll");
			// reverse iterate because we're removing items
			var element;
			for (var i = self.scrollTriggers.length - 1; i >= 0; i--) {
				element = self.scrollTriggers[i];
				if (element[0].parentNode && isScrolledIntoView(element)) {
					self.scrollTriggers.splice(i, 1); // remove
					self.fetchPrevious(element);
				}
			}
			self.renderNow();
		}, 500);
	};

	AbstractPollster.prototype.start = function() {
		console.log("onStart");
	};

	AbstractPollster.prototype.stop = function() {
		console.log("onStop");
		// scan the entire list and remove ourself
		for ( var i in filterToPollsters) {
			for ( var j in filterToPollsters[i]) {
				if (filterToPollsters[i][j] === this) {
					filterToPollsters[i].splice(j, 1); // remove
					if (filterToPollsters[i].length === 0) {
						delete filterToPollsters[i];
					}
				}
			}
		}
		this.scrollTriggers = [];
		if (this.allEntryElements) {
			this.allEntryElements = [];
		}
		// TODO: need to stop listening for scroll event
	};

	/**
	 * Empties and reloads this pollster.
	 */
	AbstractPollster.prototype.reload = function() {
		console.log("reload");
		if (this.entryContainer) {
			this.entryContainer.empty();
		}
		if (this.feedContainer) {
			this.feedContainer.empty();
		}
		// FIXME: need to populate this.ids
		// FIXME: need to re-create list with this.ids
	};

	/**
	 * Shared timer.
	 */
	var timer;

	/**
	 * Shared task queue.
	 */
	var queue = [];

	/**
	 * Shared subscriber list.
	 */
	var filterToPollsters = {};

	var addSubscriberToFeed = function(pollster, filter) {
		var stringified = JSON.stringify(filter);
		var pollsters = filterToPollsters[stringified];
		if (pollsters) {
			var existing = pollsters.indexOf(pollster);
			if (existing === -1) {
				pollsters.push(pollster);
			}
		} else {
			filterToPollsters[stringified] = [ pollster ];
		}

		var task = getCachedTask(filter);
		if (task) {
			// note: for now just queue an immediate fetch
			task.noFetchBefore = 0;
			// TODO populate with most recent cached result instead
		} else {
			// create new task
			task = {
				filter : filter,
				lastUpdate : 0,
				lastFetched : 0,
				noFetchBefore : 0
			};
			setCachedTask(filter, task);
		}

		doTask(task);
	};

	// h/t http://stackoverflow.com/questions/487073
	var isScrolledIntoView = function(elem) {
		var docViewTop = $(window).scrollTop();
		var docViewBottom = docViewTop + $(window).height();
		var elemTop = $(elem).offset().top;
		// var elemBottom = elemTop + $(elem).height();
		// return ((elemBottom >= docViewTop) && (elemTop <= docViewBottom) &&
		// (elemBottom <= docViewBottom) && (elemTop >= docViewTop));
		// console.log("isScrolledIntoView: " + elemTop + " : " + docViewBottom
		// + " :: " + (elemTop <= docViewBottom));
		return (elemTop <= docViewBottom);
	};

	var concurrentFetchCount = 0;
	var doTask = function(task) {
		var pollsters = filterToPollsters[JSON.stringify(task.filter)];
		if (!pollsters || pollsters.length === 0) {
			console.log("Should never happen: task for no subscribers");
			console.log(task);
			return;
		}
		// console.log("doTask: " + task.toString());
		var filter = shallowCopy(task.filter);
		var feedId = filter.feedId;

		if (task.latestEntryId) {
			// use latest entry update time
			filter.after = task.latestEntryId;
		}

		if (task.lastFetched === 0) {
			// else first time executing task
			// fetch one and requeue
			filter.count = 1;
		}

		var self = this;
		if (++concurrentFetchCount > 1) {
			$("body").addClass("pending");
		}
		console.log("concurrentFetchCount: inc:" + concurrentFetchCount);
		console.log("Sent:     " + concurrentFetchCount + " : " + JSON.stringify(filter));
		model.pull(filter, function(feedData) {
			concurrentFetchCount--;
			console.log("concurrentFetchCount: dec:" + concurrentFetchCount);
			if (!feedData) {
				console.log("Not found: " + concurrentFetchCount + " : " + JSON.stringify(filter));
			} else {
				console.log("Received: " + concurrentFetchCount + " : " + JSON.stringify(filter));
				for ( var i in pollsters) {
					pollsters[i].addEntriesFromFeed(feedData, filter);
				}
				// grab the latest result if any
				var entries = feedData.children("entry");
				if (entries.length > 0) {
					task.latestEntryId = controller.entryIdFromEntryUrn(entries.first().children("id").text());
					task.latestResult = domToString(feedData[0]);
				}

				// requeue this task
				var now = new Date().getTime();
				var updated;
				var diff;
				if (task.latestResult) {
					// use latest entry update if we can
					updated = Date.parse($(task.latestResult).find("entry updated").first().text());
					if (!updated) {
						console.log("Error: could not parse entry date: " + Date.parse($(feedData).children("entry updated").text()));
					}
				} else {
					// fall back on feed's updated date
					updated = Date.parse(feedData.children("updated").text());
					if (!updated) {
						console.log("Error: could not parse feed date: " + feedData.children("updated").text());
					}
				}
				if (!updated) {
					console.log("       defaulting to one hour");
					diff = 60 * 60 * 1000; // default to 1 hour
					updated = now - diff;
				}
				diff = now - updated;
				if (diff < 1) {
					console.log("Error: feed was updated in the future: " + updated);
					diff = 60 * 60 * 1000; // default to 1 hour
				}

				if (task.lastFetched === 0) {
					// first time fetch:
					// fetch again asap
					task.noFetchBefore = 0;
					console.log("rescheduled: " + task.filter.feedId + " : asap");
				} else {
					// fetch on a sliding delay
					diff = Math.max(6, Math.min(diff, Math.floor(Math.pow(diff / 60000, 1 / 3) * 20000)));
					task.noFetchBefore = now + diff;
					// schedule fetch for cube root of the number of elapsed
					// minutes
					console.log("rescheduled: " + task.filter.feedId + " : " + Math.floor((now - updated) / 1000) + "s : " + Math.floor(diff / 1000 / 60) + "m " + Math.floor((diff / 1000) % 60) + "s");
				}
				task.lastUpdate = updated;
				task.lastFetched = now;
				setCachedTask(filter, task);

				insertTaskIntoSortedQueue(task);
			}
		});
	};

	var insertTaskIntoSortedQueue = function(task) {
		// console.log("insertTaskIntoSortedQueue: ");
		// console.log(task);
		// could try binary search but suspect
		// reverse linear might be faster with js array:
		var time = task.nextFetch;
		var next;
		for (var i = queue.length - 1; i >= 0; i--) {
			next = queue[i].nextFetch;
			if (next === time) {
				// check for duplicate task
				if (JSON.stringify(queue[i].filter) === JSON.stringify(task.filter)) {
					console.log("Coalescing duplicate task");
					return; // done: exit
				}
			}
			if (next < time) {
				queue.splice(i + i, 0, task);
				return; // done: exit
			}
		}
		// insert at rear of queue
		queue.splice(0, 0, task);
	};

	/**
	 * Resumes polling.
	 */
	var start = function() {
		if (!timer) {
			timer = window.setInterval(function() {
				onTick();
			}, 1000);
		}
	};

	/**
	 * Pauses polling.
	 */
	var stop = function() {
		window.clearInterval(timer);
	};

	/**
	 * Called with each tick of the timer to refetch any pending feeds on the
	 * queue.
	 */
	var onTick = function() {
		if (concurrentFetchCount < 5) {
			// console.log("onTick");
			var task;
			var time = new Date().getTime();
			for (var i = queue.length - 1; i >= 0; i--) {
				task = queue[i];
				if (task.noFetchBefore < time) {
					queue.splice(i, 1); // remove
					doTask(task);
					return; // done: exit
				}
			}
		}
	};

	var shallowCopy = function(obj) {
		var result = {};
		for ( var i in obj) {
			result[i] = obj[i];
		}
		return result;
	};

	var domToString = function(node) {
		if (node.outerHTML) {
			return node.outerHTML;
		} else {
			try {
				console.log("domToString: no outerHTML");
				var oldParent = node.parent();
				var tmpParent = $("div");
				tmpParent.append(node);
				var result = node.innerHTML;
				task.latestResult = domToString(node);
				oldParent.append(node);
				return result;
			} catch (e2) {
				console.log("domToString: no innerHTML");
				return null;
			}
		}
	};

	var setCachedTask = function(filter, task) {
		// note: localStorage would be bad due to our random port each launch
		//!!return sessionStorage.setItem(JSON.stringify(filter), JSON.stringify(task));
	};

	var getCachedTask = function(filter) {
		// session storage so our embedded webkit window shares state
		//!!return JSON.parse(sessionStorage.getItem(JSON.stringify(filter)));
	};

	/**
	 * Called by model to notify us of a local change to a feed so we can
	 * refresh our pollsters if needed.
	 */
	model.subscribe(function(feedId) {
		var copy = [];
		var priority = [];
		var task;
		var i;
		for (i in queue) {
			task = queue[i];
			// catch plain and urn:feed case 
			if (feedId.indexOf(task.filter.feedId) != -1 ) {
				// fetch asap
				task.noFetchBefore = 0;
				task.lastUpdate = new Date().getTime();
				priority.push(task);
			} else {
				copy.push(task);
			}
		}
		for (i in priority) {
			copy.push(priority[i]);
		}
		queue = copy;
	});

	/**
	 * Create a pollster to populate the specified container element with the
	 * entries from one or more feeds. Elements will be constructed using the
	 * specified factory function.
	 */
	EntryPollster = window.EntryPollster = function(entryFactoryFunction, entryContainerElement) {
		this.allEntryElements = [];
		this.entryContainer = $(entryContainerElement);
		this.entryContainer.empty();
		this.entryFactory = entryFactoryFunction;
		this.scrollTriggers = [];
		var self = this;
		$(window).scroll(function() {
			// TODO: need to unsubscribe on stop
			if (self.scrollTriggers.length > 0) {
				self.onScroll();
			}
		});
		this.start();
	};
	EntryPollster.prototype = new AbstractPollster();
	EntryPollster.prototype.constructor = EntryPollster;

	/**
	 * Create a pollster to populate the specified container element with
	 * updated feed elements. Elements will be constructed using the specified
	 * factory function and updated if the feed changes.
	 */
	FeedPollster = window.FeedPollster = function(feedFactoryFunction, feedContainerElement) {
		this.feedContainer = $(feedContainerElement);
		this.feedContainer.empty();
		this.feedFactory = feedFactoryFunction;
		this.scrollTriggers = [];
		var self = this;
		$(window).on("scroll", null, function() {
			// TODO: need to unsubscribe on stop
			if (self.scrollTriggers.length > 0) {
				self.onScroll();
			}
		});
		this.start();
	};
	FeedPollster.prototype = new AbstractPollster();
	FeedPollster.prototype.constructor = FeedPollster;

	/**
	 * Start the timer.
	 */
	start();

})(window);
