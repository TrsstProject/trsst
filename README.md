trsst
=====

What is trsst?
------------------------------------

It depends on who you are.

- For most users, trsst looks like a microblogging social network -- a twitter clone -- where you can follow other people and news feeds.  

- For other users, trsst looks like a stream-style RSS reader with built-in microblog publishing capabilities.  

- And for a few, trsst looks like an extension to the Atom Publishing Protocol where anyone can anonymously create self-signed and/or self-encrypted feeds and entries and publish them to any participating server.  

All of these are correct. 


Download Alpha v0.2
------------------------------------

Current binaries are downloadable here:
https://github.com/TrsstProject/trsst/releases


License
------------------------------------

All Trsst Project source code is licensed under the Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0


Frequently Asked Questions
------------------------------------

The FAQ is currently kept here:
https://github.com/TrsstProject/trsst/wiki/Frequently-Asked-Questions


Development Howto
------------------------------------

Requires Java and Maven.

To build: 

	mvn clean install

To run: 

	java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar

Usage:

	post [<id>] [--status <text>] [--encrypt <pubkey>]
	 -a,--attach             Attach the specified file, or - for std input
	 -b,--base <url>         Set base URL for this feed
	 -c,--content <text>     Specify entry content on command line
	 -e,--encrypt <pubkey>   Encrypt entry for specified public key
	 -g,--tag <text>         Add a tag
	 -i,--icon <url>         Set as this feed's icon or specify url
	 -l,--logo <url>         Set as this feed's logo or specify url
	 -m,--mail <email>       Set this feed's author email
	 -n,--name <text>        Set this feed's author name
	 -p,--pass <text>        Specify passphrase on the command line
	 -r,--mention <id>       Add a mention
	 -s,--status <text>      Specify status update on command line
		--strict             Require SSL certs
		--subtitle <text>    Set this feed's subtitle
	 -t,--title <text>       Set this feed's title
	 -u,--url <url>          Attach the specified url to the new entry
	 -v,--verb <verb>        Specify an activitystreams verb for this entry
		--vanity <prefix>    Generate feed id with specified prefix
		
	pull <id>...
	 -d,--decrypt <id>   Decrypt entries as specified recipient id
	 -h,--host <url>     Set host server for this operation
	 
	push <url> <id>...
	 -d,--decrypt <id>   Decrypt entries as specified recipient id
	 -h,--host <url>     Set host server for this operation
	 
	serve
		--api          Expose client API
		--clear        Turn off SSL
		--gui          Launch embedded GUI
		--port <arg>   Specify port
		--tor          Use TOR (experimental)
	

Example: start a server.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar serve --port 8181
	
	Services now available at: https://192.168.1.5:8181/feed

Example: create a new empty feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post
	
	Starting temporary service at: http://192.168.1.5:51341
	Generating new feed id... 
	New feed id created: M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:trsst="http://trsst.com/spec/0.1" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:space="default">
	  <updated>2014-03-10T20:34:13.440Z</updated>
	  <trsst:sign>
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEvzJUjrJSRS0NHsKq1yHur5xdhH35ao4IVTDF_WuwZti3AiAt2gZ8Sehp83PV8yD9ONlw5-DiXYbgY5PUgJTVcQ</trsst:sign>
	  <trsst:encrypt>
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAE9FJDVs846H1ne1G5upDY8CIgF_HhmCILl-967JRQTjYTzRVwHMz5mFakwOKdYBcea9Q_1wLL1L-nqWznUh_uQg</trsst:encrypt>
	  <id>urn:feed:M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1</id>
	  <ds:Signature>
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>
			GK1D1kLSydLFRXCHJnPk2p9LjUQ=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
		CGjdfB+n6uAjIDQ8UCp+GMyjlP4OazvIwzoHKZ2FL8dRGK7PVfLq/dPaGt2SysHHlmCKNhmvQ2BE
		2+hQAUtQ7g==</ds:SignatureValue>
	  </ds:Signature>
	</feed>

