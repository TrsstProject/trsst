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
	
	Starting temporary service at: http://172.16.2.205:57010/trsst
	Generating new feed id... 
	New feed id created: 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/145qkKDkkeDA93Va4vSLePdmp6okkXeD9U/feed.xml
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xml:space="default">
	  <updated>2013-12-09T23:08:44.314Z</updated>
	  <trsst:sign xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEybZdk0
	syAeJG97fUDiniEqSYgAPx6MJe6RK9iWgxZg-OcKXGi2TYnGfqxdPj13cNJKtfOZaumjTJAGQN-PpdHA</trsst:sign>
	  <trsst:encrypt xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEnIU
	w9aeOS_Q9x8RnMPXhv-2fk5SUIdmUyB2az6HnuxpGNqvnTXmWzwm7Uzjfqs9kNnQTdfNLuotroau-1s6hRw
	</trsst:encrypt>
	  <id>145qkKDkkeDA93Va4vSLePdmp6okkXeD9U</id>
	  <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>QNrutGd65pjt000+xvw+CDA1pS8=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
	ytD5gA1kqJaYtYIRYBZEE/glLI0bDRAeSgxcV/l2jhkPElGJMC5gP6xXYnxEo58ADsGAek+Yd3Uh
	B1uI+ySBFw==
	</ds:SignatureValue>
	  </ds:Signature>
	</feed>

Example: create a new post on a preexisting feed.

	$ java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar post --status "First Post" 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	
	Starting temporary service at: http://172.16.2.205:57111/trsst
	Obtaining keys for feed id: 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	Using existing account id: 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	Default file persistence serving from: /Users/mpowers/trsstd
	wrote: /Users/mpowers/trsstd/145qkKDkkeDA93Va4vSLePdmp6okkXeD9U/b87090b3-b9d7-493f-bc8e-4fb3de8cca94.atom
	wrote: /Users/mpowers/trsstd/145qkKDkkeDA93Va4vSLePdmp6okkXeD9U/feed.xml
	<?xml version="1.0"?>
	<feed xmlns="http://www.w3.org/2005/Atom" xml:space="default" xml:base="http://172.16.2.205:57111/trsst/145qkKDkkeDA93Va4vSLePdmp6okkXeD9U">
	  <updated>2013-12-09T23:14:09.125Z</updated>
	  <id>145qkKDkkeDA93Va4vSLePdmp6okkXeD9U</id>
	  <trsst:sign xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEybZdk0sy
	AeJG97fUDiniEqSYgAPx6MJe6RK9iWgxZg-OcKXGi2TYnGfqxdPj13cNJKtfOZaumjTJAGQN-PpdHA</trsst:sign>
	  <trsst:encrypt xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEybZdk
	0syAeJG97fUDiniEqSYgAPx6MJe6RK9iWgxZg-OcKXGi2TYnGfqxdPj13cNJKtfOZaumjTJAGQN-PpdHA</trsst:encrypt>
	  <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>Takilp02x26eLPzvNBgJU9xXWt8=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
	unU53TJ2atEysm6tsIX1o7BCH6sEzAE3DNC42o7aNrRZAQzbou9aTVGF13JCSpJLmI0MeBPSr/Pg
	4D+nA4UG3g==
	</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
		<id>urn:uuid:b87090b3-b9d7-493f-bc8e-4fb3de8cca94</id>
		<updated>2013-12-09T23:14:09.150Z</updated>
		<published>2013-12-09T23:14:09.150Z</published>
		<title type="text">First Post</title>
		<ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
		  <ds:SignedInfo>
			<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
			<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
			<ds:Reference URI="">
			  <ds:Transforms>
				<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
				<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			  </ds:Transforms>
			  <ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			  <ds:DigestValue>b+TwcgajshNqCNjnFKoZzKJuvTs=</ds:DigestValue>
			</ds:Reference>
		  </ds:SignedInfo>
		  <ds:SignatureValue>
	EjN9cNWzERD6qDOjfggBiun7f1d4ZygYyo+7vmJ84QXiYNHUReSWQSXun5s+lBFsONmvveYzeOyO
	G87DBU/3JA==
	</ds:SignatureValue>
		</ds:Signature>
	  </entry>
	</feed>

