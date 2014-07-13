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
	 * Add the specified feed's entries to this renderer.
	 */
	AbstractRenderer.prototype.addFeed = function(id) {
		this.addQuery({
			feedId : id
		});
	};

	/**
	 * Add feeds followed by the specified feed, up to the optional limit.
	 */
	AbstractRenderer.prototype.addFeedFollows = function(id, limit) {
		var self = this;
		model.getFollowsForFeedId(id, function(follows) {
			var count = 0;
			for ( var follow in follows) {
				self.addFeed(follows[follow]);
				if (count++ == limit) {
					break;
				}
			}
		});
	};

	/**
	 * Add entries matching the specified query parameters.
	 */
	AbstractRenderer.prototype.addQuery = function(query) {
		var key = JSON.stringify(query);
		if (!this.querySet[key]) {
			console.log("addQuery: " + key);
			pollster.subscribe(query, this);
			this.querySet[key] = key; // avoid duplicates
		}
	};

	/**
	 * Immediately inserts the entries in the specified feed to the appropriate
	 * location in the list. Optional query will be called to back fill the list
	 * triggered by scrolling if needed.
	 */
	AbstractRenderer.prototype.addEntriesFromFeed = function(feedXml, query) {
		// ignore delayed fetch responses
		if (this.disposed) {
			return;
		}

		var self = this;
		var result = [];
		if (self.feedContainer && self.feedFactory) {
			self.addDataToFeedContainer(feedXml);
		}
		if (self.entryContainer && self.entryFactory) {
			var entries = feedXml.find("entry");
			var total = entries.length;
			entries.each(function(index) {
				var element = self.addDataToEntryContainer(feedXml, this, query);
				if (element) {
					result.push(element);
				}
			});
			// in case this batch all fits on the screen
			window.setTimeout(function() {
				// trigger scrolltriggers if any
				self.onScroll();
			}, 500); // delay until screen finishes populating
			self.renderLater();
		}
		return result;
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

	/**
	 * Adds feed to container, replacing any existing element for this feed.
	 * Returns the added feed element.
	 */
	AbstractRenderer.prototype.addDataToFeedContainer = function(feedXml) {
		var id = feedXml.children("id").text();
		var updated = feedXml.children("updated").text();
		// console.log("addDataToFeedContainer: " + id);
		this.feedContainer.find("[feed='" + id + "']").remove();
		var card = this.feedFactory(feedXml);
		var element = $(card).children(".object").children();
		card.updated = updated;
		$(card).attr("updated", updated);
		var self = this;
		self.feedContainer.children().each(function() {
			if (this.updated && this.updated < updated) {
				self.feedContainer[0].insertBefore(card, this);
				return false;
			}
		});
		if (!card.parentNode) {
			// if no match, append to end
			self.feedContainer.append(card);
		}
	};

	/**
	 * Adds entry to container, maintaining the existing date sort, but not
	 * expecting any duplicates. Returns the inserted entry element. Optional
	 * query used to set a scroll trigger on the last element with a matching
	 * query.
	 */
	AbstractRenderer.prototype.addDataToEntryContainer = function(feedXml, entryXml, query) {
		var self = this;
		var originalFeedXml = feedXml;
		var originalEntryXml = entryXml;
		entryXml = $(entryXml);
		var card = self.entryFactory(feedXml, entryXml);
		var element = $(card).children(".object").children();
		// if entry should be visible (public or decrypted)
		if (card) {
			var currentUrn = element.attr("entry");
			card.entryUrn = currentUrn;
			// if entry is not already displayed
			if (!self.urnToEntryElement[currentUrn]) {
				// the list to iterate
				var children = this.allEntryElements;
				// console.log("addDataToEntryContainer: " + current);

				// if entry is a reply: find first parent
				var startIndex = 0;
				var verb = entryXml.find("verb").text();
				var parentUrn;
				var parentElement;
				if ("reply" === verb) {
					var term;
					entryXml.find("category[scheme='urn:mention'],category[scheme='urn:com.trsst.mention']").each(function() {
						term = $(this).attr("term");
						if (term.indexOf("urn:entry:") === 0) {
							// first parent is top-most parent of thread
							parentUrn = term;
							return false; // break loop
						}
					});
					if (parentUrn) {
						parentElement = self.urnToEntryElement[parentUrn];
						if (parentElement) {
							// if we already fetched the parent element
							var index = children.indexOf(parentElement);
							if (index !== -1) {
								// subset to just elements after this one
								startIndex = index + 1;
								// now the logic below will automagically
								// insert this entry in the correct spot
								// beneath the parent entry in the list.
							}
						} else {
							// fetch the parent element, insert it,
							// and THEN insert this element
							model.pull({
								feedId : model.feedIdFromEntryUrn(parentUrn) + '/' + model.entryIdFromEntryUrn(parentUrn),
								count : 1
							}, function(feedData) {
								if (feedData && feedData.length > 0) {
									var entryXml = $(feedData).children("entry").first();
									// insert the parent into this container
									// with no query or scroll trigger
									self.addDataToEntryContainer(feedData, entryXml);
								} else {
									// not found: add a dummy reference to
									// ensure not called again
									self.urnToEntryElement[parentUrn] = originalFeedXml;
									console.log("Could not fetch conversation root entry: " + parentUrn);
								}
								// now retry with child
								self.addDataToEntryContainer(originalFeedXml, originalEntryXml, query);
							});
							return card; // EXIT and wait for fetch to
							// complete
						}
					}
				}

				// find existing scroll trigger with this query
				var existingTrigger = null;
				var existingTriggerIndex;
				if (query) {
					// set this element's query
					card.query = query;
					// look for a matching scroll trigger
					for ( var i in self.scrollTriggers) {
						// note: identity comparison
						if (self.scrollTriggers[i].query === query) {
							existingTrigger = self.scrollTriggers[i];
							existingTriggerIndex = i;
							break;
						}
					}
					// if no existing trigger
					if (existingTrigger === null) {
						// make this element the scroll trigger
						self.scrollTriggers.push(card);
					}
				}

				var didPlaceBefore;
				var currentFeedId = model.feedIdFromEntryUrn(currentUrn);
				var currentEntryId = model.entryIdFromEntryUrn(currentUrn);
				self.urnToEntryElement[currentUrn] = card;
				$.each(children, function(index) {
					if (index >= startIndex) {
						var currentElement = $(this).find(".object").children();
						var existing = currentElement.attr("entry");
						var existingFeedId = model.feedIdFromEntryUrn(existing);
						var existingEntryId = model.entryIdFromEntryUrn(existing);

						// ignore replies unless we are a reply
						if (!parentElement || !currentElement.hasClass("verb-reply")) {
							// hex timestamps compare lexicographically:
							// if we are newer than (or same time as) the
							// current entry
							if ((self.descendingOrder && (currentEntryId > existingEntryId)) || (!self.descendingOrder && (currentEntryId < existingEntryId))) {
								// insert before the current entry
								didPlaceBefore = this;
								children.splice(index, 0, card);
								console.log("Inserting element: " + element.attr("entry"));
								return false; // break loop
							}
							// otherwise: we're older than the current entry;
							// if we have a scroll trigger and it's the current
							// entry
							if (existingTrigger === this) {
								// then make us the new scroll trigger for this
								// query
								self.scrollTriggers[existingTriggerIndex] = card;
							}
						}
					}
				});
				if (!didPlaceBefore) {
					// else: older than all existing entries: append
					children.push(card);
					console.log("Appending element: " + element.attr("entry"));
				}
			}
		}
		self.renderLater();
		return card;
	};

	/**
	 * Called by pollster to update our contents with the specified feed.
	 */
	AbstractRenderer.prototype.notify = function(feedXml, query) {
		console.log("notify: " + JSON.stringify(query));
		this.addEntriesFromFeed(feedXml, query);
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
		if (!$(this.entryContainer).is(':visible') && this.entryContainer.height() > 0) {
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
			element = value;
			if (!previousElement) {
				// if the first element is offscreen
				if (!element.parentNode) {
					// make visible as the first element
					self.entryContainer.prepend(element);
					// in case this is the only entry:
					// invoke it as a scroll trigger
					// !!!!! self.fetchPrevious(element);
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
		var query = elem.query;
		elem = $(elem);
		var entryUrn = elem.find(".object").children().attr("entry");
		var entryId = model.entryIdFromEntryUrn(entryUrn);
		// get query from element if any
		if (query) {
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
			model.pull(query, function(feedXml) {
				/* fetch complete */
				decrementPendingCount();
				if (!feedXml) {
					console.log("fetchPrevious: complete: not found: " + JSON.stringify(query));
				} else {
					console.log("fetchPrevious: complete: found: " + JSON.stringify(query));
					displayableResults = self.addEntriesFromFeed(feedXml, query);
					// no more to fetch: exit
				}
			}, function(feedXml) {
				/* fetch partial */
				if (!feedXml) {
					console.log("fetchPrevious: partial: not found: " + JSON.stringify(query));
				} else {
					console.log("fetchPrevious: partial: found: " + JSON.stringify(query));
					displayableResults = self.addEntriesFromFeed(feedXml, query);
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
		}
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
				if (element.parentNode && isScrolledIntoView(element)) {
					self.scrollTriggers.splice(i, 1); // remove
					self.fetchPrevious(element);
				}
			}
			self.renderNow();
		}, 500);
	};

	// h/t http://stackoverflow.com/questions/487073
	var isScrolledIntoView = function(elem) {
		var docViewTop = $(window).scrollTop();
		var docViewBottom = docViewTop + $(window).height();
		var elemTop = $(elem).offset().top;
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