Example: create a new post on a preexisting feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post --status "First Post" M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	
	Starting temporary service at: http://192.168.1.5:51371
	Obtaining keys for feed id: M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	Using existing account id: M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:space="default" xml:base="http://192.168.1.5:51371/M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1">
	  <updated>2014-03-10T20:35:03.379Z</updated>
	  <id>urn:feed:M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1</id>
	  <sign xmlns="http://trsst.com/spec/0.1">
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEvzJUjrJSRS0NHsKq1yHur5xdhH35ao4IVTDF_WuwZti3AiAt2gZ8Sehp83PV8yD9ONlw5-DiXYbgY5PUgJTVcQ</sign>
	  <encrypt xmlns="http://trsst.com/spec/0.1">
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAE9FJDVs846H1ne1G5upDY8CIgF_HhmCILl-967JRQTjYTzRVwHMz5mFakwOKdYBcea9Q_1wLL1L-nqWznUh_uQg</encrypt>
	  <ds:Signature>
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>
			n/4ocrBhhFr/9+uIzmKkGQ0ufbk=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
		68l6RxoG8ZAs3QvRs3HNeQipHE/QYuRD9jhqfZzoJO2PzfdCJ9DU2/XdDEwFBJzp96fJjW/fmQWI
		VEuQa+GBQw==</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
		<updated>2014-03-10T20:35:03.379Z</updated>
		<id>
		urn:entry:M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1:144adb4ae53</id>
		<published>2014-03-10T20:35:03.379Z</published>
		<title type="text">First Post</title>
		<rights type="text">attribution, no derivatives, revoked if
		deleted</rights>
		<ds:Signature>
		  <ds:SignedInfo>
			<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
			<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
			<ds:Reference URI="">
			  <ds:Transforms>
				<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
				<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			  </ds:Transforms>
			  <ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			  <ds:DigestValue>
			  fghpP3mG0nzB5Sj4kyqlNbRGsDY=</ds:DigestValue>
			</ds:Reference>
		  </ds:SignedInfo>
		  <ds:SignatureValue>
		  ys+J0FryCApGD/juC20q9YrbVTIH5wQqmhgvuFmYZdBlhEVpIUg6XaFNbjc4eiAnxMs5r1qACp9n
		  NB1GrL7MuQ==</ds:SignatureValue>
		</ds:Signature>
	  </entry>
	</feed>

