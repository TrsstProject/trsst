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

	var controller = window.controller = {};
	var entryTemplate = document.getElementById('entryTemplate');
	$(entryTemplate).remove();
	var feedTemplate = document.getElementById('feedTemplate');
	$(feedTemplate).remove();
	var feedEditTemplate = document.getElementById('feedEditTemplate');
	$(feedEditTemplate).remove();
	var passwordVerifyTemplate = document.getElementById('passwordVerifyTemplate');
	$(passwordVerifyTemplate).remove();
	var passwordCreateTemplate = document.getElementById('passwordCreateTemplate');
	$(passwordCreateTemplate).remove();

	var createElementForFeedData = function(feedData) {

		// clone feed template
		var feedElement = $(feedTemplate).clone();
		var feedId = feedData.children("id").text();
		if (feedId.indexOf("urn:feed:http") > -1) {
			feedElement.addClass("external");
		}

		feedElement.attr("feed", feedId);
		feedElement.removeAttr("id");
		feedElement.find(".title span").text(feedData.children("title").text());
		if (feedData.children("title").text().length === 0) {
			feedElement.find(".title").addClass("empty-text"); // hint for css
		}
		feedElement.find(".subtitle span").text(feedData.children("subtitle").text());
		if (feedData.children("subtitle").text().length === 0) {
			feedElement.find(".subtitle").addClass("empty-text"); // hint for
			// css
		}
		feedElement.find(".feed-id span").text(feedData.children("id").text().substring("urn:feed:".length));
		feedElement.find(".author-name span").text(feedData.children("author>name").text());
		feedElement.find(".author-email span").text(feedData.children("author>email").text());
		feedElement.find(".author-uri span").text(feedData.children("author>uri").text());
		feedElement.find(".author-uri a").attr("href", feedData.children("author>uri").text());

		// mark if ours
		var currentAccountId = model.getAuthenticatedAccountId();
		if (currentAccountId && currentAccountId.indexOf(feedId) !== -1) {
			feedElement.addClass("own");
		}

		// logo (backdrop)
		var logoSrc = feedData.find("logo").text();
		if (logoSrc) {
			feedElement.find(".logo img").attr("src", iconSrc);
		}

		// icon (profile pic)
		var iconSrc = computeMakeshiftIcon(feedData);
		if (iconSrc) {
			feedElement.find(".icon").last().css("background-image", "url('" + iconSrc + "')");
			// some styles may choose to hide the foreground img
			// $(feedElement).find(".icon img").attr("src", iconSrc);
		}

		// mark with signed status
		if (feedData.children().find("Signature")) {
			// TODO: model needs to validate signature for us
			// TODO: for now mark as signed to test the ui
			feedElement.addClass("content-signed");
		}

		// handling for follow button
		var followElement = feedElement.find(".follow");
		var followButton = followElement.find("button");
		updateFollowElementForFeedId(followElement, feedId);
		followButton.attr("disabled", false);
		followButton.click(function(e) {
			followButton.attr("disabled", true);
			if (followElement.hasClass("following")) {
				model.unfollowFeed(feedId);
			} else {
				model.followFeed(feedId);
			}
		});

		// enable clickable anchors
		feedElement.find("a").click(function(event) {
			var url = $(event.target).closest("a").attr("src");
			if (!url) {
				// if not specified open as internal link
				event.preventDefault();
				url = $(event.target).closest('.feed').attr("feed");
				if (url.indexOf("urn:feed:") === 0) {
					url = url.substring("urn:feed:".length);
					// escape parameterized urns
					if (url.indexOf("?") !== -1) {
						url = encodeURIComponent(url);
					}
				}
				if (url) {
					controller.pushState("/" + url);
				} else {
					console.log("Feed click: could not determine destination");
					console.log(event);
				}
			}
		});

		return feedElement;
	};

	var updateFollowElementForFeedId = function(followElement, feedId) {
		if (model.isAuthenticatedFollowing(feedId)) {
			$(followElement).addClass("following");
		} else {
			$(followElement).removeClass("following");
		}
	};

	var computeMakeshiftIcon = function(feedData) {
		var feedId = feedData.children("id").text();
		var iconSrc = feedData.children("icon").last().text();
		if (iconSrc) {
			iconSrc = model.resolveUrl(iconSrc, feedData.children("icon").last());
		}
		if (!iconSrc && feedId.indexOf("urn:feed:http") === 0) {
			// no icon specified: try an apple touch icon
			var i = feedId.indexOf("//");
			var host = feedId.substring(i + 2);
			i = host.indexOf("/");
			if (i !== -1) {
				host = host.substring(0, i);
			}
			// strip only first host name
			i = host.indexOf(".");
			if (host.substring(i + 1).indexOf(".") != -1) {
				host = host.substring(i + 1);
			}
			iconSrc = 'http://' + host + "/apple-touch-icon.png";
		}
		return iconSrc;
	};

	var createElementForEntryData = function(feedData, entryData) {
		feedData = $(feedData);
		entryData = $(entryData);

		// if encrypted content
		var contentEncryption;
		if ("application/xenc+xml" === entryData.find("content").first().attr("type")) {
			// grab the unencrypted payload (if any)
			var entryPayload = entryData.find("content entry");
			if (entryPayload.length > 0) {
				// replace envelope with payload
				entryData = entryPayload.first();
				contentEncryption = "content-decrypted";
			} else {
				// treat this as an "encrypted" verb
				contentEncryption = "content-encrypted";
				// return null; //NOTE: entry skipped
				// FIXME: returning null is preventing incremental loading
			}
		}

		// clone entry template
		var entryElement = $(entryTemplate).clone();
		var entryId = entryData.find("id").text();
		var feedId = model.feedIdFromEntryUrn(entryId);
		if (entryId.indexOf("urn:entry:http") > -1) {
			entryElement.addClass("external");
		}
		entryElement.attr("entry", entryId);
		entryElement.removeAttr("id");

		// mark if ours
		var currentAccountId = model.getAuthenticatedAccountId();
		if (currentAccountId && currentAccountId.indexOf(feedId) !== -1) {
			entryElement.addClass("own");
		}

		// mark with encryption status
		if (contentEncryption) {
			entryElement.addClass(contentEncryption);
		}

		// mark with verb
		var verb = entryData.find("verb").text();
		if (verb) {
			entryElement.addClass("verb-" + verb);
			if (verb === "deleted") {
				// return null; //NOTE: entry skipped
				// FIXME: returning null is preventing incremental loading
			}
		}

		// mark with signed status
		if (entryData.children().find("Signature")) {
			// TODO: model needs to validate signature for us
			// TODO: for now mark as signed to test the ui
			entryElement.addClass("content-signed");
		}

		// populate template
		var elementUI;
		var elementData;

		// populate feed data
		if (feedId === model.feedIdFromFeedUrn(feedData.children("id").text())) {
			// use existing feed data
			populateEntryElementWithFeedData(entryElement, feedData);
		} else {
			// otherwise aggregate feed: fetch feed data
			model.getFeed(feedId, function(fetchedFeedData) {
				if (fetchedFeedData) {
					populateEntryElementWithFeedData(entryElement, fetchedFeedData);
				} else {
					console.log("Could not fetch feed data: " + feedId);
					// fall back to aggregate feed data
					populateEntryElementWithFeedData(entryElement, feedData);
				}
			});
		}

		var titleConverted = entryData.find("title").text();
		titleConverted = convertToHtml(entryData, titleConverted);
		entryElement.find(".title span").html(titleConverted);

		// summary: sandboxed iframe
		var summary = entryData.find("summary").text();
		if (summary && summary.trim().length > 0) {
			entryElement.addClass("summary").addClass("collapsed");
			entryElement.find(".summary iframe").attr('tmpdoc', inlineStyle + summary);
		}

		// updated
		var dateString = entryData.find("updated").text();
		entryElement.find(".updated .raw span").text(dateString);
		try {
			var date = new Date(dateString);
			var seconds = date.getSeconds().toString();
			if (seconds.length < 2) {
				seconds = "0" + seconds;
			}
			var minutes = date.getMinutes().toString();
			if (minutes.length < 2) {
				minutes = "0" + minutes;
			}
			entryElement.find(".updated .absolute .second span").text(seconds);
			entryElement.find(".updated .absolute .second").attr("value", seconds);
			entryElement.find(".updated .absolute .minute span").text(minutes);
			entryElement.find(".updated .absolute .minute").attr("value", minutes);
			entryElement.find(".updated .absolute .hour span").text(date.getHours());
			entryElement.find(".updated .absolute .hour").attr("value", date.getHours());
			entryElement.find(".updated .absolute .day span").text(date.getDate());
			entryElement.find(".updated .absolute .day").attr("value", date.getDate());
			entryElement.find(".updated .absolute .month span").text(date.getMonth() + 1); // 0-based
			entryElement.find(".updated .absolute .month").attr("value", date.getMonth() + 1); // 0-based
			entryElement.find(".updated .absolute .year span").text(date.getFullYear());
			entryElement.find(".updated .absolute .year").attr("value", date.getFullYear());
		} catch (e) {
			console.log("Invalid date format: " + dateString);
			entryElement.find(".updated .absolute").remove();
		}
		updateRelativeTimestamp(entryElement);

		// content
		entryElement.find(".content").each(function() {
			var viewElement = this;

			// some feeds put same link in both content and enclosure link
			var duplicateDetector = {};

			var content = entryData.find("content");
			if (content.attr("type") !== undefined) {
				entryElement.addClass("contented").addClass("collapsed");
			}
			addContentPreviewToElement(entryData, content, viewElement, duplicateDetector);

			// add any 'enclosure' links
			entryData.find("link[rel='enclosure']").each(function() {
				var content = entryData.find("content");
				if (content.attr("type") !== undefined) {
					entryElement.addClass("contented").addClass("collapsed");
				}
				addContentPreviewToElement(entryData, $(this), viewElement, duplicateDetector);
			});

			// add any 'alternate' links last
			entryData.find("link[rel='alternate']").each(function() {
				var content = entryData.find("content");
				if (content.attr("type") !== undefined) {
					entryElement.addClass("contented").addClass("collapsed");
				}
				addContentPreviewToElement(entryData, $(this), viewElement, duplicateDetector);
			});

		});

		// add any (internal) feed tags/mentions last
		var address, e;
		entryData.find("category[scheme='urn:com.trsst.tag']").each(function() {
			address = $(this).attr("term");
				if (address.indexOf("http") === -1) {
					e = $("<a class='address tag'><span></span></a>");
					e.attr("href", '/?tag=' + address);
					e.attr("title", address);
					e.children("span").text('#' + address);
					$(entryElement).find(".addresses").append(e);
				}
		});
		entryData.find("category[scheme='urn:com.trsst.mention']").each(function() {
			address = $(this).attr("term");
			if (address && address.indexOf("urn:feed:") === 0) {
				address = address.substring("urn:feed:".length);
				if (address.indexOf("http") === -1) {
					e = $("<a class='address mention'><span></span></a>");
					e.attr("href", '/' + address);
					e.attr("title", address);
					e.children("span").text('@' + address);
					$(entryElement).find(".addresses").append(e);
				}
			}
		});

		// create composer
		new Composer(entryElement.find("form"));

		// catch all clicks on the entryElement
		entryElement.click(onEntryClick);

		return entryElement;
	};

	var populateEntryElementWithFeedData = function(entryElement, feedData) {
		// icon (profile pic)
		var iconSrc = computeMakeshiftIcon(feedData);
		if (iconSrc) {
			// if icon is specified
			entryElement.find(".icon").last().css("background-image", "url('" + iconSrc + "')");
			// some styles may choose to hide the foreground
			// img
			// entryElement.find(".icon
			// img").attr("src", iconSrc);
		}

		entryElement.find(".feed-title span").text(feedData.children("title").text());
		if (feedData.children("title").text().length === 0) {
			// hint for css layout
			entryElement.find(".feed-title").addClass("empty-text");
		}
		entryElement.find(".feed-id span").text(feedData.children("id").text().substring("urn:feed:".length));

	};

	var mentionsExp = /([\@]\w+)/g;
	var hashtagsExp = /([\#]\w+)/g;
	var gruberUrl = /\b((?:https?:\/\/|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}\/)(?:[^\s()<>]+|\(([^\s()<>]+|(\([^\s()<>]+\)))*\))+(?:\(([^\s()<>]+|(\([^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'".,<>?«»“”‘’]))/i;
	var inlineStyle = '<base target="_blank"/><style>* { font-family: sans-serif; font-size: 13px !important; } img { display: block; width: 100%; float:left; height: auto; margin-bottom: 30px; }</style>';

	/** Used to highlight links in urls and respect linebreaks. */
	var convertToHtml = function(entryData, text) {
		if (text.indexOf("htt") !== -1) {
			// actually is a bit faster to prequalify before applying regex
			text = text.replace(gruberUrl, '<a target="_blank" href="$1">$1</a>');
		}

		var i;
		var match;
		var matches;

		// link hashtags
		matches = text.match(hashtagsExp);
		if (matches) {
			for (i in matches) {
				match = matches[i].substring(1); // remove #
				text = text.replace(new RegExp(matches[i], 'g'), '<a href="/?tag=' + match + '">' + matches[i] + '</a>');
			}
		}

		// link mentions
		var id;
		matches = text.match(mentionsExp);
		if (matches) {
			for (i in matches) {
				match = matches[i].substring(1); // remove @
				entryData.find("category[scheme='urn:com.trsst.mention']").each(function() {
					id = $(this).attr("term");
					if (id && id.indexOf("urn:feed:" + match) === 0) {
						id = id.substring("urn:feed:".length);
						text = text.replace(new RegExp(matches[i], 'g'), '<a href="/' + id + '">' + matches[i] + '</a>');
					}
				});
			}
		}

		return text;
	};

	var addContentPreviewToElement = function(entryXml, contentElement, viewElement, duplicateDetector) {
		// handle both content elements and link enclosures
		var src = contentElement.attr("src");
		if (!src) {
			src = contentElement.attr("href");
		}
		src = model.resolveUrl(src, contentElement);

		if (!duplicateDetector[src]) {
			duplicateDetector[src] = src;
			var type = contentElement.attr("type");
			var verb = entryXml.find("verb").text();
			var e;
			if (type !== undefined) {
				if (type === "text") {
					// pass through
				} else if (type.indexOf("video/") === 0) {
					e = $("<video controls preload='none'><source></audio>");
					e.find("source").attr("src", src);
					e.find("source").attr("type", type);
					$(viewElement).append(e);
				} else if (type.indexOf("audio/") === 0) {
					e = $("<audio controls preload='none'><source></audio>");
					e.find("source").attr("src", src);
					e.find("source").attr("type", type);
					$(viewElement).append(e);
				} else if (type.indexOf("image/") === 0) {
					e = $("<img width='100%'>");
					e.attr("src", src);
					$(viewElement).append(e);
				} else if (type.indexOf("application/atom") === 0) {
					var index;
					if ((index = src.indexOf("urn:feed:")) !== -1) {
						src = src.substring(index);
						// following a feed
						model.pull({
							feedId : src,
							count : 0
						}, function(feedData) {
							if (feedData && feedData.length > 0) {
								// following entry appears above followed feed
								createElementForFeedData(feedData).appendTo($(viewElement).closest(".entry"));
							} else {
								console.log("Could not fetch followed feed: " + src);
							}
						});
					} else if ((index = src.indexOf("urn:entry:")) !== -1) {
						src = src.substring(index);
						// reposting an entry
						model.pull({
							feedId : src,
							count : 1
						}, function(feedData) {
							if (feedData && feedData.length > 0) {
								// reposting entry appears above reposted entry
								var entryData = $(feedData).children("entry").first();
								createElementForEntryData(feedData, entryData).appendTo($(viewElement).closest(".entry"));
							} else {
								console.log("Could not fetch reposted entry: " + src);
							}
						});
					} else {
						console.log("Unsupported atom link: " + src);
					}
				} else if (src !== undefined) {
					e = $("<a class='link' target='_blank'><span></span></a>");
					e.attr("href", src);
					e.attr("title", src);
					e.children("span").text(src);
					$(viewElement).append(e);
				} else if (contentElement.text().trim().length > 0) {
					e = $("<div class='overlay'><iframe scrolling='no' seamless='seamless' sandbox='' frameBorder='0'></iframe></div>");
					e.find("iframe").attr("tmpdoc", inlineStyle + contentElement.text());
					// tmpdoc becomes srcdoc when expanded
					$(viewElement).append(e);
				} else if (type) {
					console.log("Unrecognized content type:" + type);
					// console.log(this);
				}
//TODO: rethink conversation threading 				
//			} else if (verb === "reply") {
//				// get last entry mention:
//				// this is the nearest parent in a tree of comments
//				var ref;
//				var term;
//				entryXml.find("category[scheme='urn:com.trsst.mention']").each(function() {
//					term = $(this).attr("term");
//					if ( term && term.indexOf("urn:entry") === 0 ) {
//						ref = term;
//					}
//				});
//				if (ref) {
//					model.pull({
//						feedId : model.feedIdFromEntryUrn(ref) + '/' + model.entryIdFromEntryUrn(ref),
//						count : 1
//					}, function(feedData) {
//						if (feedData && feedData.length > 0) {
//							// replying entry appears under mention entry
//							var entryData = $(feedData).children("entry").first();
//							createElementForEntryData(feedData, entryData).prependTo($(viewElement).closest(".entry"));
//						} else {
//							console.log("Could not fetch referenced entry: " + ref);
//						}
//					});
//				} else {
//					console.log("Unexpected mention type for reply: " + ref);
//				}
			} else if (type) {
				console.log("Unrecognized content type:" + type);
				// console.log(this);
			}
		}
	};

	var onEntryClick = function(event) {
		var entryElement = $(event.target).closest('.entry');

		// if target was an action
		if ($(event.target).closest('.action').length !== 0) {
			var entryId;
			if ($(event.target).parents('.repost').length !== 0) {
				entryId = entryElement.attr("entry");
				if (!entryElement.hasClass("reposted")) {
					entryElement.addClass("reposted");
					model.repostEntry(entryElement.attr("entry"));
				}
				return;
			}
			if ($(event.target).parents(".delete").length !== 0) {
				entryId = entryElement.attr("entry");
				if (!entryElement.hasClass("deleted")) {
					entryElement.addClass("deleted");
					model.deleteEntry(entryElement.attr("entry"));
				}
				return;
			}
			if ($(event.target).parents(".like").length !== 0) {
				entryId = entryElement.attr("entry");
				if (entryElement.hasClass("liked")) {
					entryElement.removeClass("liked");
					model.unlikeEntry(entryId);
				} else {
					entryElement.addClass("liked");
					model.likeEntry(entryElement.attr("entry"));
				}
				return;
			}
			if ($(event.target).parents(".comment").length !== 0) {
				if (entryElement.hasClass("commented")) {
					entryElement.removeClass("commented");
					// (don't set focus)
				} else {
					entryElement.addClass("commented");
					entryElement.find("textarea").focus();
				}
				return;
			}
			// otherwise, fall through: toggle expand
			event.target = $(this);
		}

		// if target was an iframe's overlay
		if ($(event.target).hasClass('overlay')) {
			// $(event.target).parents('.content').find('a[href]').first().each(function()
			// {
			// // launch link if any
			// var href = this.getAttribute('href');
			// if (href) {
			// window.open(href.toString(), "_blank");
			// } else {
			// console.log("Overlay could not find href:");
			// console.log(a);
			// }
			// });
			// return;
			// note: now just falling through to expand/collapse
		}

		var url;

		// if target was in the date/time updated section
		if ($(event.target).parents('.updated').length !== 0) {
			url = $(event.target).parents('.entry').first().attr("entry");
			if (url.indexOf("urn:entry:") === 0) {
				url = url.substring("urn:entry:".length);
				var colon = url.lastIndexOf(":");
				if (colon !== -1) {
					// convert to path
					// escape parameterized urns
					if (url.indexOf("?") !== -1) {
						url = encodeURIComponent(url.substring(0, colon)) + "/" + url.substring(colon + 1);
					} else {
						url = url.substring(0, colon) + "/" + url.substring(colon + 1);
					}
				}
				// go there
				event.preventDefault();
				controller.pushState("/" + url);
			}
			return;
		}

		// if target was in the content section
		if ($(event.target).parents('.content .feed').length !== 0) {
			url = $(event.target).parents('.content .feed').attr("feed");
			if (url.indexOf("urn:feed:") === 0) {
				url = url.substring("urn:feed:".length);
				// escape parameterized urns
				if (url.indexOf("?") !== -1) {
					url = encodeURIComponent(url);
				}
				// go there
				event.preventDefault();
				controller.pushState("/" + url);
			}
			return;
		}

		// if target was in the content section
		if ($(event.target).parents('.content').length !== 0) {
			// handle normally
			return;
		}

		// if target was in the input section
		if ($(event.target).parents('.input').length !== 0) {
			// handle normally
			return;
		}

		// if target was in a form section
		if ($(event.target).parents('form').length !== 0) {
			// handle normally
			return;
		}

		// if target was the profile pic container
		if ($(event.target).hasClass('icon')) {
			event.target = $(event.target).find("img")[0];
			// reset target to img and continue as internal anchor
		}

		// if target was an internal anchor
		var anchor = $(event.target).closest('a');
		if (anchor.length !== 0) {
			// trigger it
			var href = anchor.attr("href");
			if (href && href.indexOf("http") === 0) {
				// open external urls in new window
				window.open(href, "_blank");
			} else if (href && href.indexOf("/") === 0) {
				// go there
				event.preventDefault();
				controller.pushState(href);
			} else {
				// open relative urls in same page push state
				href = anchor.closest(".entry").attr("entry");
				if (href) {
					href = model.feedIdFromEntryUrn(href);
					// escape parameterized urns
					if (href.indexOf("?") !== -1) {
						href = encodeURIComponent(href);
					}
					// go there
					event.preventDefault();
					controller.pushState("/" + href);
				} else {
					console.log("Unrecognized anchor:");
					console.log(a);
				}
			}
			return;
		}

		// if collapsable/expandable then toggle
		if ($(this).hasClass("collapsed")) {
			$(this).removeClass("collapsed").addClass("expanded");
			// load iframed content when expanded
			$(this).find("iframe").each(function() {
				var tmpdoc = $(this).attr("tmpdoc");
				if (tmpdoc) {
					$(this).removeAttr("tmpdoc");
					$(this).attr("srcdoc", tmpdoc);
				}
			});
		} else if ($(this).hasClass("expanded")) {
			$(this).removeClass("expanded").addClass("collapsed");
		} else {
			// do nothing (for now)
		}
	};

	var getCurrentAccountId = function() {
		var id = localStorage.getItem("currentAccountId");
		// if (!id) {
		// NOTE: for now avoid local stored id
		id = model.getAuthenticatedAccountId();
		// }
		return id;
	};

	var setCurrentAccountId = function(feedId) {
		// update the account menu
		$(".accounts .feed").removeClass("selected-account");

		if (feedId) {
			// our id is actually a urn
			if (feedId.indexOf(model.getAuthenticatedAccountId()) === -1) {
				// prompt to authenticate account
				controller.showPopup($(passwordVerifyTemplate).clone(), function() {
					// cancelled: clear current account
					setCurrentAccountId(null);
				}, function(form) {
					// entered password: now wait for validation or denial
					model.signIn(feedId, form.find('#password-verify').val(), function(feedData) {
						if (feedData) {
							// valid: manually dismiss popup
							controller.hidePopup();
							onSignIn(feedId);
							window.localStorage.setItem("currentAccountId", feedId);

						} else {
							// invalid: set error flags and keep open for retry
							form.addClass("invalid").addClass("invalid-password");
						}
					});
					return true; // stay open until authenticated
				});
			} else {
				onSignIn(feedId);
			}
		} else {
			model.signOut();
			window.localStorage.removeItem("currentAccountId");
			$(document.body).addClass("signed-out");
			$(document.body).removeClass("signed-in");
			onPopulate();
		}
	};

	/** Update the UI to show we're logged in as the specified user id. */
	var onSignIn = function(feedId) {
		$(".accounts .feed[feed='" + feedId + "']").addClass("selected-account");
		$(document.body).removeClass("signed-out");
		$(document.body).addClass("signed-in");

		// force update of all already-loaded follow buttons
		$(".feed section .follow").each(function() {
			var followedId = $(this).parents(".feed").attr("feed");
			updateFollowElementForFeedId(this, followedId);
		});

		// force refresh of feed page if we're on it
		feedRenderer.path = null;

		// refresh page
		onPopulate();
	};

	var onAccountMenuClick = function(event) {
		if ($(document.body).hasClass("menu-showing")) {
			$(document.body).removeClass("menu-showing");
			// if target was a feed
			if ($(event.target).closest(".accounts").length !== 0) {
				setCurrentAccountId($(event.target).closest(".feed").attr("feed"));
				return;
			}
			// if target was logout
			if ($(event.target).closest(".logout").length !== 0) {
				setCurrentAccountId(null);
				return;
			}
			// if target was new
			if ($(event.target).closest(".create").length !== 0) {
				onCreateAccount();
				return;
			}
			// if target was edit
			if ($(event.target).closest(".edit").length !== 0) {
				onEditAccount();
				return;
			}
		} else {
			// otherwise just show the menu
			$(document.body).addClass("menu-showing");
		}
	};

	var onCreateAccount = function() {
		controller.showPopup($(passwordCreateTemplate).clone(), function() {
			// do nothing
		}, function(popup) {
			$(popup).removeClass("invalid-password-match");
			$(popup).removeClass("invalid-password-length");
			var create = $(popup).find('#password-create').val();
			var repeat = $(popup).find('#password-repeat').val();
			if (create.valueOf() !== repeat.valueOf()) {
				$(popup).addClass("error").addClass("invalid-password-match");
				return true;
			}
			if (create.length < 12) {
				$(popup).addClass("error").addClass("invalid-password-length");
				return true;
			}
			$(popup).addClass("authenticating");
			model.authenticateNewAccount(create, function(feedData) {
				if (feedData) {
					controller.hidePopup();
					console.log(feedData);
					$(createElementForFeedData(feedData)).addClass("menu-item").appendTo($("nav .accounts"));
					setCurrentAccountId($(feedData).find("id").text());
					onEditAccount(); // confusing?
				} else {
					$(popup).addClass("error");
				}
			});
		});
	};

	var onEditAccount = function() {
		// pull latest feed data
		var id = model.getAuthenticatedAccountId();
		if (id.indexOf("urn:feed:") === 0) {
			id = id.substring("urn:feed:".length);
		}
		model.pull({
			feedId : id,
			count : 0
		}, function(feedData) {
			var form = $(feedEditTemplate).clone();
			var iconSrc = feedData.children("icon").text();
			iconSrc = model.resolveUrl(iconSrc, feedData.children("icon"));
			if (iconSrc) {
				form.find(".icon").css("background-image", "url('" + iconSrc + "')");
				// some styles may choose to hide the foreground img
				// form.find(".icon img").attr("src", iconSrc);
			}
			// strip id from base
			var base = feedData.attr("xml:base");
			if (base) {
				var index = base.indexOf(id);
				if (index > 1) {
					base = base.substring(0, index - 1);
				}
			} 
			form.find(".title input").val(feedData.children("title").text());
			form.find(".subtitle textarea").val(feedData.children("subtitle").text());
			form.find(".base input").val(base);
			form.find(".icon img").click(function(e) {
				// trigger the hidden file field
				form.find(".icon input").focus().trigger('click');
			});
			controller.showPopup(form, function() {
				// do nothing
				console.log("CANCELLED");
			}, function(popup) {
				model.updateFeed(new FormData(form[0]), function(feedData) {
					if (feedData) {
						controller.hidePopup();
						console.log(feedData);
						// replace with updated data
						$("nav .accounts .selected-account").remove();
						$(createElementForFeedData(feedData)).addClass("menu-item").appendTo($("nav .accounts"));
						setCurrentAccountId($(feedData).find("id").text());
					} else {
						$(popup).addClass("error");
					}
				});
			});
		});
	};

	var updateRelativeTimestamp = function(entryElement) {
		var dateString = entryElement.find(".updated .raw span").text();
		try {
			var granularity = "seconds";
			var diff = Math.floor((new Date().getTime() - Date.parse(dateString)) / 1000);
			var value = diff % 60;
			diff = Math.floor(diff / 60);
			if (diff > 0) {
				granularity = "minutes";
			}
			entryElement.find(".updated .relative .seconds span").text(value);
			entryElement.find(".updated .relative .seconds").attr("value", value);

			value = diff % 60;
			diff = Math.floor(diff / 60);
			if (diff > 0) {
				granularity = "hours";
			}
			entryElement.find(".updated .relative .minutes span").text(value);
			entryElement.find(".updated .relative .minutes").attr("value", value);

			value = diff % 24;
			diff = Math.floor(diff / 60);
			if (diff > 0) {
				granularity = "days";
			}
			entryElement.find(".updated .relative .hours span").text(value);
			entryElement.find(".updated .relative .hours").attr("value", value);

			value = diff % 30;
			diff = Math.floor(diff / 24);
			if (diff > 0) {
				granularity = "months";
			}
			entryElement.find(".updated .relative .days span").text(value);
			entryElement.find(".updated .relative .days").attr("value", value);

			value = diff % 12;
			diff = Math.floor(diff / 30);
			if (diff > 0) {
				granularity = "years";
			}
			entryElement.find(".updated .relative .months span").text(value);
			entryElement.find(".updated .relative .months").attr("value", value);

			value = diff % 12;
			diff = Math.floor(diff / 12);
			entryElement.find(".updated .relative .years span").text(value);
			entryElement.find(".updated .relative .years").attr("value", value);

			// use this to determine what time unit to show
			entryElement.find(".updated").attr('class', 'updated ' + granularity);
		} catch (e) {
			console.log("updateRelativeTimestamp: Invalid date format: " + dateString);
			console.log(e);
			entryElement.find(".updated .relative").remove();
		}
	};

	var popupContainer = $(document.body).find("#popupContainer");
	var popupBackdrop = $(document.body).find("#popupBackdrop");
	popupBackdrop.click(function(e) {
		if (e.target === popupBackdrop.get(0)) {
			controller.hidePopup();
		}
	});

	/**
	 * Places the specified element in a modal popup, calling onCancel when the
	 * popup is dismissed. If onConfirm is specified, the popup shows "OK" and
	 * "Cancel" and calls either onConfirm or onCancel respectively. If onCancel
	 * or onConfirm return true, the popup is not dismissed.
	 */
	controller.showPopup = function(element, onCancel, onConfirm) {
		controller.hidePopup();
		popupContainer.append(element);
		if (onConfirm) {
			popupContainer.addClass("confirmable");
			var confirmHandler = function() {
				if (!onConfirm(popupContainer)) {
					controller.hidePopup();
				}
			};
			$("<button class='confirm'><span></span></button>").appendTo(popupContainer).click(confirmHandler);
			$(element).find("input").keyup(function(e) {
				// trigger on enter key
				var code = (e.keyCode ? e.keyCode : e.which);
				if (code == 13) {
					confirmHandler();
				}
			});
		}
		if (onCancel) {
			var cancelHandler = function() {
				if (!onCancel(popupContainer)) {
					controller.hidePopup();
				}
			};
			$("<button class='cancel'><span></span></button>").appendTo(popupContainer).click(cancelHandler);
			$(element).keyup(function(e) {
				// trigger on escape key
				var code = (e.keyCode ? e.keyCode : e.which);
				if (code == 27) {
					cancelHandler();
				}
			});
		}
		$(document.body).addClass("popup-showing");
		$(element).find("input").get(0).focus();

		return popupContainer;
	};

	controller.hidePopup = function() {
		popupContainer.removeClass("confirmable");
		$(document.body).removeClass("popup-showing");
		popupContainer.empty();
	};

	var populateAccountMenu = function() {
		/* Get all local accounts and display them. */
		model.getAccounts(function(result) {
			var accounts = $("nav .accounts");
			accounts.empty();
			var accountId = getCurrentAccountId();
			if (result.length === 0 || accountId === null || result.indexOf(accountId) === -1) {
				// no matching accounts: update the UI
				// setCurrentAccountId(null);
			}

			// need to pull each account to populate the UI
			for ( var id in result) {
				// fetch feed
				var currentId = result[id];
				model.pull({
					feedId : currentId,
					count : 0
				}, createFeedMenuItem);
			}
		});

	};

	var createFeedMenuItem = function(feedData) {
		if (feedData) {
			$(createElementForFeedData(feedData)).addClass("menu-item").appendTo($("nav .accounts"));
			if (getCurrentAccountId() === feedData.children("id").text()) {
				// update the UI
				setCurrentAccountId(getCurrentAccountId());
			}
		} else {
			console.log("Could not read account data for feed");
		}
	};

	// h/t Carlo-Zottmann http://stackoverflow.com/questions/6539761
	var searchToObject = function() {
		var pairs = window.location.search.substring(1).split("&"), obj = {}, pair, i;
		for (i in pairs) {
			if (pairs[i] === "")
				continue;
			pair = pairs[i].split("=");
			obj[decodeURIComponent(pair[0])] = decodeURIComponent(pair[1]);
		}
		return obj;
	};

	var TRSST_WELCOME = "8crfxaHcBWTHuhA8cXfwPc3vfJ3SbsRpJ";

	var onInit = function() {

		/* Ensure relative timestamps are "live" and updated */
		window.setInterval(function() {
			$(".entry").each(function() {
				updateRelativeTimestamp($(this));
			});
		}, 60000); // each minute

		/* Initial state is "signed-out" */
		$(document.body).addClass("signed-out");

		/* Don't display accounts until loaded */
		$(document.body).addClass("accounts-loading");
		populateAccountMenu();

		/* Enable account menu clicks */
		$("nav").click(onAccountMenuClick);

		/* Enable back button clicks */
		$(".menu-item.back").click(function() {
			window.history.back();
		});

		/* Reroute all preexisting anchors */
		$("a").click(function(e) {
			var a = $(e.target).closest("a");
			if (a.attr("target") !== "_blank") {
				e.preventDefault();
				var url = a.attr("href");
				if (url) {
					controller.pushState(url);
				}
			}
		});

		/* Enable global search bar */
		$(".util-search form").submit(function(e) {
			e.preventDefault();
			var i;
			var tags = [];
			var mentions = [];
			var query = $(".util-search form input").val();
			if (query) {
				query = query.trim();
				var terms = query.split(" ");
				query = "";

				// extract tags and mentions
				for (i in terms) {
					if (terms[i].indexOf("@") === 0) {
						mentions.push(terms[i].substring(1));
					} else if (terms[i].indexOf("#") === 0) {
						tags.push(terms[i].substring(1));
					} else {
						query = query + terms[i] + " ";
					}
				}
				query = query.trim();

				if (terms.length == 1) {
					// if external feed
					if (query.indexOf("http") === 0) {
						// escape parameterized urns
						if (query.indexOf("?") !== -1) {
							query = encodeURIComponent(query);
						}
						controller.pushState("/" + query);
						return;
					}
					// if feed id
					// FIXME: until we get our js address checker
					if (query.length > 25) {
						// navigate to feed id
						controller.pushState("/" + query);
						return;
					}
				}

				// otherwise: treat as query against current page

				var params = searchToObject();
				delete params.tag;
				delete params.mention;
				delete params.q;
				if ( query.length > 0 ) {
					params.q = query;
				}
				query = "?";
				// reassemble query params
				for (i in params) {
					query = query + i + "=" + params[i] + "&";
				}
				for (i in tags) {
					query = query + "tag=" + tags[i] + "&";
				}
				for (i in mentions) {
					query = query + "mention=" + mentions[i] + "&";
				}
				// strip last & or ?
				query = query.substring(0, query.length - 1);

				// navigate with new query
				controller.pushState(window.location.pathname + query);
			}
		});

		new Composer($(document).find(".private.messaging form"));
		new Composer($("article>.composer").get());
	};

	controller.pushState = function(path) {
		// if any state is set, the back button appears
		var state = model.getAuthenticatedAccountId();
		if (!state) {
			state = ""; // non-null empty string
		}
		window.history.pushState(state, null, path);
		onPopulate();
	};

	controller.openFile = function(string) {
		console.log(string);
		onCreateAccount();// FIXME
		var href = $(string).find("link[rel='self']").attr("href");
		controller.pushState("/" + href);
	};

	var feedRenderer = new EntryRenderer(createElementForEntryData, $("#feedRenderer"));
	var homeRenderer = new EntryRenderer(createElementForEntryData, $("#homeRenderer"));
	var followsRenderer = new FeedRenderer(createElementForFeedData, $("#followsRenderer>div"));
	var followingRenderer = new FeedRenderer(createElementForFeedData, $("#followingRenderer>div"));
	var profileRenderer = new FeedRenderer(createElementForFeedData, $("#profileRenderer"));
	var messageRenderer = new EntryRenderer(createElementForEntryData, $("#messageRenderer"));

	var onPopulate = function() {
		var host = window.location.host;
		var path = window.location.toString();
		var pathname = window.location.pathname;
		
		/* Enable "Open in Browser" */
		$(".util-browser-launcher a").attr("target", "_blank").attr("href", path);

		console.log("onPopulate: " + host + " : " + path);

		var j = path.indexOf(host);
		if (j !== -1) {
			path = path.substring(j + host.length + 1);
		}

		// determine if we're at origin page
		if (window.history.state !== null) {
			$("body").addClass("has-back");
		} else {
			$("body").removeClass("has-back");
		}

		// if we're not on the home page
		if (path.trim().length > 1) {

			var uid = model.getAuthenticatedAccountId();
			var entry = /([^#?]*)\/([0-9a-fA-F]{11})/.exec(pathname);
			if (entry !== null) {

				// we're on a entry page
				$("body").removeClass("page-home");
				$("body").removeClass("page-query");
				$("body").removeClass("page-feed");
				$("body").removeClass("page-external");
				$("body").removeClass("page-self");
				$("body").addClass("page-entry");
				if (uid && uid.indexOf(path) !== -1) {
					$("body").addClass("page-self");
				}
				pathname = pathname.substring(1); // trim leading slash
				var entryRenderer = $("#entryRenderer");
				if (entryRenderer[0].pathname !== pathname) {
					entryRenderer[0].pathname = pathname;
					entryRenderer.empty();
					model.pull({
						feedId : pathname,
						count : 1
					}, function(feedData) {
						$("body").removeClass("pending");
						if (feedData && feedData.length > 0) {
							// replying entry appears under mention entry
							var entryData = $(feedData).children("entry").first();
							createElementForEntryData(feedData, entryData).prependTo(entryRenderer).click();
							// click to expand
						} else {
							console.log("Could not fetch requested entry: " + pathname);
							// TODO: display a not-found message
						}
					});
				}

			} else {
				/* Start progress indicator */
				$("body").addClass("pending");

				// we're on a feed page
				$("body").removeClass("page-home");
				$("body").removeClass("page-query");
				$("body").removeClass("page-entry");
				$("body").removeClass("page-self");
				$("body").addClass("page-feed");
				if (uid && uid.indexOf(path) !== -1) {
					$("body").addClass("page-self");
				}
				if (path.indexOf("?") === 0) {
					$("body").addClass("page-query");
				}

				// if external feed
				if (path.indexOf("http") === 0) {
					$("body").addClass("page-external");
				} else {
					$("body").removeClass("page-external");
				}

				// if this is not the current path
				if (feedRenderer.path !== path) {

					// populate for this feed
					feedRenderer.path = path;

					// page owner
					profileRenderer.reset();
					profileRenderer.addFeed(path);

					// page entries
					feedRenderer.reset();
					feedRenderer.addFeed(path);

					// delay load for recommended feeds
					followsRenderer.reset();
					followsRenderer.addFeedFollows(path);
					if (path !== TRSST_WELCOME) {
						followingRenderer.addFeed(TRSST_WELCOME);
					}

					// update private messaging id
					var privateMessaging = $(document).find(".private.messaging");
					privateMessaging.find("option.private").attr("value", path);

					// page owner conversation
					messageRenderer.reset();
					if (uid) {
						if (uid !== path) {
							// add their entries that mention us
							messageRenderer.addEntries({
								feedId : path,
								mention : uid
							});
						}
						// add our entries that mention them
						// (and our own replies that mention us if same)
						messageRenderer.addEntries({
							feedId : uid,
							mention : path
						});
						// add all encrypted entries
						messageRenderer.addEntries({
							feedId : path,
							verb : "encrypt"
						});
						// only the ones we can decode will show up
					}
				}
			}

		} else {
			// otherwise: we're on the "home" page

			$("body").addClass("page-home");
			$("body").removeClass("page-query");
			$("body").removeClass("page-entry");
			$("body").removeClass("page-external");
			$("body").removeClass("page-feed");
			$("body").removeClass("page-self");

			var id = getCurrentAccountId();
			if (!id) {
				id = TRSST_WELCOME;
			}

			// this is the "home" feed
			profileRenderer.reset();
			profileRenderer.addFeed(id);

			if (homeRenderer.homeRendererId !== id) {
				homeRenderer.homeRendererId = id;

				homeRenderer.reset();
				homeRenderer.addFeed(id);
				if (id !== TRSST_WELCOME) {
					homeRenderer.addFeed(TRSST_WELCOME);
				}
				homeRenderer.addFeedFollows(id);
			}

			// global conversation
			messageRenderer.reset();
			if (id !== TRSST_WELCOME) {
				// add all our mentions
				messageRenderer.addEntries({
					mention : id
				});
			}

			// TESTING: high volume test
			// "http://api.flickr.com/services/feeds/photos_public.gne" );
			// //
		}

		// first time only
		if (!followingRenderer.homeId) {
			window.setTimeout(function() {
				// following renderer permanently shows home's recommendations
				followingRenderer.homeId = TRSST_WELCOME;
				followingRenderer.addFeedFollows(TRSST_WELCOME);
			}, 2000);
		}

		// we can show the account menu now
		$(document.body).removeClass("accounts-loading");
	};
	
	/*
	 * Force-populate all applicable renderers with the specified feed. 
	 */
	controller.forceRender = function(feedData) {
		// used to ensure quick appearance of submitted posts
		homeRenderer.addEntriesFromFeed(feedData, null);
	};

	// + Jonas Raoni Soares Silva
	// @ http://jsfromhell.com/array/shuffle [v1.0]
	var shuffle = function(o) { // v1.0
		for (var j, x, i = o.length; i; j = Math.floor(Math.random() * i), x = o[--i], o[i] = o[j], o[j] = x)
			;
		return o;
	};

	controller.start = function() {
		window.setTimeout(function() {
			window.onpopstate = function(event) {
				onPopulate();
			};
		}, 100);
		onPopulate();
	};

	onInit();

})(window);

$(document).ready(function() {
	controller.start();

	// // firebug
	// if (!document.getElementById('FirebugLite')) {
	// E = document['createElement' + 'NS'] &&
	// document.documentElement.namespaceURI;
	// E = E ? document['createElement' + 'NS'](E, 'script') :
	// document['createElement']('script');
	// E['setAttribute']('id', 'FirebugLite');
	// E['setAttribute']('src', 'https://getfirebug.com/' + 'firebug-lite.js' +
	// '#startOpened');
	// E['setAttribute']('FirebugLite', '4');
	// (document['getElementsByTagName']('head')[0] ||
	// document['getElementsByTagName']('body')[0]).appendChild(E);
	// E = new Image;
	// E['setAttribute']('src', 'https://getfirebug.com/' + '#startOpened');
	// }
});
