cd ..
mkdir ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/Frameworks
cp -R /tmp/trsst-resources/Frameworks/* ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/Frameworks/
cp -R /tmp/trsst-resources/MacOS/* ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/
cp -R /tmp/trsst-resources/Resources/* ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/Resources/
mv ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/trsst* ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/trsstd
mv ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/MacGap ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/trsst
chmod +x ./images/dmg.image/trsst-client-0.2-SNAPSHOT.app/Contents/MacOS/trsst
