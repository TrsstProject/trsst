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

	java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar

Usage:

	post [<id>] [--status <text>] [--encrypt <pubkey>]
	 -a,--attach <file>      Attach the specified file, or - for std input
	 -b,--base <url>         Set base URL for this feed
	 -c,--content <text>     Specify entry content on command line
	 -e,--encrypt <pubkey>   Encrypt entry for specified public key
	 -g,--tag <text>         Add a tag (aka category)
	 -i,--icon <url>         Set as this feed's icon or specify url
	 -k,--key <file>         Use the key store at the specified path
	 -l,--logo <url>         Set as this feed's logo or specify url
	 -m,--mail <email>       Set this feed's author email
	 -n,--name <text>        Set this feed's author name
	    --subtitle <text>    Set this feed's subtitle
	    --vanity <prefix>    Generate feed id with specified prefix
	 -p,--pass <text>        Specify passphrase on the command line
	 -r,--mention <id>       Add a mention (aka reference)
	 -s,--status <text>      Specify status update on command line
	 -t,--title <text>       Set this feed's title
	 -u,--url <url>          Attach the specified url to the new entry
	 -v,--verb <verb>        Specify an activitystreams verb for this entry
	
	pull <id>...
	 -d,--decrypt <id>   Decrypt entries as specified recipient id
	 -h,--host <url>     Set host server for this operation
	
	push <url> <id>...
	
	port <portnumber>
	

Example: start a server.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar port 8181
	Services now available at: http://192.168.1.21:8181/trsst

Example: create a new empty feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post
	
	Starting temporary service at: http://192.168.1.15:51703
	Generating new feed id... 
	New feed id created: 1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/feed.xml
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:trsst="http://trsst.com/spec/0.1" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:base="http://192.168.1.15:51703/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/" xml:space="default">
	  <updated>2013-12-31T22:59:04.938Z</updated>
	  <trsst:sign>MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEA3aN-CC-s-AF9bfKVEHymDysyVr5jv0MfzPhZgpUL7nZP2IFWB6Ep0reX-RjHjoCAryPQvg9CtSO7mYvuv1DTg</trsst:sign>
	  <trsst:encrypt>MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAELKEY57QzrH4bPLpxWeaEwMqy771D1OgsTZ8QEPxOvUZthmRBtMjH_u7Epsfrl-VU-chdBTlZjB5-Pk-DAbllRg</trsst:encrypt>
	  <id>urn:feed:1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN</id>
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
	        <ds:DigestValue>flQs7WOaYbOnv7R+P1cXqCh/pl8=</ds:DigestValue>
	      </ds:Reference>
	    </ds:SignedInfo>
	    <ds:SignatureValue>
	pKxbZwV1oMh3thk5G221LrD0AY2sUEdM2WT7C0BkoRFce0Ml1000CpX7ZKfRAsILDuJbrbJBONRA
	UTxVUU7dPA==
	</ds:SignatureValue>
	  </ds:Signature>
	</feed>

Example: create a new post on a preexisting feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post --status "First Post" 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	
	Starting temporary service at: http://192.168.1.15:51777
	Obtaining keys for feed id: 1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN
	Using existing account id: 1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/feed.xml
	wrote: /Users/mpowers/trsstd/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/1434ae49026.atom
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:base="http://192.168.1.15:51703/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/" xml:space="default">
	  <updated>2013-12-31T23:02:09.702Z</updated>
	  <id>urn:feed:1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN</id>
	  <sign xmlns="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEA3aN-CC-s-AF9bfKVEHymDysyVr5jv0MfzPhZgpUL7nZP2IFWB6Ep0reX-RjHjoCAryPQvg9CtSO7mYvuv1DTg</sign>
	  <encrypt xmlns="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEA3aN-CC-s-AF9bfKVEHymDysyVr5jv0MfzPhZgpUL7nZP2IFWB6Ep0reX-RjHjoCAryPQvg9CtSO7mYvuv1DTg</encrypt>
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
	        <ds:DigestValue>A+ggpFsmDX0HEIdbnmYZpoblXyU=</ds:DigestValue>
	      </ds:Reference>
	    </ds:SignedInfo>
	    <ds:SignatureValue>
	g2ZswWUBw9V4AX2aa9crrL1jZDTfL9Ujaxeio8TBzY7qRGulKruactJMjA8iYdq+Yo94p2/DnWlP
	/weWS78LSA==
	</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
	    <updated>2013-12-31T23:02:09.702Z</updated>
	    <id>urn:entry:1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/1434ae49026</id>
	    <published>2013-12-31T23:02:09.702Z</published>
	    <title type="text">First Post</title>
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
	          <ds:DigestValue>+v4zR9aLrGbMfSbCeFBdoMMFUOw=</ds:DigestValue>
	        </ds:Reference>
	      </ds:SignedInfo>
	      <ds:SignatureValue>
	3m2rfxx9PfaL2MPXMB12ATGnTkxtuFC8fY1f0cvXevPFopC9b/c7RiuZ9fysex0MJVB+z+O4xXrw
	C7qLIIRQsw==
	</ds:SignatureValue>
	    </ds:Signature>
	  </entry>
	</feed>

