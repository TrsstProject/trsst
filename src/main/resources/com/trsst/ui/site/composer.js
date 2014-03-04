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

	/**
	 * Composer manages an entry composer form, submitting its contents to the
	 * model when needed. Variants can just replace or extend this object.
	 */
	Composer = window.Composer = function(form) {
		this.form = $(form);
		var self = this;
		this.form.submit(function(e) {
			e.preventDefault();
			self.onSubmit();
		});
		this.form.find(".submit").click(function(e) {
			e.preventDefault();
			self.form.submit();
		});
		
		var attach = this.form.find(".attach");
		var input = this.form.find("input[type='file']");
		attach.click(function(e) {
			e.preventDefault();
			input[0].value=""; // clear any preexisting value
			input.change();
			input.click();
		});
		input.on('change', function(e) {
			// grab file name so the UI can display it
			var value = input[0].value;
			if ( value !== undefined ) {
				var i = value.lastIndexOf('\\');
				if ( i !== -1 ) {
					value = value.substring(i+1);
				}
				attach.attr("file", value);
			}
		});
	};
	Composer.prototype = new Composer();
	Composer.prototype.constructor = Composer;

	/**
	 * Adds feed to container, replacing any existing element for this feed.
	 * Returns the added feed element.
	 */
	Composer.prototype.onSubmit = function() {
		console.log("onSubmit: ");
		if ("" === this.form.find("textarea").val().trim()) {
			console.log("Not posting because nothing to send.");
		} else {
			var self = this;
			var formData = new FormData(self.form[0]);

			// copy mentions from enclosed reply
			var entry = this.form.closest(".entry");
			if (entry.length === 1) {
				// var mentions =
				// TODO: copy mentions when we display them
				formData.append("mention", entry.attr("entry"));
				formData.append("verb", "reply");
			}

			model.updateFeed(formData, function(feedData) {
				if (feedData) {
					// success
					console.log("updateFeed: result: ");
					console.log(feedData);
					self.form[0].reset();
					self.form.removeClass("error");
				} else {
					// error
					self.form.addClass("error");
				}
			});
		}
	};

})(window);
