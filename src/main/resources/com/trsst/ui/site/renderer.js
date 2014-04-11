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
	 * Renderer manages a column of entries from one or more feeds with
	 * automatic page-fetch handling and sorting by date.
	 */

	var AbstractRenderer = function() {
	};

	/**
	 * Adds feed to container, replacing any existing element for this feed.
	 * Returns the added feed element.
	 */
	AbstractRenderer.prototype.addDataToFeedContainer = function(feedData) {
		var id = feedData.children("id").text();
		var updated = feedData.children("updated").text();
		// console.log("addDataToFeedContainer: " + id);
		this.feedContainer.find("[feed='" + id + "']").remove();
		var element = this.feedFactory(feedData);
		element[0].updated = updated;
		this.feedContainer.children().each(function() {
			if (this.updated && this.updated < element[0].updated) {
				element.insertBefore(this);
				return false;
			}
		});
		if (!element[0].parentNode) {
			// if no match, append to end
			this.feedContainer.append(element);
		}
	};

	/**
	 * Adds entry to container, maintaining the existing date sort, but not
	 * expecting any duplicates. Returns the inserted entry element.
	 */
	AbstractRenderer.prototype.addDataToEntryContainer = function(feedData, entryData) {
		var self = this;
		entryData = $(entryData);
		var element = self.entryFactory(feedData, entryData);
		// if entry should be visible (public or decrypted)
		if (element) {
			var currentUrn = element.attr("entry");
			// if entry is not already displayed
			if (!self.urnToEntryElement[currentUrn]) {
				// the list to iterate
				var children = this.allEntryElements;
				var currentFeedId = model.feedIdFromEntryUrn(currentUrn);
				var currentEntryId = model.entryIdFromEntryUrn(currentUrn);
				self.urnToEntryElement[currentUrn] = element;
				// console.log("addDataToEntryContainer: " + current);

				// if entry is a reply: find first parent
				var verb = entryData.find("verb").text();
				var parentUrn;
				var parentElement;
				if ("reply" === verb) {
					var term;
					entryData.find("category[scheme='urn:mention'],category[scheme='urn:com.trsst.mention']").each(function() {
						term = $(this).attr("term");
						if (term.indexOf("urn:feed:") === 0) {
							// first parent is top-most parent of thread
							parentUrn = term;
							return false; // break loop
						}
					});
					if (parentUrn) {
						parentElement = self.urnToEntryElement[parentUrn];
						var index = children.indexOf(parentElement);
						if (index !== -1) {
							// subset to just elements after this one
							children = children.slice(index + 1);
							// now the logic below will automagically
							// insert this entry in the correct spot
							// beneath the parent entry in the list.
						}
					}
				}

				var didPlaceBefore;
				$.each(children, function(index) {
					var currentElement = $(this);
					var existing = currentElement.attr("entry");
					var existingFeedId = model.feedIdFromEntryUrn(existing);
					var existingEntryId = model.entryIdFromEntryUrn(existing);

					// ignore replies unless we are a reply
					if (!parentElement || !currentElement.hasClass("verb-reply")) {
						// hex timestamps compare lexicographically
						if ((self.descendingOrder && (currentEntryId > existingEntryId)) || (!self.descendingOrder && (currentEntryId < existingEntryId))) {
							// insert before same or earlier time
							didPlaceBefore = this;
							children.splice(index, 0, element);
							console.log("Inserting element: " + element.attr("entry"));
							return false; // break loop
						}
					}
				});
				if (!didPlaceBefore) {
					// else: older than all existing entries: append
					children.push(element);
					console.log("Appending element: " + element.attr("entry"));
				}
			}
		}
		return element;
	};

	/**
	 * Monitor all feeds followed by the specified feed.
	 */
	AbstractRenderer.prototype.addFeedFollows = function(id) {
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
	AbstractRenderer.prototype.addFeed = function(id) {
		this.addEntries({
			feedId : id
		});
	};

	/**
	 * Monitor for specified feeds and entries.
	 */
	AbstractRenderer.prototype.addEntries = function(query) {
		var key = JSON.stringify(query);
		if (!this.querySet[key]) {
			console.log("addEntries: " + key);
			pollster.subscribe(query, this);
			this.querySet[key] = key; // avoid duplicates
		}
	};

	/**
	 * Called by pollster to update our contents with the specified feed.
	 */
	AbstractRenderer.prototype.notify = function(feedData, query) {
		console.log("notify: " + JSON.stringify(query));
		this.addEntriesFromFeed(feedData, query);
	};

	AbstractRenderer.prototype.addEntriesFromFeed = function(feedData, query) {
		// ignore delayed fetch responses
		if (this.disposed) {
			return;
		}

		var self = this;
		var result = [];
		if (self.feedContainer && self.feedFactory) {
			self.addDataToFeedContainer(feedData);
		}
		if (self.entryContainer && self.entryFactory) {
			var entries = feedData.find("entry");
			var total = entries.length;
			var counter = 0;
			entries.each(function(index) {
				var element = self.addDataToEntryContainer(feedData, this);
				if (element) {
					result.push(element);
					// remember how we got here
					element[0].query = query;
					// if this is the last one
					if (index === total - 1) {
						self.scrollTriggers.push(element);
						window.setTimeout(function() {
							self.onScroll();
						}, 500); // delay until screen finishes populating
						return false; // break;
					}
				}
			});
			self.renderLater();
		}
		return result;
	};

	/**
	 * Called to prompt a non-urgent rendering of our elements to the display.
	 */
	AbstractRenderer.prototype.renderLater = function() {
		// console.log("renderLater: ");
		var self = this;
		if (self.renderCoalescence) {
			window.clearInterval(self.renderCoalescence);
		} else {
			incrementPendingCount();
		}
		self.renderCoalescence = setTimeout(function() {
			// console.log("renderingLater: ");
			decrementPendingCount();
			self.renderCoalescence = null;
			self.renderNow();
		}, 750);
	};

	/**
	 * Called when the window scrolls to potentially trigger fetch of any
	 * onscreen elements.
	 */
	AbstractRenderer.prototype.renderNow = function() {
		if (this.disposed) {
			return;
		}
		if (!$(this.entryContainer).is(':visible')) {
			console.log("renderNow: not rendering because off-screen");
			return;
		}

		// console.log("rendering: ");
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

	var getPendingCount = function() {
		pollster.getPendingCount();
	};

	var incrementPendingCount = function() {
		pollster.incrementPendingCount();
	};

	var decrementPendingCount = function() {
		pollster.decrementPendingCount();
	};

	var shallowCopy = function(obj) {
		var result = {};
		for ( var i in obj) {
			result[i] = obj[i];
		}
		return result;
	};

	AbstractRenderer.prototype.fetchPrevious = function(elem) {
		elem = $(elem);
		var entryUrn = elem.attr("entry");
		var entryId = model.entryIdFromEntryUrn(entryUrn);
		// get query from element if any
		var query = elem[0].query;
		if (!query) {
			query = {};
		}
		query = shallowCopy(query);

		// fetch only before this entry
		query.before = entryId;
		// don't overwhelm dom
		query.count = 5;

		var self = this;
		incrementPendingCount();

		/*
		 * NOTE: because not all entries are displayable we may need to keep
		 * fetching until there is something to display.
		 */
		var displayableResults;
		model.pull(query, function(feedData) {
			/* fetch complete */
			decrementPendingCount();
			if (!feedData) {
				console.log("fetchPrevious: complete: not found: " + JSON.stringify(query));
			} else {
				console.log("fetchPrevious: complete: found: " + JSON.stringify(query));
				displayableResults = self.addEntriesFromFeed(feedData, query);
				// no more to fetch: exit
			}
		}, function(feedData) {
			/* fetch partial */
			if (!feedData) {
				console.log("fetchPrevious: partial: not found: " + JSON.stringify(query));
			} else {
				console.log("fetchPrevious: partial: found: " + JSON.stringify(query));
				displayableResults = self.addEntriesFromFeed(feedData, query);
				if (displayableResults.length === 0) {
					// not enough to display: keep fetching
					return true;
				} else {
					// we're done: exit
					decrementPendingCount();
					return false;
				}
			}
		});
	};

	/**
	 * Called when the window scrolls to potentially trigger fetch of any
	 * onscreen elements.
	 */
	AbstractRenderer.prototype.onScroll = function() {
		var self = this;
		if (!$(this.entryContainer).is(':visible')) {
			console.log("onScroll: not rendering because off-screen");
			return;
		}
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

	AbstractRenderer.prototype.dispose = function() {
		this.reset();
		this.disposed = true;
	};

	AbstractRenderer.prototype.reset = function() {
		console.log("reset");
		pollster.unsubscribe(this);
		this.scrollTriggers = [];
		if (this.allEntryElements) {
			this.allEntryElements = [];
		}
		if (this.urnToEntryElement) {
			this.urnToEntryElement = {};
		}
		if (this.entryContainer) {
			this.entryContainer.empty();
		}
		if (this.feedContainer) {
			this.feedContainer.empty();
		}
		if (this.querySet) {
			this.querySet = {};
		}
	};

	/**
	 * Empties and reloads this renderer.
	 */
	AbstractRenderer.prototype.reload = function() {
		console.log("reload");
		if (this.entryContainer) {
			this.entryContainer.empty();
		}
		if (this.feedContainer) {
			this.feedContainer.empty();
		}
		// FIXME: need to populate this.querySet
		// FIXME: need to re-create list with this.ids
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

	/**
	 * Create a renderer to populate the specified container element with the
	 * entries from one or more feeds. Elements will be constructed using the
	 * specified factory function.
	 */
	EntryRenderer = window.EntryRenderer = function(entryFactoryFunction, entryContainerElement) {
		this.descendingOrder = true;
		this.allEntryElements = [];
		this.urnToEntryElement = {};
		this.entryContainer = $(entryContainerElement);
		this.entryContainer.empty();
		this.entryFactory = entryFactoryFunction;
		this.scrollTriggers = [];
		this.querySet = {};
		var self = this;
		$(window).scroll(function() {
			if (self.scrollTriggers.length > 0) {
				self.onScroll();
			}
		});
	};
	EntryRenderer.prototype = new AbstractRenderer();
	EntryRenderer.prototype.constructor = EntryRenderer;

	/**
	 * Create a renderer to populate the specified container element with
	 * updated feed elements. Elements will be constructed using the specified
	 * factory function and updated if the feed changes.
	 */
	FeedRenderer = window.FeedRenderer = function(feedFactoryFunction, feedContainerElement) {
		this.descendingOrder = true;
		this.feedContainer = $(feedContainerElement);
		this.feedContainer.empty();
		this.feedFactory = feedFactoryFunction;
		this.scrollTriggers = [];
		this.querySet = {};
		var self = this;
		$(window).on("scroll", null, function() {
			if (self.scrollTriggers.length > 0) {
				self.onScroll();
			}
		});
	};
	FeedRenderer.prototype = new AbstractRenderer();
	FeedRenderer.prototype.constructor = FeedRenderer;

})(window);