Example: create an encrypted post on a preexisting feed.

	$ java -jar target/trsst-client-0.2-SNAPSHOT-exe.jar post --status "Secret Post" 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	-- encrypt MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEQn1eOiToQlurH0gIE0AsBTJNBF1jrOSSIr8i4RSRdvx7dtkD1hre0vgPabJMLH9QktK6AYhl31xkf3xqp_mPxw

	Starting temporary service at: http://192.168.1.15:51929
	Obtaining keys for feed id: 1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN
	Using existing account id: 1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/feed.xml
	wrote: /Users/mpowers/trsstd/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/1434ae883ea.atom
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xml:base="http://192.168.1.15:51703/1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/" xml:space="default">
	  <updated>2013-12-31T23:06:28.714Z</updated>
	  <id>urn:feed:1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN</id>
	  <sign xmlns="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEA3aN-CC-s-AF9bfKVEHymDysyVr5jv0MfzPhZgpUL7nZP2IFWB6Ep0reX-RjHjoCAryPQvg9CtSO7mYvuv1DTg</sign>
	  <encrypt xmlns="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEA3aN-CC-s-AF9bfKVEHymDysyVr5jv0MfzPhZgpUL7nZP2IFWB6Ep0reX-RjHjoCAryPQvg9CtSO7mYvuv1DTg</encrypt>
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
	        <ds:DigestValue>vLauOLLIYysNltvUH/U4G0EIkFU=</ds:DigestValue>
	      </ds:Reference>
	    </ds:SignedInfo>
	    <ds:SignatureValue>
	aSJQ7rveKmyaA8ys9+H7vphTxqdwzMMe63JbAVd86anJe6u7/y+GTX3i0X6VmYpKptegCZiMNll1
	F5v0nKxKLQ==
	</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
	    <id>urn:entry:1MbwHVXTVF3qnqgKdiZzN3iguKMxbUakbN/1434ae883ea</id>
	    <updated>2013-12-31T23:06:28.714Z</updated>
	    <published>2013-12-31T23:06:28.714Z</published>
	    <predecessor xmlns="http://trsst.com/spec/0.1">
	3m2rfxx9PfaL2MPXMB12ATGnTkxtuFC8fY1f0cvXevPFopC9b/c7RiuZ9fysex0MJVB+z+O4xXrw
	C7qLIIRQsw==
	</predecessor>
	    <title type="text">Encrypted content</title>
	    <content type="text/xml">
	      <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
	        <CipherData>
	          <CipherValue>BPPENTmBtJChpI0jTaqdTNjrG0ACsM5OsA8a8Sd8zgctLJi8EMM1TPEEqrT3kXoaE1ue5
	          faDzPJay97MNf5dArdGBE0nhHQKadwBHkuM1gsuOXedG31RkKnenMBteVZukGBu8oaRHV-DXyO_K7hkbQH
	          SFW2wZrSfTB9P5POGgOZSwonjQzOSoKBeSwheJa8cECvNbv-LWfwYsYWtO2_-xyQrg9bF1I4Wrx69-7UdY
	          hG8OD6EtY-kT1iaUEU3aTcmSBesc0yLBCSyHWKBRCbI_b5q0-mSHG-ZVM_S_EixIxJkjtktBvFulKs68YC
	          h8D4psDlvas5Zs1zVZYWbBwMZ1xZ6YBwrmgamIiSlhUW0ZFvd1fDsbiyjV6cqeGkv64HFuXbJzFOL7n6AW
	          uHiBQsHlj_8Py1nQBNRDumitnvlCzGUz-kQ9YVfK540s_HlJNsEdR_7aUH7s58JMxDDcVeEjiiexA2mXxy
	          BkZIze4yyFSfQFEH8k-tjmdGcDtANpbScnGcD1TYuJGKWmTcbxuBJ4nSsI7Lo9K7ec7lx-OcHpQ0c3gAHf
	          vTmPRmuFonzwcMLxDWjHiQVGKIM0HoqWEMZXQxUSu6MSj33THcNV-OaBzaWJW8sxK1gU4NkYn7b7QdTatq
	          j7CCoii-SjbvRUR1VmT_apsegIQjgBu6n8VTFcOeKo3PfXo4HVCRtDi5mKdKKC4jL4nrEaAy-CzF0ur2A5
	          yEA8EAfXol12DGYw5jcePSf
	          </CipherValue>
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
	          <ds:DigestValue>ZSLW4QPiHrUFM7bvDdRMO7qPLeM=</ds:DigestValue>
	        </ds:Reference>
	      </ds:SignedInfo>
	      <ds:SignatureValue>
	6mfoXo2TTlMbamVzOPiEpoYtX6w3/M9Cpu5P+bG9hs2s5GbLG49Fk/JloVfAsk/UnSimzmlVJZGB
	BdaQbWXrzQ==
	</ds:SignatureValue>
	    </ds:Signature>
	  </entry>
	</feed>
	