Example: create an encrypted post on a preexisting feed.

	$ java -jar target/trsst-client-1.0-SNAPSHOT-exe.jar post --status "Secret Post" 145qkKDkkeDA93Va4vSLePdmp6okkXeD9U
	-- encrypt MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEQn1eOiToQlurH0gIE0AsBTJNBF1jrOSSIr8i4RSRdvx7dtkD1hre0vgPabJMLH9QktK6AYhl31xkf3xqp_mPxw

	<feed xmlns="http://www.w3.org/2005/Atom" xml:space="default" xml:base="http://172.16.2.205:57111/trsst/145qkKDkkeDA93Va4vSLePdmp6okkXeD9U">
	  <updated>2013-12-09T23:19:26.634Z</updated>
	  <id>145qkKDkkeDA93Va4vSLePdmp6okkXeD9U</id>
	  <trsst:sign xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEybZdk0
	syAeJG97fUDiniEqSYgAPx6MJe6RK9iWgxZg-OcKXGi2TYnGfqxdPj13cNJKtfOZaumjTJAGQN-PpdHA</trsst:sign>
	  <trsst:encrypt xmlns:trsst="http://trsst.com/spec/0.1">MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEybZ
	dk0syAeJG97fUDiniEqSYgAPx6MJe6RK9iWgxZg-OcKXGi2TYnGfqxdPj13cNJKtfOZaumjTJAGQN-PpdHA</trsst:encrypt>
	  <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
		<ds:SignedInfo>
		  <ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
		  <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
		  <ds:Reference URI="">
			<ds:Transforms>
			  <ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
			  <ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			</ds:Transforms>
			<ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			<ds:DigestValue>ocn+37zevyma+EXX+VUuxowr1Fk=</ds:DigestValue>
		  </ds:Reference>
		</ds:SignedInfo>
		<ds:SignatureValue>
	XPARFjYxAfwV+Ebe7iSUrStEwQ9LicUO4xzJ+MgjlOIlLcGH/ufT2jNxfQSo1ZThWjJTwQN2ZaCR
	AccDfB/81A==
	</ds:SignatureValue>
	  </ds:Signature>
	  <entry>
		<id>urn:uuid:2aefeebe-1af9-4d10-a18f-f5e7cfab3690</id>
		<updated>2013-12-09T23:19:26.637Z</updated>
		<published>2013-12-09T23:19:26.637Z</published>
		<predecessor xmlns="http://trsst.com/spec/0.1">
	fKMkJGcOTSZ/2dyVSDTgsHBp/fYdNUIb3KMuUBk1U5fyBqEJGP2EEafZZ1Mu0HcbUSQqHoAzTm1e
	OeAuicWzLA==
	</predecessor>
		<title type="text">Encrypted post</title>
		<EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
		  <CipherData>
	<CipherValue>BIRrjeY6aPtsinWxIvkNQA3YlXa-nttTcHrhCDOyiOETN49lv1ZoCQT9RI_jydNlFEKTXGVKL
	_7-g5j7EQFFTb7rhADpWWvN9wRJyka8CwEhJ2Mvzx4GLoiSfG2OAcayMyQXb3hBfw4_vzqGV3lAU97wZesHG2A
	bMyhPqsUbDIMTgr6RG0jh0fMMhy3oHi_Z7YHnjLLYSrsMUBBhAIN-EmIisyFDo4BOqGkqtxX6i_kS1jCAVtCrc
	L9szF4DYFkmJevcoXrzl9Q2gwjN931dTF3Vw5KRfDXYKNPOd0gGqem6taJJtFT9nMc3RArlsCVdYDOBnmXlX5J
	SOqQRbF2bbIf6kkFX08gf-8SoJl7Hbwy8_a-QdOjfFecf8Na1X83g15p8PCY0u7CH87CpAIhdODPW3akAdvfR9
	zlJJ2IoO-acvsaL9d3IzloLMKEL30EVuO3qA8H4A6J6xtNByCvyAnOXWsijBcESEMEWxM6WajcgY3J22nYQlBM
	NFcJujTBg3knT7_9k0Q_VPOO8DCymd1XZjPPVZO4Wh2VYAnb4apwky6TBE9UxmsmB7rDSJJqta2fw5a0D0ZdIK
	poprDrzDx6QGnQhaFrISXmVvXgR5zbuP4mwIja-ey0VfBOBPGxKbl_E2DH0Y2XgLFU34n2AfDsxgbPxTaILe0C
	swV2ZNveu_R7Hj5W7yWvrMTHk</CipherValue>
		  </CipherData>
		</EncryptedData>
		<ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
		  <ds:SignedInfo>
			<ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>
			<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1"/>
			<ds:Reference URI="">
			  <ds:Transforms>
				<ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
				<ds:Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
			  </ds:Transforms>
			  <ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>
			  <ds:DigestValue>xAyuJFnO6HsFe7azJnlh87b3iHw=</ds:DigestValue>
			</ds:Reference>
		  </ds:SignedInfo>
		  <ds:SignatureValue>
	U8VqUKpdr7YIeKTj4ngYj/QVTIiDY5cT2VfKKjzmMm6rCpxSR84g/Wd918r7PubTK7MfUEDLRQj0
	ksrpPRG5WQ==
	</ds:SignatureValue>
		</ds:Signature>
	  </entry>
	</feed>
