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
	 * Pollster monitors feeds and notifies subscribers of updates. We expect to
	 * see a number of different strategies for achieving near-real-time
	 * results, and those should be drop in replacements for this interface.
	 */
	var pollster = window.pollster = {};

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

	pollster.addSubscriberToFeed = function(pollster, filter) {
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

	var concurrentFetchCount = 0;

	pollster.getPendingCount = function() {
		return concurrentFetchCount;
	};

	pollster.incrementPendingCount = function() {
		concurrentFetchCount++;
	};

	pollster.decrementPendingCount = function() {
		concurrentFetchCount--;
	};

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
		pollster.incrementPendingCount();
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
		// !!return sessionStorage.setItem(JSON.stringify(filter),
		// JSON.stringify(task));
	};

	var getCachedTask = function(filter) {
		// session storage so our embedded webkit window shares state
		// !!return JSON.parse(sessionStorage.getItem(JSON.stringify(filter)));
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
			if (feedId.indexOf(task.filter.feedId) != -1) {
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
	 * Start the timer.
	 */
	start();

})(window);
