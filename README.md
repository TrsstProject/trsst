trsst
=====

Trsst protocol implementation draft.
------------------------------------

This includes a working server and command-line client for review, testing, and compatibility.
This is not intended for consumers or any other regular folk.

Requires Java and Maven.

To build: 

	mvn clean install

To run: 

	java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar

Usage:

	post [<id>] [--status <text>] [--encrypt <pubkey>]
	 -a,--attach <file>      Attach the file at the specified path to the new
							 entry
	 -b,--body <markdown>    Specify entry body on command line
	 -e,--encrypt <pubkey>   Encrypt entry for specified public key
	 -i,--icon <url>         Set this feed's icon url
	 -k,--key <file>         Use the key store at the specified path
	 -l,--logo <url>         Set this feed's logo url
	 -m,--mail <email>       Set this feed's author email
	 -n,--name <text>        Set this feed's author name
		--subtitle <text>    Set this feed's subtitle
	 -p,--pass <text>        Specify passphrase on the command line
	 -s,--status <text>      Specify status update on command line
	 -t,--title <text>       Set this feed's title
	 -u,--url <url>          Attach the specified url to the new entry
	 -v,--verb <verb>        Specify an activitystreams verb for this entry
	 
	pull <id>...
	 -h,--home <url>   Set home service for this operation
	 
	push <url> <id>...
	 -h,--home <url>   Set home service for this operation
	 
	port <portnumber>
	 -?,--help   Display these options

Example: start a server.

	$ java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar port 8181
	Services now available at: http://192.168.1.21:8181/trsst

Example: create a new empty feed.

	$ java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar post
	
	Starting temporary service at: http://192.168.1.21:64664/trsst
	Generating new feed id... 
	Password: 
	Re-type Password: 
	New feed id created: 1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f/feed.xml
	<feed xmlns="http://www.w3.org/2005/Atom" xml:space="default"><updated>2013-12-08T00:43:11.232Z</updated><trsst:sign xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEOgK_Wer_rV7Y3L5JU1QvmxQ8Xmzno3TTTImzSKTZjDLer3ZzFe8q912UXts1B-gPrXokS4Duq4-_Wu-VMBk8BA</trsst:sign><trsst:encrypt xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAE8HtJKnXb-K8lR7XMORNbidNngS--p308bfLNv3nQERdSw4t58AM6PX_EDl3YAutgpSB2HIDK2Eii7c_jJsQFdg</trsst:encrypt><id>1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f</id><ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
	<ds:SignedInfo>
	<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315" />
	<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1" />
	<ds:Reference URI="">
	<ds:Transforms>
	<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature" />
	<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#" />
	</ds:Transforms>
	<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1" />
	<ds:DigestValue>ttRUwqiKbVNRc6JhrkgPIROqTl4=</ds:DigestValue>
	</ds:Reference>
	</ds:SignedInfo>
	<ds:SignatureValue>
	HUZyfFCmcYyFYgWytrz5U8dz8CYko9XRrgHL0ZgRitpD8wzQodgnQLgdMUkzPSUYGTw0e6yiH1Qs
	Ob9MG5RIBQ==
	</ds:SignatureValue>
	
	</ds:Signature></feed>

Example: create a new post on a preexisting feed.

	$ java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar post 1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f --status "First Post"
	Starting temporary service at: http://192.168.1.21:64796/trsst
	Obtaining keys for feed id: 1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f
	Password: 
	Using existing account id: 1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f/3291aaa7-b345-4357-a956-6048d583a200.atom
	wrote: /Users/mpowers/trsstd/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f/feed.xml
	<feed xmlns="http://www.w3.org/2005/Atom" xml:space="default" xml:base="http://192.168.1.21:64796/trsst/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f"><updated>2013-12-08T00:44:17.086Z</updated><id>1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f</id><link href="http://192.168.1.21:64796/trsst/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f?count=25&amp;page=1" rel="next" /><link href="http://192.168.1.21:64796/trsst/1D79i2XamYmkCSK5ygkSUeseBxw4t2EX8f?count=25&amp;page=0" rel="current" /><trsst:sign xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEOgK_Wer_rV7Y3L5JU1QvmxQ8Xmzno3TTTImzSKTZjDLer3ZzFe8q912UXts1B-gPrXokS4Duq4-_Wu-VMBk8BA</trsst:sign><trsst:encrypt xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEOgK_Wer_rV7Y3L5JU1QvmxQ8Xmzno3TTTImzSKTZjDLer3ZzFe8q912UXts1B-gPrXokS4Duq4-_Wu-VMBk8BA</trsst:encrypt><ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
	<ds:SignedInfo>
	<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315" />
	<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1" />
	<ds:Reference URI="">
	<ds:Transforms>
	<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature" />
	<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#" />
	</ds:Transforms>
	<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1" />
	<ds:DigestValue>n8ibTWSxFKO3fcCdxmQArZREVLY=</ds:DigestValue>
	</ds:Reference>
	</ds:SignedInfo>
	<ds:SignatureValue>
	Z4PH+wC1kgzpyIXZLwRaCQ/DZCbsazwboXmJJVhwDslViAXH8qiDNvfdbqzbHJdDCtX1AxHrG7Bq
	3m+PSFtmZA==
	</ds:SignatureValue>
	</ds:Signature><entry><id>3291aaa7-b345-4357-a956-6048d583a200</id><updated>2013-12-08T00:44:17.134Z</updated><published>2013-12-08T00:44:17.134Z</published><title type="text">First Post</title><ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
	<ds:SignedInfo>
	<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315" />
	<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1" />
	<ds:Reference URI="">
	<ds:Transforms>
	<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature" />
	<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#" />
	</ds:Transforms>
	<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1" />
	<ds:DigestValue>Nz8MuoJj0E9O/CxMI6VijPmsfyA=</ds:DigestValue>
	</ds:Reference>
	</ds:SignedInfo>
	<ds:SignatureValue>
	x7joKAmLmdqK+7UeZybV8G00QfdqogyYzREcYckQhpoqjRpLYLhY/6ehcdoCYbN+C7AsWkZVOXk6
	SKEzNkHjkg==
	</ds:SignatureValue>
	
	</ds:Signature></entry></feed>