Example: create an encrypted post on a preexisting feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post --status "Secret Post" M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	-- encrypt MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEQn1eOiToQlurH0gIE0AsBTJNBF1jrOSSIr8i4RSRdvx7dtkD1hre0vgPabJMLH9QktK6AYhl31xkf3xqp_mPxw

	Starting temporary service at: http://192.168.1.5:51384
	Obtaining keys for feed id: M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	Using existing account id: M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:space="default" xml:base="http://192.168.1.5:51371/M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1">
	  <updated>2014-03-10T20:37:10.244Z</updated>
	  <id>urn:feed:M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1</id>
	  <sign xmlns="http://trsst.com/spec/0.1">
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEvzJUjrJSRS0NHsKq1yHur5xdhH35ao4IVTDF_WuwZti3AiAt2gZ8Sehp83PV8yD9ONlw5-DiXYbgY5PUgJTVcQ</sign>
	  <encrypt xmlns="http://trsst.com/spec/0.1">
	  MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAE9FJDVs846H1ne1G5upDY8CIgF_HhmCILl-967JRQTjYTzRVwHMz5mFakwOKdYBcea9Q_1wLL1L-nqWznUh_uQg</encrypt>
	  <ds:Signature>
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>
			5KBNuLZ9TTbN0UL65hFSjLN+myE=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
		aCS1AhKl29Pdlcgs6SRhI6oNhEWpak1/8Ft8CrgwTF/qjh7Bcy3H7UjiBC97YI2bHZcedV5Z0BRf
		htGt6UCGUQ==</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
		<id>
		urn:entry:M4QovUyLYdyMy2ZB4s7BwLztwYcyJqrJ1:144adb69de4</id>
		<updated>2014-03-10T20:37:10.244Z</updated>
		<published>2014-03-10T20:37:10.244Z</published>
		<predecessor xmlns="http://trsst.com/spec/0.1">
		ys+J0FryCApGD/juC20q9YrbVTIH5wQqmhgvuFmYZdBlhEVpIUg6XaFNbjc4eiAnxMs5r1qACp9n
		NB1GrL7MuQ==</predecessor>
		<title type="text">Encrypted content</title>
		<content type="application/xenc+xml">
		  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
			<CipherData>
			  <CipherValue>
			  BO8MZ3_KSnwnTD3g0apIsP6GnmzF4RrXMhVXV7VgcqBYuITqfAcKwR6s0rjb0BBGRrtxuiiqz6dIDbRGcTSuxVdIgTPcWMvDsixO-I6wnQTT5KXl6_DhoIO_perg9ap0cT-dTi6qS-mKdaN0ADqf3E6vTsgpGTt3h_8mNQCYo9baJbobFgdmzjRi75KIMyXijA</CipherValue>
			</CipherData>
		  </EncryptedData>
		  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
			<CipherData>
			  <CipherValue>
			  BAr8zcyj6SrI0T70_-U44V4edUxB9jNNaNLfFumWrL-yj_DoOm-DcDy4lwW3SnjqeFb-_at-72bb-mPrmaMnbAk6CVVfHDgg4pRDTriBQiCxwX8VDlCA5ileCdX0qi40c2NTgsEGS7IWwmnHwfIGj5_C7bmeyiIEhB9PiU_v7t-KRXrNPblcNarZ3Gbsu71SJw</CipherValue>
			</CipherData>
		  </EncryptedData>
		  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
			<CipherData>
			  <CipherValue>
			  nxTxeHPGnONdAEvpWtClWbA7C5SQsAdjFeleXDnMrOPb8YqTuAy_Uc4d39ycT7aoMmZlH1VWPfWyjJq43azenQbtmluNaw1ABxRvI8yWfNJZYb6fIxDQLL4en_bQ9GW5Z2epJuPU0IxU4gj99YZ_hOjyO5jpOnUO7shoTt8CRvRdtoEqI8QGT8-nmJyIg4wNMeLRTEcQ503dekG8ks6TrFGkrYAa5nlDMxrlAe_2etfr1eBDctgHkk18gCFTjO18Ydjx955DzsbZHqmnqok4eom6SNVdkHXWjQMt4bhWgJWFDjnwpaYoiqcms8upDNope6G98sib8vNX6Muw8liEq787xy4LMLbS6fbMPHzHDYV7yZV1xKA4YRKV99mWkENnhmjGrNjMrV5WVmBg_8VU2h4FicGkYtBl3mPD_Q967-CyTJlwdNWmtrECascazcdSROI7fpB0ZDnE9gdNPUlUC6YACXemjYRCY1OFyOwiVBFX8UhqR9CLsb0KzQtC_WOavNpGneZoPEO3hGVyQusawYb4ESZumuDPLSYkm6TTiRBxxlgCgmWu45zBNidOmDy2ARzq_f-aEozbKyzDJEy7hR-jXWbRrxvVUV24eiJd0gV8c7AEJYT6edXnO2PXbEjR3Itl-JOBec4k8Y2dTkUDXvdO1u1hk6uZ_oD1-RizQVo</CipherValue>
			</CipherData>
		  </EncryptedData>
		</content>
		<ds:Signature>
		  <ds:SignedInfo>
			<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
			<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
			<ds:Reference URI="">
			  <ds:Transforms>
				<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
				<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			  </ds:Transforms>
			  <ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			  <ds:DigestValue>
			  1Izt6hPxvZExmTWiSPT5QbMuoKs=</ds:DigestValue>
			</ds:Reference>
		  </ds:SignedInfo>
		  <ds:SignatureValue>
		  iUFSwxDvxti1hjDFgmux7NPrk3PEEFAnhNnNqlad2VB42iir2Xl8RPcEZA1Sne7Rc51b376E9Iy0
		  qT5shelegQ==</ds:SignatureValue>
		</ds:Signature>
	  </entry>
	</feed>
