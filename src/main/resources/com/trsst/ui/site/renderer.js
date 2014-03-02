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
		this.ids = [];
	};

	/**
	 * Adds feed to container, replacing any existing element for this feed.
	 * Returns the added feed element.
	 */
	AbstractRenderer.prototype.addDataToFeedContainer = function(feedData) {
		var id = feedData.children("id").text();
		console.log("addDataToFeedContainer: " + id);
		this.feedContainer.find("[feed='" + id + "']").remove();
		this.feedContainer.append(this.feedFactory(feedData));

	};

	/**
	 * Adds entry to container, maintaining the existing date sort, but not
	 * expecting any duplicates. Returns the inserted entry element.
	 */
	AbstractRenderer.prototype.addDataToEntryContainer = function(feedData, entryData) {
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
	AbstractRenderer.prototype.addEntries = function(filter) {
		console.log("addEntries: " + filter);

		// **********//
		pollster.addSubscriberToFeed(this, filter);
	};

	/**
	 * Monitor for specified feeds and entries.
	 */
	AbstractRenderer.prototype.addFeed = function(id) {
		this.addEntries({
			feedId : id
		});
	};

	AbstractRenderer.prototype.addEntriesFromFeed = function(feedData, filter) {
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
	AbstractRenderer.prototype.renderLater = function() {
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
	AbstractRenderer.prototype.renderNow = function() {
		console.log("rendering: ");
		if (getPendingCount() === 0) {
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

	var getPendingCount = function() {
		pollster.getPendingCount();
	};

	var incrementPendingCount = function() {
		pollster.incrementPendingCount();
		if (getPendingCount() > 1) {
			$("body").addClass("pending");
		}
	};

	var decrementPendingCount = function() {
		pollster.decrementPendingCount();
		// body class "pending" removed on render
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
		var entryId = controller.entryIdFromEntryUrn(entryUrn);
		// get filter from element if any
		var filter = elem[0].filter;
		if (!filter) {
			filter = {};
		}
		filter = shallowCopy(filter);

		// fetch only before this entry
		filter.before = entryId;
		// don't overwhelm dom
		filter.count = 5;

		var self = this;
		incrementPendingCount();

		/*
		 * NOTE: because not all entries are displayable we may need to keep
		 * fetching until there is something to display.
		 */
		var displayableResults;
		model.pull(filter, function(feedData) {
			/* fetch complete */
			decrementPendingCount();
			if (!feedData) {
				console.log("fetchPrevious: complete: not found: " + JSON.stringify(filter));
			} else {
				console.log("fetchPrevious: complete: found: " + JSON.stringify(filter));
				displayableResults = self.addEntriesFromFeed(feedData, filter);
				// no more to fetch: exit
			}
		}, function(feedData) {
			/* fetch partial */
			decrementPendingCount();
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
	AbstractRenderer.prototype.onScroll = function() {
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

	AbstractRenderer.prototype.start = function() {
		console.log("onStart");
	};

	AbstractRenderer.prototype.stop = function() {
		console.log("onStop");
		pollster.removeSubscriber(this);
		this.scrollTriggers = [];
		if (this.allEntryElements) {
			this.allEntryElements = [];
		}
		// TODO: need to stop listening for scroll event
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
		// FIXME: need to populate this.ids
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
	EntryRenderer.prototype = new AbstractRenderer();
	EntryRenderer.prototype.constructor = EntryRenderer;

	/**
	 * Create a renderer to populate the specified container element with
	 * updated feed elements. Elements will be constructed using the specified
	 * factory function and updated if the feed changes.
	 */
	FeedRenderer = window.FeedRenderer = function(feedFactoryFunction, feedContainerElement) {
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
	FeedRenderer.prototype = new AbstractRenderer();
	FeedRenderer.prototype.constructor = FeedRenderer;

})(window);
